package dev.nami.core.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Радиусы по смыслу, не один на всё (Дизайн.md, "Радиусы"): каждое значение называет тип
 * элемента, а не произвольное число. Используй именованный токен вместо магического `N.dp`
 * в новом коде - см. таблицу в доке для контекста, откуда взято каждое значение.
 *
 * П.md §26 "Форма" - Sheet/Card/Button переопределяемы из редактора темы тем же
 * mutableState-паттерном, что и цвета в [NamiColors]: геттер вместо val, все существующие
 * `NamiRadius.Card` продолжают работать и сами перерисовываются при движении ползунка.
 * AlbumArt и Chip оставлены фиксированными - обложка это конверт пластинки, её мягкость не
 * вопрос вкуса, а чип и так почти квадрат.
 */
object NamiRadius {
    private val overrides = mutableStateMapOf<String, Dp>()

    /** Обложка альбома - конверт пластинки, не должен быть мягким. */
    val AlbumArt = 4.dp

    /** Лист, шит, диалог - верхние углы. */
    val Sheet: Dp get() = overrides[TOKEN_SHEET] ?: 24.dp

    /** Карточка, поле. */
    val Card: Dp get() = overrides[TOKEN_CARD] ?: 16.dp

    /** Кнопка, пилюля, мелкая плашка. */
    val Button: Dp get() = overrides[TOKEN_BUTTON] ?: 12.dp

    /** Чип. */
    val Chip = 8.dp

    /** Кнопка воспроизведения - квадрат 64×64 со скруглением 20. */
    val PlayButton = 20.dp

    fun setOverride(token: String, value: Dp?) {
        if (value == null) overrides.remove(token) else overrides[token] = value
    }

    fun defaultOf(token: String): Dp = when (token) {
        TOKEN_SHEET -> 24.dp
        TOKEN_CARD -> 16.dp
        else -> 12.dp
    }

    const val TOKEN_SHEET = "Sheet"
    const val TOKEN_CARD = "Card"
    const val TOKEN_BUTTON = "Button"

    /** Порядок ползунков в редакторе темы. */
    val EDITABLE_TOKENS = listOf(TOKEN_CARD, TOKEN_BUTTON, TOKEN_SHEET)

    fun tokenLabel(token: String): String = when (token) {
        TOKEN_SHEET -> "Шиты и диалоги"
        TOKEN_CARD -> "Карточки"
        TOKEN_BUTTON -> "Кнопки и плашки"
        else -> token
    }

    /** Разумный предел ползунка: 0 - строгий прямоугольник, 24 - максимум, после которого
     * карточка визуально превращается в пилюлю и текст в ней начинает упираться в скругление. */
    const val MIN_DP = 0
    const val MAX_DP = 24
}

/** Верхние углы скруглены на [NamiRadius.Sheet], нижние прямые - шит/диалог, выезжающий снизу. */
fun namiSheetShape() = RoundedCornerShape(
    topStart = NamiRadius.Sheet,
    topEnd = NamiRadius.Sheet,
    bottomStart = 0.dp,
    bottomEnd = 0.dp,
)

/**
 * П.md §26 "Плотность" - один общий множитель вертикального ритма вместо отдельного токена на
 * каждый отступ: компактно 0.85 / обычно 1.0 / просторно 1.2. Тот же mutableState-паттерн, что
 * у цветов и радиусов.
 *
 * Честно: множитель применён НЕ везде, а только к высоте строки списка треков
 * ([listRowHeight], TrackListItem - самый частый переиспользуемый компонент в приложении).
 * Боковые поля и отступы всех остальных экранов остались фиксированными - там сотни
 * захардкоженных `padding(...)`, менять их вслепую пачкой рискованнее, чем польза от того,
 * что интервал станет на 4dp другим.
 */
object NamiDensity {
    private var scaleState by mutableFloatStateOf(1f)

    /** 0.85 / 1.0 / 1.2 - см. [SCALES]. */
    val scale: Float get() = scaleState

    fun setScale(value: Float) {
        scaleState = value.coerceIn(0.85f, 1.2f)
    }

    /** Высота строки списка треков. База 64dp - как было до появления плотности. */
    val listRowHeight: Dp get() = (64 * scaleState).dp

    const val COMPACT = 0.85f
    const val NORMAL = 1.0f
    const val SPACIOUS = 1.2f

    val SCALES = listOf(COMPACT to "Компактно", NORMAL to "Обычно", SPACIOUS to "Просторно")
}
