package dev.nami.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import dev.nami.app.navigation.NamiNavHost
import dev.nami.core.designsystem.NamiTheme
import dev.nami.data.AppSettingsRepository
import dev.nami.feature.library.BackupViewModel
import dev.nami.feature.library.LibraryViewModel
import dev.nami.player.EXTRA_OPEN_PLAYER
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var appSettingsRepository: AppSettingsRepository

    private val libraryViewModel: LibraryViewModel by viewModels()
    private val backupViewModel: BackupViewModel by viewModels()
    private val playlistActionsViewModel: PlaylistActionsViewModel by viewModels()
    private val metadataActionsViewModel: MetadataActionsViewModel by viewModels()

    // Bumped whenever an intent (fresh launch or onNewIntent, e.g. tapping the system media
    // notification/status-bar chip) asks to open the player directly.
    private val openPlayerSignal = MutableStateFlow(0)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_PLAYER, false)) openPlayerSignal.value++
        handleShareIntent(intent)
    }

    // П.md §2 "Share-target на audio MIME type" - ACTION_SEND/SEND_MULTIPLE from another app
    // (Telegram, a file manager, a browser's download). Uses the same importFiles(uris) pipeline
    // as the file picker - LibraryRepositoryImpl copies each into private storage immediately, so
    // the temporary read grant this intent carries only needs to outlive that one copy.
    private fun handleShareIntent(intent: Intent) {
        val uris = when (intent.action) {
            Intent.ACTION_SEND -> intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)?.let { listOf(it) }
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<android.net.Uri>(Intent.EXTRA_STREAM)
            else -> null
        }
        if (!uris.isNullOrEmpty()) libraryViewModel.importFiles(uris.map { it.toString() })
    }

    private val pickFiles = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            libraryViewModel.importFiles(uris.map { it.toString() })
        }
    }

    private val pickZip = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { libraryViewModel.importZip(it.toString()) } }

    private val createExportZip = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri -> uri?.let { backupViewModel.exportLibrary(it.toString()) } }

    private val pickFolder = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        uri?.let {
            contentResolver.takePersistableUriPermission(
                it,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            libraryViewModel.importFolder(it.toString())
        }
    }

    // П.md §2 "Режим наблюдения за папкой" - same persistable-permission dance as pickFolder,
    // but also remembers the tree so rescanWatchedFolders() (cold start / manual refresh) can
    // come back to it later.
    private val pickWatchedFolder = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        uri?.let {
            contentResolver.takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            libraryViewModel.addWatchedFolder(it.toString())
        }
    }

    // Two pickers per cover/photo target - system Files (SAF, arbitrary storage/providers) and
    // the system Photo Picker (gallery-style grid, no storage permission needed) - so "Изменить
    // обложку" always offers both instead of jumping straight into just one of them. Both ends of
    // a pair call the exact same ViewModel callback (it only cares about the resulting Uri), so
    // adding the gallery half didn't need touching PlaylistActionsViewModel/MetadataActionsViewModel.
    private val pickCoverImage = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(playlistActionsViewModel::onCoverPicked) }

    private val pickCoverImageFromGallery = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let(playlistActionsViewModel::onCoverPicked) }

    private val pickExportDestination = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-mpegurl"),
    ) { uri -> uri?.let(playlistActionsViewModel::onExportDestinationPicked) }

    private val pickImportSource = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(playlistActionsViewModel::onImportSourcePicked) }

    private val pickAlbumCoverImage = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(metadataActionsViewModel::onAlbumCoverPicked) }

    private val pickAlbumCoverImageFromGallery = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let(metadataActionsViewModel::onAlbumCoverPicked) }

    private val pickArtistPhotoImage = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(metadataActionsViewModel::onArtistPhotoPicked) }

    private val pickArtistPhotoImageFromGallery = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let(metadataActionsViewModel::onArtistPhotoPicked) }

    /** Уход в фон - единственный момент, когда лаунчер точно перечитает иконку, см.
     * [IconPicker.refreshLauncherIfPending]. Ничего не делает, если иконку не меняли. */
    override fun onStop() {
        super.onStop()
        IconPicker.refreshLauncherIfPending(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Transparent system nav bar: content draws edge-to-edge under it (NamiBottomBar already
        // adds its own navigationBarsPadding inset, so nothing shifts), and the gesture bar/
        // buttons overlay directly on the app's own dark background instead of a separate solid
        // system-drawn bar.
        enableEdgeToEdge(navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        // Guards against the app silently vanishing from the launcher if every icon alias ever
        // somehow ended up disabled at once (shouldn't happen - IconPicker.select always
        // enables one before disabling the rest - but a crash mid-toggle or a manifest change
        // across an update could still leave it in that state).
        IconPicker.ensureValidState(this)
        if (intent.getBooleanExtra(EXTRA_OPEN_PLAYER, false)) openPlayerSignal.value++
        handleShareIntent(intent)
        // Watched folders (П.md §2) have no true background watch on Android - rescanned once
        // per cold start instead.
        libraryViewModel.rescanWatchedFolders()
        val importProgress = libraryViewModel.uiState
            .map { it.importProgress }
            .stateIn(lifecycleScope, SharingStarted.Eagerly, libraryViewModel.uiState.value.importProgress)
        setContent {
            // Which pair of (Files, Gallery) launchers "Изменить обложку" should use once the
            // user picks a source in the chooser below - set by the onPickXxx callback that
            // fired, read/cleared once the chooser dialog resolves.
            var pendingImagePickSource by remember { mutableStateOf<ImagePickSource?>(null) }
            val hideSystemBars by appSettingsRepository.hideSystemBars.collectAsState()
            val nightModeEnabled by appSettingsRepository.nightModeEnabled.collectAsState()
            val amoledEnabled by appSettingsRepository.amoledEnabled.collectAsState()
            val themeColorOverrides by appSettingsRepository.themeColorOverrides.collectAsState()
            val themeShapeOverrides by appSettingsRepository.themeShapeOverrides.collectAsState()
            val themeDensityScale by appSettingsRepository.themeDensityScale.collectAsState()
            val themeFontScale by appSettingsRepository.themeFontScale.collectAsState()
            val blurEnabled by appSettingsRepository.blurEnabled.collectAsState()
            val autoNightAmoled by appSettingsRepository.autoNightAmoled.collectAsState()
            // П.md §26 "Автопереключение", самое простое правило: с 23:00 до 6:00 включаем
            // AMOLED сами. Час берётся один раз на создание Activity, живого таймера нет - если
            // приложение открыто в момент наступления 23:00, тема переключится на следующем
            // открытии экрана, а не мгновенно. Сознательное упрощение: полная система правил из
            // плана (по системной теме, по устройству вывода, по плейлисту) - отдельная задача.
            val isNightHour = remember { java.time.LocalTime.now().hour.let { it >= 23 || it < 6 } }
            val uiFontPath by appSettingsRepository.uiFontPath.collectAsState()
            // Loaded once per path, not on every recomposition - Font(File) does real I/O/parsing.
            val uiFontFamily = remember(uiFontPath) {
                uiFontPath?.let { androidx.compose.ui.text.font.FontFamily(androidx.compose.ui.text.font.Font(java.io.File(it))) }
            }
            // Dims the actual screen backlight (not just an on-screen overlay) to its minimum --
            // WindowManager.LayoutParams.screenBrightness in [0,1] overrides the system brightness
            // for this window only; -1 (BRIGHTNESS_OVERRIDE_NONE) restores following the system
            // setting when night mode turns off, so it never leaves the user stuck dim after
            // leaving the app.
            LaunchedEffect(nightModeEnabled) {
                val attrs = window.attributes
                attrs.screenBrightness = if (nightModeEnabled) 0.01f else android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                window.attributes = attrs
            }
            val exportResult by backupViewModel.exportResult.collectAsState()
            LaunchedEffect(exportResult) {
                val result = exportResult ?: return@LaunchedEffect
                val message = if (result) "Библиотека экспортирована" else "Ошибка экспорта"
                android.widget.Toast.makeText(this@MainActivity, message, android.widget.Toast.LENGTH_SHORT).show()
                backupViewModel.exportResultShown()
            }
            LaunchedEffect(hideSystemBars) {
                val controller = WindowInsetsControllerCompat(window, window.decorView)
                if (hideSystemBars) {
                    controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                } else {
                    controller.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                }
            }
            NamiTheme(
                amoled = amoledEnabled || (autoNightAmoled && isNightHour),
                uiFont = uiFontFamily,
                colorOverrides = themeColorOverrides,
                shapeOverrides = themeShapeOverrides,
                densityScale = themeDensityScale,
                fontScale = themeFontScale,
                blurEnabled = blurEnabled,
            ) {
                NamiNavHost(
                    onImportRequested = { pickFiles.launch(arrayOf("audio/*")) },
                    onImportFolderRequested = { pickFolder.launch(null) },
                    onImportZipRequested = { pickZip.launch(arrayOf("application/zip")) },
                    onAddWatchedFolderRequested = { pickWatchedFolder.launch(null) },
                    onExportRequested = {
                        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd_HHmm", java.util.Locale.ROOT).format(java.util.Date())
                        createExportZip.launch("nami_backup_$stamp.zip")
                    },
                    importProgress = importProgress,
                    onPickPlaylistCover = { playlistId ->
                        playlistActionsViewModel.requestCoverPick(playlistId)
                        pendingImagePickSource = ImagePickSource.PLAYLIST_COVER
                    },
                    onExportPlaylist = { playlistId ->
                        playlistActionsViewModel.requestExport(playlistId)
                        pickExportDestination.launch("playlist.m3u8")
                    },
                    onImportPlaylist = { playlistName ->
                        playlistActionsViewModel.requestImport(playlistName)
                        pickImportSource.launch(arrayOf("*/*"))
                    },
                    lastImportResult = playlistActionsViewModel.lastImportResult,
                    onImportResultShown = playlistActionsViewModel::onImportResultShown,
                    onPickAlbumCover = { albumId ->
                        metadataActionsViewModel.requestAlbumCoverPick(albumId)
                        pendingImagePickSource = ImagePickSource.ALBUM_COVER
                    },
                    onPickArtistPhoto = { artistId ->
                        metadataActionsViewModel.requestArtistPhotoPick(artistId)
                        pendingImagePickSource = ImagePickSource.ARTIST_PHOTO
                    },
                    openPlayerSignal = openPlayerSignal,
                )
                pendingImagePickSource?.let { source ->
                    dev.nami.core.designsystem.ContextActionSheet(
                        onDismiss = { pendingImagePickSource = null },
                        actions = listOf(
                            dev.nami.core.designsystem.ContextAction("Галерея", Icons.Outlined.Image) {
                                when (source) {
                                    ImagePickSource.PLAYLIST_COVER -> pickCoverImageFromGallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                    ImagePickSource.ALBUM_COVER -> pickAlbumCoverImageFromGallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                    ImagePickSource.ARTIST_PHOTO -> pickArtistPhotoImageFromGallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                }
                                pendingImagePickSource = null
                            },
                            dev.nami.core.designsystem.ContextAction("Файлы", Icons.Outlined.Folder) {
                                when (source) {
                                    ImagePickSource.PLAYLIST_COVER -> pickCoverImage.launch(arrayOf("image/*"))
                                    ImagePickSource.ALBUM_COVER -> pickAlbumCoverImage.launch(arrayOf("image/*"))
                                    ImagePickSource.ARTIST_PHOTO -> pickArtistPhotoImage.launch(arrayOf("image/*"))
                                }
                                pendingImagePickSource = null
                            },
                        ),
                    )
                }
            }
        }
    }

    private enum class ImagePickSource { PLAYLIST_COVER, ALBUM_COVER, ARTIST_PHOTO }
}
