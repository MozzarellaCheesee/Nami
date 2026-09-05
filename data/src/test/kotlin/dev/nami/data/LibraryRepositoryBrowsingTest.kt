package dev.nami.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.nami.core.database.NamiDatabase
import dev.nami.core.database.entity.AlbumEntity
import dev.nami.core.database.entity.ArtistEntity
import dev.nami.core.database.entity.TrackEntity
import dev.nami.core.model.AlbumId
import dev.nami.core.model.ArtistId
import dev.nami.domain.NativeBridge
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class LibraryRepositoryBrowsingTest {
    private lateinit var db: NamiDatabase
    private lateinit var context: Context
    private lateinit var repo: LibraryRepositoryImpl

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, NamiDatabase::class.java)
            .allowMainThreadQueries().build()
        val fakeBridge = object : NativeBridge {
            override suspend fun readTags(path: String) = null
        }
        repo = LibraryRepositoryImpl(
            context, db.trackDao(), db.artistDao(), db.albumDao(), fakeBridge,
            MetadataResolver(db.artistDao(), db.albumDao()), ArtworkStore(context),
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `tracksInAlbum returns tracks ordered by track number`() = runTest {
        db.artistDao().insert(ArtistEntity(id = "a1", name = "Farewell225", sortName = "Farewell225"))
        db.albumDao().insert(AlbumEntity(id = "al1", title = "Doujin Compilation", artistId = "a1", year = 2023, artworkPath = null))
        db.trackDao().insertAll(
            listOf(
                trackFixture(id = "t2", albumId = "al1", trackNo = 2),
                trackFixture(id = "t1", albumId = "al1", trackNo = 1),
            ),
        )

        val tracks = repo.tracksInAlbum(AlbumId("al1")).first()

        assertEquals(listOf("t1", "t2"), tracks.map { it.id.value })
    }

    @Test
    fun `albumsByArtist maps album rows for that artist`() = runTest {
        db.artistDao().insert(ArtistEntity(id = "a1", name = "Farewell225", sortName = "Farewell225"))
        db.albumDao().insert(AlbumEntity(id = "al1", title = "Doujin Compilation", artistId = "a1", year = 2023, artworkPath = null))

        val albums = repo.albumsByArtist(ArtistId("a1")).first()

        assertEquals(1, albums.size)
        assertEquals("Doujin Compilation", albums[0].title)
    }

    private fun trackFixture(id: String, albumId: String? = null, trackNo: Int? = null) = TrackEntity(
        id = id, title = id, artistId = null, albumId = albumId,
        trackNo = trackNo, discNo = null, durationMs = 1000,
        path = "/music/$id.flac", format = "flac", sizeBytes = 1,
        dateAdded = 1, lastPlayed = null, playCount = 0, genre = null,
    )
}
