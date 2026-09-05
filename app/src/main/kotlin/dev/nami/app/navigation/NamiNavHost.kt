package dev.nami.app.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.nami.app.PlaylistsPlaceholderScreen
import dev.nami.app.SettingsPlaceholderScreen
import dev.nami.feature.library.AlbumDetailScreen
import dev.nami.feature.library.ArtistDetailScreen
import dev.nami.feature.library.LibraryScreen
import dev.nami.feature.player.MiniPlayer
import dev.nami.feature.player.NowPlayingScreen
import dev.nami.feature.player.NowPlayingViewModel
import dev.nami.feature.search.SearchScreen

private const val ROUTE_LIBRARY = "library"
private const val ROUTE_SEARCH = "search"
private const val ROUTE_PLAYLISTS = "playlists"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_NOW_PLAYING = "now_playing"
private const val ROUTE_ALBUM_DETAIL = "album/{albumId}"
private const val ROUTE_ARTIST_DETAIL = "artist/{artistId}"

private val BOTTOM_BAR_ROUTES = setOf(ROUTE_LIBRARY, ROUTE_SEARCH, ROUTE_PLAYLISTS, ROUTE_SETTINGS)

@Composable
fun NamiNavHost(
    onImportRequested: () -> Unit,
    navController: NavHostController = rememberNavController(),
) {
    // Scoped here (Activity-level ViewModelStoreOwner), not inside a nav destination,
    // so MiniPlayer and NowPlayingScreen share the same instance and stay in sync.
    val nowPlayingViewModel: NowPlayingViewModel = hiltViewModel()
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route

    Column(modifier = Modifier.navigationBarsPadding()) {
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
            composable(ROUTE_SEARCH) {
                SearchScreen(
                    onTrackClick = { trackId ->
                        nowPlayingViewModel.playTrack(trackId)
                        navController.navigate(ROUTE_NOW_PLAYING)
                    },
                    onAlbumClick = { albumId -> navController.navigate("album/${albumId.value}") },
                    onArtistClick = { artistId -> navController.navigate("artist/${artistId.value}") },
                )
            }
            composable(ROUTE_PLAYLISTS) { PlaylistsPlaceholderScreen() }
            composable(ROUTE_SETTINGS) { SettingsPlaceholderScreen() }
            composable(ROUTE_NOW_PLAYING) {
                NowPlayingScreen(
                    onCollapse = { navController.popBackStack() },
                    onQueueClick = {},
                    viewModel = nowPlayingViewModel,
                )
            }
            composable(
                ROUTE_ALBUM_DETAIL,
                arguments = listOf(navArgument("albumId") { type = NavType.StringType }),
            ) {
                AlbumDetailScreen(
                    onBack = { navController.popBackStack() },
                    onTrackClick = { trackId ->
                        nowPlayingViewModel.playTrack(trackId)
                        navController.navigate(ROUTE_NOW_PLAYING)
                    },
                )
            }
            composable(
                ROUTE_ARTIST_DETAIL,
                arguments = listOf(navArgument("artistId") { type = NavType.StringType }),
            ) {
                ArtistDetailScreen(
                    onBack = { navController.popBackStack() },
                    onAlbumClick = { albumId -> navController.navigate("album/${albumId.value}") },
                    onTrackClick = { trackId ->
                        nowPlayingViewModel.playTrack(trackId)
                        navController.navigate(ROUTE_NOW_PLAYING)
                    },
                )
            }
        }
        MiniPlayer(onExpand = { navController.navigate(ROUTE_NOW_PLAYING) }, viewModel = nowPlayingViewModel)
        if (currentRoute in BOTTOM_BAR_ROUTES) {
            NamiBottomBar(
                currentRoute = currentRoute,
                onTabSelected = { route ->
                    navController.navigate(route) {
                        popUpTo(ROUTE_LIBRARY) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
            )
        }
    }
}
