package dev.nami.app

import dev.nami.core.designsystem.NamiColors
import dev.nami.core.designsystem.NamiRadius
import org.json.JSONObject

/** П.md §26 "Галерея тем" - предустановленные наборы оверрайдов, применяются разом одним тапом.
 * Пользовательские темы в галерею не сохраняются: это ровно то же, что экспорт в .json, второй
 * механизм для того же не нужен.
 *
 * Пресет - это НАБОР ОВЕРРАЙДОВ поверх существующей тёмной схемы, а не отдельная светлая тема
 * MaterialTheme: вся архитектура (NamiDarkScheme + 12 токенов NamiColors) и так принимает
 * произвольный цвет в каждый токен, так что "Бумага" честно работает как светлая тема без
 * перестройки системы тем. Известное следствие: места, где цвет захардкожен мимо токенов
 * (например чёрные скримы ночного режима в Now Playing), на светлом пресете остаются тёмными. */
data class ThemePreset(val name: String, val colors: Map<String, String>)

val THEME_PRESETS = listOf(
    ThemePreset("墨 Тушь", emptyMap()),
    ThemePreset(
        "Бумага",
        mapOf(
            NamiColors.TOKEN_INK900 to "#F5F1E8",
            NamiColors.TOKEN_INK800 to "#EBE6DA",
            NamiColors.TOKEN_INK700 to "#E0DACC",
            NamiColors.TOKEN_INK600 to "#CFC7B5",
            NamiColors.TOKEN_INK500 to "#B4AB98",
            NamiColors.TOKEN_PAPER100 to "#1A1A18",
            NamiColors.TOKEN_PAPER70 to "#4A4844",
            NamiColors.TOKEN_PAPER40 to "#7A756B",
            NamiColors.TOKEN_SHU to "#C24A34",
            NamiColors.TOKEN_AI to "#3E6098",
            NamiColors.TOKEN_KIN to "#8A6D12",
            NamiColors.TOKEN_WAKABA to "#3E7A44",
        ),
    ),
    ThemePreset(
        "Ночник",
        mapOf(
            NamiColors.TOKEN_INK900 to "#12100E",
            NamiColors.TOKEN_INK800 to "#1A1714",
            NamiColors.TOKEN_INK700 to "#221E1A",
            NamiColors.TOKEN_INK600 to "#2E2822",
            NamiColors.TOKEN_INK500 to "#453D34",
            NamiColors.TOKEN_PAPER100 to "#EFE4D6",
            NamiColors.TOKEN_PAPER70 to "#A2968A",
            NamiColors.TOKEN_PAPER40 to "#7E7368",
            NamiColors.TOKEN_SHU to "#C2603A",
            NamiColors.TOKEN_AI to "#7E8A9E",
            NamiColors.TOKEN_KIN to "#C9A227",
            NamiColors.TOKEN_WAKABA to "#6C9A62",
        ),
    ),
)

/** Разобранный .json темы. Каждое поле уже провалидировано: цвета - только известные токены с
 * разбираемым hex, радиусы - только известные токены в допустимом диапазоне, множители - в своих
 * границах. Всё, что не прошло, просто отсутствует. */
data class ThemeFile(
    val colors: Map<String, String>,
    val shape: Map<String, Int>,
    val densityScale: Float?,
    val fontScale: Float?,
)

private const val THEME_FILE_VERSION = 1

fun encodeThemeFile(file: ThemeFile): String = JSONObject().apply {
    put("version", THEME_FILE_VERSION)
    put("colors", JSONObject().also { obj -> file.colors.forEach { (k, v) -> obj.put(k, v) } })
    put("shape", JSONObject().also { obj -> file.shape.forEach { (k, v) -> obj.put(k, v) } })
    file.densityScale?.let { put("density", it.toDouble()) }
    file.fontScale?.let { put("fontScale", it.toDouble()) }
}.toString(2)

/**
 * П.md §26: "парсер строгий, неизвестные поля игнорируются, значения валидируются". Тема - это
 * данные, а не код, и файл приходит извне (мессенджер, чужой телефон), поэтому:
 * - неизвестное поле или неизвестный токен - молча пропускается, а не ломает импорт;
 * - неразбираемый hex/не-число - пропускается ТОЛЬКО это поле, остальные применяются;
 * - числа зажимаются в допустимый диапазон, а не принимаются как есть (радиус 9000dp);
 * - совсем не-JSON или пустой файл - null, вызывающий покажет ошибку и ничего не тронет.
 * Версия читается, но пока ни на что не влияет: формат один, и ломать его назад незачем.
 */
fun parseThemeFile(raw: String): ThemeFile? {
    val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null

    val colorsObj = root.optJSONObject("colors")
    val colors = NamiColors.EDITABLE_TOKENS.mapNotNull { token ->
        val hex = colorsObj?.optString(token)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        // Единственный источник правды о том, что такое валидный цвет - тот же парсер, что
        // применяет оверрайд в NamiTheme. Не своя регулярка, которая рано или поздно разойдётся.
        runCatching { android.graphics.Color.parseColor(hex.trim()) }.getOrNull() ?: return@mapNotNull null
        token to hex.trim()
    }.toMap()

    val shapeObj = root.optJSONObject("shape")
    val shape = NamiRadius.EDITABLE_TOKENS.mapNotNull { token ->
        if (shapeObj == null || !shapeObj.has(token)) return@mapNotNull null
        val dp = shapeObj.optInt(token, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE } ?: return@mapNotNull null
        token to dp.coerceIn(NamiRadius.MIN_DP, NamiRadius.MAX_DP)
    }.toMap()

    val density = root.optDouble("density").takeIf { !it.isNaN() }?.toFloat()?.coerceIn(0.85f, 1.2f)
    val fontScale = root.optDouble("fontScale").takeIf { !it.isNaN() }?.toFloat()?.coerceIn(0.9f, 1.2f)

    return ThemeFile(colors, shape, density, fontScale)
}
