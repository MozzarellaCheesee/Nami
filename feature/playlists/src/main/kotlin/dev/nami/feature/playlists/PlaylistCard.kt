package dev.nami.feature.playlists

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.nami.core.designsystem.LikedPlaylistCover
import dev.nami.core.designsystem.NamiColors
import dev.nami.core.designsystem.NamiRadius
import dev.nami.core.designsystem.NamiType
import dev.nami.core.model.PlaylistSummary

@Composable
fun PlaylistCard(playlist: PlaylistSummary, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth().clickable(onClick = onClick)) {
        when {
            playlist.isLiked -> LikedPlaylistCover(
                modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(NamiRadius.AlbumArt)),
            )
            playlist.coverPath != null -> AsyncImage(
                model = playlist.coverPath,
                contentDescription = playlist.name,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .background(NamiColors.Ink700, RoundedCornerShape(NamiRadius.AlbumArt)),
            )
            else -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .background(NamiColors.Ink700, RoundedCornerShape(NamiRadius.AlbumArt)),
            )
        }
        Text(
            text = playlist.name,
            color = NamiColors.Paper100,
            style = NamiType.ListTitle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            // Smart playlists' tracks aren't rows in playlist_tracks (see SmartPlaylistEvaluator),
            // so the LEFT JOIN COUNT() backing trackCount is always 0 for them - showing that as
            // "0 треков" would read as a broken/empty playlist, not what it actually is.
            text = if (playlist.isSmart) "Умный плейлист" else "${playlist.trackCount} треков",
            // Умный плейлист отличался от обычного только словом в подписи - теперь ещё и цветом:
            // в сетке из двадцати карточек слово читается позже, чем оттенок.
            color = if (playlist.isSmart) NamiColors.Ai else NamiColors.Paper40,
            style = NamiType.Secondary,
        )
    }
}
