package dev.nami.domain

import java.io.File

/** Один трек из библиотеки сервера (`GET /api/tracks`). */
data class ServerTrackMeta(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long,
)

/**
 * Часть VII - обзор и офлайн-кеш серверной библиотеки. Работает только когда сервер
 * подключён (`SettingsRepository.namiServerPreferred` + токен). Все методы «best effort»:
 * null / false означает «сервера нет / недоступен».
 */
interface ServerLibraryRepository {

    fun isServerActive(): Boolean

    /** Список треков сервера, постранично. Null - сервера нет или запрос не удался. */
    suspend fun listTracks(limit: Int = 500, offset: Int = 0): List<ServerTrackMeta>?

    /** Скачать трек сервера в офлайн-кеш. Возвращает файл или null при ошибке. */
    suspend fun downloadTrack(serverTrackId: Long): File?

    /** Уже скачанный файл трека, если он есть в офлайн-кеше. */
    fun cachedFile(serverTrackId: Long): File?

    /** Все id треков, лежащих в офлайн-кеше. */
    fun cachedTrackIds(): Set<Long>

    /** Удалить трек из офлайн-кеша. */
    fun removeFromCache(serverTrackId: Long)

    /** Залить локальный файл на сервер (`POST /api/tracks/upload`). Возвращает
     * человекочитаемый итог («Загружен» / «Уже есть на сервере» / null при ошибке). */
    suspend fun uploadLocalTrack(path: String): String?

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
