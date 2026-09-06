package dev.nami.data.search

import androidx.room.withTransaction
import dev.nami.core.database.NamiDatabase
import dev.nami.core.database.dao.AlbumDao
import dev.nami.core.database.dao.ArtistDao
import dev.nami.core.database.dao.SearchDao
import dev.nami.core.database.dao.TrackDao
import dev.nami.core.model.AlbumId
import dev.nami.core.model.ArtistId
import dev.nami.core.model.TrackId
import dev.nami.domain.SearchRepository
import dev.nami.domain.SearchResult
import javax.inject.Inject

class SearchRepositoryImpl @Inject constructor(
    private val database: NamiDatabase,
    private val trackDao: TrackDao,
    private val albumDao: AlbumDao,
    private val artistDao: ArtistDao,
    private val searchDao: SearchDao,
) : SearchRepository {

    override suspend fun rebuildIndex() {
        database.withTransaction {
            searchDao.clear()

            trackDao.allForIndexing().forEach { row ->
                searchDao.insert(
                    itemId = row.id, type = "track", title = row.title,
                    subtitle = row.artistName, format = row.format, year = row.year,
                )
            }
            albumDao.allForIndexing().forEach { row ->
                searchDao.insert(
                    itemId = row.id, type = "album", title = row.title,
                    subtitle = row.artistName, format = null, year = null,
                )
            }
            artistDao.allForIndexing().forEach { row ->
                searchDao.insert(
                    itemId = row.id, type = "artist", title = row.name,
                    subtitle = null, format = null, year = null,
                )
            }
        }
    }

    override suspend fun search(query: String): List<SearchResult> {
        val parsed = SearchQueryParser.parse(query)
        val rows = if (parsed.text.isBlank()) {
            if (parsed.format == null && parsed.year == null) return emptyList()
            searchDao.filterOnly(parsed.format, parsed.year)
        } else {
            val matchExpression = FtsQueryBuilder.build(parsed.text) ?: return emptyList()
            searchDao.searchByMatchWithFilters(matchExpression, parsed.format, parsed.year)
        }
        return rows.map { it.toDomain() }
    }

    // The FTS index itself doesn't carry artwork (adding a column to an fts5 table needs care,
    // and search results are already capped at 50 with debounced typing, so a per-result lookup
    // against the existing track/album DAOs is simpler than migrating the index for this).
    private suspend fun SearchDao.SearchResultRow.toDomain(): SearchResult = when (type) {
        "track" -> {
            val track = trackDao.findByIdWithArtwork(itemId)
            SearchResult.TrackResult(TrackId(itemId), title, subtitle, track?.albumArtworkPath ?: track?.track?.artworkPath)
        }
        "album" -> SearchResult.AlbumResult(AlbumId(itemId), title, subtitle, albumDao.findById(itemId)?.artworkPath)
        else -> SearchResult.ArtistResult(ArtistId(itemId), title)
    }
}
