package dev.nami.app.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.nami.feature.library.AlbumDetailScreen
import dev.nami.feature.library.ArtistDetailScreen
import dev.nami.feature.library.LibraryScreen
import dev.nami.feature.player.MiniPlayer
import dev.nami.feature.player.NowPlayingScreen
import dev.nami.feature.player.NowPlayingViewModel

private const val ROUTE_LIBRARY = "library"
private const val ROUTE_NOW_PLAYING = "now_playing"
private const val ROUTE_ALBUM_DETAIL = "album/{albumId}"
private const val ROUTE_ARTIST_DETAIL = "artist/{artistId}"

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
                    onAlbumClick = { albumId -> navController.navigate("album/${albumId.value}") },
                    onArtistClick = { artistId -> navController.navigate("artist/${artistId.value}") },
                    onImportRequested = onImportRequested,
                )
            }
            composable(ROUTE_NOW_PLAYING) {
                NowPlayingScreen(onCollapse = { navController.popBackStack() }, viewModel = nowPlayingViewModel)
            }
            composable(
                ROUTE_ALBUM_DETAIL,
                arguments = listOf(navArgument("albumId") { type = NavType.StringType }),
            ) {
                AlbumDetailScreen(onBack = { navController.popBackStack() })
            }
            composable(
                ROUTE_ARTIST_DETAIL,
                arguments = listOf(navArgument("artistId") { type = NavType.StringType }),
            ) {
                ArtistDetailScreen(
                    onBack = { navController.popBackStack() },
                    onAlbumClick = { albumId -> navController.navigate("album/${albumId.value}") },
                )
            }
        }
        MiniPlayer(onExpand = { navController.navigate(ROUTE_NOW_PLAYING) }, viewModel = nowPlayingViewModel)
    }
}
