package dev.nami.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import dev.nami.app.navigation.NamiNavHost
import dev.nami.core.designsystem.NamiTheme
import dev.nami.data.AppSettingsRepository
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
    private val playlistActionsViewModel: PlaylistActionsViewModel by viewModels()
    private val metadataActionsViewModel: MetadataActionsViewModel by viewModels()

    // Bumped whenever an intent (fresh launch or onNewIntent, e.g. tapping the system media
    // notification/status-bar chip) asks to open the player directly.
    private val openPlayerSignal = MutableStateFlow(0)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_PLAYER, false)) openPlayerSignal.value++
    }

    private val pickFiles = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            libraryViewModel.importFiles(uris.map { it.toString() })
        }
    }

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

    private val pickCoverImage = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
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

    private val pickArtistPhotoImage = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(metadataActionsViewModel::onArtistPhotoPicked) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Transparent system nav bar: content draws edge-to-edge under it (NamiBottomBar already
        // adds its own navigationBarsPadding inset, so nothing shifts), and the gesture bar/
        // buttons overlay directly on the app's own dark background instead of a separate solid
        // system-drawn bar.
        enableEdgeToEdge(navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        // Guards against the app silently vanishing from the launcher if every icon alias ever
        // somehow ended up disabled at once (shouldn't happen -- IconPicker.select always
        // enables one before disabling the rest -- but a crash mid-toggle or a manifest change
        // across an update could still leave it in that state).
        IconPicker.ensureValidState(this)
        if (intent.getBooleanExtra(EXTRA_OPEN_PLAYER, false)) openPlayerSignal.value++
        val importProgress = libraryViewModel.uiState
            .map { it.importProgress }
            .stateIn(lifecycleScope, SharingStarted.Eagerly, libraryViewModel.uiState.value.importProgress)
        setContent {
            val hideSystemBars by appSettingsRepository.hideSystemBars.collectAsState()
            val nightModeEnabled by appSettingsRepository.nightModeEnabled.collectAsState()
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
            LaunchedEffect(hideSystemBars) {
                val controller = WindowInsetsControllerCompat(window, window.decorView)
                if (hideSystemBars) {
                    controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                } else {
                    controller.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                }
            }
            NamiTheme {
                NamiNavHost(
                    onImportRequested = { pickFiles.launch(arrayOf("audio/*")) },
                    onImportFolderRequested = { pickFolder.launch(null) },
                    importProgress = importProgress,
                    onPickPlaylistCover = { playlistId ->
                        playlistActionsViewModel.requestCoverPick(playlistId)
                        pickCoverImage.launch(arrayOf("image/*"))
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
                        pickAlbumCoverImage.launch(arrayOf("image/*"))
                    },
                    onPickArtistPhoto = { artistId ->
                        metadataActionsViewModel.requestArtistPhotoPick(artistId)
                        pickArtistPhotoImage.launch(arrayOf("image/*"))
                    },
                    openPlayerSignal = openPlayerSignal,
                )
            }
        }
    }
}
