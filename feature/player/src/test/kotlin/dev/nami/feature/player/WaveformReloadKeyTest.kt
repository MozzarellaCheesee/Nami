package dev.nami.feature.player

import dev.nami.core.model.Track
import dev.nami.core.model.TrackId
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class WaveformReloadKeyTest {

    private fun track(path: String, waveform: List<Float>? = null) = Track(
        id = TrackId("t1"),
        title = "Песня",
        artistId = null,
        albumId = null,
        durationMs = 1000,
        path = path,
        format = "flac",
        sizeBytes = 1,
        dateAdded = 0,
        waveform = waveform,
    )

    @Test
    fun `waveform appearing restarts the load`() {
        // Расчёт идёт в фоне и дописывает волну в базу уже после открытия экрана. Путь при этом
        // не меняется, и пока ключ считался только по нему, волна появлялась лишь после
        // переключения на другой трек и обратно.
        val before = waveformReloadKey(track("/музыка/а.flac"), null)
        val after = waveformReloadKey(track("/музыка/а.flac", listOf(0.1f, 0.2f)), null)
        assertNotEquals(before, after)
    }

    @Test
    fun `the same track without changes does not restart the load`() {
        val a = waveformReloadKey(track("/музыка/а.flac", listOf(0.1f)), null)
        val b = waveformReloadKey(track("/музыка/а.flac", listOf(0.1f)), null)
        assertEquals(a, b)
    }

    @Test
    fun `switching tracks restarts the load`() {
        val a = waveformReloadKey(track("/музыка/а.flac"), null)
        val b = waveformReloadKey(track("/музыка/б.flac"), null)
        assertNotEquals(a, b)
    }
}
