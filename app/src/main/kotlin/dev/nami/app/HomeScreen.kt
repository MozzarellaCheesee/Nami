package dev.nami.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil3.compose.AsyncImage
import dev.nami.core.designsystem.NamiColors
import dev.nami.core.model.AlbumId
import dev.nami.core.model.Track
import dev.nami.core.model.TrackId
import dev.nami.domain.HomeBlockConfig
import dev.nami.domain.HomeBlockType

/** П.md §14 "Главный экран - конструктор" - см. HomeViewModel doc. Каждый включённый блок -
 * заголовок + до 10 строк-треков (или один альбом/строка статистики), в порядке из настроек. */
@Composable
fun HomeScreen(
    onTrackClick: (TrackId) -> Unit,
    onAlbumClick: (AlbumId) -> Unit,
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

/** Реордер простыми стрелками вверх/вниз, не drag-and-drop - список из 6 статичных строк не
 * оправдывает вес собственной drag-машинерии (см. QueueScreen для сравнения объёма кода) при
 * том же результате: любой порядок, любое подмножество включено. */
@Composable
fun HomeConstructorScreen(onBack: () -> Unit, viewModel: HomeViewModel = hiltViewModel()) {
    val blocks by viewModel.blocks.collectAsState()

    SettingsSubScreenScaffold(title = "Конструктор главного экрана", onBack = onBack) {
        Text(
            "Включай, выключай и переставляй блоки стрелками.",
            color = NamiColors.Paper40,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
        )
        SettingsCard(modifier = Modifier.padding(horizontal = 20.dp)) {
            blocks.forEachIndexed { index, block ->
                SettingsRow(
                    icon = Icons.Outlined.Tune,
                    title = homeBlockLabel(block.type),
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = { viewModel.setHomeBlocks(moveBlock(blocks, index, index - 1)) },
                                enabled = index > 0,
                            ) { Icon(Icons.Outlined.ArrowUpward, contentDescription = "Выше", tint = if (index > 0) NamiColors.Paper70 else NamiColors.Ink700) }
                            IconButton(
                                onClick = { viewModel.setHomeBlocks(moveBlock(blocks, index, index + 1)) },
                                enabled = index < blocks.lastIndex,
                            ) { Icon(Icons.Outlined.ArrowDownward, contentDescription = "Ниже", tint = if (index < blocks.lastIndex) NamiColors.Paper70 else NamiColors.Ink700) }
                            NamiSwitch(
                                checked = block.enabled,
                                onCheckedChange = { checked -> viewModel.setHomeBlocks(blocks.toMutableList().also { it[index] = block.copy(enabled = checked) }) },
                            )
                        }
                    },
                    onClick = {},
                )
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
    HomeBlockType.STATS_TODAY -> "Статистика дня"
}
