"""Builds the terrain pack of Lithuania (app/src/main/assets/terrain/terrain.tpk) from open data.

    python build_lithuania.py WORKDIR fetch     download the sources (resumable, ~4 GB)
    python build_lithuania.py WORKDIR grids     turn them into country-wide grids
    python build_lithuania.py WORKDIR pack OUT  write the terrain pack
    python build_lithuania.py WORKDIR all OUT

`pack` and `all` take `--obj-cell 10` to keep trees and buildings at the full 10 m resolution
of the grids (a pack of about 250 MB instead of about half of that).

Sources (attribution is required when the pack is distributed, see doc/terrain.md):

  ground      GDR50LT relief grid, (c) Nacionaline zemes tarnyba, CC BY 4.0. Served by the
              geoportal.lt elevation image service; interpolated from 1:50 000 contours.
  trees       Meta / World Resources Institute global canopy height v2, 10 m, CC BY 4.0.
  buildings   Microsoft Global ML Building Footprints (with heights where estimated), ODbL.
  populated   GRPK "Uzstatytos teritorijos" (built-up territories), (c) Nacionaline zemes
              tarnyba, CC BY 4.0, via the geoportal.lt GRPK map service.

Everything is put on one LKS-94 (EPSG:3346) grid:

  dtm  20 m cells, decimeters above sea level
  obj  20 m cells (10 m on request), meters above ground of the highest tree/building in
       the cell
  pop  20 m cells, 0 = not built-up, 1 = isolated buildings (farmsteads), 2 = settlement

None of this is a measured surface model: tree heights are estimated from satellite imagery
(typical error a few meters) and many buildings only get a default height. The national
LiDAR point clouds would be far better but cannot be downloaded without an account; if you
get DSM/DTM rasters made from them, replace the `chm`/`buildings` steps.
"""

import csv
import gzip
import io
import json
import math
import os
import sys
import time

import numpy as np
import rasterio
import requests
from pyproj import Transformer
from rasterio import features
from rasterio.transform import from_origin
from rasterio.warp import Resampling, reproject

import tpk

# ---- output grid (LKS-94 meters) -------------------------------------------------------------
X0, Y0 = 306000.0, 5974000.0     # south-west corner
TILE_M = 2560.0                  # every layer is cut into 2.56 km tiles
TILES_X, TILES_Y = 145, 111      # 371.2 x 284.16 km, covers the whole country
X1, Y1 = X0 + TILES_X * TILE_M, Y0 + TILES_Y * TILE_M

DTM_CELL, OBJ_CELL, POP_CELL = 20.0, 10.0, 20.0
PACK_OBJ_CELL = 20.0             # cell of the tree/building layer in the pack (grids: OBJ_CELL)

DEFAULT_BUILDING_HEIGHT = 8      # meters, for footprints without a height estimate
# GRPK built-up territory codes which count as populated: urbanised territory, industrial
# territory, sports grounds. Left out: cemeteries (vp1), airports (va*), utilities (vg*).
POPULATED_CODES = ("pu0", "pu3", "vk1")
SETTLEMENT_WINDOW_M = 500.0      # a built-up cell belongs to a settlement when at least
SETTLEMENT_DENSITY = 0.10        # this share of the surrounding window is built-up

DTM_URL = "https://www.geoportal.lt/mapproxy/elevation/exportImage"
GRPK_BUILTUP_URL = "https://www.geoportal.lt/mapproxy/rest/services/gisc_grpk/MapServer/23/query"
CHM_URL = "https://dataforgood-fb-data.s3.amazonaws.com/forests/v2/global/dinov3_global_chm_v2_ml3_10m_p95/chm/%s.tif"
CHM_TILES = ["1200333", "1201222", "1201223", "1202111", "1202113",
             "1203000", "1203001", "1203002", "1203003"]
BUILDINGS_INDEX_URL = "https://minedbuildings.z5.web.core.windows.net/global-buildings/dataset-links.csv"

session = requests.Session()
session.headers["User-Agent"] = "terrain-pack-builder/1.0"


def log(*args):
    print(time.strftime("%H:%M:%S"), *args, flush=True)


def grid_shape(cell):
    return int(round((Y1 - Y0) / cell)), int(round((X1 - X0) / cell))


def grid_transform(cell):
    return from_origin(X0, Y1, cell, cell)


def open_grid(work, name, dtype, cell, mode, fill=None):
    """Country-wide grid as a memory mapped file; row 0 is the NORTH edge (raster order)."""
    path = os.path.join(work, name)
    shape = grid_shape(cell)
    if mode == "w+":
        grid = np.lib.format.open_memmap(path, mode="w+", dtype=dtype, shape=shape)
        if fill is not None:
            grid[:] = fill
        return grid
    return np.load(path, mmap_mode=mode)


def download(url, path, retries=5):
    if os.path.exists(path):
        return
    for attempt in range(retries):
        try:
            with session.get(url, stream=True, timeout=120) as r:
                r.raise_for_status()
                with open(path + ".part", "wb") as f:
                    for chunk in r.iter_content(1 << 20):
                        f.write(chunk)
            os.replace(path + ".part", path)
            return
        except Exception as e:  # noqa: network errors of any kind are retried
            log("  retry", attempt + 1, url, e)
            time.sleep(5 * (attempt + 1))
    raise RuntimeError("cannot download " + url)


# ---- fetch -----------------------------------------------------------------------------------

DTM_BLOCK_PX = 4000  # the service accepts at most 15000 x 4100 pixels per request


def dtm_blocks():
    block_m = DTM_BLOCK_PX * DTM_CELL
    for by in range(int(math.ceil((Y1 - Y0) / block_m))):
        for bx in range(int(math.ceil((X1 - X0) / block_m))):
            xmin, ymin = X0 + bx * block_m, Y0 + by * block_m
            xmax, ymax = min(xmin + block_m, X1), min(ymin + block_m, Y1)
            yield bx, by, xmin, ymin, xmax, ymax


def fetch_dtm(work):
    out_dir = os.path.join(work, "dtm")
    os.makedirs(out_dir, exist_ok=True)
    for bx, by, xmin, ymin, xmax, ymax in dtm_blocks():
        path = os.path.join(out_dir, "dtm_%d_%d.tif" % (bx, by))
        if os.path.exists(path):
            continue
        width, height = int(round((xmax - xmin) / DTM_CELL)), int(round((ymax - ymin) / DTM_CELL))
        log("dtm block", bx, by, width, "x", height)
        # Elevations are multiplied by 10 on the server and sent as int16 decimeters
        data = {
            "bbox": "%f,%f,%f,%f" % (xmin, ymin, xmax, ymax), "bboxSR": "3346", "imageSR": "3346",
            "size": "%d,%d" % (width, height), "format": "tiff", "pixelType": "S16",
            "noData": "-32768", "compression": "LZ77", "interpolation": "RSP_BilinearInterpolation",
            "renderingRule": json.dumps({"rasterFunction": "Arithmetic", "rasterFunctionArguments": {
                "Raster": "$$", "Raster2": 10, "Operation": 3}}),
            "f": "image",
        }
        for attempt in range(5):
            r = session.post(DTM_URL, data=data, timeout=300)
            if r.ok and r.content[:2] in (b"II", b"MM"):
                break
            log("  retry", attempt + 1, r.status_code, r.content[:120])
            time.sleep(10 * (attempt + 1))
        else:
            raise RuntimeError("elevation service keeps failing")
        with open(path + ".part", "wb") as f:
            f.write(r.content)
        with rasterio.open(path + ".part") as check:
            assert check.width == width and check.height == height and check.dtypes[0] == "int16"
        os.replace(path + ".part", path)
        time.sleep(3)  # be gentle with the public service


def fetch_chm(work):
    out_dir = os.path.join(work, "chm")
    os.makedirs(out_dir, exist_ok=True)
    for quadkey in CHM_TILES:
        path = os.path.join(out_dir, quadkey + ".tif")
        if not os.path.exists(path):
            log("canopy height tile", quadkey)
            download(CHM_URL % quadkey, path)


def fetch_buildings(work):
    out_dir = os.path.join(work, "buildings")
    os.makedirs(out_dir, exist_ok=True)
    index = session.get(BUILDINGS_INDEX_URL, timeout=120)
    index.raise_for_status()
    rows = [r for r in csv.DictReader(io.StringIO(index.text)) if r["Location"] == "Lithuania"]
    log("building footprint files:", len(rows))
    for row in rows:
        download(row["Url"], os.path.join(out_dir, row["QuadKey"] + ".csv.gz"))


def fetch_builtup(work):
    out_dir = os.path.join(work, "builtup")
    os.makedirs(out_dir, exist_ok=True)
    ids_path = os.path.join(out_dir, "ids.json")
    if not os.path.exists(ids_path):
        r = session.get(GRPK_BUILTUP_URL, params={"where": "1=1", "returnIdsOnly": "true", "f": "json"}, timeout=300)
        r.raise_for_status()
        with open(ids_path, "w") as f:
            json.dump(sorted(r.json()["objectIds"]), f)
    ids = json.load(open(ids_path))
    batch = 500
    log("built-up polygons:", len(ids))
    for start in range(0, len(ids), batch):
        path = os.path.join(out_dir, "part_%06d.json" % start)
        if os.path.exists(path):
            continue
        data = {"objectIds": ",".join(str(i) for i in ids[start:start + batch]), "outFields": "GKODAS",
                "returnGeometry": "true", "outSR": "3346", "f": "json"}
        for attempt in range(5):
            try:
                r = session.post(GRPK_BUILTUP_URL, data=data, timeout=300)
                reply = r.json()
                if "features" in reply and len(reply["features"]) == len(ids[start:start + batch]):
                    break
                log("  retry", attempt + 1, str(reply)[:120])
            except Exception as e:  # noqa
                log("  retry", attempt + 1, e)
            time.sleep(10 * (attempt + 1))
        else:
            raise RuntimeError("GRPK service keeps failing")
        with open(path + ".part", "w") as f:
            json.dump(reply, f)
        os.replace(path + ".part", path)
        if start % 20000 == 0:
            log("  built-up", start, "/", len(ids))
        time.sleep(0.5)


# ---- grids -----------------------------------------------------------------------------------

def grid_dtm(work):
    grid = open_grid(work, "dtm.npy", np.int16, DTM_CELL, "w+", -32768)
    rows = grid.shape[0]
    for bx, by, xmin, ymin, xmax, ymax in dtm_blocks():
        with rasterio.open(os.path.join(work, "dtm", "dtm_%d_%d.tif" % (bx, by))) as src:
            block = src.read(1)
            assert abs(src.bounds.left - xmin) < 0.01 and abs(src.bounds.top - ymax) < 0.01
            assert abs(src.res[0] - DTM_CELL) < 1e-6
        col = int(round((xmin - X0) / DTM_CELL))
        row = rows - int(round((ymax - Y0) / DTM_CELL))
        grid[row:row + block.shape[0], col:col + block.shape[1]] = block
    valid = grid[:] != -32768
    log("dtm: %.1f%% of the grid has data, %.1f .. %.1f m" %
        (100.0 * valid.mean(), grid[:][valid].min() / 10.0, grid[:][valid].max() / 10.0))
    grid.flush()


def grid_chm(work):
    grid = open_grid(work, "chm.npy", np.uint8, OBJ_CELL, "w+", 255)
    rows, cols = grid.shape
    transform = grid_transform(OBJ_CELL)
    sources = [rasterio.open(os.path.join(work, "chm", q + ".tif")) for q in CHM_TILES]
    to_source = Transformer.from_crs("EPSG:3346", sources[0].crs, always_xy=True)
    chunk = 2560
    for r0 in range(0, rows, chunk):
        for c0 in range(0, cols, chunk):
            h, w = min(chunk, rows - r0), min(chunk, cols - c0)
            window_transform = transform * rasterio.Affine.translation(c0, r0)
            # bounds of the chunk in the CRS of the sources (with a margin for the curvature)
            xs = [X0 + (c0 + i) * OBJ_CELL for i in (0, w / 2.0, w)]
            ys = [Y1 - (r0 + i) * OBJ_CELL for i in (0, h / 2.0, h)]
            px, py = to_source.transform([x for x in xs for _ in ys], [y for _ in xs for y in ys])
            left, right, bottom, top = min(px) - 100, max(px) + 100, min(py) - 100, max(py) + 100
            out = np.full((h, w), 255, dtype=np.uint8)
            for src in sources:
                b = src.bounds
                if b.right < left or b.left > right or b.top < bottom or b.bottom > top:
                    continue
                part = np.full((h, w), 255, dtype=np.uint8)
                # The source pixels are ~5.5 m on the ground here; keep the highest per cell
                reproject(rasterio.band(src, 1), part, dst_transform=window_transform, dst_crs="EPSG:3346",
                          src_nodata=255, dst_nodata=255, resampling=Resampling.max)
                known = part != 255
                out = np.where(known & ((out == 255) | (part > out)), part, out)
            grid[r0:r0 + h, c0:c0 + w] = out
        log("chm rows", r0 + chunk, "/", rows)
    for src in sources:
        src.close()
    grid.flush()


def read_buildings(path, to_grid):
    """Returns the exterior rings (Nx2 arrays in grid meters) and the heights (-1: unknown)."""
    rings, heights = [], []
    with gzip.open(path, "rt", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            feature = json.loads(line)
            geometry = feature["geometry"]
            height = float(feature.get("properties", {}).get("height", -1) or -1)
            polygons = [geometry["coordinates"]] if geometry["type"] == "Polygon" else geometry["coordinates"]
            for polygon in polygons:
                rings.append(np.asarray(polygon[0], dtype=np.float64)[:, :2])
                heights.append(height)
    if not rings:
        return [], []
    flat = np.concatenate(rings)
    x, y = to_grid.transform(flat[:, 0], flat[:, 1])
    xy = np.column_stack([x, y])
    out, position = [], 0
    for ring in rings:
        out.append(xy[position:position + len(ring)])
        position += len(ring)
    return out, heights


def burn(grid, cell, shapes, all_touched):
    """Rasterizes (ring, value) pairs into the grid keeping the highest value per cell."""
    if not shapes:
        return
    rows, cols = grid.shape
    xs = np.concatenate([s[0][:, 0] for s in shapes])
    ys = np.concatenate([s[0][:, 1] for s in shapes])
    c0 = max(int((xs.min() - X0) / cell) - 1, 0)
    c1 = min(int((xs.max() - X0) / cell) + 2, cols)
    r0 = max(int((Y1 - ys.max()) / cell) - 1, 0)
    r1 = min(int((Y1 - ys.min()) / cell) + 2, rows)
    if c1 <= c0 or r1 <= r0:
        return
    transform = grid_transform(cell) * rasterio.Affine.translation(c0, r0)
    # later shapes overwrite earlier ones, so draw the highest values last
    ordered = sorted(shapes, key=lambda s: s[1])
    burned = features.rasterize(
        (({"type": "Polygon", "coordinates": [ring.tolist()]}, value) for ring, value in ordered),
        out_shape=(r1 - r0, c1 - c0), transform=transform, fill=0, all_touched=all_touched, dtype=np.uint8)
    grid[r0:r1, c0:c1] = np.maximum(grid[r0:r1, c0:c1], burned)


def grid_buildings(work):
    grid = open_grid(work, "buildings.npy", np.uint8, OBJ_CELL, "w+", 0)
    to_grid = Transformer.from_crs("EPSG:4326", "EPSG:3346", always_xy=True)
    total = with_height = 0
    names = sorted(n for n in os.listdir(os.path.join(work, "buildings")) if n.endswith(".csv.gz"))
    for name in names:
        rings, heights = read_buildings(os.path.join(work, "buildings", name), to_grid)
        shapes = []
        for ring, height in zip(rings, heights):
            if len(ring) < 4:
                continue
            if height > 0:
                with_height += 1
            value = int(min(max(math.ceil(height), 1), 254)) if height > 0 else DEFAULT_BUILDING_HEIGHT
            shapes.append((ring, value))
        total += len(shapes)
        burn(grid, OBJ_CELL, shapes, all_touched=True)
        log("buildings", name, len(shapes))
    log("buildings: %d footprints, %d with a height estimate" % (total, with_height))
    grid.flush()


def box_sum(a, half):
    """Sum over a (2*half+1)^2 window around every cell."""
    integral = np.zeros((a.shape[0] + 1, a.shape[1] + 1), dtype=np.int64)
    integral[1:, 1:] = a.cumsum(axis=0, dtype=np.int64).cumsum(axis=1, dtype=np.int64)
    rows, cols = a.shape
    r0 = np.clip(np.arange(rows) - half, 0, rows)
    r1 = np.clip(np.arange(rows) + half + 1, 0, rows)
    c0 = np.clip(np.arange(cols) - half, 0, cols)
    c1 = np.clip(np.arange(cols) + half + 1, 0, cols)
    return (integral[r1][:, c1] - integral[r0][:, c1] - integral[r1][:, c0] + integral[r0][:, c0])


def dilate(mask):
    out = mask.copy()
    out[1:, :] |= mask[:-1, :]
    out[:-1, :] |= mask[1:, :]
    out[:, 1:] |= mask[:, :-1]
    out[:, :-1] |= mask[:, 1:]
    out[1:, 1:] |= mask[:-1, :-1]
    out[:-1, :-1] |= mask[1:, 1:]
    out[1:, :-1] |= mask[:-1, 1:]
    out[:-1, 1:] |= mask[1:, :-1]
    return out


def grid_populated(work):
    builtup = np.zeros(grid_shape(POP_CELL), dtype=np.uint8)
    codes = {}
    names = sorted(n for n in os.listdir(os.path.join(work, "builtup")) if n.startswith("part_"))
    for name in names:
        reply = json.load(open(os.path.join(work, "builtup", name)))
        shapes = []
        for feature in reply["features"]:
            code = feature["attributes"].get("GKODAS")
            codes[code] = codes.get(code, 0) + 1
            if code not in POPULATED_CODES:
                continue
            for ring in feature.get("geometry", {}).get("rings", []):
                if len(ring) >= 4:
                    # holes are drawn as filled too: a yard inside a block is still the block
                    shapes.append((np.asarray(ring, dtype=np.float64)[:, :2], 1))
        burn(builtup, POP_CELL, shapes, all_touched=False)
    log("built-up polygon codes:", sorted(codes.items(), key=lambda kv: -kv[1]))

    mask = builtup > 0
    half = int(round(SETTLEMENT_WINDOW_M / POP_CELL / 2))
    needed = SETTLEMENT_DENSITY * (2 * half + 1) ** 2
    core = np.zeros_like(mask)
    strip = 2048  # the country-wide grid is processed in strips to keep the memory use low
    for r0 in range(0, mask.shape[0], strip):
        a0, a1 = max(r0 - half, 0), min(r0 + strip + half, mask.shape[0])
        dense = box_sum(mask[a0:a1].astype(np.uint8), half) >= needed
        core[r0:r0 + strip] = (mask[a0:a1] & dense)[r0 - a0:r0 - a0 + strip]
    # close the gaps (streets, yards) between the built-up cells of a settlement
    closed = dilate(dilate(core))
    closed = ~dilate(dilate(~closed))
    settlement = closed | core
    grid = open_grid(work, "pop.npy", np.uint8, POP_CELL, "w+", 0)
    grid[:] = np.where(settlement, 2, np.where(mask, 1, 0)).astype(np.uint8)
    cell_km2 = POP_CELL * POP_CELL / 1e6
    log("populated: built-up %.0f km2, settlements %.0f km2, isolated %.0f km2" %
        (mask.sum() * cell_km2, settlement.sum() * cell_km2, (mask & ~settlement).sum() * cell_km2))
    grid.flush()


# ---- pack ------------------------------------------------------------------------------------

def south_up(block):
    return block[::-1]


def write_pack(work, out_path, obj_cell=PACK_OBJ_CELL):
    dtm = open_grid(work, "dtm.npy", np.int16, DTM_CELL, "r")
    chm = open_grid(work, "chm.npy", np.uint8, OBJ_CELL, "r")
    buildings = open_grid(work, "buildings.npy", np.uint8, OBJ_CELL, "r")
    pop = open_grid(work, "pop.npy", np.uint8, POP_CELL, "r")

    description = "Lithuania %s: GDR50LT, Meta CHM v2, MS buildings, GRPK" % time.strftime("%Y-%m")
    pack = tpk.PackWriter(out_path, description=description)
    n_dtm, n_obj, n_pop = int(TILE_M / DTM_CELL), int(TILE_M / OBJ_CELL), int(TILE_M / POP_CELL)
    coarser = int(round(obj_cell / OBJ_CELL))  # grid cells per pack cell of the object layer
    if coarser < 1 or abs(coarser * OBJ_CELL - obj_cell) > 1e-6 or n_obj % coarser:
        raise ValueError("unsupported object cell size %r" % obj_cell)
    dtm_layer = pack.add_layer("dtm", tpk.DTYPE_I16, n_dtm, DTM_CELL, X0, Y0, TILES_X, TILES_Y, scale=0.1)
    obj_layer = pack.add_layer("obj", tpk.DTYPE_U8, n_obj // coarser, obj_cell, X0, Y0, TILES_X, TILES_Y)
    pop_layer = pack.add_layer("pop", tpk.DTYPE_U8, n_pop, POP_CELL, X0, Y0, TILES_X, TILES_Y)

    for ty in range(TILES_Y):
        top = TILES_Y - 1 - ty  # tile row counted from the north edge of the grids
        for tx in range(TILES_X):
            ground = np.asarray(dtm[top * n_dtm:(top + 1) * n_dtm, tx * n_dtm:(tx + 1) * n_dtm])
            known = ground != -32768
            if not known.any():
                continue
            dtm_layer.put_tile(tx, ty, south_up(ground))

            trees = np.asarray(chm[top * n_obj:(top + 1) * n_obj, tx * n_obj:(tx + 1) * n_obj])
            houses = np.asarray(buildings[top * n_obj:(top + 1) * n_obj, tx * n_obj:(tx + 1) * n_obj])
            objects = np.maximum(np.where(trees == 255, 0, trees), houses)
            objects = np.minimum(objects, 254).astype(np.uint8)
            scale = n_obj // n_dtm
            known_obj = np.repeat(np.repeat(known, scale, axis=0), scale, axis=1)
            objects[~known_obj] = 0
            if coarser > 1:
                # a coarser cell keeps the highest object anywhere inside it
                size = n_obj // coarser
                objects = objects.reshape(size, coarser, size, coarser).max(axis=(1, 3))
                known_obj = known_obj.reshape(size, coarser, size, coarser).any(axis=(1, 3))
            objects[~known_obj] = 255
            obj_layer.put_tile(tx, ty, south_up(objects))

            people = np.asarray(pop[top * n_pop:(top + 1) * n_pop, tx * n_pop:(tx + 1) * n_pop]).copy()
            scale = n_dtm // n_pop
            people[~known[::scale, ::scale]] = 255
            pop_layer.put_tile(tx, ty, south_up(people))
        if ty % 10 == 0:
            log("pack row", ty, "/", TILES_Y)
    counts = dtm_layer.tile_count, obj_layer.tile_count, pop_layer.tile_count
    pack.close()
    log("wrote %s: %.1f MB, tiles dtm/obj/pop = %r" % (out_path, os.path.getsize(out_path) / 1e6, counts))


def main(argv):
    if len(argv) < 3:
        print(__doc__)
        return 2
    obj_cell = PACK_OBJ_CELL
    if "--obj-cell" in argv:
        position = argv.index("--obj-cell")
        obj_cell = float(argv[position + 1])
        argv = argv[:position] + argv[position + 2:]
    work, command = argv[1], argv[2]
    os.makedirs(work, exist_ok=True)
    steps = {
        "fetch-dtm": fetch_dtm, "fetch-chm": fetch_chm, "fetch-buildings": fetch_buildings,
        "fetch-builtup": fetch_builtup,
        "grid-dtm": grid_dtm, "grid-chm": grid_chm, "grid-buildings": grid_buildings,
        "grid-populated": grid_populated,
    }
    if command in steps:
        steps[command](work)
        return 0
    if command in ("fetch", "all"):
        for name in ("fetch-dtm", "fetch-chm", "fetch-buildings", "fetch-builtup"):
            steps[name](work)
    if command in ("grids", "all"):
        for name in ("grid-dtm", "grid-chm", "grid-buildings", "grid-populated"):
            steps[name](work)
    if command in ("pack", "all"):
        if len(argv) < 4:
            print("output path is missing")
            return 2
        write_pack(work, argv[3], obj_cell)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
