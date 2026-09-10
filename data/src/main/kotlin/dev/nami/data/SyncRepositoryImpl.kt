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
        
        // ponytail: парсим changes и применяем к локальной DB
        val changes = result.optJSONArray("changes") ?: JSONArray()
        val currentTs = result.optLong("current_ts", System.currentTimeMillis())
        
        Log.d(TAG, "pullFromServer: got ${changes.length()} changes since=$since")
        
        // TODO: применить changes к TrackDao (play_count, last_played), MomentDao, LoopDao
        // ponytail: пока заглушка - просто обновляем timestamp
        
        _lastSyncTimestamp.value = currentTs
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
