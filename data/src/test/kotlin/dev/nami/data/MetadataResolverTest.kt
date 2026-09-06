package dev.nami.data

import androidx.paging.PagingSource
import dev.nami.core.database.dao.AlbumDao
import dev.nami.core.database.dao.ArtistDao
import dev.nami.core.database.entity.AlbumEntity
import dev.nami.core.database.entity.ArtistEntity
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MetadataResolverTest {

    @Test
    fun `resolveArtist returns existing id when name already known`() = runTest {
        val existing = ArtistEntity(id = "a1", name = "Farewell225", sortName = "Farewell225")
        val artistDao = object : ArtistDao {
            override suspend fun findById(id: String) = if (id == "a1") existing else null
            override suspend fun findByName(name: String) = if (name == "Farewell225") existing else null
            override suspend fun insert(artist: ArtistEntity) = error("should not insert when found")
            override fun pagingSource(): PagingSource<Int, ArtistDao.ArtistWithPhoto> = error("unused")
            override suspend fun findByIdWithPhoto(id: String): ArtistDao.ArtistWithPhoto? = error("unused")
            override suspend fun allForIndexing(): List<ArtistEntity> = error("unused")
            override suspend fun setPhotoPath(id: String, path: String) = error("unused")
        }
        val albumDao = object : AlbumDao {
            override suspend fun findById(id: String): AlbumEntity? = null
            override suspend fun findByTitleAndArtist(title: String, artistId: String?): AlbumEntity? = null
            override suspend fun albumsByArtist(artistId: String): List<AlbumEntity> = error("unused")
            override suspend fun insert(album: AlbumEntity) = error("unused")
            override suspend fun setArtworkPath(id: String, path: String) = error("unused")
            override fun pagingSource(): PagingSource<Int, AlbumDao.AlbumListRow> = error("unused")
            override suspend fun recentAlbums(limit: Int): List<AlbumDao.AlbumListRow> = error("unused")
            override suspend fun allForIndexing(): List<AlbumDao.AlbumListRow> = error("unused")
        }
        val resolver = MetadataResolver(artistDao, albumDao)

        val id = resolver.resolveArtist("Farewell225")

        assertEquals("a1", id)
    }

    @Test
    fun `resolveArtist inserts new artist when name unknown`() = runTest {
        var inserted: ArtistEntity? = null
        val artistDao = object : ArtistDao {
            override suspend fun findById(id: String): ArtistEntity? = null
            override suspend fun findByName(name: String): ArtistEntity? = null
            override suspend fun insert(artist: ArtistEntity) { inserted = artist }
            override fun pagingSource(): PagingSource<Int, ArtistDao.ArtistWithPhoto> = error("unused")
            override suspend fun findByIdWithPhoto(id: String): ArtistDao.ArtistWithPhoto? = error("unused")
            override suspend fun allForIndexing(): List<ArtistEntity> = error("unused")
            override suspend fun setPhotoPath(id: String, path: String) = error("unused")
        }
        val albumDao = object : AlbumDao {
            override suspend fun findById(id: String): AlbumEntity? = null
            override suspend fun findByTitleAndArtist(title: String, artistId: String?): AlbumEntity? = null
            override suspend fun albumsByArtist(artistId: String): List<AlbumEntity> = error("unused")
            override suspend fun insert(album: AlbumEntity) = error("unused")
            override suspend fun setArtworkPath(id: String, path: String) = error("unused")
            override fun pagingSource(): PagingSource<Int, AlbumDao.AlbumListRow> = error("unused")
            override suspend fun recentAlbums(limit: Int): List<AlbumDao.AlbumListRow> = error("unused")
            override suspend fun allForIndexing(): List<AlbumDao.AlbumListRow> = error("unused")
        }
        val resolver = MetadataResolver(artistDao, albumDao)

        val id = resolver.resolveArtist("New Artist")

        assertEquals("New Artist", inserted?.name)
        assertEquals(inserted?.id, id)
    }

    @Test
    fun `resolveArtist strips a feat credit down to the primary artist`() = runTest {
        var inserted: ArtistEntity? = null
        val artistDao = object : ArtistDao {
            override suspend fun findById(id: String): ArtistEntity? = null
            override suspend fun findByName(name: String) = if (name == "Farewell225") inserted else null
            override suspend fun insert(artist: ArtistEntity) { inserted = artist }
            override fun pagingSource(): PagingSource<Int, ArtistDao.ArtistWithPhoto> = error("unused")
            override suspend fun findByIdWithPhoto(id: String): ArtistDao.ArtistWithPhoto? = error("unused")
            override suspend fun allForIndexing(): List<ArtistEntity> = error("unused")
            override suspend fun setPhotoPath(id: String, path: String) = error("unused")
        }
        val albumDao = object : AlbumDao {
            override suspend fun findById(id: String): AlbumEntity? = null
            override suspend fun findByTitleAndArtist(title: String, artistId: String?): AlbumEntity? = null
            override suspend fun albumsByArtist(artistId: String): List<AlbumEntity> = error("unused")
            override suspend fun insert(album: AlbumEntity) = error("unused")
            override suspend fun setArtworkPath(id: String, path: String) = error("unused")
            override fun pagingSource(): PagingSource<Int, AlbumDao.AlbumListRow> = error("unused")
            override suspend fun recentAlbums(limit: Int): List<AlbumDao.AlbumListRow> = error("unused")
            override suspend fun allForIndexing(): List<AlbumDao.AlbumListRow> = error("unused")
        }
        val resolver = MetadataResolver(artistDao, albumDao)

        resolver.resolveArtist("Farewell225 feat. Someone Else")
        assertEquals("Farewell225", inserted?.name)

        val secondId = resolver.resolveArtist("Farewell225 (feat. Another Guest)")
        assertEquals(inserted?.id, secondId)
    }

    @Test
    fun `resolveArtist returns null for null name`() = runTest {
        val artistDao = object : ArtistDao {
            override suspend fun findById(id: String): ArtistEntity? = null
            override suspend fun findByName(name: String): ArtistEntity? = null
            override suspend fun insert(artist: ArtistEntity) = error("should not be called")
            override fun pagingSource(): PagingSource<Int, ArtistDao.ArtistWithPhoto> = error("unused")
            override suspend fun findByIdWithPhoto(id: String): ArtistDao.ArtistWithPhoto? = error("unused")
            override suspend fun allForIndexing(): List<ArtistEntity> = error("unused")
            override suspend fun setPhotoPath(id: String, path: String) = error("unused")
        }
        val albumDao = object : AlbumDao {
            override suspend fun findById(id: String): AlbumEntity? = null
            override suspend fun findByTitleAndArtist(title: String, artistId: String?): AlbumEntity? = null
            override suspend fun albumsByArtist(artistId: String): List<AlbumEntity> = error("unused")
            override suspend fun insert(album: AlbumEntity) = error("unused")
            override suspend fun setArtworkPath(id: String, path: String) = error("unused")
            override fun pagingSource(): PagingSource<Int, AlbumDao.AlbumListRow> = error("unused")
            override suspend fun recentAlbums(limit: Int): List<AlbumDao.AlbumListRow> = error("unused")
            override suspend fun allForIndexing(): List<AlbumDao.AlbumListRow> = error("unused")
        }
        val resolver = MetadataResolver(artistDao, albumDao)

        assertNull(resolver.resolveArtist(null))
    }

    @Test
    fun `resolveAlbum inserts new album with given year`() = runTest {
        var inserted: AlbumEntity? = null
        val artistDao = object : ArtistDao {
            override suspend fun findById(id: String): ArtistEntity? = null
            override suspend fun findByName(name: String): ArtistEntity? = null
            override suspend fun insert(artist: ArtistEntity) = error("unused")
            override fun pagingSource(): PagingSource<Int, ArtistDao.ArtistWithPhoto> = error("unused")
            override suspend fun findByIdWithPhoto(id: String): ArtistDao.ArtistWithPhoto? = error("unused")
            override suspend fun allForIndexing(): List<ArtistEntity> = error("unused")
            override suspend fun setPhotoPath(id: String, path: String) = error("unused")
        }
        val albumDao = object : AlbumDao {
            override suspend fun findById(id: String): AlbumEntity? = null
            override suspend fun findByTitleAndArtist(title: String, artistId: String?): AlbumEntity? = null
            override suspend fun albumsByArtist(artistId: String): List<AlbumEntity> = error("unused")
            override suspend fun insert(album: AlbumEntity) { inserted = album }
            override suspend fun setArtworkPath(id: String, path: String) = error("unused")
            override fun pagingSource(): PagingSource<Int, AlbumDao.AlbumListRow> = error("unused")
            override suspend fun recentAlbums(limit: Int): List<AlbumDao.AlbumListRow> = error("unused")
            override suspend fun allForIndexing(): List<AlbumDao.AlbumListRow> = error("unused")
        }
        val resolver = MetadataResolver(artistDao, albumDao)

        val id = resolver.resolveAlbum("New Album", "a1", 1999)

        assertEquals(1999, inserted?.year)
        assertEquals(inserted?.id, id)
    }
}
