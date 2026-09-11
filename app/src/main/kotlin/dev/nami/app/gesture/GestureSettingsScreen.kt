package dev.nami.app.gesture

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ScreenLockPortrait
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.nami.app.SettingsViewModel
import dev.nami.core.designsystem.NamiAlertDialog
import dev.nami.core.designsystem.NamiColors
import dev.nami.core.designsystem.NamiRadius
import dev.nami.app.NamiSwitch
import dev.nami.domain.GestureAction

@Composable
fun GestureSettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val doubleTapAction by viewModel.doubleTapArtworkAction.collectAsState()
    val longPressAction by viewModel.longPressArtworkAction.collectAsState()
    val miniPlayerSideSwipe by viewModel.miniPlayerSideSwipeAction.collectAsState()
    val shakeToShuffle by viewModel.shakeToShuffleEnabled.collectAsState()
    val shakeSensitivity by viewModel.shakeSensitivity.collectAsState()
    val lockscreenLyrics by viewModel.lockscreenLyricsEnabled.collectAsState()

    var pickerTarget by remember { mutableStateOf<PickerTarget?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NamiColors.Ink900)
            .verticalScroll(rememberScrollState())
            .padding(bottom = 32.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 16.dp, start = 8.dp, bottom = 8.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Outlined.ArrowBack, contentDescription = "Назад", tint = NamiColors.Paper100)
            }
            Text(
                text = "Жесты и управление",
                style = MaterialTheme.typography.titleLarge,
                color = NamiColors.Paper100,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        Text(
            text = "NOW PLAYING",
            style = MaterialTheme.typography.labelSmall,
            color = NamiColors.Paper40,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )

        SettingsCard(modifier = Modifier.padding(horizontal = 20.dp)) {
            GestureRow(
                icon = Icons.Outlined.TouchApp,
                title = "Двойной тап по обложке",
                value = gestureActionTitle(doubleTapAction),
                onClick = { pickerTarget = PickerTarget.DOUBLE_TAP },
            )
            GestureRow(
                icon = Icons.Outlined.TouchApp,
                title = "Долгое нажатие по обложке",
                value = gestureActionTitle(longPressAction),
                onClick = { pickerTarget = PickerTarget.LONG_PRESS },
            )
        }

        Text(
            text = "МИНИ-ПЛЕЕР",
            style = MaterialTheme.typography.labelSmall,
            color = NamiColors.Paper40,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )

        SettingsCard(modifier = Modifier.padding(horizontal = 20.dp)) {
            GestureRow(
                icon = Icons.Outlined.TouchApp,
                title = "Свайп вбок по мини-плееру",
                value = if (miniPlayerSideSwipe == GestureAction.SKIP_NEXT) "Листать треки" else "Ничего",
                onClick = {
                    val next = if (miniPlayerSideSwipe == GestureAction.SKIP_NEXT) GestureAction.NONE else GestureAction.SKIP_NEXT
                    viewModel.setMiniPlayerSideSwipeAction(next)
                },
            )
        }

        Text(
            text = "АКСЕЛЕРОМЕТР",
            style = MaterialTheme.typography.labelSmall,
            color = NamiColors.Paper40,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )

        SettingsCard(modifier = Modifier.padding(horizontal = 20.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.setShakeToShuffleEnabled(!shakeToShuffle) }
                    .padding(16.dp),
            ) {
                Icon(Icons.Outlined.Vibration, contentDescription = null, tint = NamiColors.Paper70)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text("Shake to shuffle", color = NamiColors.Paper100, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Встряхните телефон, чтобы перемешать очередь",
                        color = NamiColors.Paper40,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                NamiSwitch(checked = shakeToShuffle, onCheckedChange = viewModel::setShakeToShuffleEnabled)
            }

            if (shakeToShuffle) {
                GestureRow(
                    icon = Icons.Outlined.Vibration,
                    title = "Чувствительность встряхивания",
                    value = when {
                        shakeSensitivity <= 11f -> "Высокая (лёгкий взмах)"
                        shakeSensitivity <= 14f -> "Обычная"
                        else -> "Низкая (сильное встряхивание)"
                    },
                    onClick = { pickerTarget = PickerTarget.SHAKE_SENSITIVITY },
                )
            }
        }

        Text(
            text = "ЭКРАН БЛОКИРОВКИ",
            style = MaterialTheme.typography.labelSmall,
            color = NamiColors.Paper40,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )

        SettingsCard(modifier = Modifier.padding(horizontal = 20.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.setLockscreenLyricsEnabled(!lockscreenLyrics) }
                    .padding(16.dp),
            ) {
                Icon(Icons.Outlined.ScreenLockPortrait, contentDescription = null, tint = NamiColors.Paper70)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text("Строка лирики на локскрине", color = NamiColors.Paper100, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Динамическая смена текущей строки песни в уведомлении",
                        color = NamiColors.Paper40,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                NamiSwitch(checked = lockscreenLyrics, onCheckedChange = viewModel::setLockscreenLyricsEnabled)
            }
        }
    }

    pickerTarget?.let { target ->
        when (target) {
            PickerTarget.DOUBLE_TAP -> {
                ActionPickerDialog(
                    title = "Двойной тап по обложке",
                    selected = doubleTapAction,
                    onSelect = {
                        viewModel.setDoubleTapArtworkAction(it)
                        pickerTarget = null
                    },
                    onDismiss = { pickerTarget = null },
                )
            }
            PickerTarget.LONG_PRESS -> {
                ActionPickerDialog(
                    title = "Долгое нажатие по обложке",
                    selected = longPressAction,
                    onSelect = {
                        viewModel.setLongPressArtworkAction(it)
                        pickerTarget = null
                    },
                    onDismiss = { pickerTarget = null },
                )
            }
            PickerTarget.SHAKE_SENSITIVITY -> {
                SensitivityPickerDialog(
                    current = shakeSensitivity,
                    onSelect = {
                        viewModel.setShakeSensitivity(it)
                        pickerTarget = null
                    },
                    onDismiss = { pickerTarget = null },
                )
            }
        }
    }
}

private enum class PickerTarget { DOUBLE_TAP, LONG_PRESS, SHAKE_SENSITIVITY }

@Composable
private fun SettingsCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(NamiColors.Ink800, RoundedCornerShape(NamiRadius.Card)),
        content = { content() },
    )
}

@Composable
private fun GestureRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    value: String,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        Icon(icon, contentDescription = null, tint = NamiColors.Paper70)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = NamiColors.Paper100, style = MaterialTheme.typography.bodyMedium)
        }
        Text(value, color = NamiColors.Paper40, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ActionPickerDialog(
    title: String,
    selected: GestureAction,
    onSelect: (GestureAction) -> Unit,
    onDismiss: () -> Unit,
) {
    NamiAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = NamiColors.Paper100) },
        text = {
            Column {
                GestureAction.entries.forEach { action ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(action) }
                            .padding(vertical = 12.dp),
                    ) {
                        Text(
                            gestureActionTitle(action),
                            color = if (action == selected) NamiColors.Shu else NamiColors.Paper100,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (action == selected) {
                            Icon(Icons.Outlined.Check, contentDescription = null, tint = NamiColors.Shu)
                        }
                    }
                }
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun SensitivityPickerDialog(
    current: Float,
    onSelect: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val options = listOf(
        10.0f to "Высокая (чувствительная)",
        13.0f to "Обычная (рекомендуется)",
        17.0f to "Низкая (требует резкого рывка)",
    )
    NamiAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Чувствительность", color = NamiColors.Paper100) },
        text = {
            Column {
                options.forEach { (value, label) ->
                    val isSelected = kotlin.math.abs(current - value) < 1.0f
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(value) }
                            .padding(vertical = 12.dp),
                    ) {
                        Text(
                            label,
                            color = if (isSelected) NamiColors.Shu else NamiColors.Paper100,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (isSelected) {
                            Icon(Icons.Outlined.Check, contentDescription = null, tint = NamiColors.Shu)
                        }
                    }
                }
            }
        },
        confirmButton = {},
    )
}

private fun gestureActionTitle(action: GestureAction): String = when (action) {
    GestureAction.NONE -> "Ничего"
    GestureAction.TOGGLE_LIKE -> "Любимый трек"
    GestureAction.SKIP_NEXT -> "Следующий трек"
    GestureAction.PREV_TRACK -> "Предыдущий трек"
    GestureAction.PLAY_PAUSE -> "Пауза / играть"
    GestureAction.SHOW_LYRICS -> "Показать лирику"
    GestureAction.SHUFFLE -> "Перемешать очередь"
}
