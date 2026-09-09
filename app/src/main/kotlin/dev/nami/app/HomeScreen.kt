package dev.nami.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil3.compose.AsyncImage
import dev.nami.core.designsystem.NamiColors
import dev.nami.core.designsystem.NamiRadius
import dev.nami.core.model.AlbumId
import dev.nami.core.model.PlaylistId
import dev.nami.core.model.TrackId
import dev.nami.domain.HomeBlockConfig
import dev.nami.domain.HomeBlockType

/** П.md §14 "Главный экран - конструктор" - см. HomeViewModel doc. Каждый включённый блок -
 * заголовок + своё представление, в порядке из настроек.
 *
 * Раньше все блоки были одинаковыми вертикальными строками, и экран читался как один длинный
 * список списков, а не как дашборд. Теперь вид зависит от смысла блока: подборки треков -
 * горизонтальная лента карточек (компактнее по вертикали, блоки отличимы друг от друга и
 * влезает несколько блоков сразу), случайный альбом - одна крупная карточка, статистика дня -
 * плашка с крупными цифрами. Плейлисты-закладки остались строками: у них смысл в списке имён,
 * а не в обложках.
 *
 * Вид на блок (лента/сетка/список) настройкой, как хочет план, НЕ сделан - это ещё один слой
 * настроек поверх настроек; сначала важнее, чтобы вид по умолчанию был приличным. */
@Composable
fun HomeScreen(
    onTrackClick: (TrackId) -> Unit,
    onAlbumClick: (AlbumId) -> Unit,
    onPlaylistClick: (PlaylistId) -> Unit,
    onConstructorClick: () -> Unit,
    onOpenLocalShare: () -> Unit,
    onShuffleAllClick: (List<dev.nami.core.model.Track>) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val allTracks by viewModel.allTracks.collectAsState()
    val nearbyDevices by viewModel.nearbyDevices.collectAsState()
    val guestState by viewModel.nearbyGuestState.collectAsState()
    val nearbyBlockEnabled = state.blocks.any { it.type == HomeBlockType.NEARBY_NETWORK && it.enabled }
    // Поиск идёт только пока сам блок реально на экране (см. HomeViewModel.startNearbyDiscovery
    // doc про то, почему не в жизненном цикле ViewModel) - выключается автоматически, если блок
    // выключили в конструкторе или ушли с главного экрана.
    androidx.compose.runtime.DisposableEffect(nearbyBlockEnabled) {
        if (nearbyBlockEnabled) viewModel.startNearbyDiscovery()
        onDispose { if (nearbyBlockEnabled) viewModel.stopNearbyDiscovery() }
    }

    Column(modifier = Modifier.fillMaxSize().background(NamiColors.Ink900)) {
        dev.nami.core.designsystem.NamiScreenHeader(
            title = "Главная",
            actions = {
                IconButton(onClick = onConstructorClick) {
                    Icon(Icons.Outlined.Tune, contentDescription = "Настроить блоки", tint = NamiColors.Paper70)
                }
            },
        )
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            state.blocks.filter { it.enabled }.forEach { block ->
                when (block.type) {
                    HomeBlockType.CONTINUE_LISTENING -> state.continueListening?.let { track ->
                        item { HomeSectionHeader("Продолжить слушать") }
                        item { HomeHeroRow(track.title, track.artistName, track.albumArtworkPath, onClick = { onTrackClick(track.id) }) }
                    }
                    HomeBlockType.SHUFFLE_ALL -> if (allTracks.isNotEmpty()) {
                        item { HomeShuffleAllCard(trackCount = allTracks.size, onClick = { onShuffleAllClick(allTracks) }) }
                    }
                    HomeBlockType.RECENTLY_ADDED -> if (state.recentlyAdded.isNotEmpty()) {
                        item { HomeSectionHeader("Недавно добавленное") }
                        item {
                            HomeCardStrip(state.recentlyAdded, key = { "added-" + it.id.value }) { track ->
                                HomeTrackCard(track.title, track.artistName, track.albumArtworkPath) { onTrackClick(track.id) }
                            }
                        }
                    }
                    HomeBlockType.TOP_WEEK -> if (state.topWeek.isNotEmpty()) {
                        item { HomeSectionHeader("Топ недели") }
                        item {
                            HomeCardStrip(state.topWeek, key = { "top-" + it.trackId.value }) { stat ->
                                // Номер в топе прямо на обложке - иначе лента ничем не отличается
                                // от "недавно добавленного", а весь смысл блока в порядке.
                                HomeTrackCard(
                                    stat.title,
                                    stat.artistName,
                                    stat.albumArtworkPath,
                                    rank = state.topWeek.indexOf(stat) + 1,
                                ) { onTrackClick(stat.trackId) }
                            }
                        }
                    }
                    HomeBlockType.RANDOM_ALBUM -> state.randomAlbum?.let { album ->
                        item { HomeSectionHeader("Случайный альбом") }
                        item { HomeAlbumCard(album.title, album.artistName, album.artworkPath) { onAlbumClick(album.id) } }
                    }
                    HomeBlockType.FORGOTTEN -> if (state.forgotten.isNotEmpty()) {
                        item { HomeSectionHeader("Давно не слушал") }
                        item {
                            HomeCardStrip(state.forgotten, key = { "forgotten-" + it.id.value }) { track ->
                                HomeTrackCard(track.title, track.artistName, track.albumArtworkPath) { onTrackClick(track.id) }
                            }
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
                        item {
                            HomeCardStrip(state.tagTracks, key = { "tag-" + it.id.value }) { track ->
                                HomeTrackCard(track.title, track.artistName, track.albumArtworkPath) { onTrackClick(track.id) }
                            }
                        }
                    }
                    HomeBlockType.BOOKMARKED_PLAYLISTS -> if (state.bookmarkedPlaylists.isNotEmpty()) {
                        item { HomeSectionHeader("Плейлисты-закладки") }
                        items(state.bookmarkedPlaylists, key = { "playlist-" + it.id.value }) { playlist ->
                            HomeTrackRow(playlist.name, "${playlist.trackCount} треков", playlist.coverPath, onClick = { onPlaylistClick(playlist.id) }, isLiked = playlist.isLiked)
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
                        item { HomeStatsCard(stats.totalMinutes, stats.distinctTracks, stats.distinctArtists) }
                    }
                    // Карточка на экране всегда (старый стиль - "Пока никого не видно рядом"
                    // внутри неё же, поиск идёт фоном), а не то плоское "Ищем рядом..." без
                    // стиля - анимируется появление КАЖДОГО найденного устройства по отдельности
                    // (см. AnimatedVisibility на строке устройства внутри HomeNearbyBlock), не
                    // вся карточка целиком.
                    HomeBlockType.NEARBY_NETWORK -> {
                        item { HomeSectionHeader("Рядом") }
                        item {
                            HomeNearbyBlock(
                                devices = nearbyDevices,
                                guestState = guestState,
                                onJoinListenTogether = viewModel::joinListenTogether,
                                onPullDrop = viewModel::pullDrop,
                                onLeaveListenTogether = viewModel::leaveListenTogether,
                                onOpenLocalShare = onOpenLocalShare,
                            )
                        }
                    }
                }
            }
            if (state.loaded && state.blocks.none { it.enabled }) {
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

/** Горизонтальная лента карточек - общая обёртка для всех трековых блоков, чтобы отступы и
 * интервал были одинаковыми и задавались в одном месте. */
@Composable
private fun <T> HomeCardStrip(items: List<T>, key: (T) -> Any, card: @Composable (T) -> Unit) {
    LazyRow(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        items(items, key = key) { item -> card(item) }
    }
}

/** Мини-карточка трека под ленту - тот же визуальный язык, что у AlbumGridItem в библиотеке
 * (квадратная обложка со скруглением обложки, под ней две строки текста), только фиксированной
 * ширины, потому что в ленте нет сетки, которая задала бы ширину сама. */
@Composable
private fun HomeTrackCard(title: String, subtitle: String?, artworkPath: String?, rank: Int? = null, onClick: () -> Unit) {
    Column(modifier = Modifier.width(CARD_WIDTH).clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .size(CARD_WIDTH)
                .background(NamiColors.Ink700, RoundedCornerShape(NamiRadius.AlbumArt)),
        ) {
            if (artworkPath != null) {
                AsyncImage(
                    model = artworkPath,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(NamiRadius.AlbumArt)),
                )
            }
            if (rank != null) {
                Text(
                    "$rank",
                    color = NamiColors.Paper100,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .background(NamiColors.Ink900.copy(alpha = 0.72f), RoundedCornerShape(NamiRadius.AlbumArt))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
        Text(
            title,
            color = NamiColors.Paper100,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        subtitle?.let {
            Text(it, color = NamiColors.Paper70, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private val CARD_WIDTH = 132.dp

/** "Продолжить слушать" - один трек, поэтому не лента, а широкая карточка во всю ширину: это
 * единственное действие блока, и оно должно быть самой заметной кнопкой на экране. */
@Composable
private fun HomeHeroRow(title: String, subtitle: String?, artworkPath: String?, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .background(NamiColors.Ink800, RoundedCornerShape(NamiRadius.Card))
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        Box(modifier = Modifier.size(56.dp).background(NamiColors.Ink700, RoundedCornerShape(NamiRadius.AlbumArt))) {
            if (artworkPath != null) {
                AsyncImage(
                    model = artworkPath,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(NamiRadius.AlbumArt)),
                )
            }
        }
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(title, color = NamiColors.Paper100, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, color = NamiColors.Paper70, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

private fun tracksWord(count: Int): String {
    val mod100 = count % 100
    val mod10 = count % 10
    return when {
        mod100 in 11..14 -> "треков"
        mod10 == 1 -> "трек"
        mod10 in 2..4 -> "трека"
        else -> "треков"
    }
}

/** Одна кнопка - запустить всю библиотеку вперемешку, без похода в Библиотеку/выбора плейлиста.
 * Без заголовка-секции (в отличие от остальных блоков) - сама карточка самодостаточна, подпись
 * "Продолжить слушать" сверху ей не нужна, это не список, а один явный призыв к действию. */
@Composable
private fun HomeShuffleAllCard(trackCount: Int, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .background(NamiColors.Ink800, RoundedCornerShape(NamiRadius.Card))
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        Box(
            modifier = Modifier.size(48.dp).background(NamiColors.Shu, RoundedCornerShape(NamiRadius.Button)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = NamiColors.Ink900)
        }
        Column(modifier = Modifier.weight(1f).padding(start = 14.dp)) {
            Text("Слушать всё вперемешку", color = NamiColors.Paper100, style = MaterialTheme.typography.bodyLarge)
            Text("$trackCount " + tracksWord(trackCount), color = NamiColors.Paper40, style = MaterialTheme.typography.bodySmall)
        }
        Icon(Icons.Outlined.Shuffle, contentDescription = null, tint = NamiColors.Paper40, modifier = Modifier.size(20.dp))
    }
}

/** Случайный альбом - крупная обложка в половину ширины экрана рядом с названием: блок про
 * "посмотри на эту обложку", маленькая картинка убивает весь смысл. */
@Composable
private fun HomeAlbumCard(title: String, subtitle: String?, artworkPath: String?, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .background(NamiColors.Ink800, RoundedCornerShape(NamiRadius.Card))
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        Box(modifier = Modifier.size(112.dp).background(NamiColors.Ink700, RoundedCornerShape(NamiRadius.AlbumArt))) {
            if (artworkPath != null) {
                AsyncImage(
                    model = artworkPath,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(NamiRadius.AlbumArt)),
                )
            }
        }
        Column(modifier = Modifier.weight(1f).padding(start = 16.dp)) {
            Text(title, color = NamiColors.Paper100, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, color = NamiColors.Paper70, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** Статистика дня - плашка с тремя крупными числами вместо одной серой строки текста: цифру
 * видно с полуметра, строку "42 мин · 13 треков · 7 исполнителей" надо читать. */
@Composable
private fun HomeStatsCard(minutes: Int, tracks: Int, artists: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .background(NamiColors.Ink800, RoundedCornerShape(NamiRadius.Card))
            .padding(vertical = 16.dp),
    ) {
        StatCell(minutes.toString(), "минут")
        StatCell(tracks.toString(), "треков")
        StatCell(artists.toString(), "исполнителей")
    }
}

/** П.md §14 доп. - "слушать вместе"/"принять трек" без захода в Настройки → Локальная сеть.
 * Живой блок (см. HomeViewModel doc): пока идёт активная гостевая сессия - карточка "слушаю с",
 * иначе - список найденных рядом устройств с теми же двумя действиями, что на полном экране
 * (DeviceRow в LocalShareScreen). Синхронизация, Wi-Fi Direct и раздача своего трека остаются
 * только на полном экране - для этого блока это редкие, не "быстрые" действия. */
@Composable
private fun HomeNearbyBlock(
    devices: List<dev.nami.domain.DiscoveredDevice>,
    guestState: dev.nami.domain.ListenTogetherGuestState?,
    onJoinListenTogether: (dev.nami.domain.DiscoveredDevice) -> Unit,
    onPullDrop: (dev.nami.domain.DiscoveredDevice) -> Unit,
    onLeaveListenTogether: () -> Unit,
    onOpenLocalShare: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .background(NamiColors.Ink800, RoundedCornerShape(NamiRadius.Card))
            .padding(16.dp),
    ) {
        if (guestState != null) {
            Text("Слушаю вместе с ${guestState.hostName}", color = NamiColors.Wakaba, style = MaterialTheme.typography.bodyMedium)
            Text(guestState.trackTitle ?: "-", color = NamiColors.Paper100, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            dev.nami.core.designsystem.NamiPill(
                text = "Выйти",
                color = NamiColors.Paper70,
                modifier = Modifier.padding(top = 10.dp),
                onClick = onLeaveListenTogether,
            )
        } else if (devices.isEmpty()) {
            Text("Пока никого не видно рядом", color = NamiColors.Paper40, style = MaterialTheme.typography.bodyMedium)
        } else {
            devices.forEachIndexed { index, device ->
                // Каждое устройство появляется само по себе, когда NSD его находит - не вся
                // карточка целиком (та уже на экране, ищет фоном) - remember(device.host) даёт
                // AnimatedVisibility сыграть enter один раз именно в момент появления ЭТОЙ строки
                // в composition, а не при каждой рекомпозиции карточки.
                key(device.host) {
                    if (index > 0) dev.nami.core.designsystem.NamiCardDivider(modifier = Modifier.padding(vertical = 10.dp))
                    androidx.compose.animation.AnimatedVisibility(
                        visible = true,
                        enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(250)) +
                            androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(250)) { it / 4 },
                    ) {
                        Column {
                            Text(device.name, color = NamiColors.Paper100, style = MaterialTheme.typography.bodyMedium)
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()),
                            ) {
                                dev.nami.core.designsystem.NamiPill(text = "Принять трек", color = NamiColors.Shu, onClick = { onPullDrop(device) })
                                dev.nami.core.designsystem.NamiPill(text = "Слушать вместе", color = NamiColors.Wakaba, onClick = { onJoinListenTogether(device) })
                            }
                        }
                    }
                }
            }
        }
        Text(
            "Ещё способы (Wi-Fi Direct, раздать свой трек) →",
            color = NamiColors.Paper40,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 14.dp).clickable(onClick = onOpenLocalShare),
        )
    }
}

@Composable
private fun RowScope.StatCell(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
        Text(value, color = NamiColors.Paper100, style = MaterialTheme.typography.titleLarge)
        Text(label, color = NamiColors.Paper40, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun HomeTrackRow(title: String, subtitle: String?, artworkPath: String?, onClick: () -> Unit, isLiked: Boolean = false) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Box(modifier = Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)).background(NamiColors.Ink700)) {
            when {
                // "Любимые треки" - генерируемая обложка (сердце на градиенте), у неё нет
                // coverPath вообще - без этой ветки строка на главном экране показывала
                // просто пустой квадрат вместо неё.
                isLiked -> dev.nami.core.designsystem.LikedPlaylistCover(modifier = Modifier.fillMaxSize())
                artworkPath != null -> AsyncImage(model = artworkPath, contentDescription = title, modifier = Modifier.fillMaxSize())
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
                            RoundedCornerShape(NamiRadius.Button),
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
    HomeBlockType.SHUFFLE_ALL -> "Слушать всё вперемешку"
    HomeBlockType.RECENTLY_ADDED -> "Недавно добавленное"
    HomeBlockType.TOP_WEEK -> "Топ недели"
    HomeBlockType.RANDOM_ALBUM -> "Случайный альбом"
    HomeBlockType.FORGOTTEN -> "Давно не слушал"
    HomeBlockType.QUICK_TAGS -> "Быстрые теги"
    HomeBlockType.BOOKMARKED_PLAYLISTS -> "Плейлисты-закладки"
    HomeBlockType.STATS_TODAY -> "Статистика дня"
    HomeBlockType.NEW_IMPORT -> "Импорт (если есть новое)"
    HomeBlockType.NEARBY_NETWORK -> "Рядом (слушать вместе, принять трек)"
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
            .background(if (selected) tagColor else tagColor.copy(alpha = 0.24f), RoundedCornerShape(NamiRadius.Card))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}
