package dev.nami.core.designsystem

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/** Set by [NamiTheme] from the persisted AMOLED setting. A plain observable flag rather than a
 * CompositionLocal so the many existing call sites (`NamiColors.Ink900` used directly, not via
 * MaterialTheme.colorScheme) keep working unchanged and still recompose on toggle - this is a
 * single-activity app, one global flag is enough.
 * ponytail: global flag, switch to a CompositionLocal if this ever needs to vary per-window. */
private var amoledEnabled by mutableStateOf(false)

internal fun setAmoledColors(enabled: Boolean) {
    amoledEnabled = enabled
}

/** П.md §26 "Редактор темы" - каждый токен ниже переопределяем через [NamiColors.setOverride],
 * тем же принципом что уже был у amoledEnabled (mutableState-backed getter, все существующие
 * `NamiColors.Paper100` и т.д. по всему приложению продолжают работать без изменений и сами
 * перерисовываются при смене оверрайда). Оверрайд побеждает и обычный цвет, и AMOLED-вариант.
 * Не сделано из плана: форма/скругления, плотность, типографика, прозрачность/blur, режимы
 * акцента, автопереключение по времени/устройству/плейлисту, проверка контраста, экспорт/импорт
 * .json - каждое отдельная задача, здесь только цвет. */
object NamiColors {
    // Compose-наблюдаемая мапа - изменение любого значения пересобирает все места, что читают
    // соответствующий токен, ровно как при обычной recomposition по mutableStateOf.
    private val overrides = mutableStateMapOf<String, Color>()

    val Ink900: Color get() = overrides[TOKEN_INK900] ?: if (amoledEnabled) AmoledBackground else Color(0xFF0C0D0F)
    val Ink800: Color get() = overrides[TOKEN_INK800] ?: if (amoledEnabled) AmoledSurface else Color(0xFF131417)
    val AmoledBackground = Color(0xFF000000)
    val AmoledSurface = Color(0xFF0A0B0D)
    val Ink700: Color get() = overrides[TOKEN_INK700] ?: Color(0xFF1A1B1F)
    val Ink600: Color get() = overrides[TOKEN_INK600] ?: Color(0xFF232429)
    val Ink500: Color get() = overrides[TOKEN_INK500] ?: Color(0xFF3A3C43)
    val Paper100: Color get() = overrides[TOKEN_PAPER100] ?: Color(0xFFEDEAE4)
    val Paper70: Color get() = overrides[TOKEN_PAPER70] ?: Color(0xFF9B9A97)
    val Paper40: Color get() = overrides[TOKEN_PAPER40] ?: Color(0xFF7A7C82)
    val Shu: Color get() = overrides[TOKEN_SHU] ?: Color(0xFFC24A34)
    val Ai: Color get() = overrides[TOKEN_AI] ?: Color(0xFF6A8CC0)
    val Kin: Color get() = overrides[TOKEN_KIN] ?: Color(0xFFC9A227)
    val Wakaba: Color get() = overrides[TOKEN_WAKABA] ?: Color(0xFF5FA463)

    fun setOverride(token: String, color: Color?) {
        if (color == null) overrides.remove(token) else overrides[token] = color
    }

    fun getOverride(token: String): Color? = overrides[token]

    fun resetAll() = overrides.clear()

    /** Значение токена без учёта оверрайда - "как в базовой теме", для кнопки сброса одного
     * поля и для строки редактора, которая показывает и текущее, и базовое значение сразу. */
    fun defaultOf(token: String): Color = when (token) {
        TOKEN_INK900 -> if (amoledEnabled) AmoledBackground else Color(0xFF0C0D0F)
        TOKEN_INK800 -> if (amoledEnabled) AmoledSurface else Color(0xFF131417)
        TOKEN_INK700 -> Color(0xFF1A1B1F)
        TOKEN_INK600 -> Color(0xFF232429)
        TOKEN_INK500 -> Color(0xFF3A3C43)
        TOKEN_PAPER100 -> Color(0xFFEDEAE4)
        TOKEN_PAPER70 -> Color(0xFF9B9A97)
        TOKEN_PAPER40 -> Color(0xFF7A7C82)
        TOKEN_SHU -> Color(0xFFC24A34)
        TOKEN_AI -> Color(0xFF6A8CC0)
        TOKEN_KIN -> Color(0xFFC9A227)
        TOKEN_WAKABA -> Color(0xFF5FA463)
        else -> Color(0xFFC24A34)
    }

    const val TOKEN_INK900 = "Ink900"
    const val TOKEN_INK800 = "Ink800"
    const val TOKEN_INK700 = "Ink700"
    const val TOKEN_INK600 = "Ink600"
    const val TOKEN_INK500 = "Ink500"
    const val TOKEN_PAPER100 = "Paper100"
    const val TOKEN_PAPER70 = "Paper70"
    const val TOKEN_PAPER40 = "Paper40"
    const val TOKEN_SHU = "Shu"
    const val TOKEN_AI = "Ai"
    const val TOKEN_KIN = "Kin"
    const val TOKEN_WAKABA = "Wakaba"

    /** Порядок редактируемых токенов в UI редактора темы - фон, поверхности, текст, акцент,
     * статусные - то же деление, что в П.md §26 "Цвет". */
    val EDITABLE_TOKENS = listOf(
        TOKEN_INK900, TOKEN_INK800, TOKEN_INK700, TOKEN_INK600, TOKEN_INK500,
        TOKEN_PAPER100, TOKEN_PAPER70, TOKEN_PAPER40,
        TOKEN_SHU, TOKEN_AI, TOKEN_KIN, TOKEN_WAKABA,
    )

    fun tokenLabel(token: String): String = when (token) {
        TOKEN_INK900 -> "Фон"
        TOKEN_INK800 -> "Поверхность"
        TOKEN_INK700 -> "Поверхность 2"
        TOKEN_INK600 -> "Граница"
        TOKEN_INK500 -> "Поверхность 3"
        TOKEN_PAPER100 -> "Текст"
        TOKEN_PAPER70 -> "Текст вторичный"
        TOKEN_PAPER40 -> "Текст третичный"
        TOKEN_SHU -> "Акцент"
        TOKEN_AI -> "Информационный"
        TOKEN_KIN -> "Предупреждение"
        TOKEN_WAKABA -> "Успех"
        else -> token
    }
}
