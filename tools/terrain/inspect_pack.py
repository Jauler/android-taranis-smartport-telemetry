"""Looks into a terrain pack from the command line.

    python inspect_pack.py PACK                      summary of the layers
    python inspect_pack.py PACK LAT LON              terrain at a point
    python inspect_pack.py PACK LAT LON LAT2 LON2 [ANTENNA_HEIGHT [IGNORE_NEAR]]
                                                     line of sight from an antenna at the first
                                                     point to an aircraft above the second one
    python inspect_pack.py PACK --png OUT_PREFIX     quick-look images of the layers (needs Pillow)
"""

import math
import sys

import numpy as np
from pyproj import Transformer

import tpk
from make_test_pack import Reference


def summary(pack):
    print("description:", pack.description)
    print("projection:", pack.projection)
    for layer in pack.layers.values():
        spans = np.diff(layer.index)
        print("layer %-4s cell %5.1f m, %d x %d tiles of %d cells, %d tiles with data, %.1f MB" % (
            layer.name, layer.cell_size, layer.tiles_x, layer.tiles_y, layer.tile_size,
            int((spans > 0).sum()), layer.data_length / 1e6))


def quick_look(pack, prefix):
    from PIL import Image
    for layer in pack.layers.values():
        # one pixel per 16 cells keeps the picture of a whole country manageable
        step = max(layer.tile_size // 16, 1)
        size = layer.tile_size // step
        image = np.zeros((layer.tiles_y * size, layer.tiles_x * size), dtype=np.float32)
        image[:] = np.nan
        for ty in range(layer.tiles_y):
            for tx in range(layer.tiles_x):
                tile = layer.tile(tx, ty)
                if tile is None:
                    continue
                block = tile.astype(np.float32)
                block[tile == layer.nodata] = np.nan
                with np.errstate(all="ignore"):
                    block = np.nanmax(block.reshape(size, step, size, step), axis=(1, 3))
                image[ty * size:(ty + 1) * size, tx * size:(tx + 1) * size] = block
        image = image[::-1]  # north up
        known = ~np.isnan(image)
        low, high = np.nanpercentile(image, 1), np.nanpercentile(image, 99.5)
        gray = np.clip((image - low) / max(high - low, 1e-6), 0, 1) * 230 + 25
        gray[~known] = 0
        path = "%s_%s.png" % (prefix, layer.name)
        Image.fromarray(gray.astype(np.uint8)).save(path)
        print("wrote", path, image.shape, "range %.1f .. %.1f" % (low * layer.scale, high * layer.scale))


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    pack = tpk.PackReader(argv[1])
    if len(argv) == 2:
        summary(pack)
        return 0
    if argv[2] == "--png":
        quick_look(pack, argv[3])
        return 0

    ref = Reference(argv[1])
    to_grid = Transformer.from_crs("EPSG:4326", "EPSG:3346", always_xy=True)
    lat, lon = float(argv[2]), float(argv[3])
    x, y = to_grid.transform(lon, lat)
    ground = ref.ground(x, y)
    print("point %.6f, %.6f (LKS-94 %.1f, %.1f)" % (lat, lon, x, y))
    print("  ground %.1f m, objects %.0f m, populated: %s" % (
        ground, ref.surface(x, y) - ground,
        {-1: "unknown", 0: "no", 1: "nearby (150 m)", 2: "yes"}[ref.populated(x, y, 150.0)]))
    if len(argv) >= 6:
        lat2, lon2 = float(argv[4]), float(argv[5])
        antenna = float(argv[6]) if len(argv) > 6 else 2.0
        ignore = float(argv[7]) if len(argv) > 7 else 50.0
        x2, y2 = to_grid.transform(lon2, lat2)
        los = ref.line_of_sight(x, y, antenna, x2, y2, ignore, 0.0)
        if los is None:
            print("  no terrain data for the antenna or the aircraft position")
            return 1
        ground2 = ref.ground(x2, y2)
        print("aircraft above %.6f, %.6f: ground %.1f m, distance %.0f m" % (lat2, lon2, ground2, los[1]))
        print("  minimum altitude with line of sight: %.0f m above sea level" % los[0])
        print("    = %.0f m above the antenna site, %.0f m above the ground below the aircraft" % (
            los[0] - ground, los[0] - ground2))
        if los[2] < los[1] - 1:
            print("  limited by an obstacle %.0f m from the antenna" % los[2])
        if not los[3]:
            print("  part of the line crosses an area without data")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
