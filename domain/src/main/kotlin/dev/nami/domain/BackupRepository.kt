package dev.nami.domain

interface BackupRepository {
    /** П.md §2 "Полный экспорт в .zip" - музыка + JSON-манифест (плейлисты, рейтинги, теги,
     * настройки). Лирика отдельно не собирается - она уже лежит sidecar-файлами рядом с треками
     * в приватном хранилище, попадает в архив вместе с музыкой. Возвращает false при ошибке
     * записи (например нет места на диске). */
    suspend fun exportLibrary(destinationUri: String): Boolean
}
