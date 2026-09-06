package dev.nami.data.search

/*
 * NOTE (mirrors the documented Task 1 tech debt in SearchDaoTest.kt):
 * Robolectric's bundled SQLite does not support FTS5 ("no such module: fts5") on this
 * machine, confirmed across three independent attempts in Task 1 (plain Robolectric SQLite,
 * FrameworkSQLiteOpenHelperFactory, and RequerySQLiteOpenHelperFactory via JitPack, which fails
 * with UnsatisfiedLinkError on Windows native binaries). The brief's test drives Room +
 * Robolectric end-to-end through SearchDao's real FTS5 MATCH queries, so it hits the same wall.
 * The FTS5 SQL itself is already covered by hand in Task 1's SearchDaoTest (raw sqlite-jdbc).
 * This test instead covers SearchRepositoryImpl's own logic -- what rows it reads from
 * TrackDao/AlbumDao/ArtistDao during rebuildIndex, how it maps query text to
 * SearchQueryParser/FtsQueryBuilder calls and picks the right SearchDao method, and how
 * SearchResultRow maps to SearchResult -- using fake DAOs (the same anonymous-object style as
 * MetadataResolverTest.kt) instead of a real Room database.
 */

import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.nami.core.database.NamiDatabase
import dev.nami.core.database.dao.AlbumDao
import dev.nami.core.database.dao.ArtistDao
import dev.nami.core.database.dao.SearchDao
import dev.nami.core.database.dao.TrackDao
import dev.nami.core.database.entity.AlbumEntity
import dev.nami.core.database.entity.ArtistEntity
import dev.nami.core.database.entity.TrackEntity
import dev.nami.domain.SearchResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SearchRepositoryImplTest {

    // withTransaction just needs a real RoomDatabase instance to call runInTransaction on;
    // the DAOs used by rebuildIndex/search are still the fakes below, not this database's own.
    private fun inMemoryDb(): NamiDatabase =
        Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), NamiDatabase::class.java)
            .allowMainThreadQueries()
            .build()

    private fun fakeTrackDao(rows: List<TrackDao.TrackIndexRow> = emptyList()) = object : TrackDao {
        override fun pagingSource(): PagingSource<Int, TrackDao.TrackWithArtwork> = error("unused")
        override suspend fun allOrderedWithArtwork(): List<TrackDao.TrackWithArtwork> = error("unused")
        override suspend fun findById(id: String): TrackEntity? = error("unused")
        override suspend fun findByIdWithArtwork(id: String): TrackDao.TrackWithArtwork? = error("unused")
        override suspend fun findByPath(path: String): TrackEntity? = error("unused")
        override suspend fun findDuplicate(title: String, artistId: String?, albumId: String?, durationMs: Long): TrackEntity? = error("unused")
        override suspend fun insertAll(tracks: List<TrackEntity>) = error("unused")
        override suspend fun count(): Int = error("unused")
        override suspend fun tracksForAlbum(albumId: String): List<TrackDao.TrackWithArtwork> = error("unused")
        override suspend fun tracksForArtist(artistId: String): List<TrackDao.TrackWithArtwork> = error("unused")
        override suspend fun allForIndexing(): List<TrackDao.TrackIndexRow> = rows
        override suspend fun setDeletedAt(id: String, deletedAt: Long?, path: String) = error("unused")
        override suspend fun hardDelete(id: String) = error("unused")
        override suspend fun setArtworkPath(id: String, path: String) = error("unused")
        override fun trashedTracksFlow() = error("unused")
    }

    private fun fakeAlbumDao(rows: List<AlbumDao.AlbumListRow> = emptyList()) = object : AlbumDao {
        override suspend fun findById(id: String): AlbumEntity? = error("unused")
        override suspend fun findByTitleAndArtist(title: String, artistId: String?): AlbumEntity? = error("unused")
        override suspend fun albumsByArtist(artistId: String): List<AlbumEntity> = error("unused")
        override fun pagingSource(): PagingSource<Int, AlbumDao.AlbumListRow> = error("unused")
        override suspend fun insert(album: AlbumEntity) = error("unused")
        override suspend fun setArtworkPath(id: String, path: String) = error("unused")
        override suspend fun recentAlbums(limit: Int): List<AlbumDao.AlbumListRow> = error("unused")
        override suspend fun allForIndexing(): List<AlbumDao.AlbumListRow> = rows
    }

    private fun fakeArtistDao(rows: List<ArtistEntity> = emptyList()) = object : ArtistDao {
        override suspend fun findById(id: String): ArtistEntity? = error("unused")
        override suspend fun findByName(name: String): ArtistEntity? = error("unused")
        override fun pagingSource(): PagingSource<Int, ArtistDao.ArtistWithPhoto> = error("unused")
        override suspend fun findByIdWithPhoto(id: String): ArtistDao.ArtistWithPhoto? = error("unused")
        override suspend fun insert(artist: ArtistEntity) = error("unused")
        override suspend fun allForIndexing(): List<ArtistEntity> = rows
        override suspend fun setPhotoPath(id: String, path: String) = error("unused")
    }

    @Test
    fun `rebuildIndex clears index then inserts tracks, albums and artists`() = runTest {
        var cleared = false
        val inserted = mutableListOf<SearchDao.SearchResultRow>()
        val searchDao = object : SearchDao {
            override suspend fun clear() { cleared = true }
            override suspend fun insert(itemId: String, type: String, title: String, subtitle: String?, format: String?, year: Int?) {
                check(cleared) { "insert before clear" }
                inserted += SearchDao.SearchResultRow(itemId, type, title, subtitle, format, year)
            }
            override suspend fun searchByMatchWithFilters(matchExpression: String, format: String?, year: Int?) = error("unused")
            override suspend fun filterOnly(format: String?, year: Int?) = error("unused")
        }
        val repo = SearchRepositoryImpl(
            database = inMemoryDb(),
            trackDao = fakeTrackDao(
                listOf(
                    TrackDao.TrackIndexRow(
                        id = "t1", title = "Window View", artistName = "Farewell225",
                        albumName = "Doujin Compilation", format = "flac", year = 2023,
                    ),
                ),
            ),
            albumDao = fakeAlbumDao(
                listOf(AlbumDao.AlbumListRow(id = "al1", title = "Doujin Compilation", artistName = "Farewell225", artworkPath = null)),
            ),
            artistDao = fakeArtistDao(
                listOf(ArtistEntity(id = "a1", name = "Farewell225", sortName = "Farewell225")),
            ),
            searchDao = searchDao,
        )

        repo.rebuildIndex()

        assertTrue(cleared)
        assertEquals(3, inserted.size)
        assertEquals(SearchDao.SearchResultRow("t1", "track", "Window View", "Farewell225", "flac", 2023), inserted[0])
        assertEquals(SearchDao.SearchResultRow("al1", "album", "Doujin Compilation", "Farewell225", null, null), inserted[1])
        assertEquals(SearchDao.SearchResultRow("a1", "artist", "Farewell225", null, null, null), inserted[2])
    }

    @Test
    fun `search builds match expression and maps rows by type`() = runTest {
        var capturedMatch: String? = null
        var capturedFormat: String? = null
        var capturedYear: Int? = null
        val searchDao = object : SearchDao {
            override suspend fun clear() = error("unused")
            override suspend fun insert(itemId: String, type: String, title: String, subtitle: String?, format: String?, year: Int?) = error("unused")
            override suspend fun searchByMatchWithFilters(matchExpression: String, format: String?, year: Int?): List<SearchDao.SearchResultRow> {
                capturedMatch = matchExpression
                capturedFormat = format
                capturedYear = year
                return listOf(
                    SearchDao.SearchResultRow("t1", "track", "Window View", "Farewell225", "flac", 2023),
                    SearchDao.SearchResultRow("al1", "album", "Doujin Compilation", "Farewell225", null, null),
                    SearchDao.SearchResultRow("a1", "artist", "Farewell225", null, null, null),
                )
            }
            override suspend fun filterOnly(format: String?, year: Int?) = error("unused")
        }
        val repo = SearchRepositoryImpl(inMemoryDb(), fakeTrackDao(), fakeAlbumDao(), fakeArtistDao(), searchDao)

        val results = repo.search("wind format:flac year:2023")

        assertEquals("\"wind\"*", capturedMatch)
        assertEquals("flac", capturedFormat)
        assertEquals(2023, capturedYear)
        assertEquals(3, results.size)
        assertEquals(SearchResult.TrackResult(dev.nami.core.model.TrackId("t1"), "Window View", "Farewell225"), results[0])
        assertEquals(SearchResult.AlbumResult(dev.nami.core.model.AlbumId("al1"), "Doujin Compilation", "Farewell225"), results[1])
        assertEquals(SearchResult.ArtistResult(dev.nami.core.model.ArtistId("a1"), "Farewell225"), results[2])
    }

    @Test
    fun `search with only filters uses filterOnly and skips FTS match`() = runTest {
        var filterOnlyCalled = false
        val searchDao = object : SearchDao {
            override suspend fun clear() = error("unused")
            override suspend fun insert(itemId: String, type: String, title: String, subtitle: String?, format: String?, year: Int?) = error("unused")
            override suspend fun searchByMatchWithFilters(matchExpression: String, format: String?, year: Int?) = error("should not be called")
            override suspend fun filterOnly(format: String?, year: Int?): List<SearchDao.SearchResultRow> {
                filterOnlyCalled = true
                assertEquals("flac", format)
                assertEquals(null, year)
                return listOf(SearchDao.SearchResultRow("t1", "track", "Window View", null, "flac", null))
            }
        }
        val repo = SearchRepositoryImpl(inMemoryDb(), fakeTrackDao(), fakeAlbumDao(), fakeArtistDao(), searchDao)

        val results = repo.search("format:flac")

        assertTrue(filterOnlyCalled)
        assertEquals(1, results.size)
    }

    @Test
    fun `search with blank query and no filters returns empty without querying`() = runTest {
        val searchDao = object : SearchDao {
            override suspend fun clear() = error("unused")
            override suspend fun insert(itemId: String, type: String, title: String, subtitle: String?, format: String?, year: Int?) = error("unused")
            override suspend fun searchByMatchWithFilters(matchExpression: String, format: String?, year: Int?) = error("should not be called")
            override suspend fun filterOnly(format: String?, year: Int?) = error("should not be called")
        }
        val repo = SearchRepositoryImpl(inMemoryDb(), fakeTrackDao(), fakeAlbumDao(), fakeArtistDao(), searchDao)

        val results = repo.search("   ")

        assertEquals(emptyList(), results)
    }
}
