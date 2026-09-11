package dev.nami.domain

import dev.nami.core.model.PlaylistId
import dev.nami.core.model.TrackId
import kotlinx.coroutines.flow.Flow

/**
 * Статус импорта одного трека в гибридном режиме (Spotify метаданные + аудио из VK).
 */
enum class HybridTrackStatus {
    PENDING,        // В очереди
    SEARCHING_VK,   // Поиск аудио в VK
    DOWNLOADING,    // Скачивание аудиопотока
    IMPORTING,      // Запись тегов и регистрация в библиотеке Nami
    DONE,           // Успешно импортирован
    NEEDS_REVIEW,   // Требуется подтверждение / ручной выбор пользователем
    NOT_FOUND,      // В каталоге VK ничего подходящего не найдено
    ERROR           // Ошибка скачивания или импорта
}

/**
 * Кандидат из VK для ручного выбора пользователем при [HybridTrackStatus.NEEDS_REVIEW].
 */
data class HybridCandidate(
    val vkId: Long,
    val ownerId: Long,
    val title: String,
    val artist: String,
    val durationSec: Int,
    val url: String?,
    val score: Int,
    val reason: String?,
)

/**
 * Состояние одного трека в очереди гибридного импорта.
 */
data class HybridImportItem(
    val spotifyId: String,
    val spotifyTitle: String,
    val spotifyArtist: String,
    val spotifyAlbum: String?,
    val spotifyDurationMs: Long,
    val spotifyCoverUrl: String?,
    val spotifyYear: String?,
    val status: HybridTrackStatus = HybridTrackStatus.PENDING,
    val localTrackId: TrackId? = null,
    val candidates: List<HybridCandidate> = emptyList(),
    val errorMessage: String? = null,
)

/**
 * Сводка прогресса гибридного импорта.
 */
data class HybridImportProgress(
    val total: Int,
    val done: Int,
    val needsReview: Int,
    val notFound: Int,
    val inProgress: Int,
    val items: List<HybridImportItem>,
)

interface HybridImportRepository {
    /**
     * Запуск гибридного импорта для списка треков из Spotify.
     * Недостающие треки ищутся в VK, скачиваются, тегируются метаданными из Spotify
     * и опционально добавляются в [targetPlaylistId].
     */
    fun startHybridImport(
        tracks: List<HybridImportItem>,
        targetPlaylistId: PlaylistId?,
    ): Flow<HybridImportProgress>

    /**
     * Ручной выбор кандидата из VK для трека со статусом [HybridTrackStatus.NEEDS_REVIEW].
     */
    suspend fun resolveCandidateManually(
        item: HybridImportItem,
        chosenCandidate: HybridCandidate,
        targetPlaylistId: PlaylistId?,
    ): Result<TrackId>
}
