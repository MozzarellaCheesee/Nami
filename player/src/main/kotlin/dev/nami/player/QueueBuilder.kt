package dev.nami.player

import dev.nami.core.model.TrackId
import dev.nami.domain.PlayerQueue
import dev.nami.domain.QueueItem
import dev.nami.domain.QueueOrigin
import dev.nami.domain.QueueTrack

data class MediaItemInfo(
    val mediaId: String,
    val title: String,
    val artist: String?,
    val artworkPath: String? = null,
    val format: String? = null,
)

fun buildPlayerQueue(
    nowPlaying: MediaItemInfo?,
    upcoming: List<MediaItemInfo>,
    originByMediaId: Map<String, QueueOrigin>,
    previous: MediaItemInfo? = null,
): PlayerQueue {
    val nowPlayingTrack = nowPlaying?.let {
        QueueTrack(TrackId(it.mediaId), it.title, it.artist, it.artworkPath, it.format)
    }
    val upcomingItems = upcoming.map { info ->
        QueueItem(
            track = QueueTrack(TrackId(info.mediaId), info.title, info.artist, info.artworkPath, info.format),
            origin = originByMediaId[info.mediaId] ?: QueueOrigin.CONTEXT,
        )
    }
    val previousTrack = previous?.let {
        QueueTrack(TrackId(it.mediaId), it.title, it.artist, it.artworkPath, it.format)
    }
    return PlayerQueue(nowPlayingTrack, upcomingItems, previousTrack)
}
