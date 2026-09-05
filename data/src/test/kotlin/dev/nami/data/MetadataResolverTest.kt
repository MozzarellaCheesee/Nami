package dev.nami.data

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
        }
        val albumDao = object : AlbumDao {
            override suspend fun findById(id: String): AlbumEntity? = null
            override suspend fun findByTitleAndArtist(title: String, artistId: String?): AlbumEntity? = null
            override suspend fun insert(album: AlbumEntity) = error("unused")
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
        }
        val albumDao = object : AlbumDao {
            override suspend fun findById(id: String): AlbumEntity? = null
            override suspend fun findByTitleAndArtist(title: String, artistId: String?): AlbumEntity? = null
            override suspend fun insert(album: AlbumEntity) = error("unused")
        }
        val resolver = MetadataResolver(artistDao, albumDao)

        val id = resolver.resolveArtist("New Artist")

        assertEquals("New Artist", inserted?.name)
        assertEquals(inserted?.id, id)
    }

    @Test
    fun `resolveArtist returns null for null name`() = runTest {
        val artistDao = object : ArtistDao {
            override suspend fun findById(id: String): ArtistEntity? = null
            override suspend fun findByName(name: String): ArtistEntity? = null
            override suspend fun insert(artist: ArtistEntity) = error("should not be called")
        }
        val albumDao = object : AlbumDao {
            override suspend fun findById(id: String): AlbumEntity? = null
            override suspend fun findByTitleAndArtist(title: String, artistId: String?): AlbumEntity? = null
            override suspend fun insert(album: AlbumEntity) = error("unused")
        }
        val resolver = MetadataResolver(artistDao, albumDao)

        assertNull(resolver.resolveArtist(null))
    }
}
