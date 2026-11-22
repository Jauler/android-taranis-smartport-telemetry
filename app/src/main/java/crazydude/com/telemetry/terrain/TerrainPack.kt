package crazydude.com.telemetry.terrain

import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.zip.DataFormatException
import java.util.zip.Inflater
import kotlin.math.floor

/**
 * Random access byte source of a terrain pack. Reads must be safe to call from any thread.
 */
interface TerrainSource : Closeable {
    val size: Long
    @Throws(IOException::class)
    fun readFully(position: Long, dst: ByteArray, offset: Int, length: Int)
}

/**
 * Terrain source backed by a region of a file channel. Used both for plain files and for
 * uncompressed APK assets (where the pack is a region inside the APK file).
 */
class ChannelTerrainSource(
    private val channel: FileChannel,
    private val startOffset: Long,
    override val size: Long,
    private val owner: Closeable?
) : TerrainSource {

    override fun readFully(position: Long, dst: ByteArray, offset: Int, length: Int) {
        if (position < 0 || position + length > size) {
            throw EOFException("Read past the end of terrain pack")
        }
        val buf = ByteBuffer.wrap(dst, offset, length)
        var pos = startOffset + position
        while (buf.hasRemaining()) {
            val n = channel.read(buf, pos)
            if (n < 0) throw EOFException("Unexpected end of terrain pack")
            pos += n
        }
    }

    override fun close() {
        try {
            channel.close()
        } finally {
            owner?.close()
        }
    }

    companion object {
        fun open(file: File): ChannelTerrainSource {
            val raf = RandomAccessFile(file, "r")
            return ChannelTerrainSource(raf.channel, 0, raf.length(), raf)
        }
    }
}

/**
 * One raster layer of a terrain pack: a grid of square cells split into square tiles which are
 * decompressed on demand and kept in a small LRU cache.
 *
 * Cell (col, row) covers x in [originX + col*cellSize, +cellSize) and
 * y in [originY + row*cellSize, +cellSize); rows grow northwards.
 */
class RasterLayer internal constructor(
    private val source: TerrainSource,
    val name: String,
    private val dtype: Int,
    val tileSize: Int,
    val cellSize: Double,
    val originX: Double,
    val originY: Double,
    val tilesX: Int,
    val tilesY: Int,
    private val scale: Float,
    private val offset: Float,
    val nodata: Int,
    private val index: IntArray,
    private val dataOffset: Long,
    private val cacheTiles: Int
) {
    private class Tile(val shorts: ShortArray?, val bytes: ByteArray?, val constant: Int)

    val width: Int = tilesX * tileSize
    val height: Int = tilesY * tileSize

    private val emptyTile = Tile(null, null, nodata)
    private val cache = object : LinkedHashMap<Int, Tile>(cacheTiles * 2, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Tile>?): Boolean {
            return size > cacheTiles
        }
    }
    private var lastKey = -1
    private var lastTile: Tile? = null
    private val inflater = Inflater()

    /** Raw stored value of a cell, or [nodata] when the cell is outside of the covered area. */
    @Synchronized
    fun rawAt(col: Int, row: Int): Int {
        if (col < 0 || row < 0 || col >= width || row >= height) return nodata
        val key = (row / tileSize) * tilesX + (col / tileSize)
        val tile = if (key == lastKey) lastTile!! else tileFor(key)
        val i = (row % tileSize) * tileSize + (col % tileSize)
        return when {
            tile.shorts != null -> tile.shorts[i].toInt()
            tile.bytes != null -> tile.bytes[i].toInt() and 0xFF
            else -> tile.constant
        }
    }

    /** Value of a cell in real units, NaN when unknown. */
    fun valueAt(col: Int, row: Int): Float {
        val raw = rawAt(col, row)
        return if (raw == nodata) Float.NaN else raw * scale + offset
    }

    fun colOf(x: Double): Int = floor((x - originX) / cellSize).toInt()

    fun rowOf(y: Double): Int = floor((y - originY) / cellSize).toInt()

    /** Value of the cell containing the grid point, NaN when unknown. */
    fun nearest(x: Double, y: Double): Float = valueAt(colOf(x), rowOf(y))

    /** Bilinear interpolation between cell centres; falls back to the nearest cell at data edges. */
    fun bilinear(x: Double, y: Double): Float {
        val fx = (x - originX) / cellSize - 0.5
        val fy = (y - originY) / cellSize - 0.5
        val c0 = floor(fx).toInt()
        val r0 = floor(fy).toInt()
        val tx = (fx - c0).toFloat()
        val ty = (fy - r0).toFloat()
        val v00 = valueAt(c0, r0)
        val v10 = valueAt(c0 + 1, r0)
        val v01 = valueAt(c0, r0 + 1)
        val v11 = valueAt(c0 + 1, r0 + 1)
        if (v00.isNaN() || v10.isNaN() || v01.isNaN() || v11.isNaN()) {
            return nearest(x, y)
        }
        val bottom = v00 + (v10 - v00) * tx
        val top = v01 + (v11 - v01) * tx
        return bottom + (top - bottom) * ty
    }

    private fun tileFor(key: Int): Tile {
        val cached = cache[key]
        val tile = if (cached != null) cached else {
            val loaded = try {
                loadTile(key)
            } catch (e: IOException) {
                emptyTile
            } catch (e: DataFormatException) {
                emptyTile
            }
            cache[key] = loaded
            loaded
        }
        lastKey = key
        lastTile = tile
        return tile
    }

    private fun loadTile(key: Int): Tile {
        val start = index[key].toLong() and 0xFFFFFFFFL
        val end = index[key + 1].toLong() and 0xFFFFFFFFL
        val length = (end - start).toInt()
        if (length <= 0) return emptyTile
        val payload = ByteArray(length)
        source.readFully(dataOffset + start, payload, 0, length)
        val cells = tileSize * tileSize
        val codec = payload[0].toInt()
        if (codec == CODEC_CONSTANT) {
            if (length < 3) throw DataFormatException("Truncated constant tile")
            val v = (payload[1].toInt() and 0xFF) or (payload[2].toInt() shl 8)
            return Tile(null, null, v)
        }
        val bytesPerCell = if (dtype == DTYPE_I16) 2 else 1
        val raw = ByteArray(cells * bytesPerCell)
        inflater.reset()
        inflater.setInput(payload, 1, length - 1)
        var done = 0
        while (done < raw.size) {
            val n = inflater.inflate(raw, done, raw.size - done)
            if (n == 0) {
                if (inflater.finished() || inflater.needsInput() || inflater.needsDictionary()) break
            }
            done += n
        }
        if (done != raw.size) throw DataFormatException("Truncated tile data")

        if (dtype == DTYPE_U8) {
            when (codec) {
                CODEC_RAW -> {
                }
                CODEC_U8_DELTA -> {
                    for (r in 0 until tileSize) {
                        var prev = 0
                        val base = r * tileSize
                        for (c in 0 until tileSize) {
                            prev = (prev + raw[base + c]) and 0xFF
                            raw[base + c] = prev.toByte()
                        }
                    }
                }
                else -> throw DataFormatException("Unknown tile codec $codec")
            }
            return Tile(null, raw, 0)
        }

        val out = ShortArray(cells)
        when (codec) {
            CODEC_RAW -> {
                for (i in 0 until cells) {
                    out[i] = ((raw[2 * i].toInt() and 0xFF) or (raw[2 * i + 1].toInt() shl 8)).toShort()
                }
            }
            CODEC_I16_PLANE -> {
                // residuals of the predictor left + up - upleft, zigzag coded, stored as a plane
                // of low bytes followed by a plane of high bytes
                for (r in 0 until tileSize) {
                    val base = r * tileSize
                    for (c in 0 until tileSize) {
                        val i = base + c
                        val zz = (raw[i].toInt() and 0xFF) or ((raw[cells + i].toInt() and 0xFF) shl 8)
                        val residual = (zz ushr 1) xor -(zz and 1)
                        val left = if (c > 0) out[i - 1].toInt() else 0
                        val up = if (r > 0) out[i - tileSize].toInt() else 0
                        val upLeft = if (c > 0 && r > 0) out[i - tileSize - 1].toInt() else 0
                        val predicted = when {
                            c > 0 && r > 0 -> left + up - upLeft
                            c > 0 -> left
                            else -> up
                        }
                        out[i] = (predicted + residual).toShort()
                    }
                }
            }
            else -> throw DataFormatException("Unknown tile codec $codec")
        }
        return Tile(out, null, 0)
    }

    companion object {
        const val DTYPE_U8 = 1
        const val DTYPE_I16 = 2

        const val CODEC_RAW = 0
        const val CODEC_CONSTANT = 1
        const val CODEC_U8_DELTA = 2
        const val CODEC_I16_PLANE = 3
    }
}

/**
 * Reader of the "TPK1" terrain pack container: a header with the grid projection followed by
 * a number of named raster layers. The format is produced by tools/terrain/tpk.py, which also
 * documents the layout.
 */
class TerrainPack(private val source: TerrainSource, cacheTilesPerLayer: Int = 48) : Closeable {

    val projection: TransverseMercator
    val description: String
    private val layers = LinkedHashMap<String, RasterLayer>()

    init {
        val header = ByteArray(HEADER_SIZE)
        if (source.size < HEADER_SIZE) throw IOException("Not a terrain pack: file too short")
        source.readFully(0, header, 0, HEADER_SIZE)
        val h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        if (h.get() != 'T'.toByte() || h.get() != 'P'.toByte() || h.get() != 'K'.toByte() || h.get() != '1'.toByte()) {
            throw IOException("Not a terrain pack: bad magic")
        }
        val version = h.int
        if (version != 1) throw IOException("Unsupported terrain pack version $version")
        val layerCount = h.int
        if (layerCount < 0 || layerCount > 64) throw IOException("Corrupted terrain pack header")
        val projType = h.int
        if (projType != 1) throw IOException("Unsupported terrain pack projection $projType")
        val a = h.double
        val invF = h.double
        val lon0 = h.double
        val lat0 = h.double
        val k0 = h.double
        val fe = h.double
        val fn = h.double
        projection = TransverseMercator(a, invF, lon0, lat0, k0, fe, fn)
        description = readString(header, 72, 64)

        val table = ByteArray(layerCount * LAYER_ENTRY_SIZE)
        source.readFully(HEADER_SIZE.toLong(), table, 0, table.size)
        for (i in 0 until layerCount) {
            val base = i * LAYER_ENTRY_SIZE
            val t = ByteBuffer.wrap(table, base, LAYER_ENTRY_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            val name = readString(table, base, 8)
            t.position(base + 8)
            val dtype = t.int
            val tileSize = t.int
            val cellSize = t.double
            val originX = t.double
            val originY = t.double
            val tilesX = t.int
            val tilesY = t.int
            val scale = t.float
            val offset = t.float
            val nodata = t.int
            t.int // reserved
            val indexOffset = t.long
            val dataOffset = t.long
            if ((dtype != RasterLayer.DTYPE_U8 && dtype != RasterLayer.DTYPE_I16) ||
                tileSize <= 0 || tileSize > 4096 || tilesX <= 0 || tilesY <= 0 ||
                tilesX.toLong() * tilesY > 16000000L || cellSize <= 0.0
            ) {
                throw IOException("Corrupted terrain pack layer '$name'")
            }
            val entries = tilesX * tilesY + 1
            val indexBytes = ByteArray(entries * 4)
            source.readFully(indexOffset, indexBytes, 0, indexBytes.size)
            val index = IntArray(entries)
            ByteBuffer.wrap(indexBytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(index)
            layers[name] = RasterLayer(
                source, name, dtype, tileSize, cellSize, originX, originY, tilesX, tilesY,
                scale, offset, nodata, index, dataOffset, cacheTilesPerLayer
            )
        }
    }

    fun layer(name: String): RasterLayer? = layers[name]

    val layerNames: Set<String>
        get() = layers.keys

    override fun close() {
        source.close()
    }

    private fun readString(bytes: ByteArray, offset: Int, maxLength: Int): String {
        var end = offset
        while (end < offset + maxLength && bytes[end].toInt() != 0) end++
        return String(bytes, offset, end - offset, Charsets.UTF_8)
    }

    companion object {
        const val HEADER_SIZE = 136
        const val LAYER_ENTRY_SIZE = 96

        const val LAYER_GROUND = "dtm"
        const val LAYER_OBJECTS = "obj"
        const val LAYER_POPULATED = "pop"
    }
}
