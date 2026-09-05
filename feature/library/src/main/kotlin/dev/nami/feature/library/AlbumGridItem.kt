package dev.nami.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.nami.core.designsystem.NamiColors
import dev.nami.core.model.AlbumSummary

@Composable
fun AlbumGridItem(album: AlbumSummary, onClick: () -> Unit, modifier: Modifier = Modifier) {
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
        Text(
            text = album.title,
            color = NamiColors.Paper100,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = album.artistName ?: "",
            color = NamiColors.Paper70,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
