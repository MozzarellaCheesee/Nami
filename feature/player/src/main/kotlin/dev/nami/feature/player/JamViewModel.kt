package dev.nami.feature.player

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nami.domain.JamRepository
import dev.nami.domain.JamSession
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class JamViewModel @Inject constructor(
    private val jamRepository: JamRepository,
) : ViewModel() {

    val session: StateFlow<JamSession?> = jamRepository.session
    val error: StateFlow<String?> = jamRepository.error
    val connected: StateFlow<Boolean> = jamRepository.connected
    val isServerConfigured: StateFlow<Boolean> = jamRepository.isServerConfigured
    val activeHostUrl: StateFlow<String?> = jamRepository.activeHostUrl
    val allHostUrls: StateFlow<List<String>> = jamRepository.allHostUrls
    val recentHosts: StateFlow<List<String>> = jamRepository.recentHosts

    fun createRoom() = jamRepository.createRoom()
    fun joinRoom(code: String, hostUrl: String? = null) = jamRepository.joinRoom(code, hostUrl)
    fun leave() = jamRepository.leave()
    fun play(serverTrackId: Long, positionMs: Long = 0) = jamRepository.play(serverTrackId, positionMs)
    fun seek(positionMs: Long) = jamRepository.seek(positionMs)
    fun addToQueue(serverTrackId: Long) = jamRepository.addToQueue(serverTrackId)
    fun clearError() = jamRepository.clearError()

    /** Проверяет, является ли адрес локальным (LAN/loopback). */
    fun isLocalHost(url: String?): Boolean {
        if (url.isNullOrBlank()) return true
        val host = url.removePrefix("http://").removePrefix("https://").substringBefore(':').substringBefore('/')
        return host == "localhost" ||
            host == "127.0.0.1" ||
            host.startsWith("192.168.") ||
            host.startsWith("10.") ||
            (host.startsWith("172.") && runCatching {
                val sec = host.split(".")[1].toInt()
                sec in 16..31
            }.getOrDefault(false))
    }

    /**
     * Разбирает текст из буфера обмена (ссылка, приглашение, код):
     * Возвращает Pair(code, hostUrl) либо null, если это не приглашение в Jam.
     */
    fun parseJamInvite(text: String?): Pair<String, String?>? {
        val raw = text?.trim() ?: return null
        if (raw.isBlank()) return null

        // 1. Ссылка nami://jam?code=...
        if (raw.contains("jam?code=") || raw.contains("jam&code=") || raw.startsWith("nami://jam")) {
            val uri = runCatching { android.net.Uri.parse(raw) }.getOrNull()
            if (uri != null) {
                val code = uri.getQueryParameter("code")
                val host = uri.getQueryParameter("host") ?: uri.getQueryParameter("hosts")?.split(",")?.firstOrNull()
                if (!code.isNullOrBlank()) return code to host
            }
        }

        // 2. Веб-ссылка http(s)://.../jam?code=... или /jam/...
        if (raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true)) {
            val uri = runCatching { android.net.Uri.parse(raw) }.getOrNull()
            if (uri != null && uri.path?.contains("jam") == true) {
                val code = uri.getQueryParameter("code") ?: uri.lastPathSegment?.takeIf { it != "jam" }
                val base = "${uri.scheme}://${uri.authority}"
                if (!code.isNullOrBlank()) return code to base
            }
        }

        // 3. Формат CODE@HOST
        if (raw.contains("@") && !raw.contains(" ") && !raw.contains("\n")) {
            val parts = raw.split("@", limit = 2)
            if (parts[0].length in 4..8 && parts[1].isNotBlank()) {
                return parts[0] to parts[1]
            }
        }

        // 4. Текст с "Код комнаты: ABC234"
        val codeMatch = Regex("(?:код(?:\\s+комнаты)?|code)[:\\s]+([A-Za-z0-9]{4,8})", RegexOption.IGNORE_CASE).find(raw)
        if (codeMatch != null) {
            val code = codeMatch.groupValues[1]
            val linkMatch = Regex("(https?://[^\\s]+|nami://[^\\s]+)").find(raw)
            val link = linkMatch?.value
            val host = if (link != null) {
                val uri = runCatching { android.net.Uri.parse(link) }.getOrNull()
                uri?.getQueryParameter("host") ?: uri?.getQueryParameter("hosts")?.split(",")?.firstOrNull() ?: uri?.let { "${it.scheme}://${it.authority}" }
            } else null
            return code to host
        }

        // 5. Одиночный 6-значный код комнаты
        val clean = raw.uppercase().filter { it in "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" }
        if (clean.length == 6 && raw.trim().length == 6) {
            return clean to null
        }

        return null
    }
}
