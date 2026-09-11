package dev.nami.feature.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.ViewModel
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nami.domain.JamRepository
import dev.nami.domain.JamSession
import kotlinx.coroutines.flow.StateFlow
import java.util.EnumMap
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
    val discoveredRooms: StateFlow<List<dev.nami.domain.DiscoveredJamRoom>> = jamRepository.discoveredRooms

    fun startDiscovery() = jamRepository.startDiscovery()
    fun stopDiscovery() = jamRepository.stopDiscovery()

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

    /**
     * Декодирует QR-код из изображения (выбранного из галереи или скриншота).
     */
    fun decodeQrFromUri(context: Context, uri: Uri): String? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()
            if (bitmap != null) decodeQrFromBitmap(bitmap) else null
        } catch (e: Exception) {
            null
        }
    }

    fun decodeQrFromBitmap(bitmap: Bitmap): String? {
        return try {
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            val source = RGBLuminanceSource(width, height, pixels)
            val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
            val hints = EnumMap<DecodeHintType, Any>(DecodeHintType::class.java).apply {
                put(DecodeHintType.POSSIBLE_FORMATS, listOf(com.google.zxing.BarcodeFormat.QR_CODE))
                put(DecodeHintType.TRY_HARDER, true)
            }
            val result = MultiFormatReader().decode(binaryBitmap, hints)
            result.text
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Пытается подключиться к комнате по сырому тексту из QR-кода.
     * Возвращает true, если код или ссылка были распознаны.
     */
    fun joinFromQrText(text: String): Boolean {
        val invite = parseJamInvite(text)
        return if (invite != null) {
            joinRoom(invite.first, invite.second)
            true
        } else {
            val clean = text.trim().uppercase().filter { it in "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" }
            if (clean.length == 6) {
                joinRoom(clean, null)
                true
            } else {
                false
            }
        }
    }
}
