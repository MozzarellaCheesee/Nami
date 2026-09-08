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
    // Чистый чёрный фон: на OLED выключенный пиксель не светится, поэтому Ink900 именно #000000,
    // а не "очень тёмный серый" - иначе весь смысл пресета теряется.
    ThemePreset(
        "AMOLED",
        mapOf(
            NamiColors.TOKEN_INK900 to "#000000",
            NamiColors.TOKEN_INK800 to "#0A0A0A",
            NamiColors.TOKEN_INK700 to "#141414",
            NamiColors.TOKEN_INK600 to "#1F1F1F",
            NamiColors.TOKEN_INK500 to "#2E2E2E",
            NamiColors.TOKEN_PAPER100 to "#FFFFFF",
            NamiColors.TOKEN_PAPER70 to "#B8B8B8",
            NamiColors.TOKEN_PAPER40 to "#8A8A8A",
            NamiColors.TOKEN_SHU to "#FF5C42",
            NamiColors.TOKEN_AI to "#6C9BE8",
            NamiColors.TOKEN_KIN to "#E8B93C",
            NamiColors.TOKEN_WAKABA to "#5FC26A",
        ),
    ),
    // Тот же тёмный силуэт, но все пары текст/фон уведены выше 7:1 (WCAG AAA): Paper40 в базовой
    // схеме - декоративный серый, здесь он тоже обязан читаться.
    ThemePreset(
        "Контраст",
        mapOf(
            NamiColors.TOKEN_INK900 to "#000000",
            NamiColors.TOKEN_INK800 to "#0D0D0D",
            NamiColors.TOKEN_INK700 to "#1A1A1A",
            NamiColors.TOKEN_INK600 to "#333333",
            NamiColors.TOKEN_INK500 to "#4D4D4D",
            NamiColors.TOKEN_PAPER100 to "#FFFFFF",
            NamiColors.TOKEN_PAPER70 to "#E6E6E6",
            NamiColors.TOKEN_PAPER40 to "#C4C4C4",
            NamiColors.TOKEN_SHU to "#FF8A73",
            NamiColors.TOKEN_AI to "#94BCFF",
            NamiColors.TOKEN_KIN to "#FFD24A",
            NamiColors.TOKEN_WAKABA to "#7FE08C",
        ),
    ),
)

private fun Int.toHexColor(): String = String.format("#%06X", 0xFFFFFF and this)

/** "Из обоев" - Material You (API 31+). Схему считает сама система из обоев пользователя, наше
 * дело - разложить её роли по 12 токенам. Ниже 31 API динамических цветов нет, возвращаем null,
 * и пресет просто не показывается: подделывать его своей палитрой - врать про источник. */
fun wallpaperPreset(context: android.content.Context): ThemePreset? {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return null
    val res = context.resources
    fun hex(id: Int) = res.getColor(id, context.theme).toHexColor()
    return ThemePreset(
        "Из обоев",
        mapOf(
            NamiColors.TOKEN_INK900 to hex(android.R.color.system_neutral1_900),
            NamiColors.TOKEN_INK800 to hex(android.R.color.system_neutral1_800),
            NamiColors.TOKEN_INK700 to hex(android.R.color.system_neutral1_700),
            NamiColors.TOKEN_INK600 to hex(android.R.color.system_neutral2_700),
            NamiColors.TOKEN_INK500 to hex(android.R.color.system_neutral2_500),
            NamiColors.TOKEN_PAPER100 to hex(android.R.color.system_neutral1_50),
            NamiColors.TOKEN_PAPER70 to hex(android.R.color.system_neutral1_200),
            NamiColors.TOKEN_PAPER40 to hex(android.R.color.system_neutral2_400),
            NamiColors.TOKEN_SHU to hex(android.R.color.system_accent1_200),
            NamiColors.TOKEN_AI to hex(android.R.color.system_accent2_200),
            NamiColors.TOKEN_KIN to hex(android.R.color.system_accent3_200),
            NamiColors.TOKEN_WAKABA to hex(android.R.color.system_accent1_300),
        ),
    )
}

/** "Из обложки" - акцент даёт уже существующий Palette-механизм (rememberArtworkAccentColor),
 * здесь только раскладка. Трогаем ТОЛЬКО четыре акцентных токена: одна обложка не содержит
 * информации о том, какими должны быть пять оттенков фона, а угаданный из неё фон - самый
 * быстрый способ получить нечитаемый экран. Соседние акценты - поворот тона, не новые цвета. */
fun artworkPreset(accentArgb: Int): ThemePreset {
    val hsl = FloatArray(3)
    androidx.core.graphics.ColorUtils.colorToHSL(accentArgb, hsl)
    fun rotated(degrees: Float): String {
        val shifted = floatArrayOf((hsl[0] + degrees).mod(360f), hsl[1], hsl[2])
        return androidx.core.graphics.ColorUtils.HSLToColor(shifted).toHexColor()
    }
    return ThemePreset(
        "Из обложки",
        mapOf(
            NamiColors.TOKEN_SHU to accentArgb.toHexColor(),
            NamiColors.TOKEN_AI to rotated(180f),
            NamiColors.TOKEN_KIN to rotated(40f),
            NamiColors.TOKEN_WAKABA to rotated(-100f),
        ),
    )
}

/** Коэффициент контраста по WCAG 2.1: (L1 + 0.05) / (L2 + 0.05), где L - относительная яркость
 * sRGB с гамма-развёрткой каналов. Порог AA для обычного текста - 4.5:1. */
fun contrastRatio(foregroundArgb: Int, backgroundArgb: Int): Double {
    val a = relativeLuminance(foregroundArgb)
    val b = relativeLuminance(backgroundArgb)
    val lighter = maxOf(a, b)
    val darker = minOf(a, b)
    return (lighter + 0.05) / (darker + 0.05)
}

private fun relativeLuminance(argb: Int): Double {
    fun channel(shift: Int): Double {
        val srgb = ((argb shr shift) and 0xFF) / 255.0
        return if (srgb <= 0.03928) srgb / 12.92 else Math.pow((srgb + 0.055) / 1.055, 2.4)
    }
    return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
}

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
