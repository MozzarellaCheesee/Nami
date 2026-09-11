package dev.nami.data.vk

/** Модель аудиозаписи из VK Музыки. */
data class VkTrack(
    val id: Long,
    val ownerId: Long,
    val title: String,
    val artist: String,
    val durationSec: Int,
    val url: String?,            // Прямая ссылка на mp3-поток
    val albumCoverUrl: String?,  // Обложка трека/альбома из VK (если есть)
    val approxBitrateKbps: Int? = null, // Примерный битрейт (320, 256, 128)
)

data class VkSearchResult(
    val count: Int,
    val items: List<VkTrack>,
    val errorMessage: String? = null,
)

enum class MatchStatus {
    CONFIDENT,    // Скор >= 60, надёжное совпадение
    NEEDS_REVIEW, // Скор 40..59, требует подтверждения пользователем
    NOT_FOUND,    // Скор < 40, подходящих кандидатов нет
}

data class VkMatchCandidate(
    val vkTrack: VkTrack,
    val score: Int,
    val status: MatchStatus,
    val reason: String? = null,
)
