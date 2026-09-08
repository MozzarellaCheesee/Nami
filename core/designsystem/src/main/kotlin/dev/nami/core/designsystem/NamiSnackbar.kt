package dev.nami.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Dark-card snackbar matching the rest of the app's palette instead of Material3's default
 * light/grey theming (Design mock 4.27 - "Трек удалён из очереди" / "Отменить" in Shu). Swap-in
 * replacement for [SnackbarHost] wherever a Scaffold declares one. */
@Composable
fun NamiSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(hostState, modifier = modifier) { data ->
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth()
                .background(NamiColors.Ink800, RoundedCornerShape(NamiRadius.Button))
                .padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 14.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = data.visuals.message,
                color = NamiColors.Paper100,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            data.visuals.actionLabel?.let { label ->
                TextButton(onClick = { data.performAction() }) {
                    Text(label, color = NamiColors.Shu, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
