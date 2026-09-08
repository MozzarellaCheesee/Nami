package dev.nami.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** [keepParentOpen] - действие открывает своё окно поверх текущего листа, а не уводит с экрана
 * совсем (например Аудиотракт, Таймер сна из Now Playing) - родительский лист не закрывать, он
 * останется под новым окном и снова окажется на виду, когда то закроется. По умолчанию false -
 * старое поведение (закрыть лист, потом выполнить действие) не меняется там, где это не нужно. */
data class ContextAction(val label: String, val icon: ImageVector, val keepParentOpen: Boolean = false, val onClick: () -> Unit)

/**
 * Slide-up sheet for a "..." menu - the app-wide replacement for a plain
 * [androidx.compose.material3.DropdownMenu]. Each target type (track/album/playlist/artist)
 * supplies its own [actions] list. Every row gets its icon in a soft rounded chip instead of a
 * bare glyph (more visual weight, easier to scan a long list at a glance); a row whose label
 * starts with "Удалить" is picked out in Shu - the only warm/attention color in the palette,
 * same role it already plays for "Сохранить" links elsewhere, just applied here to the one
 * consequential action in these lists instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContextActionSheet(onDismiss: () -> Unit, actions: List<ContextAction>, header: (@Composable () -> Unit)? = null) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        ImmersiveSheetEffect()

        Column(modifier = Modifier.padding(bottom = 28.dp, top = 4.dp)) {
            header?.invoke()
            actions.forEach { action ->
                val isDestructive = action.label.startsWith("Удалить")
                val tint = if (isDestructive) NamiColors.Shu else NamiColors.Paper100
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { if (!action.keepParentOpen) onDismiss(); action.onClick() }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(
                                if (isDestructive) NamiColors.Shu.copy(alpha = 0.14f) else NamiColors.Ink700,
                                CircleShape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(action.icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
                    }
                    Text(
                        text = action.label,
                        color = tint,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(start = 16.dp),
                    )
                }
            }
        }
    }
}
