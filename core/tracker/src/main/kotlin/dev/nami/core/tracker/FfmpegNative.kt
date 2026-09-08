package dev.nami.core.tracker

/** APE / WavPack / TAK / Musepack через минимальный FFmpeg - см. native/jni/nami_ffmpeg_jni.cpp
 * и native/jni/build_ffmpeg.sh.
 *
 * Живёт в :core:tracker, а не в отдельном модуле, только потому, что делит с ним весь
 * ndk-build (native/jni/Android.mk) - заводить второй модуль ради одного .so смысла нет.
 *
 * Как и [TrackerNative], молча становится недоступным, если .so не собрана: FFmpeg собирается
 * отдельным скриптом, и на машине без него сборка проекта должна оставаться зелёной. */
object FfmpegNative {

    private val loaded = runCatching { System.loadLibrary("namiffmpeg") }.isSuccess

    val isAvailable: Boolean get() = loaded

    /** Блокирующий - вызывать с IO-диспетчера. */
    fun decodeToWav(inputPath: String, outputPath: String): Boolean =
        loaded && runCatching { nativeDecodeToWav(inputPath, outputPath) }.getOrDefault(false)

    @JvmStatic
    private external fun nativeDecodeToWav(inputPath: String, outputPath: String): Boolean

    /** .mp+ намеренно нет: расширение с плюсом ломается в слишком многих местах (URI, имена
     * файлов на FAT), а встречается почти никогда. */
    val extensions = listOf(".ape", ".wv", ".tak", ".mpc", ".mpp")
}
