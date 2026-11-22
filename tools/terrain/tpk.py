"""Reader/writer of the TPK1 terrain pack used by the app for offline terrain queries.

The app-side reader is app/src/main/java/crazydude/com/telemetry/terrain/TerrainPack.kt;
keep the two in sync.

Layout (everything little-endian):

    header, 136 bytes
        0   4s   magic "TPK1"
        4   u32  version (1)
        8   u32  layer count
        12  u32  projection type (1 = Transverse Mercator)
        16  f64  ellipsoid semi-major axis
        24  f64  ellipsoid inverse flattening
        32  f64  central meridian, degrees
        40  f64  latitude of origin, degrees
        48  f64  scale factor
        56  f64  false easting
        64  f64  false northing
        72  64s  free-form description, UTF-8, zero padded
    layer table, 96 bytes per layer
        0   8s   name, zero padded ("dtm", "obj", "pop")
        8   u32  sample type (1 = u8, 2 = i16)
        12  u32  tile size in cells (tiles are square)
        16  f64  cell size in meters
        24  f64  x of the west edge of column 0
        32  f64  y of the south edge of row 0 (rows grow northwards)
        40  u32  tiles in x
        44  u32  tiles in y
        48  f32  scale   (value = raw * scale + offset)
        52  f32  offset
        56  i32  raw value meaning "no data"
        60  u32  reserved
        64  u64  file offset of the tile index
        72  u64  file offset of the tile data
        80  u64  length of the tile data
        88  u64  reserved
    per layer: tile index, (tilesX * tilesY + 1) x u32
        Offsets of the tile payloads relative to the start of the layer's tile data, in
        row-major order starting with the south-west tile. Payload i spans
        index[i] .. index[i + 1]; an empty span means the whole tile has no data.
    per layer: tile payloads
        byte 0 is the codec, the rest depends on it:
        0  zlib stream of the raw samples
        1  constant tile: one i16 with the value of every cell
        2  u8 only: zlib stream of horizontal differences (modulo 256, row start = 0)
        3  i16 only: zlib stream of the residuals of the predictor left + up - upleft
           (first row: left, first column: up, first cell: 0), computed modulo 2^16,
           zigzag coded and stored as a plane of low bytes followed by a plane of high bytes
"""

import os
import struct
import zlib

import numpy as np

MAGIC = b"TPK1"
HEADER_SIZE = 136
LAYER_ENTRY_SIZE = 96

DTYPE_U8 = 1
DTYPE_I16 = 2

CODEC_RAW = 0
CODEC_CONSTANT = 1
CODEC_U8_DELTA = 2
CODEC_I16_PLANE = 3

_NP_TYPES = {DTYPE_U8: np.uint8, DTYPE_I16: np.int16}

# LKS-94 / Lithuania TM (EPSG:3346): GRS80, central meridian 24E, k = 0.9998, FE = 500 km
LKS94 = dict(a=6378137.0, inv_f=298.257222101, lon0=24.0, lat0=0.0, k0=0.9998, fe=500000.0, fn=0.0)


def encode_tile(tile, dtype, level=9):
    """Encodes one tile (2D array, row 0 = southmost) into a payload."""
    tile = np.ascontiguousarray(tile, dtype=_NP_TYPES[dtype])
    first = tile.flat[0]
    if (tile == first).all():
        return struct.pack("<Bh", CODEC_CONSTANT, int(first))

    best = bytes([CODEC_RAW]) + zlib.compress(tile.astype("<" + tile.dtype.str[1:]).tobytes(), level)
    if dtype == DTYPE_U8:
        delta = tile.copy()
        delta[:, 1:] = tile[:, 1:] - tile[:, :-1]  # uint8 arithmetic wraps modulo 256
        alt = bytes([CODEC_U8_DELTA]) + zlib.compress(delta.tobytes(), level)
    else:
        v = tile.astype(np.int32)
        predicted = np.zeros_like(v)
        predicted[1:, 1:] = v[1:, :-1] + v[:-1, 1:] - v[:-1, :-1]
        predicted[0, 1:] = v[0, :-1]
        predicted[1:, 0] = v[:-1, 0]
        residual = ((v - predicted + 32768) & 0xFFFF) - 32768
        zigzag = ((residual << 1) ^ (residual >> 15)) & 0xFFFF
        planes = (zigzag & 0xFF).astype(np.uint8).tobytes() + (zigzag >> 8).astype(np.uint8).tobytes()
        alt = bytes([CODEC_I16_PLANE]) + zlib.compress(planes, level)
    return alt if len(alt) < len(best) else best


def decode_tile(payload, dtype, tile_size):
    """Inverse of encode_tile; mirrors the Kotlin implementation."""
    np_type = _NP_TYPES[dtype]
    codec = payload[0]
    if codec == CODEC_CONSTANT:
        return np.full((tile_size, tile_size), struct.unpack("<h", payload[1:3])[0]).astype(np_type)
    raw = zlib.decompress(payload[1:])
    if codec == CODEC_RAW:
        return np.frombuffer(raw, dtype="<" + np.dtype(np_type).str[1:]).reshape(tile_size, tile_size).copy()
    if codec == CODEC_U8_DELTA and dtype == DTYPE_U8:
        delta = np.frombuffer(raw, dtype=np.uint8).reshape(tile_size, tile_size)
        return np.cumsum(delta, axis=1, dtype=np.uint8)
    if codec == CODEC_I16_PLANE and dtype == DTYPE_I16:
        cells = tile_size * tile_size
        planes = np.frombuffer(raw, dtype=np.uint8).astype(np.int32)
        zigzag = (planes[:cells] | (planes[cells:] << 8)).reshape(tile_size, tile_size)
        residual = (zigzag >> 1) ^ -(zigzag & 1)
        # undo the predictor: first column is a running sum, then each row is a running sum of
        # the residual plus the vertical difference of the row above
        out = np.zeros((tile_size, tile_size), dtype=np.int64)
        out[:, 0] = np.cumsum(residual[:, 0])
        out[0, :] = np.cumsum(residual[0, :])
        for r in range(1, tile_size):
            # v[r,c] = v[r,c-1] + v[r-1,c] - v[r-1,c-1] + res[r,c]
            up_diff = out[r - 1, 1:] - out[r - 1, :-1]
            out[r, 1:] = out[r, 0] + np.cumsum(up_diff + residual[r, 1:])
        return (((out + 32768) & 0xFFFF) - 32768).astype(np.int16)
    raise ValueError("unknown tile codec %d" % codec)


class LayerWriter:
    def __init__(self, pack, name, dtype, tile_size, cell_size, origin_x, origin_y,
                 tiles_x, tiles_y, scale=1.0, offset=0.0, nodata=None):
        if nodata is None:
            nodata = 255 if dtype == DTYPE_U8 else -32768
        self.name = name
        self.dtype = dtype
        self.tile_size = tile_size
        self.cell_size = float(cell_size)
        self.origin_x = float(origin_x)
        self.origin_y = float(origin_y)
        self.tiles_x = tiles_x
        self.tiles_y = tiles_y
        self.scale = scale
        self.offset = offset
        self.nodata = nodata
        self._tmp_path = "%s.%s.tmp" % (pack.path, name)
        self._tmp = open(self._tmp_path, "w+b")
        self._tiles = {}  # tile key -> (offset in tmp file, length)

    def put_tile(self, tx, ty, tile):
        """Stores a tile; `tile` is (tile_size, tile_size) with row 0 at the SOUTH edge."""
        if not (0 <= tx < self.tiles_x and 0 <= ty < self.tiles_y):
            raise ValueError("tile %d,%d outside of layer %s" % (tx, ty, self.name))
        if tile.shape != (self.tile_size, self.tile_size):
            raise ValueError("bad tile shape %r" % (tile.shape,))
        tile = np.asarray(tile, dtype=_NP_TYPES[self.dtype])
        if (tile == self.nodata).all():
            self._tiles.pop(ty * self.tiles_x + tx, None)
            return
        payload = encode_tile(tile, self.dtype)
        self._tmp.seek(0, os.SEEK_END)
        self._tiles[ty * self.tiles_x + tx] = (self._tmp.tell(), len(payload))
        self._tmp.write(payload)

    @property
    def tile_count(self):
        return len(self._tiles)


class PackWriter:
    def __init__(self, path, projection=LKS94, description=""):
        self.path = path
        self.projection = projection
        self.description = description
        self.layers = []

    def add_layer(self, name, dtype, tile_size, cell_size, origin_x, origin_y, tiles_x, tiles_y,
                  scale=1.0, offset=0.0, nodata=None):
        layer = LayerWriter(self, name, dtype, tile_size, cell_size, origin_x, origin_y,
                            tiles_x, tiles_y, scale, offset, nodata)
        self.layers.append(layer)
        return layer

    def close(self):
        p = self.projection
        with open(self.path + ".part", "wb") as out:
            out.write(struct.pack("<4sIII7d64s", MAGIC, 1, len(self.layers), 1,
                                  p["a"], p["inv_f"], p["lon0"], p["lat0"], p["k0"], p["fe"], p["fn"],
                                  self.description.encode("utf-8")[:63]))
            assert out.tell() == HEADER_SIZE
            table_pos = out.tell()
            out.write(b"\0" * (LAYER_ENTRY_SIZE * len(self.layers)))
            entries = []
            for layer in self.layers:
                count = layer.tiles_x * layer.tiles_y
                index = np.zeros(count + 1, dtype="<u4")
                index_pos = out.tell()
                out.write(index.tobytes())
                data_pos = out.tell()
                position = 0
                for key in range(count):
                    index[key] = position
                    span = layer._tiles.get(key)
                    if span is not None:
                        layer._tmp.seek(span[0])
                        out.write(layer._tmp.read(span[1]))
                        position += span[1]
                if position >= 2 ** 32:
                    raise ValueError("layer %s is larger than 4 GB" % layer.name)
                index[count] = position
                end_pos = out.tell()
                out.seek(index_pos)
                out.write(index.tobytes())
                out.seek(end_pos)
                entries.append(struct.pack(
                    "<8sIIdddIIffiIQQQQ", layer.name.encode("ascii"), layer.dtype, layer.tile_size,
                    layer.cell_size, layer.origin_x, layer.origin_y, layer.tiles_x, layer.tiles_y,
                    layer.scale, layer.offset, layer.nodata, 0, index_pos, data_pos, position, 0))
            out.seek(table_pos)
            for entry in entries:
                assert len(entry) == LAYER_ENTRY_SIZE
                out.write(entry)
        for layer in self.layers:
            layer._tmp.close()
            os.remove(layer._tmp_path)
        os.replace(self.path + ".part", self.path)


class LayerReader:
    def __init__(self, f, entry):
        (name, self.dtype, self.tile_size, self.cell_size, self.origin_x, self.origin_y,
         self.tiles_x, self.tiles_y, self.scale, self.offset, self.nodata, _,
         index_pos, self.data_pos, self.data_length, _) = struct.unpack("<8sIIdddIIffiIQQQQ", entry)
        self.name = name.rstrip(b"\0").decode("ascii")
        self._f = f
        f.seek(index_pos)
        self.index = np.frombuffer(f.read(4 * (self.tiles_x * self.tiles_y + 1)), dtype="<u4").astype(np.int64)
        self._cache = {}

    def tile(self, tx, ty):
        """Returns the decoded tile or None when it has no data."""
        key = ty * self.tiles_x + tx
        if key not in self._cache:
            if len(self._cache) > 256:
                self._cache.clear()
            start, end = self.index[key], self.index[key + 1]
            if end == start:
                self._cache[key] = None
            else:
                self._f.seek(self.data_pos + start)
                self._cache[key] = decode_tile(self._f.read(end - start), self.dtype, self.tile_size)
        return self._cache[key]

    def raw_at(self, col, row):
        if not (0 <= col < self.tiles_x * self.tile_size and 0 <= row < self.tiles_y * self.tile_size):
            return self.nodata
        t = self.tile(col // self.tile_size, row // self.tile_size)
        if t is None:
            return self.nodata
        return int(t[row % self.tile_size, col % self.tile_size])

    def value_at(self, col, row):
        raw = self.raw_at(col, row)
        if raw == self.nodata:
            return float("nan")
        return float(np.float32(raw) * np.float32(self.scale) + np.float32(self.offset))


class PackReader:
    def __init__(self, path):
        self._f = open(path, "rb")
        header = self._f.read(HEADER_SIZE)
        magic, version, count, proj_type, a, inv_f, lon0, lat0, k0, fe, fn, description = \
            struct.unpack("<4sIII7d64s", header)
        if magic != MAGIC or version != 1 or proj_type != 1:
            raise ValueError("not a TPK1 terrain pack")
        self.projection = dict(a=a, inv_f=inv_f, lon0=lon0, lat0=lat0, k0=k0, fe=fe, fn=fn)
        self.description = description.rstrip(b"\0").decode("utf-8")
        table = self._f.read(LAYER_ENTRY_SIZE * count)
        self.layers = {}
        for i in range(count):
            layer = LayerReader(self._f, table[i * LAYER_ENTRY_SIZE:(i + 1) * LAYER_ENTRY_SIZE])
            self.layers[layer.name] = layer

    def close(self):
        self._f.close()
