package dev.nami.domain

import java.io.File

/** Один трек из библиотеки сервера (`GET /api/tracks`). */
data class ServerTrackMeta(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long,
    val trackNo: Int? = null,
    val year: Int? = null,
    val sizeBytes: Long = 0,
    val format: String? = null,
)

/**
 * Часть VII - обзор и офлайн-кеш серверной библиотеки. Работает только когда сервер
 * подключён (`SettingsRepository.namiServerPreferred` + токен). Все методы «best effort»:
 * null / false означает «сервера нет / недоступен».
 */
interface ServerLibraryRepository {

    fun isServerActive(): Boolean

    /** Список треков сервера, постранично. Null - сервера нет или запрос не удался. */
    suspend fun listTracks(limit: Int = 1000, offset: Int = 0): List<ServerTrackMeta>?

    /** Скачать трек сервера в офлайн-кеш. Возвращает файл или null при ошибке. */
    suspend fun downloadTrack(serverTrackId: Long): File?

    /** Уже скачанный файл трека, если он есть в офлайн-кеше. */
    fun cachedFile(serverTrackId: Long): File?

    /** Файл сохранённой обложки в офлайн-кеше, если есть. */
    fun cachedArtwork(serverTrackId: Long): File?

    /** Скачать только обложку через тот же TLS-pinning, который использует API-клиент. */
    suspend fun downloadArtwork(serverTrackId: Long): File? = null

    /** Все id треков, лежащих в офлайн-кеше. */
    fun cachedTrackIds(): Set<Long>

    /** Удалить трек и его сохранённую обложку из офлайн-кеша. */
    fun removeFromCache(serverTrackId: Long)

    /** Удалить трек с сервера. Также удаляет его из офлайн-кеша и локального зеркала. */
    suspend fun deleteTrack(serverTrackId: Long): Boolean = false

    /** Множественное удаление треков с сервера. */
    suspend fun deleteTracks(serverTrackIds: List<Long>): Boolean = false

    suspend fun updateTrack(track: ServerTrackMeta): Boolean = false

    suspend fun updateMatchingTrack(original: ServerTrackMeta, updated: ServerTrackMeta): Boolean = false

    suspend fun updateMatchingArtwork(original: ServerTrackMeta, imageUri: String): Boolean = false

    suspend fun updateAlbum(
        album: String,
        artist: String?,
        title: String? = null,
        year: Int? = null,
        updateYear: Boolean = false,
        albumArtist: String? = null,
        updateAlbumArtist: Boolean = false,
    ): Boolean = false

    suspend fun updateArtist(artist: String, name: String): Boolean = false

    fun invalidateArtwork(serverTrackId: Long) = Unit

    suspend fun updateArtwork(serverTrackId: Long, imageUri: String): Boolean = false

    /** Удалить серверные записи из общей Room-библиотеки после выхода или отзыва устройства. */
    suspend fun clearMirroredTracks() {}

    /** Залить локальный файл на сервер (`POST /api/tracks/upload`). Возвращает
     * человекочитаемый итог («Загружен» / «Уже есть на сервере» / null при ошибке). */
    suspend fun uploadLocalTrack(path: String): String?

    /** Текущий статус фоновой пакетной выгрузки треков на сервер (не сбрасывается при выходе с экрана). */
    val uploadProgress: kotlinx.coroutines.flow.StateFlow<String?>
        get() = kotlinx.coroutines.flow.MutableStateFlow(null)

    /** Запустить фоновую выгрузку треков с предварительной проверкой их наличия на сервере. */
    fun uploadTracksBackground(tracks: List<dev.nami.core.model.Track>) {}

    /** Запустить фоновую выгрузку путей к файлам в синглтоне репозитория. */
    fun uploadPathsBackground(paths: List<String>) {}

    /** Сбросить статус выгрузки. */
    fun clearUploadProgress() {}

    /**
     * Создать гостевую ссылку на набор треков. `tracks` - метаданные локальных треков
     * (`artist`, `title`, `durationMs`); те, что нашлись на сервере, попадают в ссылку.
     * Возвращает URL либо null (сервер не подключён / ни один трек не сопоставлен).
     */
    suspend fun createGuestLink(
        title: String,
        tracks: List<Triple<String?, String, Long>>,
        ttlSecs: Long? = 7 * 24 * 3600,
        maxPlays: Int? = null,
    ): String?
}
