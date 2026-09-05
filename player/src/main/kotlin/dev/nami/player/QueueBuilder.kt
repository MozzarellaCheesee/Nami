package dev.nami.player

import dev.nami.core.model.TrackId
import dev.nami.domain.PlayerQueue
import dev.nami.domain.QueueItem
import dev.nami.domain.QueueOrigin
import dev.nami.domain.QueueTrack

data class MediaItemInfo(val mediaId: String, val title: String, val artist: String?)

fun buildPlayerQueue(
    nowPlaying: MediaItemInfo?,
    upcoming: List<MediaItemInfo>,
    originByMediaId: Map<String, QueueOrigin>,
): PlayerQueue {
    val nowPlayingTrack = nowPlaying?.let { QueueTrack(TrackId(it.mediaId), it.title, it.artist) }
    val upcomingItems = upcoming.map { info ->
        QueueItem(
            track = QueueTrack(TrackId(info.mediaId), info.title, info.artist),
            origin = originByMediaId[info.mediaId] ?: QueueOrigin.CONTEXT,
        )
    }
    return PlayerQueue(nowPlayingTrack, upcomingItems)
}
