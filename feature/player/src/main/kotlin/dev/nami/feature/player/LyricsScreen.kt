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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MenuBook
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
private const val GAP_FRACTION_BEFORE_SILENT = 0.7f

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
    // Off by default: tapping a line just seeks to it, same as everywhere else in the app --
    // dictionary lookup only kicks in once this is switched on, so casually tapping through the
    // lyrics to seek around doesn't keep popping up word definitions.
    var wordSelectMode by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // Shared by the drag gesture and the back button -- both need the same "slide fully off,
    // THEN flip the state" sequence instead of an instant cut.
    fun dismiss() {
        scope.launch {
            animate(dragOffsetY, screenHeightPx) { value, _ -> dragOffsetY = value }
            onBack()
        }
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
            .statusBarsPadding()
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
            IconButton(onClick = { dismiss() }) {
                Icon(Icons.Outlined.ArrowBack, contentDescription = "Назад", tint = NamiColors.Paper100)
            }
            Text(
                text = "Текст песни",
                color = NamiColors.Paper100,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            val hasLyrics = uiState.lyrics != null && uiState.lyrics!!.lines.isNotEmpty()
            if (hasLyrics) {
                // Live, not generated/cached -- tokenizing one line is fast, so this is just a
                // display flip, no long-press-to-regenerate escape hatch needed.
                IconButton(onClick = { viewModel.toggleFurigana() }) {
                    Text(
                        "振",
                        color = if (uiState.showFurigana) NamiColors.Shu else NamiColors.Paper70,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                IconButton(onClick = { showVocabulary = true }) {
                    Icon(
                        Icons.Outlined.MenuBook,
                        contentDescription = "Мой словарик",
                        tint = NamiColors.Paper70,
                    )
                }
                IconButton(onClick = { wordSelectMode = !wordSelectMode }) {
                    Icon(
                        Icons.Outlined.TouchApp,
                        contentDescription = "Выбор слова для словаря",
                        tint = if (wordSelectMode) NamiColors.Shu else NamiColors.Paper70,
                    )
                }
            }
            if (uiState.isGeneratingRomaji) {
                androidx.compose.material3.CircularProgressIndicator(
                    color = NamiColors.Paper70,
                    strokeWidth = 2.dp,
                    modifier = Modifier.padding(horizontal = 8.dp).size(20.dp),
                )
            } else if (hasLyrics) {
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
                    modifier = Modifier.padding(horizontal = 8.dp).size(20.dp),
                )
            } else if (hasLyrics) {
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
                        contentDescription = "Перевод (долгое нажатие -- пересчитать заново)",
                        tint = if (uiState.showTranslation) NamiColors.Shu else NamiColors.Paper70,
                    )
                }
            }
            IconButton(onClick = { showEditor = true }) {
                Icon(Icons.Outlined.Edit, contentDescription = "Синхронизировать вручную", tint = NamiColors.Paper70)
            }
        }

        val lyrics = uiState.lyrics
        if (lyrics == null || lyrics.lines.isEmpty()) {
            Column(modifier = Modifier.fillMaxWidth().weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(modifier = Modifier.height(64.dp))
                if (uiState.isFetchingOnline) {
                    androidx.compose.material3.CircularProgressIndicator(color = NamiColors.Paper70, modifier = Modifier.size(24.dp))
                    Text(text = "Ищу текст в LRCLIB…", color = NamiColors.Paper70, modifier = Modifier.padding(top = 12.dp))
                } else {
                    Text(text = "Текст не найден", color = NamiColors.Paper70)
                    Text(
                        text = "Искать ещё раз в сети",
                        color = NamiColors.Shu,
                        modifier = Modifier.padding(top = 12.dp).clickable { viewModel.retryOnlineFetch() },
                    )
                }
                Text(
                    text = "Добавить и синхронизировать вручную",
                    color = NamiColors.Paper70,
                    modifier = Modifier.padding(top = 12.dp).clickable { showEditor = true },
                )
            }
        } else {
            Box(modifier = Modifier.weight(1f)) {
                SyncedLyricsList(
                    lyrics = lyrics,
                    translation = uiState.translation.takeIf { uiState.showTranslation },
                    showFurigana = uiState.showFurigana,
                    romaji = uiState.romaji.takeIf { uiState.showRomaji },
                    positionMs = uiState.positionMs,
                    onLineClick = { viewModel.seekTo(it) },
                    tokenizeLine = { viewModel.tokenizeLine(it) },
                    wordSelectMode = wordSelectMode,
                    onWordTap = { token, contextLine -> viewModel.lookupWord(token, contextLine) },
                )
            }
        }

        LyricsTransportBar(
            isPlaying = (playbackState as? dev.nami.domain.PlaybackState.Playing)?.isPlaying == true,
            onToggle = nowPlayingViewModel::toggle,
            onSkipPrevious = nowPlayingViewModel::skipPrevious,
            onSkipNext = nowPlayingViewModel::skipNext,
        )
    }
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
    positionMs: Long,
    onLineClick: (Long) -> Unit,
    tokenizeLine: suspend (String) -> List<dev.nami.core.model.WordToken>,
    wordSelectMode: Boolean,
    onWordTap: (dev.nami.core.model.WordToken, String) -> Unit,
) {
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
    val inGap = currentLine != null && nextLine != null &&
        (nextLine.timeMs - currentLine.timeMs) > GAP_MS &&
        positionMs > currentLine.timeMs + ((nextLine.timeMs - currentLine.timeMs) * GAP_FRACTION_BEFORE_SILENT).toLong()
    val currentIndex = if (inGap) -1 else rawIndex
    var lastCentered by remember { mutableIntStateOf(-1) }
    // Scrolls by the raw (gap-inclusive) index -- during an instrumental break there's no active
    // line to highlight, but the list should still be sitting at the last line that played, not
    // jump back to the top because the highlight temporarily went to -1.
    LaunchedEffect(rawIndex) {
        if (rawIndex != lastCentered) {
            lastCentered = rawIndex
            scope.launch {
                listState.animateScrollToItem(
                    index = (rawIndex - 2).coerceAtLeast(0),
                )
            }
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
                            modifier = Modifier.padding(bottom = 2.dp),
                        )
                    }
                    // Linear on-device estimate, not real per-word timing (no free source for
                    // that exists -- checked; Musixmatch/Suno-class APIs need a paid key, Spotify
                    // /Yandex internal endpoints are unofficial ToS violations). Sweeps evenly
                    // across the line between its own timestamp and the next line's.
                    val itemNextLine = lyrics.lines.getOrNull(index + 1)
                    val karaokeProgress = when {
                        !isCurrent -> if (index < currentIndex) 1f else 0f
                        itemNextLine == null -> 1f
                        else -> ((positionMs - line.timeMs).toFloat() / (itemNextLine.timeMs - line.timeMs).toFloat()).coerceIn(0f, 1f)
                    }
                    TappableLine(
                        line = line.text,
                        showFurigana = showFurigana,
                        color = NamiColors.Paper100.copy(alpha = alpha),
                        sungColor = NamiColors.Shu.copy(alpha = alpha),
                        karaokeProgress = if (isCurrent) karaokeProgress else null,
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
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
                        Text(
                            text = translatedText,
                            color = NamiColors.Paper70.copy(alpha = alpha),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 2.dp),
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
    tokenizeLine: suspend (String) -> List<dev.nami.core.model.WordToken>,
    wordSelectMode: Boolean,
    onWordTap: (dev.nami.core.model.WordToken) -> Unit,
) {
    val tokens by androidx.compose.runtime.produceState(initialValue = emptyList<dev.nami.core.model.WordToken>(), line) {
        value = tokenizeLine(line)
    }
    if (tokens.isEmpty()) {
        Text(text = line.ifBlank { "…" }, color = color, style = MaterialTheme.typography.headlineMedium, fontWeight = fontWeight)
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
