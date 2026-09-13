package dev.nami.data

/** Форма волны в базе - строка из значений через запятую, а не JSON и не BLOB.
 *
 * Сто двадцать чисел 0..1 с тремя знаками после запятой занимают меньше килобайта, читаются
 * глазами при отладке базы и не тянут за собой ни сериализатор, ни конвертер Room. JSON на этих
 * данных не даёт ничего, кроме лишних кавычек и скобок.
 *
 * Пустая строка и null - разные вещи: null означает "ещё не считали", пустая строка -
 * "считали, не получилось", и второй раз декодировать файл ради того же результата незачем. */
internal fun encodeWaveform(bars: List<Float>): String =
    // Locale.ROOT обязателен: на русской локали "%.3f" пишет запятую как десятичный
    // разделитель, и разделитель значений становится неотличим от дробной части.
    bars.joinToString(",") { String.format(java.util.Locale.ROOT, "%.3f", it) }

internal fun decodeWaveform(raw: String?): List<Float>? {
    if (raw == null) return null
    if (raw.isBlank()) return emptyList()
    return raw.split(',').mapNotNull { it.trim().toFloatOrNull() }
}
