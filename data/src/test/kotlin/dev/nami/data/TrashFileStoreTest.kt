package dev.nami.data

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrashFileStoreTest {

    @Test
    fun `moveTrackFile moves file into destination directory and returns new path`() {
        val filesDir = kotlin.io.path.createTempDirectory().toFile()
        val musicDir = File(filesDir, "music").apply { mkdirs() }
        val source = File(musicDir, "t1.flac").apply { writeText("audio-bytes") }

        val newPath = moveTrackFile(source, File(filesDir, "trash"), "t1")

        assertEquals(File(filesDir, "trash/t1.flac").path, newPath)
        assertTrue(File(newPath!!).exists())
        assertTrue(!source.exists())
    }

    @Test
    fun `moveTrackFile returns null when source file is missing`() {
        val filesDir = kotlin.io.path.createTempDirectory().toFile()

        assertNull(moveTrackFile(File(filesDir, "music/gone.flac"), File(filesDir, "trash"), "missing"))
    }

    @Test
    fun `moveTrackFile restores file from trash back into destination directory`() {
        val filesDir = kotlin.io.path.createTempDirectory().toFile()
        val trashDir = File(filesDir, "trash").apply { mkdirs() }
        val trashed = File(trashDir, "t1.flac").apply { writeText("audio-bytes") }

        val restoredPath = moveTrackFile(trashed, File(filesDir, "music"), "t1")

        assertEquals(File(filesDir, "music/t1.flac").path, restoredPath)
        assertTrue(File(restoredPath!!).exists())
        assertTrue(!trashed.exists())
    }

    @Test
    fun `moveTrackFile preserves file extension`() {
        val filesDir = kotlin.io.path.createTempDirectory().toFile()
        val sourceDir = File(filesDir, "source").apply { mkdirs() }
        val destDir = File(filesDir, "dest").apply { mkdirs() }
        val source = File(sourceDir, "song.mp3").apply { writeText("audio") }

        val newPath = moveTrackFile(source, destDir, "track123")

        assertEquals(File(destDir, "track123.mp3").path, newPath)
        assertTrue(File(newPath!!).exists())
    }

    @Test
    fun `moveTrackFile handles files without extension`() {
        val filesDir = kotlin.io.path.createTempDirectory().toFile()
        val sourceDir = File(filesDir, "source").apply { mkdirs() }
        val destDir = File(filesDir, "dest").apply { mkdirs() }
        val source = File(sourceDir, "noext").apply { writeText("data") }

        val newPath = moveTrackFile(source, destDir, "track123")

        assertEquals(File(destDir, "track123").path, newPath)
        assertTrue(File(newPath!!).exists())
    }
}
