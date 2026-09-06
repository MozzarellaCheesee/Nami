package dev.nami.core.whisperalign

import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.nami.core.model.WordTiming
import dev.nami.domain.WhisperAligner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

private const val MODEL_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin"
private const val MODEL_FILE_NAME = "ggml-small.bin"

@Singleton
class WhisperAlignerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : WhisperAligner {

    private val modelFile: File
        get() = File(context.filesDir, "whisper/$MODEL_FILE_NAME")

    override fun isSupported(): Boolean =
        Build.SUPPORTED_64_BIT_ABIS.contains("arm64-v8a")

    override fun isModelDownloaded(): Boolean = modelFile.exists() && modelFile.length() > 0

    override suspend fun downloadModel(onProgress: (Float) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val target = modelFile
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "$MODEL_FILE_NAME.part")
        try {
            val connection = URL(MODEL_URL).openConnection() as HttpURLConnection
            connection.connect()
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return@withContext false
            val total = connection.contentLengthLong
            var downloaded = 0L
            connection.inputStream.use { input ->
                tmp.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (total > 0) onProgress(downloaded.toFloat() / total)
                    }
                }
            }
            tmp.renameTo(target)
            true
        } catch (e: Exception) {
            tmp.delete()
            false
        }
    }

    override suspend fun alignWords(audioPath: String, language: String?, onProgress: (Float) -> Unit): List<WordTiming>? =
        withContext(Dispatchers.Default) {
            if (!isSupported() || !isModelDownloaded()) return@withContext null
            val pcm = AudioPcmDecoder.decodeTo16kMono(audioPath) ?: return@withContext null
            // whisper.cpp's own progress lives in a global (single-alignment-at-a-time) counter
            // on the Rust side -- poll it from a side coroutine while the blocking JNI call runs,
            // a full track on a phone CPU takes minutes and the UI would otherwise look hung.
            val pollJob = launch {
                while (isActive) {
                    onProgress(uniffi.whisper_align.getAlignProgress() / 100f)
                    delay(400)
                }
            }
            try {
                uniffi.whisper_align.alignWords(modelFile.absolutePath, pcm.toList(), language)
                    .map { WordTiming(it.word, it.startMs, it.endMs) }
                    .takeIf { it.isNotEmpty() }
            } catch (e: Exception) {
                // Rust panics surface here as exceptions from the generated JNI layer.
                null
            } finally {
                pollJob.cancel()
            }
        }
}
