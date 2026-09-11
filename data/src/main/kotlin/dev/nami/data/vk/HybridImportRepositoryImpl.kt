package dev.nami.data.vk

import android.content.Context
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.nami.core.model.PlaylistId
import dev.nami.core.model.TrackId
import dev.nami.data.spotify.SpotifyTrackMeta
import dev.nami.domain.HybridCandidate
import dev.nami.domain.HybridImportItem
import dev.nami.domain.HybridImportProgress
import dev.nami.domain.HybridImportRepository
import dev.nami.domain.HybridTrackStatus
import dev.nami.domain.ImportSource
import dev.nami.domain.LibraryRepository
import dev.nami.domain.PlaylistRepository
import dev.nami.domain.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HybridImportRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val libraryRepository: LibraryRepository,
    private val playlistRepository: PlaylistRepository,
    private val settingsRepository: SettingsRepository,
) : HybridImportRepository {

    private companion object {
        const val TAG = "HybridImport"
    }

    override fun startHybridImport(
        tracks: List<HybridImportItem>,
        targetPlaylistId: PlaylistId?,
    ): Flow<HybridImportProgress> = channelFlow {
        val token = settingsRepository.vkAccessToken.value
        if (token.isNullOrBlank()) {
            val failedItems = tracks.map {
                it.copy(status = HybridTrackStatus.ERROR, errorMessage = "VK токен не настроен")
            }
            send(buildProgress(failedItems))
            return@channelFlow
        }

        val parallelism = settingsRepository.vkParallelDownloads.value.coerceIn(1, 3)
        val semaphore = Semaphore(parallelism)

        val itemsState = tracks.toMutableList()
        send(buildProgress(itemsState))

        withContext(Dispatchers.IO) {
            val deferreds = itemsState.indices.map { index ->
                async {
                    semaphore.withPermit {
                        processTrack(
                            item = itemsState[index],
                            token = token,
                            targetPlaylistId = targetPlaylistId,
                            onUpdate = { updated ->
                                synchronized(itemsState) {
                                    itemsState[index] = updated
                                    val progress = buildProgress(itemsState)
                                    trySend(progress)
                                }
                            }
                        )
                    }
                }
            }
            deferreds.awaitAll()
        }

        send(buildProgress(itemsState))
    }

    override suspend fun resolveCandidateManually(
        item: HybridImportItem,
        chosenCandidate: HybridCandidate,
        targetPlaylistId: PlaylistId?,
    ): Result<TrackId> = withContext(Dispatchers.IO) {
        val downloadUrl = chosenCandidate.url
            ?: return@withContext Result.failure(IllegalStateException("У выбранного трека нет ссылки на скачивание"))

        try {
            val trackId = downloadAndImport(
                item = item,
                downloadUrl = downloadUrl,
                targetPlaylistId = targetPlaylistId,
            ) ?: return@withContext Result.failure(IllegalStateException("Не удалось импортировать трек в библиотеку"))

            Result.success(trackId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun processTrack(
        item: HybridImportItem,
        token: String,
        targetPlaylistId: PlaylistId?,
        onUpdate: (HybridImportItem) -> Unit,
    ) {
        onUpdate(item.copy(status = HybridTrackStatus.SEARCHING_VK))

        val searchQuery = "${item.spotifyArtist} ${item.spotifyTitle}"
        var vkResults = VkMusicClient.searchAudio(searchQuery, token, count = 15)

        if (vkResults.isEmpty()) {
            // Запасной запрос: только название трека
            vkResults = VkMusicClient.searchAudio(item.spotifyTitle, token, count = 15)
        }

        if (vkResults.isEmpty()) {
            onUpdate(item.copy(status = HybridTrackStatus.NOT_FOUND, errorMessage = "Не найдено в каталоге VK"))
            return
        }

        val spotifyMeta = SpotifyTrackMeta(
            spotifyId = item.spotifyId,
            title = item.spotifyTitle,
            artists = listOf(item.spotifyArtist),
            albumName = item.spotifyAlbum,
            trackNo = 1,
            durationMs = item.spotifyDurationMs,
            isrc = null,
            coverUrl = item.spotifyCoverUrl,
            year = item.spotifyYear,
        )

        val scoredCandidates = VkTrackMatcher.scoreAll(spotifyMeta, vkResults)

        if (scoredCandidates.isEmpty()) {
            onUpdate(item.copy(status = HybridTrackStatus.NOT_FOUND, errorMessage = "Все кандидаты отсеяны по длительности"))
            return
        }

        val bestCandidate = scoredCandidates.first()

        val hybridCandidates = scoredCandidates.take(5).map {
            HybridCandidate(
                vkId = it.vkTrack.id,
                ownerId = it.vkTrack.ownerId,
                title = it.vkTrack.title,
                artist = it.vkTrack.artist,
                durationSec = it.vkTrack.durationSec,
                url = it.vkTrack.url,
                score = it.score,
                reason = it.reason,
            )
        }

        when (bestCandidate.status) {
            MatchStatus.CONFIDENT -> {
                val downloadUrl = bestCandidate.vkTrack.url
                if (downloadUrl == null) {
                    onUpdate(
                        item.copy(
                            status = HybridTrackStatus.NEEDS_REVIEW,
                            candidates = hybridCandidates,
                            errorMessage = "У лучшего совпадения нет прямой ссылки на аудио",
                        )
                    )
                    return
                }

                onUpdate(item.copy(status = HybridTrackStatus.DOWNLOADING))

                val trackId = downloadAndImport(
                    item = item,
                    downloadUrl = downloadUrl,
                    targetPlaylistId = targetPlaylistId,
                )

                if (trackId != null) {
                    onUpdate(item.copy(status = HybridTrackStatus.DONE, localTrackId = trackId))
                } else {
                    onUpdate(item.copy(status = HybridTrackStatus.ERROR, errorMessage = "Ошибка записи файла или тегов"))
                }
            }

            MatchStatus.NEEDS_REVIEW -> {
                onUpdate(
                    item.copy(
                        status = HybridTrackStatus.NEEDS_REVIEW,
                        candidates = hybridCandidates,
                        errorMessage = bestCandidate.reason,
                    )
                )
            }

            MatchStatus.NOT_FOUND -> {
                onUpdate(
                    item.copy(
                        status = HybridTrackStatus.NOT_FOUND,
                        candidates = hybridCandidates,
                        errorMessage = "Слишком низкое соответствие (скор ${bestCandidate.score})",
                    )
                )
            }
        }
    }

    private suspend fun downloadAndImport(
        item: HybridImportItem,
        downloadUrl: String,
        targetPlaylistId: PlaylistId?,
    ): TrackId? {
        val scratchDir = File(File(context.cacheDir, "vk_import"), UUID.randomUUID().toString())
        scratchDir.mkdirs()

        val safeFileName = "${item.spotifyArtist} - ${item.spotifyTitle}.mp3"
            .take(120)
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")

        val audioFile = File(scratchDir, safeFileName)

        try {
            if (!downloadFile(downloadUrl, audioFile)) {
                Log.w(TAG, "Не удалось скачать аудио: $downloadUrl")
                return null
            }

            val tracksBefore = libraryRepository.allTracksOrdered().map { it.id.value }.toSet()

            libraryRepository.import(ImportSource.Files(listOf(Uri.fromFile(audioFile).toString()))).collect { }

            val importedTrack = libraryRepository.allTracksOrdered().firstOrNull { it.id.value !in tracksBefore }
                ?: return null

            // Тегирование метаданными из Spotify
            libraryRepository.renameTrack(importedTrack.id, item.spotifyTitle)
            libraryRepository.batchEditTracks(
                ids = listOf(importedTrack.id),
                artistName = item.spotifyArtist,
                albumName = item.spotifyAlbum,
                year = item.spotifyYear?.toIntOrNull(),
                genre = null,
            )

            // Загрузка и установка обложки из Spotify
            val coverUrl = item.spotifyCoverUrl
            if (coverUrl != null && settingsRepository.spotifyCoversEnabled.value) {
                val coverFile = File(scratchDir, "cover.jpg")
                if (downloadFile(coverUrl, coverFile)) {
                    runCatching {
                        libraryRepository.setTrackCover(importedTrack.id, Uri.fromFile(coverFile).toString())
                    }
                }
            }

            // Добавление в целевой плейлист
            if (targetPlaylistId != null) {
                runCatching {
                    playlistRepository.addTrack(targetPlaylistId, importedTrack.id)
                }
            }

            return importedTrack.id
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка импорта трека: ${e.message}", e)
            return null
        } finally {
            scratchDir.deleteRecursively()
        }
    }

    private fun downloadFile(urlStr: String, destination: File): Boolean {
        return try {
            (URL(urlStr).openConnection() as HttpURLConnection).run {
                connectTimeout = 15_000
                readTimeout = 30_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Mozilla/5.0 (Android 14; Mobile)")
                try {
                    if (responseCode !in 200..299) return false
                    inputStream.use { input ->
                        destination.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    destination.length() > 0
                } finally {
                    disconnect()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Download failed from $urlStr: ${e.message}")
            false
        }
    }

    private fun buildProgress(items: List<HybridImportItem>): HybridImportProgress {
        val total = items.size
        val done = items.count { it.status == HybridTrackStatus.DONE }
        val needsReview = items.count { it.status == HybridTrackStatus.NEEDS_REVIEW }
        val notFound = items.count { it.status == HybridTrackStatus.NOT_FOUND || it.status == HybridTrackStatus.ERROR }
        val inProgress = items.count {
            it.status == HybridTrackStatus.SEARCHING_VK ||
            it.status == HybridTrackStatus.DOWNLOADING ||
            it.status == HybridTrackStatus.IMPORTING ||
            it.status == HybridTrackStatus.PENDING
        }
        return HybridImportProgress(
            total = total,
            done = done,
            needsReview = needsReview,
            notFound = notFound,
            inProgress = inProgress,
            items = items.toList(),
        )
    }
}
