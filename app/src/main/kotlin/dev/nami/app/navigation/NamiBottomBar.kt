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
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
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
    BottomTab("library", "Библиотека", Icons.Filled.LibraryMusic),
    BottomTab("search", "Поиск", Icons.Filled.Search),
    BottomTab("playlists", "Плейлисты", Icons.Filled.QueueMusic),
    BottomTab("settings", "Настройки", Icons.Filled.Settings),
)

@Composable
fun NamiBottomBar(currentRoute: String?, onTabSelected: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(
        // background first (outer) so it paints the full resolved size of the chain below,
        // including the navigationBarsPadding inset the caller passes in via `modifier` --
        // background-after-padding only paints the inner 48dp content, leaving that inset
        // transparent and dependent on whatever's drawn behind it lining up in the same color.
        modifier = Modifier
            .background(NamiColors.Ink900)
            .then(modifier)
            .fillMaxWidth()
            .height(48.dp),
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
                    .padding(top = 6.dp),
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(tab.icon, contentDescription = tab.label, tint = iconTint, modifier = Modifier.size(24.dp))
                Text(text = tab.label, color = labelColor, fontSize = 10.sp)
            }
        }
    }
}
