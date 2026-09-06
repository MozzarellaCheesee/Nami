# Metadata Editing, Album CRUD, Waveform Scrubber Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the user rename and re-cover tracks/albums/artists, manage which tracks belong to an album (add/remove/mark as single/delete), bulk-manage albums from the Albums tab, and scrub playback position on a real (procedurally rendered) waveform in Now Playing.

**Architecture:** Extend the existing Room entities additively (one migration, 7→8, adding `AlbumEntity.isSingle`). Reuse the already-shipped "force overwrite" DAO pattern (`PlaylistDao.setCoverPath`, an unconditional `UPDATE`) for renames/re-covers, alongside the existing "only if null" writer used during import — both queries coexist, callers pick the one that matches intent. Reuse the existing `PlaylistActionsViewModel` pattern (SavedStateHandle-backed pending-target fields surviving process death across a SAF picker) for the two new pickers (album cover, artist photo). The waveform is procedurally generated per track (deterministic pseudo-random bar heights seeded from the track id) — real amplitude analysis would need decoding every supported audio format up front and is out of scope; only the playback-position scrubbing is real.

**Tech Stack:** Kotlin, Jetpack Compose (Material3), Room (migration 7→8), Coil3, existing `ArtworkStore`/SAF picker pattern from playlists.

**Spec:** `C:\Users\Mozzarella6\Documents\main\Nami\Дизайн.md` §3 "Волна-скраббер" (waveform look: 350×48, 120 bars width 2 gap 1, played portion `--shu`, remainder `--ink-500`, head is a 2×48 `--paper-100` line), §3 "Карточка альбома"/track row for consistent overflow-menu conventions.

## Global Constraints

- Colors: use `NamiColors` tokens only (Shu, Ink500, Ink800, Ink900, Paper100, Paper70, Paper40) — no new hex literals in feature code.
- Room migrations are additive only, never `fallbackToDestructiveMigration` (established project rule). Current schema version is 7 (`core/database/src/main/kotlin/dev/nami/core/database/NamiDatabase.kt`, last bumped by `MIGRATION_6_7`) — this plan adds `MIGRATION_7_8`.
- Follow the existing "force overwrite via unconditional UPDATE" pattern (`PlaylistDao.setCoverPath`) for anything the user explicitly edits; keep the existing "only if NULL" writers (`AlbumDao.setArtworkPath`, `ArtistDao.setPhotoPath`, `TrackDao.setArtworkPath`) untouched — they're still used by import and must not start overwriting user edits on a later re-import.
- Deleting an album deletes its tracks via the **existing** `LibraryRepository.deleteTracks` (soft-delete to trash, restorable) — never a new hard-delete path. An album with no non-deleted tracks left already disappears from every album query on its own (they all filter by `EXISTS (... tracks ... deletedAt IS NULL)`), so there is no separate "delete the album row" step.
- No new Gradle dependencies.

---

### Task 1: Schema v8 — `isSingle` column, force-overwrite/update DAO queries, repository methods

**Files:**
- Modify: `core/database/src/main/kotlin/dev/nami/core/database/entity/AlbumEntity.kt`
- Modify: `core/database/src/main/kotlin/dev/nami/core/database/NamiDatabase.kt`
- Modify: `core/database/src/main/kotlin/dev/nami/core/database/migration/Migrations.kt`
- Modify: `core/database/src/main/kotlin/dev/nami/core/database/di/DatabaseModule.kt`
- Modify: `core/database/src/main/kotlin/dev/nami/core/database/dao/TrackDao.kt`
- Modify: `core/database/src/main/kotlin/dev/nami/core/database/dao/AlbumDao.kt`
- Modify: `core/database/src/main/kotlin/dev/nami/core/database/dao/ArtistDao.kt`
- Modify: `core/model/src/main/kotlin/dev/nami/core/model/Album.kt`
- Modify: `data/src/main/kotlin/dev/nami/data/mapper/AlbumMapper.kt`
- Modify: `domain/src/main/kotlin/dev/nami/domain/LibraryRepository.kt`
- Modify: `data/src/main/kotlin/dev/nami/data/LibraryRepositoryImpl.kt`
- Test: `core/database/src/test/kotlin/dev/nami/core/database/Migration7To8Test.kt` (new, follow `Migration6To7Test.kt`'s exact JDBC pattern)

**Interfaces:**
- Consumes: `ArtworkStore.save(key: String, bytes: ByteArray): String?` (existing, unconditionally overwrites the file at a stable path per key — reused here for re-covers).
- Produces: `LibraryRepository.renameTrack`, `renameAlbum`, `renameArtist`, `setAlbumCover`, `setArtistPhoto`, `setAlbumIsSingle`, `addTrackToAlbum`, `removeTrackFromAlbum` (exact signatures below) — consumed by Tasks 2-5.

- [ ] **Step 1: Add `isSingle` to `AlbumEntity`, bump schema to 8**

In `core/database/src/main/kotlin/dev/nami/core/database/entity/AlbumEntity.kt`:
```kotlin
data class AlbumEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artistId: String?,
    val year: Int?,
    val artworkPath: String?,
    val isSingle: Boolean = false,
)
```

In `core/database/src/main/kotlin/dev/nami/core/database/NamiDatabase.kt`, change `version = 7` to `version = 8`.

- [ ] **Step 2: Write the migration**

In `core/database/src/main/kotlin/dev/nami/core/database/migration/Migrations.kt`, append:
```kotlin
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE albums ADD COLUMN isSingle INTEGER NOT NULL DEFAULT 0")
    }
}
```

- [ ] **Step 3: Register the migration**

In `core/database/src/main/kotlin/dev/nami/core/database/di/DatabaseModule.kt`, add the import `dev.nami.core.database.migration.MIGRATION_7_8` and append it to `.addMigrations(...)`:
```kotlin
.addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
```

- [ ] **Step 4: Write the migration test**

Follow `core/database/src/test/kotlin/dev/nami/core/database/Migration6To7Test.kt`'s exact JDBC-based pattern (not Room's `MigrationTestHelper` — this codebase doesn't use that). Create `Migration7To8Test.kt`:
```kotlin
package dev.nami.core.database

import java.sql.DriverManager
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Verifies MIGRATION_7_8 (see Migrations.kt) adds isSingle defaulting to false without
 * touching existing rows.
 */
class Migration7To8Test {
    private lateinit var conn: java.sql.Connection

    @Before
    fun setUp() {
        Class.forName("org.sqlite.JDBC")
        conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        val stmt = conn.createStatement()
        stmt.execute(
            """
            CREATE TABLE albums (
                id TEXT NOT NULL, title TEXT NOT NULL, artistId TEXT, year INTEGER, artworkPath TEXT,
                PRIMARY KEY(id)
            )
            """,
        )
        stmt.execute("INSERT INTO albums(id, title, artistId, year, artworkPath) VALUES ('al1', 'Wishes Hidden', NULL, 2010, NULL)")
    }

    @After
    fun tearDown() = conn.close()

    @Test
    fun `migration adds isSingle defaulting to false and preserves existing rows`() {
        val stmt = conn.createStatement()
        stmt.execute("ALTER TABLE albums ADD COLUMN isSingle INTEGER NOT NULL DEFAULT 0")

        val album = conn.createStatement().executeQuery("SELECT title, isSingle FROM albums WHERE id = 'al1'")
        assertEquals(true, album.next())
        assertEquals("Wishes Hidden", album.getString(1))
        assertFalse(album.getBoolean(2))
    }
}
```

- [ ] **Step 5: Run the migration test**

Run: `export JAVA_HOME="/c/Users/Mozzarella6/jdk-temurin-21/jdk-21.0.5+11" && ./gradlew :core:database:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 6: Add rename/re-cover/isSingle/album-membership DAO queries**

In `core/database/src/main/kotlin/dev/nami/core/database/dao/TrackDao.kt`, add:
```kotlin
@Query("UPDATE tracks SET title = :title WHERE id = :id")
suspend fun updateTitle(id: String, title: String)

// Unconditional -- unlike setArtworkPath (import's "only if null" writer), this is for the
// user explicitly replacing a track's own (albumless) cover.
@Query("UPDATE tracks SET artworkPath = :path WHERE id = :id")
suspend fun updateArtworkPath(id: String, path: String)

// null detaches the track from any album (used by "remove from album").
@Query("UPDATE tracks SET albumId = :albumId WHERE id = :id")
suspend fun setAlbumId(id: String, albumId: String?)
```

In `core/database/src/main/kotlin/dev/nami/core/database/dao/AlbumDao.kt`, add:
```kotlin
@Query("UPDATE albums SET title = :title WHERE id = :id")
suspend fun updateTitle(id: String, title: String)

// Unconditional -- unlike setArtworkPath (import's "only if null" writer), this is for the
// user explicitly replacing an album's cover.
@Query("UPDATE albums SET artworkPath = :path WHERE id = :id")
suspend fun updateArtworkPath(id: String, path: String)

@Query("UPDATE albums SET isSingle = :isSingle WHERE id = :id")
suspend fun setIsSingle(id: String, isSingle: Boolean)
```

In `core/database/src/main/kotlin/dev/nami/core/database/dao/ArtistDao.kt`, add:
```kotlin
@Query("UPDATE artists SET name = :name, sortName = :name WHERE id = :id")
suspend fun updateName(id: String, name: String)

// Unconditional -- unlike setPhotoPath (import's "only if null" writer), this is for the
// user explicitly replacing an artist's photo.
@Query("UPDATE artists SET photoPath = :path WHERE id = :id")
suspend fun updatePhotoPath(id: String, path: String)
```

- [ ] **Step 7: Add `isSingle` to the domain `Album` model and its mapper**

In `core/model/src/main/kotlin/dev/nami/core/model/Album.kt`:
```kotlin
data class Album(
    val id: AlbumId,
    val title: String,
    val artistId: ArtistId?,
    val year: Int?,
    val artworkPath: String?,
    val isSingle: Boolean = false,
)
```

In `data/src/main/kotlin/dev/nami/data/mapper/AlbumMapper.kt`, add `isSingle = isSingle` to `AlbumEntity.toDomain()`:
```kotlin
fun AlbumEntity.toDomain(): Album = Album(
    id = AlbumId(id),
    title = title,
    artistId = artistId?.let(::ArtistId),
    year = year,
    artworkPath = artworkPath,
    isSingle = isSingle,
)
```

- [ ] **Step 8: Add the new repository methods to the interface**

In `domain/src/main/kotlin/dev/nami/domain/LibraryRepository.kt`, add after `fun tracksInAlbum(id: AlbumId): Flow<List<Track>>`:
```kotlin
suspend fun renameTrack(id: TrackId, title: String)
suspend fun setTrackCover(id: TrackId, imageUri: String)
suspend fun renameAlbum(id: AlbumId, title: String)
suspend fun setAlbumCover(id: AlbumId, imageUri: String)
suspend fun setAlbumIsSingle(id: AlbumId, isSingle: Boolean)
suspend fun addTrackToAlbum(trackId: TrackId, albumId: AlbumId)
suspend fun removeTrackFromAlbum(trackId: TrackId)
suspend fun renameArtist(id: ArtistId, name: String)
suspend fun setArtistPhoto(id: ArtistId, imageUri: String)
```

- [ ] **Step 9: Implement them**

In `data/src/main/kotlin/dev/nami/data/LibraryRepositoryImpl.kt`, add (near the other track/album mutation methods; `context.contentResolver` and `artworkStore` are already constructor-injected fields on this class per the existing `copyAndIndex`/artwork-saving code):
```kotlin
override suspend fun renameTrack(id: TrackId, title: String) {
    trackDao.updateTitle(id.value, title)
}

override suspend fun setTrackCover(id: TrackId, imageUri: String) {
    val bytes = context.contentResolver.openInputStream(imageUri.toUri())?.use { it.readBytes() } ?: return
    artworkStore.save(id.value, bytes)?.let { path -> trackDao.updateArtworkPath(id.value, path) }
}

override suspend fun renameAlbum(id: AlbumId, title: String) {
    albumDao.updateTitle(id.value, title)
}

override suspend fun setAlbumCover(id: AlbumId, imageUri: String) {
    val bytes = context.contentResolver.openInputStream(imageUri.toUri())?.use { it.readBytes() } ?: return
    artworkStore.save(id.value, bytes)?.let { path -> albumDao.updateArtworkPath(id.value, path) }
}

override suspend fun setAlbumIsSingle(id: AlbumId, isSingle: Boolean) {
    albumDao.setIsSingle(id.value, isSingle)
}

override suspend fun addTrackToAlbum(trackId: TrackId, albumId: AlbumId) {
    trackDao.setAlbumId(trackId.value, albumId.value)
}

override suspend fun removeTrackFromAlbum(trackId: TrackId) {
    trackDao.setAlbumId(trackId.value, null)
}

override suspend fun renameArtist(id: ArtistId, name: String) {
    artistDao.updateName(id.value, name)
}

override suspend fun setArtistPhoto(id: ArtistId, imageUri: String) {
    val bytes = context.contentResolver.openInputStream(imageUri.toUri())?.use { it.readBytes() } ?: return
    artworkStore.save(id.value, bytes)?.let { path -> artistDao.updatePhotoPath(id.value, path) }
}
```
`imageUri.toUri()` needs `import androidx.core.net.toUri` — already imported in this file for the existing `importFiles`/`importFolder` code, no new import needed.

- [ ] **Step 10: Fix test fakes**

Run `Grep` for `object : LibraryRepository`, `object : TrackDao`, `object : AlbumDao`, `object : ArtistDao` across the repo (known sites from earlier plans: `LibraryViewModelTest.kt`, `AlbumDetailViewModelTest.kt`, `NowPlayingViewModelTest.kt` for `LibraryRepository`; `SearchRepositoryImplTest.kt` and `MetadataResolverTest.kt` for the DAOs). Add the new methods to each fake:
- `LibraryRepository` fakes: `override suspend fun renameTrack(id: TrackId, title: String) = error("unused")` (and the same `error("unused")` pattern for the other 7 new methods) unless the test specifically exercises one, in which case implement it to record the call the same way existing fakes record `removedTracks`/`restoredTrack` etc.
- `TrackDao` fakes: `override suspend fun updateTitle(id: String, title: String) = error("unused")`, `override suspend fun updateArtworkPath(id: String, path: String) = error("unused")`, `override suspend fun setAlbumId(id: String, albumId: String?) = error("unused")`.
- `AlbumDao` fakes: `override suspend fun updateTitle(id: String, title: String) = error("unused")`, `override suspend fun updateArtworkPath(id: String, path: String) = error("unused")`, `override suspend fun setIsSingle(id: String, isSingle: Boolean) = error("unused")`.
- `ArtistDao` fakes: `override suspend fun updateName(id: String, name: String) = error("unused")`, `override suspend fun updatePhotoPath(id: String, path: String) = error("unused")`.

- [ ] **Step 11: Build and test**

Run: `export JAVA_HOME="/c/Users/Mozzarella6/jdk-temurin-21/jdk-21.0.5+11" && ./gradlew :app:assembleDebug test testDebugUnitTest`
Expected: PASS.

- [ ] **Step 12: Commit**

```bash
git add -A
git commit -m "feat: add isSingle column, rename/re-cover/album-membership repository methods (schema v8)"
```

---

### Task 2: Rename dialogs for tracks, albums, artists

**Files:**
- Create: `feature/library/src/main/kotlin/dev/nami/feature/library/RenameDialog.kt`
- Modify: `feature/library/src/main/kotlin/dev/nami/feature/library/TrackListItem.kt`
- Modify: `feature/library/src/main/kotlin/dev/nami/feature/library/LibraryScreen.kt`
- Modify: `feature/library/src/main/kotlin/dev/nami/feature/library/AlbumDetailScreen.kt`
- Modify: `feature/library/src/main/kotlin/dev/nami/feature/library/AlbumDetailViewModel.kt`
- Modify: `feature/library/src/main/kotlin/dev/nami/feature/library/ArtistDetailScreen.kt`
- Modify: `feature/library/src/main/kotlin/dev/nami/feature/library/ArtistDetailViewModel.kt`

**Interfaces:**
- Consumes: `LibraryRepository.renameTrack/renameAlbum/renameArtist` (Task 1).
- Produces: `RenameDialog(currentName: String, title: String, onRename: (String) -> Unit, onDismiss: () -> Unit)` — a shared composable, consumed by all three screens.

- [ ] **Step 1: Write the shared `RenameDialog`**

```kotlin
package dev.nami.feature.library

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

@Composable
fun RenameDialog(
    currentName: String,
    title: String,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = {
                val trimmed = name.trim()
                if (trimmed.isNotEmpty()) {
                    onRename(trimmed)
                    onDismiss()
                }
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
```

- [ ] **Step 2: Add a rename action to `TrackListItem`'s overflow menu**

Read `feature/library/src/main/kotlin/dev/nami/feature/library/TrackListItem.kt` in full first (it has a `DropdownMenu` with `onAddToPlaylist`/`onAddToQueue`/`onDelete` optional callbacks). Add a new optional `onRename: (() -> Unit)? = null` parameter to `TrackListItem`, and a `DropdownMenuItem` for it inside the existing menu, following the exact pattern of the other items:
```kotlin
onRename?.let { action ->
    DropdownMenuItem(
        text = { Text("Переименовать") },
        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
        onClick = { showMenu = false; action() },
    )
}
```
Add the parameter to the function signature (alongside the other optional callbacks) and the import `androidx.compose.material.icons.filled.Edit`.

- [ ] **Step 3: Wire track rename into `LibraryScreen`'s Tracks tab**

In `feature/library/src/main/kotlin/dev/nami/feature/library/LibraryScreen.kt`, add local state `var renameTrackId by remember { mutableStateOf<TrackId?>(null) }` near the existing `addToPlaylistTrackId` state in `LibraryScreen`. Pass `onRename = { renameTrackId = track.id }` to each `TrackListItem(...)` call inside `TrackListContent`'s `items(...)` block (thread `onRenameTrack: (TrackId) -> Unit` down as a new `TrackListContent` parameter the same way `onDelete`/`onAddToPlaylist` already are, then wire it at the `LibraryScreen` call site to `{ trackId -> renameTrackId = trackId }`). After the existing `addToPlaylistTrackId?.let { ... }` block at the bottom of `LibraryScreen`, add:
```kotlin
renameTrackId?.let { trackId ->
    RenameDialog(
        currentName = "",
        title = "Переименовать трек",
        onRename = { newTitle -> viewModel.renameTrack(trackId, newTitle) },
        onDismiss = { renameTrackId = null },
    )
}
```
This needs the CURRENT title as `currentName`, not an empty string — since `TrackListContent` only has the track's id at the point the dialog is triggered from `LibraryScreen`'s state (not the `Track` object itself), change `renameTrackId` to hold the whole `Track` instead of just `TrackId`: `var renameTrack by remember { mutableStateOf<Track?>(null) }`, set it from `TrackListContent`'s `onRename = { renameTrack = track }` (pass the callback down as `onRenameTrack: (Track) -> Unit`), and use `renameTrack?.let { track -> RenameDialog(currentName = track.title, ..., onRename = { newTitle -> viewModel.renameTrack(track.id, newTitle) }, onDismiss = { renameTrack = null }) }`.

Add `renameTrack` to `LibraryViewModel`:
```kotlin
fun renameTrack(id: TrackId, title: String) {
    viewModelScope.launch { libraryRepository.renameTrack(id, title) }
}
```

- [ ] **Step 4: Wire album rename into `AlbumDetailScreen`**

In `feature/library/src/main/kotlin/dev/nami/feature/library/AlbumDetailScreen.kt`, add `var showRenameDialog by remember { mutableStateOf(false) }`, add a `DropdownMenuItem` to the existing album `DropdownMenu` (the one already showing "Добавить в очередь"):
```kotlin
DropdownMenuItem(
    text = { Text("Переименовать") },
    onClick = { showAlbumMenu = false; showRenameDialog = true },
)
```
After the existing `addToPlaylistTrackId?.let { ... }` block at the bottom, add:
```kotlin
if (showRenameDialog) {
    RenameDialog(
        currentName = uiState.album?.title ?: "",
        title = "Переименовать альбом",
        onRename = { newTitle -> viewModel.renameAlbum(newTitle) },
        onDismiss = { showRenameDialog = false },
    )
}
```

In `feature/library/src/main/kotlin/dev/nami/feature/library/AlbumDetailViewModel.kt`, add (constructor needs `libraryRepository` kept as a field, not just a local `val` used only in `init` — change `libraryRepository: LibraryRepository` from a constructor parameter used inline to a `private val` so it's reachable from new methods):
```kotlin
@HiltViewModel
class AlbumDetailViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val albumId = AlbumId(checkNotNull(savedStateHandle.get<String>("albumId")))

    private val _uiState = MutableStateFlow(AlbumDetailUiState())
    val uiState: StateFlow<AlbumDetailUiState> = _uiState.asStateFlow()

    init {
        libraryRepository.album(albumId)
            .combine(libraryRepository.tracksInAlbum(albumId)) { album, tracks ->
                AlbumDetailUiState(album = album, tracks = tracks)
            }
            .onEach { _uiState.value = it }
            .launchIn(viewModelScope)
    }

    fun renameAlbum(title: String) {
        viewModelScope.launch { libraryRepository.renameAlbum(albumId, title) }
    }
}
```
Add the imports `kotlinx.coroutines.launch` and `dev.nami.core.model.AlbumId` (the latter is likely already imported).

- [ ] **Step 5: Wire artist rename into `ArtistDetailScreen`**

In `feature/library/src/main/kotlin/dev/nami/feature/library/ArtistDetailScreen.kt`, there is currently no overflow menu at all for the artist header — add one next to the play button, matching `AlbumDetailScreen`'s pattern:
```kotlin
Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
    if (uiState.tracks.isNotEmpty()) {
        IconButton(
            onClick = { onPlayTracks(uiState.tracks, artistName, 0) },
            modifier = Modifier
                .size(64.dp)
                .background(NamiColors.Paper100, androidx.compose.foundation.shape.RoundedCornerShape(20.dp)),
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = "Играть всё", tint = NamiColors.Ink900)
        }
    }
    Spacer(modifier = Modifier.padding(start = 12.dp))
    androidx.compose.foundation.layout.Box {
        IconButton(onClick = { showArtistMenu = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Действия с артистом", tint = NamiColors.Paper100)
        }
        androidx.compose.material3.DropdownMenu(expanded = showArtistMenu, onDismissRequest = { showArtistMenu = false }) {
            androidx.compose.material3.DropdownMenuItem(
                text = { Text("Переименовать") },
                onClick = { showArtistMenu = false; showRenameDialog = true },
            )
        }
    }
}
```
This replaces the existing `if (uiState.tracks.isNotEmpty()) { IconButton(...play...) }` block (which currently has no wrapping `Row`+menu). Add `var showArtistMenu by remember { mutableStateOf(false) }` and `var showRenameDialog by remember { mutableStateOf(false) }` near the top of `ArtistDetailScreen`, and add the import `androidx.compose.material.icons.filled.MoreVert`. After the existing `addToPlaylistTrackId?.let { ... }` block, add:
```kotlin
if (showRenameDialog) {
    RenameDialog(
        currentName = artistName ?: "",
        title = "Переименовать артиста",
        onRename = { newName -> viewModel.renameArtist(newName) },
        onDismiss = { showRenameDialog = false },
    )
}
```

In `feature/library/src/main/kotlin/dev/nami/feature/library/ArtistDetailViewModel.kt`, change `libraryRepository` to a `private val` constructor field (same reasoning as Step 4) and add:
```kotlin
fun renameArtist(name: String) {
    viewModelScope.launch { libraryRepository.renameArtist(artistId, name) }
}
```
Add the import `kotlinx.coroutines.launch`.

- [ ] **Step 6: Build and test**

Run: `export JAVA_HOME="/c/Users/Mozzarella6/jdk-temurin-21/jdk-21.0.5+11" && ./gradlew :app:assembleDebug testDebugUnitTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: add rename dialogs for tracks, albums, and artists"
```

---

### Task 3: Cover-image editing for albums and artists

**Files:**
- Create: `app/src/main/kotlin/dev/nami/app/MetadataActionsViewModel.kt`
- Modify: `app/src/main/kotlin/dev/nami/app/MainActivity.kt`
- Modify: `app/src/main/kotlin/dev/nami/app/navigation/NamiNavHost.kt`
- Modify: `feature/library/src/main/kotlin/dev/nami/feature/library/AlbumDetailScreen.kt`
- Modify: `feature/library/src/main/kotlin/dev/nami/feature/library/ArtistDetailScreen.kt`

**Interfaces:**
- Consumes: `LibraryRepository.setAlbumCover/setArtistPhoto` (Task 1).
- Produces: `MetadataActionsViewModel.requestAlbumCoverPick(AlbumId)`, `.requestArtistPhotoPick(ArtistId)`, `.onAlbumCoverPicked(Uri)`, `.onArtistPhotoPicked(Uri)` — wired at the `MainActivity`/`NamiNavHost` boundary, consumed by `AlbumDetailScreen`/`ArtistDetailScreen`.

- [ ] **Step 1: Write `MetadataActionsViewModel`**

Mirror `app/src/main/kotlin/dev/nami/app/PlaylistActionsViewModel.kt`'s exact SavedStateHandle-backed pending-target pattern (the comment there explains why: SAF pickers run in a separate Activity the host process may be killed while waiting on):
```kotlin
package dev.nami.app

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nami.core.model.AlbumId
import dev.nami.core.model.ArtistId
import dev.nami.domain.LibraryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MetadataActionsViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private var pendingAlbumCoverTarget: AlbumId?
        get() = savedStateHandle.get<String>("pendingAlbumCoverTarget")?.let(::AlbumId)
        set(value) { savedStateHandle["pendingAlbumCoverTarget"] = value?.value }

    private var pendingArtistPhotoTarget: ArtistId?
        get() = savedStateHandle.get<String>("pendingArtistPhotoTarget")?.let(::ArtistId)
        set(value) { savedStateHandle["pendingArtistPhotoTarget"] = value?.value }

    fun requestAlbumCoverPick(id: AlbumId) {
        pendingAlbumCoverTarget = id
    }

    fun onAlbumCoverPicked(uri: Uri) {
        val target = pendingAlbumCoverTarget?.also { pendingAlbumCoverTarget = null } ?: return
        viewModelScope.launch {
            try {
                libraryRepository.setAlbumCover(target, uri.toString())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // SAF I/O can fail (revoked URI grant, IOException): swallow so viewModelScope survives.
            }
        }
    }

    fun requestArtistPhotoPick(id: ArtistId) {
        pendingArtistPhotoTarget = id
    }

    fun onArtistPhotoPicked(uri: Uri) {
        val target = pendingArtistPhotoTarget?.also { pendingArtistPhotoTarget = null } ?: return
        viewModelScope.launch {
            try {
                libraryRepository.setArtistPhoto(target, uri.toString())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // SAF I/O can fail (revoked URI grant, IOException): swallow so viewModelScope survives.
            }
        }
    }
}
```

- [ ] **Step 2: Register two SAF pickers in `MainActivity` and thread callbacks through**

In `app/src/main/kotlin/dev/nami/app/MainActivity.kt`, add:
```kotlin
private val metadataActionsViewModel: MetadataActionsViewModel by viewModels()

private val pickAlbumCoverImage = registerForActivityResult(
    ActivityResultContracts.OpenDocument(),
) { uri -> uri?.let(metadataActionsViewModel::onAlbumCoverPicked) }

private val pickArtistPhotoImage = registerForActivityResult(
    ActivityResultContracts.OpenDocument(),
) { uri -> uri?.let(metadataActionsViewModel::onArtistPhotoPicked) }
```
Add the import `dev.nami.app.MetadataActionsViewModel` (same package, likely no import needed since `MainActivity.kt` is already in `dev.nami.app`).

Pass two new callbacks into `NamiNavHost(...)`:
```kotlin
onPickAlbumCover = { albumId ->
    metadataActionsViewModel.requestAlbumCoverPick(albumId)
    pickAlbumCoverImage.launch(arrayOf("image/*"))
},
onPickArtistPhoto = { artistId ->
    metadataActionsViewModel.requestArtistPhotoPick(artistId)
    pickArtistPhotoImage.launch(arrayOf("image/*"))
},
```

- [ ] **Step 3: Thread the new callbacks through `NamiNavHost` to the two detail screens**

In `app/src/main/kotlin/dev/nami/app/navigation/NamiNavHost.kt`, add `onPickAlbumCover: (AlbumId) -> Unit` and `onPickArtistPhoto: (ArtistId) -> Unit` to `NamiNavHost`'s parameter list. Pass them into the existing `AlbumDetailScreen(...)`/`ArtistDetailScreen(...)` calls as new parameters `onPickCoverRequested = onPickAlbumCover` / `onPickPhotoRequested = onPickArtistPhoto` respectively.

- [ ] **Step 4: Add a "change cover" menu item to `AlbumDetailScreen`**

Add `onPickCoverRequested: (AlbumId) -> Unit` to `AlbumDetailScreen`'s parameter list. In the existing album `DropdownMenu`, add:
```kotlin
DropdownMenuItem(
    text = { Text("Изменить обложку") },
    onClick = {
        showAlbumMenu = false
        uiState.album?.let { onPickCoverRequested(it.id) }
    },
)
```

- [ ] **Step 5: Add a "change photo" menu item to `ArtistDetailScreen`**

Add `onPickPhotoRequested: (ArtistId) -> Unit` to `ArtistDetailScreen`'s parameter list. In the artist `DropdownMenu` added in Task 2 Step 5, add:
```kotlin
DropdownMenuItem(
    text = { Text("Изменить фото") },
    onClick = {
        showArtistMenu = false
        uiState.artist?.let { onPickPhotoRequested(it.id) }
    },
)
```

- [ ] **Step 6: Build and test**

Run: `export JAVA_HOME="/c/Users/Mozzarella6/jdk-temurin-21/jdk-21.0.5+11" && ./gradlew :app:assembleDebug testDebugUnitTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: add cover/photo editing for albums and artists via SAF image picker"
```

---

### Task 4: Album membership — add/remove tracks, mark as single

**Files:**
- Create: `feature/library/src/main/kotlin/dev/nami/feature/library/AddTracksToAlbumDialog.kt`
- Create: `feature/library/src/main/kotlin/dev/nami/feature/library/AddTracksToAlbumViewModel.kt`
- Modify: `feature/library/src/main/kotlin/dev/nami/feature/library/AlbumDetailScreen.kt`
- Modify: `feature/library/src/main/kotlin/dev/nami/feature/library/AlbumDetailViewModel.kt`
- Modify: `feature/library/src/main/kotlin/dev/nami/feature/library/TrackListItem.kt`

**Interfaces:**
- Consumes: `LibraryRepository.tracks()` (existing Paging flow, for the track picker), `addTrackToAlbum`/`removeTrackFromAlbum`/`setAlbumIsSingle` (Task 1).
- Produces: `AddTracksToAlbumDialog(albumId: AlbumId, onDismiss: () -> Unit)`.

- [ ] **Step 1: Add an optional "remove from album" action to `TrackListItem`**

In `feature/library/src/main/kotlin/dev/nami/feature/library/TrackListItem.kt`, add another optional parameter `onRemoveFromAlbum: (() -> Unit)? = null` (alongside `onRename` from Task 2) and its `DropdownMenuItem`:
```kotlin
onRemoveFromAlbum?.let { action ->
    DropdownMenuItem(
        text = { Text("Убрать из альбома") },
        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
        onClick = { showMenu = false; action() },
    )
}
```
(Reuses the already-imported `Icons.Filled.Delete` — this action detaches the track from the album, it does not trash it, so reusing the delete icon is a visual nod to "removes it from this list" without implying permanent deletion; the menu text already makes the distinction clear.)

- [ ] **Step 2: Wire "remove from album" into `AlbumDetailScreen`'s track list**

In `feature/library/src/main/kotlin/dev/nami/feature/library/AlbumDetailScreen.kt`, pass `onRemoveFromAlbum = { viewModel.removeTrackFromAlbum(track.id) }` to each `TrackListItem(...)` call in the album's track list.

In `feature/library/src/main/kotlin/dev/nami/feature/library/AlbumDetailViewModel.kt`, add:
```kotlin
fun removeTrackFromAlbum(trackId: TrackId) {
    viewModelScope.launch { libraryRepository.removeTrackFromAlbum(trackId) }
}
```
Add the import `dev.nami.core.model.TrackId` if not already present.

- [ ] **Step 3: Write `AddTracksToAlbumViewModel`**

```kotlin
package dev.nami.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nami.core.model.AlbumId
import dev.nami.core.model.Track
import dev.nami.core.model.TrackId
import dev.nami.domain.LibraryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AddTracksToAlbumViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
) : ViewModel() {

    val tracks: Flow<PagingData<Track>> =
        libraryRepository.tracks().cachedIn(viewModelScope)

    fun addTrack(trackId: TrackId, albumId: AlbumId) {
        viewModelScope.launch { libraryRepository.addTrackToAlbum(trackId, albumId) }
    }
}
```

- [ ] **Step 4: Write `AddTracksToAlbumDialog`**

Follow `AddToPlaylistDialog.kt`'s exact structure (a full-screen-ish `AlertDialog` with a `LazyColumn` of `LazyPagingItems`):
```kotlin
package dev.nami.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import dev.nami.core.designsystem.NamiColors
import dev.nami.core.model.AlbumId

@Composable
fun AddTracksToAlbumDialog(
    albumId: AlbumId,
    onDismiss: () -> Unit,
    viewModel: AddTracksToAlbumViewModel = hiltViewModel(),
) {
    val tracks = viewModel.tracks.collectAsLazyPagingItems()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Добавить треки в альбом") },
        text = {
            LazyColumn(modifier = Modifier.height(400.dp)) {
                items(count = tracks.itemCount, key = tracks.itemKey { it.id.value }) { index ->
                    tracks[index]?.let { track ->
                        Text(
                            text = track.title,
                            color = NamiColors.Paper100,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.addTrack(track.id, albumId) }
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Готово") } },
    )
}
```
Add the import `androidx.compose.foundation.lazy.items` (needed for the `items(count, key) { }` overload used above).

- [ ] **Step 5: Wire the dialog and the "mark as single" toggle into `AlbumDetailScreen`'s menu**

Add `var showAddTracksDialog by remember { mutableStateOf(false) }` to `AlbumDetailScreen`. Add two more items to the album `DropdownMenu`:
```kotlin
DropdownMenuItem(
    text = { Text("Добавить треки") },
    onClick = { showAlbumMenu = false; showAddTracksDialog = true },
)
DropdownMenuItem(
    text = { Text(if (uiState.album?.isSingle == true) "Убрать метку \"сингл\"" else "Отметить как сингл") },
    onClick = {
        showAlbumMenu = false
        uiState.album?.let { viewModel.setIsSingle(!it.isSingle) }
    },
)
```
At the bottom of `AlbumDetailScreen`, alongside the other `if (show...) { ... }` blocks:
```kotlin
if (showAddTracksDialog) {
    uiState.album?.let { album ->
        AddTracksToAlbumDialog(albumId = album.id, onDismiss = { showAddTracksDialog = false })
    }
}
```

In `AlbumDetailViewModel`, add:
```kotlin
fun setIsSingle(isSingle: Boolean) {
    viewModelScope.launch { libraryRepository.setAlbumIsSingle(albumId, isSingle) }
}
```

- [ ] **Step 6: Build and test**

Run: `export JAVA_HOME="/c/Users/Mozzarella6/jdk-temurin-21/jdk-21.0.5+11" && ./gradlew :app:assembleDebug testDebugUnitTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: add/remove tracks in an album, mark album as single"
```

---

### Task 5: Delete album (cascades to its tracks via trash)

**Files:**
- Modify: `feature/library/src/main/kotlin/dev/nami/feature/library/AlbumDetailScreen.kt`
- Modify: `feature/library/src/main/kotlin/dev/nami/feature/library/AlbumDetailViewModel.kt`
- Modify: `app/src/main/kotlin/dev/nami/app/navigation/NamiNavHost.kt`

**Interfaces:**
- Consumes: `LibraryRepository.deleteTracks(ids: List<TrackId>)` (existing).
- Produces: `AlbumDetailViewModel.deleteAlbum(): Unit` (fire-and-forget; the screen navigates back immediately, matching `PlaylistDetailScreen`'s existing `onDeleted` pattern).

- [ ] **Step 1: Add `deleteAlbum` to `AlbumDetailViewModel`**

```kotlin
fun deleteAlbum() {
    viewModelScope.launch {
        libraryRepository.deleteTracks(_uiState.value.tracks.map { it.id })
    }
}
```

- [ ] **Step 2: Add a confirm-delete dialog and menu item to `AlbumDetailScreen`**

Add `var showDeleteConfirm by remember { mutableStateOf(false) }` and a new parameter `onDeleted: () -> Unit`. Add a menu item:
```kotlin
DropdownMenuItem(
    text = { Text("Удалить альбом") },
    onClick = { showAlbumMenu = false; showDeleteConfirm = true },
)
```
At the bottom of the screen:
```kotlin
if (showDeleteConfirm) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { showDeleteConfirm = false },
        title = { Text("Удалить альбом?") },
        text = { Text("Треки альбома переместятся в корзину. Их можно будет восстановить.") },
        confirmButton = {
            TextButton(onClick = {
                viewModel.deleteAlbum()
                showDeleteConfirm = false
                onDeleted()
            }) { Text("Удалить") }
        },
        dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Отмена") } },
    )
}
```

- [ ] **Step 3: Wire `onDeleted` at the call site in `NamiNavHost`**

In the existing `ROUTE_ALBUM_DETAIL` `composable(...)` block in `app/src/main/kotlin/dev/nami/app/navigation/NamiNavHost.kt`, add:
```kotlin
onDeleted = { navController.popBackStack() },
```
to the `AlbumDetailScreen(...)` call (same pattern already used by `PlaylistDetailScreen`'s `onDeleted` in this same file).

- [ ] **Step 4: Build and test**

Run: `export JAVA_HOME="/c/Users/Mozzarella6/jdk-temurin-21/jdk-21.0.5+11" && ./gradlew :app:assembleDebug testDebugUnitTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: add album deletion (tracks go to trash, same as track delete)"
```

---

### Task 6: Album list multi-select + bulk delete

**Files:**
- Modify: `feature/library/src/main/kotlin/dev/nami/feature/library/LibraryViewModel.kt`
- Modify: `feature/library/src/main/kotlin/dev/nami/feature/library/LibraryScreen.kt`
- Modify: `feature/library/src/main/kotlin/dev/nami/feature/library/AlbumGridItem.kt`

**Interfaces:**
- Consumes: `LibraryRepository.tracksInAlbum` (existing, to resolve an album's current track ids before bulk-deleting), `deleteTracks` (existing).
- Produces: no new public interface — this is UI-layer state internal to `LibraryScreen`/`LibraryViewModel`.

- [ ] **Step 1: Add album-selection state to `LibraryUiState`**

In `feature/library/src/main/kotlin/dev/nami/feature/library/LibraryViewModel.kt`:
```kotlin
data class LibraryUiState(
    val importProgress: ImportProgress? = null,
    val selectedTab: LibraryTab = LibraryTab.TRACKS,
    val lastDeletedTrackIds: Set<TrackId> = emptySet(),
    val selectedTrackIds: Set<TrackId> = emptySet(),
    val selectedAlbumIds: Set<AlbumId> = emptySet(),
)
```
Add the import `dev.nami.core.model.AlbumId` (likely already imported for `AlbumSummary`'s package, but `AlbumId` itself may need its own import line).

Add the selection-management functions, mirroring `toggleTrackSelection`/`clearSelection`/`deleteSelectedTracks`:
```kotlin
fun toggleAlbumSelection(id: AlbumId) {
    val current = _uiState.value.selectedAlbumIds
    _uiState.value = _uiState.value.copy(
        selectedAlbumIds = if (id in current) current - id else current + id,
    )
}

fun clearAlbumSelection() {
    _uiState.value = _uiState.value.copy(selectedAlbumIds = emptySet())
}

fun deleteSelectedAlbums() {
    val ids = _uiState.value.selectedAlbumIds
    if (ids.isEmpty()) return
    viewModelScope.launch {
        ids.forEach { albumId ->
            val trackIds = libraryRepository.tracksInAlbum(albumId).first().map { it.id }
            libraryRepository.deleteTracks(trackIds)
        }
        refreshRecentAlbums()
        clearAlbumSelection()
    }
}
```
Add the import `kotlinx.coroutines.flow.first`.

- [ ] **Step 2: Add long-press-to-select and a selection top bar to the Albums tab**

Read `feature/library/src/main/kotlin/dev/nami/feature/library/LibraryScreen.kt`'s current `AlbumGridContent` and `SelectionTopBar` in full first (`SelectionTopBar` currently only handles track selection text/actions). Add a second, album-specific top bar function:
```kotlin
@Composable
private fun AlbumSelectionTopBar(selectedCount: Int, onCancel: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onCancel) {
            Icon(Icons.Filled.Close, contentDescription = "Отменить выбор", tint = NamiColors.Paper100)
        }
        Text(
            text = "Выбрано: $selectedCount",
            color = NamiColors.Paper100,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
        )
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = "Удалить", tint = NamiColors.Paper70)
        }
    }
}
```

In `LibraryScreen`'s main body, compute `val albumSelectionMode = uiState.selectedAlbumIds.isNotEmpty()`. Where the top currently branches on `if (selectionMode) SelectionTopBar(...) else LibraryChipsRow(...)`, extend it:
```kotlin
if (selectionMode) {
    SelectionTopBar(...)
} else if (albumSelectionMode) {
    AlbumSelectionTopBar(
        selectedCount = uiState.selectedAlbumIds.size,
        onCancel = viewModel::clearAlbumSelection,
        onDelete = viewModel::deleteSelectedAlbums,
    )
} else {
    LibraryChipsRow(selected = uiState.selectedTab, onSelect = viewModel::selectTab)
}
```

Add a `BackHandler(enabled = albumSelectionMode) { viewModel.clearAlbumSelection() }` alongside the existing track-selection `BackHandler` at the top of `LibraryScreen`.

- [ ] **Step 3: Wire long-press-to-select and a selection checkbox into `AlbumGridContent`**

In `feature/library/src/main/kotlin/dev/nami/feature/library/AlbumGridItem.kt`, add `selectionMode: Boolean = false` and `isSelected: Boolean = false` parameters, and render a `Checkbox` overlay when `selectionMode` is true (same visual language as `TrackListItem`):
```kotlin
@Composable
fun AlbumGridItem(
    album: AlbumSummary,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onMoreClick: (() -> Unit)? = null,
    selectionMode: Boolean = false,
    isSelected: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        androidx.compose.foundation.layout.Box {
            if (album.artworkPath != null) {
                AsyncImage(
                    model = album.artworkPath,
                    contentDescription = album.title,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f).background(NamiColors.Ink700, RoundedCornerShape(4.dp)),
                )
            } else {
                Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f).background(NamiColors.Ink700, RoundedCornerShape(4.dp)))
            }
            if (selectionMode) {
                androidx.compose.material3.Checkbox(
                    checked = isSelected,
                    onCheckedChange = null,
                    modifier = Modifier.align(Alignment.TopEnd),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = album.title,
                color = NamiColors.Paper100,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (onMoreClick != null && !selectionMode) {
                IconButton(onClick = onMoreClick, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Действия с альбомом", tint = NamiColors.Paper40)
                }
            }
        }
        Text(
            text = album.artistName ?: "",
            color = NamiColors.Paper70,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
```
This changes `Modifier.clickable` to `Modifier.combinedClickable` (needs `@OptIn(ExperimentalFoundationApi::class)` on the function and the imports `androidx.compose.foundation.ExperimentalFoundationApi`, `androidx.compose.foundation.combinedClickable`, replacing the old `androidx.compose.foundation.clickable` import) and hides the per-item overflow button while any items are selected (matching `TrackListItem`'s existing selection-mode behavior of hiding its own overflow menu).

In `AlbumGridContent` (inside `LibraryScreen.kt`), thread `selectionMode`/`selectedAlbumIds`/`onToggleAlbumSelection` down the same way `TrackListContent` already threads its track-selection equivalents, and wire:
```kotlin
AlbumGridItem(
    album = album,
    onClick = {
        if (albumSelectionMode) viewModel.toggleAlbumSelection(album.id) else onAlbumClick(album.id)
    },
    onLongClick = { viewModel.toggleAlbumSelection(album.id) },
    selectionMode = albumSelectionMode,
    isSelected = album.id in uiState.selectedAlbumIds,
    modifier = Modifier.padding(8.dp),
)
```

- [ ] **Step 4: Build and test**

Run: `export JAVA_HOME="/c/Users/Mozzarella6/jdk-temurin-21/jdk-21.0.5+11" && ./gradlew :app:assembleDebug testDebugUnitTest`
Expected: PASS. Fix any `AlbumGridItem(...)` call sites broken by the new parameter order (there are existing calls in `ArtistDetailScreen.kt` and `LibraryScreen.kt`'s `DiscographySection` — since the new parameters have defaults and are inserted before `modifier`, calls using named arguments are unaffected; calls using positional arguments for `modifier` need `modifier = ...` added explicitly if they weren't already named).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: add multi-select and bulk delete to the Albums tab"
```

---

### Task 7: Waveform scrubber in Now Playing

**Files:**
- Create: `feature/player/src/main/kotlin/dev/nami/feature/player/WaveformScrubber.kt`
- Modify: `feature/player/src/main/kotlin/dev/nami/feature/player/NowPlayingScreen.kt`

**Interfaces:**
- Consumes: `NowPlayingViewModel.seek(ms: Long)` (existing), `PlaybackState.Playing.positionMs/durationMs` (existing).
- Produces: `WaveformScrubber(seedKey: String, progress: Float, onSeek: (Float) -> Unit, modifier: Modifier = Modifier)` where `progress` and the `onSeek` callback's argument are both in `[0f, 1f]` (fraction of track duration).

- [ ] **Step 1: Write `WaveformScrubber`**

Per Дизайн.md §3: 350×48 area, 120 bars width 2 gap 1, played portion `--shu`, remainder `--ink-500`, head a 2×48 `--paper-100` vertical line. Bar heights are deterministic per track (seeded `Random`) since no real amplitude data is available (see plan Architecture note) — varied enough to read as a waveform, not a flat EQ ladder:
```kotlin
package dev.nami.feature.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dev.nami.core.designsystem.NamiColors
import kotlin.math.roundToInt
import kotlin.random.Random

private const val BAR_COUNT = 120
private const val BAR_WIDTH_DP = 2
private const val BAR_GAP_DP = 1

/** Deterministic per-track bar heights (0f..1f) -- no real amplitude data is available, so
 * this reads as a plausible waveform shape rather than a flat EQ ladder, seeded so the same
 * track always draws the same shape. */
private fun barHeights(seedKey: String): List<Float> {
    val random = Random(seedKey.hashCode())
    // A few sine-ish "phrases" layered with noise so it doesn't look uniformly random.
    return (0 until BAR_COUNT).map { i ->
        val phrase = (kotlin.math.sin(i / 9.0) * 0.5 + 0.5)
        val noise = random.nextFloat() * 0.5
        (phrase * 0.5 + noise).toFloat().coerceIn(0.15f, 1f)
    }
}

@Composable
fun WaveformScrubber(
    seedKey: String,
    progress: Float,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val heights = remember(seedKey) { barHeights(seedKey) }
    var dragProgress by remember(seedKey) { mutableStateOf<Float?>(null) }
    val displayedProgress = dragProgress ?: progress

    Canvas(
        modifier = modifier
            .height(48.dp)
            .pointerInput(seedKey) {
                detectTapGestures { offset ->
                    val fraction = (offset.x / size.width).coerceIn(0f, 1f)
                    onSeek(fraction)
                }
            }
            .pointerInput(seedKey) {
                detectDragGestures(
                    onDragStart = { offset -> dragProgress = (offset.x / size.width).coerceIn(0f, 1f) },
                    onDrag = { change, _ ->
                        change.consume()
                        dragProgress = (change.position.x / size.width).coerceIn(0f, 1f)
                    },
                    onDragEnd = {
                        dragProgress?.let(onSeek)
                        dragProgress = null
                    },
                    onDragCancel = { dragProgress = null },
                )
            },
    ) {
        val barWidthPx = BAR_WIDTH_DP.dp.toPx()
        val gapPx = BAR_GAP_DP.dp.toPx()
        val step = barWidthPx + gapPx
        val playedBars = (heights.size * displayedProgress).roundToInt()
        heights.forEachIndexed { index, heightFraction ->
            val barHeightPx = size.height * heightFraction
            val x = index * step
            drawLine(
                color = if (index < playedBars) NamiColors.Shu else NamiColors.Ink500,
                start = Offset(x, (size.height - barHeightPx) / 2f),
                end = Offset(x, (size.height + barHeightPx) / 2f),
                strokeWidth = barWidthPx,
            )
        }
        val headX = displayedProgress * size.width
        drawLine(
            color = NamiColors.Paper100,
            start = Offset(headX, 0f),
            end = Offset(headX, size.height),
            strokeWidth = 2.dp.toPx(),
        )
    }
}
```
Confirm `NamiColors.Ink500` and `NamiColors.Shu` are the exact property names (`Grep 'Ink500|Shu' core/designsystem/src/main/kotlin/dev/nami/core/designsystem/Color.kt` — both were already used elsewhere this session, e.g. `Ink500`/`Shu` in the design tokens file read earlier: `Ink500 = Color(0xFF3A3C43)`, `Shu = Color(0xFFC24A34)`).

- [ ] **Step 2: Replace the plain position/duration row in `NowPlayingScreen` with the scrubber**

In `feature/player/src/main/kotlin/dev/nami/feature/player/NowPlayingScreen.kt`, replace the existing block:
```kotlin
Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
    Text(
        text = formatDuration(playing?.positionMs ?: 0L),
        color = NamiColors.Paper70,
        style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
    )
    Text(
        text = "-" + formatDuration(((playing?.durationMs ?: 0L) - (playing?.positionMs ?: 0L)).coerceAtLeast(0L)),
        color = NamiColors.Paper70,
        style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
    )
}
```
with:
```kotlin
val durationMs = playing?.durationMs ?: 0L
val positionMs = playing?.positionMs ?: 0L
val progress = if (durationMs > 0) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
WaveformScrubber(
    seedKey = queue.nowPlaying?.id?.value ?: "",
    progress = progress,
    onSeek = { fraction -> viewModel.seek((fraction * durationMs).toLong()) },
    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
)
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
```

- [ ] **Step 3: Build and test**

Run: `export JAVA_HOME="/c/Users/Mozzarella6/jdk-temurin-21/jdk-21.0.5+11" && ./gradlew :app:assembleDebug testDebugUnitTest`
Expected: PASS.

- [ ] **Step 4: Install and manually verify**

Run: `adb install -r C:/Nami/app/build/outputs/apk/debug/app-debug.apk`
Check: dragging along the waveform seeks playback; tapping a point jumps there; the played portion is `--shu`, the rest `--ink-500`; renaming/re-covering a track/album/artist persists across app restart; deleting an album moves its tracks to the trash screen (visible there, restorable); the Albums tab supports long-press multi-select with bulk delete.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: add waveform scrubber to Now Playing with real seek"
```
