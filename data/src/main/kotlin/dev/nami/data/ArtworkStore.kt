package dev.nami.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ArtworkStore @Inject constructor(@ApplicationContext private val context: Context) {
    fun save(albumId: String, bytes: ByteArray): String? {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val artworkDir = File(context.filesDir, "artwork").apply { mkdirs() }
        val destination = File(artworkDir, "${albumId}_full.webp")
        // WEBP_LOSSY needs API 30+; minSdk here is 26, so fall back to the older
        // (deprecated but still functional) WEBP constant on pre-R devices.
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }
        destination.outputStream().use { output ->
            bitmap.compress(format, 90, output)
        }
        return destination.path
    }
}
