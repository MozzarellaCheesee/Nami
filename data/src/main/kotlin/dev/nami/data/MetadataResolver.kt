package dev.nami.data

import dev.nami.core.database.dao.AlbumDao
import dev.nami.core.database.dao.ArtistDao
import dev.nami.core.database.entity.AlbumEntity
import dev.nami.core.database.entity.ArtistEntity
import java.util.UUID
import javax.inject.Inject

class MetadataResolver @Inject constructor(
    private val artistDao: ArtistDao,
    private val albumDao: AlbumDao,
) {
    suspend fun resolveArtist(name: String?): String? {
        if (name.isNullOrBlank()) return null
        artistDao.findByName(name)?.let { return it.id }
        val id = UUID.randomUUID().toString()
        artistDao.insert(ArtistEntity(id = id, name = name, sortName = name))
        return id
    }

    suspend fun resolveAlbum(title: String?, artistId: String?, year: Int? = null): String? {
        if (title.isNullOrBlank()) return null
        albumDao.findByTitleAndArtist(title, artistId)?.let { return it.id }
        val id = UUID.randomUUID().toString()
        albumDao.insert(AlbumEntity(id = id, title = title, artistId = artistId, year = year, artworkPath = null))
        return id
    }
}
