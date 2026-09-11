package dev.nami.domain

import kotlinx.coroutines.flow.StateFlow

/** State of an active Jam session. */
data class JamSession(
    val code: String,
    val isHost: Boolean,
    val queue: List<Long>,          // server track IDs
    val currentTrackId: Long?,
    val positionMs: Long,
    val lastSyncAt: Long,           // epoch ms from server's 'at' field
    val playedBy: Long?,            // user_id who triggered current play
)

interface JamRepository {
    /** Current session or null if not in a Jam. */
    val session: StateFlow<JamSession?>

    /** Last error message from server (e.g. "нет такой сессии"). Null = no error. */
    val error: StateFlow<String?>

    /** Whether the WebSocket is currently connected. */
    val connected: StateFlow<Boolean>

    /** Whether the Nami server is configured and ready for Jam (token and URL are set). */
    val isServerConfigured: StateFlow<Boolean>

    /** Active host URL for this session (either configured server or guest server). */
    val activeHostUrl: StateFlow<String?>

    /** All known host URLs for the configured server (local IP, external domain, Tailscale). */
    val allHostUrls: StateFlow<List<String>>

    /** Recently used Jam host addresses for guest fallback. */
    val recentHosts: StateFlow<List<String>>

    /** Create a new Jam room. On success, [session] will emit with isHost=true. */
    fun createRoom()

    /** Join an existing Jam room by code. On success, [session] will emit with isHost=false.
     * If [hostUrl] is provided, connects directly as a guest without requiring local server setup. */
    fun joinRoom(code: String, hostUrl: String? = null)

    /** Leave the current Jam session and disconnect. */
    fun leave()

    /** Host sends play command to sync all participants. */
    fun play(serverTrackId: Long, positionMs: Long = 0)

    /** Seek to position (sends jam_play with current track). */
    fun seek(positionMs: Long)

    /** Add a track to the shared queue. */
    fun addToQueue(serverTrackId: Long)

    /** Clear error state. */
    fun clearError()
}
