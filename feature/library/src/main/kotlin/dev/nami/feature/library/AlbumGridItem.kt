package dev.nami.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.nami.core.designsystem.NamiColors
import dev.nami.core.model.AlbumSummary

@Composable
fun AlbumGridItem(
    album: AlbumSummary,
    onClick: () -> Unit,
    onMoreClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().clickable(onClick = onClick)) {
        if (album.artworkPath != null) {
            AsyncImage(
                model = album.artworkPath,
                contentDescription = album.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .background(NamiColors.Ink700, RoundedCornerShape(4.dp)),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .background(NamiColors.Ink700, RoundedCornerShape(4.dp)),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = album.title,
                color = NamiColors.Paper100,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (onMoreClick != null) {
                IconButton(onClick = onMoreClick, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Действия с альбомом", tint = NamiColors.Paper40)
                }
            }
        }
        Text(
            text = album.artistName ?: "",
            color = NamiColors.Paper70,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
