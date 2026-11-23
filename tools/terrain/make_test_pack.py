"""Generates the small synthetic terrain pack and the expected values used by the app unit
test (app/src/test/java/crazydude/com/telemetry/terrain/TerrainModelTest.kt).

The expected values come from the straightforward reference implementation below, which
shares no code with the app: projection is done by PROJ and rasters are read back through
the Python pack reader.

    python make_test_pack.py ../../app/src/test/resources/terrain
"""

import math
import os
import sys

import numpy as np
from pyproj import Transformer

import tpk

EFFECTIVE_EARTH_RADIUS = 6371000.0 * 4.0 / 3.0

ORIGIN_X, ORIGIN_Y = 560000.0, 6050000.0
SIZE_X, SIZE_Y = 12800.0, 7680.0  # meters

to_grid = Transformer.from_crs("EPSG:4326", "EPSG:3346", always_xy=True)
to_geo = Transformer.from_crs("EPSG:3346", "EPSG:4326", always_xy=True)


def build(path):
    rng = np.random.default_rng(20261122)
    pack = tpk.PackWriter(path, description="synthetic test terrain")

    # ground: 20 m cells, decimeters. West part hilly, east part (x >= 6.4 km) flat 100.0 m.
    cell, ts = 20.0, 64
    nx, ny = int(SIZE_X / cell), int(SIZE_Y / cell)
    xs = (np.arange(nx) + 0.5) * cell
    ys = (np.arange(ny) + 0.5) * cell
    gx, gy = np.meshgrid(xs, ys)
    hills = 120 + 60 * np.sin(gx / 900.0) * np.cos(gy / 700.0) + 25 * np.sin(gx / 230.0 + gy / 310.0)
    ground = np.where(gx >= 6400.0, 100.0, hills)
    ground_raw = np.round(ground * 10).astype(np.int16)
    ground_raw[:40, :50] = -32768          # a corner without data
    ground_raw[ny - 64:, nx - 64:] = -32768  # one whole tile without data
    layer = pack.add_layer("dtm", tpk.DTYPE_I16, ts, cell, ORIGIN_X, ORIGIN_Y, nx // ts, ny // ts, scale=0.1)
    for ty in range(ny // ts):
        for tx in range(nx // ts):
            layer.put_tile(tx, ty, ground_raw[ty * ts:(ty + 1) * ts, tx * ts:(tx + 1) * ts])

    # objects: 10 m cells, meters. Random "forest" blobs in the west, a single 30 m wall in the east.
    cell, ts = 10.0, 128
    nx, ny = int(SIZE_X / cell), int(SIZE_Y / cell)
    obj = np.zeros((ny, nx), dtype=np.uint8)
    for _ in range(60):
        cx, cy = int(rng.integers(0, 600)), int(rng.integers(0, ny - 60))
        w, h = int(rng.integers(5, 60)), int(rng.integers(5, 60))
        obj[cy:cy + h, cx:cx + w] = rng.integers(8, 32, size=(h, w), dtype=np.uint8)
    obj[:, 900] = 30                        # wall at x = 9000..9010 m
    obj[200:260, 1100:1160] = 55            # a tall block
    obj[ny - 128:, :128] = 255              # one tile without data
    layer = pack.add_layer("obj", tpk.DTYPE_U8, ts, cell, ORIGIN_X, ORIGIN_Y, nx // ts, ny // ts)
    for ty in range(ny // ts):
        for tx in range(nx // ts):
            layer.put_tile(tx, ty, obj[ty * ts:(ty + 1) * ts, tx * ts:(tx + 1) * ts])

    # populated areas: 40 m cells
    cell, ts = 40.0, 32
    nx, ny = int(SIZE_X / cell), int(SIZE_Y / cell)
    pop = np.zeros((ny, nx), dtype=np.uint8)
    pop[60:90, 40:80] = 1
    pop[20:24, 200:260] = 2
    pop[120:150, 60:100] = 2
    pop[100:101, 150:151] = 1
    layer = pack.add_layer("pop", tpk.DTYPE_U8, ts, cell, ORIGIN_X, ORIGIN_Y, nx // ts, ny // ts)
    for ty in range(ny // ts):
        for tx in range(nx // ts):
            layer.put_tile(tx, ty, pop[ty * ts:(ty + 1) * ts, tx * ts:(tx + 1) * ts])
    pack.close()


class Reference:
    """Plain reimplementation of the terrain queries, working in grid coordinates."""

    def __init__(self, path):
        self.pack = tpk.PackReader(path)
        self.dtm = self.pack.layers["dtm"]
        self.obj = self.pack.layers["obj"]
        self.pop = self.pack.layers["pop"]

    @staticmethod
    def nearest(layer, x, y):
        return layer.value_at(math.floor((x - layer.origin_x) / layer.cell_size),
                              math.floor((y - layer.origin_y) / layer.cell_size))

    def ground(self, x, y):
        layer = self.dtm
        fx = (x - layer.origin_x) / layer.cell_size - 0.5
        fy = (y - layer.origin_y) / layer.cell_size - 0.5
        c0, r0 = math.floor(fx), math.floor(fy)
        tx, ty = fx - c0, fy - r0
        v = [layer.value_at(c0, r0), layer.value_at(c0 + 1, r0),
             layer.value_at(c0, r0 + 1), layer.value_at(c0 + 1, r0 + 1)]
        if any(math.isnan(i) for i in v):
            return self.nearest(layer, x, y)
        bottom = v[0] + (v[1] - v[0]) * tx
        top = v[2] + (v[3] - v[2]) * tx
        return bottom + (top - bottom) * ty

    def surface(self, x, y):
        g = self.ground(x, y)
        if math.isnan(g):
            return g
        o = self.nearest(self.obj, x, y)
        return g if math.isnan(o) else g + o

    def populated(self, x, y, radius, min_class=1):
        layer = self.pop
        cell = layer.cell_size
        col = math.floor((x - layer.origin_x) / cell)
        row = math.floor((y - layer.origin_y) / cell)
        here = layer.raw_at(col, row)
        if here == layer.nodata:
            return -1
        if here >= min_class:
            return 2
        reach = int(math.ceil(radius / cell)) if radius > 0 else 0
        for dr in range(-reach, reach + 1):
            for dc in range(-reach, reach + 1):
                v = layer.raw_at(col + dc, row + dr)
                if v < min_class or v == layer.nodata:
                    continue
                x0 = layer.origin_x + (col + dc) * cell
                y0 = layer.origin_y + (row + dr) * cell
                dx = max(x0 - x, x - (x0 + cell), 0.0)
                dy = max(y0 - y, y - (y0 + cell), 0.0)
                if dx * dx + dy * dy <= radius * radius:
                    return 1
        return 0

    def line_of_sight(self, ax, ay, antenna_height, bx, by, ignore_near, margin, wavelength=0.0):
        antenna_ground = self.ground(ax, ay)
        below = self.surface(bx, by)
        if math.isnan(antenna_ground) or math.isnan(below):
            return None
        antenna = antenna_ground + antenna_height
        distance = math.hypot(bx - ax, by - ay)
        required, obstacle_distance, complete = below + margin, distance, True
        step = self.obj.cell_size * 0.5
        samples = int(math.ceil(distance / step))
        for i in range(1, samples):
            t = i / samples
            d = distance * t
            if d < ignore_near:
                continue
            top = self.surface(ax + (bx - ax) * t, ay + (by - ay) * t)
            if math.isnan(top):
                complete = False
                continue
            bulge = d * (distance - d) / (2.0 * EFFECTIVE_EARTH_RADIUS)
            # 60 % of the first Fresnel zone of a radio link of this wavelength
            fresnel = 0.6 * math.sqrt(wavelength * d * (distance - d) / distance) if wavelength > 0 else 0.0
            needed = antenna + (top + margin + fresnel + bulge - antenna) / t
            if needed > required:
                required, obstacle_distance = needed, d
        return required, distance, obstacle_distance, complete


def fmt(v):
    return "nan" if isinstance(v, float) and math.isnan(v) else repr(float(v))


def stable(fn, x, y, tolerance=1e-3):
    """True when the result does not depend on millimeter-level differences of the position,
    i.e. the point is not sitting right on a cell boundary."""
    base = fn(x, y)
    for dx, dy in ((0.003, 0), (-0.003, 0), (0, 0.003), (0, -0.003)):
        other = fn(x + dx, y + dy)
        if isinstance(base, float) and math.isnan(base):
            if not math.isnan(other):
                return False
        elif other != base and not (isinstance(other, float) and abs(other - base) <= tolerance):
            return False
    return True


def vectors(pack_path, out_path):
    ref = Reference(pack_path)
    rng = np.random.default_rng(7)
    lines = []

    def geo(x, y):
        lon, lat = to_geo.transform(x, y)
        return lat, lon

    # projection, including points far from the central meridian
    for lat, lon in ((54.6872, 25.2797), (55.7033, 21.1443), (54.0, 26.8), (56.4, 20.9), (55.0, 24.0)):
        x, y = to_grid.transform(lon, lat)
        lines.append("T %r %r %r %r" % (lat, lon, x, y))

    def random_point(margin=-300.0):
        return (ORIGIN_X + rng.uniform(margin, SIZE_X - margin),
                ORIGIN_Y + rng.uniform(margin, SIZE_Y - margin))

    count = 0
    while count < 400:
        x, y = random_point()
        if not (stable(ref.ground, x, y) and stable(ref.surface, x, y)):
            continue
        lat, lon = geo(x, y)
        lines.append("G %r %r %s %s" % (lat, lon, fmt(ref.ground(x, y)), fmt(ref.surface(x, y))))
        count += 1

    count = 0
    while count < 300:
        x, y = random_point()
        radius = float(rng.choice([0.0, 50.0, 150.0, 300.0]))
        min_class = int(rng.choice([1, 2]))
        if not stable(lambda px, py: ref.populated(px, py, radius, min_class), x, y):
            continue
        lat, lon = geo(x, y)
        lines.append("P %r %r %r %d %d" % (lat, lon, radius, min_class, ref.populated(x, y, radius, min_class)))
        count += 1

    count = 0
    while count < 250:
        ax, ay = random_point(200.0)
        bx, by = random_point(200.0)
        antenna_height = float(rng.choice([2.0, 2.0, 10.0, 100.0]))
        ignore_near = float(rng.choice([0.0, 50.0, 200.0]))
        margin = float(rng.choice([0.0, 0.0, 10.0]))
        base = ref.line_of_sight(ax, ay, antenna_height, bx, by, ignore_near, margin)

        def los(px, py):
            r = ref.line_of_sight(px, py, antenna_height, bx + (px - ax), by + (py - ay), ignore_near, margin)
            return float("nan") if r is None else r[0]

        if not stable(los, ax, ay, tolerance=0.02):
            continue
        alat, alon = geo(ax, ay)
        blat, blon = geo(bx, by)
        if base is None:
            lines.append("L %r %r %r %r %r %r %r nan nan nan 0" %
                         (alat, alon, antenna_height, blat, blon, ignore_near, margin))
        else:
            lines.append("L %r %r %r %r %r %r %r %r %r %r %d" %
                         (alat, alon, antenna_height, blat, blon, ignore_near, margin,
                          base[0], base[1], base[2], 1 if base[3] else 0))
        count += 1

    # Analytic case on the flat eastern part: ground 100 m, antenna 2 m above it at x = 8000,
    # a 30 m wall at x = 9000..9010, aircraft at x = 11000 on the same northing.
    alat, alon = geo(ORIGIN_X + 8000.0, ORIGIN_Y + 5000.0)
    blat, blon = geo(ORIGIN_X + 11000.0, ORIGIN_Y + 5000.0)
    lines.append("W %r %r %r %r" % (alat, alon, blat, blon))

    with open(out_path, "w", newline="\n") as f:
        f.write("\n".join(lines) + "\n")
    return len(lines)


if __name__ == "__main__":
    out_dir = sys.argv[1] if len(sys.argv) > 1 else "."
    os.makedirs(out_dir, exist_ok=True)
    pack_path = os.path.join(out_dir, "test.tpk")
    build(pack_path)
    n = vectors(pack_path, os.path.join(out_dir, "test_vectors.txt"))
    print("wrote %s (%d bytes), %d test vectors" % (pack_path, os.path.getsize(pack_path), n))
