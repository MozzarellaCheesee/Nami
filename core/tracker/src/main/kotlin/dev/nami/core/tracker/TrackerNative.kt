package dev.nami.core.tracker

/** Рендер трекерных модулей (libopenmpt) и чиптюнов игровых консолей (game-music-emu) в обычный
 * .wav - см. native/jni/nami_tracker_jni.cpp, почему именно конвертация, а не свой Media3-декодер.
 *
 * Библиотека может отсутствовать в сборке (её собирает ndk-build, а он требует установленного
 * NDK), поэтому загрузка не роняет приложение: [isAvailable] честно скажет "нет", и импорт
 * трекерного файла просто пройдёт как для любого нераспознанного формата. */
object TrackerNative {

    private val loaded = runCatching { System.loadLibrary("namitracker") }.isSuccess

    val isAvailable: Boolean get() = loaded

    /** Синхронный, блокирующий - вызывать с IO-диспетчера. Рендер целого модуля идёт быстрее
     * реального времени, но на длинном .it это всё равно секунды. */
    fun renderToWav(inputPath: String, outputPath: String): Boolean =
        loaded && runCatching { nativeRenderToWav(inputPath, outputPath) }.getOrDefault(false)

    @JvmStatic
    private external fun nativeRenderToWav(inputPath: String, outputPath: String): Boolean

    /** Расширения, которые умеют эти две библиотеки. Список намеренно короткий - самые
     * распространённые из ~50 форматов libopenmpt и всех, что знает gme. */
    val extensions = listOf(
        // libopenmpt
        ".mod", ".xm", ".it", ".s3m", ".mptm", ".mtm", ".669", ".med", ".okt", ".stm", ".umx",
        // game-music-emu
        ".nsf", ".nsfe", ".spc", ".vgm", ".vgz", ".gbs", ".ay", ".gym", ".hes", ".kss", ".sap",
    )
}
