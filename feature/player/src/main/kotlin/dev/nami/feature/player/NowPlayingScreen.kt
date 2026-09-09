package dev.nami.feature.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.QueueMusic
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.Subject
import androidx.compose.material.icons.outlined.VolumeDown
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil3.compose.AsyncImage
import coil3.request.crossfade
import coil3.compose.LocalPlatformContext
import coil3.imageLoader
import coil3.request.ImageRequest
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.toArgb
import dev.nami.core.designsystem.ContextAction
import dev.nami.core.designsystem.ContextActionSheet
import dev.nami.core.designsystem.NamiAlertDialog
import dev.nami.core.designsystem.NamiColors
import dev.nami.core.designsystem.namiBlur
import dev.nami.core.designsystem.fullBlockClickable
import dev.nami.domain.PlaybackState
import kotlin.math.roundToInt

private const val DISMISS_THRESHOLD_DP = 120

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    onCollapse: () -> Unit,
    onQueueClick: () -> Unit,
    onLyricsClick: () -> Unit,
    onOpenAlbum: (dev.nami.core.model.AlbumId) -> Unit,
    onOpenArtist: (dev.nami.core.model.ArtistId) -> Unit,
    onShowTrackInfo: (dev.nami.core.model.TrackId) -> Unit,
    onShareCard: (dev.nami.core.model.Track) -> Unit,
    // Навигационные переходы из меню "Ещё". С дефолтами - чтобы не ломать превью и любые другие
    // места вызова, которые про эти пункты не знают.
    onOpenDriveMode: () -> Unit = {},
    onOpenPlayerSettings: () -> Unit = {},
    onOpenThemeEditor: () -> Unit = {},
    onOpenAllSettings: () -> Unit = {},
    // "Поделиться треком" и "Слушать со мной" - оба ведут на группу G "сеть" (Wi-Fi Drop/
    // Wi-Fi Direct/слушать вместе), только с разным заранее включённым режимом - см. вызывающую
    // сторону (NamiNavHost), которая заводит раздачу/хост-режим сама на входе в экран.
    onShareTrackOverNetwork: () -> Unit = {},
    onStartListenTogether: () -> Unit = {},
    viewModel: NowPlayingViewModel = hiltViewModel(),
) {
    val state by viewModel.playbackState.collectAsState()
    val queue by viewModel.queue.collectAsState()
    val trackDetails by viewModel.currentTrackDetails.collectAsState()
    val playing = state as? PlaybackState.Playing
    val density = LocalDensity.current
    val dismissThresholdPx = with(density) { DISMISS_THRESHOLD_DP.dp.toPx() }
    val screenHeightPx = with(density) { LocalConfiguration.current.screenHeightDp.dp.toPx() }

    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showAudioTractSheet by remember { mutableStateOf(false) }
    var showCastPicker by remember { mutableStateOf(false) }
    var showEqualizerInSheet by remember { mutableStateOf(false) }
    var showSleepTimerSheet by remember { mutableStateOf(false) }
    var showLoopSheet by remember { mutableStateOf(false) }
    var showAddToPlaylist by remember { mutableStateOf(false) }
    var pendingLoopStartMs by remember { mutableStateOf<Long?>(null) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.requestShowLyrics.collect { onLyricsClick() }
    }
    val clipExportUri by viewModel.clipExportUri.collectAsState()
    val clipShareContext = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(clipExportUri) {
        val uri = clipExportUri ?: return@LaunchedEffect
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "audio/wav"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        clipShareContext.startActivity(android.content.Intent.createChooser(intent, "Поделиться клипом"))
        viewModel.clipExportUriShown()
    }
    // Local-only stub - no "favorites" concept exists in the domain layer yet, so this doesn't
    // persist across tracks/sessions. Resets whenever the playing track changes.
    // Real, not a stub: backed by the Любимые треки system playlist (PlaylistRepository.
    // isTrackLiked/toggleLike) - see LikedPlaylistCover for the playlist's own heart cover.
    val isFavorite by viewModel.isCurrentTrackLiked.collectAsState()
    // Real, not a stub: viewModel.shuffleEnabled reflects the live queue's actual order (see
    // PlayerRepository.setShuffleEnabled) - toggling this really reorders/restores the queue.
    val shuffleEnabled by viewModel.shuffleEnabled.collectAsState()
    // Real ExoPlayer repeat mode - OFF/ALL/ONE, cycled by cycleRepeatMode().
    val repeatMode by viewModel.repeatMode.collectAsState()
    // Real, persisted - see SettingsRepository.nightModeEnabled. Read up here (not just at the
    // pill row further down) so the ambient backdrop below can react to it too.
    val nightModeEnabled by viewModel.nightModeEnabled.collectAsState()
    val showTechInfo by viewModel.nowPlayingShowTechInfo.collectAsState()
    val showShuffle by viewModel.nowPlayingShowShuffle.collectAsState()
    val showRepeat by viewModel.nowPlayingShowRepeat.collectAsState()
    val blockOrder by viewModel.nowPlayingBlockOrder.collectAsState()
    val compactCover by viewModel.nowPlayingCompactCover.collectAsState()
    val lineProgress by viewModel.nowPlayingLineProgress.collectAsState()

    // Shared by the swipe gesture and the chevron button so both dismiss paths always finish
    // the slide-down themselves before popping - see the comment on the swipe branch below.
    fun collapseAnimated() {
        scope.launch {
            animate(dragOffsetY, screenHeightPx) { value, _ -> dragOffsetY = value }
            onCollapse()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .offset { IntOffset(0, dragOffsetY.roundToInt()) },
    ) {
        // Ambient background: the current track's own artwork, heavily blurred, dimmed under a
        // dark scrim for text legibility - for atmosphere, per Дизайн.md's "тихое" restraint
        // this stays a backdrop, never competing with the actual artwork/controls on top of it.
        val backgroundArtworkPath = queue.nowPlaying?.artworkPath
        // Sticks to the last real artwork through a momentary null (artworkPath briefly unset
        // between tracks while the new one resolves) instead of unmounting AsyncImage - an
        // unmount/remount is an instant cut with no crossfade at all, since Coil has nothing to
        // fade FROM once the composable is gone. Keeping it mounted continuously is what lets
        // Coil's own crossfade actually run when the real new artwork shows up.
        var lastArtworkPath by remember { mutableStateOf(backgroundArtworkPath) }
        androidx.compose.runtime.LaunchedEffect(backgroundArtworkPath) {
            if (backgroundArtworkPath != null) lastArtworkPath = backgroundArtworkPath
        }
        val displayArtworkPath = backgroundArtworkPath ?: lastArtworkPath
        // Solid backing UNDER the crossfading art - Crossfade fades the old layer's alpha down
        // while fading the new one up, so mid-transition both are partially transparent at once;
        // without an opaque backer behind them, whatever's actually behind this screen (Library,
        // MiniPlayer) briefly showed through the gap.
        Box(modifier = Modifier.fillMaxSize().background(NamiColors.Ink900))
        // Explicit Compose Crossfade, not Coil's own ImageRequest.crossfade() - that one relies
        // on Coil recognizing successive loads on the same AsyncImage as a transition, which in
        // practice here (through Coil3's compose integration) never visibly cross-dissolved,
        // always reading as an instant cut. A real two-layer alpha fade at the Compose level
        // can't fail to animate regardless of what Coil's internals decide to do.
        androidx.compose.animation.Crossfade(
            targetState = displayArtworkPath,
            animationSpec = tween(400),
            label = "now-playing-background",
        ) { path ->
            if (path != null) {
                AsyncImage(
                    model = path,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().namiBlur(64.dp),
                )
            }
        }
        Box(modifier = Modifier.fillMaxSize().background(NamiColors.Ink900.copy(alpha = if (displayArtworkPath != null) 0.72f else 1f)))

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding().displayCutoutPadding()
            .navigationBarsPadding()
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { delta ->
                    dragOffsetY = (dragOffsetY + delta).coerceAtLeast(0f)
                },
                onDragStopped = { velocity ->
                    if (dragOffsetY > dismissThresholdPx || velocity > 2000f) {
                        // Finish sliding fully off-screen ourselves, THEN pop - popping first
                        // (tried before) let the Library screen and MiniPlayer underneath
                        // become visible/interactive while this screen was still mid-slide on
                        // top of them, and stacked AnimatedVisibility's own exit slide on top of
                        // this one's offset, compounding into a visible gap/glitch. Popping only
                        // once this is already fully off-screen makes AnimatedVisibility's exit
                        // (now instant, see NamiNavHost) invisible - there's nothing left to see.
                        animate(dragOffsetY, screenHeightPx) { value, _ -> dragOffsetY = value }
                        onCollapse()
                    } else {
                        animate(dragOffsetY, 0f) { value, _ -> dragOffsetY = value }
                    }
                },
            )
            .padding(20.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(40.dp).fullBlockClickable(shape = CircleShape, onClick = ::collapseAnimated),
            ) {
                Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "Свернуть", tint = NamiColors.Paper100)
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(40.dp).fullBlockClickable(shape = CircleShape) { showOverflowMenu = true },
            ) {
                Icon(Icons.Outlined.MoreVert, contentDescription = "Ещё", tint = NamiColors.Paper100)
            }
        }
        // Своя вёрстка (NowPlayingMoreSheet), не общий ContextActionSheet: шапка с треком и
        // громкостью, сетка быстрых действий 3-в-ряд, ниже плоский список переходов.
        if (showOverflowMenu) {
            val track = trackDetails
            val sheetContext = androidx.compose.ui.platform.LocalContext.current
            NowPlayingMoreSheet(
                onDismiss = { showOverflowMenu = false },
                header = {
                    if (track != null) {
                        NowPlayingOverflowHeader(track = track)
                        VolumeSlider(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
                    }
                },
                grid = listOfNotNull(
                    // keepParentOpen: лист выбора устройства открывается ПОВЕРХ "Ещё" - см. ниже.
                    ContextAction("Трансляция", Icons.Outlined.Cast, keepParentOpen = true) { showCastPicker = true },
                    track?.let { ContextAction("Поделиться карточкой", Icons.Outlined.Share) { onShareCard(it) } },
                    track?.let { ContextAction("Радио", Icons.Outlined.PlayCircleOutline) { viewModel.startRadio(it.id) } },
                    // keepParentOpen: эти действия открывают своё окно ПОВЕРХ Now Playing (диалог/
                    // лист/отдельный экран), а не заменяют его - лист "Ещё" остаётся под ними и
                    // сам всплывает обратно, когда их закрывают/уходят назад, вместо того чтобы
                    // пользователь оказывался на голом Now Playing и открывал "Ещё" заново.
                    ContextAction("В плейлист", Icons.Outlined.PlaylistAdd, keepParentOpen = true) { showAddToPlaylist = true },
                    ContextAction("Дорожный режим", Icons.Outlined.DirectionsCar, keepParentOpen = true, onClick = onOpenDriveMode),
                    ContextAction("Аудиотракт", Icons.Outlined.QueueMusic, keepParentOpen = true) { showAudioTractSheet = true },
                    ContextAction("Таймер сна", Icons.Outlined.DarkMode, keepParentOpen = true) { showSleepTimerSheet = true },
                    // По сети (Wi-Fi Drop/Wi-Fi Direct - что сейчас доступнее, решает сам экран) -
                    // не Google Cast (та же "Трансляция" выше) и не текстовая ссылка (та же
                    // "Поделиться" в плоском списке ниже), а реальная передача файла трека
                    // другому телефону - такое же частое быстрое действие, как остальные в сетке.
                    ContextAction("Поделиться треком по сети", Icons.Outlined.Send, keepParentOpen = true, onClick = onShareTrackOverNetwork),
                    // Названо не "Джем" - это не серверная синхронизация как у Spotify (каждый
                    // качает свой же трек из общего облака, сервер только дирижирует таймингом),
                    // а P2P-раздача байтов трека по LAN/Wi-Fi Direct. Настоящий Jam-аналог -
                    // отдельная задача поверх self-host сервера пользователя (см. заметки по
                    // серверу), не то, что реализовано здесь сейчас.
                    ContextAction("Слушать со мной", Icons.Outlined.Groups, keepParentOpen = true, onClick = onStartListenTogether),
                ),
                list = listOfNotNull(
                    track?.let { ContextAction("Поделиться", Icons.Outlined.Share) { shareTrackText(sheetContext, it) } },
                    ContextAction("Моменты и петли", Icons.Outlined.Repeat, keepParentOpen = true) { showLoopSheet = true },
                    track?.let { ContextAction("Информация о треке", Icons.Outlined.Info, keepParentOpen = true) { onShowTrackInfo(it.id) } },
                    track?.artistId?.let { artistId -> ContextAction("Открыть исполнителя", Icons.Outlined.Person, keepParentOpen = true) { onOpenArtist(artistId) } },
                    track?.albumId?.let { albumId -> ContextAction("Открыть альбом", Icons.Outlined.Album, keepParentOpen = true) { onOpenAlbum(albumId) } },
                    ContextAction("Настройки плеера", Icons.Outlined.Tune, keepParentOpen = true, onClick = onOpenPlayerSettings),
                    ContextAction("Редактор темы", Icons.Outlined.Palette, keepParentOpen = true, onClick = onOpenThemeEditor),
                    ContextAction("Все настройки", Icons.Outlined.Settings, keepParentOpen = true, onClick = onOpenAllSettings),
                ),
            )
        }
        if (showAddToPlaylist) {
            trackDetails?.let { track ->
                dev.nami.feature.playlists.AddToPlaylistDialog(
                    trackIds = setOf(track.id),
                    onDismiss = { showAddToPlaylist = false },
                )
            }
        }
        // Аудиотракт (and, from inside it, Эквалайзер) appear right here as a sliding-up sheet
        // instead of navigating to a separate screen - same content (AudioTractBody/
        // EqualizerBody) the standalone routes use, just embedded. showEqualizerInSheet swaps
        // which body the ONE sheet shows instead of stacking a second ModalBottomSheet on top.
        if (showCastPicker) {
            CastPickerSheet(onDismiss = { showCastPicker = false })
        }
        if (showAudioTractSheet) {
            ModalBottomSheet(onDismissRequest = { showAudioTractSheet = false; showEqualizerInSheet = false }) {
                dev.nami.core.designsystem.ImmersiveSheetEffect()
                Column(modifier = Modifier.padding(horizontal = 4.dp)) {
                    if (showEqualizerInSheet) {
                        EqualizerBody(onBack = { showEqualizerInSheet = false })
                    } else {
                        AudioTractBody(onOpenEqualizer = { showEqualizerInSheet = true })
                    }
                }
            }
        }
        if (showSleepTimerSheet) {
            val sleepTimerRemainingMs by viewModel.sleepTimerRemainingMs.collectAsState()
            SleepTimerSheet(
                remainingMs = sleepTimerRemainingMs,
                onDismiss = { showSleepTimerSheet = false },
                onStart = { minutes -> viewModel.startSleepTimer(minutes * 60_000L) },
                onCancel = { viewModel.cancelSleepTimer() },
            )
        }
        if (showLoopSheet) {
            val sheetMoments by viewModel.currentTrackMoments.collectAsState()
            val sheetDurationMs = playing?.durationMs ?: 0L
            val sheetPositionMs = playing?.positionMs ?: 0L
            val sheetProgress = if (sheetDurationMs > 0) (sheetPositionMs.toFloat() / sheetDurationMs).coerceIn(0f, 1f) else 0f
            var sheetPendingMomentPosition by remember { mutableStateOf<Long?>(null) }
            var sheetSelectedMoment by remember { mutableStateOf<dev.nami.domain.Moment?>(null) }
            MomentsAndLoopsSheet(
                trackTitle = trackDetails?.title ?: queue.nowPlaying?.title.orEmpty(),
                artistName = trackDetails?.artistName ?: queue.nowPlaying?.artistName,
                waveformHeights = viewModel.waveform.collectAsState().value,
                progress = sheetProgress,
                positionMs = sheetPositionMs,
                durationMs = sheetDurationMs,
                moments = sheetMoments,
                activeLoop = viewModel.activeLoop.collectAsState().value,
                pendingLoopStartMs = pendingLoopStartMs,
                onSeek = { fraction -> viewModel.seek((fraction * sheetDurationMs).toLong()) },
                onAddMomentHere = { sheetPendingMomentPosition = sheetPositionMs },
                onMomentClick = { moment -> sheetSelectedMoment = moment },
                onMarkLoopStart = { pendingLoopStartMs = sheetPositionMs },
                onMarkLoopEnd = { start ->
                    viewModel.setLoopRange(start, sheetPositionMs)
                    pendingLoopStartMs = null
                },
                onClearLoop = { viewModel.clearLoop() },
                onExportClip = { start, end -> viewModel.exportClip(start, end) },
                onDismiss = { showLoopSheet = false },
            )
            sheetPendingMomentPosition?.let { positionMsAt ->
                AddMomentDialog(
                    onSave = { label, colorArgb, isChapter ->
                        viewModel.addMoment(positionMsAt, label, colorArgb, isChapter)
                        sheetPendingMomentPosition = null
                    },
                    onDismiss = { sheetPendingMomentPosition = null },
                )
            }
            sheetSelectedMoment?.let { moment ->
                ContextActionSheet(
                    onDismiss = { sheetSelectedMoment = null },
                    actions = listOf(
                        ContextAction("Перейти: ${moment.label}", Icons.Rounded.PlayArrow) {
                            viewModel.seek(moment.positionMs)
                            sheetSelectedMoment = null
                        },
                        ContextAction("Удалить метку", Icons.Outlined.Delete) {
                            viewModel.removeMoment(moment.id)
                            sheetSelectedMoment = null
                        },
                    ),
                )
            }
        }
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.weight(1f))
        // No scroll: everything must fit on-screen at once. Reduced top gap before the artwork
        // frees up the vertical room this needs, instead of a scrollable body.
        // 3-page window: 0 = previous, 1 = current, 2 = next. HorizontalPager owns the drag/fling
        // math itself (a hand-rolled offset carousel here kept shipping subtle positioning bugs),
        // and keeps neighbor pages composed via beyondViewportPageCount so their artwork is
        // already loading well before a swipe reaches them.
        val pagerState = rememberPagerState(initialPage = 1) { 3 }
        val autoAdvanceSignal by viewModel.autoAdvanceSignal.collectAsState()
        val suppressSkip = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
        LaunchedEffectSettlePage(pagerState, queue.previousTrack != null, queue.upcoming.isNotEmpty(), viewModel, suppressSkip = { suppressSkip.value })
        LaunchedEffectAutoAdvance(pagerState, autoAdvanceSignal, queue.previousTrack != null, suppressSkip)

        // Peek: pages are narrower than the pager itself (contentPadding), so the previous/next
        // cover's edge shows at rest, not just once you start dragging. The pager's own height is
        // set to the resulting (smaller) page width, not the full container width, so each page
        // is still a perfect square instead of a square-container's worth of height stuffed into
        // a narrower page.
        // П.md §17 "размер обложки". Единственное, что тут трогается - величина бокового отступа:
        // ширина страницы (и высота пейджера) считаются из неё, а вся drag/fling/автопродвижение
        // логика пейджера работает в долях страницы и о константе не знает вообще. Поэтому
        // "компактно" безопасно, в отличие от любой правки самого пейджера.
        val peekDp = if (compactCover) 56.dp else 28.dp
        val hasPreviousTrack = queue.previousTrack != null
        val hasNextTrack = queue.upcoming.isNotEmpty()
        // Blocks the drag itself (not just re-snapping after) when there's nothing on that side --
        // without this, swiping revealed a blank gray square for a page that has no real track,
        // since the pager is a fixed 3-slot window even when a neighbor doesn't exist.
        val edgeGuard = remember(hasPreviousTrack, hasNextTrack) {
            object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
                override fun onPreScroll(
                    available: androidx.compose.ui.geometry.Offset,
                    source: androidx.compose.ui.input.nestedscroll.NestedScrollSource,
                ): androidx.compose.ui.geometry.Offset {
                    val revealingMissingPrevious = !hasPreviousTrack && available.x > 0f
                    val revealingMissingNext = !hasNextTrack && available.x < 0f
                    return if (revealingMissingPrevious || revealingMissingNext) available else androidx.compose.ui.geometry.Offset.Zero
                }
            }
        }
        androidx.compose.foundation.layout.BoxWithConstraints(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 24.dp).nestedScroll(edgeGuard),
        ) {
            val pageWidth = maxWidth - peekDp * 2
            HorizontalPager(
                state = pagerState,
                pageSpacing = 16.dp,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = peekDp),
                beyondViewportPageCount = 1,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(pageWidth)
                    .clipToBounds(),
            ) { page ->
                val track = when (page) {
                    0 -> queue.previousTrack
                    2 -> queue.upcoming.firstOrNull()?.track
                    else -> queue.nowPlaying
                }
                // No track for this slot (start/end of queue) - nothing to peek at all, not a
                // gray placeholder square (combined with the edgeGuard above, which already stops
                // the drag from ever settling here).
                if (track == null) return@HorizontalPager
                val accentColor = rememberArtworkAccentColor(track.artworkPath)
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    // Soft accent glow behind the artwork, per Дизайн.md's "мягкое свечение
                    // цветом акцента" - Compose has no CSS box-shadow, so a blurred radial
                    // gradient sitting behind the artwork approximates it (Modifier.blur needs
                    // API 31+; on older devices it degrades to an unblurred soft-edged gradient,
                    // still reading as a glow). Color is that page's own dominant/vibrant tone
                    // (via Palette), falling back to --shu while loading or if extraction fails.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(accentColor.copy(alpha = 0.55f), accentColor.copy(alpha = 0f)),
                                ),
                            )
                            .namiBlur(32.dp),
                    )
                    NowPlayingArtwork(
                        artworkPath = track.artworkPath,
                        contentDescription = track.title,
                        modifier = Modifier
                            .fillMaxSize()
                            .background(NamiColors.Ink700, RoundedCornerShape(4.dp))
                            .pointerInput(Unit) {
                                detectTapGestures(onDoubleTap = { viewModel.performDoubleTapAction() })
                            },
                    )
                }
            }
        }
        // Всё, что нужно сразу нескольким секциям, считается ДО перебора порядка - иначе
        // прогресс-бар и время под ним зависели бы от того, куда пользователь переставил блок.
        val durationMs = playing?.durationMs ?: 0L
        val actualPositionMs = playing?.positionMs ?: 0L
        val actualProgress = if (durationMs > 0) (actualPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
        // While dragging, the time label below should track the finger, not real playback
        // position (which only jumps once the drag ends and seek() actually fires).
        var previewProgress by remember { mutableStateOf<Float?>(null) }
        val progress = previewProgress ?: actualProgress
        val positionMs = (progress * durationMs).toLong()
        val waveform by viewModel.waveform.collectAsState()
        val moments by viewModel.currentTrackMoments.collectAsState()
        var pendingMomentFraction by remember { mutableStateOf<Float?>(null) }
        var selectedMoment by remember { mutableStateOf<dev.nami.domain.Moment?>(null) }

        // П.md §17 "порядок блоков". Секции ниже обложки идут одна за другой в обычном потоке
        // Column, не завязаны ни на пейджер, ни друг на друга по layout - поэтому порядок
        // читается из настройки, а не захардкожен здесь. Сам пейджер в списке отсутствует
        // намеренно: он всегда сверху, см. NowPlayingBlock.
        blockOrder.forEach { block ->
            when (block) {
                dev.nami.domain.NowPlayingBlock.TITLE_ARTIST -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = queue.nowPlaying?.title ?: "Ничего не играет",
                        color = NamiColors.Paper100,
                        maxLines = 1,
                        modifier = Modifier.weight(1f).basicMarquee(iterations = Int.MAX_VALUE),
                    )
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(32.dp).fullBlockClickable(shape = CircleShape) { viewModel.toggleLikeCurrentTrack() },
                    ) {
                        // Лёгкий "поп" при переключении - иконка менялась мгновенно, тап частый
                        // (десятки раз в день), поэтому только едва заметный bounce, не полноценная
                        // анимация. Скачок значения (1.3 -> 1.0), не steady-state - иначе не от чего
                        // отталкиваться при каждом повторном тапе на одно и то же значение.
                        val likeScale = remember { Animatable(1f) }
                        LaunchedEffect(isFavorite) {
                            likeScale.snapTo(1.3f)
                            likeScale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
                        }
                        Icon(
                            imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                            contentDescription = if (isFavorite) "Убрать из избранного" else "В избранное",
                            tint = if (isFavorite) NamiColors.Shu else NamiColors.Paper100,
                            modifier = Modifier.scale(likeScale.value),
                        )
                    }
                }
                queue.nowPlaying?.artistName?.let { artistName ->
                    Text(text = artistName, color = NamiColors.Paper70)
                }
                }

                dev.nami.domain.NowPlayingBlock.PROGRESS -> {
                // П.md §17 "форма прогресс-бара" - волна или линия, подмена ровно на этом месте, всё
                // вокруг (время под шкалой, транспорт, диалоги меток) одинаково для обоих вариантов.
                if (lineProgress) {
                    LineScrubber(
                        progress = actualProgress,
                        onSeek = { fraction -> viewModel.seek((fraction * durationMs).toLong()) },
                        onProgressPreview = { fraction -> previewProgress = fraction },
                        onPreviewEnd = { previewProgress = null },
                        modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                    )
                } else {
                    WaveformScrubber(
                        seedKey = queue.nowPlaying?.id?.value ?: "",
                        progress = actualProgress,
                        onSeek = { fraction -> viewModel.seek((fraction * durationMs).toLong()) },
                        onProgressPreview = { fraction -> previewProgress = fraction },
                        onPreviewEnd = { previewProgress = null },
                        modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                        realHeights = waveform,
                        moments = if (durationMs > 0) {
                            moments.map { MomentMarker(it.id, it.positionMs.toFloat() / durationMs, it.colorArgb) }
                        } else {
                            emptyList()
                        },
                        onLongPress = { fraction -> pendingMomentFraction = fraction },
                        onMomentClick = { id -> selectedMoment = moments.firstOrNull { it.id == id } },
                    )
                }
                pendingMomentFraction?.let { fraction ->
                    AddMomentDialog(
                        onSave = { label, colorArgb, isChapter ->
                            viewModel.addMoment((fraction * durationMs).toLong(), label, colorArgb, isChapter)
                            pendingMomentFraction = null
                        },
                        onDismiss = { pendingMomentFraction = null },
                    )
                }
                // Tapping a marker (WaveformScrubber's own hit-test) is the only way to manage one - see
                // "как убирать метки и управлять ими": jump there, or delete it.
                selectedMoment?.let { moment ->
                    ContextActionSheet(
                        onDismiss = { selectedMoment = null },
                        actions = listOf(
                            ContextAction("Перейти: ${moment.label}", Icons.Rounded.PlayArrow) {
                                viewModel.seek(moment.positionMs)
                                selectedMoment = null
                            },
                            ContextAction("Удалить метку", Icons.Outlined.Delete) {
                                viewModel.removeMoment(moment.id)
                                selectedMoment = null
                            },
                        ),
                    )
                }
                Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        text = formatDuration(positionMs),
                        color = NamiColors.Paper70,
                        style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                    )
                    Text(
                        text = "-" + formatDuration((durationMs - positionMs).coerceAtLeast(0L)),
                        color = NamiColors.Paper70,
                        style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                    )
                }
                }

                dev.nami.domain.NowPlayingBlock.TRANSPORT -> {
                // Five rounded-square blocks, shrinking away from the center: play (72) > prev/next (56)
                // > shuffle/repeat (44). Обе крайние кнопки включаются по отдельности (П.md §17 "какие
                // кнопки в ряду управления"), центральные три - каркас ряда, их выключать нечем и незачем.
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (showShuffle) {
                        TransportBlock(
                            icon = Icons.Outlined.Shuffle,
                            size = 44.dp,
                            active = shuffleEnabled,
                            contentDescription = "Перемешать",
                            onClick = { viewModel.toggleShuffle() },
                        )
                    }
                    TransportBlock(
                        icon = Icons.Rounded.SkipPrevious,
                        size = 56.dp,
                        contentDescription = "Предыдущий",
                        onClick = {
                            // A previous track to show -> animate the pager, same as a swipe (forces the
                            // actual previous track). Nothing to show -> fall back to the button's own
                            // restart-if-elapsed semantics with no animation (nothing to slide to).
                            if (queue.previousTrack != null) {
                                scope.launch { pagerState.animateScrollToPage(0, animationSpec = TRACK_SLIDE_SPEC) }
                            } else {
                                viewModel.skipPrevious()
                            }
                        },
                    )
                    TransportBlock(
                        icon = if (playing?.isPlaying == true) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        size = 72.dp,
                        filled = true,
                        contentDescription = "Играть/пауза",
                        onClick = viewModel::toggle,
                    )
                    TransportBlock(
                        icon = Icons.Rounded.SkipNext,
                        size = 56.dp,
                        contentDescription = "Следующий",
                        onClick = {
                            if (queue.upcoming.isNotEmpty()) {
                                scope.launch { pagerState.animateScrollToPage(2, animationSpec = TRACK_SLIDE_SPEC) }
                            } else {
                                viewModel.skipNext()
                            }
                        },
                    )
                    if (showRepeat) {
                        TransportBlock(
                            icon = Icons.Outlined.Repeat,
                            size = 44.dp,
                            active = repeatMode != dev.nami.domain.RepeatMode.OFF,
                            badgeText = if (repeatMode == dev.nami.domain.RepeatMode.ONE) "1" else null,
                            contentDescription = when (repeatMode) {
                                dev.nami.domain.RepeatMode.OFF -> "Зациклить очередь"
                                dev.nami.domain.RepeatMode.ALL -> "Зациклить один трек"
                                dev.nami.domain.RepeatMode.ONE -> "Выключить цикл"
                            },
                            onClick = { viewModel.cycleRepeatMode() },
                        )
                    }
                }
                }

                dev.nami.domain.NowPlayingBlock.TECH_INFO -> {
                // Format badge sits below the transport controls per Дизайн.md §4.3 (mockup order:
                // controls, then format badge row, then the pill row) - was above the scrubber before.
                // Detail string (bitrate/sample-rate-bit-depth/size) needs the full Track (byte size,
                // duration), not just QueueTrack's format string - falls back to just the format badge
                // until currentTrackDetails' lookup resolves, and stays format-only if it never does.
                if (showTechInfo) queue.nowPlaying?.format?.let { format ->
                    // BPM/key (BpmKeyAnalyzer) land here live once a background scan finishes, sometimes
                    // well after the badge is already on screen - an instant text swap would read as the
                    // chip randomly resizing/changing under the user. animateContentSize smooths the
                    // width change, AnimatedContent crossfades the text itself instead of a hard cut.
                    Box(
                        modifier = Modifier
                            .padding(top = 40.dp)
                            .background(NamiColors.Ai.copy(alpha = 0.14f), RoundedCornerShape(4.dp))
                            .animateContentSize(animationSpec = tween(200)),
                    ) {
                        AnimatedContent(
                            targetState = formatBadgeDetail(format, trackDetails),
                            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) },
                            label = "format-badge",
                        ) { text ->
                            Text(
                                text = text,
                                color = NamiColors.Ai,
                                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                }

                dev.nami.domain.NowPlayingBlock.PILLS -> {
                // Bottom pill row per Дизайн.md §4.3: Очередь, night mode, and lyrics ("Текст") --
                // night mode is real (see NowPlayingViewModel.nightModeEnabled/toggleNightMode), not a
                // stub. Очередь/Текст are rectangular with sharp corners (r4); night mode is its own
                // small circle, set apart from the other two.
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NowPlayingPill(
                        text = "Очередь",
                        icon = Icons.Outlined.QueueMusic,
                        onClick = onQueueClick,
                        modifier = Modifier.weight(1f),
                    )
                    NowPlayingPill(
                        icon = Icons.Outlined.DarkMode,
                        onClick = { viewModel.toggleNightMode() },
                        shape = CircleShape,
                        active = nightModeEnabled,
                        modifier = Modifier.size(48.dp),
                    )
                    NowPlayingPill(text = "Текст", icon = Icons.Outlined.Subject, onClick = onLyricsClick, modifier = Modifier.weight(1f))
                }
                }
            }
        }
        // Верхний Spacer(weight(1f)) перед пейджером (см. выше) один без пары тянет весь остаток
        // высоты наверх - при уменьшенной обложке (NowPlayingLayoutPreset.compactCover) контент
        // короче, и это читалось как "слишком много воздуха над обложкой". Второй такой же
        // Spacer здесь распределяет остаток поровну сверху/снизу - блок реально по центру, а не
        // прижат к низу. Только для compactCover: в обычном пресете обложка и так занимает
        // большую часть высоты, добавлять второй Spacer там незачем.
        if (compactCover) androidx.compose.foundation.layout.Spacer(modifier = Modifier.weight(1f))
    }
    // Real night-mode effect, drawn LAST so it dims everything - cover art, transport controls,
    // text - not just the ambient backdrop peeking around the edges (that was the previous,
    // barely-visible version: a scrim placed under the foreground content only tinted what showed
    // through the gaps). No pointerInput/clickable here, so touches still pass straight through
    // to the buttons underneath.
    if (nightModeEnabled) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
        Box(modifier = Modifier.fillMaxSize().background(Color(0xFFFF8A00).copy(alpha = 0.10f)))
    }
    }
}

@Composable
private fun NowPlayingPill(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    text: String? = null,
    icon: ImageVector? = null,
    // Rectangle, but noticeably rounded (not the near-sharp r4 this started at); night mode
    // passes CircleShape to stand apart as its own small round button.
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(14.dp),
    active: Boolean = false,
) {
    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .height(48.dp)
            .background(if (active) NamiColors.Shu.copy(alpha = 0.18f) else NamiColors.Ink800, shape)
            .fullBlockClickable(shape = shape, onClick = onClick)
            .padding(horizontal = 8.dp),
    ) {
        icon?.let {
            Icon(it, contentDescription = text, tint = if (active) NamiColors.Shu else NamiColors.Paper70, modifier = Modifier.size(18.dp))
            if (text != null) androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(start = 4.dp))
        }
        text?.let { Text(text = it, color = if (active) NamiColors.Shu else NamiColors.Paper70) }
    }
}

@Composable
private fun TransportBlock(
    icon: ImageVector,
    size: androidx.compose.ui.unit.Dp,
    contentDescription: String,
    onClick: () -> Unit,
    filled: Boolean = false,
    active: Boolean = false,
    // "1" for repeat-one - sits inside the Repeat icon's own loop (dead center, same spot the
    // real RepeatOne glyph draws its digit) instead of a separate corner badge, reusing the plain
    // Repeat icon rather than pulling in material-icons-extended for a glyph nothing else needs.
    badgeText: String? = null,
) {
    val shape = RoundedCornerShape(size / 3.5f)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .background(color = if (filled) NamiColors.Paper100 else NamiColors.Ink800, shape = shape)
            .fullBlockClickable(shape = shape, onClick = onClick),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = when {
                filled -> NamiColors.Ink900
                active -> NamiColors.Shu
                else -> NamiColors.Paper100
            },
            modifier = Modifier.fillMaxSize().padding(size / 4),
        )
        if (badgeText != null) {
            androidx.compose.material3.Text(
                text = badgeText,
                color = if (filled) NamiColors.Ink900 else if (active) NamiColors.Shu else NamiColors.Paper100,
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall.copy(
                    fontSize = androidx.compose.ui.unit.TextUnit(size.value / 4.2f, androidx.compose.ui.unit.TextUnitType.Sp),
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                ),
            )
        }
    }
}

// A slower, explicitly-eased spec for every programmatic page transition (skip buttons, the
// auto-advance replay below) - the default animateScrollToPage spec reads as an abrupt snap at
// this page size, distinct from the naturally-smooth motion a real finger drag already gets from
// the pager's own fling physics.
internal val TRACK_SLIDE_SPEC = tween<Float>(durationMillis = 420, easing = androidx.compose.animation.core.FastOutSlowInEasing)

// Fires the actual track change once the pager settles on the previous/next page (0/2), then
// snaps it back to the center page (1) with no animation - page 1 now shows the NEW current
// track, so nothing visibly moves. Landing on 0/2 with nothing to show there (start/end of
// queue) just snaps back without skipping. suppressSkip is true while
// LaunchedEffectAutoAdvance below is doing its own page-0-to-1 replay for a track that ALREADY
// changed on its own - that replay's own scrollToPage(0) would otherwise be misread as a manual
// swipe-to-previous and trigger a real (wrong, double) skip.
@Composable
internal fun LaunchedEffectSettlePage(
    pagerState: PagerState,
    hasPrevious: Boolean,
    hasNext: Boolean,
    viewModel: NowPlayingViewModel,
    suppressSkip: () -> Boolean = { false },
) {
    androidx.compose.runtime.LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            when (page) {
                0 -> {
                    if (hasPrevious && !suppressSkip()) viewModel.skipToPreviousTrack()
                    pagerState.scrollToPage(1)
                }
                2 -> {
                    if (hasNext && !suppressSkip()) viewModel.skipNext()
                    pagerState.scrollToPage(1)
                }
            }
        }
    }
}

/** Replays the same slide the pager plays for a manual swipe/skip, but for a track that just
 * ended and auto-advanced on its own - otherwise the cover just silently jumps to the next
 * track with no motion at all. By the time this fires, queue.previousTrack/nowPlaying already
 * hold the right data for the "just finished" and "now playing" tracks (the transition already
 * happened for real) - page 0 already shows exactly what page 1 used to show, so jumping there
 * instantly and animating back to 1 IS the transition, no second real skip involved. */
@Composable
internal fun LaunchedEffectAutoAdvance(
    pagerState: PagerState,
    autoAdvanceSignal: Int,
    hasPrevious: Boolean,
    suppressSkip: androidx.compose.runtime.MutableState<Boolean>,
) {
    var seenInitial by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(autoAdvanceSignal) {
        if (!seenInitial) {
            // Skip the value this StateFlow starts with - only react to it actually changing.
            seenInitial = true
            return@LaunchedEffect
        }
        if (!hasPrevious) return@LaunchedEffect
        // try/finally: without it, a SECOND auto-advance signal arriving before this animation
        // finishes cancels this coroutine mid-flight (LaunchedEffect restarts on a new key) --
        // execution stops right there, skipping the reset below and leaving suppressSkip stuck
        // true FOREVER. That silently broke the real previous-track button/swipe (LaunchedEffect-
        // SettlePage's page-0 branch checks suppressSkip() too) for the rest of the session.
        suppressSkip.value = true
        try {
            pagerState.scrollToPage(0)
            pagerState.animateScrollToPage(1, animationSpec = TRACK_SLIDE_SPEC)
        } finally {
            suppressSkip.value = false
        }
    }
}

@Composable
private fun NowPlayingArtwork(artworkPath: String?, contentDescription: String?, modifier: Modifier) {
    if (artworkPath != null) {
        AsyncImage(
            model = artworkPath,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
    } else {
        Box(modifier = modifier)
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

/** "FLAC · 24 бит · 96 кГц · 1411 кбит/с · 42.3 МБ" - as much as we actually know about the
 * file, not guessed. Bitrate is average (fileSize*8/duration), same approximation любой player
 * uses for a non-VBR-analyzed file; container overhead makes it a slight overestimate, close
 * enough to be useful. */
private fun formatBadgeDetail(format: String, track: dev.nami.core.model.Track?): String {
    val parts = buildList {
        add(format.uppercase())
        track?.bitDepth?.let { add("$it бит") }
        track?.sampleRateHz?.let { add("${it / 1000} кГц") }
        if (track != null && track.durationMs > 0) {
            val kbps = (track.sizeBytes * 8) / track.durationMs
            add("$kbps кбит/с")
        }
        track?.sizeBytes?.let { bytes ->
            val mb = bytes / 1024.0 / 1024.0
            add("%.1f МБ".format(mb))
        }
        // BpmKeyAnalyzer's cached result (Этап 6, П.md §3) - null until the track has actually
        // played once and gotten scanned, same lazy-cache lifecycle as replayGainDb.
        track?.bpm?.let { add("${it.roundToInt()} BPM") }
        track?.musicalKey?.let { add(it) }
    }
    return parts.joinToString(" · ")
}

/** Long-press on the scrubber (План.md §22.1) - name it, pick a color, done. Colors are fixed
 * swatches rather than a full picker: a moment marker is a tiny dot on the waveform, a handful of
 * clearly distinct hues reads better there than any color a full picker could produce. */
@Composable
private fun AddMomentDialog(onSave: (label: String, colorArgb: Int, isChapter: Boolean) -> Unit, onDismiss: () -> Unit) {
    var label by remember { mutableStateOf("") }
    var isChapter by remember { mutableStateOf(false) }
    val swatches = listOf(
        NamiColors.Shu.toArgb(),
        NamiColors.Ai.toArgb(),
        androidx.compose.ui.graphics.Color(0xFF4CAF50).toArgb(),
        androidx.compose.ui.graphics.Color(0xFFFFC107).toArgb(),
        NamiColors.Paper100.toArgb(),
    )
    var selectedColor by remember { mutableStateOf(swatches.first()) }

    NamiAlertDialog(
        onDismissRequest = onDismiss,
        title = { androidx.compose.material3.Text("Новая метка", color = NamiColors.Paper100) },
        text = {
            Column {
                androidx.compose.material3.OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    placeholder = { androidx.compose.material3.Text("Например: лучший дроп") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(modifier = Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    swatches.forEach { colorArgb ->
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(androidx.compose.ui.graphics.Color(colorArgb), androidx.compose.foundation.shape.CircleShape)
                                .then(
                                    if (colorArgb == selectedColor) {
                                        Modifier.border(2.dp, NamiColors.Paper100, androidx.compose.foundation.shape.CircleShape)
                                    } else {
                                        Modifier
                                    },
                                )
                                .clickable { selectedColor = colorArgb },
                        )
                    }
                }
                // План.md §22.16 "Главы и закладки" - same marker, flagged as a navigation
                // point rather than a "best part" highlight (see MomentEntity.isChapter).
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 16.dp).clickable { isChapter = !isChapter },
                ) {
                    androidx.compose.material3.Checkbox(checked = isChapter, onCheckedChange = { isChapter = it })
                    androidx.compose.material3.Text("Это глава/закладка, а не момент", color = NamiColors.Paper70)
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = { onSave(label.ifBlank { "Момент" }, selectedColor, isChapter) },
            ) { androidx.compose.material3.Text("Добавить", color = NamiColors.Shu) }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { androidx.compose.material3.Text("Отмена", color = NamiColors.Paper70) }
        },
    )
}

/**
 * "Трансляция": Chromecast. Само воспроизведение живёт в CastController (:player), здесь только
 * выбор устройства.
 *
 * Показываем штатные диалоги androidx.mediarouter - те же самые, что открывает кнопка
 * MediaRouteButton/CastButtonFactory, только вызванные напрямую: сама кнопка требует, чтобы
 * активность была FragmentActivity (она ищет FragmentManager), а MainActivity у нас - голая
 * ComponentActivity, и переводить всё приложение на AppCompatActivity ради одной иконки дороже,
 * чем открыть тот же диалог руками. Своего списка устройств не рисуем.
 */
internal fun openCastPicker(context: android.content.Context) {
    // runCatching: без сервисов Google (AOSP-прошивки) или без реального Cast-совместимого
    // устройства рядом Cast SDK не инициализируется вообще - причина попадает в logcat, а не
    // теряется молча, чтобы диагностировать было можно не только по обрубленному тосту.
    val castContext = runCatching { com.google.android.gms.cast.framework.CastContext.getSharedInstance(context) }
        .onFailure { android.util.Log.e("NamiCast", "CastContext.getSharedInstance упал", it) }
        .getOrNull()
    if (castContext == null) {
        android.widget.Toast.makeText(context, "Трансляция недоступна: нет сервисов Google", android.widget.Toast.LENGTH_SHORT).show()
        return
    }
    val connected = castContext.sessionManager.currentCastSession?.isConnected == true
    val dialog = if (connected) {
        // Уже транслируем - этот диалог показывает громкость и кнопку "Отключить".
        androidx.mediarouter.app.MediaRouteControllerDialog(context)
    } else {
        androidx.mediarouter.app.MediaRouteChooserDialog(context).apply {
            routeSelector = androidx.mediarouter.media.MediaRouteSelector.Builder()
                .addControlCategory(
                    com.google.android.gms.cast.CastMediaControlIntent.categoryForCast(
                        com.google.android.gms.cast.CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID,
                    ),
                )
                .build()
        }
    }
    dialog.show()
}

/** Обычный share текстом - отдельно от "Поделиться карточкой" (та рендерит картинку,
 * TrackCardRenderer). Ссылки у трека нет: плеер локальный, делиться нечем кроме названия. */
private fun shareTrackText(context: android.content.Context, track: dev.nami.core.model.Track) {
    val text = listOfNotNull(track.title, track.artistName).joinToString(" - ")
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_TEXT, text)
    }
    context.startActivity(android.content.Intent.createChooser(intent, "Поделиться треком"))
}

/** Header for the "..." sheet's richer layout (cover + "Сейчас играет" + title/artist), same
 * idea as a platform media output sheet's now-playing summary above its own action list. */
@Composable
private fun NowPlayingOverflowHeader(track: dev.nami.core.model.Track) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (track.albumArtworkPath != null) {
            AsyncImage(
                model = track.albumArtworkPath,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(48.dp).background(NamiColors.Ink700, RoundedCornerShape(8.dp)),
            )
        } else {
            Box(modifier = Modifier.size(48.dp).background(NamiColors.Ink700, RoundedCornerShape(8.dp)))
        }
        Column(modifier = Modifier.padding(start = 14.dp)) {
            Text("Сейчас играет", color = NamiColors.Paper40, style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
            Text(track.title, color = NamiColors.Paper100, style = androidx.compose.material3.MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            track.artistName?.let { Text(it, color = NamiColors.Paper70, style = androidx.compose.material3.MaterialTheme.typography.bodySmall, maxLines = 1) }
        }
    }
}

/** Real system STREAM_MUSIC volume, same knob the hardware buttons control (VolumeController) -
 * not a fake/local-only slider. */
@Composable
private fun VolumeSlider(modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val controller = remember { VolumeController(context) }
    var value by remember { mutableFloatStateOf(controller.current.toFloat()) }
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.VolumeDown, contentDescription = null, tint = NamiColors.Paper40)
        androidx.compose.material3.Slider(
            value = value,
            onValueChange = { value = it; controller.set(it.roundToInt()) },
            valueRange = 0f..controller.max.toFloat(),
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            colors = androidx.compose.material3.SliderDefaults.colors(
                thumbColor = NamiColors.Paper100,
                activeTrackColor = NamiColors.Paper100,
                inactiveTrackColor = NamiColors.Ink700,
            ),
        )
        Icon(Icons.Outlined.VolumeUp, contentDescription = null, tint = NamiColors.Paper40)
    }
}
