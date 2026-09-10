package dev.nami.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.nami.domain.ServerLibraryRepository
import dev.nami.domain.ServerTrackMeta
import dev.nami.domain.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ServerLibraryRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
) : ServerLibraryRepository {

    /** Офлайн-кеш серверных треков - в приватном хранилище приложения. */
    private val cacheDir: File by lazy {
        File(context.getExternalFilesDir(null) ?: context.filesDir, "ServerCache").apply { mkdirs() }
    }

    override fun isServerActive(): Boolean =
        settingsRepository.namiServerPreferred.value &&
            settingsRepository.namiServerToken.value != null &&
            settingsRepository.namiServerUrl.value.isNotBlank()

    private fun activeConfig(): NamiServerClient.Config? {
        if (!isServerActive()) return null
        val token = settingsRepository.namiServerToken.value ?: return null
        val cert = settingsRepository.namiServerCertSha256.value
        val bases = settingsRepository.namiServerUrl.value
            .split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() }
        if (bases.isEmpty()) return null
        return NamiServerClient.Config(bases.first(), token, cert, bases)
    }

    override suspend fun listTracks(limit: Int, offset: Int): List<ServerTrackMeta>? =
        withContext(Dispatchers.IO) {
            val cfg = activeConfig() ?: return@withContext null
            val arr = NamiServerClient.tracks(cfg, limit, offset) ?: return@withContext null
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optLong("id", -1)
                if (id < 0) return@mapNotNull null
                ServerTrackMeta(
                    id = id,
                    title = o.optString("title", ""),
                    artist = o.optString("artist", ""),
                    album = o.optString("album").takeIf { it.isNotBlank() },
                    durationMs = o.optLong("duration_ms", 0L),
                )
            }
        }

    override suspend fun downloadTrack(serverTrackId: Long): File? =
        withContext(Dispatchers.IO) {
            val cfg = activeConfig() ?: return@withContext null
            val dest = fileFor(serverTrackId)
            if (dest.exists() && dest.length() > 0) return@withContext dest
            val tmp = File(dest.parentFile, "${dest.name}.part")
            val ok = NamiServerClient.downloadTrack(cfg, serverTrackId, tmp)
            if (!ok || tmp.length() == 0L) {
                tmp.delete()
                return@withContext null
            }
            if (!tmp.renameTo(dest)) {
                tmp.delete()
                return@withContext null
            }
            dest
        }

    override fun cachedFile(serverTrackId: Long): File? =
        fileFor(serverTrackId).takeIf { it.exists() && it.length() > 0 }

    override fun cachedTrackIds(): Set<Long> =
        cacheDir.listFiles { f -> f.isFile && f.name.endsWith(".audio") }
            ?.mapNotNull { it.nameWithoutExtension.toLongOrNull() }
            ?.toSet()
            ?: emptySet()

    override fun removeFromCache(serverTrackId: Long) {
        fileFor(serverTrackId).delete()
    }

    override suspend fun uploadLocalTrack(path: String): String? = withContext(Dispatchers.IO) {
        val cfg = activeConfig() ?: return@withContext null
        val file = File(path)
        if (!file.exists() || file.length() == 0L) return@withContext null
        val res = NamiServerClient.uploadTrack(cfg, file) ?: return@withContext null
        val dup = res.optString("duplicate_of").takeIf { it.isNotBlank() && it != "null" }
        if (dup != null) "Уже есть на сервере" else "Загружен на сервер"
    }

    override suspend fun createGuestLink(
        title: String,
        tracks: List<Triple<String?, String, Long>>,
        ttlSecs: Long?,
        maxPlays: Int?,
    ): String? = withContext(Dispatchers.IO) {
        val cfg = activeConfig() ?: return@withContext null
        val ids = NamiServerClient.matchTrackIds(cfg, tracks)
            ?.filterNotNull()
            ?.distinct()
            ?: return@withContext null
        if (ids.isEmpty()) return@withContext null
        NamiServerClient.createShare(cfg, title, ids, ttlSecs, maxPlays)
    }

    // Расширение не по формату (сервер отдаёт что попросили) - ExoPlayer определяет по содержимому.
    private fun fileFor(id: Long) = File(cacheDir, "$id.audio")
}
