package dev.nami.app

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.nami.core.designsystem.NamiCard
import dev.nami.core.designsystem.NamiColors
import dev.nami.core.designsystem.NamiRadius
import dev.nami.core.designsystem.NamiSectionLabel
import dev.nami.domain.NowPlayingBlock

/** П.md §17 "порядок блоков" - тот же жест и та же реализация, что у конструктора главного
 * экрана (HomeConstructorScreen): пять строк, все на экране сразу, поэтому хватает счёта
 * "сколько высот строки прошёл палец". Включать/выключать блоки тут нельзя - у обложки,
 * названия, прогресса и транспорта нет осмысленного "выключено", а у техинфо и пилюль
 * переключатели уже есть в Настройках -> Плеер. */
@Composable
fun NowPlayingBlocksScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val saved by viewModel.nowPlayingBlockOrder.collectAsState()
    var order by remember { mutableStateOf(saved) }
    // Не remember(saved): жест захватывает этот MutableState один раз (pointerInput(index)), см.
    // тот же комментарий в HomeConstructorScreen.
    LaunchedEffect(saved) { order = saved }
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var rowHeightPx by remember { mutableIntStateOf(0) }
    var dragAccumulator by remember { mutableFloatStateOf(0f) }

    SettingsSubScreenScaffold(title = "Порядок блоков плеера", onBack = onBack) {
        NamiCard(modifier = Modifier.padding(horizontal = 20.dp)) {
            Text(
                "Обложка всегда сверху",
                color = NamiColors.Paper100,
                style = dev.nami.core.designsystem.NamiType.TrackTitle,
            )
            Text(
                "Вокруг неё завязаны свайпы смены трека, местами её не двигаем. Ниже - порядок " +
                    "секций под обложкой, меняется долгим тапом по ручке справа.",
                color = NamiColors.Paper40,
                style = dev.nami.core.designsystem.NamiType.Secondary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        NamiSectionLabel("Под обложкой")
        SettingsCard(modifier = Modifier.padding(horizontal = 20.dp)) {
            order.forEachIndexed { index, block ->
                Box(
                    modifier = Modifier
                        .onSizeChanged { rowHeightPx = it.height }
                        .background(
                            if (draggingIndex == index) NamiColors.Ink700 else Color.Transparent,
                            RoundedCornerShape(NamiRadius.Button),
                        ),
                ) {
                    // Номер позиции вместо одинаковой иконки Tune на всех пяти строках: иконка
                    // ничего не различала, а порядок - ровно то, ради чего этот экран есть.
                    Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    ) {
                        Box(
                            contentAlignment = androidx.compose.ui.Alignment.Center,
                            modifier = Modifier
                                .size(24.dp)
                                .background(NamiColors.Ink700, androidx.compose.foundation.shape.CircleShape),
                        ) {
                            Text(
                                "${index + 1}",
                                color = NamiColors.Paper70,
                                style = dev.nami.core.designsystem.NamiType.Caption,
                            )
                        }
                        Text(
                            nowPlayingBlockLabel(block),
                            color = NamiColors.Paper100,
                            style = dev.nami.core.designsystem.NamiType.TrackTitle,
                            modifier = Modifier.weight(1f).padding(start = 16.dp),
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
                                                viewModel.setNowPlayingBlockOrder(order)
                                            },
                                            onDragCancel = { draggingIndex = null },
                                            onDrag = { change, dragAmount ->
                                                change.consume()
                                                val height = rowHeightPx.takeIf { it > 0 } ?: return@detectDragGesturesAfterLongPress
                                                dragAccumulator += dragAmount.y
                                                var from = draggingIndex ?: return@detectDragGesturesAfterLongPress
                                                while (dragAccumulator >= height && from < order.lastIndex) {
                                                    order = moveNowPlayingBlock(order, from, from + 1)
                                                    dragAccumulator -= height
                                                    from++
                                                }
                                                while (dragAccumulator <= -height && from > 0) {
                                                    order = moveNowPlayingBlock(order, from, from - 1)
                                                    dragAccumulator += height
                                                    from--
                                                }
                                                draggingIndex = from
                                            },
                                        )
                                    },
                            )
                    }
                }
            }
        }
    }
}

private fun moveNowPlayingBlock(order: List<NowPlayingBlock>, from: Int, to: Int): List<NowPlayingBlock> {
    if (to !in order.indices) return order
    return order.toMutableList().apply { add(to, removeAt(from)) }
}

private fun nowPlayingBlockLabel(block: NowPlayingBlock): String = when (block) {
    NowPlayingBlock.TITLE_ARTIST -> "Название и исполнитель"
    NowPlayingBlock.PROGRESS -> "Прогресс и время"
    NowPlayingBlock.TRANSPORT -> "Кнопки управления"
    NowPlayingBlock.TECH_INFO -> "Техинфо трека"
    NowPlayingBlock.PILLS -> "Очередь, ночной режим, текст"
}
