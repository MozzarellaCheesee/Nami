package dev.nami.feature.player

import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
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
import androidx.compose.runtime.derivedStateOf
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
    val density = LocalDensity.current
    val dismissThresholdPx = with(density) { DISMISS_THRESHOLD_DP.dp.toPx() }
    val screenHeightPx = with(density) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    var dragOffsetY by remember { mutableStateOf(0f) }
    var showEditor by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .offset { IntOffset(0, dragOffsetY.roundToInt()) }
            .background(NamiColors.Ink900)
            .statusBarsPadding()
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { delta -> dragOffsetY = (dragOffsetY + delta).coerceAtLeast(0f) },
                onDragStopped = { velocity ->
                    if (dragOffsetY > dismissThresholdPx || velocity > 2000f) {
                        animate(dragOffsetY, screenHeightPx) { value, _ -> dragOffsetY = value }
                        onBack()
                    } else {
                        animate(dragOffsetY, 0f) { value, _ -> dragOffsetY = value }
                    }
                },
            ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
            IconButton(onClick = onBack) {
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
    val currentIndex = lyrics.lines.indexOfLast { it.timeMs <= positionMs }.coerceAtLeast(0)
    var lastCentered by remember { mutableIntStateOf(-1) }
    LaunchedEffect(currentIndex) {
        if (currentIndex != lastCentered) {
            lastCentered = currentIndex
            scope.launch {
                listState.animateScrollToItem(
                    index = (currentIndex - 2).coerceAtLeast(0),
                )
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
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
                val alpha = when {
                    distance == 0 -> 1f
                    distance == 1 -> 0.55f
                    distance == 2 -> 0.3f
                    else -> 0.15f
                }
                val isCurrent = distance == 0
                Text(
                    text = line.text.ifBlank { "…" },
                    color = (if (isCurrent) NamiColors.Paper100 else NamiColors.Paper100).copy(alpha = alpha),
                    style = if (isCurrent) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onLineClick(line.timeMs) }
                        .padding(vertical = 14.dp),
                )
            }
        }
        // Edge fade so lines don't just hard-cut at the top/bottom of the list -- the whole
        // point of the falloff above is a smooth gradient into the background, not a clip.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .align(Alignment.TopCenter)
                .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(NamiColors.Ink900, androidx.compose.ui.graphics.Color.Transparent))),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .align(Alignment.BottomCenter)
                .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(androidx.compose.ui.graphics.Color.Transparent, NamiColors.Ink900))),
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
            .padding(horizontal = 32.dp, vertical = 20.dp),
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
