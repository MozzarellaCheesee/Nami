package dev.nami.domain

/** Application ID is public; a Discord user token must never be accepted here. */
fun validDiscordApplicationId(value: String): Boolean =
    value.length in 17..20 && value.all { it in '0'..'9' } &&
        value.toULongOrNull()?.let { it > 0uL } == true

enum class DiscordListeningMode(val label: String) {
    SOLO("Слушает музыку"),
    JAM_HOST("Джем · ведущий"),
    JAM_GUEST("Джем · участник"),
    TOGETHER_HOST("Слушать вместе · ведущий"),
    TOGETHER_GUEST("Слушать вместе · гость"),
    DROP("Раздаёт трек"),
}

fun discordListeningMode(
    jam: JamSession?,
    jamConnected: Boolean,
    togetherGuest: Boolean,
    togetherHost: Boolean,
    sharing: Boolean,
): DiscordListeningMode = when {
    jam != null && jamConnected -> if (jam.isHost) DiscordListeningMode.JAM_HOST else DiscordListeningMode.JAM_GUEST
    togetherGuest -> DiscordListeningMode.TOGETHER_GUEST
    togetherHost -> DiscordListeningMode.TOGETHER_HOST
    sharing -> DiscordListeningMode.DROP
    else -> DiscordListeningMode.SOLO
}

data class DiscordPresence(
    val trackId: String,
    val title: String,
    val description: String,
    val startSeconds: Long,
    val endSeconds: Long?,
    /** Public HTTPS URL of the track's own artwork, server-hosted - never a `file://` path
     * (the publisher is a process on the SERVER, it cannot reach anything local to the phone).
     * Null when the server doesn't know this track or has no artwork for it. */
    val artworkUrl: String? = null,
)

/** Only publish metadata of the track that is actually playing, never a stale queue entry.
 * `artworkUrl` is looked up by the caller (needs a repository, this function stays pure) - pass
 * null when there's nothing to show, never a local file path (see [DiscordPresence.artworkUrl]). */
fun discordPresence(
    playback: PlaybackState,
    track: QueueTrack?,
    mode: DiscordListeningMode,
    showMode: Boolean,
    nowMs: Long,
    artworkUrl: String? = null,
): DiscordPresence? {
    val playing = playback as? PlaybackState.Playing ?: return null
    if (!playing.isPlaying || track == null || track.id != playing.trackId) return null
    val position = if (playing.durationMs > 0) playing.positionMs.coerceIn(0, playing.durationMs)
        else playing.positionMs.coerceAtLeast(0)
    val start = (nowMs - position).coerceAtLeast(0) / 1000
    return DiscordPresence(
        trackId = track.id.value,
        title = track.title.ifBlank { "Неизвестный трек" }.take(128),
        description = listOfNotNull(
            track.artistName?.takeIf { it.isNotBlank() }?.take(80),
            mode.label.takeIf { showMode },
        ).joinToString(" · ").ifBlank { "Nami" }.take(128),
        startSeconds = start,
        endSeconds = playing.durationMs.takeIf { it > 0 }?.let { start + it / 1000 },
        artworkUrl = artworkUrl,
    )
}
