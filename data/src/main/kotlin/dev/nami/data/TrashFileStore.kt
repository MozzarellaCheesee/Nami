package dev.nami.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrashFileStore @Inject constructor(@ApplicationContext private val context: Context) {

    fun moveToTrash(trackId: String, sourcePath: String): String? {
        val source = File(sourcePath)
        val trashDir = File(context.filesDir, "trash")
        return moveTrackFile(source, trashDir, trackId)
    }

    fun restoreFromMusic(trackId: String, trashedPath: String): String? {
        val source = File(trashedPath)
        val musicDir = File(context.filesDir, "music")
        return moveTrackFile(source, musicDir, trackId)
    }

    fun deletePermanently(path: String) {
        File(path).delete()
    }
}

internal fun moveTrackFile(source: File, destinationDir: File, trackId: String): String? {
    if (!source.exists()) return null
    destinationDir.mkdirs()
    val destination = File(destinationDir, trackId + (source.extension.takeIf { it.isNotEmpty() }?.let { ".$it" } ?: ""))
    return if (source.renameTo(destination)) destination.path else null
}
