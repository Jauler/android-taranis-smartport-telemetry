package crazydude.com.telemetry.terrain

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileInputStream

/**
 * Finds and opens the terrain pack.
 *
 * A pack in the application's external files directory
 * (Android/data/<package>/files/terrain/<anything>.tpk), put there by [TerrainInstaller] or by
 * hand, takes precedence over the one bundled into the APK as the asset terrain/terrain.tpk.
 */
object TerrainLoader {

    private const val TAG = "Terrain"
    const val DIRECTORY = "terrain"
    const val ASSET_PATH = "terrain/terrain.tpk"
    const val EXTENSION = ".tpk"

    fun userDirectory(context: Context): File? {
        return try {
            context.getExternalFilesDir(DIRECTORY)
        } catch (e: Exception) {
            null
        }
    }

    /** The packs in the external files directory, complete ones only. */
    fun userPacks(context: Context): List<File> {
        return userDirectory(context)
            ?.listFiles()
            ?.filter { it.isFile && it.name.endsWith(EXTENSION, true) }
            ?: emptyList()
    }

    private fun userPack(context: Context): File? {
        return userPacks(context).maxBy { it.lastModified() }
    }

    /** Changes whenever [open] would open something else than before. */
    fun signature(context: Context): String {
        val pack = userPack(context) ?: return "bundled"
        return "${pack.absolutePath}:${pack.length()}:${pack.lastModified()}"
    }

    fun open(context: Context): TerrainModel? {
        val userPack = userPack(context)
        if (userPack != null) {
            try {
                return TerrainModel(TerrainPack(ChannelTerrainSource.open(userPack)))
            } catch (e: Exception) {
                Log.w(TAG, "Cannot open terrain pack ${userPack.name}", e)
            }
        }

        try {
            // The asset is stored uncompressed (see aaptOptions in build.gradle), so it can be
            // read in place straight from the APK
            val descriptor = context.assets.openFd(ASSET_PATH)
            try {
                val channel = FileInputStream(descriptor.fileDescriptor).channel
                val source = ChannelTerrainSource(channel, descriptor.startOffset, descriptor.length, descriptor)
                return TerrainModel(TerrainPack(source))
            } catch (e: Exception) {
                descriptor.close()
                Log.w(TAG, "Cannot open bundled terrain pack", e)
            }
        } catch (e: Exception) {
            Log.i(TAG, "No bundled terrain pack")
        }
        return null
    }
}
