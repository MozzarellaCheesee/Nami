package dev.nami.feature.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nami.data.NamiServerClient
import dev.nami.domain.SettingsRepository
import dev.nami.ui.theme.NamiColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject

// ponytail: минимальная data class для серверного трека
data class ServerTrack(
    val id: Long,
    val artist: String,
    val title: String,
    val album: String?,
)

@HiltViewModel
class ServerLibraryViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    var tracks by mutableStateOf<List<ServerTrack>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    fun load() {
        if (loading) return
        viewModelScope.launch {
            loading = true
            error = null
            try {
                val cfg = serverConfig()
                if (cfg == null) {
                    error = "Сервер не подключён"
                    return@launch
                }
                val result = withContext(Dispatchers.IO) {
                    NamiServerClient.tracks(cfg, limit = 500, offset = 0)
                }
                if (result == null) {
                    error = "Не удалось загрузить треки"
                    return@launch
                }
                tracks = (0 until result.length()).mapNotNull { i ->
                    parseTrack(result.optJSONObject(i))
                }
            } finally {
                loading = false
            }
        }
    }

    // ponytail: минимальный парсер
    private fun parseTrack(o: JSONObject?): ServerTrack? {
        o ?: return null
        val id = o.optLong("id", -1)
        if (id < 0) return null
        return ServerTrack(
            id = id,
            artist = o.optString("artist", ""),
            title = o.optString("title", ""),
            album = o.optString("album").takeIf { it.isNotBlank() },
        )
    }

    private fun serverConfig(): NamiServerClient.Config? {
        if (!settingsRepository.namiServerPreferred.value) return null
        val urls = settingsRepository.namiServerUrl.value?.split("\n")?.filter { it.isNotBlank() } ?: return null
        val token = settingsRepository.namiServerToken.value ?: return null
        val cert = settingsRepository.namiServerCertSha256.value
        return NamiServerClient.Config(urls.first(), token, cert, urls)
    }
}

@Composable
fun ServerLibraryScreen(
    onBack: () -> Unit,
    viewModel: ServerLibraryViewModel = androidx.hilt.navigation.compose.hiltViewModel(),
) {
    LaunchedEffect(Unit) { viewModel.load() }

    Column(modifier = Modifier.fillMaxSize()) {
        // Header
        dev.nami.ui.components.TopBar(
            title = { Text("Серверная библиотека") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Outlined.ArrowBack, contentDescription = "Назад")
                }
            },
        )

        when {
            viewModel.loading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = NamiColors.Shu)
            }
            viewModel.error != null -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(viewModel.error ?: "", color = NamiColors.Paper70)
            }
            viewModel.tracks.isEmpty() -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Нет треков на сервере", color = NamiColors.Paper70)
            }
            else -> LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(viewModel.tracks) { track ->
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(track.title, color = NamiColors.Paper100, style = MaterialTheme.typography.bodyLarge)
                        Text(track.artist, color = NamiColors.Paper70, style = MaterialTheme.typography.bodySmall)
                        track.album?.let {
                            Text(it, color = NamiColors.Paper40, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
