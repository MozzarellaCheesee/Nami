package dev.nami.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.nami.core.database.NamiDatabase
import dev.nami.core.database.entity.TrackEntity
import dev.nami.core.model.TagResult
import dev.nami.core.model.TrackId
import dev.nami.domain.ImportSource
import dev.nami.domain.NativeBridge
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
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
        )

        repo.deleteTracks(listOf(TrackId("t1"), TrackId("t2")))

        val t1 = db.trackDao().findById("t1")
        val t2 = db.trackDao().findById("t2")
        assertNotNull(t1?.deletedAt)
        assertNotNull(t2?.deletedAt)
    }
}
