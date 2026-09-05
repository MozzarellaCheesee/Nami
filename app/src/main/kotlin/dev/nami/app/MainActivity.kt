package dev.nami.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import dagger.hilt.android.AndroidEntryPoint
import dev.nami.app.navigation.NamiNavHost
import dev.nami.core.designsystem.NamiTheme
import dev.nami.feature.library.LibraryViewModel

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val libraryViewModel: LibraryViewModel by viewModels()
    private val playlistActionsViewModel: PlaylistActionsViewModel by viewModels()

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NamiTheme {
                NamiNavHost(
                    onImportRequested = { pickFiles.launch(arrayOf("audio/*")) },
                    onImportFolderRequested = { pickFolder.launch(null) },
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
                )
            }
        }
    }
}
