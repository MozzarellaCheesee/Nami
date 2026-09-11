package dev.nami.data

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.nami.core.database.dao.LoopDao
import dev.nami.core.database.dao.MomentDao
import dev.nami.core.database.dao.PlayHistoryDao
import dev.nami.core.database.dao.PlaylistDao
import dev.nami.core.database.dao.PlaylistTrackDao
import dev.nami.core.database.dao.TagDao
import dev.nami.core.database.dao.TrackDao
import dev.nami.core.database.entity.LoopEntity
import dev.nami.core.database.entity.MomentEntity
import dev.nami.core.database.entity.PlayHistoryEntity
import dev.nami.core.database.entity.PlaylistEntity
import dev.nami.core.database.entity.PlaylistTrackEntity
import dev.nami.core.database.entity.TagEntity
import dev.nami.core.database.entity.TrackTagEntity
import dev.nami.domain.SettingsRepository
import dev.nami.domain.SyncRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SyncRepositoryImpl"

@Singleton
class SyncRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val playlistDao: PlaylistDao,
    private val playlistTrackDao: PlaylistTrackDao,
    private val trackDao: TrackDao,
    private val momentDao: MomentDao,
    private val loopDao: LoopDao,
    private val tagDao: TagDao,
    private val playHistoryDao: PlayHistoryDao,
    private val pendingScrobbleDao: dev.nami.core.database.dao.PendingScrobbleDao,
    private val artistDao: dev.nami.core.database.dao.ArtistDao,
) : SyncRepository {

    private val prefs = context.getSharedPreferences("nami_sync_prefs", Context.MODE_PRIVATE)
    private val _lastSyncTimestamp = MutableStateFlow(prefs.getLong("last_sync_ts", 0L))
    override val lastSyncTimestamp: StateFlow<Long> = _lastSyncTimestamp

    override suspend fun pullFromServer(): Boolean {
        val cfg = serverConfig() ?: return false
        var currentSince = _lastSyncTimestamp.value
        val positionSince = currentSince
        var cursor = prefs.getLong("last_sync_cursor", 0L)
        var maxNow = currentSince
        var totalApplied = 0

        var iterations = 0
        while (iterations < 100) {
            iterations++
            val result = NamiServerClient.syncPull(cfg, currentSince, cursor) ?: return false
            val changes = result.optJSONArray("changes") ?: JSONArray()
            val now = result.optLong("now", System.currentTimeMillis() / 1000)
            val truncated = result.optBoolean("truncated", false)
            if (now > maxNow) maxNow = now

            var maxUpdatedAtInBatch = currentSince
            for (i in 0 until changes.length()) {
                val change = changes.optJSONObject(i) ?: continue
                val entity = change.optString("entity")
                val id = change.optString("id")
                val field = change.optString("field")
                val updatedAt = change.optLong("updated_at", currentSince)
                if (updatedAt > maxUpdatedAtInBatch) {
                    maxUpdatedAtInBatch = updatedAt
                }
                applyChange(entity, id, field, change, updatedAt)
                totalApplied++
            }

            if (truncated && changes.length() > 0 && maxUpdatedAtInBatch > currentSince) {
                currentSince = maxUpdatedAtInBatch
                cursor = result.optLong("next_cursor", 0L)
            } else if (truncated && changes.length() > 0) {
                cursor = result.optLong("next_cursor", 0L)
            } else {
                cursor = 0L
                break
            }
        }

        val finalTs = if (maxNow > 0) maxNow else (System.currentTimeMillis() / 1000)
        _lastSyncTimestamp.value = finalTs
        prefs.edit().putLong("last_sync_ts", finalTs).putLong("last_sync_cursor", cursor).apply()
        Log.d(TAG, "pullFromServer: applied $totalApplied changes, new lastSync=$finalTs")
        NamiServerClient.positions(cfg, positionSince)?.optJSONObject(0)?.let { position ->
            val serverTrackId = position.optLong("track_id").takeIf { it > 0 } ?: return@let
            settingsRepository.setLastPlayback(
                listOf("server_$serverTrackId"),
                0,
                position.optLong("position_ms").coerceAtLeast(0L),
                position.optLong("updated_at") * 1000L,
            )
        }
        flushPendingScrobbles(cfg)
        return true
    }

    internal suspend fun applyChange(
        entity: String,
        id: String,
        field: String,
        change: JSONObject,
        updatedAt: Long,
    ) {
        val isDeleted = field == "__deleted" || (change.has("value") && change.optBoolean("value", false) && field == "__deleted")
        when (entity) {
            "playlist" -> {
                if (isDeleted) {
                    playlistDao.softDelete(id, updatedAt * 1000L)
                } else if (field == "name") {
                    val name = change.optString("value")
                    val existing = playlistDao.findById(id)
                    if (existing != null) {
                        playlistDao.rename(id, name, updatedAt * 1000L)
                        val delAt = existing.deletedAt
                        if (delAt != null && updatedAt * 1000L > delAt) {
                            playlistDao.restore(id)
                        }
                    } else {
                        playlistDao.insert(
                            PlaylistEntity(
                                id = id,
                                name = name.ifBlank { "Плейлист" },
                                coverPath = null,
                                createdAt = updatedAt * 1000L,
                            ),
                        )
                    }
                }
            }
            "playlist_track" -> {
                val (playlistId, trackId) = if (id.contains(":")) {
                    val parts = id.split(":", limit = 2)
                    parts[0] to parts[1]
                } else {
                    val obj = change.optJSONObject("value")
                    val pId = obj?.optString("playlist_id") ?: ""
                    val tId = obj?.optString("track_id") ?: ""
                    pId to tId
                }
                if (playlistId.isNotBlank() && trackId.isNotBlank()) {
                    if (isDeleted) {
                        playlistTrackDao.remove(playlistId, trackId)
                    } else {
                        val targetTrackId = when {
                            trackDao.findById(trackId) != null -> trackId
                            trackDao.findById("server_$trackId") != null -> "server_$trackId"
                            else -> trackId
                        }
                        if (trackDao.findById(targetTrackId) != null) {
                            if (playlistDao.findById(playlistId) == null) {
                                playlistDao.insert(
                                    PlaylistEntity(
                                        id = playlistId,
                                        name = "Плейлист",
                                        coverPath = null,
                                        createdAt = updatedAt * 1000L,
                                    ),
                                )
                            }
                            val pos = if (field == "position") {
                                change.optInt("value", 0)
                            } else {
                                change.optJSONObject("value")?.optInt("position", 0) ?: 0
                            }
                            playlistTrackDao.insert(
                                PlaylistTrackEntity(
                                    playlistId = playlistId,
                                    trackId = targetTrackId,
                                    position = pos,
                                    addedAt = updatedAt * 1000L,
                                    updatedAt = updatedAt * 1000L,
                                ),
                            )
                        }
                    }
                }
            }
            "rating" -> {
                val targetTrackId = when {
                    trackDao.findById(id) != null -> id
                    trackDao.findById("server_$id") != null -> "server_$id"
                    else -> id
                }
                if (isDeleted || change.isNull("value")) {
                    trackDao.updateRating(targetTrackId, null, updatedAt * 1000L)
                } else {
                    val stars = change.optInt("value", 0)
                    val rating = if (stars in 1..5) stars else null
                    trackDao.updateRating(targetTrackId, rating, updatedAt * 1000L)
                }
            }
            "track_note" -> {
                val targetTrackId = when {
                    trackDao.findById(id) != null -> id
                    trackDao.findById("server_$id") != null -> "server_$id"
                    else -> id
                }
                if (isDeleted || change.isNull("value")) {
                    trackDao.updateNote(targetTrackId, null, updatedAt * 1000L)
                } else {
                    val note = change.optString("value").ifBlank { null }
                    trackDao.updateNote(targetTrackId, note, updatedAt * 1000L)
                }
            }
            "moment" -> {
                val momentId = id.toLongOrNull()
                if (isDeleted) {
                    if (momentId != null) momentDao.delete(momentId)
                } else {
                    val obj = when (val v = change.opt("value")) {
                        is JSONObject -> v
                        is String -> runCatching { JSONObject(v) }.getOrNull()
                        else -> null
                    }
                    if (obj != null) {
                        val rawTrackId = obj.optString("track_id").ifEmpty { obj.optString("trackId") }
                        val targetTrackId = when {
                            trackDao.findById(rawTrackId) != null -> rawTrackId
                            trackDao.findById("server_$rawTrackId") != null -> "server_$rawTrackId"
                            else -> rawTrackId
                        }
                        val positionMs = if (obj.has("position_ms")) obj.optLong("position_ms") else obj.optLong("positionMs", 0L)
                        val label = obj.optString("label", "")
                        val color = obj.optInt("color", 0)
                        val createdAt = if (obj.has("created_at")) obj.optLong("created_at") else obj.optLong("createdAt", updatedAt * 1000L)
                        val isChapter = if (obj.has("is_chapter")) obj.optBoolean("is_chapter") else obj.optBoolean("isChapter", false)
                        momentDao.insert(
                            MomentEntity(
                                id = momentId ?: 0L,
                                trackId = targetTrackId,
                                positionMs = positionMs,
                                label = label,
                                color = color,
                                createdAt = createdAt,
                                isChapter = isChapter,
                                updatedAt = updatedAt * 1000L,
                            ),
                        )
                    }
                }
            }
            "loop" -> {
                val loopId = id.toLongOrNull()
                if (isDeleted) {
                    if (loopId != null) loopDao.delete(loopId)
                } else {
                    val obj = when (val v = change.opt("value")) {
                        is JSONObject -> v
                        is String -> runCatching { JSONObject(v) }.getOrNull()
                        else -> null
                    }
                    if (obj != null) {
                        val rawTrackId = obj.optString("track_id").ifEmpty { obj.optString("trackId") }
                        val targetTrackId = when {
                            trackDao.findById(rawTrackId) != null -> rawTrackId
                            trackDao.findById("server_$rawTrackId") != null -> "server_$rawTrackId"
                            else -> rawTrackId
                        }
                        val startMs = if (obj.has("start_ms")) obj.optLong("start_ms") else obj.optLong("startMs", 0L)
                        val endMs = if (obj.has("end_ms")) obj.optLong("end_ms") else obj.optLong("endMs", 0L)
                        val name = obj.optString("name", "")
                        val createdAt = if (obj.has("created_at")) obj.optLong("created_at") else obj.optLong("createdAt", updatedAt * 1000L)
                        loopDao.insert(
                            LoopEntity(
                                id = loopId ?: 0L,
                                trackId = targetTrackId,
                                startMs = startMs,
                                endMs = endMs,
                                name = name,
                                createdAt = createdAt,
                                updatedAt = updatedAt * 1000L,
                            ),
                        )
                    }
                }
            }
            "tag" -> {
                if (isDeleted) {
                    tagDao.delete(id)
                } else {
                    val obj = when (val v = change.opt("value")) {
                        is JSONObject -> v
                        is String -> runCatching { JSONObject(v) }.getOrNull()
                        else -> null
                    }
                    val name = obj?.optString("name") ?: (if (field == "name") change.optString("value") else "")
                    val colorArgb = obj?.optInt("color_argb") ?: obj?.optInt("colorArgb") ?: 0
                    if (name.isNotBlank()) {
                        tagDao.insert(TagEntity(id = id, name = name, colorArgb = colorArgb, updatedAt = updatedAt * 1000L))
                    }
                }
            }
            "tag_assignment" -> {
                val (rawTrackId, tagId) = if (id.contains(":")) {
                    val parts = id.split(":", limit = 2)
                    parts[0] to parts[1]
                } else {
                    val obj = change.optJSONObject("value")
                    val tId = obj?.optString("track_id") ?: ""
                    val tgId = obj?.optString("tag_id") ?: ""
                    tId to tgId
                }
                if (rawTrackId.isNotBlank() && tagId.isNotBlank()) {
                    if (isDeleted) {
                        tagDao.unassign(rawTrackId, tagId)
                        tagDao.unassign("server_$rawTrackId", tagId)
                    } else {
                        val targetTrackId = when {
                            trackDao.findById(rawTrackId) != null -> rawTrackId
                            trackDao.findById("server_$rawTrackId") != null -> "server_$rawTrackId"
                            else -> rawTrackId
                        }
                        if (trackDao.findById(targetTrackId) != null && tagDao.allRaw().any { it.id == tagId }) {
                            tagDao.assign(TrackTagEntity(trackId = targetTrackId, tagId = tagId, updatedAt = updatedAt * 1000L))
                        }
                    }
                }
            }
            "listening_history" -> {
                if (!isDeleted) {
                    val obj = when (val v = change.opt("value")) {
                        is JSONObject -> v
                        is String -> runCatching { JSONObject(v) }.getOrNull()
                        else -> null
                    }
                    val rawTrackId = obj?.optString("track_id") ?: if (id.contains(":")) id.substringBefore(":") else id
                    val targetTrackId = when {
                        trackDao.findById(rawTrackId) != null -> rawTrackId
                        trackDao.findById("server_$rawTrackId") != null -> "server_$rawTrackId"
                        else -> rawTrackId
                    }
                    val playedAt = obj?.optLong("played_at")
                        ?: (if (id.contains(":")) id.substringAfter(":").toLongOrNull() ?: (updatedAt * 1000L) else (updatedAt * 1000L))
                    val durationMs = obj?.optLong("duration_ms", 0L) ?: 0L
                    playHistoryDao.insert(
                        PlayHistoryEntity(
                            trackId = targetTrackId,
                            playedAt = playedAt,
                            durationMs = durationMs,
                        ),
                    )
                }
            }
        }
    }

    override suspend fun pushToServer(): Boolean {
        val cfg = serverConfig() ?: return false
        val since = _lastSyncTimestamp.value
        val nowSec = System.currentTimeMillis() / 1000L

        val changes = JSONArray()
        val playlistEntries = playlistTrackDao.allRaw()

        // Серверное состояние ссылается только на id серверной библиотеки. Локальные Room id
        // разных устройств намеренно никогда не уходят в sync-протокол.
        val localTracks = trackDao.allRaw()
        val matchedIds = NamiServerClient.matchTracks(
            cfg,
            localTracks.map { track ->
                NamiServerClient.MatchTrackRequest(
                    title = track.title,
                    artist = track.artistId?.let { artistDao.findById(it)?.name },
                    durationMs = track.durationMs,
                    fileHash = track.fileHash,
                )
            },
        ) ?: return false
        val serverIds = localTracks.zip(matchedIds).mapNotNull { (track, matched) ->
            val direct = track.id.removePrefix("server_").toLongOrNull()
            (direct ?: matched)?.let { track.id to it.toString() }
        }.toMap()

        // 1. Плейлисты и треки в плейлистах
        val playlists = playlistDao.allForSync()
        for (p in playlists) {
            val pUpdatedAt = ((p.updatedAt.takeIf { it > 0 } ?: p.createdAt) / 1000L).coerceAtLeast(1L).coerceAtMost(nowSec)
            if (p.deletedAt != null) {
                val deletedAt = p.deletedAt ?: continue
                changes.put(JSONObject().apply {
                    put("entity", "playlist")
                    put("id", p.id)
                    put("field", "__deleted")
                    put("value", true)
                    put("updated_at", (deletedAt / 1000L).coerceAtLeast(1L).coerceAtMost(nowSec))
                })
                continue
            }
            changes.put(JSONObject().apply {
                put("entity", "playlist")
                put("id", p.id)
                put("field", "name")
                put("value", p.name)
                put("updated_at", pUpdatedAt)
            })

            val tracks = playlistTrackDao.tracksInPlaylist(p.id)
            tracks.forEachIndexed { index, track ->
                val serverTrackId = serverIds[track.id] ?: return@forEachIndexed
                changes.put(JSONObject().apply {
                    put("entity", "playlist_track")
                    put("id", "${p.id}:$serverTrackId")
                    put("field", "position")
                    put("value", index)
                    val entry = playlistEntries.firstOrNull { it.playlistId == p.id && it.trackId == track.id }
                    val changedAt = entry?.updatedAt?.takeIf { it > 0 } ?: entry?.addedAt ?: p.createdAt
                    put("updated_at", (changedAt / 1000L).coerceAtLeast(1L).coerceAtMost(nowSec))
                })
            }
        }

        // 2. Треки с рейтингом или заметкой
        for (track in localTracks) {
            val serverTrackId = serverIds[track.id] ?: continue
            if (track.rating != null) {
                changes.put(JSONObject().apply {
                    put("entity", "rating")
                    put("id", serverTrackId)
                    put("field", "stars")
                    put("value", track.rating)
                    put("updated_at", (track.ratingUpdatedAt / 1000L).coerceAtLeast(1L).coerceAtMost(nowSec))
                })
            }
            if (!track.note.isNullOrBlank()) {
                changes.put(JSONObject().apply {
                    put("entity", "track_note")
                    put("id", serverTrackId)
                    put("field", "note")
                    put("value", track.note)
                    put("updated_at", (track.noteUpdatedAt / 1000L).coerceAtLeast(1L).coerceAtMost(nowSec))
                })
            }
        }

        // 3. Моменты
        val moments = momentDao.allSnapshot()
        for (m in moments) {
            val serverTrackId = serverIds[m.trackId] ?: continue
            val mUpdatedAt = ((m.updatedAt.takeIf { it > 0 } ?: m.createdAt) / 1000L).coerceAtLeast(1L).coerceAtMost(nowSec)
            changes.put(JSONObject().apply {
                put("entity", "moment")
                put("id", m.id.toString())
                put("field", "data")
                put("value", JSONObject().apply {
                    put("track_id", serverTrackId)
                    put("position_ms", m.positionMs)
                    put("label", m.label)
                    put("color", m.color)
                    put("created_at", m.createdAt)
                    put("is_chapter", m.isChapter)
                })
                put("updated_at", mUpdatedAt)
            })
        }

        // 4. Петли
        val loops = loopDao.allSnapshot()
        for (l in loops) {
            val serverTrackId = serverIds[l.trackId] ?: continue
            val lUpdatedAt = ((l.updatedAt.takeIf { it > 0 } ?: l.createdAt) / 1000L).coerceAtLeast(1L).coerceAtMost(nowSec)
            changes.put(JSONObject().apply {
                put("entity", "loop")
                put("id", l.id.toString())
                put("field", "data")
                put("value", JSONObject().apply {
                    put("track_id", serverTrackId)
                    put("start_ms", l.startMs)
                    put("end_ms", l.endMs)
                    put("name", l.name)
                    put("created_at", l.createdAt)
                })
                put("updated_at", lUpdatedAt)
            })
        }

        // 5. Теги и связи
        val tags = tagDao.allRaw()
        for (tag in tags) {
            changes.put(JSONObject().apply {
                put("entity", "tag")
                put("id", tag.id)
                put("field", "data")
                put("value", JSONObject().apply {
                    put("name", tag.name)
                    put("color_argb", tag.colorArgb)
                })
                put("updated_at", (tag.updatedAt / 1000L).coerceAtLeast(1L).coerceAtMost(nowSec))
            })
        }
        val assignments = tagDao.allAssignmentsRaw()
        for (a in assignments) {
            val serverTrackId = serverIds[a.trackId] ?: continue
            changes.put(JSONObject().apply {
                put("entity", "tag_assignment")
                put("id", "$serverTrackId:${a.tagId}")
                put("field", "assigned")
                put("value", true)
                put("updated_at", (a.updatedAt / 1000L).coerceAtLeast(1L).coerceAtMost(nowSec))
            })
        }

        // 6. История прослушиваний
        val historyList = playHistoryDao.since(since * 1000L)
        for (h in historyList) {
            val serverTrackId = serverIds[h.trackId] ?: continue
            val hUpdatedAt = (h.playedAt / 1000L).coerceAtLeast(1L).coerceAtMost(nowSec)
            changes.put(JSONObject().apply {
                put("entity", "listening_history")
                put("id", "$serverTrackId:${h.playedAt}")
                put("field", "history")
                put("value", JSONObject().apply {
                    put("track_id", serverTrackId)
                    put("played_at", h.playedAt)
                    put("duration_ms", h.durationMs)
                })
                put("updated_at", hUpdatedAt)
            })
        }

        if (changes.length() == 0) {
            Log.d(TAG, "pushToServer: no local changes")
            return true
        }

        Log.d(TAG, "pushToServer: pushing ${changes.length()} changes")
        val chunkSize = 5000
        for (i in 0 until changes.length() step chunkSize) {
            val batch = JSONArray()
            val end = minOf(i + chunkSize, changes.length())
            for (j in i until end) {
                batch.put(changes.getJSONObject(j))
            }
            val ok = NamiServerClient.syncPush(cfg, batch)
            if (!ok) {
                Log.w(TAG, "pushToServer: push batch failed at offset $i")
                return false
            }
        }
        val queue = settingsRepository.lastPlaybackQueueTrackIds.value
        val index = settingsRepository.lastPlaybackQueueIndex.value
        queue.getOrNull(index)?.let { localId ->
            serverIds[localId]?.toLongOrNull()?.let { serverId ->
                NamiServerClient.positionPost(
                    cfg,
                    serverId,
                    settingsRepository.lastPlaybackPositionMs.value,
                    (settingsRepository.lastPlaybackPausedAt.value / 1000L).coerceAtLeast(1L),
                )
            }
        }
        flushPendingScrobbles(cfg)
        return true
    }

    private suspend fun flushPendingScrobbles(cfg: NamiServerClient.Config) {
        val pending = runCatching { pendingScrobbleDao.getAll() }.getOrNull() ?: return
        if (pending.isEmpty()) return
        for (item in pending) {
            var serverId = item.serverTrackId
            if (serverId == null) {
                if (item.trackId.startsWith("server_")) {
                    serverId = item.trackId.removePrefix("server_").toLongOrNull()
                } else {
                    val track = trackDao.findById(item.trackId)
                    if (track != null) {
                        val artistName = track.artistId?.let { artistDao.findById(it)?.name }
                        val matches = runCatching {
                            NamiServerClient.matchTrackIds(
                                cfg,
                                listOf(Triple(artistName, track.title, track.durationMs)),
                            )
                        }.getOrNull()
                        serverId = matches?.firstOrNull()
                    }
                }
            }
            if (serverId != null) {
                val ok = runCatching {
                    NamiServerClient.scrobble(cfg, serverId, item.playedAt / 1000)
                }.getOrDefault(false)
                if (ok) {
                    pendingScrobbleDao.deleteById(item.id)
                } else {
                    pendingScrobbleDao.incrementRetry(item.id)
                }
            } else {
                if (item.retryCount > 10) {
                    pendingScrobbleDao.deleteById(item.id)
                } else {
                    pendingScrobbleDao.incrementRetry(item.id)
                }
            }
        }
    }

    override suspend fun sync(): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val pullOk = pullFromServer()
        val pushOk = pushToServer()
        pullOk && pushOk
    }

    private fun serverConfig(): NamiServerClient.Config? {
        val urls = settingsRepository.namiServerUrl.value
            .split('\n', ',').map { it.trim().trimEnd('/') }.filter { it.isNotEmpty() }
        if (urls.isEmpty()) return null
        val token = settingsRepository.namiServerToken.value?.takeIf { it.isNotBlank() } ?: return null
        val cert = settingsRepository.namiServerCertSha256.value
        return NamiServerClient.Config(urls.first(), token, cert, urls)
    }
}
