package dev.nami.domain

import dev.nami.core.model.AlbumId
import dev.nami.core.model.ArtistId
import dev.nami.core.model.TrackId

sealed interface SearchResult {
    data class TrackResult(val id: TrackId, val title: String, val artistName: String?, val artworkPath: String?) : SearchResult
    data class AlbumResult(val id: AlbumId, val title: String, val artistName: String?, val artworkPath: String?) : SearchResult
    data class ArtistResult(val id: ArtistId, val name: String, val photoPath: String?) : SearchResult
}
