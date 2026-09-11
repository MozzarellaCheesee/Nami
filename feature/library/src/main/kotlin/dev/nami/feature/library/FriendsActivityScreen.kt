package dev.nami.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.nami.core.designsystem.NamiColors
import dev.nami.core.designsystem.NamiRadius
import dev.nami.core.model.TrackId
import dev.nami.domain.FriendNowPlaying
import dev.nami.domain.PlayableTrack
import dev.nami.domain.PlayerRepository
import dev.nami.domain.ServerAudioRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FriendsActivityViewModel @Inject constructor(
    private val playerRepository: PlayerRepository,
    private val serverAudioRepository: ServerAudioRepository,
) : ViewModel() {

    var friends by mutableStateOf<List<FriendNowPlaying>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var serverConfigured by mutableStateOf(false)
        private set

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            if (!serverAudioRepository.isServerActive()) {
                serverConfigured = false
                return@launch
            }
            serverConfigured = true
            loading = true
            error = null
            val result = serverAudioRepository.fetchFriendsNowPlaying()
            loading = false
            if (result != null) {
                friends = result
            } else {
                error = "Не удалось обновить статус с сервера"
            }
        }
    }

    fun playFriendTrack(item: FriendNowPlaying) {
        viewModelScope.launch {
            val streamUrl = serverAudioRepository.serverStreamUrl(item.trackId) ?: return@launch
            val track = PlayableTrack(
                id = TrackId("server_${item.trackId}"),
                title = item.title,
                artistName = item.artist,
                path = streamUrl,
                durationMs = 0L,
            )
            playerRepository.play(listOf(track), startIndex = 0, startMs = item.positionMs)
        }
    }
}

@Composable
fun FriendsActivityScreen(
    onBack: () -> Unit,
    viewModel: FriendsActivityViewModel = hiltViewModel(),
) {
    LaunchedEffect(Unit) {
        viewModel.refresh()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NamiColors.Ink900),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, start = 8.dp, end = 8.dp, bottom = 8.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Outlined.ArrowBack, contentDescription = "Назад", tint = NamiColors.Paper100)
            }
            Text(
                text = "Что слушают друзья",
                style = MaterialTheme.typography.titleLarge,
                color = NamiColors.Paper100,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
            )
            IconButton(onClick = viewModel::refresh) {
                Icon(Icons.Outlined.Refresh, contentDescription = "Обновить", tint = NamiColors.Paper70)
            }
        }

        when {
            viewModel.loading && viewModel.friends.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = NamiColors.Shu)
                }
            }
            !viewModel.serverConfigured -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Сервер NAMI не подключён.\nПодключите сервер в Настройки → Сервер, чтобы видеть активность друзей.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = NamiColors.Paper40,
                    )
                }
            }
            viewModel.friends.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Никто из друзей прямо сейчас не слушает музыку.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = NamiColors.Paper40,
                    )
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                ) {
                    items(viewModel.friends, key = { it.userId }) { friend ->
                        FriendActivityCard(
                            friend = friend,
                            onPlay = { viewModel.playFriendTrack(friend) },
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun FriendActivityCard(
    friend: FriendNowPlaying,
    onPlay: () -> Unit,
) {
    val initial = friend.username.take(1).uppercase()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(NamiRadius.Card))
            .background(NamiColors.Ink800)
            .clickable(onClick = onPlay)
            .padding(16.dp),
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(NamiColors.Shu),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = initial,
                style = MaterialTheme.typography.titleMedium,
                color = NamiColors.Paper100,
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = friend.username,
                style = MaterialTheme.typography.bodyLarge,
                color = NamiColors.Paper100,
            )
            val sub = if (friend.artist.isNullOrBlank()) friend.title else "${friend.title} • ${friend.artist}"
            Text(
                text = sub,
                style = MaterialTheme.typography.bodySmall,
                color = NamiColors.Paper70,
                maxLines = 1,
            )
            val secAgo = (System.currentTimeMillis() / 1000 - friend.updatedAt).coerceAtLeast(0)
            val timeText = if (secAgo < 60) "слушает прямо сейчас" else "${secAgo / 60} мин. назад"
            Text(
                text = timeText,
                style = MaterialTheme.typography.labelSmall,
                color = NamiColors.Paper40,
            )
        }
        IconButton(onClick = onPlay) {
            Icon(
                Icons.Outlined.PlayArrow,
                contentDescription = "Слушать",
                tint = NamiColors.Shu,
                modifier = Modifier.size(32.dp),
            )
        }
    }
}
