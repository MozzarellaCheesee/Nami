package dev.nami.feature.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material.icons.outlined.MusicNote
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
import androidx.hilt.navigation.compose.hiltViewModel
import dev.nami.core.model.Lyrics
import dev.nami.core.designsystem.NamiColors
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val DISMISS_THRESHOLD_DP = 120
// A gap this long between two lines is an instrumental break, not just normal breathing room
// between sung lines -- past LINE_DURATION_ASSUMPTION_MS into it, nothing is "currently playing"
// even though the last line's timestamp has technically passed, so it shouldn't stay lit as if
// the vocalist were still on it.
private const val GAP_MS = 5000L
private const val LINE_DURATION_ASSUMPTION_MS = 2500L

/** Three of План.md's four "18. Экран лирики" modes (furigana, romaji triplet, karaoke word
 * highlight) and its dictionary/Anki/LRCLIB pieces aren't here -- this is the load-bearing first
 * slice: parse/show/auto-scroll/tap-to-seek synced lyrics from a local .lrc, and a manual
 * tap-to-stamp editor for tracks that don't have one yet. */
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
            Text(text = "Текст песни", color = NamiColors.Paper100, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
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
                SyncedLyricsList(lyrics = lyrics, positionMs = uiState.positionMs, onLineClick = { viewModel.seekTo(it) })
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
}

@Composable
private fun SyncedLyricsList(lyrics: Lyrics, positionMs: Long, onLineClick: (Long) -> Unit) {
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
        positionMs > currentLine.timeMs + LINE_DURATION_ASSUMPTION_MS
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
                Text(
                    text = line.text.ifBlank { "…" },
                    color = NamiColors.Paper100.copy(alpha = alpha),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer { scaleX = scale; scaleY = scale; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f) }
                        .clickable { onLineClick(line.timeMs) }
                        .padding(vertical = 14.dp),
                )
            }
        }
        // Edge fade so lines don't just hard-cut at the top/bottom of the list -- was solid
        // opaque Ink900 fading to transparent, which read as a flat grey patch stacked right on
        // top of the ambient blurred backdrop showing everywhere else (right where it meets the
        // header/transport bar, the two spots this was most obvious). Same alpha as the scrim
        // over the rest of the screen instead of full opacity, so it's the same background, only
        // built up a little for text legibility, not a visibly different patch.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .align(Alignment.TopCenter)
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(NamiColors.Ink900.copy(alpha = 0.72f), androidx.compose.ui.graphics.Color.Transparent),
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .align(Alignment.BottomCenter)
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(androidx.compose.ui.graphics.Color.Transparent, NamiColors.Ink900.copy(alpha = 0.72f)),
                    ),
                ),
        )
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
