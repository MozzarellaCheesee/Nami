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

    private val pickFiles = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            libraryViewModel.importFiles(uris.map { it.toString() })
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NamiTheme {
                NamiNavHost(
                    onImportRequested = { pickFiles.launch(arrayOf("audio/*")) },
                )
            }
        }
    }
}
