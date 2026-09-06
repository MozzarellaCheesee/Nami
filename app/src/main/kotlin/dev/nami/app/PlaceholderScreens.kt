package dev.nami.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.nami.core.designsystem.NamiColors

@Composable
fun SettingsScreen(onTrashClick: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val autoOpenPlayer by viewModel.autoOpenPlayer.collectAsState()
    val context = LocalContext.current
    var selectedIcon by remember { mutableStateOf(IconPicker.current(context)) }

    Column(modifier = Modifier.fillMaxSize().background(NamiColors.Ink900)) {
        Text(
            text = "Настройки",
            color = NamiColors.Paper100,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
        )

        SettingsSectionLabel("Иконка приложения")
        SettingsCard(modifier = Modifier.padding(horizontal = 20.dp)) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxWidth().height(if (LauncherIcon.entries.size > 3) 240.dp else 120.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(LauncherIcon.entries) { icon ->
                    IconChoice(
                        icon = icon,
                        selected = icon == selectedIcon,
                        onClick = {
                            IconPicker.select(context, icon)
                            selectedIcon = icon
                        },
                    )
                }
            }
        }

        SettingsSectionLabel("Плеер")
        SettingsCard(modifier = Modifier.padding(horizontal = 20.dp)) {
            SettingsRow(
                icon = Icons.Outlined.PlayCircleOutline,
                title = "Открывать плеер при выборе трека",
                trailing = {
                    Switch(
                        checked = autoOpenPlayer,
                        onCheckedChange = viewModel::setAutoOpenPlayer,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = NamiColors.Shu,
                            checkedThumbColor = NamiColors.Paper100,
                            uncheckedTrackColor = NamiColors.Ink600,
                            uncheckedThumbColor = NamiColors.Paper70,
                        ),
                    )
                },
                onClick = { viewModel.setAutoOpenPlayer(!autoOpenPlayer) },
            )
        }

        SettingsSectionLabel("Хранилище")
        SettingsCard(modifier = Modifier.padding(horizontal = 20.dp)) {
            SettingsRow(
                icon = Icons.Outlined.Delete,
                title = "Корзина",
                trailing = { Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = NamiColors.Paper40) },
                onClick = onTrashClick,
            )
        }
    }
}

@Composable
private fun SettingsSectionLabel(text: String) {
    Text(
        text = text,
        color = NamiColors.Paper40,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(start = 24.dp, top = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun SettingsCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(NamiColors.Ink800, RoundedCornerShape(16.dp))
            .padding(4.dp),
        content = content,
    )
}

@Composable
private fun SettingsRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    trailing: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = NamiColors.Paper70, modifier = Modifier.padding(end = 16.dp))
        Text(text = title, color = NamiColors.Paper100, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun IconChoice(icon: LauncherIcon, selected: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        androidx.compose.foundation.Image(
            painter = painterResource(icon.previewRes),
            contentDescription = icon.label,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .aspectRatio(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .then(
                    if (selected) {
                        Modifier.border(2.dp, NamiColors.Shu, RoundedCornerShape(18.dp))
                    } else {
                        Modifier
                    },
                ),
        )
        Text(
            text = icon.label,
            color = if (selected) NamiColors.Shu else NamiColors.Paper70,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
