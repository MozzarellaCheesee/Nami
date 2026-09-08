package dev.nami.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class CueSheetTest {
    @Test
    fun `parses tracks with title, performer and INDEX 01 start time`() {
        val cue = """
            PERFORMER "Album Artist"
            TITLE "Some Album"
            FILE "album.flac" WAVE
              TRACK 01 AUDIO
                TITLE "First Song"
                PERFORMER "Track Artist"
                INDEX 00 00:00:00
                INDEX 01 00:00:00
              TRACK 02 AUDIO
                TITLE "Second Song"
                INDEX 00 03:58:50
                INDEX 01 04:00:00
        """.trimIndent()

        val tracks = CueSheet.parse(cue)

        assertEquals(2, tracks.size)
        assertEquals(CueTrackInfo(1, "First Song", "Track Artist", 0L), tracks[0])
        // 4:00:00 mm:ss:ff (75 frames/sec) -> (4*60+0)*1000 = 240_000ms, frame 0 adds nothing.
        assertEquals(CueTrackInfo(2, "Second Song", "Album Artist", 240_000L), tracks[1])
    }

    @Test
    fun `stops at a second FILE block instead of merging a multi-disc sheet`() {
        val cue = """
            FILE "disc1.flac" WAVE
              TRACK 01 AUDIO
                TITLE "A"
                INDEX 01 00:00:00
            FILE "disc2.flac" WAVE
              TRACK 02 AUDIO
                TITLE "B"
                INDEX 01 00:00:00
        """.trimIndent()

        assertEquals(listOf(CueTrackInfo(1, "A", null, 0L)), CueSheet.parse(cue))
    }

    @Test
    fun `empty or malformed input yields no tracks`() {
        assertEquals(emptyList(), CueSheet.parse(""))
        assertEquals(emptyList(), CueSheet.parse("REM GENRE Rock\nCATALOG 1234567890123"))
    }
}
