# Now Playing Restyle and Track Row "Now Playing" Indicator Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `NowPlayingScreen`'s spacing/typography/glow match Дизайн.md §4.3 (keeping our own `WaveformScrubber` as the scrubber), and mark the currently-playing track in every track list (Library Tracks tab, Album detail, Artist detail) with a `--shu` title + accent bar + a small moving-waveform indicator in place of its subtitle — even when that track was started from a different screen.

**Architecture:** `PlaybackState.Playing` already carries `trackId` and `isPlaying` (domain, unchanged). `LibraryViewModel` already has `PlayerRepository` injected; add the same to `AlbumDetailViewModel`/`ArtistDetailViewModel` and expose a `nowPlaying: StateFlow<NowPlayingRow?>` (trackId + isPlaying) from all three, derived via `map` from `playerRepository.state`. `TrackListItem` gets two new parameters (`isCurrentTrack: Boolean`, `isPlaying: Boolean`) driving the visual state; a new small `MiniPlayingIndicator` composable replaces the subtitle row when `isCurrentTrack` is true.

**Tech Stack:** Kotlin, Jetpack Compose (Material3, Canvas), existing `PlayerRepository`/`PlaybackState` domain types.

**Spec:** `C:\Users\Mozzarella6\Documents\main\Nami\Дизайн.md` §3 "Строка трека в списке" (playing-track visual rules), §4.3 "Now Playing — ключевой экран" (layout/spacing this plan restyles toward).

## Global Constraints

- Colors: `NamiColors` tokens only (`Shu`, `Ink500`, `Ink700`, `Ink900`, `Paper100`, `Paper70`, `Ai`) — no new hex literals.
- Keep the existing custom `WaveformScrubber` (feature/player) as the Now Playing scrubber; do not replace it with a different control. Its bar pattern accuracy is explicitly out of scope for this plan.
- Search results are explicitly out of scope (they don't use `TrackListItem`).
- No new Gradle dependencies.

---

### Task 1: Expose "now playing" trackId/isPlaying from the three list ViewModels

**Files:**
- Modify: `feature/library/src/main/kotlin/dev/nami/feature/library/LibraryViewModel.kt`
- Modify: `feature/library/src/main/kotlin/dev/nami/feature/library/AlbumDetailViewModel.kt`
- Modify: `feature/library/src/main/kotlin/dev/nami/feature/library/ArtistDetailViewModel.kt`
- Test: `feature/library/src/test/kotlin/dev/nami/feature/library/LibraryViewModelTest.kt`
- Test: `feature/library/src/test/kotlin/dev/nami/feature/library/AlbumDetailViewModelTest.kt`

**Interfaces:**
- Consumes: `PlayerRepository.state: StateFlow<PlaybackState>` (existing), `PlaybackState.Playing(trackId: TrackId, positionMs: Long, durationMs: Long, isPlaying: Boolean)` (existing, domain unchanged).
- Produces: `NowPlayingRow(val trackId: TrackId, val isPlaying: Boolean)` (new small data class, put in `LibraryViewModel.kt` since it's the first consumer, reused by the other two) and a `nowPlaying: StateFlow<NowPlayingRow?>` property on all three ViewModels — consumed by Task 2's screens.

- [ ] **Step 1: Add `NowPlayingRow` and expose it from `LibraryViewModel`**

`LibraryViewModel` already has `private val playerRepository: PlayerRepository` injected (used by `deleteTracks`). Add near the top of `LibraryViewModel.kt` (outside the class, alongside `enum class LibraryTab`):
```kotlin
data class NowPlayingRow(val trackId: TrackId, val isPlaying: Boolean)
```
Inside `LibraryViewModel`, add:
```kotlin
val nowPlaying: StateFlow<NowPlayingRow?> = playerRepository.state
    .map { state -> (state as? PlaybackState.Playing)?.let { NowPlayingRow(it.trackId, it.isPlaying) } }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
```
Add the imports `dev.nami.domain.PlaybackState`, `kotlinx.coroutines.flow.SharingStarted`, `kotlinx.coroutines.flow.map`, `kotlinx.coroutines.flow.stateIn`.

- [ ] **Step 2: Add `PlayerRepository` to `AlbumDetailViewModel` and expose the same**

In `feature/library/src/main/kotlin/dev/nami/feature/library/AlbumDetailViewModel.kt`, add `private val playerRepository: PlayerRepository` to the constructor (Hilt will inject it automatically -- `PlayerRepository` already has a bound implementation used elsewhere) and add:
```kotlin
val nowPlaying: StateFlow<NowPlayingRow?> = playerRepository.state
    .map { state -> (state as? PlaybackState.Playing)?.let { NowPlayingRow(it.trackId, it.isPlaying) } }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
```
Add the imports `dev.nami.domain.PlaybackState`, `dev.nami.domain.PlayerRepository`, `kotlinx.coroutines.flow.SharingStarted`, `kotlinx.coroutines.flow.map`, `kotlinx.coroutines.flow.stateIn`. `NowPlayingRow` is in the same package (`dev.nami.feature.library`), no import needed.

- [ ] **Step 3: Same for `ArtistDetailViewModel`**

In `feature/library/src/main/kotlin/dev/nami/feature/library/ArtistDetailViewModel.kt`, add `private val playerRepository: PlayerRepository` to the constructor and the identical `nowPlaying` property and imports as Step 2.

- [ ] **Step 4: Fix the two existing tests that construct these ViewModels**

`AlbumDetailViewModelTest.kt` constructs `AlbumDetailViewModel(fakeRepo, savedStateHandle)` -- it now needs a third argument. Add a minimal fake:
```kotlin
val fakePlayerRepo = object : PlayerRepository {
    override val state: StateFlow<PlaybackState> = MutableStateFlow(PlaybackState.Idle)
    override val queue: StateFlow<PlayerQueue> = MutableStateFlow(PlayerQueue.EMPTY)
    override suspend fun play(tracks: List<PlayableTrack>, startIndex: Int, startMs: Long) {}
    override suspend fun toggle() {}
    override suspend fun seek(ms: Long) {}
    override suspend fun skipNext() {}
    override suspend fun skipPrevious() {}
    override suspend fun addToQueue(track: PlayableTrack) {}
    override suspend fun moveQueueItem(fromIndex: Int, toIndex: Int) {}
    override suspend fun removeQueueItem(index: Int) {}
    override suspend fun removeTracks(ids: Set<TrackId>) {}
    override suspend fun stop() {}
}
```
and pass it: `AlbumDetailViewModel(fakeRepo, fakePlayerRepo, savedStateHandle)`. Add the imports `dev.nami.domain.PlayerRepository`, `dev.nami.domain.PlayerQueue`, `dev.nami.domain.PlayableTrack`, `dev.nami.domain.PlaybackState`, `kotlinx.coroutines.flow.MutableStateFlow`.

`LibraryViewModelTest.kt` already constructs `LibraryViewModel` with a `FakePlayerRepository` fake for every test case (it already has one, used for `deleteTracks`/`removeTracks`) -- no change needed there since the constructor signature isn't changing, only a new derived property is added.

- [ ] **Step 5: Build and test**

Run: `export JAVA_HOME="/c/Users/Mozzarella6/jdk-temurin-21/jdk-21.0.5+11" && ./gradlew :app:assembleDebug testDebugUnitTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: expose currently-playing trackId/isPlaying from Library/Album/Artist view models"
```

---

### Task 2: "Now playing" visual state in `TrackListItem` (accent bar, shu title, mini indicator)

**Files:**
- Create: `feature/library/src/main/kotlin/dev/nami/feature/library/MiniPlayingIndicator.kt`
- Modify: `feature/library/src/main/kotlin/dev/nami/feature/library/TrackListItem.kt`

**Interfaces:**
- Consumes: nothing external (pure UI).
- Produces: `TrackListItem` gains `isCurrentTrack: Boolean = false` and `isPlaying: Boolean = false` parameters; `MiniPlayingIndicator(isPlaying: Boolean, modifier: Modifier = Modifier)` -- consumed by Task 3's call sites.

- [ ] **Step 1: Write `MiniPlayingIndicator`**

A small 3-bar animated equalizer, `--shu` colored, per Дизайн.md §3's "микро-волна" idea but animated (moving) rather than static, since it stands in for the subtitle only while a track is actually the one loaded/playing:
```kotlin
package dev.nami.feature.library

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import dev.nami.core.designsystem.NamiColors

private data class BarSpec(val periodMs: Int, val minFraction: Float)

private val BARS = listOf(
    BarSpec(periodMs = 620, minFraction = 0.25f),
    BarSpec(periodMs = 480, minFraction = 0.35f),
    BarSpec(periodMs = 700, minFraction = 0.2f),
)

/** Three vertical bars that bounce continuously while [isPlaying], and sit at a fixed
 * mid-height when paused (still the current track, just not making sound right now). */
@Composable
fun MiniPlayingIndicator(isPlaying: Boolean, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "mini-playing-indicator")
    val fractions = BARS.map { bar ->
        if (isPlaying) {
            transition.animateFloat(
                initialValue = bar.minFraction,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(bar.periodMs, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "bar",
            ).value
        } else {
            0.55f
        }
    }
    Canvas(modifier = modifier.size(width = 14.dp, height = 14.dp)) {
        val barWidthPx = size.width / (BARS.size * 2 - 1)
        fractions.forEachIndexed { index, fraction ->
            val x = index * barWidthPx * 2 + barWidthPx / 2
            val barHeightPx = size.height * fraction
            drawLine(
                color = NamiColors.Shu,
                start = Offset(x, size.height - barHeightPx),
                end = Offset(x, size.height),
                strokeWidth = barWidthPx,
            )
        }
    }
}
```

- [ ] **Step 2: Wire the new parameters into `TrackListItem`**

Read `feature/library/src/main/kotlin/dev/nami/feature/library/TrackListItem.kt` in full first (it was modified across earlier plans this session -- confirm current parameter list before editing). Add two parameters and use them:
```kotlin
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackListItem(
    track: Track,
    onClick: () -> Unit,
    onAddToQueue: (() -> Unit)? = null,
    onAddToPlaylist: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onRename: (() -> Unit)? = null,
    onRemoveFromAlbum: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    selectionMode: Boolean = false,
    isSelected: Boolean = false,
    isCurrentTrack: Boolean = false,
    isPlaying: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var showMenu by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isCurrentTrack) {
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(32.dp)
                    .background(NamiColors.Shu),
            )
            Spacer(modifier = Modifier.width(14.dp))
        }
        if (track.albumArtworkPath != null) {
            AsyncImage(
                model = track.albumArtworkPath,
                contentDescription = track.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(44.dp)
                    .background(NamiColors.Ink700, RoundedCornerShape(4.dp)),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(NamiColors.Ink700, RoundedCornerShape(4.dp)),
            )
        }
        Column(modifier = Modifier.padding(start = 16.dp).weight(1f)) {
            Text(
                text = track.title,
                color = if (isCurrentTrack) NamiColors.Shu else NamiColors.Paper100,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (isCurrentTrack) {
                MiniPlayingIndicator(isPlaying = isPlaying, modifier = Modifier.padding(top = 4.dp))
            } else {
                Text(
                    text = subtitleFor(track),
                    color = NamiColors.Paper70,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                )
            }
        }
        if (selectionMode) {
            Checkbox(checked = isSelected, onCheckedChange = null)
        } else if (onAddToPlaylist != null || onAddToQueue != null || onDelete != null || onRename != null || onRemoveFromAlbum != null) {
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Ещё", tint = NamiColors.Paper40)
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    onAddToPlaylist?.let { action ->
                        DropdownMenuItem(
                            text = { Text("В плейлист") },
                            leadingIcon = { Icon(Icons.Filled.LibraryAdd, contentDescription = null) },
                            onClick = { showMenu = false; action() },
                        )
                    }
                    onAddToQueue?.let { action ->
                        DropdownMenuItem(
                            text = { Text("В очередь") },
                            leadingIcon = { Icon(Icons.Filled.PlaylistAdd, contentDescription = null) },
                            onClick = { showMenu = false; action() },
                        )
                    }
                    onRename?.let { action ->
                        DropdownMenuItem(
                            text = { Text("Переименовать") },
                            leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                            onClick = { showMenu = false; action() },
                        )
                    }
                    onRemoveFromAlbum?.let { action ->
                        DropdownMenuItem(
                            text = { Text("Убрать из альбома") },
                            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                            onClick = { showMenu = false; action() },
                        )
                    }
                    onDelete?.let { action ->
                        DropdownMenuItem(
                            text = { Text("Удалить") },
                            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                            onClick = { showMenu = false; action() },
                        )
                    }
                }
            }
        }
    }
}
```
This is the full function body with the two additions inlined (the accent bar block at the top of the `Row`'s children, and the `if (isCurrentTrack) MiniPlayingIndicator(...) else Text(subtitleFor(track), ...)` swap) -- everything else is unchanged from the current file. Add the imports `androidx.compose.foundation.layout.Spacer`, `androidx.compose.foundation.layout.width`.

- [ ] **Step 3: Build**

Run: `export JAVA_HOME="/c/Users/Mozzarella6/jdk-temurin-21/jdk-21.0.5+11" && ./gradlew :feature:library:compileDebugKotlin`
Expected: PASS (nothing calls the new parameters yet, both default to `false`, so no other call site breaks).

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat: add now-playing accent bar and animated mini indicator to TrackListItem"
```

---

### Task 3: Wire the indicator into the three list screens

**Files:**
- Modify: `feature/library/src/main/kotlin/dev/nami/feature/library/LibraryScreen.kt`
- Modify: `feature/library/src/main/kotlin/dev/nami/feature/library/AlbumDetailScreen.kt`
- Modify: `feature/library/src/main/kotlin/dev/nami/feature/library/ArtistDetailScreen.kt`

**Interfaces:**
- Consumes: `LibraryViewModel.nowPlaying`/`AlbumDetailViewModel.nowPlaying`/`ArtistDetailViewModel.nowPlaying` (Task 1), `TrackListItem(isCurrentTrack, isPlaying, ...)` (Task 2).

- [ ] **Step 1: Wire it into `LibraryScreen`'s Tracks tab**

In `LibraryScreen`'s main composable body, add `val nowPlaying by viewModel.nowPlaying.collectAsState()` alongside the existing `val uiState by viewModel.uiState.collectAsState()`. Thread it down: add `nowPlaying: NowPlayingRow?` as a new parameter to the private `TrackListContent` composable, pass `nowPlaying = nowPlaying` at its call site, and inside `TrackListContent`'s `TrackListItem(...)` call add:
```kotlin
isCurrentTrack = track.id == nowPlaying?.trackId,
isPlaying = track.id == nowPlaying?.trackId && nowPlaying.isPlaying,
```

- [ ] **Step 2: Wire it into `AlbumDetailScreen`**

Add `val nowPlaying by viewModel.nowPlaying.collectAsState()` near the existing `val uiState by viewModel.uiState.collectAsState()`. In the `TrackListItem(...)` call inside the `itemsIndexed(uiState.tracks, ...)` block, add:
```kotlin
isCurrentTrack = track.id == nowPlaying?.trackId,
isPlaying = track.id == nowPlaying?.trackId && nowPlaying.isPlaying,
```

- [ ] **Step 3: Wire it into `ArtistDetailScreen`**

Same pattern: add `val nowPlaying by viewModel.nowPlaying.collectAsState()`, and in its `TrackListItem(...)` call add the same two lines as Step 2.

- [ ] **Step 4: Build and test**

Run: `export JAVA_HOME="/c/Users/Mozzarella6/jdk-temurin-21/jdk-21.0.5+11" && ./gradlew :app:assembleDebug testDebugUnitTest`
Expected: PASS.

- [ ] **Step 5: Install and manually verify**

Run: `adb install -r C:/Nami/app/build/outputs/apk/debug/app-debug.apk`
Check: start a track from the Library Tracks tab, then navigate to that track's album (or artist) -- its row there also shows the `--shu` title, accent bar, and moving mini-indicator instead of the artist/duration subtitle; pausing freezes the indicator at mid-height instead of animating.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: show now-playing indicator in track rows across Library/Album/Artist screens"
```

---

### Task 4: Restyle `NowPlayingScreen` toward the Дизайн.md §4.3 mockup

**Files:**
- Modify: `feature/player/src/main/kotlin/dev/nami/feature/player/NowPlayingScreen.kt`

**Interfaces:** none new -- pure visual restyle, same `NowPlayingScreen(onCollapse, onQueueClick, viewModel)` signature.

- [ ] **Step 1: Read the current file in full**

Read `feature/player/src/main/kotlin/dev/nami/feature/player/NowPlayingScreen.kt` completely before editing (it has the swipe-to-dismiss/skip gesture code from earlier plans this session -- none of that changes here, only spacing/visuals around it).

- [ ] **Step 2: Add a glow shadow under the artwork**

Per Дизайн.md §2 "Границы и тени": "под обложкой на Now Playing — мягкое свечение цветом акцента, `0 24px 64px rgba(accent, 0.18)`". Compose doesn't have CSS box-shadow, but `Modifier.shadow` with a colored ambient/spot color approximates it on API 28+; a simpler, broadly-compatible approximation is a soft radial-gradient `Box` placed behind the artwork, slightly larger, blurred via `Modifier.blur`. Replace the artwork block:
```kotlin
Box(contentAlignment = androidx.compose.ui.Alignment.Center) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .padding(vertical = 24.dp)
            .background(
                androidx.compose.ui.graphics.Brush.radialGradient(
                    colors = listOf(NamiColors.Shu.copy(alpha = 0.35f), NamiColors.Shu.copy(alpha = 0f)),
                ),
            )
            .blur(32.dp),
    )
    if (queue.nowPlaying?.artworkPath != null) {
        AsyncImage(
            model = queue.nowPlaying?.artworkPath,
            contentDescription = queue.nowPlaying?.title,
            contentScale = ContentScale.Crop,
            modifier = artworkModifier,
        )
    } else {
        Box(modifier = artworkModifier)
    }
}
```
Add the import `androidx.compose.ui.draw.blur`. Keep the existing `artworkModifier` `val` (with its drag/skip gesture) exactly as it is -- only the surrounding `Box` wrapper and glow layer are new. Note: `Modifier.blur` requires API 31+ to render an actual blur (older devices silently skip the blur, showing a soft-edged gradient without blur, which still reads as a glow -- acceptable given minSdk 26 already tolerates graceful degradation elsewhere in this app).

- [ ] **Step 3: Match spec spacing rhythm around the title/artist/format block**

Per Дизайн.md §2 "Сетка и отступы": step scale `4·8·12·16·20·24·32·40·56`, vertical rhythm between blocks 32, inside a block 12. Adjust the existing title/artist/format Texts' `padding(top = ...)` values to this scale (currently uses `12.dp` for the format badge, `16.dp`/`8.dp` for the scrubber/time row -- these already sit on the scale; the one adjustment is the gap between the artwork and the title, currently implicit via the artwork's own `padding(vertical = 24.dp)`, which is already on-scale). No code change needed here beyond what Step 2 already touched -- this step is a verification pass: read through the spacing values in the file after Step 2 and confirm each is one of `4,8,12,16,20,24,32,40,56`; adjust any that aren't (there should be none left).

- [ ] **Step 4: Build**

Run: `export JAVA_HOME="/c/Users/Mozzarella6/jdk-temurin-21/jdk-21.0.5+11" && ./gradlew :app:assembleDebug`
Expected: PASS.

- [ ] **Step 5: Install and manually verify**

Run: `adb install -r C:/Nami/app/build/outputs/apk/debug/app-debug.apk`
Check: Now Playing's artwork has a soft colored glow behind it; the waveform scrubber (unchanged component) still works for tap/drag-seek; swipe-to-dismiss and swipe-to-skip still work exactly as before.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "style: add accent glow under Now Playing artwork, verify spacing matches the design scale"
```
