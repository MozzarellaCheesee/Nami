package dev.nami.data

import android.content.Context
import android.graphics.Bitmap
import androidx.documentfile.provider.DocumentFile
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.nami.core.database.NamiDatabase
import dev.nami.core.database.entity.TrackEntity
import dev.nami.core.model.TagResult
import dev.nami.core.model.TrackId
import dev.nami.domain.ImportSource
import dev.nami.domain.NativeBridge
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@RunWith(RobolectricTestRunner::class)
class LibraryRepositoryImplTest {
    private lateinit var db: NamiDatabase
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, NamiDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `import populates artist and album from tag data`() = runTest {
        val fakeBridge = object : NativeBridge {
            override suspend fun readTags(path: String) = TagResult(
                title = "Window View", artist = "Farewell225", album = "Doujin Compilation",
                albumArtist = null, trackNo = 3, discNo = 1, genre = "J-Rock", year = 2023,
                durationMs = 180_000, artwork = null, artworkMime = null,
            )
        }
        val resolver = MetadataResolver(db.artistDao(), db.albumDao())
        val artworkStore = ArtworkStore(context)
        val repo = LibraryRepositoryImpl(
            context, db.trackDao(), db.artistDao(), db.albumDao(), fakeBridge, resolver, artworkStore, TrashFileStore(context),
            FolderImportScanner(context),
        )

        // Register the stream directly instead of a file:// URI: Robolectric's real
        // file-based ContentResolver path resolution has Windows drive-letter issues
        // unrelated to the import logic this test actually exercises.
        val sourceUri = android.net.Uri.parse("content://fake/source.flac")
        shadowOf(context.contentResolver).registerInputStream(sourceUri, byteArrayOf(1).inputStream())

        repo.import(ImportSource.Files(listOf(sourceUri.toString()))).last()

        assertEquals(1, db.trackDao().count())
        val artist = db.artistDao().findByName("Farewell225")
        assertNotNull(artist)
        val album = db.albumDao().findByTitleAndArtist("Doujin Compilation", artist.id)
        assertNotNull(album)
    }

    @Test
    fun `import saves embedded artwork even when the track has no album tag`() = runTest {
        val fakeBridge = object : NativeBridge {
            override suspend fun readTags(path: String) = TagResult(
                title = "Window View", artist = null, album = null,
                albumArtist = null, trackNo = null, discNo = null, genre = null, year = null,
                durationMs = 180_000, artwork = byteArrayOf(1, 2, 3), artworkMime = "image/jpeg",
            )
        }
        val resolver = MetadataResolver(db.artistDao(), db.albumDao())
        val artworkStore = ArtworkStore(context)
        val repo = LibraryRepositoryImpl(
            context, db.trackDao(), db.artistDao(), db.albumDao(), fakeBridge, resolver, artworkStore, TrashFileStore(context),
            FolderImportScanner(context),
        )

        val sourceUri = android.net.Uri.parse("content://fake/source2.flac")
        shadowOf(context.contentResolver).registerInputStream(sourceUri, byteArrayOf(1).inputStream())

        repo.import(ImportSource.Files(listOf(sourceUri.toString()))).last()

        assertEquals(1, db.trackDao().count())
        val imported = db.trackDao().findById(db.trackDao().allForIndexing().first().id)
        assertNotNull(imported?.artworkPath)
    }

    @Test
    fun `deleteTracks soft-deletes every id`() = runTest {
        val musicDir = File(context.filesDir, "music").apply { mkdirs() }
        val t1File = File(musicDir, "t1.flac").apply { writeText("audio-bytes-1") }
        val t2File = File(musicDir, "t2.flac").apply { writeText("audio-bytes-2") }
        db.trackDao().insertAll(
            listOf(
                TrackEntity(
                    id = "t1", title = "Track 1", artistId = null, albumId = null, trackNo = null, discNo = null,
                    durationMs = 1000, path = t1File.path, format = "flac", sizeBytes = 1,
                    dateAdded = 1, lastPlayed = null, playCount = 0, genre = null,
                ),
                TrackEntity(
                    id = "t2", title = "Track 2", artistId = null, albumId = null, trackNo = null, discNo = null,
                    durationMs = 1000, path = t2File.path, format = "flac", sizeBytes = 1,
                    dateAdded = 1, lastPlayed = null, playCount = 0, genre = null,
                ),
            ),
        )
        val repo = LibraryRepositoryImpl(
            context, db.trackDao(), db.artistDao(), db.albumDao(), object : NativeBridge {
                override suspend fun readTags(path: String) = null
            }, MetadataResolver(db.artistDao(), db.albumDao()), ArtworkStore(context), TrashFileStore(context),
            FolderImportScanner(context),
        )

        repo.deleteTracks(listOf(TrackId("t1"), TrackId("t2")))

        val t1 = db.trackDao().findById("t1")
        val t2 = db.trackDao().findById("t2")
        assertNotNull(t1?.deletedAt)
        assertNotNull(t2?.deletedAt)
    }

    @Test
    fun `importFolder groups tracks by subdirectory and resolves artist from root folder name`() = runTest {
        val fakeBridge = object : NativeBridge {
            override suspend fun readTags(path: String) = null
        }
        val repo = LibraryRepositoryImpl(
            context, db.trackDao(), db.artistDao(), db.albumDao(), fakeBridge,
            MetadataResolver(db.artistDao(), db.albumDao()), ArtworkStore(context), TrashFileStore(context),
            FolderImportScanner(context),
        )

        val root = File(context.cacheDir, "Farewell225").apply { mkdirs() }
        val albumADir = File(root, "Album A").apply { mkdirs() }
        val albumBDir = File(root, "Album B").apply { mkdirs() }
        val trackA = File(albumADir, "one.flac").apply { writeText("audio-a") }
        val trackB = File(albumBDir, "two.flac").apply { writeText("audio-b") }

        val groups = listOf(
            AudioGroup(
                albumFolderName = "Album A",
                artistFolderName = "Farewell225",
                audioFiles = listOf(DocumentFile.fromFile(trackA)),
                sourceDir = DocumentFile.fromFile(albumADir),
            ),
            AudioGroup(
                albumFolderName = "Album B",
                artistFolderName = "Farewell225",
                audioFiles = listOf(DocumentFile.fromFile(trackB)),
                sourceDir = DocumentFile.fromFile(albumBDir),
            ),
        )

        repo.importFolderFromGroups(groups).toList()

        assertEquals(2, db.trackDao().count())
        val artist = db.artistDao().findByName("Farewell225")
        assertNotNull(artist)
        val albumA = db.albumDao().findByTitleAndArtist("Album A", artist.id)
        val albumB = db.albumDao().findByTitleAndArtist("Album B", artist.id)
        assertNotNull(albumA)
        assertNotNull(albumB)
    }

    @Test
    fun `importFolder uses folder cover when no track has embedded artwork`() = runTest {
        val fakeBridge = object : NativeBridge {
            override suspend fun readTags(path: String) = TagResult(
                title = "Track", artist = null, album = null, albumArtist = null,
                trackNo = null, discNo = null, genre = null, year = null,
                durationMs = 1000, artwork = null, artworkMime = null,
            )
        }
        val repo = LibraryRepositoryImpl(
            context, db.trackDao(), db.artistDao(), db.albumDao(), fakeBridge,
            MetadataResolver(db.artistDao(), db.albumDao()), ArtworkStore(context), TrashFileStore(context),
            FolderImportScanner(context),
        )

        val albumDir = File(context.cacheDir, "Album").apply { mkdirs() }
        val trackFile = File(albumDir, "one.flac").apply { writeText("audio") }
        val coverFile = File(albumDir, "cover.jpg").apply {
            val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
            val bytes = ByteArrayOutputStream().apply {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, this)
            }.toByteArray()
            writeBytes(bytes)
        }

        val groups = listOf(
            AudioGroup(
                albumFolderName = "Album",
                artistFolderName = null,
                audioFiles = listOf(DocumentFile.fromFile(trackFile)),
                sourceDir = DocumentFile.fromFile(albumDir),
            ),
        )

        repo.importFolderFromGroups(groups).toList()

        val album = db.albumDao().allForIndexing().singleOrNull { it.title == "Album" }
        assertNotNull(album)
        assertNotNull(album.artworkPath)
        check(coverFile.exists())
    }
}
