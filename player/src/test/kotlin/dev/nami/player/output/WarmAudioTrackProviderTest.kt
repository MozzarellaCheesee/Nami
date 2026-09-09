package dev.nami.player.output

import androidx.media3.common.AudioAttributes
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** Проверяем выбор конфигурации для прогрева - ту часть, где живёт вся логика. Сам выигрыш по
 * времени юнит-тестом не проверяется: AudioTrack на JVM не создаётся, эффект слышен только на
 * устройстве. */
@UnstableApi
class WarmAudioTrackProviderTest {

    private fun key(sampleRate: Int) = WarmAudioTrackProvider.Key(
        encoding = 2,
        sampleRate = sampleRate,
        channelConfig = 12,
        tunneling = false,
        offload = false,
        bufferSize = 8192,
        attributes = AudioAttributes.DEFAULT,
        sessionId = 1,
    )

    @Before
    fun reset() = WarmAudioTrackProvider.release()

    @Test
    fun `один формат - греть нечего`() {
        val only = key(44100)
        WarmAudioTrackProvider.observe(only)
        WarmAudioTrackProvider.observe(only)
        assertNull(WarmAudioTrackProvider.warmTarget(only))
    }

    @Test
    fun `после смены формата греется соседний - симметрично в обе стороны`() {
        val a = key(44100)
        val b = key(48000)
        WarmAudioTrackProvider.observe(a)
        WarmAudioTrackProvider.observe(b)
        // играет b - наготове должен быть a (возврат "предыдущий трек")
        assertEquals(a, WarmAudioTrackProvider.warmTarget(b))
        // вернулись на a - наготове снова b (переход "следующий трек")
        WarmAudioTrackProvider.observe(a)
        assertEquals(b, WarmAudioTrackProvider.warmTarget(a))
    }

    @Test
    fun `история не растёт бесконечно`() {
        val keys = listOf(44100, 48000, 88200, 96000, 192000).map(::key)
        keys.forEach(WarmAudioTrackProvider::observe)
        // самый старый вытеснен, греется предпоследний из виденных
        assertEquals(keys[3], WarmAudioTrackProvider.warmTarget(keys[4]))
        WarmAudioTrackProvider.observe(keys[0])
        assertEquals(keys[0], WarmAudioTrackProvider.warmTarget(keys[4]))
    }
}
