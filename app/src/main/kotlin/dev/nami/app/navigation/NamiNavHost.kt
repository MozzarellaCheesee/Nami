package dev.nami.app.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.nami.core.designsystem.NamiColors
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
import dev.nami.domain.ImportProgress
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
    onImportFolderRequested: () -> Unit,
    importProgress: StateFlow<ImportProgress?>,
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

    Box(modifier = Modifier.fillMaxSize().background(NamiColors.Ink900)) {
    Column(modifier = Modifier.statusBarsPadding()) {
        NavHost(
            navController = navController,
            startDestination = ROUTE_LIBRARY,
            modifier = Modifier.weight(1f),
            // Default Navigation-Compose cross-fade leaves the outgoing destination composed
            // and touchable for the transition's duration, overlapping the incoming one. That
            // window is where a screen popped by back (e.g. AlbumDetailScreen) can still catch
            // a tap meant for what's now visually on top (e.g. the Albums grid), firing the
            // wrong click handler. Instant, no-overlap switches close that window entirely.
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None },
        ) {
            composable(ROUTE_LIBRARY) {
                LibraryScreen(
                    onTrackClick = { trackId ->
                        nowPlayingViewModel.playFromLibrary(trackId)
                        navController.navigate(ROUTE_NOW_PLAYING)
                    },
                    onAlbumClick = { albumId -> navController.navigate("album/${albumId.value}") },
                    onArtistClick = { artistId -> navController.navigate("artist/${artistId.value}") },
                    onImportRequested = onImportRequested,
                    onImportFolderRequested = onImportFolderRequested,
                    importProgress = importProgress,
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
            // Rendered as a sliding overlay below, not here -- this destination only exists
            // to give Now Playing a real back-stack entry so system back / popBackStack work,
            // without the NavHost swap causing MiniPlayer/BottomBar to disappear abruptly.
            composable(ROUTE_NOW_PLAYING) {}
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
        MiniPlayer(onExpand = { navController.navigate(ROUTE_NOW_PLAYING) }, viewModel = nowPlayingViewModel)
        NamiBottomBar(
            currentRoute = currentRoute,
            onTabSelected = { route ->
                if (route == ROUTE_LIBRARY) {
                    // Library tab always jumps back to the Tracks root, closing any open
                    // Album/Artist detail screen and resetting the Albums/Artists sub-tab --
                    // a fresh instance (no saveState/restoreState) is the simplest way to get
                    // both a clean back stack and LibraryViewModel's default TRACKS tab.
                    navController.navigate(route) {
                        popUpTo(ROUTE_LIBRARY) { inclusive = true }
                        launchSingleTop = true
                    }
                } else {
                    navController.navigate(route) {
                        popUpTo(ROUTE_LIBRARY) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            },
            modifier = Modifier.navigationBarsPadding(),
        )
    }

    AnimatedVisibility(
        visible = currentRoute == ROUTE_NOW_PLAYING,
        enter = slideInVertically(initialOffsetY = { fullHeight -> fullHeight }),
        // Short exit: the screen underneath should show up quickly after a swipe-to-dismiss
        // instead of waiting on a full-length transition.
        exit = slideOutVertically(
            animationSpec = tween(150),
            targetOffsetY = { fullHeight -> fullHeight },
        ),
    ) {
        NowPlayingScreen(
            onCollapse = { navController.popBackStack() },
            onQueueClick = { navController.navigate(ROUTE_QUEUE) },
            viewModel = nowPlayingViewModel,
        )
    }
    }
}
