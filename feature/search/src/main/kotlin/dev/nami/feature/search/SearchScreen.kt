package dev.nami.feature.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.LibraryAdd
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil3.compose.AsyncImage
import dev.nami.core.designsystem.NamiColors
import dev.nami.core.model.AlbumId
import dev.nami.core.model.ArtistId
import dev.nami.core.model.TrackId
import dev.nami.domain.SearchResult
import dev.nami.feature.playlists.AddToPlaylistDialog

private const val TRACKS_PREVIEW = 4
private const val ALBUMS_PREVIEW = 6
private const val ARTISTS_PREVIEW = 4

private enum class SearchSection { TRACKS, ALBUMS, ARTISTS }

@Composable
fun SearchScreen(
    onTrackClick: (TrackId) -> Unit,
    onAlbumClick: (AlbumId) -> Unit,
    onArtistClick: (ArtistId) -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    var addToPlaylistTrackId by remember { mutableStateOf<TrackId?>(null) }
    var expandedSection by remember { mutableStateOf<SearchSection?>(null) }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    fun unfocusSearchField() {
        focusManager.clearFocus()
        keyboardController?.hide()
    }
    val resultsListState = rememberLazyListState()
    // Defocusing (and hiding the keyboard) as soon as the list starts scrolling, same as most
    // search screens -- tapping a result also defocuses (each result's onClick below), but a
    // scroll with no tap needs its own trigger.
    LaunchedEffect(resultsListState.isScrollInProgress) {
        if (resultsListState.isScrollInProgress) unfocusSearchField()
    }

    val tracks = uiState.results.filterIsInstance<SearchResult.TrackResult>()
    val albums = uiState.results.filterIsInstance<SearchResult.AlbumResult>()
    val artists = uiState.results.filterIsInstance<SearchResult.ArtistResult>()

    val browseAlbums by viewModel.browseAlbums.collectAsState()
    val browseArtists by viewModel.browseArtists.collectAsState()

    Column(modifier = Modifier.fillMaxSize().background(NamiColors.Ink900).imePadding()) {
        CompactSearchField(
            value = uiState.query,
            onValueChange = viewModel::onQueryChange,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        )

        if (uiState.query.isBlank()) {
            LazyColumn(state = resultsListState) {
                if (uiState.recentQueries.isNotEmpty()) {
                    item { SectionHeader("Недавнее") }
                    item {
                        LazyRow(
                            contentPadding = PaddingValuesHorizontal20,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(uiState.recentQueries) { query ->
                                Text(
                                    text = query,
                                    color = NamiColors.Paper100,
                                    style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier
                                        .background(NamiColors.Ink800, RoundedCornerShape(20.dp))
                                        .clickable { viewModel.onRecentQueryClick(query) }
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                            }
                        }
                    }
                }
                // Same recentAlbums/featuredArtists data the Library tab already previews with --
                // browsable straight from Search instead of an empty "start typing" prompt.
                if (browseAlbums.isNotEmpty()) {
                    item { SectionHeader("Альбомы") }
                    item {
                        LazyRow(
                            contentPadding = PaddingValuesHorizontal20,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(browseAlbums, key = { "browse-album-${it.id.value}" }) { album ->
                                AlbumCard(
                                    title = album.title,
                                    subtitle = album.artistName,
                                    artworkPath = album.artworkPath,
                                    onClick = { unfocusSearchField(); onAlbumClick(album.id) },
                                )
                            }
                        }
                    }
                }
                if (browseArtists.isNotEmpty()) {
                    item { SectionHeader("Артисты") }
                    items(browseArtists, key = { "browse-artist-${it.id.value}" }) { artist ->
                        ArtistRow(
                            name = artist.name,
                            photoPath = artist.photoPath,
                            onClick = { unfocusSearchField(); onArtistClick(artist.id) },
                        )
                    }
                }
                if (uiState.recentQueries.isEmpty() && browseAlbums.isEmpty() && browseArtists.isEmpty()) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(top = 80.dp), contentAlignment = Alignment.Center) {
                            Text(text = "Начните вводить, чтобы искать", color = NamiColors.Paper40)
                        }
                    }
                }
            }
        } else if (uiState.results.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text = "Ничего не нашлось", color = NamiColors.Paper40)
            }
        } else {
            LazyColumn(state = resultsListState) {
                if (tracks.isNotEmpty()) {
                    item {
                        SectionHeader(
                            title = "Треки",
                            onMoreClick = { expandedSection = SearchSection.TRACKS }.takeIf { tracks.size > TRACKS_PREVIEW },
                        )
                    }
                    items(tracks.take(TRACKS_PREVIEW), key = { "track-${it.id.value}" }) { track ->
                        TrackResultRow(
                            track = track,
                            onClick = { unfocusSearchField(); onTrackClick(track.id) },
                            onAddToPlaylist = { addToPlaylistTrackId = track.id },
                        )
                    }
                }
                if (albums.isNotEmpty()) {
                    item {
                        SectionHeader(
                            title = "Альбомы",
                            onMoreClick = { expandedSection = SearchSection.ALBUMS }.takeIf { albums.size > ALBUMS_PREVIEW },
                        )
                    }
                    item {
                        LazyRow(
                            contentPadding = PaddingValuesHorizontal20,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(albums.take(ALBUMS_PREVIEW), key = { "album-${it.id.value}" }) { album ->
                                AlbumResultCard(album = album, onClick = { unfocusSearchField(); onAlbumClick(album.id) })
                            }
                        }
                    }
                }
                if (artists.isNotEmpty()) {
                    item {
                        SectionHeader(
                            title = "Артисты",
                            onMoreClick = { expandedSection = SearchSection.ARTISTS }.takeIf { artists.size > ARTISTS_PREVIEW },
                        )
                    }
                    items(artists.take(ARTISTS_PREVIEW), key = { "artist-${it.id.value}" }) { artist ->
                        ArtistResultRow(artist = artist, onClick = { unfocusSearchField(); onArtistClick(artist.id) })
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    addToPlaylistTrackId?.let { trackId ->
        AddToPlaylistDialog(trackIds = setOf(trackId), onDismiss = { addToPlaylistTrackId = null })
    }

    expandedSection?.let { section ->
        ExpandedSectionScreen(
            section = section,
            tracks = tracks,
            albums = albums,
            artists = artists,
            onBack = { expandedSection = null },
            onTrackClick = onTrackClick,
            onAlbumClick = onAlbumClick,
            onArtistClick = onArtistClick,
            onAddToPlaylist = { addToPlaylistTrackId = it },
        )
    }
}

/** "Больше" opens this instead of a separate nav destination -- it's the exact same result data
 * already in memory, just without the preview cap, so there's nothing to navigate to/load. */
@Composable
private fun ExpandedSectionScreen(
    section: SearchSection,
    tracks: List<SearchResult.TrackResult>,
    albums: List<SearchResult.AlbumResult>,
    artists: List<SearchResult.ArtistResult>,
    onBack: () -> Unit,
    onTrackClick: (TrackId) -> Unit,
    onAlbumClick: (AlbumId) -> Unit,
    onArtistClick: (ArtistId) -> Unit,
    onAddToPlaylist: (TrackId) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize().background(NamiColors.Ink900)) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding().displayCutoutPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Outlined.ArrowBack, contentDescription = "Назад", tint = NamiColors.Paper100)
                }
                Text(
                    text = when (section) {
                        SearchSection.TRACKS -> "Треки"
                        SearchSection.ALBUMS -> "Альбомы"
                        SearchSection.ARTISTS -> "Артисты"
                    },
                    color = NamiColors.Paper100,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            when (section) {
                SearchSection.TRACKS -> LazyColumn {
                    items(tracks, key = { it.id.value }) { track ->
                        TrackResultRow(track = track, onClick = { onTrackClick(track.id) }, onAddToPlaylist = { onAddToPlaylist(track.id) })
                    }
                }
                SearchSection.ALBUMS -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 140.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    gridItems(albums, key = { it.id.value }) { album ->
                        AlbumResultCard(album = album, onClick = { onAlbumClick(album.id) })
                    }
                }
                SearchSection.ARTISTS -> LazyColumn {
                    items(artists, key = { it.id.value }) { artist ->
                        ArtistResultRow(artist = artist, onClick = { onArtistClick(artist.id) })
                    }
                }
            }
        }
    }
}

private val PaddingValuesHorizontal20 = PaddingValues(horizontal = 20.dp)

@Composable
private fun SectionHeader(title: String, onMoreClick: (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            color = NamiColors.Paper40,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.weight(1f),
        )
        if (onMoreClick != null) {
            Text(
                text = "Больше",
                color = NamiColors.Shu,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.clickable(onClick = onMoreClick).padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun TrackResultRow(track: SearchResult.TrackResult, onClick: () -> Unit, onAddToPlaylist: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)).background(NamiColors.Ink700),
        ) {
            if (track.artworkPath != null) {
                AsyncImage(model = track.artworkPath, contentDescription = null, modifier = Modifier.fillMaxSize())
            }
        }
        Column(modifier = Modifier.weight(1f).padding(start = 14.dp)) {
            Text(text = track.title, color = NamiColors.Paper100, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            track.artistName?.let { name ->
                Text(text = name, color = NamiColors.Paper70, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        IconButton(onClick = onAddToPlaylist) {
            Icon(Icons.Outlined.LibraryAdd, contentDescription = "В плейлист", tint = NamiColors.Paper70)
        }
    }
}

@Composable
private fun AlbumResultCard(album: SearchResult.AlbumResult, onClick: () -> Unit) {
    AlbumCard(title = album.title, subtitle = album.artistName, artworkPath = album.artworkPath, onClick = onClick)
}

@Composable
private fun AlbumCard(title: String, subtitle: String?, artworkPath: String?, onClick: () -> Unit) {
    Column(modifier = Modifier.width(140.dp).clickable(onClick = onClick)) {
        Box(
            modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(NamiColors.Ink700),
        ) {
            if (artworkPath != null) {
                AsyncImage(model = artworkPath, contentDescription = null, modifier = Modifier.fillMaxSize())
            }
        }
        Text(
            text = title,
            color = NamiColors.Paper100,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        subtitle?.let { name ->
            Text(text = name, color = NamiColors.Paper70, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ArtistResultRow(artist: SearchResult.ArtistResult, onClick: () -> Unit) {
    ArtistRow(name = artist.name, photoPath = artist.photoPath, onClick = onClick)
}

@Composable
private fun ArtistRow(name: String, photoPath: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(44.dp).clip(CircleShape).background(NamiColors.Ink700),
            contentAlignment = Alignment.Center,
        ) {
            if (photoPath != null) {
                AsyncImage(model = photoPath, contentDescription = null, modifier = Modifier.fillMaxSize().clip(CircleShape))
            } else {
                Icon(Icons.Outlined.Person, contentDescription = null, tint = NamiColors.Paper40)
            }
        }
        Text(
            text = name,
            color = NamiColors.Paper100,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 14.dp),
        )
    }
}

/** Material3's TextField enforces a ~56dp min touch target no matter how padding is tweaked --
 * a plain BasicTextField in a fixed-height row is the only way to actually get a compact bar. */
@Composable
private fun CompactSearchField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .height(44.dp)
            .background(NamiColors.Ink800, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Search, contentDescription = null, tint = NamiColors.Paper70, modifier = Modifier.size(20.dp))
        Box(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
            if (value.isEmpty()) {
                Text("Поиск треков, альбомов, исполнителей", color = NamiColors.Paper40, style = MaterialTheme.typography.bodyMedium)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = NamiColors.Paper100),
                cursorBrush = SolidColor(NamiColors.Shu),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
