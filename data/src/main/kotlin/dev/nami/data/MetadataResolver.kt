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
        val primaryName = primaryArtistName(name) ?: return null
        artistDao.findByName(primaryName)?.let { return it.id }
        val id = UUID.randomUUID().toString()
        artistDao.insert(ArtistEntity(id = id, name = primaryName, sortName = primaryName))
        return id
    }

    // "Artist A feat. Artist B" / "Artist A ft. B" / "Artist A (feat. B)" tags would otherwise
    // resolve as a brand-new, distinct artist (and thus a new album) per collaboration credit --
    // keep just the primary artist so featured guests don't fragment the library.
    private val featPattern = Regex(
        """\s*[(\[]?\s*(feat\.?|ft\.?|featuring)\s+.*""",
        RegexOption.IGNORE_CASE,
    )

    internal fun primaryArtistName(name: String?): String? {
        if (name.isNullOrBlank()) return null
        return name.replace(featPattern, "").trim().takeIf { it.isNotBlank() } ?: name.trim()
    }

    suspend fun resolveAlbum(title: String?, artistId: String?, year: Int? = null): String? {
        if (title.isNullOrBlank()) return null
        albumDao.findByTitleAndArtist(title, artistId)?.let { return it.id }
        val id = UUID.randomUUID().toString()
        albumDao.insert(AlbumEntity(id = id, title = title, artistId = artistId, year = year, artworkPath = null))
        return id
    }
}
