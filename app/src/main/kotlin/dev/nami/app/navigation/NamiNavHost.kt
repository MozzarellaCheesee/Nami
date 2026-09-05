package dev.nami.app.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
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
import dev.nami.app.SettingsScreen
import dev.nami.core.model.PlaylistId
import dev.nami.domain.ImportM3u8Result
import dev.nami.feature.library.AlbumDetailScreen
import dev.nami.feature.library.ArtistDetailScreen
import dev.nami.feature.library.LibraryScreen
import dev.nami.feature.player.MiniPlayer
import dev.nami.feature.player.NowPlayingScreen
import dev.nami.feature.player.NowPlayingViewModel
import dev.nami.feature.player.QueueScreen
import dev.nami.feature.playlists.PlaylistDetailScreen
import dev.nami.feature.playlists.PlaylistsScreen
import dev.nami.feature.search.SearchScreen
import dev.nami.feature.trash.TrashScreen
import kotlinx.coroutines.flow.StateFlow

private const val ROUTE_LIBRARY = "library"
private const val ROUTE_SEARCH = "search"
private const val ROUTE_PLAYLISTS = "playlists"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_NOW_PLAYING = "now_playing"
private const val ROUTE_QUEUE = "queue"
private const val ROUTE_ALBUM_DETAIL = "album/{albumId}"
private const val ROUTE_ARTIST_DETAIL = "artist/{artistId}"
private const val ROUTE_PLAYLIST_DETAIL = "playlist/{playlistId}"
private const val ROUTE_TRASH = "trash"


@Composable
fun NamiNavHost(
    onImportRequested: () -> Unit,
    onPickPlaylistCover: (PlaylistId) -> Unit,
    onExportPlaylist: (PlaylistId) -> Unit,
    onImportPlaylist: (playlistName: String) -> Unit,
    lastImportResult: StateFlow<ImportM3u8Result?>,
    onImportResultShown: () -> Unit,
    navController: NavHostController = rememberNavController(),
) {
    // Scoped here (Activity-level ViewModelStoreOwner), not inside a nav destination,
    // so MiniPlayer and NowPlayingScreen share the same instance and stay in sync.
    val nowPlayingViewModel: NowPlayingViewModel = hiltViewModel()
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route

    Column(modifier = Modifier.statusBarsPadding().navigationBarsPadding()) {
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
            composable(ROUTE_PLAYLISTS) {
                PlaylistsScreen(
                    onPlaylistClick = { playlistId -> navController.navigate("playlist/${playlistId.value}") },
                    onImportRequested = onImportPlaylist,
                    lastImportResult = lastImportResult,
                    onImportResultShown = onImportResultShown,
                )
            }
            composable(ROUTE_SETTINGS) {
                SettingsScreen(onTrashClick = { navController.navigate(ROUTE_TRASH) })
            }
            composable(ROUTE_TRASH) {
                TrashScreen(onBack = { navController.popBackStack() })
            }
            composable(ROUTE_NOW_PLAYING) {
                NowPlayingScreen(
                    onCollapse = { navController.popBackStack() },
                    onQueueClick = { navController.navigate(ROUTE_QUEUE) },
                    viewModel = nowPlayingViewModel,
                )
            }
            composable(ROUTE_QUEUE) {
                QueueScreen(onBack = { navController.popBackStack() }, viewModel = nowPlayingViewModel)
            }
            composable(
                ROUTE_ALBUM_DETAIL,
                arguments = listOf(navArgument("albumId") { type = NavType.StringType }),
            ) {
                AlbumDetailScreen(
                    onBack = { navController.popBackStack() },
                    onPlayTracks = { tracks, startIndex ->
                        nowPlayingViewModel.playTracks(tracks, artistName = null, startIndex = startIndex)
                        navController.navigate(ROUTE_NOW_PLAYING)
                    },
                    onAddToQueue = { track -> nowPlayingViewModel.addToQueue(track, artistName = null) },
                )
            }
            composable(
                ROUTE_ARTIST_DETAIL,
                arguments = listOf(navArgument("artistId") { type = NavType.StringType }),
            ) {
                ArtistDetailScreen(
                    onBack = { navController.popBackStack() },
                    onAlbumClick = { albumId -> navController.navigate("album/${albumId.value}") },
                    onPlayTracks = { tracks, artistName, startIndex ->
                        nowPlayingViewModel.playTracks(tracks, artistName, startIndex)
                        navController.navigate(ROUTE_NOW_PLAYING)
                    },
                    onAddToQueue = { track, artistName -> nowPlayingViewModel.addToQueue(track, artistName) },
                )
            }
            composable(
                ROUTE_PLAYLIST_DETAIL,
                arguments = listOf(navArgument("playlistId") { type = NavType.StringType }),
            ) {
                PlaylistDetailScreen(
                    onBack = { navController.popBackStack() },
                    onDeleted = { navController.popBackStack() },
                    onPlayTracks = { tracks, startIndex ->
                        nowPlayingViewModel.playTracks(tracks, artistName = null, startIndex = startIndex)
                        navController.navigate(ROUTE_NOW_PLAYING)
                    },
                    onExportRequested = onExportPlaylist,
                    onPickCoverRequested = onPickPlaylistCover,
                )
            }
        }
        if (currentRoute != ROUTE_NOW_PLAYING) {
            MiniPlayer(onExpand = { navController.navigate(ROUTE_NOW_PLAYING) }, viewModel = nowPlayingViewModel)
        }
        if (currentRoute != ROUTE_NOW_PLAYING) {
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
