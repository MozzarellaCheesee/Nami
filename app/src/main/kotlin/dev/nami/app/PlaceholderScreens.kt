package dev.nami.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.nami.core.designsystem.NamiColors

@Composable
fun PlaylistsPlaceholderScreen() {
    Box(modifier = Modifier.fillMaxSize().background(NamiColors.Ink900), contentAlignment = Alignment.Center) {
        Text(text = "Плейлисты — скоро", color = NamiColors.Paper70)
    }
}

@Composable
fun SettingsPlaceholderScreen() {
    Box(modifier = Modifier.fillMaxSize().background(NamiColors.Ink900), contentAlignment = Alignment.Center) {
        Text(text = "Настройки — скоро", color = NamiColors.Paper70)
    }
}
