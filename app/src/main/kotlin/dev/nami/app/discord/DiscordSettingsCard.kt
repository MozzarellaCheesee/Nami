package dev.nami.app.discord

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import dev.nami.app.NamiSwitch
import dev.nami.app.SettingsCard
import dev.nami.app.SettingsRow
import dev.nami.app.SettingsViewModel
import dev.nami.domain.validDiscordApplicationId

@Composable
internal fun DiscordSettingsCard(viewModel: SettingsViewModel) {
    val id by viewModel.discordApplicationId.collectAsState()
    val enabled by viewModel.discordPresenceEnabled.collectAsState()
    val showMode by viewModel.discordShowMode.collectAsState()
    val showAppIcon by viewModel.discordShowAppIcon.collectAsState()
    val status by viewModel.discordStatus.collectAsState()
    var draft by remember(id) { mutableStateOf(id) }
    val valid = draft.isBlank() || validDiscordApplicationId(draft.trim())
    val uriHandler = LocalUriHandler.current
    val serverAccount by viewModel.discordServerAccount.collectAsState()
    val serverMessage by viewModel.discordServerMessage.collectAsState()
    val serverBusy by viewModel.discordServerBusy.collectAsState()
    val serverToken by viewModel.namiServerToken.collectAsState()
    val serverUrl by viewModel.namiServerUrl.collectAsState()
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(serverToken, serverUrl) { viewModel.discordServerAction() }
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) viewModel.discordServerAction()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    SettingsCard(Modifier.padding(horizontal = 20.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("Discord · учётная запись на сервере")
            Text("Каждый пользователь Nami подключает свой аккаунт Discord. Привязка доступна на его устройствах.")
            Text(when {
                serverBusy -> "Подождите…"
                serverAccount?.optBoolean("linked") == true -> "Подключён Discord: ${serverAccount?.optString("username")}"
                serverAccount?.optBoolean("configured") == false -> "Администратор ещё не настроил Discord OAuth на сервере"
                else -> "Discord не подключён"
            })
            if (serverAccount?.optBoolean("needs_reconnect") == true) Text("Настройки сервера изменились. Подключите Discord заново.")
            serverMessage?.let { Text(it) }
            TextButton(enabled = !serverBusy && !serverToken.isNullOrBlank(), onClick = {
                viewModel.discordServerAction("authorize") { uriHandler.openUri(it) }
            }) { Text("Подключить свой Discord") }
            TextButton(enabled = !serverBusy, onClick = { viewModel.discordServerAction() }) { Text("Обновить статус") }
            TextButton(enabled = !serverBusy && !serverToken.isNullOrBlank(), onClick = {
                viewModel.discordServerAction("disconnect")
            }) { Text("Отключить Discord / отменить привязку") }
            Text(if (serverAccount?.optBoolean("presence_supported") == true)
                "Музыка с телефона публикуется через сервер в вашем профиле Discord. Application ID на телефоне для этого не нужен."
                else "Для публикации администратору нужно настроить Discord Social SDK на сервере.")
            if (serverAccount?.optBoolean("linked") == true) Text(when (serverAccount?.optString("presence_status")) {
                "published" -> "Активность опубликована"
                "connecting" -> "Подключение к Discord…"
                "error" -> "Ошибка публикации. Сервер повторит попытку."
                else -> "Ожидает воспроизведения"
            })
        }
        SettingsRow(
            icon = Icons.Outlined.Image,
            title = "Значок приложения в активности",
            subtitle = "Маленькая иконка Nami поверх обложки трека",
            trailing = { NamiSwitch(showAppIcon, viewModel::setDiscordShowAppIcon) },
            onClick = { viewModel.setDiscordShowAppIcon(!showAppIcon) },
        )
    }
    SettingsCard(Modifier.padding(horizontal = 20.dp)) {
        SettingsRow(
            icon = Icons.Outlined.Share,
            title = "Прямое подключение к Discord на телефоне",
            subtitle = status,
            trailing = { NamiSwitch(enabled, viewModel::setDiscordPresenceEnabled) },
            onClick = { viewModel.setDiscordPresenceEnabled(!enabled) },
        )
        Column(Modifier.padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it.take(64) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Discord Application ID") },
                supportingText = { Text(if (valid) "ID вашего приложения Discord; токен аккаунта не нужен."
                    else "Введите числовой Application ID из Developer Portal.") },
                isError = !valid,
                singleLine = true,
            )
            TextButton(
                enabled = valid && draft.trim() != id,
                onClick = { viewModel.setDiscordApplicationId(draft) },
            ) { Text("Сохранить ID") }
            Text("Создайте приложение Discord и включите Social SDK. На телефоне должен быть установлен Discord с выполненным входом. При паузе активность скрывается.")
            TextButton(onClick = { uriHandler.openUri("https://discord.com/developers/applications") }) {
                Text("Открыть Discord Developer Portal")
            }
        }
        SettingsRow(
            icon = Icons.Outlined.Groups,
            title = "Показывать совместный режим",
            subtitle = "Джем, раздача, слушать вместе · без кодов комнат и адресов устройств",
            trailing = { NamiSwitch(showMode, viewModel::setDiscordShowMode) },
            onClick = { viewModel.setDiscordShowMode(!showMode) },
        )
    }
}
