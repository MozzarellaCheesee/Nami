package dev.nami.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.nami.core.designsystem.NamiCard
import dev.nami.core.designsystem.NamiColors
import dev.nami.core.designsystem.NamiRadius
import dev.nami.core.designsystem.NamiSectionLabel
import dev.nami.core.designsystem.NamiType
import dev.nami.domain.DEFAULT_NOW_PLAYING_MORE_ITEMS
import dev.nami.domain.NowPlayingMoreAccent
import dev.nami.domain.NowPlayingMoreConfig
import dev.nami.domain.NowPlayingMoreSection
import dev.nami.feature.player.nowPlayingMoreAccentColor
import dev.nami.feature.player.nowPlayingMoreIcon
import dev.nami.feature.player.nowPlayingMoreLabel

/** Редактор меню "Ещё" на Now Playing. Тот же конструктор, что у таб-бара
 * ([BottomTabsScreen]): перетаскивание за ручку плюс переключатель на строке - только вместо
 * "вкл/выкл" здесь три состояния (сетка/список/скрыт), а у пунктов сетки ещё и акцент. */
@Composable
fun NowPlayingMoreMenuScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val saved by viewModel.nowPlayingMoreItems.collectAsState()
    var order by remember { mutableStateOf(saved) }
    // Не remember(saved) - жест захватывает этот MutableState один раз, см. BottomTabsScreen.
    LaunchedEffect(saved) { order = saved }
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var rowHeightPx by remember { mutableIntStateOf(0) }
    var dragAccumulator by remember { mutableFloatStateOf(0f) }

    SettingsSubScreenScaffold(title = "Меню \"Ещё\"", onBack = onBack) {
        NamiCard(modifier = Modifier.padding(horizontal = 20.dp)) {
            Text("Меню \"Ещё\" на экране плеера", color = NamiColors.Paper100, style = NamiType.TrackTitle)
            Text(
                "Сетка - крупные плитки сверху, список - строки ниже. Порядок меняется долгим тапом " +
                    "по ручке справа. Пункты, которых сейчас нет (нет альбома у трека), в меню всё " +
                    "равно не появятся.",
                color = NamiColors.Paper40,
                style = NamiType.Secondary,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                "Вернуть как было",
                color = NamiColors.Shu,
                style = NamiType.Secondary,
                modifier = Modifier
                    .padding(top = 12.dp)
                    .clip(RoundedCornerShape(NamiRadius.Button))
                    .clickable { viewModel.setNowPlayingMoreItems(DEFAULT_NOW_PLAYING_MORE_ITEMS) }
                    .padding(vertical = 4.dp),
            )
        }

        NamiSectionLabel("Пункты и порядок")
        SettingsCard(modifier = Modifier.padding(horizontal = 20.dp)) {
            order.forEachIndexed { index, config ->
                Column(
                    modifier = Modifier
                        .onSizeChanged { rowHeightPx = it.height }
                        .background(
                            if (draggingIndex == index) NamiColors.Ink700 else Color.Transparent,
                            RoundedCornerShape(NamiRadius.Button),
                        )
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Icon(
                            nowPlayingMoreIcon(config.item),
                            contentDescription = null,
                            tint = if (config.section == NowPlayingMoreSection.GRID) {
                                nowPlayingMoreAccentColor(config.accent)
                            } else {
                                NamiColors.Paper70
                            },
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            nowPlayingMoreLabel(config.item),
                            color = NamiColors.Paper100,
                            style = NamiType.TrackTitle,
                            modifier = Modifier.padding(start = 16.dp).weight(1f),
                        )
                        Icon(
                            Icons.Outlined.DragHandle,
                            contentDescription = "Перетащить, чтобы изменить порядок",
                            tint = NamiColors.Paper70,
                            modifier = Modifier
                                .size(28.dp)
                                .pointerInput(index) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            draggingIndex = index
                                            dragAccumulator = 0f
                                        },
                                        onDragEnd = {
                                            draggingIndex = null
                                            viewModel.setNowPlayingMoreItems(order)
                                        },
                                        onDragCancel = { draggingIndex = null },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            val height = rowHeightPx.takeIf { it > 0 } ?: return@detectDragGesturesAfterLongPress
                                            dragAccumulator += dragAmount.y
                                            var from = draggingIndex ?: return@detectDragGesturesAfterLongPress
                                            while (dragAccumulator >= height && from < order.lastIndex) {
                                                order = move(order, from, from + 1)
                                                dragAccumulator -= height
                                                from++
                                            }
                                            while (dragAccumulator <= -height && from > 0) {
                                                order = move(order, from, from - 1)
                                                dragAccumulator += height
                                                from--
                                            }
                                            draggingIndex = from
                                        },
                                    )
                                },
                        )
                    }

                    val update = { changed: NowPlayingMoreConfig ->
                        val next = order.toMutableList().also { it[index] = changed }
                        order = next
                        viewModel.setNowPlayingMoreItems(next)
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 8.dp, start = 36.dp),
                    ) {
                        NowPlayingMoreSection.entries.forEach { section ->
                            SectionPill(
                                text = when (section) {
                                    NowPlayingMoreSection.GRID -> "Сетка"
                                    NowPlayingMoreSection.LIST -> "Список"
                                    NowPlayingMoreSection.HIDDEN -> "Скрыт"
                                },
                                selected = config.section == section,
                                onClick = { update(config.copy(section = section)) },
                            )
                        }
                    }

                    // Цвет только у сетки: в плоском списке иконки нейтральные по дизайну, и
                    // выбор там был бы настройкой без видимого эффекта.
                    if (config.section == NowPlayingMoreSection.GRID) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.padding(top = 10.dp, start = 36.dp),
                        ) {
                            NowPlayingMoreAccent.entries.forEach { accent ->
                                val color = nowPlayingMoreAccentColor(accent)
                                Box(
                                    modifier = Modifier
                                        .size(26.dp)
                                        .clip(CircleShape)
                                        .background(color.copy(alpha = 0.24f))
                                        .border(
                                            width = if (config.accent == accent) 2.dp else 1.dp,
                                            color = if (config.accent == accent) color else NamiColors.Ink600,
                                            shape = CircleShape,
                                        )
                                        .clickable { update(config.copy(accent = accent)) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(color))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionPill(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        color = if (selected) NamiColors.Ink900 else NamiColors.Paper70,
        style = NamiType.Secondary,
        modifier = Modifier
            .clip(RoundedCornerShape(NamiRadius.Button))
            .background(if (selected) NamiColors.Paper100 else NamiColors.Ink700)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

private fun move(items: List<NowPlayingMoreConfig>, from: Int, to: Int): List<NowPlayingMoreConfig> {
    if (to !in items.indices) return items
    return items.toMutableList().apply { add(to, removeAt(from)) }
}
