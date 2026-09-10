package dev.nami.data

import org.json.JSONObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class ServerAudioRepositoryImplTest {

    @Test
    fun `parseAnalysis reads analyzer fields`() {
        val o = JSONObject(
            """{"id":7,"title":"x","replaygain_track_gain":-6.5,"r128_loudness":-11.5,
                "bpm":128.0,"musical_key":"A Minor"}""",
        )
        val a = ServerAudioRepositoryImpl.parseAnalysis(o)!!
        assertEquals(-6.5f, a.replayGainTrackGainDb)
        assertEquals(-11.5f, a.r128LoudnessLufs)
        assertEquals(128.0f, a.bpm)
        assertEquals("A Minor", a.musicalKey)
    }

    @Test
    fun `parseAnalysis is null when no analysis present`() {
        val o = JSONObject("""{"id":7,"title":"x","artist":"y"}""")
        assertNull(ServerAudioRepositoryImpl.parseAnalysis(o))
    }
}
