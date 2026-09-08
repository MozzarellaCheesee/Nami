package dev.nami.app

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.Label
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.nami.app.navigation.bottomTabIcon
import dev.nami.app.navigation.bottomTabLabel
import dev.nami.core.designsystem.NamiColors
import dev.nami.core.designsystem.NamiRadius
import dev.nami.domain.BottomTabConfig
import dev.nami.domain.MAX_BOTTOM_TABS
import dev.nami.domain.MIN_BOTTOM_TABS

/** П.md §13 "Таб-бар настраиваемый" - тот же экран-конструктор, что у главной
 * (HomeConstructorScreen): переключатель включения плюс перетаскивание за ручку. Ограничение
 * 3-5 включённых держится здесь, а не в репозитории: это правило интерфейса, а хранилищу нечего
 * решать за пользователя, если список пришёл из бэкапа. */
@Composable
fun BottomTabsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val saved by viewModel.bottomTabs.collectAsState()
    val labelsHidden by viewModel.bottomTabLabelsHidden.collectAsState()
    var order by remember { mutableStateOf(saved) }
    // Не remember(saved): жест захватывает этот MutableState один раз (pointerInput(index)), см.
    // тот же комментарий в HomeConstructorScreen.
    LaunchedEffect(saved) { order = saved }
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var rowHeightPx by remember { mutableIntStateOf(0) }
    var dragAccumulator by remember { mutableFloatStateOf(0f) }
    val enabledCount = order.count { it.enabled }

    SettingsSubScreenScaffold(title = "Вкладки", onBack = onBack) {
        Text(
            "Включи от $MIN_BOTTOM_TABS до $MAX_BOTTOM_TABS вкладок, порядок меняй долгим тапом " +
                "по ручке справа. Сейчас включено: $enabledCount.",
            color = NamiColors.Paper40,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
        )
        SettingsCard(modifier = Modifier.padding(horizontal = 20.dp)) {
            SettingsRow(
                icon = Icons.Outlined.Label,
                title = "Скрыть подписи вкладок",
                trailing = { NamiSwitch(checked = labelsHidden, onCheckedChange = viewModel::setBottomTabLabelsHidden) },
                onClick = { viewModel.setBottomTabLabelsHidden(!labelsHidden) },
            )
            order.forEachIndexed { index, config ->
                Box(
                    modifier = Modifier
                        .onSizeChanged { rowHeightPx = it.height }
                        .background(
                            if (draggingIndex == index) NamiColors.Ink700 else Color.Transparent,
                            RoundedCornerShape(NamiRadius.Button),
                        ),
                ) {
                    val toggle = {
                        val wantOn = !config.enabled
                        val allowed = if (wantOn) enabledCount < MAX_BOTTOM_TABS else enabledCount > MIN_BOTTOM_TABS
                        if (allowed) {
                            viewModel.setBottomTabs(order.toMutableList().also { it[index] = it[index].copy(enabled = wantOn) })
                        }
                    }
                    SettingsRow(
                        icon = bottomTabIcon(config.tab),
                        title = bottomTabLabel(config.tab),
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                NamiSwitch(checked = config.enabled, onCheckedChange = { toggle() })
                                Icon(
                                    Icons.Outlined.DragHandle,
                                    contentDescription = "Перетащить, чтобы изменить порядок",
                                    tint = NamiColors.Paper70,
                                    modifier = Modifier
                                        .padding(start = 12.dp)
                                        .size(28.dp)
                                        .pointerInput(index) {
                                            detectDragGesturesAfterLongPress(
                                                onDragStart = {
                                                    draggingIndex = index
                                                    dragAccumulator = 0f
                                                },
                                                onDragEnd = {
                                                    draggingIndex = null
                                                    viewModel.setBottomTabs(order)
                                                },
                                                onDragCancel = { draggingIndex = null },
                                                onDrag = { change, dragAmount ->
                                                    change.consume()
                                                    val height = rowHeightPx.takeIf { it > 0 } ?: return@detectDragGesturesAfterLongPress
                                                    dragAccumulator += dragAmount.y
                                                    var from = draggingIndex ?: return@detectDragGesturesAfterLongPress
                                                    while (dragAccumulator >= height && from < order.lastIndex) {
                                                        order = moveTab(order, from, from + 1)
                                                        dragAccumulator -= height
                                                        from++
                                                    }
                                                    while (dragAccumulator <= -height && from > 0) {
                                                        order = moveTab(order, from, from - 1)
                                                        dragAccumulator += height
                                                        from--
                                                    }
                                                    draggingIndex = from
                                                },
                                            )
                                        },
                                )
                            }
                        },
                        onClick = { toggle() },
                    )
                }
            }
        }
    }
}

private fun moveTab(tabs: List<BottomTabConfig>, from: Int, to: Int): List<BottomTabConfig> {
    if (to !in tabs.indices) return tabs
    return tabs.toMutableList().apply { add(to, removeAt(from)) }
}
