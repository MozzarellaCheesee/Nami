package dev.nami.core.designsystem

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

data class ContextAction(val label: String, val icon: ImageVector, val onClick: () -> Unit)

/**
 * Slide-up sheet for a "..." menu -- the app-wide replacement for a plain
 * [androidx.compose.material3.DropdownMenu]. Each target type (track/album/playlist/artist)
 * supplies its own [actions] list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContextActionSheet(onDismiss: () -> Unit, actions: List<ContextAction>) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        ImmersiveSheetEffect()

        Column(modifier = Modifier.padding(bottom = 20.dp)) {
            actions.forEach { action ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onDismiss(); action.onClick() }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(action.icon, contentDescription = null, tint = NamiColors.Paper100)
                    Text(
                        text = action.label,
                        color = NamiColors.Paper100,
                        modifier = Modifier.padding(start = 20.dp),
                    )
                }
            }
        }
    }
}
