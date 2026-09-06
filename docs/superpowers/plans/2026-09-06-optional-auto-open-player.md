# Optional Auto-Open Player on Track Tap Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a persisted, default-OFF setting "Открывать плеер при выборе трека". When OFF, tapping a track anywhere starts playback but keeps the user on the current screen -- MiniPlayer slides into view if it wasn't showing, or plays its existing "slide to next" artwork transition if it was already showing a different track. When ON, tapping a track opens Now Playing immediately (today's only behavior).

**Architecture:** A tiny `SharedPreferences`-backed `AppSettingsRepository` (new, `data` module) exposes `autoOpenPlayer: StateFlow<Boolean>` and `setAutoOpenPlayer(Boolean)`. `NamiNavHost`'s five track-tap call sites read a `SettingsViewModel`'s `autoOpenPlayer` value to decide whether to flip `showNowPlaying = true`. `MiniPlayer` gains an `AnimatedVisibility` wrapper (slide-in on first appearance) at its `NamiNavHost` call site, and a new effect that plays the existing horizontal artwork-slide transition automatically when the current track id changes for a reason OTHER than the user's own swipe gesture (a dedicated "external change" signal from `NowPlayingViewModel`, bumped only by `playTrack`/`playTracks`/`playFromLibrary` -- never by `skipNext`/`skipPrevious`, so it can't collide with the manual swipe's own animation).

**Tech Stack:** Kotlin, Jetpack Compose, plain Android `SharedPreferences` (no new Gradle dependency), Hilt.

**Spec:** none (this is a UX behavior request from this session, not from Дизайн.md).

## Global Constraints

- No new Gradle dependencies -- use `android.content.SharedPreferences`, not Jetpack DataStore.
- Colors/spacing: reuse `NamiColors` tokens for the new settings row; no new hex literals.
- The existing manual swipe-to-skip animation in `MiniPlayer.kt` (the horizontal `draggable` on the artwork+title block) must keep working exactly as today and must never run twice for the same track change.

---

### Task 1: `AppSettingsRepository` (SharedPreferences-backed, one boolean)

**Files:**
- Create: `data/src/main/kotlin/dev/nami/data/AppSettingsRepository.kt`
- Test: `data/src/test/kotlin/dev/nami/data/AppSettingsRepositoryTest.kt`

**Interfaces:**
- Produces: `AppSettingsRepository` with `val autoOpenPlayer: StateFlow<Boolean>` and `fun setAutoOpenPlayer(value: Boolean)` -- consumed by Task 2's `SettingsViewModel`.

- [ ] **Step 1: Write the repository**

```kotlin
package dev.nami.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

private const val PREFS_NAME = "nami_settings"
private const val KEY_AUTO_OPEN_PLAYER = "auto_open_player"

@Singleton
class AppSettingsRepository @Inject constructor(@ApplicationContext context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Default OFF: tapping a track starts playback without jumping to Now Playing, per the
    // explicit request this setting exists for -- opening the full player is opt-in.
    private val _autoOpenPlayer = MutableStateFlow(prefs.getBoolean(KEY_AUTO_OPEN_PLAYER, false))
    val autoOpenPlayer: StateFlow<Boolean> = _autoOpenPlayer

    fun setAutoOpenPlayer(value: Boolean) {
        prefs.edit { putBoolean(KEY_AUTO_OPEN_PLAYER, value) }
        _autoOpenPlayer.value = value
    }
}
```
`androidx.core.content.edit` (the `SharedPreferences.edit { }` extension) comes from `androidx.core:core-ktx`, already a transitive dependency of this project's AndroidX usage elsewhere (Hilt/Compose apps always pull it in) -- if `:data`'s `build.gradle.kts` doesn't already depend on `androidx.core:core-ktx`, add `implementation(libs.androidx.core.ktx)` to it (check `gradle/libs.versions.toml` for the exact alias -- it's very likely already defined and used by the `app` module; reuse the same alias, don't invent a new version).

- [ ] **Step 2: Write a test using a real (Robolectric) `SharedPreferences`**

Check how other `data` module tests get an Android `Context` for real (non-mocked) Android APIs -- `LibraryRepositoryImplTest.kt` uses `@RunWith(RobolectricTestRunner::class)` with `ApplicationProvider.getApplicationContext()`. Follow the exact same pattern:
```kotlin
package dev.nami.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppSettingsRepositoryTest {

    @Test
    fun `defaults to false and persists a change`() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val repo = AppSettingsRepository(context)

        assertFalse(repo.autoOpenPlayer.value)

        repo.setAutoOpenPlayer(true)
        assertEquals(true, repo.autoOpenPlayer.value)

        // A second instance reading the same SharedPreferences file sees the persisted value.
        val repo2 = AppSettingsRepository(context)
        assertEquals(true, repo2.autoOpenPlayer.value)
    }
}
```

- [ ] **Step 3: Build and test**

Run: `export JAVA_HOME="/c/Users/Mozzarella6/jdk-temurin-21/jdk-21.0.5+11" && ./gradlew :data:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat: add AppSettingsRepository for the auto-open-player preference"
```

---

### Task 2: `SettingsViewModel` and the toggle row in `SettingsScreen`

**Files:**
- Create: `app/src/main/kotlin/dev/nami/app/SettingsViewModel.kt`
- Modify: `app/src/main/kotlin/dev/nami/app/PlaceholderScreens.kt`

**Interfaces:**
- Consumes: `AppSettingsRepository` (Task 1).
- Produces: `SettingsViewModel.autoOpenPlayer: StateFlow<Boolean>`, `.setAutoOpenPlayer(Boolean)` -- consumed by `SettingsScreen` (this task) and by `NamiNavHost` (Task 3, via a second `hiltViewModel()` instance of the same class -- Hilt ViewModels scoped to an Activity-level owner are singletons per that owner, so both call sites observe the same state).

- [ ] **Step 1: Write `SettingsViewModel`**

```kotlin
package dev.nami.app

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nami.data.AppSettingsRepository
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val appSettingsRepository: AppSettingsRepository,
) : ViewModel() {

    val autoOpenPlayer: StateFlow<Boolean> = appSettingsRepository.autoOpenPlayer

    fun setAutoOpenPlayer(value: Boolean) {
        appSettingsRepository.setAutoOpenPlayer(value)
    }
}
```

- [ ] **Step 2: Add the toggle row to `SettingsScreen`**

Read `app/src/main/kotlin/dev/nami/app/PlaceholderScreens.kt` in full first (it's currently an 18-line static placeholder). Replace it with:
```kotlin
package dev.nami.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.nami.core.designsystem.NamiColors

@Composable
fun SettingsScreen(onTrashClick: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val autoOpenPlayer by viewModel.autoOpenPlayer.collectAsState()

    Column(modifier = Modifier.fillMaxSize().background(NamiColors.Ink900)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clickable { viewModel.setAutoOpenPlayer(!autoOpenPlayer) }
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Открывать плеер при выборе трека",
                color = NamiColors.Paper100,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = autoOpenPlayer, onCheckedChange = viewModel::setAutoOpenPlayer)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clickable(onClick = onTrashClick)
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "Корзина", color = NamiColors.Paper100)
        }
    }
}
```
Add `androidx.compose.foundation.layout.weight` is a `RowScope` extension, no separate import needed (matches the pattern already used elsewhere in this codebase, e.g. `AlbumGridItem.kt`).

- [ ] **Step 3: Build and test**

Run: `export JAVA_HOME="/c/Users/Mozzarella6/jdk-temurin-21/jdk-21.0.5+11" && ./gradlew :app:assembleDebug testDebugUnitTest`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat: add auto-open-player toggle to Settings"
```

---

### Task 3: "External track change" signal in `NowPlayingViewModel`

**Files:**
- Modify: `feature/player/src/main/kotlin/dev/nami/feature/player/NowPlayingViewModel.kt`

**Interfaces:**
- Produces: `NowPlayingViewModel.externalTrackChangeSignal: StateFlow<Int>` -- a counter bumped by `playTrack`, `playFromLibrary`, and `playTracks` (never by `skipNext`/`skipPrevious`), consumed by Task 5's `MiniPlayer`.

- [ ] **Step 1: Add the signal and bump it from the three "start playback from a tap" entry points**

Read `feature/player/src/main/kotlin/dev/nami/feature/player/NowPlayingViewModel.kt` in full first. Add:
```kotlin
private val _externalTrackChangeSignal = MutableStateFlow(0)
/** Bumped by playTrack/playFromLibrary/playTracks -- the "a track was selected from a list tap"
 * entry points -- but never by skipNext/skipPrevious, so MiniPlayer can play its track-change
 * slide animation for taps without it colliding with the animation a manual swipe already
 * plays for itself. */
val externalTrackChangeSignal: StateFlow<Int> = _externalTrackChangeSignal.asStateFlow()
```
Add the imports `kotlinx.coroutines.flow.MutableStateFlow` and `kotlinx.coroutines.flow.asStateFlow`. Then add `_externalTrackChangeSignal.value++` as the last line inside each of `playTrack`'s, `playFromLibrary`'s, and `playTracks`'s `viewModelScope.launch { ... }` blocks (after the existing `playerRepository.play(...)` call in each):
```kotlin
fun playTrack(trackId: TrackId) {
    viewModelScope.launch {
        val track = libraryRepository.track(trackId).first() ?: return@launch
        playerRepository.play(listOf(track.toPlayableTrack(artistName = null)), startIndex = 0)
        _externalTrackChangeSignal.value++
    }
}

fun playFromLibrary(trackId: TrackId) {
    viewModelScope.launch {
        val tracks = libraryRepository.allTracksOrdered()
        val startIndex = tracks.indexOfFirst { it.id == trackId }
        if (startIndex < 0) return@launch
        playerRepository.play(tracks.map { it.toPlayableTrack(artistName = null) }, startIndex = startIndex)
        _externalTrackChangeSignal.value++
    }
}

fun playTracks(tracks: List<Track>, artistName: String?, startIndex: Int) {
    viewModelScope.launch {
        playerRepository.play(tracks.map { it.toPlayableTrack(artistName) }, startIndex = startIndex)
        _externalTrackChangeSignal.value++
    }
}
```

- [ ] **Step 2: Build**

Run: `export JAVA_HOME="/c/Users/Mozzarella6/jdk-temurin-21/jdk-21.0.5+11" && ./gradlew :feature:player:compileDebugKotlin`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "feat: add externalTrackChangeSignal to NowPlayingViewModel for taps that don't open Now Playing"
```

---

### Task 4: MiniPlayer appear animation and automatic track-change slide

**Files:**
- Modify: `feature/player/src/main/kotlin/dev/nami/feature/player/MiniPlayer.kt`
- Modify: `app/src/main/kotlin/dev/nami/app/navigation/NamiNavHost.kt`

**Interfaces:**
- Consumes: `NowPlayingViewModel.externalTrackChangeSignal` (Task 3), `NowPlayingViewModel.queue` (existing).

- [ ] **Step 1: Wrap MiniPlayer's `NamiNavHost` call site in an appear animation**

Read `app/src/main/kotlin/dev/nami/app/navigation/NamiNavHost.kt` in full first. It already does `val nowPlayingViewModel: NowPlayingViewModel = hiltViewModel()` at the top -- add `val queue by nowPlayingViewModel.queue.collectAsState()` alongside it (needs the import `androidx.compose.runtime.collectAsState`, likely not yet imported in this file -- check and add if missing). Replace:
```kotlin
// Always mounted, even while Now Playing is open/closing -- it's what Now Playing's
// own slide-down is supposed to progressively uncover. Hiding it made it pop in
// abruptly the moment Now Playing finished closing instead of already being there.
MiniPlayer(onExpand = { showNowPlaying = true }, viewModel = nowPlayingViewModel)
```
with:
```kotlin
// Always mounted, even while Now Playing is open/closing -- it's what Now Playing's
// own slide-down is supposed to progressively uncover. Hiding it made it pop in
// abruptly the moment Now Playing finished closing instead of already being there.
// The AnimatedVisibility here only handles the very first appearance (nothing was
// playing, now something is) -- exit is instant because MiniPlayer's own swipe-down
// dismiss already animates its height to 0 before queue.nowPlaying goes null (see
// MiniPlayer.kt), so by the time this flips invisible there's nothing left to see.
AnimatedVisibility(
    visible = queue.nowPlaying != null,
    enter = slideInVertically(initialOffsetY = { it }),
    exit = ExitTransition.None,
) {
    MiniPlayer(onExpand = { showNowPlaying = true }, viewModel = nowPlayingViewModel)
}
```
`AnimatedVisibility`, `slideInVertically`, and `ExitTransition` are already imported in this file (used for the Now Playing overlay lower down).

- [ ] **Step 2: Play the artwork-slide transition automatically on `externalTrackChangeSignal`, without colliding with the manual swipe**

Read `feature/player/src/main/kotlin/dev/nami/feature/player/MiniPlayer.kt` in full first (its current gesture code stays untouched -- this only adds a new effect). Add, right after the existing `LaunchedEffect(queue.nowPlaying?.id) { dragOffsetY = 0f; artworkOffsetX = 0f }` block:
```kotlin
val externalTrackChangeSignal by viewModel.externalTrackChangeSignal.collectAsState()
// Skip the very first collected value (whatever it happens to be at this composition's
// mount) so the animation only plays for a signal bump that happens WHILE this MiniPlayer
// instance is already alive and showing a track -- covers "already playing, user tapped a
// different track elsewhere" without also firing (redundantly, since the appear animation
// above already covers it) the moment MiniPlayer is first composed for a brand new track.
var hasSeenFirstSignal by remember { mutableStateOf(false) }
val scope = rememberCoroutineScope()
LaunchedEffect(externalTrackChangeSignal) {
    if (hasSeenFirstSignal) {
        val exitDistance = blockWidthPx.toFloat() + skipThresholdPx
        animate(artworkOffsetX, -exitDistance) { value, _ -> artworkOffsetX = value }
        artworkOffsetX = exitDistance
        animate(artworkOffsetX, 0f) { value, _ -> artworkOffsetX = value }
    }
    hasSeenFirstSignal = true
}
```
Add the import `androidx.compose.runtime.rememberCoroutineScope` if not already present (check first -- `scope` here isn't actually needed since `LaunchedEffect` already runs in a coroutine; remove the unused `val scope = rememberCoroutineScope()` line, it was not needed). The corrected block (without the stray `scope` line):
```kotlin
val externalTrackChangeSignal by viewModel.externalTrackChangeSignal.collectAsState()
var hasSeenFirstSignal by remember { mutableStateOf(false) }
LaunchedEffect(externalTrackChangeSignal) {
    if (hasSeenFirstSignal) {
        val exitDistance = blockWidthPx.toFloat() + skipThresholdPx
        animate(artworkOffsetX, -exitDistance) { value, _ -> artworkOffsetX = value }
        artworkOffsetX = exitDistance
        animate(artworkOffsetX, 0f) { value, _ -> artworkOffsetX = value }
    }
    hasSeenFirstSignal = true
}
```
This reuses `blockWidthPx` and `skipThresholdPx`, both already defined earlier in this same composable for the manual swipe gesture -- no new state needed for those two.

- [ ] **Step 3: Verify no collision with the manual swipe gesture**

The manual swipe's `onDragStopped` handler calls `viewModel.skipNext()`/`skipPrevious()`, NOT `playTrack`/`playFromLibrary`/`playTracks` -- so `externalTrackChangeSignal` never bumps from a manual swipe, and this new `LaunchedEffect` never fires for it. No code change needed for this step; it's a verification-only step -- read `NowPlayingViewModel.skipNext`/`skipPrevious` once more to confirm neither touches `_externalTrackChangeSignal` (they don't, per Task 3's diff).

- [ ] **Step 4: Build**

Run: `export JAVA_HOME="/c/Users/Mozzarella6/jdk-temurin-21/jdk-21.0.5+11" && ./gradlew :app:assembleDebug`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: animate MiniPlayer's first appearance and auto-play its track-slide transition when a track starts from elsewhere"
```

---

### Task 5: Gate the five track-tap call sites on the setting

**Files:**
- Modify: `app/src/main/kotlin/dev/nami/app/navigation/NamiNavHost.kt`

**Interfaces:**
- Consumes: `SettingsViewModel.autoOpenPlayer` (Task 2).

- [ ] **Step 1: Read the setting once, at the top of `NamiNavHost`**

Add `val settingsViewModel: SettingsViewModel = hiltViewModel()` and `val autoOpenPlayer by settingsViewModel.autoOpenPlayer.collectAsState()` near the existing `val nowPlayingViewModel: NowPlayingViewModel = hiltViewModel()` line. Add the import `dev.nami.app.SettingsViewModel`.

- [ ] **Step 2: Gate each of the five `showNowPlaying = true` assignments that follow a `play...` call**

There are five: `LibraryScreen`'s `onTrackClick`, `SearchScreen`'s `onTrackClick`, `AlbumDetailScreen`'s `onPlayTracks`, `ArtistDetailScreen`'s `onPlayTracks`, `PlaylistDetailScreen`'s `onPlayTracks`. In each, change:
```kotlin
onTrackClick = { trackId ->
    nowPlayingViewModel.playFromLibrary(trackId)
    showNowPlaying = true
},
```
to:
```kotlin
onTrackClick = { trackId ->
    nowPlayingViewModel.playFromLibrary(trackId)
    if (autoOpenPlayer) showNowPlaying = true
},
```
and the same one-line change (`showNowPlaying = true` -> `if (autoOpenPlayer) showNowPlaying = true`) in the other four call sites (`SearchScreen`'s `onTrackClick` using `playTrack`, and the three `onPlayTracks` lambdas using `playTracks`). Do not touch anything else in those lambdas -- the `play...` call itself is unchanged, only the line that opens Now Playing becomes conditional.

- [ ] **Step 3: Build and test**

Run: `export JAVA_HOME="/c/Users/Mozzarella6/jdk-temurin-21/jdk-21.0.5+11" && ./gradlew :app:assembleDebug testDebugUnitTest`
Expected: PASS.

- [ ] **Step 4: Install and manually verify both settings states**

Run: `adb install -r C:/Nami/app/build/outputs/apk/debug/app-debug.apk`
Check with the setting OFF (default): tapping a track when nothing was playing makes MiniPlayer slide up, Now Playing does NOT open; tapping a different track while one is already playing keeps you on the current screen and plays MiniPlayer's slide transition (not an actual skip -- the queue reflects the newly tapped track/list, not next/previous of the old one). Check with the setting ON (toggle it in Settings): tapping a track opens Now Playing immediately, exactly as before this plan.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: gate auto-opening Now Playing on track tap behind the new setting"
```
