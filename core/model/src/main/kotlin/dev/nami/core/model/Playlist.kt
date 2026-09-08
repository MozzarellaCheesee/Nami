package dev.nami.core.model

@JvmInline
value class PlaylistId(val value: String)

data class Playlist(
    val id: PlaylistId,
    val name: String,
    val coverPath: String?,
    /** The system "Любимые треки" (Spotify-style Liked Songs) playlist - own heart-gradient
     * cover instead of coverPath, name/cover locked, can't be deleted. */
    val isLiked: Boolean = false,
    /** П.md §20 "умные плейлисты" - the actual track list is computed from this at read time
     * (see PlaylistRepository.tracksInPlaylist), not stored in playlist_tracks. Raw JSON here
     * (not a parsed SmartQuery) because core.model has no dependency on :domain, where SmartQuery
     * lives - PlaylistRepository.parseSmartQuery is how a caller gets the parsed form. */
    val isSmart: Boolean = false,
    val smartQueryJson: String? = null,
)

data class PlaylistSummary(
    val id: PlaylistId,
    val name: String,
    val coverPath: String?,
    val trackCount: Int,
    val isLiked: Boolean = false,
    val isSmart: Boolean = false,
)
