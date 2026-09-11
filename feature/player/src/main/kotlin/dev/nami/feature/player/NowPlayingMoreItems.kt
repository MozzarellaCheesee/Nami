package dev.nami.feature.player

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.QueueMusic
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import dev.nami.core.designsystem.NamiColors
import dev.nami.domain.NowPlayingMoreAccent
import dev.nami.domain.NowPlayingMoreItem

/** Подпись и иконка пункта меню "Ещё" - одно место на два экрана: сам лист на Now Playing и
 * его редактор в настройках. Раньше подпись была ещё и ключом (по ней маппился цвет), теперь
 * ключ - [NowPlayingMoreItem], а подпись можно менять свободно. */
fun nowPlayingMoreLabel(item: NowPlayingMoreItem): String = when (item) {
    NowPlayingMoreItem.CAST -> "Трансляция"
    NowPlayingMoreItem.SHARE_CARD -> "Поделиться карточкой"
    NowPlayingMoreItem.RADIO -> "Радио"
    NowPlayingMoreItem.ADD_TO_PLAYLIST -> "В плейлист"
    NowPlayingMoreItem.DRIVE_MODE -> "Дорожный режим"
    NowPlayingMoreItem.AUDIO_TRACT -> "Аудиотракт"
    NowPlayingMoreItem.SLEEP_TIMER -> "Таймер сна"
    NowPlayingMoreItem.SHARE_OVER_NETWORK -> "Поделиться треком по сети"
    NowPlayingMoreItem.LISTEN_TOGETHER -> "Слушать со мной"
    NowPlayingMoreItem.JAM -> "Джем"
    NowPlayingMoreItem.SHARE -> "Поделиться"
    NowPlayingMoreItem.MOMENTS -> "Моменты и петли"
    NowPlayingMoreItem.TRACK_INFO -> "Информация о треке"
    NowPlayingMoreItem.OPEN_ARTIST -> "Открыть исполнителя"
    NowPlayingMoreItem.OPEN_ALBUM -> "Открыть альбом"
    NowPlayingMoreItem.PLAYER_SETTINGS -> "Настройки плеера"
    NowPlayingMoreItem.THEME_EDITOR -> "Редактор темы"
    NowPlayingMoreItem.ALL_SETTINGS -> "Все настройки"
}

fun nowPlayingMoreIcon(item: NowPlayingMoreItem): ImageVector = when (item) {
    NowPlayingMoreItem.CAST -> Icons.Outlined.Cast
    NowPlayingMoreItem.SHARE_CARD -> Icons.Outlined.Share
    NowPlayingMoreItem.RADIO -> Icons.Outlined.PlayCircleOutline
    NowPlayingMoreItem.ADD_TO_PLAYLIST -> Icons.Outlined.PlaylistAdd
    NowPlayingMoreItem.DRIVE_MODE -> Icons.Outlined.DirectionsCar
    NowPlayingMoreItem.AUDIO_TRACT -> Icons.Outlined.QueueMusic
    NowPlayingMoreItem.SLEEP_TIMER -> Icons.Outlined.DarkMode
    NowPlayingMoreItem.SHARE_OVER_NETWORK -> Icons.Outlined.Send
    NowPlayingMoreItem.LISTEN_TOGETHER -> Icons.Outlined.Groups
    NowPlayingMoreItem.JAM -> Icons.Outlined.QueueMusic
    NowPlayingMoreItem.SHARE -> Icons.Outlined.Share
    NowPlayingMoreItem.MOMENTS -> Icons.Outlined.Repeat
    NowPlayingMoreItem.TRACK_INFO -> Icons.Outlined.Info
    NowPlayingMoreItem.OPEN_ARTIST -> Icons.Outlined.Person
    NowPlayingMoreItem.OPEN_ALBUM -> Icons.Outlined.Album
    NowPlayingMoreItem.PLAYER_SETTINGS -> Icons.Outlined.Tune
    NowPlayingMoreItem.THEME_EDITOR -> Icons.Outlined.Palette
    NowPlayingMoreItem.ALL_SETTINGS -> Icons.Outlined.Settings
}

/** Палитра акцентов ячеек сетки - только токены существующей темы, никакого произвольного
 * color picker: цвет ячейки обязан жить вместе с темой, а не мимо неё. */
fun nowPlayingMoreAccentColor(accent: NowPlayingMoreAccent): Color = when (accent) {
    NowPlayingMoreAccent.NEUTRAL -> NamiColors.Paper100
    NowPlayingMoreAccent.AI -> NamiColors.Ai
    NowPlayingMoreAccent.SHU -> NamiColors.Shu
    NowPlayingMoreAccent.WAKABA -> NamiColors.Wakaba
    NowPlayingMoreAccent.KIN -> NamiColors.Kin
}
