package dev.nami.domain

/** Источники, из которых можно легально (Audius/Archive) или "на свой страх" (Piped) забрать
 * трек в библиотеку - см. План-Импорт-из-сети.md. */
enum class NetworkImportSource { AUDIUS, ARCHIVE, PIPED }

/**
 * Найденный в сети трек до скачивания. [downloadUrl] пуст только у Piped - там прямую ссылку
 * приходится добывать отдельным запросом в момент скачивания (она короткоживущая и привязана к
 * инстансу, за время листания результатов протухнет).
 */
data class NetworkTrack(
    val source: NetworkImportSource,
    /** Уникален в пределах источника: id трека Audius, "item/file.flac" у Archive, videoId у Piped. */
    val id: String,
    val title: String,
    val artistName: String?,
    val durationSec: Int?,
    val artworkUrl: String?,
    /** Правая подпись на карточке: формат/размер/битрейт - то, чем результаты реально отличаются. */
    val detail: String?,
    val downloadUrl: String?,
    /** Имя файла с расширением, под которым трек ляжет во временную папку перед импортом. */
    val fileName: String,
)

interface NetworkImportRepository {
    suspend fun search(source: NetworkImportSource, query: String): List<NetworkTrack>

    /** Качает во временный файл и прогоняет через обычный импорт библиотеки. Возвращает null при
     * успехе, иначе текст ошибки для показа пользователю. */
    suspend fun importTrack(track: NetworkTrack): String?
}
