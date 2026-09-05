package dev.nami.app.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
fun NamiBottomBar(currentRoute: String?, onTabSelected: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(NamiColors.Ink900),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        TABS.forEach { tab ->
            val isActive = tab.route == currentRoute
            val tint = if (isActive) NamiColors.Shu else NamiColors.Paper70
            Column(
                modifier = Modifier
                    .clickable { onTabSelected(tab.route) },
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(tab.icon, contentDescription = tab.label, tint = tint, modifier = Modifier.height(24.dp))
                Text(text = tab.label, color = tint, fontSize = 10.sp)
            }
        }
    }
}
