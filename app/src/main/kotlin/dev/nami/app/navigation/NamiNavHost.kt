package dev.nami.app.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
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
import dev.nami.app.SettingsViewModel
import dev.nami.core.model.AlbumId
import dev.nami.core.model.ArtistId
import dev.nami.core.model.PlaylistId
import dev.nami.domain.ImportM3u8Result
import dev.nami.domain.ImportProgress
import dev.nami.feature.library.AlbumDetailScreen
import dev.nami.feature.library.ArtistDetailScreen
import dev.nami.feature.library.LibraryScreen
import dev.nami.feature.library.LibraryTab
import dev.nami.feature.library.LibraryViewModel
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
    onPickAlbumCover: (AlbumId) -> Unit,
    onPickArtistPhoto: (ArtistId) -> Unit,
    openPlayerSignal: StateFlow<Int> = kotlinx.coroutines.flow.MutableStateFlow(0),
    navController: NavHostController = rememberNavController(),
) {
    // Scoped here (Activity-level ViewModelStoreOwner), not inside a nav destination,
    // so MiniPlayer and NowPlayingScreen share the same instance and stay in sync.
    val nowPlayingViewModel: NowPlayingViewModel = hiltViewModel()
    val queue by nowPlayingViewModel.queue.collectAsState()
    val settingsViewModel: SettingsViewModel = hiltViewModel()
    val autoOpenPlayer by settingsViewModel.autoOpenPlayer.collectAsState()
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route

    // Now Playing is deliberately NOT a NavHost destination: NavHost only keeps its current
    // destination's composition alive, so pushing a "now_playing" route used to dispose the
    // whole Library screen (Paging, recentAlbums, scroll position, ViewModel) behind it -- every
    // expand/collapse paid for a full reload. Tracking it as plain state keeps Library (or
    // whatever screen was open) mounted underneath the whole time; the overlay below is purely
    // visual, and system back is wired by hand via BackHandler instead of the nav graph.
    var showNowPlaying by remember { mutableStateOf(false) }
    BackHandler(enabled = showNowPlaying) { showNowPlaying = false }

    // Tapping the system media notification/status-bar chip bumps this from MainActivity --
    // skip the initial value (0) so it only reacts to an actual tap, not first composition.
    val openPlayerSignalValue by openPlayerSignal.collectAsState()
    var hasSeenInitialOpenSignal by remember { mutableStateOf(false) }
    LaunchedEffect(openPlayerSignalValue) {
        if (hasSeenInitialOpenSignal) showNowPlaying = true
        hasSeenInitialOpenSignal = true
    }

    // Bumped each time the Library tab is tapped, to reset its sub-tab to Tracks without
    // recreating LibraryViewModel/its Paging flows -- an earlier fix used a fresh nav entry
    // (popUpTo inclusive) for that reset, which briefly flashed an empty list + import banner
    // while Paging reloaded from scratch every time.
    var libraryTabResetSignal by remember { mutableIntStateOf(0) }

    Box(modifier = Modifier.fillMaxSize().background(NamiColors.Ink900)) {
    Column(modifier = Modifier.statusBarsPadding()) {
        NavHost(
            navController = navController,
            startDestination = ROUTE_LIBRARY,
            // clipToBounds: a list's last row can render partially past its own weighted area
            // right where MiniPlayer starts (the boundary in a Column doesn't clip children by
            // default), showing a sliver of it squeezed between the list and the bar below.
            modifier = Modifier.weight(1f).clipToBounds(),
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
            composable(ROUTE_LIBRARY) { entry ->
                val libraryViewModel: LibraryViewModel = hiltViewModel(entry)
                LaunchedEffect(libraryTabResetSignal) {
                    if (libraryTabResetSignal > 0) libraryViewModel.selectTab(LibraryTab.TRACKS)
                }
                LibraryScreen(
                    onTrackClick = { trackId ->
                        if (queue.nowPlaying?.id == trackId) {
                            showNowPlaying = true
                        } else {
                            nowPlayingViewModel.playFromLibrary(trackId)
                            if (autoOpenPlayer) showNowPlaying = true
                        }
                    },
                    onAlbumClick = { albumId -> navController.navigate("album/${albumId.value}") },
                    onArtistClick = { artistId -> navController.navigate("artist/${artistId.value}") },
                    onImportRequested = onImportRequested,
                    onImportFolderRequested = onImportFolderRequested,
                    importProgress = importProgress,
                    viewModel = libraryViewModel,
                )
            }
            composable(ROUTE_SEARCH) {
                SearchScreen(
                    onTrackClick = { trackId ->
                        if (queue.nowPlaying?.id == trackId) {
                            showNowPlaying = true
                        } else {
                            nowPlayingViewModel.playTrack(trackId)
                            if (autoOpenPlayer) showNowPlaying = true
                        }
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
                        if (queue.nowPlaying?.id == tracks.getOrNull(startIndex)?.id) {
                            showNowPlaying = true
                        } else {
                            nowPlayingViewModel.playTracks(tracks, artistName = null, startIndex = startIndex)
                            if (autoOpenPlayer) showNowPlaying = true
                        }
                    },
                    onAddToQueue = { track -> nowPlayingViewModel.addToQueue(track, artistName = null) },
                    onPickCoverRequested = onPickAlbumCover,
                    onDeleted = { navController.popBackStack() },
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
                        if (queue.nowPlaying?.id == tracks.getOrNull(startIndex)?.id) {
                            showNowPlaying = true
                        } else {
                            nowPlayingViewModel.playTracks(tracks, artistName, startIndex)
                            if (autoOpenPlayer) showNowPlaying = true
                        }
                    },
                    onAddToQueue = { track, artistName -> nowPlayingViewModel.addToQueue(track, artistName) },
                    onPickPhotoRequested = onPickArtistPhoto,
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
                        if (queue.nowPlaying?.id == tracks.getOrNull(startIndex)?.id) {
                            showNowPlaying = true
                        } else {
                            nowPlayingViewModel.playTracks(tracks, artistName = null, startIndex = startIndex)
                            if (autoOpenPlayer) showNowPlaying = true
                        }
                    },
                    onExportRequested = onExportPlaylist,
                    onPickCoverRequested = onPickPlaylistCover,
                )
            }
        }
        // Always mounted, even while Now Playing is open/closing -- it's what Now Playing's
        // own slide-down is supposed to progressively uncover. Hiding it made it pop in
        // abruptly the moment Now Playing finished closing instead of already being there.
        // The AnimatedVisibility here only handles the very first appearance (nothing was
        // playing, now something is) -- exit is instant because MiniPlayer's own swipe-down
        // dismiss already animates its height to 0 before queue.nowPlaying goes null (see
        // MiniPlayer.kt), so by the time this flips invisible there's nothing left to see.
        AnimatedVisibility(
            visible = queue.nowPlaying != null,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = ExitTransition.None,
        ) {
            MiniPlayer(onExpand = { showNowPlaying = true }, viewModel = nowPlayingViewModel)
        }
        NamiBottomBar(
            currentRoute = currentRoute,
            onTabSelected = { route ->
                navController.navigate(route) {
                    popUpTo(ROUTE_LIBRARY) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
                // Library tab always jumps back to the Tracks root, closing any open
                // Album/Artist detail screen (the popUpTo above already does that) and
                // resetting the Albums/Artists sub-tab -- via the signal, not a fresh
                // ViewModel/Paging instance, so the list doesn't flash empty on the way.
                if (route == ROUTE_LIBRARY) libraryTabResetSignal++
            },
            modifier = Modifier.navigationBarsPadding(),
        )
    }

    AnimatedVisibility(
        visible = showNowPlaying,
        enter = slideInVertically(initialOffsetY = { fullHeight -> fullHeight }),
        // Instant exit: NowPlayingScreen always finishes its own slide-down animation (swipe
        // or the collapse chevron, both routed through the same code) before flipping this to
        // false, so by that point the screen is already fully off-canvas -- an animated exit
        // here would just add a second, redundant slide on top of that one.
        exit = ExitTransition.None,
    ) {
        NowPlayingScreen(
            onCollapse = { showNowPlaying = false },
            onQueueClick = { navController.navigate(ROUTE_QUEUE) },
            viewModel = nowPlayingViewModel,
        )
    }
    }
}
