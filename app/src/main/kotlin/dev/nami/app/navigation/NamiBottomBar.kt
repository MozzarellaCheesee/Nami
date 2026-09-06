package dev.nami.app.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.QueueMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nami.core.designsystem.NamiColors

private data class BottomTab(val route: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    BottomTab("library", "Библиотека", Icons.Outlined.LibraryMusic),
    BottomTab("search", "Поиск", Icons.Outlined.Search),
    BottomTab("playlists", "Плейлисты", Icons.Outlined.QueueMusic),
    BottomTab("settings", "Настройки", Icons.Outlined.Settings),
)

@Composable
fun NamiBottomBar(currentRoute: String?, onTabSelected: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(
        // background must come BEFORE (outer of) the incoming `modifier` (navigationBarsPadding):
        // a draw modifier paints the node's final resolved size regardless of where it sits in
        // the chain, so putting it first here means it also covers the padding inset added by
        // `modifier` -- background-after-padding only paints the inner content, leaving the
        // inset transparent and dependent on whatever happens to be drawn behind it.
        modifier = Modifier
            .background(NamiColors.Ink900)
            .then(modifier)
            .fillMaxWidth()
            .height(56.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        TABS.forEach { tab ->
            val isActive = tab.route == currentRoute
            val iconTint = if (isActive) NamiColors.Shu else NamiColors.Paper70
            val labelColor = if (isActive) NamiColors.Shu else NamiColors.Paper40
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable { onTabSelected(tab.route) }
                    .padding(top = 10.dp),
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top,
            ) {
                Icon(tab.icon, contentDescription = tab.label, tint = iconTint, modifier = Modifier.size(24.dp))
                Text(text = tab.label, color = labelColor, fontSize = 10.sp)
            }
        }
    }
}
