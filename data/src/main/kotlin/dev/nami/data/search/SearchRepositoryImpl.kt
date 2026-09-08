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
import dev.nami.data.lyricsSibling
import dev.nami.domain.SearchResult
import java.io.File
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
        // Операторы bpm/rating/added/no-lyrics/lyrics не лежат в fts5-индексе - они доотбирают
        // уже найденные треки по строкам таблицы tracks (План.md §21).
        val allowedTrackIds = if (parsed.hasTrackOnlyFilters) matchingTrackIds(parsed) else null

        if (parsed.text.isBlank()) {
            if (allowedTrackIds != null) {
                // Запрос вида "bpm:120-140" без текста: искать в fts5 нечего, список полностью
                // задан фильтром.
                return allowedTrackIds.take(SEARCH_LIMIT).mapNotNull { id ->
                    trackDao.findByIdWithArtwork(id)?.let {
                        SearchResult.TrackResult(
                            TrackId(id), it.track.title, it.artistName,
                            it.albumArtworkPath ?: it.track.artworkPath,
                        )
                    }
                }
            }
            if (parsed.format == null && parsed.year == null) return emptyList()
            return searchDao.filterOnly(parsed.format, parsed.year).map { it.toDomain() }
        }

        val matchExpression = FtsQueryBuilder.build(parsed.text) ?: return emptyList()
        val rows = searchDao.searchByMatchWithFilters(matchExpression, parsed.format, parsed.year)
        // Трек-операторы отбрасывают и альбомы с артистами: под "bpm:120" они не подходят
        // по определению.
        val filtered = if (allowedTrackIds == null) rows
        else rows.filter { it.type == "track" && it.itemId in allowedTrackIds }
        return filtered.map { it.toDomain() }
    }

    /** Id треков, проходящих трек-операторы запроса. Полный проход по библиотеке - но только
     * когда оператор реально написан в строке, а не на каждую букву обычного поиска. */
    private suspend fun matchingTrackIds(parsed: ParsedSearchQuery): Set<String> {
        val now = System.currentTimeMillis()
        val lyricsNeedle = parsed.lyrics?.lowercase()
        val newerThan = parsed.addedWithinDays?.let { now - it * DAY_MS }
        val olderThan = parsed.addedOlderThanDays?.let { now - it * DAY_MS }
        val bpmFrom = parsed.bpmFrom
        val bpmTo = parsed.bpmTo
        val ratingMin = parsed.ratingMin
        val ratingMax = parsed.ratingMax
        return trackDao.allForSearchFilter().asSequence()
            .filter { row ->
                val bpm = row.bpm
                (bpmFrom == null || (bpm != null && bpm >= bpmFrom)) &&
                    (bpmTo == null || (bpm != null && bpm <= bpmTo)) &&
                    (ratingMin == null || (row.rating ?: 0) >= ratingMin) &&
                    (ratingMax == null || (row.rating ?: 0) <= ratingMax) &&
                    (parsed.ratingExact == null || row.rating == parsed.ratingExact) &&
                    (newerThan == null || row.dateAdded >= newerThan) &&
                    (olderThan == null || row.dateAdded < olderThan)
            }
            // Файловые проверки последними: к ним доходят только треки, прошедшие дешёвые поля.
            .filter { row ->
                parsed.noLyrics == null || File(lyricsSibling(row.path, ".lrc")).exists() != parsed.noLyrics
            }
            .filter { row ->
                if (lyricsNeedle == null) return@filter true
                val file = File(lyricsSibling(row.path, ".lrc"))
                file.exists() && file.readText().lowercase().contains(lyricsNeedle)
            }
            .map { it.id }
            .toSet()
    }

    private companion object {
        const val SEARCH_LIMIT = 50
        const val DAY_MS = 24L * 60 * 60 * 1000
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
        else -> SearchResult.ArtistResult(ArtistId(itemId), title, artistDao.findByIdWithPhoto(itemId)?.photoPath)
    }
}
