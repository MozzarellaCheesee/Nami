package dev.nami.core.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * Радиусы по смыслу, не один на всё (Дизайн.md, "Радиусы"): каждое значение называет тип
 * элемента, а не произвольное число. Используй именованный токен вместо магического `N.dp`
 * в новом коде -- см. таблицу в доке для контекста, откуда взято каждое значение.
 */
object NamiRadius {
    /** Обложка альбома -- конверт пластинки, не должен быть мягким. */
    val AlbumArt = 4.dp

    /** Лист, шит, диалог -- верхние углы. */
    val Sheet = 24.dp

    /** Карточка, поле. */
    val Card = 16.dp

    /** Чип. */
    val Chip = 8.dp

    /** Кнопка воспроизведения -- квадрат 64×64 со скруглением 20. */
    val PlayButton = 20.dp
}

/** Верхние углы скруглены на [NamiRadius.Sheet], нижние прямые -- шит/диалог, выезжающий снизу. */
fun namiSheetShape() = RoundedCornerShape(
    topStart = NamiRadius.Sheet,
    topEnd = NamiRadius.Sheet,
    bottomStart = 0.dp,
    bottomEnd = 0.dp,
)
