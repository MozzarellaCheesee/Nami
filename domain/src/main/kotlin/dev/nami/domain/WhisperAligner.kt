package dev.nami.domain

import dev.nami.core.model.WordTiming

/** On-device word-level forced alignment (whisper.cpp via whisper-rs, arm64-v8a only) - the
 * "точная синхронизация" fallback for tracks where LRCLIB's line-level timing makes the
 * interpolated karaoke sweep land on the wrong word (short lines, long instrumental gaps
 * misread as silence, etc). Real WhisperX (wav2vec2 CTC forced alignment) doesn't cross-compile
 * to Android; this uses whisper.cpp's own token timestamps instead, which is less precise but
 * runs fully on-device with no account/key, matching the rest of the app. */
interface WhisperAligner {
    /** Arm64-v8a only, and the model is a ~500MB one-time download - callers should gate the
     * feature entirely (hide the button) rather than surface a runtime failure. */
    fun isSupported(): Boolean

    fun isModelDownloaded(): Boolean

    /** Downloads ggml-small.bin (multilingual) to app-private storage. [onProgress] is 0f..1f. */
    suspend fun downloadModel(onProgress: (Float) -> Unit): Boolean

    /** Decodes [audioPath] to 16kHz mono PCM and runs whisper.cpp over it - a full track on a
     * phone CPU genuinely takes minutes, [onProgress] (0f..1f) is whisper.cpp's own decode
     * progress so the UI doesn't look hung. Null on any failure (missing model, decode error,
     * unsupported ABI) - caller falls back to the existing interpolated karaoke sweep, same as
     * when LRCLIB has no word-level data at all. */
    suspend fun alignWords(audioPath: String, language: String?, onProgress: (Float) -> Unit = {}): List<WordTiming>?
}
