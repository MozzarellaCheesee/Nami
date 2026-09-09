package dev.nami.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.nami.core.designsystem.ContextAction
import dev.nami.core.designsystem.ImmersiveSheetEffect
import dev.nami.core.designsystem.NamiColors
import dev.nami.core.designsystem.NamiRadius
import dev.nami.core.designsystem.fullBlockClickable

/**
 * Меню "Ещё" экрана Now Playing. Отдельный composable, а НЕ правка [dev.nami.core.designsystem.ContextActionSheet]:
 * тот переиспользуется десятком экранов (плейлисты/альбомы/артисты/треки), и смена его вёрстки
 * поменяла бы вид меню везде. Структура повторяет его (ModalBottomSheet + ImmersiveSheetEffect),
 * но внутри - раскладка в духе системного media-output листа: шапка с треком и громкостью,
 * сетка быстрых действий 3-в-ряд одной карточкой, ниже плоский список навигационных пунктов.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingMoreSheet(
    onDismiss: () -> Unit,
    grid: List<MoreGridItem>,
    list: List<ContextAction>,
    header: (@Composable () -> Unit)? = null,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        ImmersiveSheetEffect()

        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(bottom = 28.dp, top = 4.dp),
        ) {
            header?.invoke()

            if (grid.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .background(NamiColors.Ink800, RoundedCornerShape(NamiRadius.Card))
                        .padding(vertical = 8.dp),
                ) {
                    // Ряды по 3 вручную: LazyVerticalGrid внутри скроллящейся колонки требует
                    // фиксированной высоты, а элементов тут заведомо единицы.
                    grid.chunked(3).forEach { row ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            row.forEach { cell ->
                                GridCell(
                                    action = cell.action,
                                    accent = cell.accent,
                                    onDismiss = onDismiss,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            // Добивка пустыми ячейками, чтобы неполный ряд не растягивался.
                            repeat(3 - row.size) { Spacer(modifier = Modifier.weight(1f)) }
                        }
                    }
                }
            }

            if (list.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .background(NamiColors.Ink800, RoundedCornerShape(NamiRadius.Card)),
                ) {
                    list.forEachIndexed { index, action ->
                        if (index > 0) {
                            HorizontalDivider(color = NamiColors.Ink700, modifier = Modifier.padding(start = 52.dp))
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .fullBlockClickable { if (!action.keepParentOpen) onDismiss(); action.onClick() }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(action.icon, contentDescription = null, tint = NamiColors.Paper70, modifier = Modifier.size(20.dp))
                            Text(
                                text = action.label,
                                color = NamiColors.Paper100,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(start = 16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Ячейка сетки вместе со своим акцентом. Цвет приходит из настроек пользователя
 * (dev.nami.domain.NowPlayingMoreConfig), а не из маппинга по подписи, как было раньше: подпись
 * контекстная и переименовывается, а настройка должна это переживать. */
data class MoreGridItem(val action: ContextAction, val accent: androidx.compose.ui.graphics.Color)

@Composable
private fun GridCell(
    action: ContextAction,
    accent: androidx.compose.ui.graphics.Color,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Раньше все шесть-восемь ячеек делили один и тот же серый Ink700 - ряд читался одним
    // плоским пятном, глазу не за что зацепиться, чтобы быстро найти нужное действие. Свой
    // акцентный цвет на каждую (по смыслу иконки, не рандом) - тот же приём, что уже был у
    // "Удалить"-строк в ContextActionSheet, просто на каждую ячейку свой оттенок вместо одного
    // тревожного.
    Column(
        modifier = modifier
            .fullBlockClickable(shape = RoundedCornerShape(NamiRadius.Button)) { if (!action.keepParentOpen) onDismiss(); action.onClick() }
            .padding(vertical = 12.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier.size(44.dp).background(accent.copy(alpha = 0.16f), RoundedCornerShape(NamiRadius.Button)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(action.icon, contentDescription = null, tint = accent, modifier = Modifier.size(22.dp))
        }
        Text(
            text = action.label,
            color = NamiColors.Paper70,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
