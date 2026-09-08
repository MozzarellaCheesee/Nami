package dev.nami.player.waveform

import android.content.Context
import java.io.File
import java.security.MessageDigest

/** On-disk cache for [WaveformScanner] results, keyed by track path (hashed for a safe
 * filename) - without this, every app restart lost the in-memory-only cache and the scrubber
 * showed the placeholder shape again for however long the re-scan took, even for a track that
 * had already been scanned in a previous session. One file per track, 120 bytes (BAR_COUNT
 * bars, one byte each - plenty of precision for a scrubber's visual bars, no reason to spend
 * 4 bytes/float on disk for this). Lives in cacheDir, not filesDir: purely a derived, cheaply
 * recomputable value, safe for the system to clear under storage pressure. */
class WaveformCache(context: Context?) {
    // Lazy, and context is nullable: plain-JVM unit tests construct the owning ViewModel without
    // a real Android Context (no Robolectric in this project), and touching context.cacheDir
    // eagerly would crash them even though those tests never call read()/write() at all.
    private val dir: File? by lazy { context?.let { File(it.cacheDir, "waveforms").apply { mkdirs() } } }

    fun read(path: String): List<Float>? {
        val dir = dir ?: return null
        val file = File(dir, keyFor(path))
        if (!file.exists()) return null
        return try {
            val bytes = file.readBytes()
            if (bytes.size != WaveformScanner.BAR_COUNT) return null
            bytes.map { (it.toInt() and 0xFF) / 255f }
        } catch (e: Exception) {
            null
        }
    }

    fun write(path: String, bars: List<Float>) {
        val dir = dir ?: return
        if (bars.size != WaveformScanner.BAR_COUNT) return
        try {
            val bytes = ByteArray(bars.size) { i -> (bars[i].coerceIn(0f, 1f) * 255f).toInt().toByte() }
            File(dir, keyFor(path)).writeBytes(bytes)
        } catch (e: Exception) {
            // Best-effort - a failed write just means this track re-scans next time, same as
            // never having been cached.
        }
    }

    private fun keyFor(path: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(path.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
