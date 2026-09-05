package dev.nami.app.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.hilt.navigation.compose.hiltViewModel
import dev.nami.feature.library.LibraryScreen
import dev.nami.feature.player.MiniPlayer
import dev.nami.feature.player.NowPlayingScreen
import dev.nami.feature.player.NowPlayingViewModel

private const val ROUTE_LIBRARY = "library"
private const val ROUTE_NOW_PLAYING = "now_playing"

@Composable
fun NamiNavHost(
    onImportRequested: () -> Unit,
    navController: NavHostController = rememberNavController(),
) {
    // Scoped here (Activity-level ViewModelStoreOwner), not inside a nav destination,
    // so MiniPlayer and NowPlayingScreen share the same instance and stay in sync.
    val nowPlayingViewModel: NowPlayingViewModel = hiltViewModel()

    Column {
        NavHost(
            navController = navController,
            startDestination = ROUTE_LIBRARY,
            modifier = Modifier.weight(1f),
        ) {
            composable(ROUTE_LIBRARY) {
                LibraryScreen(
                    onTrackClick = { trackId ->
                        nowPlayingViewModel.playTrack(trackId)
                        navController.navigate(ROUTE_NOW_PLAYING)
                    },
                    onImportRequested = onImportRequested,
                )
            }
            composable(ROUTE_NOW_PLAYING) {
                NowPlayingScreen(onCollapse = { navController.popBackStack() }, viewModel = nowPlayingViewModel)
            }
        }
        MiniPlayer(onExpand = { navController.navigate(ROUTE_NOW_PLAYING) }, viewModel = nowPlayingViewModel)
    }
}
