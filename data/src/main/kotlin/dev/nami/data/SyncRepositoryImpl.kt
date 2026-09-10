package dev.nami.data

import android.util.Log
import dev.nami.domain.SettingsRepository
import dev.nami.domain.SyncRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SyncRepositoryImpl"

/**
 * ponytail: минимальная реализация sync для этапа 6. Пока только pull/push ручками,
 * без автоматической синхронизации при локальных изменениях. WebSocket добавится позже.
 */
@Singleton
class SyncRepositoryImpl @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : SyncRepository {

    private val _lastSyncTimestamp = MutableStateFlow(0L)
    override val lastSyncTimestamp: StateFlow<Long> = _lastSyncTimestamp

    override suspend fun pullFromServer(): Boolean {
        val cfg = serverConfig() ?: return false
        val since = _lastSyncTimestamp.value
        val result = NamiServerClient.syncPull(cfg, since) ?: return false
        
        // Сервер отдаёт { now, changes, truncated } - см. sync.rs::Pull.
        val changes = result.optJSONArray("changes") ?: JSONArray()
        val now = result.optLong("now", System.currentTimeMillis() / 1000)

        Log.d(TAG, "pullFromServer: got ${changes.length()} changes since=$since now=$now")

        // ponytail: применение изменений к Room (rating, moment, loop, listening_history)
        // требует миграции сущностей под updated_at/deleted_at по полям - отдельная задача.
        // Пока pull только двигает метку, чтобы push-сторона не слала уже применённое.

        _lastSyncTimestamp.value = now
        return true
    }

    override suspend fun pushToServer(): Boolean {
        val cfg = serverConfig() ?: return false
        
        // ponytail: собираем локальные изменения с момента последнего sync
        val changes = JSONArray()
        // TODO: собрать TrackEntity.play_count > 0 OR last_played != null
        // TODO: собрать MomentEntity where updated_at > lastSyncTimestamp
        // TODO: собрать LoopEntity where updated_at > lastSyncTimestamp
        
        if (changes.length() == 0) {
            Log.d(TAG, "pushToServer: no local changes")
            return true
        }
        
        Log.d(TAG, "pushToServer: pushing ${changes.length()} changes")
        return NamiServerClient.syncPush(cfg, changes)
    }

    override suspend fun sync(): Boolean {
        val pullOk = pullFromServer()
        val pushOk = pushToServer()
        return pullOk && pushOk
    }

    // ponytail: DRY helper
    private fun serverConfig(): NamiServerClient.Config? {
        if (!settingsRepository.namiServerPreferred.value) return null
        val urls = settingsRepository.namiServerUrl.value?.split("\n")?.filter { it.isNotBlank() } ?: return null
        val token = settingsRepository.namiServerToken.value ?: return null
        val cert = settingsRepository.namiServerCertSha256.value
        return NamiServerClient.Config(urls.first(), token, cert, urls)
    }
}
