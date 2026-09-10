package dev.nami.domain

import kotlinx.coroutines.flow.StateFlow

/**
 * Синхронизация состояния с self-hosted сервером (План-Сервер этап 6, Задача 2).
 * Last-write-wins на уровне поля: моменты, петли, play_count, last_played, плейлисты.
 * Автоматически pull+push при изменениях локально и по WebSocket от сервера.
 */
interface SyncRepository {
    /** Последний успешный pull timestamp. */
    val lastSyncTimestamp: StateFlow<Long>

    /** Запустить pull с сервера. Возвращает true при успехе. */
    suspend fun pullFromServer(): Boolean

    /** Запустить push на сервер. Возвращает true при успехе. */
    suspend fun pushToServer(): Boolean

    /** Запустить двусторонний sync (pull + push). */
    suspend fun sync(): Boolean
}
