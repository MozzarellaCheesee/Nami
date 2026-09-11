package dev.nami.feature.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.nami.core.designsystem.NamiColors
import dev.nami.core.designsystem.NamiPill
import dev.nami.core.designsystem.NamiPillRow
import dev.nami.core.designsystem.NamiRadius
import dev.nami.core.designsystem.NamiType
import dev.nami.domain.NetworkImportSource
import dev.nami.domain.NetworkTrack

/** Подписи источников: короткое имя для чипа и одна строка про то, чем этот источник вообще
 * отличается - иначе выбор между тремя чипами ничем не обоснован. */
private fun sourceLabel(source: NetworkImportSource) = when (source) {
    NetworkImportSource.AUDIUS -> "Свободная музыка"
    NetworkImportSource.ARCHIVE -> "Lossless-архив"
    NetworkImportSource.PIPED -> "Весь мир"
    NetworkImportSource.JAMENDO -> "Creative Commons"
    NetworkImportSource.BANDCAMP -> "Bandcamp"
    NetworkImportSource.SOUNDCLOUD -> "SoundCloud"
    NetworkImportSource.VK -> "VK Музыка"
}

private fun sourceHint(source: NetworkImportSource) = when (source) {
    NetworkImportSource.AUDIUS -> "Audius - артисты сами разрешили раздачу. Инди и электроника, MP3 320."
    NetworkImportSource.ARCHIVE -> "Internet Archive - оцифровки и концерты, часто в чистом FLAC."
    NetworkImportSource.PIPED -> "YouTube через Piped - не гарантированно доступно всегда."
    NetworkImportSource.JAMENDO -> "Jamendo - каталог под Creative Commons. Нужен свой client_id в настройках."
    NetworkImportSource.BANDCAMP -> "Bandcamp - только то, что артист отдаёт бесплатно. Разбор страницы, может сломаться."
    NetworkImportSource.SOUNDCLOUD -> "SoundCloud - скачивается лишь то, что автор разрешил скачивать. Нужен client_id в настройках."
    NetworkImportSource.VK -> "VK Музыка - аудиозаписи ВКонтакте. Нужен токен доступа (Kate Mobile / VK Admin) в настройках."
}

/** Вкладка "В сети" - поиск и скачивание из открытых источников. Поле ввода общее с локальным
 * поиском и живёт в SearchScreen, сюда приходит уже готовое состояние. */
@Composable
internal fun NetworkSearchContent(
    state: NetworkSearchUiState,
    onSourceChange: (NetworkImportSource) -> Unit,
    onDownload: (NetworkTrack) -> Unit,
    onDismissMessage: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        NamiPillRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
            NetworkImportSource.entries.forEach { source ->
                NamiPill(
                    text = sourceLabel(source),
                    selected = state.source == source,
                    onClick = { onSourceChange(source) },
                )
            }
        }
        Text(
            text = sourceHint(state.source),
            color = NamiColors.Paper40,
            style = NamiType.Secondary,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        )
        state.message?.let { message ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp)
                    .background(NamiColors.Ink800, RoundedCornerShape(NamiRadius.Card))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(message, color = NamiColors.Shu, style = NamiType.Secondary, modifier = Modifier.weight(1f))
                NamiPill(text = "Ок", onClick = onDismissMessage)
            }
        }

        when {
            state.loading && state.results.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = NamiColors.Shu)
            }
            state.clientIdMissing -> NetworkPlaceholder(
                title = "Нужен client_id",
                hint = "Настройки → Связь → Источники в сети: там поле для этого источника и инструкция, " +
                    "как получить ключ. Без него источник не ищет.",
                offline = true,
            )
            state.query.isBlank() -> NetworkPlaceholder(
                title = "Поиск в сети",
                hint = "Набери название трека или исполнителя - результаты придут из выбранного источника",
            )
            state.searched && state.results.isEmpty() -> NetworkPlaceholder(
                title = "Ничего не нашлось",
                hint = "Попробуй другой источник или другой запрос - источник мог быть и просто недоступен",
                offline = true,
            )
            else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(state.results, key = { trackKey(it) }) { track ->
                    val key = trackKey(track)
                    NetworkTrackRow(
                        track = track,
                        downloading = key in state.downloading,
                        imported = key in state.imported,
                        onDownload = { onDownload(track) },
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun NetworkTrackRow(track: NetworkTrack, downloading: Boolean, imported: Boolean, onDownload: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(48.dp).clip(RoundedCornerShape(NamiRadius.AlbumArt)).background(NamiColors.Ink700)) {
            if (track.artworkUrl != null) {
                AsyncImage(model = track.artworkUrl, contentDescription = null, modifier = Modifier.fillMaxSize())
            }
        }
        Column(modifier = Modifier.weight(1f).padding(start = 14.dp)) {
            Text(track.title, color = NamiColors.Paper100, style = NamiType.TrackTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val subtitle = listOfNotNull(
                track.artistName,
                track.durationSec?.let { "%d:%02d".format(it / 60, it % 60) },
                track.detail,
            ).joinToString(" · ")
            if (subtitle.isNotEmpty()) {
                Text(subtitle, color = NamiColors.Paper40, style = NamiType.Secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        when {
            downloading -> CircularProgressIndicator(color = NamiColors.Shu, modifier = Modifier.size(20.dp))
            imported -> Icon(Icons.Outlined.Check, contentDescription = "В библиотеке", tint = NamiColors.Paper40)
            else -> IconButton(onClick = onDownload) {
                Icon(Icons.Outlined.CloudDownload, contentDescription = "Скачать", tint = NamiColors.Shu)
            }
        }
    }
}

@Composable
private fun NetworkPlaceholder(title: String, hint: String, offline: Boolean = false) {
    Box(modifier = Modifier.fillMaxSize().padding(top = 48.dp), contentAlignment = Alignment.TopCenter) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 40.dp),
        ) {
            Icon(
                if (offline) Icons.Outlined.CloudOff else Icons.Outlined.Public,
                contentDescription = null,
                tint = NamiColors.Ink500,
                modifier = Modifier.size(48.dp),
            )
            Text(title, color = NamiColors.Paper70, style = NamiType.TrackTitle, modifier = Modifier.padding(top = 10.dp))
            Text(
                hint,
                color = NamiColors.Paper40,
                style = NamiType.Secondary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}
