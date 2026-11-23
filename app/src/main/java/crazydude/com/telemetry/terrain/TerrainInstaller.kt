package crazydude.com.telemetry.terrain

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Puts a terrain pack picked by the user where [TerrainLoader] finds it, so that nobody has to
 * copy files into Android/data by hand.
 *
 * The pack is copied under a name the loader does not look at and gets its real name only once
 * it is complete and opens as a terrain pack. Nothing is downloaded.
 */
object TerrainInstaller {

    private const val PACK_NAME = "terrain.tpk"
    private const val IMPORT_TEMP = "terrain.import"

    /**
     * Installs a pack read from [input] (which is closed). [onProgress] is called with the
     * number of bytes copied so far, on the calling thread.
     * @return the description of the installed pack
     */
    @Throws(IOException::class)
    fun installFromStream(context: Context, input: InputStream, onProgress: (Long) -> Unit): String {
        val directory = TerrainLoader.userDirectory(context) ?: throw IOException("Storage is not available")
        val temp = File(directory, IMPORT_TEMP)
        try {
            input.use { source ->
                FileOutputStream(temp).use { target ->
                    val buffer = ByteArray(1 shl 16)
                    var copied = 0L
                    var reported = 0L
                    while (true) {
                        val n = source.read(buffer)
                        if (n < 0) break
                        target.write(buffer, 0, n)
                        copied += n
                        if (copied - reported >= (1 shl 20)) {
                            reported = copied
                            onProgress(copied)
                        }
                    }
                    onProgress(copied)
                }
            }
        } catch (e: IOException) {
            temp.delete()
            throw e
        }
        return install(context, directory, temp)
    }

    /** Makes the complete file [temp] the installed pack, if it is one. */
    @Synchronized
    @Throws(IOException::class)
    private fun install(context: Context, directory: File, temp: File): String {
        val description = try {
            val model = TerrainModel(TerrainPack(ChannelTerrainSource.open(temp)))
            try {
                model.description
            } finally {
                model.close()
            }
        } catch (e: Exception) {
            temp.delete()
            throw IOException(e.message ?: "not a terrain pack")
        }
        TerrainLoader.userPacks(context).forEach { it.delete() }
        val target = File(directory, PACK_NAME)
        if (!temp.renameTo(target)) {
            temp.delete()
            throw IOException("cannot write ${target.absolutePath}")
        }
        // the loader takes the newest pack, and tells packs apart by this time
        target.setLastModified(System.currentTimeMillis())
        return description
    }

    /** True if there is a pack installed by the user (as opposed to one bundled into the APK). */
    fun isInstalled(context: Context): Boolean {
        return TerrainLoader.userPacks(context).isNotEmpty()
    }

    /** Removes the packs installed by the user. */
    @Synchronized
    fun remove(context: Context) {
        TerrainLoader.userPacks(context).forEach { it.delete() }
    }
}
