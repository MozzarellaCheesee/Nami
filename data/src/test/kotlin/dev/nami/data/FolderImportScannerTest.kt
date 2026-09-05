package dev.nami.data

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

@RunWith(RobolectricTestRunner::class)
class FolderImportScannerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scanner = FolderImportScanner(context)

    private fun tempDir(): File = kotlin.io.path.createTempDirectory().toFile()

    @Test
    fun `folder with audio files directly inside is one album named after the folder`() {
        val root = File(tempDir(), "Doujin Compilation").apply { mkdirs() }
        File(root, "01 - Intro.mp3").writeText("fake")
        File(root, "02 - Track.mp3").writeText("fake")

        val groups = scanner.scanDirectory(DocumentFile.fromFile(root))

        assertEquals(1, groups.size)
        assertEquals("Doujin Compilation", groups[0].albumFolderName)
        assertNull(groups[0].artistFolderName)
        assertEquals(2, groups[0].audioFiles.size)
    }

    @Test
    fun `folder with only subdirectories treats each subdirectory as an album under the root's name`() {
        val root = File(tempDir(), "Farewell225").apply { mkdirs() }
        val albumA = File(root, "Album A").apply { mkdirs() }
        val albumB = File(root, "Album B").apply { mkdirs() }
        File(albumA, "01.mp3").writeText("fake")
        File(albumB, "01.mp3").writeText("fake")

        val groups = scanner.scanDirectory(DocumentFile.fromFile(root))

        assertEquals(2, groups.map { it.albumFolderName }.toSet().size)
        assertEquals(setOf("Album A", "Album B"), groups.map { it.albumFolderName }.toSet())
        assertEquals(setOf("Farewell225"), groups.map { it.artistFolderName }.toSet())
    }

    @Test
    fun `nested disc subfolders still count as one album`() {
        val root = File(tempDir(), "Big Release").apply { mkdirs() }
        val disc1 = File(root, "Disc 1").apply { mkdirs() }
        val disc2 = File(root, "Disc 2").apply { mkdirs() }
        File(disc1, "01.mp3").writeText("fake")
        File(disc2, "01.mp3").writeText("fake")

        val groups = scanner.scanDirectory(DocumentFile.fromFile(root))

        assertEquals(1, groups.size)
        assertEquals("Big Release", groups[0].albumFolderName)
        assertEquals(2, groups[0].audioFiles.size)
    }

    @Test
    fun `non-audio files are ignored`() {
        val root = File(tempDir(), "Album").apply { mkdirs() }
        File(root, "01.mp3").writeText("fake")
        File(root, "cover.jpg").writeText("fake-image")
        File(root, "notes.txt").writeText("fake-text")

        val groups = scanner.scanDirectory(DocumentFile.fromFile(root))

        assertEquals(1, groups[0].audioFiles.size)
    }

    @Test
    fun `findFolderCover matches folder or cover stem case-insensitively`() {
        val root = File(tempDir(), "Album").apply { mkdirs() }
        File(root, "01.mp3").writeText("fake")
        File(root, "Cover.PNG").writeText("fake-image")

        val cover = scanner.findFolderCover(DocumentFile.fromFile(root))

        assertNotNull(cover)
        assertEquals("Cover.PNG", cover?.name)
    }

    @Test
    fun `findFolderCover returns null when no matching image exists`() {
        val root = File(tempDir(), "Album").apply { mkdirs() }
        File(root, "01.mp3").writeText("fake")
        File(root, "artwork.jpg").writeText("fake-image")

        val cover = scanner.findFolderCover(DocumentFile.fromFile(root))

        assertNull(cover)
    }
}
