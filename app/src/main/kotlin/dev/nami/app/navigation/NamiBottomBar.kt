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
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.InsertChart
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.MenuBook
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
import dev.nami.domain.BottomTab

/** Подпись и иконка вкладки - единственное, что панель знает сверх роута из [BottomTab]. */
fun bottomTabLabel(tab: BottomTab): String = when (tab) {
    BottomTab.HOME -> "Главная"
    BottomTab.LIBRARY -> "Библиотека"
    BottomTab.SEARCH -> "Поиск"
    BottomTab.PLAYLISTS -> "Плейлисты"
    BottomTab.SETTINGS -> "Настройки"
    BottomTab.STATS -> "Статистика"
    BottomTab.VOCABULARY -> "Словарь"
    BottomTab.FOLDERS -> "Папки"
}

fun bottomTabIcon(tab: BottomTab): ImageVector = when (tab) {
    BottomTab.HOME -> Icons.Outlined.Home
    BottomTab.LIBRARY -> Icons.Outlined.LibraryMusic
    BottomTab.SEARCH -> Icons.Outlined.Search
    BottomTab.PLAYLISTS -> Icons.Outlined.QueueMusic
    BottomTab.SETTINGS -> Icons.Outlined.Settings
    BottomTab.STATS -> Icons.Outlined.InsertChart
    BottomTab.VOCABULARY -> Icons.Outlined.MenuBook
    BottomTab.FOLDERS -> Icons.Outlined.Folder
}

@Composable
fun NamiBottomBar(
    tabs: List<BottomTab>,
    showLabels: Boolean,
    currentRoute: String?,
    onTabSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        // background must come BEFORE (outer of) the incoming `modifier` (navigationBarsPadding):
        // a draw modifier paints the node's final resolved size regardless of where it sits in
        // the chain, so putting it first here means it also covers the padding inset added by
        // `modifier` - background-after-padding only paints the inner content, leaving the
        // inset transparent and dependent on whatever happens to be drawn behind it.
        modifier = Modifier
            .background(NamiColors.Ink900)
            .then(modifier)
            .fillMaxWidth()
            .height(if (showLabels) 56.dp else 48.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        tabs.forEach { tab ->
            val isActive = tab.route == currentRoute
            val iconTint = if (isActive) NamiColors.Shu else NamiColors.Paper70
            val labelColor = if (isActive) NamiColors.Shu else NamiColors.Paper40
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable { onTabSelected(tab.route) }
                    .padding(top = if (showLabels) 10.dp else 0.dp),
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                verticalArrangement = if (showLabels) Arrangement.Top else Arrangement.Center,
            ) {
                Icon(bottomTabIcon(tab), contentDescription = bottomTabLabel(tab), tint = iconTint, modifier = Modifier.size(24.dp))
                if (showLabels) Text(text = bottomTabLabel(tab), color = labelColor, fontSize = 10.sp)
            }
        }
    }
}
