package dev.nami.app.discord

import android.os.SystemClock
import dev.nami.data.AppSettingsRepository
import dev.nami.data.NamiServerClient
import dev.nami.domain.DiscordPresence
import dev.nami.domain.JamRepository
import dev.nami.domain.LocalShareRepository
import dev.nami.domain.PlayerRepository
import dev.nami.domain.discordListeningMode
import dev.nami.domain.discordPresence
import dev.nami.domain.validDiscordApplicationId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/** Perceived-idle cutoff: how long nothing may play before a lingering "paused" activity gets
 * cleared for real, in both DiscordPresenceManager report paths. */
private const val IDLE_TIMEOUT_MS = 120_000L

/** Runs with the application process, including background playback; it is not owned by a screen. */
@Singleton
class DiscordPresenceManager @Inject constructor(
    private val settings: AppSettingsRepository,
    private val player: PlayerRepository,
    private val jam: JamRepository,
    private val share: LocalShareRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _status = MutableStateFlow("Выключено")
    val status: StateFlow<String> = _status
    private var started = false
    private val serverSelected = MutableStateFlow(false)

    fun start() {
        if (started) return
        started = true
        scope.launch {
            combine(settings.namiServerToken, settings.namiServerUrl, settings.namiServerCertSha256) { token, urls, cert ->
                val bases = urls.split('\n', ',').map { it.trim().trimEnd('/') }
                    .filter { it.isNotEmpty() }.map { if (it.contains("://")) it else "https://$it" }
                if (token.isNullOrBlank() || bases.isEmpty()) null
                else NamiServerClient.Config(bases.first(), token, cert, bases)
            }.collectLatest { config ->
                serverSelected.value = false
                if (config != null) reportToServer(config)
            }
        }
        scope.launch {
            combine(settings.discordPresenceEnabled, settings.discordApplicationId, serverSelected) { enabled, id, server -> Triple(enabled, id, server) }
                .collectLatest { (enabled, id, server) ->
                    when {
                        server -> _status.value = "Активность через ваш аккаунт сервера Nami"
                        !enabled -> _status.value = "Выключено"
                        !validDiscordApplicationId(id) -> _status.value = "Укажите Discord Application ID"
                        !DiscordSdk.available -> _status.value = "В этой сборке нет поддержки Discord. Нужна сборка с Discord SDK."
                        else -> runPresence(id)
                    }
                }
        }
    }

    private fun currentPresence(): DiscordPresence? = discordPresence(
        player.state.value, player.queue.value.nowPlaying,
        discordListeningMode(jam.session.value, jam.connected.value,
            share.listenTogetherGuestState.value != null,
            share.serverRunning.value && share.listenTogetherHostEnabled.value,
            share.serverRunning.value && share.dropTrack.value != null),
        settings.discordShowMode.value, System.currentTimeMillis(),
    )

    /** Artwork only makes sense for the server-relay path: the publisher there runs on the
     * server, which can host a real public HTTPS URL. The direct phone-to-Discord RPC path
     * (runPresence()) deliberately never attaches one - see the note in discord_presence.cpp
     * ("Local artwork and private server URLs must never be published as public assets"), it
     * has no server in front of it to vet or proxy the URL. */
    private fun withArtwork(presence: DiscordPresence, config: NamiServerClient.Config): DiscordPresence {
        val serverId = presence.trackId.removePrefix("server_").removePrefix("jam_").toLongOrNull()
            ?.takeIf { presence.trackId.startsWith("server_") || presence.trackId.startsWith("jam_") }
            ?: return presence
        // Not serverAudio.serverArtworkUrl() - it prefers the local offline-cache file:// path
        // when the artwork happens to already be cached on this phone (the common case), which
        // is useless to a publisher running on the SERVER. Build the network URL directly.
        val url = "${config.baseUrl}/api/tracks/$serverId/artwork?token=${config.token}"
            .takeIf { it.startsWith("https://") }
        return if (url != null) presence.copy(artworkUrl = url) else presence
    }

    private fun changed(previous: DiscordPresence?, desired: DiscordPresence?): Boolean = when {
        previous == null || desired == null -> previous != desired
        else -> previous.trackId != desired.trackId || previous.title != desired.title ||
            previous.description != desired.description || abs(previous.startSeconds - desired.startSeconds) > 2 ||
            (previous.endSeconds == null) != (desired.endSeconds == null) ||
            abs((previous.endSeconds ?: 0) - (desired.endSeconds ?: 0)) > 2
    }

    /** OAuth linking is the opt-in. No Discord token or Application ID is needed on the phone. */
    private suspend fun reportToServer(config: NamiServerClient.Config) {
        var available = false
        var checkedAt = -30_000L
        var sentAt = -15_000L
        var last: DiscordPresence? = null
        var observed: DiscordPresence? = null
        var takeover = false
        var mayHavePublished = false
        // Nami player only reports "listening" while actually playing - a pause, or the gap
        // during a track change/crossfade handover, would otherwise read as null here and this
        // loop would immediately publish "stopped". The user wants activity to persist through
        // that (as most desktop clients do) and disappear only when the app itself goes away -
        // sticking to the last real presence and never letting it go back to null (short of the
        // whole coroutine ending, which happens on app exit/server disconnect/feature toggle)
        // is what makes that true without inventing a separate "paused" wire state.
        var sticky: DiscordPresence? = null
        // Sticky above keeps activity alive through a short pause/track-change gap, but a pause
        // left sitting for real (user walked away, phone locked) shouldn't show Discord activity
        // forever - clear it once nothing has actually played for IDLE_TIMEOUT_MS straight.
        var idleSince = SystemClock.elapsedRealtime()
        try {
            while (true) {
                val raw = currentPresence()?.let { withArtwork(it, config) }
                val now = SystemClock.elapsedRealtime()
                if (raw != null) {
                    sticky = raw
                    idleSince = now
                } else if (sticky != null && now - idleSince >= IDLE_TIMEOUT_MS) {
                    sticky = null
                }
                val desired = sticky
                // Discord has no native "paused" state for a plain Activity's timestamps - they're
                // just a fixed epoch it counts up from client-side forever, so while raw is null
                // (paused, or a track-change/crossfade gap) the server is told to omit them
                // entirely instead of freezing/faking a value: no bar shown at all beats one that
                // has to be kept artificially in sync.
                val paused = raw == null && desired != null
                if (raw != null && (observed == null || observed.trackId != raw.trackId)) takeover = true
                observed = raw
                if (desired != null && now - checkedAt >= 30_000) {
                    val response = withContext(Dispatchers.IO) { NamiServerClient.discordAccount(config) }
                    checkedAt = SystemClock.elapsedRealtime()
                    available = response?.let { (code, account) ->
                        code == 200 && account.optBoolean("linked") && account.optBoolean("presence_supported") &&
                            !account.optBoolean("needs_reconnect")
                    } == true
                    // Keep direct RPC off during a temporary server outage after successful linking.
                    if (response?.first == 200) serverSelected.value = available
                    continue // Re-read the player after the network wait; it may already be paused.
                }
                if ((available && desired != null && (changed(last, desired) || now - sentAt >= 15_000)) ||
                    (desired == null && mayHavePublished)) {
                    val payload = JSONObject().put("playing", desired != null).put("takeover", takeover).put("paused", paused)
                    desired?.let {
                        val duration = it.endSeconds?.let { end -> (end - it.startSeconds) * 1000 } ?: 0
                        val position = (System.currentTimeMillis() - it.startSeconds * 1000).coerceAtLeast(0)
                        payload.put("title", it.title.filterNot(Char::isISOControl))
                            // 116, not the server's old 128: leaves room for the bridge's own
                            // " · На паузе" suffix (see discord_presence.rs) without the whole
                            // update getting rejected once truncation pushed the pause label past
                            // Discord's 128-char state limit.
                            .put("description", it.description.filterNot(Char::isISOControl).take(116))
                            .put("position_ms", if (duration > 0) position.coerceAtMost(duration) else position)
                            .put("duration_ms", duration)
                        it.artworkUrl?.let { url -> payload.put("artwork_url", url) }
                        payload.put("show_app_icon", settings.discordShowAppIcon.value)
                    }
                    // A failed request may already have reached the server, so always send a later stop.
                    mayHavePublished = mayHavePublished || desired != null
                    val response = withContext(Dispatchers.IO) { NamiServerClient.discordPlayback(config, payload) }
                    sentAt = SystemClock.elapsedRealtime()
                    if (response?.first == 200) {
                        last = desired
                        takeover = false
                        if (desired == null) mayHavePublished = false
                    } else {
                        available = false
                        if (response?.first in listOf(401, 403, 409)) { last = null; mayHavePublished = false }
                        delay(5_000)
                    }
                }
                delay(1_000)
            }
        } finally {
            serverSelected.value = false
            if (mayHavePublished) withContext(NonCancellable + Dispatchers.IO) {
                NamiServerClient.discordPlayback(config, JSONObject().put("playing", false))
            }
        }
    }

    private suspend fun runPresence(id: String) {
        while (!DiscordSdk.ready) {
            _status.value = "Откройте Nami для подключения Discord"
            delay(500)
        }
        var opened = false
        try {
            DiscordSdk.open(id)
            opened = true
            var last: DiscordPresence? = null
            var sentAt = 0L
            // Same "stick to the last real track" reasoning as reportToServer() below - a pause
            // or a track-change gap must not clear the activity, only closing/disabling this does
            // (which tears down this whole coroutine and runs the DiscordSdk.clear() in finally).
            var sticky: DiscordPresence? = null
            // Same idle cutoff as reportToServer() - a pause left sitting for real, not just a
            // brief gap, must eventually clear the activity instead of showing it forever.
            var idleSince = SystemClock.elapsedRealtime()
            var lastPaused = false
            while (true) {
                val raw = currentPresence()
                val now = SystemClock.elapsedRealtime()
                if (raw != null) {
                    sticky = raw
                    idleSince = now
                } else if (sticky != null && now - idleSince >= IDLE_TIMEOUT_MS) {
                    sticky = null
                }
                val desired = sticky
                val paused = raw == null && desired != null
                val result = DiscordSdk.poll()
                if (desired == null) {
                    if (last != null) {
                        DiscordSdk.clear()
                        last = null
                    }
                    _status.value = "Готово · ожидает воспроизведения"
                } else {
                    // paused != lastPaused forces an immediate republish on pause/resume - changed()
                    // alone wouldn't notice, it doesn't compare pause state at all.
                    val changed = changed(last, desired) || paused != lastPaused
                    // Coalesce seek bursts; refresh after reconnect even when the track is unchanged.
                    val elapsed = now - sentAt
                    if (last == null || (elapsed >= 5000 && changed && result != 0) ||
                        (result == -1 && elapsed >= 15000) || elapsed >= 30000) {
                        DiscordSdk.publish(desired, paused)
                        last = desired
                        lastPaused = paused
                        sentAt = now
                        _status.value = "Отправка активности…"
                    } else {
                        _status.value = when (result) {
                            1 -> "Активность опубликована"
                            -1 -> "Не удалось опубликовать: проверьте ID и вход в Discord"
                            else -> "Ожидание Discord…"
                        }
                    }
                }
                delay(250)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            _status.value = "Ошибка подключения Discord. Выключите и включите интеграцию."
        } catch (_: LinkageError) {
            _status.value = "Discord недоступен: несовместимая нативная библиотека в сборке."
        } finally {
            if (opened) withContext(NonCancellable) {
                // Let the asynchronous clear leave the process before releasing the SDK client.
                runCatching {
                    DiscordSdk.clear()
                    repeat(4) { DiscordSdk.poll(); delay(50) }
                    DiscordSdk.close()
                }
            }
        }
    }
}
