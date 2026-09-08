package dev.nami.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * Общие детали экранов - то, что раньше по десять раз копировалось в каждый *Screen.kt своей
 * приватной копией (карточка Ink800, пилюля с рамкой, заголовок со стрелкой назад, подпись
 * секции). Один источник правды, чтобы экраны настроек, поиска, плейлистов, альбомов и артистов
 * выглядели как одно приложение, а не как пять разных.
 *
 * Стиль взят с переработанного экрана локальной сети: карточка Ink800 + NamiRadius.Card,
 * кнопки-пилюли с рамкой акцентного цвета, редкое - под сворачиваемой секцией.
 */

/** Шапка экрана: круглая кнопка назад на поверхности + крупный заголовок шкалы ScreenTitle. */
@Composable
fun NamiScreenHeader(
    title: String,
    onBack: (() -> Unit)? = null,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    actions: @Composable (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
    ) {
        if (onBack != null) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(NamiColors.Ink800)
                    .clickable(onClick = onBack),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад", tint = NamiColors.Paper100, modifier = Modifier.size(20.dp))
            }
        }
        Column(modifier = Modifier.weight(1f).padding(start = if (onBack != null) 12.dp else 8.dp)) {
            Text(title, color = NamiColors.Paper100, style = NamiType.ScreenTitle)
            if (subtitle != null) {
                Text(subtitle, color = NamiColors.Paper40, style = NamiType.Secondary, modifier = Modifier.padding(top = 2.dp))
            }
        }
        actions?.invoke()
    }
}

/** Базовая карточка: поверхность Ink800, скругление карточки, необязательная акцентная рамка для
 * того, что требует внимания прямо сейчас. */
@Composable
fun NamiCard(
    modifier: Modifier = Modifier,
    accent: Color? = null,
    padding: androidx.compose.foundation.layout.PaddingValues = androidx.compose.foundation.layout.PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(NamiColors.Ink800, RoundedCornerShape(NamiRadius.Card))
            .then(
                if (accent != null) {
                    Modifier.border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(NamiRadius.Card))
                } else {
                    Modifier
                },
            )
            .padding(padding),
        content = content,
    )
}

/** Подпись группы над карточкой. */
@Composable
fun NamiSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        color = NamiColors.Paper40,
        style = NamiType.Caption,
        modifier = modifier.padding(start = 24.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
    )
}

/** Заголовок внутри карточки: иконка акцентного цвета + название блока. */
@Composable
fun NamiBlockTitle(icon: ImageVector?, text: String, accent: Color = NamiColors.Shu, modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
        }
        Text(
            text,
            color = NamiColors.Paper100,
            style = NamiType.TrackTitle,
            modifier = Modifier.padding(start = if (icon != null) 8.dp else 0.dp),
        )
    }
}

/** Кнопка-пилюля с рамкой акцентного цвета. [selected] заливает её тем же цветом на 14% - так
 * видно выбранный вариант в ряду одинаковых пилюль (чипы фильтров, режимы). */
@Composable
fun NamiPill(
    text: String,
    color: Color = NamiColors.Shu,
    enabled: Boolean = true,
    selected: Boolean = false,
    leading: ImageVector? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val tint = if (enabled) color else NamiColors.Paper40
    val shape = RoundedCornerShape(NamiRadius.Button)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(shape)
            .then(if (selected) Modifier.background(tint.copy(alpha = 0.14f)) else Modifier)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .border(if (selected) 1.5.dp else 1.dp, tint.copy(alpha = if (selected) 0.9f else 0.45f), shape)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        if (leading != null) {
            Icon(leading, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp).padding(end = 0.dp))
        }
        Text(
            text = text,
            color = tint,
            style = NamiType.Caption,
            modifier = Modifier.padding(start = if (leading != null) 6.dp else 0.dp),
        )
    }
}

/** Разделитель строк внутри карточки. */
@Composable
fun NamiCardDivider(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth().height(1.dp).background(NamiColors.Ink600))
}

/** Заголовок сворачиваемой секции "редкое, но нужное" - тот же приём, что "Другие способы" на
 * экране локальной сети. */
@Composable
fun NamiDisclosure(text: String, expanded: Boolean, modifier: Modifier = Modifier, onToggle: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(NamiRadius.Button))
            .clickable(onClick = onToggle)
            .padding(horizontal = 8.dp, vertical = 10.dp),
    ) {
        Text(text, color = NamiColors.Paper70, style = NamiType.Caption, modifier = Modifier.weight(1f))
        Icon(
            if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            tint = NamiColors.Paper40,
        )
    }
}

/** Ряд пилюль: не сжимает текст, а уезжает горизонтально - на узких экранах длинная подпись
 * иначе переносится и кнопка распухает по высоте. */
@Composable
fun NamiPillRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.horizontalScroll(rememberScrollState()),
    ) { content() }
}
