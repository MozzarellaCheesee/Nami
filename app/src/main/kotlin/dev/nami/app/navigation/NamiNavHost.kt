package dev.nami.app.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.displayCutoutPadding
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
import dev.nami.app.SettingsAppearanceScreen
import dev.nami.app.SettingsLyricsScreen
import dev.nami.app.SettingsPlayerScreen
import dev.nami.app.SettingsScreen
import dev.nami.app.SettingsViewModel
import dev.nami.core.model.AlbumId
import dev.nami.core.model.ArtistId
import dev.nami.core.model.PlaylistId
import dev.nami.domain.ImportM3u8Result
import dev.nami.domain.ImportProgress
import dev.nami.feature.library.AlbumDetailScreen
import dev.nami.feature.library.ArtistDetailScreen
import dev.nami.feature.library.ArtistAllTracksScreen
import dev.nami.feature.library.ArtistDiscographyScreen
import dev.nami.feature.library.LibraryScreen
import dev.nami.feature.library.LibraryTab
import dev.nami.feature.library.LibraryViewModel
import dev.nami.feature.player.AudioTractScreen
import dev.nami.feature.player.EqualizerScreen
import dev.nami.feature.player.LyricsScreen
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
private const val ROUTE_ALBUM_DETAIL = "album/{albumId}"
private const val ROUTE_ARTIST_DETAIL = "artist/{artistId}"
private const val ROUTE_ARTIST_DISCOGRAPHY = "artist/{artistId}/discography"
private const val ROUTE_ARTIST_ALL_TRACKS = "artist/{artistId}/tracks"
private const val ROUTE_PLAYLIST_DETAIL = "playlist/{playlistId}"
private const val ROUTE_SMART_PLAYLIST_EDITOR = "smart_playlist_editor"
private const val ROUTE_SMART_PLAYLIST_EDIT_EXISTING = "smart_playlist_editor/{playlistId}"
private const val ROUTE_SETTINGS_APPEARANCE = "settings/appearance"
private const val ROUTE_SETTINGS_PLAYER = "settings/player"
private const val ROUTE_SESSIONS = "settings/sessions"
private const val ROUTE_SETTINGS_LYRICS = "settings/lyrics"
private const val ROUTE_TRASH = "trash"
private const val ROUTE_LIBRARY_HEALTH = "library_health"
private const val ROUTE_STATS = "stats"
private const val ROUTE_TRACK_INFO = "track_info/{trackId}"
private const val ROUTE_ALBUM_INFO = "album_info/{albumId}"
private const val ROUTE_ARTIST_INFO = "artist_info/{artistId}"
private const val ROUTE_AUDIO_TRACT = "audio_tract"
private const val ROUTE_EQUALIZER = "equalizer"


@Composable
fun NamiNavHost(
    onImportRequested: () -> Unit,
    onImportFolderRequested: () -> Unit,
    onImportZipRequested: () -> Unit,
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
    // Also hoisted here rather than scoped to the ROUTE_LIBRARY nav entry -- the bottom nav's
    // Library tab needs to call selectTab(TRACKS) directly and reliably from any screen (Album/
    // Artist detail, Discography, another tab entirely). The previous approach signaled a
    // LaunchedEffect keyed off an int bump, scoped inside the ROUTE_LIBRARY composable -- reset
    // only actually resets if that effect happens to remount and re-observe the new key at the
    // right time, which turned out not to hold up from every screen it needed to.
    val libraryViewModel: LibraryViewModel = hiltViewModel()
    val searchViewModel: dev.nami.feature.search.SearchViewModel = hiltViewModel()
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
    var showQueue by remember { mutableStateOf(false) }
    // Registered after showNowPlaying's, so it takes priority (last-mounted BackHandler wins)
    // while both are showing -- back should close Queue first, not skip straight past it.
    BackHandler(enabled = showQueue) { showQueue = false }
    var showLyrics by remember { mutableStateOf(false) }
    BackHandler(enabled = showLyrics) { showLyrics = false }

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
    Column(modifier = Modifier.statusBarsPadding().displayCutoutPadding()) {
        NavHost(
            navController = navController,
            startDestination = ROUTE_LIBRARY,
            // No clipToBounds here anymore -- it used to blanket-clip every destination to this
            // Column's own bounds, which also meant Album/Artist detail's cover couldn't bleed
            // up past the status-bar-height padding above (their own negative-offset trick for
            // that was otherwise correct, just clipped away before it could ever draw). The
            // "list's last row peeking past MiniPlayer" bug this used to guard against is now
            // handled per-route below (each list screen wraps itself in its own clipToBounds()),
            // so screens that don't need it aren't clipped for no reason.
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
                // LibraryScreen already wraps its own list in clipToBounds() internally -- no
                // extra wrap needed here.
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
                    onImportZipRequested = onImportZipRequested,
                    onShowTrackInfo = { trackId -> navController.navigate("track_info/${trackId.value}") },
                    importProgress = importProgress,
                    resetSignal = libraryTabResetSignal,
                    viewModel = libraryViewModel,
                )
            }
            composable(ROUTE_SEARCH) {
                Box(modifier = Modifier.fillMaxSize().clipToBounds()) {
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
                    viewModel = searchViewModel,
                )
                }
            }
            composable(ROUTE_PLAYLISTS) {
                Box(modifier = Modifier.fillMaxSize().clipToBounds()) {
                PlaylistsScreen(
                    onPlaylistClick = { playlistId -> navController.navigate("playlist/${playlistId.value}") },
                    onImportRequested = onImportPlaylist,
                    onCreateSmartPlaylist = { navController.navigate(ROUTE_SMART_PLAYLIST_EDITOR) },
                    lastImportResult = lastImportResult,
                    onImportResultShown = onImportResultShown,
                )
                }
            }
            composable(ROUTE_SMART_PLAYLIST_EDITOR) {
                dev.nami.feature.playlists.SmartPlaylistEditorScreen(onBack = { navController.popBackStack() })
            }
            composable(
                ROUTE_SMART_PLAYLIST_EDIT_EXISTING,
                arguments = listOf(navArgument("playlistId") { type = NavType.StringType }),
            ) {
                dev.nami.feature.playlists.SmartPlaylistEditorScreen(onBack = { navController.popBackStack() })
            }
            composable(ROUTE_SETTINGS) {
                Box(modifier = Modifier.fillMaxSize().clipToBounds()) {
                SettingsScreen(
                    onTrashClick = { navController.navigate(ROUTE_TRASH) },
                    onAudioTractClick = { navController.navigate(ROUTE_AUDIO_TRACT) },
                    onAppearanceClick = { navController.navigate(ROUTE_SETTINGS_APPEARANCE) },
                    onPlayerClick = { navController.navigate(ROUTE_SETTINGS_PLAYER) },
                    onLyricsClick = { navController.navigate(ROUTE_SETTINGS_LYRICS) },
                    onLibraryHealthClick = { navController.navigate(ROUTE_LIBRARY_HEALTH) },
                    onStatsClick = { navController.navigate(ROUTE_STATS) },
                )
                }
            }
            composable(ROUTE_SETTINGS_APPEARANCE) {
                SettingsAppearanceScreen(onBack = { navController.popBackStack() })
            }
            composable(ROUTE_SETTINGS_PLAYER) {
                SettingsPlayerScreen(
                    onBack = { navController.popBackStack() },
                    onSessionsClick = { navController.navigate(ROUTE_SESSIONS) },
                )
            }
            composable(ROUTE_SETTINGS_LYRICS) {
                SettingsLyricsScreen(onBack = { navController.popBackStack() })
            }
            composable(ROUTE_SESSIONS) {
                dev.nami.app.SessionsScreen(onBack = { navController.popBackStack() })
            }
            composable(ROUTE_LIBRARY_HEALTH) {
                dev.nami.feature.library.LibraryHealthScreen(onBack = { navController.popBackStack() })
            }
            composable(ROUTE_STATS) {
                dev.nami.feature.library.StatsScreen(onBack = { navController.popBackStack() })
            }
            composable(
                ROUTE_TRACK_INFO,
                arguments = listOf(navArgument("trackId") { type = NavType.StringType }),
            ) {
                dev.nami.feature.library.TrackInfoScreen(onBack = { navController.popBackStack() })
            }
            composable(
                ROUTE_ALBUM_INFO,
                arguments = listOf(navArgument("albumId") { type = NavType.StringType }),
            ) {
                dev.nami.feature.library.AlbumInfoScreen(onBack = { navController.popBackStack() })
            }
            composable(
                ROUTE_ARTIST_INFO,
                arguments = listOf(navArgument("artistId") { type = NavType.StringType }),
            ) {
                dev.nami.feature.library.ArtistInfoScreen(onBack = { navController.popBackStack() })
            }
            composable(ROUTE_TRASH) {
                Box(modifier = Modifier.fillMaxSize().clipToBounds()) {
                TrashScreen(onBack = { navController.popBackStack() })
                }
            }
            composable(ROUTE_AUDIO_TRACT) {
                AudioTractScreen(
                    onBack = { navController.popBackStack() },
                    onOpenEqualizer = { navController.navigate(ROUTE_EQUALIZER) },
                )
            }
            composable(ROUTE_EQUALIZER) {
                EqualizerScreen(onBack = { navController.popBackStack() })
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
                    onShuffleTracks = { tracks ->
                        nowPlayingViewModel.playTracksShuffled(tracks, artistName = null)
                        if (autoOpenPlayer) showNowPlaying = true
                    },
                    onAddToQueue = { track -> nowPlayingViewModel.addToQueue(track, artistName = null) },
                    onPickCoverRequested = onPickAlbumCover,
                    onDeleted = { navController.popBackStack() },
                    onShowTrackInfo = { trackId -> navController.navigate("track_info/${trackId.value}") },
                    onShowAlbumInfo = { albumId -> navController.navigate("album_info/${albumId.value}") },
                )
            }
            composable(
                ROUTE_ARTIST_DETAIL,
                arguments = listOf(navArgument("artistId") { type = NavType.StringType }),
            ) {
                val artistId = ArtistId(it.arguments?.getString("artistId").orEmpty())
                ArtistDetailScreen(
                    onBack = { navController.popBackStack() },
                    onAlbumClick = { albumId -> navController.navigate("album/${albumId.value}") },
                    onShowDiscography = { navController.navigate("artist/${artistId.value}/discography") },
                    onShowAllTracks = { navController.navigate("artist/${artistId.value}/tracks") },
                    onPlayTracks = { tracks, artistName, startIndex ->
                        if (queue.nowPlaying?.id == tracks.getOrNull(startIndex)?.id) {
                            showNowPlaying = true
                        } else {
                            nowPlayingViewModel.playTracks(tracks, artistName, startIndex)
                            if (autoOpenPlayer) showNowPlaying = true
                        }
                    },
                    onShuffleTracks = { tracks, artistName ->
                        nowPlayingViewModel.playTracksShuffled(tracks, artistName)
                        if (autoOpenPlayer) showNowPlaying = true
                    },
                    onAddToQueue = { track, artistName -> nowPlayingViewModel.addToQueue(track, artistName) },
                    onPickPhotoRequested = onPickArtistPhoto,
                    onShowArtistInfo = { id -> navController.navigate("artist_info/${id.value}") },
                )
            }
            composable(
                ROUTE_ARTIST_DISCOGRAPHY,
                arguments = listOf(navArgument("artistId") { type = NavType.StringType }),
            ) {
                Box(modifier = Modifier.fillMaxSize().clipToBounds()) {
                ArtistDiscographyScreen(
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
                )
                }
            }
            composable(
                ROUTE_ARTIST_ALL_TRACKS,
                arguments = listOf(navArgument("artistId") { type = NavType.StringType }),
            ) {
                Box(modifier = Modifier.fillMaxSize().clipToBounds()) {
                ArtistAllTracksScreen(
                    onBack = { navController.popBackStack() },
                    onPlayTracks = { tracks, artistName, startIndex ->
                        if (queue.nowPlaying?.id == tracks.getOrNull(startIndex)?.id) {
                            showNowPlaying = true
                        } else {
                            nowPlayingViewModel.playTracks(tracks, artistName, startIndex)
                            if (autoOpenPlayer) showNowPlaying = true
                        }
                    },
                    onAddToQueue = { track, artistName -> nowPlayingViewModel.addToQueue(track, artistName) },
                    onShowTrackInfo = { trackId -> navController.navigate("track_info/${trackId.value}") },
                )
                }
            }
            composable(
                ROUTE_PLAYLIST_DETAIL,
                arguments = listOf(navArgument("playlistId") { type = NavType.StringType }),
            ) {
                Box(modifier = Modifier.fillMaxSize().clipToBounds()) {
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
                    onEditSmartPlaylist = { playlistId -> navController.navigate("smart_playlist_editor/${playlistId.value}") },
                )
                }
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
                // Pop the back stack directly down to this tab's own root, however deep the
                // current screen is nested (playlist detail, a settings sub-screen, Search ->
                // Artist, etc.) -- succeeds (returns true) only when `route` is actually already
                // on the live stack, i.e. this tab is the one currently open. More direct and
                // reliable than navigate()'s popUpTo()/launchSingleTop/restoreState combo (tried
                // first here): that combo is meant for jumping BETWEEN independent nested graphs,
                // and on this app's single flat stack it just navigated to the target route
                // without actually clearing whatever was pushed on top of it, so re-tapping a tab
                // while inside one of its sub-screens silently did nothing.
                if (!navController.popBackStack(route, inclusive = false)) {
                    // Not on the stack at all yet -- this is a real switch to a different tab.
                    navController.navigate(route) {
                        popUpTo(ROUTE_LIBRARY) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
                if (route == ROUTE_LIBRARY) {
                    libraryViewModel.selectTab(LibraryTab.TRACKS)
                    libraryTabResetSignal++
                }
                if (route == ROUTE_SEARCH) searchViewModel.onQueryChange("")
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
            onQueueClick = { showQueue = true },
            onLyricsClick = { showLyrics = true },
            onOpenAlbum = { albumId ->
                showNowPlaying = false
                navController.navigate("album/${albumId.value}")
            },
            onOpenArtist = { artistId ->
                showNowPlaying = false
                navController.navigate("artist/${artistId.value}")
            },
            onShowTrackInfo = { trackId ->
                showNowPlaying = false
                navController.navigate("track_info/${trackId.value}")
            },
            viewModel = nowPlayingViewModel,
        )
    }

    // Also plain state, not a nav destination -- was previously pushed onto the same NavHost as
    // Library/etc, which rendered it BEHIND NowPlayingScreen's overlay (drawn later, on top) since
    // that overlay isn't part of the nav graph either. Stacking this AnimatedVisibility after
    // NowPlaying's puts it on top for real.
    AnimatedVisibility(
        visible = showQueue,
        enter = slideInVertically(initialOffsetY = { fullHeight -> fullHeight }),
        // Unlike NowPlaying, Queue/Lyrics don't already animate themselves off-screen before the
        // system back gesture/button flips this to false -- their own drag-dismiss does, but
        // hardware back skips straight to the BackHandler above, so this exit is what animates
        // that path instead of an instant cut.
        exit = slideOutVertically(targetOffsetY = { fullHeight -> fullHeight }),
    ) {
        QueueScreen(onBack = { showQueue = false }, viewModel = nowPlayingViewModel)
    }

    AnimatedVisibility(
        visible = showLyrics,
        enter = slideInVertically(initialOffsetY = { fullHeight -> fullHeight }),
        exit = slideOutVertically(targetOffsetY = { fullHeight -> fullHeight }),
    ) {
        LyricsScreen(onBack = { showLyrics = false }, nowPlayingViewModel = nowPlayingViewModel)
    }
    }
}
