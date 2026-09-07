package dev.nami.feature.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.hilt.navigation.compose.hiltViewModel
import dev.nami.core.model.Lyrics
import dev.nami.core.designsystem.NamiColors
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val DISMISS_THRESHOLD_DP = 120
// A gap this long between two lines is an instrumental break, not just normal breathing room
// between sung lines.
private const val GAP_MS = 5000L
// How far INTO that gap (as a fraction) before assuming the vocalist is actually done and it's
// safe to show "nothing playing" -- a fixed millisecond guess (the original approach) was wrong
// for any line whose actual sung duration didn't match the guess, cutting the highlight before
// the line was finished. Scaling with the gap's own size adapts to lines of very different length
// without needing per-line duration data.
private const val GAP_FRACTION_BEFORE_SILENT = 1.00f
// Extra grace period after a line's own last known word ends (real word timings only) before
// calling it silence -- singing that trails slightly past the last detected word shouldn't
// instantly dim the line and pop the note icon in.
private const val WORD_TIMING_GAP_GRACE_MS = 2000L

/** Three of План.md's four "18. Экран лирики" modes (furigana, romaji triplet, karaoke word
 * highlight) and its dictionary/Anki/LRCLIB pieces aren't here -- this is the load-bearing first
 * slice: parse/show/auto-scroll/tap-to-seek synced lyrics from a local .lrc, and a manual
 * tap-to-stamp editor for tracks that don't have one yet. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LyricsScreen(
    onBack: () -> Unit,
    nowPlayingViewModel: NowPlayingViewModel,
    viewModel: LyricsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val playbackState by nowPlayingViewModel.playbackState.collectAsState()
    val queue by nowPlayingViewModel.queue.collectAsState()
    val density = LocalDensity.current
    val dismissThresholdPx = with(density) { DISMISS_THRESHOLD_DP.dp.toPx() }
    val screenHeightPx = with(density) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    var dragOffsetY by remember { mutableStateOf(0f) }
    var showEditor by remember { mutableStateOf(false) }
    var showVocabulary by remember { mutableStateOf(false) }
    val lyricsFileImportFailed by viewModel.lyricsFileImportFailed.collectAsState()
    // Loaded once per path, not on every recomposition -- Font(File) does real I/O/parsing.
    val lyricsFontFamily = remember(uiState.lyricsFontPath) {
        uiState.lyricsFontPath?.let {
            androidx.compose.ui.text.font.FontFamily(androidx.compose.ui.text.font.Font(java.io.File(it)))
        }
    }
    val pickLyricsFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importLyricsFile)
    }
    // Off by default: tapping a line just seeks to it, same as everywhere else in the app --
    // dictionary lookup only kicks in once this is switched on, so casually tapping through the
    // lyrics to seek around doesn't keep popping up word definitions.
    var wordSelectMode by remember { mutableStateOf(false) }
    var showToolsMenu by remember { mutableStateOf(false) }
    var showPreciseSyncConfirm by remember { mutableStateOf(false) }
    var fullscreenMode by remember { mutableStateOf(false) }
    val isPlaying = (playbackState as? dev.nami.domain.PlaybackState.Playing)?.isPlaying == true
    // The karaoke sweep needs frame-smooth position, not just "however often the player was
    // last polled" (100ms polling still visibly stepped). Interpolate between polls using real
    // elapsed time instead of polling faster -- every poll re-anchors this to the authoritative
    // value (so drift/seeks/pauses never accumulate), and each frame in between just adds
    // wall-clock time on top, which is free.
    var smoothPositionMs by remember { mutableStateOf(uiState.positionMs) }
    LaunchedEffect(uiState.positionMs, isPlaying) {
        val anchorPos = uiState.positionMs
        val anchorTime = android.os.SystemClock.elapsedRealtime()
        smoothPositionMs = anchorPos
        if (isPlaying) {
            while (true) {
                withFrameMillis { }
                smoothPositionMs = anchorPos + (android.os.SystemClock.elapsedRealtime() - anchorTime)
            }
        }
    }
    val scope = rememberCoroutineScope()
    // Swipe-to-dismiss used to manually animate dragOffsetY to screenHeightPx and only THEN call
    // onBack() -- meant to look like one continuous slide, but that coroutine sometimes never
    // reached onBack() at all (composable disposed/recomposed mid-animation cancels it, and a
    // spring's "close enough" settling can also just take a while), leaving showLyrics stuck
    // true forever: the screen sat fully slid off-screen but never actually closed, so
    // reopening was a silent no-op. onBack() now always fires immediately and synchronously;
    // dragOffsetY resets to 0 in the same breath so only the outer AnimatedVisibility's own exit
    // transition (in NamiNavHost) animates the slide-down, instead of two competing animations.
    fun dismiss() {
        // Not resetting dragOffsetY here -- doing so snapped the screen back to the top for one
        // frame (visible as a jump/teleport) before AnimatedVisibility's own exit transition
        // started sliding it back down from 0. Leaving it wherever the swipe left it means the
        // screen is already most of the way off-screen when the exit transition takes over.
        onBack()
    }

    Box(modifier = Modifier.fillMaxSize().offset { IntOffset(0, dragOffsetY.roundToInt()) }) {
        // Same ambient blurred-artwork backdrop as Now Playing -- the dark scrim on top keeps
        // contrast/legibility regardless of how light the artwork itself is, no per-pixel text
        // color logic needed, it's the same trick the rest of the app already relies on.
        val backgroundArtworkPath = queue.nowPlaying?.artworkPath
        if (backgroundArtworkPath != null) {
            AsyncImage(
                model = backgroundArtworkPath,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(64.dp),
            )
        }
        Box(modifier = Modifier.fillMaxSize().background(NamiColors.Ink900.copy(alpha = if (backgroundArtworkPath != null) 0.72f else 1f)))

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding().displayCutoutPadding()
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { delta -> dragOffsetY = (dragOffsetY + delta).coerceAtLeast(0f) },
                onDragStopped = { velocity ->
                    if (dragOffsetY > dismissThresholdPx || velocity > 2000f) {
                        dismiss()
                    } else {
                        scope.launch { animate(dragOffsetY, 0f) { value, _ -> dragOffsetY = value } }
                    }
                },
            ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
            // Straight to onBack(), not dismiss() -- dismiss()'s manual slide-then-flip coroutine
            // was a source of "the screen stays open forever after this" reports (its own
            // animate() apparently doesn't always run to completion), and the outer
            // AnimatedVisibility in NamiNavHost already animates the same slide-down on its own
            // exit transition. No coroutine to get stuck in means there's nothing left to get
            // stuck on.
            IconButton(onClick = { onBack() }) {
                Icon(Icons.Outlined.ArrowBack, contentDescription = "Назад", tint = NamiColors.Paper100)
            }
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                    .background(NamiColors.Ink700),
            ) {
                queue.nowPlaying?.artworkPath?.let { path ->
                    AsyncImage(model = path, contentDescription = null, modifier = Modifier.fillMaxSize())
                }
            }
            Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
                Text(
                    text = queue.nowPlaying?.title ?: "Текст песни",
                    color = NamiColors.Paper100,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                queue.nowPlaying?.artistName?.let { artist ->
                    Text(
                        text = artist,
                        color = NamiColors.Ai,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
            val hasLyrics = uiState.lyrics != null && uiState.lyrics!!.lines.isNotEmpty()
            if (hasLyrics) {
                IconButton(onClick = { showToolsMenu = !showToolsMenu }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "Инструменты", tint = NamiColors.Paper70)
                }
            }
        }

        val lyrics = uiState.lyrics
        if (lyrics == null || lyrics.lines.isEmpty()) {
            Column(modifier = Modifier.fillMaxWidth().weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(modifier = Modifier.height(64.dp))
                if (uiState.isFetchingOnline) {
                    androidx.compose.material3.CircularProgressIndicator(color = NamiColors.Paper70, modifier = Modifier.size(24.dp))
                    Text(text = "Ищу текст…", color = NamiColors.Paper70, modifier = Modifier.padding(top = 12.dp))
                } else {
                    Text(text = "Текст не найден", color = NamiColors.Paper70)
                    Text(
                        text = "Искать ещё раз в LRCLIB",
                        color = NamiColors.Shu,
                        modifier = Modifier.padding(top = 12.dp).clickable { viewModel.retryOnlineFetch() },
                    )
                    // STANDS4 is never tried automatically -- it burns the user's own daily quota,
                    // see LyricsUiState.stands4Configured's doc. No key configured -> no button at
                    // all, instead of one that would just silently miss every time.
                    if (uiState.stands4Configured) {
                        Text(
                            text = "Искать в STANDS4",
                            color = NamiColors.Shu,
                            modifier = Modifier.padding(top = 8.dp).clickable { viewModel.searchStands4() },
                        )
                    }
                }
                // STANDS4's terms require crediting them wherever their lyrics data is used --
                // shown here (the one place this app actually queries them) rather than tracking
                // per-track provenance through save/reload just to show it conditionally.
                Text(
                    text = "Источники текста: LRCLIB (lrclib.net), STANDS4 (stands4.com)",
                    color = NamiColors.Paper40,
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 16.dp),
                )
                Text(
                    text = "Добавить и синхронизировать вручную",
                    color = NamiColors.Paper70,
                    modifier = Modifier.padding(top = 12.dp).clickable { showEditor = true },
                )
                Text(
                    text = "Загрузить .lrc из файла",
                    color = NamiColors.Paper70,
                    modifier = Modifier.padding(top = 12.dp).clickable { pickLyricsFile.launch(arrayOf("*/*")) },
                )
            }
        } else {
            Box(modifier = Modifier.weight(1f)) {
                SyncedLyricsList(
                    lyrics = lyrics,
                    translation = uiState.translation.takeIf { uiState.showTranslation },
                    showFurigana = uiState.showFurigana,
                    romaji = uiState.romaji.takeIf { uiState.showRomaji },
                    wordTimings = uiState.wordTimings,
                    karaokeEnabled = uiState.karaokeEnabled,
                    studyModeEnabled = uiState.studyModeEnabled,
                    fontFamily = lyricsFontFamily,
                    positionMs = smoothPositionMs,
                    onLineClick = { viewModel.seekTo(it) },
                    tokenizeLine = { viewModel.tokenizeLine(it) },
                    wordSelectMode = wordSelectMode,
                    onWordTap = { token, contextLine -> viewModel.lookupWord(token, contextLine) },
                )
            }
        }

        LyricsTransportBar(
            isPlaying = isPlaying,
            onToggle = nowPlayingViewModel::toggle,
            onSkipPrevious = nowPlayingViewModel::skipPrevious,
            onSkipNext = nowPlayingViewModel::skipNext,
        )
    }

    // Overlay, not part of the Column above -- sits on top of the lyrics list instead of
    // pushing it down when it slides out. Anchored under the "..." button (header row height
    // plus statusbar inset), icons stacked vertically per Ф user request.
    androidx.compose.animation.AnimatedVisibility(
        visible = showToolsMenu,
        enter = androidx.compose.animation.expandVertically(expandFrom = Alignment.Top) + androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.shrinkVertically(shrinkTowards = Alignment.Top) + androidx.compose.animation.fadeOut(),
        modifier = Modifier
            .align(Alignment.TopEnd)
            .statusBarsPadding().displayCutoutPadding()
            .padding(top = 56.dp, end = 4.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .background(NamiColors.Ink700, androidx.compose.foundation.shape.RoundedCornerShape(28.dp))
                .padding(horizontal = 4.dp, vertical = 6.dp),
        ) {
            IconButton(onClick = { viewModel.toggleFurigana() }) {
                Text(
                    "振",
                    color = if (uiState.showFurigana) NamiColors.Shu else NamiColors.Paper70,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            IconButton(onClick = { showVocabulary = true }) {
                Icon(Icons.Outlined.MenuBook, contentDescription = "Мой словарик", tint = NamiColors.Paper70)
            }
            IconButton(onClick = { wordSelectMode = !wordSelectMode }) {
                Icon(
                    Icons.Outlined.TouchApp,
                    contentDescription = "Выбор слова для словаря",
                    tint = if (wordSelectMode) NamiColors.Shu else NamiColors.Paper70,
                )
            }
            if (uiState.isGeneratingRomaji) {
                androidx.compose.material3.CircularProgressIndicator(
                    color = NamiColors.Paper70,
                    strokeWidth = 2.dp,
                    modifier = Modifier.padding(vertical = 8.dp).size(20.dp),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .combinedClickable(
                            onClick = { viewModel.toggleRomaji() },
                            onLongClick = { viewModel.forceRegenerateRomaji() },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "R",
                        color = if (uiState.showRomaji) NamiColors.Shu else NamiColors.Paper70,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
            if (uiState.isTranslating) {
                androidx.compose.material3.CircularProgressIndicator(
                    color = NamiColors.Paper70,
                    strokeWidth = 2.dp,
                    modifier = Modifier.padding(vertical = 8.dp).size(20.dp),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .combinedClickable(
                            onClick = { viewModel.toggleTranslation() },
                            onLongClick = { viewModel.forceRetranslate() },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.Translate,
                        contentDescription = "Перевод (долгое нажатие - пересчитать заново)",
                        tint = if (uiState.showTranslation) NamiColors.Shu else NamiColors.Paper70,
                    )
                }
            }
            IconButton(onClick = { showEditor = true }) {
                Icon(Icons.Outlined.Edit, contentDescription = "Синхронизировать вручную", tint = NamiColors.Paper70)
            }
            IconButton(onClick = { pickLyricsFile.launch(arrayOf("*/*")) }) {
                Icon(Icons.Outlined.FileOpen, contentDescription = "Загрузить .lrc из файла", tint = NamiColors.Paper70)
            }
            IconButton(onClick = { fullscreenMode = true; showToolsMenu = false }) {
                Icon(Icons.Outlined.Fullscreen, contentDescription = "Полноэкранный режим", tint = NamiColors.Paper70)
            }
            // Real per-word timing (on-device whisper.cpp) instead of the linear-interpolation
            // karaoke sweep -- arm64-v8a only, and a ~500MB one-time model download, so this is
            // opt-in and hidden entirely when unsupported rather than failing at runtime.
            if (uiState.preciseSyncSupported) {
                if (uiState.isPreciseSyncing) {
                    androidx.compose.material3.CircularProgressIndicator(
                        progress = { uiState.preciseSyncProgress },
                        color = NamiColors.Shu,
                        strokeWidth = 2.dp,
                        modifier = Modifier.padding(vertical = 8.dp).size(20.dp),
                    )
                } else {
                    IconButton(onClick = { showPreciseSyncConfirm = true }) {
                        Icon(
                            Icons.Outlined.GraphicEq,
                            contentDescription = "Точная синхронизация караоке (Whisper, офлайн)",
                            tint = if (uiState.wordTimings != null) NamiColors.Shu else NamiColors.Paper70,
                        )
                    }
                }
            }
        }
    }
    }

    if (showPreciseSyncConfirm) {
        dev.nami.core.designsystem.NamiAlertDialog(
            onDismissRequest = { showPreciseSyncConfirm = false },
            title = { Text("Точная синхронизация", color = NamiColors.Paper100) },
            text = {
                Text(
                    "Разберёт текст по словам прямо на устройстве (whisper.cpp). При первом запуске " +
                        "скачает модель распознавания (~500 МБ) и займёт какое-то время на анализ трека.",
                    color = NamiColors.Paper70,
                )
            },
            confirmButton = {
                TextButton(onClick = { showPreciseSyncConfirm = false; viewModel.runPreciseSync() }) { Text("Запустить") }
            },
            dismissButton = { TextButton(onClick = { showPreciseSyncConfirm = false }) { Text("Отмена") } },
        )
    }

    if (showEditor) {
        ManualSyncEditor(
            initialText = uiState.lyrics?.lines?.joinToString("\n") { it.text } ?: "",
            currentPositionMs = { uiState.positionMs },
            onSave = { texts, stamps ->
                viewModel.saveManualSync(texts, stamps)
                showEditor = false
            },
            onDismiss = { showEditor = false },
        )
    }

    if (lyricsFileImportFailed) {
        dev.nami.core.designsystem.NamiAlertDialog(
            onDismissRequest = viewModel::dismissLyricsFileImportFailed,
            title = { Text("Не получилось", color = NamiColors.Paper100) },
            text = { Text("Файл не читается как .lrc (нужен формат [mm:ss.xx]текст)", color = NamiColors.Paper70) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissLyricsFileImportFailed) { Text("ОК") }
            },
        )
    }

    uiState.wordLookup?.let { lookup ->
        WordLookupDialog(
            lookup = lookup,
            onDismiss = { viewModel.dismissWordLookup() },
            onAddToVocabulary = { meaning ->
                viewModel.addToVocabulary(lookup.token.baseForm, lookup.token.readingHiragana, meaning, lookup.contextLine)
                viewModel.dismissWordLookup()
            },
        )
    }

    if (showVocabulary) {
        VocabularyScreen(onBack = { showVocabulary = false })
    }

    if (fullscreenMode) {
        val fsLyrics = uiState.lyrics
        if (fsLyrics != null && fsLyrics.lines.isNotEmpty()) {
            FullscreenLyricsOverlay(
                lyrics = fsLyrics,
                positionMs = smoothPositionMs,
                fontFamily = lyricsFontFamily,
                onDismiss = { fullscreenMode = false },
            )
        } else {
            fullscreenMode = false
        }
    }
}

/** План.md's "полноэкранный режим с крупным шрифтом и обратным отсчётом до следующей строки --
 * телефон на стол, можно петь". Tap anywhere to exit. */
@Composable
private fun FullscreenLyricsOverlay(
    lyrics: Lyrics,
    positionMs: Long,
    fontFamily: androidx.compose.ui.text.font.FontFamily?,
    onDismiss: () -> Unit,
) {
    val rawIndex = if (positionMs < (lyrics.lines.firstOrNull()?.timeMs ?: 0L)) {
        -1
    } else {
        lyrics.lines.indexOfLast { it.timeMs <= positionMs }
    }
    val currentLine = lyrics.lines.getOrNull(rawIndex)
    val nextLine = lyrics.lines.getOrNull(rawIndex + 1)
    val secondsToNext = nextLine?.let { ((it.timeMs - positionMs) / 1000f).coerceAtLeast(0f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NamiColors.Ink900)
            .clickable(
                indication = null,
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = currentLine?.text?.ifBlank { "…" } ?: "…",
                color = NamiColors.Paper100,
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                fontFamily = fontFamily,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            nextLine?.let { next ->
                Text(
                    text = next.text.ifBlank { "…" },
                    color = NamiColors.Paper70,
                    style = MaterialTheme.typography.titleLarge,
                    fontFamily = fontFamily,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(top = 24.dp, start = 24.dp, end = 24.dp),
                )
                if (secondsToNext != null && secondsToNext in 0f..9.5f) {
                    Text(
                        text = "%.0f с".format(secondsToNext),
                        color = NamiColors.Shu,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun WordLookupDialog(lookup: WordLookup, onDismiss: () -> Unit, onAddToVocabulary: (String) -> Unit) {
    dev.nami.core.designsystem.NamiAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(lookup.token.baseForm, color = NamiColors.Paper100)
                Text(
                    "「${lookup.token.readingHiragana}」",
                    color = NamiColors.Paper70,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        },
        text = {
            if (lookup.entries.isEmpty()) {
                Text("Ничего не нашлось в словаре", color = NamiColors.Paper70)
            } else {
                Column {
                    lookup.entries.take(5).forEach { entry ->
                        Column(modifier = Modifier.padding(bottom = 10.dp)) {
                            if (entry.partsOfSpeech.isNotEmpty()) {
                                Text(
                                    entry.partsOfSpeech.joinToString(", "),
                                    color = NamiColors.Ai,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                            Text(entry.glosses.joinToString("; "), color = NamiColors.Paper100)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAddToVocabulary(lookup.entries.firstOrNull()?.glosses?.firstOrNull().orEmpty()) }) {
                Text("В словарик")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}

@Composable
private fun SyncedLyricsList(
    lyrics: Lyrics,
    translation: List<String>?,
    showFurigana: Boolean,
    romaji: List<String>?,
    wordTimings: List<List<dev.nami.core.model.WordTiming>>?,
    karaokeEnabled: Boolean,
    studyModeEnabled: Boolean,
    fontFamily: androidx.compose.ui.text.font.FontFamily?,
    positionMs: Long,
    onLineClick: (Long) -> Unit,
    tokenizeLine: suspend (String) -> List<dev.nami.core.model.WordToken>,
    wordSelectMode: Boolean,
    onWordTap: (dev.nami.core.model.WordToken, String) -> Unit,
) {
    // Study mode (Beta): translation hidden per line until tapped -- forces actually recalling
    // the meaning instead of passively reading it alongside the original every time. Resets
    // per track (new remember key) rather than persisting across tracks/sessions.
    val revealedTranslations = remember(lyrics) { mutableStateMapOf<Int, Boolean>() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // Plain recomputation, not derivedStateOf -- derivedStateOf's lambda was captured once (by
    // remember(lyrics), which only re-runs when the LYRICS change) and kept reading whatever
    // `positionMs` happened to be at that first composition forever after, so the highlighted
    // line and autoscroll never advanced. This recomposes with `positionMs` normally instead.
    // -1 (not coerced to 0) before the first line's own timestamp -- vocals commonly start well
    // into the track, and clamping to 0 was lighting up line one as "currently playing" the whole
    // silent intro.
    val rawIndex = if (positionMs < (lyrics.lines.firstOrNull()?.timeMs ?: 0L)) {
        -1
    } else {
        lyrics.lines.indexOfLast { it.timeMs <= positionMs }
    }
    // Instrumental gap: the next line is far enough away, and enough time has passed since the
    // current one started, that nobody is actually singing right now -- don't keep the last line
    // lit as "active" through it.
    val nextLine = lyrics.lines.getOrNull(rawIndex + 1)
    val currentLine = lyrics.lines.getOrNull(rawIndex)
    // With real word timings for the current line, "singing has actually stopped" is just
    // "past the last known word's end" -- no more guessing a fixed fraction of the gap, which
    // was wrong (too early or too late) whenever a line's actual sung duration didn't match
    // that guess, including flagging a gap as silent while the vocalist was still singing.
    val currentLineWords = wordTimings?.getOrNull(rawIndex)
    val inGap = currentLine != null && nextLine != null &&
        (nextLine.timeMs - currentLine.timeMs) > GAP_MS &&
        if (!currentLineWords.isNullOrEmpty()) {
            positionMs > currentLineWords.last().endMs + WORD_TIMING_GAP_GRACE_MS
        } else {
            positionMs > currentLine.timeMs + ((nextLine.timeMs - currentLine.timeMs) * GAP_FRACTION_BEFORE_SILENT).toLong()
        }
    val currentIndex = if (inGap) -1 else rawIndex
    var lastCentered by remember { mutableIntStateOf(-1) }
    // Scrolls by the raw (gap-inclusive) index -- during an instrumental break there's no active
    // line to highlight, but the list should still be sitting at the last line that played, not
    // jump back to the top because the highlight temporarily went to -1.
    //
    // "-2 items back" (an earlier version of this) put the active line near the top, not centered
    // -- it assumed every item was the same fixed height, which isn't true here (romaji/
    // translation lines make some rows taller than others). Real centering: scroll to the item
    // first, then measure where it actually landed and correct by the leftover pixel delta so its
    // center lines up with the viewport's center regardless of row height.
    LaunchedEffect(rawIndex) {
        if (rawIndex == lastCentered) return@LaunchedEffect
        lastCentered = rawIndex
        val targetIndex = rawIndex.coerceAtLeast(0)
        scope.launch {
            // Two animations back to back (scroll-to-item, THEN a correction) is exactly the
            // "jumps too far, then slides back to center" the visible glitch this used to cause --
            // animateScrollToItem's own resting spot for a variable-height row rarely lands on
            // center, so the correction always fired, always as a second, separately-eased motion.
            // Normal line-to-line advance keeps the target already on screen (it's the very next
            // row), so measure first and do ONE smooth animateScrollBy covering the whole distance.
            // Only a genuine off-screen jump (track change, seek) needs an instant snap first.
            if (listState.layoutInfo.visibleItemsInfo.none { it.index == targetIndex }) {
                listState.scrollToItem(index = targetIndex)
            }
            val info = listState.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull { it.index == targetIndex } ?: return@launch
            val viewportCenter = (info.viewportStartOffset + info.viewportEndOffset) / 2
            val itemCenter = item.offset + item.size / 2
            val delta = (itemCenter - viewportCenter).toFloat()
            if (kotlin.math.abs(delta) > 1f) listState.animateScrollBy(delta)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Nothing is "active" right now (before the first line, or an instrumental gap) -- a
        // quiet note icon instead of leaving the last line looking like it's still being sung.
        androidx.compose.animation.AnimatedVisibility(
            visible = currentIndex == -1,
            enter = androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.fadeOut(),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Icon(
                Icons.Outlined.MusicNote,
                contentDescription = null,
                tint = NamiColors.Paper40,
                modifier = Modifier.size(28.dp),
            )
        }
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = 120.dp, bottom = 160.dp),
            modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp),
        ) {
            itemsIndexed(lyrics.lines) { index, line ->
                // Distance from the active line, not just "current or not" -- lines fade out the
                // further they are from what's playing, same falloff as most karaoke-style lyric
                // views (Apple/YT Music), instead of a flat dim/bright split.
                val distance = kotlin.math.abs(index - currentIndex)
                val targetAlpha = when {
                    distance == 0 -> 1f
                    distance == 1 -> 0.55f
                    distance == 2 -> 0.3f
                    else -> 0.15f
                }
                val isCurrent = distance == 0
                // Animated, not an instant cut -- alpha/scale ease between lines as the active
                // one changes, same spirit as the karaoke-style reference (a smooth handoff, not
                // a hard flip from one line to the next).
                val alpha by animateFloatAsState(targetAlpha, tween(350), label = "lyric-line-alpha")
                val scale by animateFloatAsState(if (isCurrent) 1f else 0.92f, tween(350), label = "lyric-line-scale")
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer { scaleX = scale; scaleY = scale; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f) }
                        .clickable { onLineClick(line.timeMs) }
                        .padding(vertical = 14.dp),
                ) {
                    // План.md's romaji/original/translation triplet -- romaji goes above the
                    // original line, translation below it.
                    romaji?.getOrNull(index)?.let { romajiText ->
                        Text(
                            text = romajiText,
                            color = NamiColors.Paper70.copy(alpha = alpha),
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = fontFamily,
                            modifier = Modifier.padding(bottom = 2.dp),
                        )
                    }
                    // Real per-word span (from an on-device Whisper alignment pass, "точная
                    // синхронизация") when available -- otherwise the linear on-device estimate
                    // (no free source for real word-level timing otherwise exists -- checked;
                    // Musixmatch/Suno-class APIs need a paid key, Spotify/Yandex internal
                    // endpoints are unofficial ToS violations), sweeping evenly across the line
                    // between its own timestamp and the next line's.
                    val itemNextLine = lyrics.lines.getOrNull(index + 1)
                    val itemWords = wordTimings?.getOrNull(index)
                    val karaokeProgress = when {
                        !isCurrent -> if (index < currentIndex) 1f else 0f
                        !itemWords.isNullOrEmpty() -> {
                            val start = itemWords.first().startMs
                            val end = itemWords.last().endMs
                            if (end <= start) 1f else ((positionMs - start).toFloat() / (end - start).toFloat()).coerceIn(0f, 1f)
                        }
                        itemNextLine == null -> 1f
                        else -> ((positionMs - line.timeMs).toFloat() / (itemNextLine.timeMs - line.timeMs).toFloat()).coerceIn(0f, 1f)
                    }
                    TappableLine(
                        line = line.text,
                        showFurigana = showFurigana,
                        color = NamiColors.Paper100.copy(alpha = alpha),
                        sungColor = NamiColors.Shu.copy(alpha = alpha),
                        karaokeProgress = if (isCurrent && karaokeEnabled) karaokeProgress else null,
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                        fontFamily = fontFamily,
                        tokenizeLine = tokenizeLine,
                        // Off: words aren't individually clickable at all, so the line's own
                        // clickable (seek) is the only thing that can react to the tap -- no
                        // separate onLineClick call needed here in that case, unlike when word
                        // selection is on and the word's own clickable would otherwise eat it.
                        wordSelectMode = wordSelectMode,
                        onWordTap = { token ->
                            onLineClick(line.timeMs)
                            onWordTap(token, line.text)
                        },
                    )
                    translation?.getOrNull(index)?.let { translatedText ->
                        val revealed = !studyModeEnabled || revealedTranslations[index] == true
                        Text(
                            text = if (revealed) translatedText else "・・・ тап, чтобы открыть перевод",
                            color = NamiColors.Paper70.copy(alpha = alpha),
                            style = MaterialTheme.typography.bodyMedium,
                            fontStyle = if (revealed) androidx.compose.ui.text.font.FontStyle.Normal else androidx.compose.ui.text.font.FontStyle.Italic,
                            fontFamily = fontFamily,
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .then(
                                    if (studyModeEnabled && !revealed) {
                                        // Also seeks (like the word-tap case above) -- this Text
                                        // sits inside the line's own clickable, which would
                                        // otherwise never see the tap at all.
                                        Modifier.clickable {
                                            revealedTranslations[index] = true
                                            onLineClick(line.timeMs)
                                        }
                                    } else {
                                        Modifier
                                    },
                                ),
                        )
                    }
                }
            }
        }
        // No separate edge-fade box here anymore -- it was a second scrim stacked on top of the
        // screen's own ambient one (0.72 alpha over 0.72 alpha compounds to ~0.92, reading as a
        // flat near-opaque patch right at the header/transport seams instead of "the same
        // background"). The per-line alpha falloff above already does the fade-into-background
        // job on its own; the whole screen now shares one uniform scrim, no darker bands.
    }
}

/** One rendering for original-text lines: tokenized live (Kuromoji, via [tokenizeLine]) into
 * words -- the SAME split used for both the furigana ruby-text overlay and for tap-to-dictionary,
 * so a reading is always positioned directly above the exact kanji it belongs to (one word, one
 * column, reading on top) rather than two independently-computed segmentations drifting apart. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun TappableLine(
    line: String,
    showFurigana: Boolean,
    color: androidx.compose.ui.graphics.Color,
    sungColor: androidx.compose.ui.graphics.Color,
    karaokeProgress: Float?,
    fontWeight: FontWeight,
    fontFamily: androidx.compose.ui.text.font.FontFamily?,
    tokenizeLine: suspend (String) -> List<dev.nami.core.model.WordToken>,
    wordSelectMode: Boolean,
    onWordTap: (dev.nami.core.model.WordToken) -> Unit,
) {
    val tokens by androidx.compose.runtime.produceState(initialValue = emptyList<dev.nami.core.model.WordToken>(), line) {
        value = tokenizeLine(line)
    }
    if (tokens.isEmpty()) {
        Text(text = line.ifBlank { "…" }, color = color, style = MaterialTheme.typography.headlineMedium, fontWeight = fontWeight, fontFamily = fontFamily)
        return
    }
    val totalLen = tokens.sumOf { it.surface.length }.coerceAtLeast(1)
    val sungChars = if (karaokeProgress != null) (totalLen * karaokeProgress).roundToInt() else 0
    var cumulative = 0
    androidx.compose.foundation.layout.FlowRow(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(2.dp)) {
        tokens.forEach { token ->
            val tokenEnd = cumulative + token.surface.length
            val isSung = karaokeProgress != null && tokenEnd <= sungChars
            cumulative = tokenEnd
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                // Off: no clickable on the word at all -- letting the tap fall through to the
                // line's own clickable (seek) instead of eating it for a dictionary popup nobody
                // asked for right now.
                modifier = if (wordSelectMode) Modifier.clickable { onWordTap(token) } else Modifier,
            ) {
                if (showFurigana) {
                    Text(text = if (token.hasKanji) token.readingHiragana else "", color = color, fontSize = 11.sp)
                }
                Text(
                    text = token.surface,
                    color = if (karaokeProgress != null && isSung) sungColor else color,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = fontWeight,
                    fontFamily = fontFamily,
                )
            }
        }
    }
}

@Composable
private fun LyricsTransportBar(
    isPlaying: Boolean,
    onToggle: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 32.dp, vertical = 20.dp)
            .padding(bottom = 16.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onSkipPrevious, modifier = Modifier.size(48.dp)) {
            Icon(
                Icons.Rounded.SkipPrevious,
                contentDescription = "Предыдущий трек",
                tint = NamiColors.Paper100,
                modifier = Modifier.size(32.dp),
            )
        }
        IconButton(
            onClick = onToggle,
            modifier = Modifier.size(64.dp).background(NamiColors.Paper100, androidx.compose.foundation.shape.CircleShape),
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (isPlaying) "Пауза" else "Играть",
                tint = NamiColors.Ink900,
                modifier = Modifier.size(32.dp),
            )
        }
        IconButton(onClick = onSkipNext, modifier = Modifier.size(48.dp)) {
            Icon(
                Icons.Rounded.SkipNext,
                contentDescription = "Следующий трек",
                tint = NamiColors.Paper100,
                modifier = Modifier.size(32.dp),
            )
        }
    }
}

/** Paste plain lines, then tap "Отметить" through the track once to stamp each line's timestamp
 * from the current playback position -- the offline equivalent of План.md's "играешь трек,
 * тапаешь на каждой строке" sync editor. */
@Composable
private fun ManualSyncEditor(
    initialText: String,
    currentPositionMs: () -> Long,
    onSave: (List<String>, List<Long>) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initialText) }
    var syncing by remember { mutableStateOf(false) }
    var stamps by remember { mutableStateOf<List<Long>>(emptyList()) }
    val lines = remember(text) { text.lines().filter { it.isNotBlank() } }

    Box(modifier = Modifier.fillMaxSize().background(NamiColors.Ink900)) {
        if (!syncing) {
            Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "Отмена", tint = NamiColors.Paper100)
                    }
                    Text(text = "Текст песни", color = NamiColors.Paper100, style = MaterialTheme.typography.titleLarge)
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("Вставь текст, по строке на строку") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(vertical = 12.dp),
                )
                TextButton(
                    onClick = { stamps = emptyList(); syncing = true },
                    enabled = lines.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Синхронизировать (${lines.size} строк)") }
            }
        } else {
            val currentLineIndex = stamps.size
            Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { syncing = false }) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "Назад к тексту", tint = NamiColors.Paper100)
                    }
                    Text(text = "Строка ${(currentLineIndex + 1).coerceAtMost(lines.size)} из ${lines.size}", color = NamiColors.Paper70)
                }
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        text = lines.getOrNull(currentLineIndex) ?: "Готово",
                        color = NamiColors.Paper100,
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
                TextButton(
                    onClick = {
                        if (currentLineIndex < lines.size) {
                            stamps = stamps + currentPositionMs()
                            if (stamps.size == lines.size) onSave(lines, stamps)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (currentLineIndex < lines.size) "Отметить" else "Сохранено") }
            }
        }
    }
}
