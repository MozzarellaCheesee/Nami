package dev.nami.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil3.compose.AsyncImage
import dev.nami.core.designsystem.NamiColors
import dev.nami.core.model.AlbumId
import dev.nami.core.model.PlaylistId
import dev.nami.core.model.TrackId
import dev.nami.domain.HomeBlockConfig
import dev.nami.domain.HomeBlockType

/** П.md §14 "Главный экран - конструктор" - см. HomeViewModel doc. Каждый включённый блок -
 * заголовок + до 10 строк-треков (или один альбом/строка статистики), в порядке из настроек. */
@Composable
fun HomeScreen(
    onTrackClick: (TrackId) -> Unit,
    onAlbumClick: (AlbumId) -> Unit,
    onPlaylistClick: (PlaylistId) -> Unit,
    onConstructorClick: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    Column(modifier = Modifier.fillMaxSize().background(NamiColors.Ink900)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
            Text(
                "Главная",
                color = NamiColors.Paper100,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).padding(start = 16.dp),
            )
            IconButton(onClick = onConstructorClick) {
                Icon(Icons.Outlined.Tune, contentDescription = "Настроить блоки", tint = NamiColors.Paper70)
            }
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            state.blocks.filter { it.enabled }.forEach { block ->
                when (block.type) {
                    HomeBlockType.CONTINUE_LISTENING -> state.continueListening?.let { track ->
                        item { HomeSectionHeader("Продолжить слушать") }
                        item { HomeTrackRow(track.title, track.artistName, track.albumArtworkPath, onClick = { onTrackClick(track.id) }) }
                    }
                    HomeBlockType.RECENTLY_ADDED -> if (state.recentlyAdded.isNotEmpty()) {
                        item { HomeSectionHeader("Недавно добавленное") }
                        items(state.recentlyAdded, key = { "added-" + it.id.value }) { track ->
                            HomeTrackRow(track.title, track.artistName, track.albumArtworkPath, onClick = { onTrackClick(track.id) })
                        }
                    }
                    HomeBlockType.TOP_WEEK -> if (state.topWeek.isNotEmpty()) {
                        item { HomeSectionHeader("Топ недели") }
                        items(state.topWeek, key = { "top-" + it.trackId.value }) { stat ->
                            HomeTrackRow(stat.title, stat.artistName, stat.albumArtworkPath, onClick = { onTrackClick(stat.trackId) })
                        }
                    }
                    HomeBlockType.RANDOM_ALBUM -> state.randomAlbum?.let { album ->
                        item { HomeSectionHeader("Случайный альбом") }
                        item { HomeTrackRow(album.title, album.artistName, album.artworkPath, onClick = { onAlbumClick(album.id) }) }
                    }
                    HomeBlockType.FORGOTTEN -> if (state.forgotten.isNotEmpty()) {
                        item { HomeSectionHeader("Давно не слушал") }
                        items(state.forgotten, key = { "forgotten-" + it.id.value }) { track ->
                            HomeTrackRow(track.title, track.artistName, track.albumArtworkPath, onClick = { onTrackClick(track.id) })
                        }
                    }
                    HomeBlockType.QUICK_TAGS -> if (state.quickTags.isNotEmpty()) {
                        item { HomeSectionHeader("Быстрые теги") }
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                state.quickTags.forEach { tag ->
                                    TagChip(
                                        name = tag.name,
                                        colorArgb = tag.colorArgb,
                                        selected = state.selectedTag == tag.id,
                                        onClick = { viewModel.selectTag(tag.id) },
                                    )
                                }
                            }
                        }
                        items(state.tagTracks, key = { "tag-" + it.id.value }) { track ->
                            HomeTrackRow(track.title, track.artistName, track.albumArtworkPath, onClick = { onTrackClick(track.id) })
                        }
                    }
                    HomeBlockType.BOOKMARKED_PLAYLISTS -> if (state.bookmarkedPlaylists.isNotEmpty()) {
                        item { HomeSectionHeader("Плейлисты-закладки") }
                        items(state.bookmarkedPlaylists, key = { "playlist-" + it.id.value }) { playlist ->
                            HomeTrackRow(playlist.name, "${playlist.trackCount} треков", playlist.coverPath, onClick = { onPlaylistClick(playlist.id) })
                        }
                    }
                    HomeBlockType.NEW_IMPORT -> if (state.newImportCount > 0) {
                        item { HomeSectionHeader("Импорт") }
                        item {
                            Text(
                                "Добавлено ${state.newImportCount} новых треков за последние сутки",
                                color = NamiColors.Paper70,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                            )
                        }
                    }
                    HomeBlockType.STATS_TODAY -> state.statsToday?.let { stats ->
                        item { HomeSectionHeader("Статистика дня") }
                        item {
                            Text(
                                "${stats.totalMinutes} мин · ${stats.distinctTracks} треков · ${stats.distinctArtists} исполнителей",
                                color = NamiColors.Paper70,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }
            if (state.blocks.none { it.enabled }) {
                item {
                    Text(
                        "Все блоки выключены - настрой в конструкторе (⚙ сверху)",
                        color = NamiColors.Paper40,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(32.dp),
                    )
                }
            }
            item { Box(modifier = Modifier.padding(bottom = 24.dp)) }
        }
    }
}

@Composable
private fun HomeSectionHeader(title: String) {
    Text(
        title,
        color = NamiColors.Paper40,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun HomeTrackRow(title: String, subtitle: String?, artworkPath: String?, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Box(modifier = Modifier.size(44.dp).background(NamiColors.Ink700, RoundedCornerShape(6.dp))) {
            if (artworkPath != null) {
                AsyncImage(model = artworkPath, contentDescription = title, modifier = Modifier.fillMaxSize())
            }
        }
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(title, color = NamiColors.Paper100, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, color = NamiColors.Paper70, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** Реордер долгим тапом по ручке справа и перетаскиванием, тот же жест, что в очереди плеера
 * (QueueScreen), только сильно короче: строк меньше десятка, все на экране сразу, поэтому не нужны
 * ни LazyColumn с layoutInfo, ни автоскролл, ни плавающая копия строки - хватает подсчёта
 * "сколько высот строки прошёл палец" и обмена соседей по ходу жеста.
 *
 * Порядок во время перетаскивания живёт в локальном [order]: писать в настройки на каждый обмен
 * значило бы дёргать перезагрузку данных главного экрана десяток раз за один жест, поэтому
 * сохранение одно - на отпускание. */
@Composable
fun HomeConstructorScreen(onBack: () -> Unit, viewModel: HomeViewModel = hiltViewModel()) {
    val blocks by viewModel.blocks.collectAsState()
    var order by remember { mutableStateOf(blocks) }
    // Не remember(blocks): жест захватывает этот MutableState один раз (pointerInput(index)) и
    // пересозданный на новом ключе объект остался бы для него навсегда устаревшим.
    LaunchedEffect(blocks) { order = blocks }
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var rowHeightPx by remember { mutableIntStateOf(0) }
    var dragAccumulator by remember { mutableFloatStateOf(0f) }

    SettingsSubScreenScaffold(title = "Конструктор главного экрана", onBack = onBack) {
        Text(
            "Включай и выключай блоки, а порядок меняй долгим тапом по ручке справа.",
            color = NamiColors.Paper40,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
        )
        SettingsCard(modifier = Modifier.padding(horizontal = 20.dp)) {
            order.forEachIndexed { index, block ->
                Box(
                    modifier = Modifier
                        .onSizeChanged { rowHeightPx = it.height }
                        .background(
                            if (draggingIndex == index) NamiColors.Ink700 else Color.Transparent,
                            RoundedCornerShape(12.dp),
                        ),
                ) {
                    SettingsRow(
                        icon = Icons.Outlined.Tune,
                        title = homeBlockLabel(block.type),
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                NamiSwitch(
                                    checked = block.enabled,
                                    onCheckedChange = { checked ->
                                        viewModel.setHomeBlocks(order.toMutableList().also { it[index] = it[index].copy(enabled = checked) })
                                    },
                                )
                                Icon(
                                    Icons.Outlined.DragHandle,
                                    contentDescription = "Перетащить, чтобы изменить порядок",
                                    tint = NamiColors.Paper70,
                                    modifier = Modifier
                                        .padding(start = 12.dp)
                                        .size(28.dp)
                                        // Ключ - только index: он у слота в Column постоянный, а
                                        // order/draggingIndex читаются через State, так что жест
                                        // не перезапускается посреди перетаскивания.
                                        .pointerInput(index) {
                                            detectDragGesturesAfterLongPress(
                                                onDragStart = {
                                                    draggingIndex = index
                                                    dragAccumulator = 0f
                                                },
                                                onDragEnd = {
                                                    draggingIndex = null
                                                    viewModel.setHomeBlocks(order)
                                                },
                                                onDragCancel = { draggingIndex = null },
                                                onDrag = { change, dragAmount ->
                                                    change.consume()
                                                    val height = rowHeightPx.takeIf { it > 0 } ?: return@detectDragGesturesAfterLongPress
                                                    dragAccumulator += dragAmount.y
                                                    var from = draggingIndex ?: return@detectDragGesturesAfterLongPress
                                                    while (dragAccumulator >= height && from < order.lastIndex) {
                                                        order = moveBlock(order, from, from + 1)
                                                        dragAccumulator -= height
                                                        from++
                                                    }
                                                    while (dragAccumulator <= -height && from > 0) {
                                                        order = moveBlock(order, from, from - 1)
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
                        onClick = {},
                    )
                }
            }
        }
    }
}

private fun moveBlock(blocks: List<HomeBlockConfig>, from: Int, to: Int): List<HomeBlockConfig> {
    if (to !in blocks.indices) return blocks
    return blocks.toMutableList().apply { add(to, removeAt(from)) }
}

private fun homeBlockLabel(type: HomeBlockType): String = when (type) {
    HomeBlockType.CONTINUE_LISTENING -> "Продолжить слушать"
    HomeBlockType.RECENTLY_ADDED -> "Недавно добавленное"
    HomeBlockType.TOP_WEEK -> "Топ недели"
    HomeBlockType.RANDOM_ALBUM -> "Случайный альбом"
    HomeBlockType.FORGOTTEN -> "Давно не слушал"
    HomeBlockType.QUICK_TAGS -> "Быстрые теги"
    HomeBlockType.BOOKMARKED_PLAYLISTS -> "Плейлисты-закладки"
    HomeBlockType.STATS_TODAY -> "Статистика дня"
    HomeBlockType.NEW_IMPORT -> "Импорт (если есть новое)"
}

@Composable
private fun TagChip(name: String, colorArgb: Int, selected: Boolean, onClick: () -> Unit) {
    val tagColor = Color(colorArgb)
    Text(
        name,
        color = if (selected) NamiColors.Ink900 else NamiColors.Paper100,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .padding(end = 8.dp)
            .background(if (selected) tagColor else tagColor.copy(alpha = 0.24f), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}
