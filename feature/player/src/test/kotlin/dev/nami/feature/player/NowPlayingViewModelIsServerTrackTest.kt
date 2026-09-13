package dev.nami.feature.player

import org.junit.Test
import kotlin.test.assertEquals

/**
 * Задача "скачанный серверный трек должен становиться полностью локальным": кнопка "скачать"
 * не пропадала, потому что isServerTrack смотрел на префикс id ("server_..."), а он остаётся
 * прежним и после того, как трек стал локальным (меняется только path строки). Теперь решение
 * принимается по пути настоящего Track (currentTrackDetails), а не по id.
 *
 * Тестируется вынесенная чистая функция isServerTrack(id, path) - прогонять через неё весь
 * NowPlayingViewModel в plain JUnit нельзя: его init запускает WaveformScanner, который дёргает
 * настоящий android.media.MediaExtractor, не подменённый в этом окружении.
 */
class NowPlayingViewModelIsServerTrackTest {

    @Test
    fun `server mirror not yet downloaded is a server track`() {
        assertEquals(true, isServerTrack("server_1", "nami-server://1"))
    }

    @Test
    fun `downloaded server mirror with a real local path is no longer a server track`() {
        // id остаётся "server_1" (плейлисты и история ссылаются на него), но path сменился на
        // реальный файл после downloadTrack - именно это раньше не отражалось на кнопке.
        assertEquals(false, isServerTrack("server_1", "/data/music/real.mp3"))
    }

    @Test
    fun `jam track missing from the library is still a server track`() {
        assertEquals(true, isServerTrack("jam_1", null))
    }

    @Test
    fun `local track is never a server track`() {
        assertEquals(false, isServerTrack("42", "/data/music/local.flac"))
    }
}
