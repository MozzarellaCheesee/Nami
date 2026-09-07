package dev.nami.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush

/** Spotify-style "Любимые треки" cover -- a soft gradient with a filled heart, generated instead
 * of a real file so it can never go stale/missing and can't be replaced (that playlist's cover
 * is permanently locked -- see PlaylistRepositoryImpl.setCoverImage's isLiked guard). Draw this
 * wherever [dev.nami.core.model.Playlist.isLiked]/[dev.nami.core.model.PlaylistSummary.isLiked]
 * is true, instead of the normal coverPath AsyncImage. */
@Composable
fun LikedPlaylistCover(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(
                Brush.linearGradient(
                    colors = listOf(NamiColors.Kin, NamiColors.Shu),
                    start = Offset(0f, 0f),
                    end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Favorite,
            contentDescription = null,
            tint = NamiColors.Paper100,
            modifier = Modifier.fillMaxSize(0.5f),
        )
    }
}
