package dev.nami.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.nami.core.designsystem.NamiCard
import dev.nami.core.designsystem.NamiColors
import dev.nami.core.designsystem.NamiPill
import dev.nami.core.designsystem.NamiSectionLabel
import dev.nami.core.designsystem.NamiType

/** Единственный официальный способ узнать, что систему уговорили не убивать процесс в фоне.
 * До Android 6 (API 23) режима Doze нет вообще, но minSdk=26, так что ветка не нужна. */
fun Context.isIgnoringBatteryOptimizations(): Boolean =
    (getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

/** План.md Часть X "убийцы фоновых процессов" - на MIUI/One UI/EMUI система душит фоновый
 * процесс плеера, и воспроизведение просто обрывается посреди трека. Экран объясняет это
 * человеческим языком и ведёт в системный диалог.
 *
 * Вендор-специфичные экраны автозапуска (MIUI Autostart, Samsung "Спящие приложения") сюда
 * намеренно не добавлены: это недокументированные intent'ы, разные на каждой прошивке и версии,
 * половина из них падает ActivityNotFoundException. ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS -
 * единственный официальный API, и его достаточно на большинстве прошивок. */
@Composable
fun BatteryOptimizationScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var ignoring by remember { mutableStateOf(context.isIgnoringBatteryOptimizations()) }

    // Ответ приходит не через ActivityResult (системный диалог его не возвращает) - состояние
    // перечитываем на возврате в приложение, иначе экран продолжит врать "не отключено".
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) ignoring = context.isIgnoringBatteryOptimizations()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    SettingsSubScreenScaffold(title = "Фоновое воспроизведение", onBack = onBack) {
        // Статус - первое и самое крупное на экране: раньше он был первым абзацем сплошного
        // текста, и понять "у меня уже нормально или ещё нет" можно было только вчитавшись.
        NamiCard(
            modifier = Modifier.padding(horizontal = 20.dp),
            accent = if (ignoring) NamiColors.Wakaba else NamiColors.Kin,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(if (ignoring) NamiColors.Wakaba else NamiColors.Kin, CircleShape),
                )
                Text(
                    text = if (ignoring) "Фон разрешён" else "Система может обрывать музыку",
                    color = NamiColors.Paper100,
                    style = NamiType.TrackTitle,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
            Text(
                text = if (ignoring) {
                    "Nami исключён из оптимизации батареи - воспроизведение в фоне система не усыпит."
                } else {
                    "Android экономит батарею, усыпляя приложения в фоне, и музыка может оборваться " +
                        "посреди трека при выключенном экране - особенно на Xiaomi, Samsung и Huawei."
                },
                color = NamiColors.Paper40,
                style = NamiType.Secondary,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (!ignoring) {
                NamiPill(
                    text = "Отключить оптимизацию",
                    color = NamiColors.Shu,
                    modifier = Modifier.padding(top = 14.dp),
                    onClick = { context.requestIgnoreBatteryOptimizations() },
                )
            }
        }
        if (!ignoring) {
            NamiSectionLabel("Что произойдёт")
            NamiCard(modifier = Modifier.padding(horizontal = 20.dp)) {
                Text(
                    text = "Откроется системный диалог Android с вопросом, разрешить ли Nami работать " +
                        "в фоне без ограничений. Никаких данных это не открывает - только запрещает " +
                        "системе убивать воспроизведение.",
                    color = NamiColors.Paper70,
                    style = NamiType.Secondary,
                )
            }
        }
    }
}

/** Часть прошивок (и Android TV) не имеет этой activity - тогда просто открываем общий список
 * настроек батареи, чтобы клик не оказывался мёртвым. */
private fun Context.requestIgnoreBatteryOptimizations() {
    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        .setData(Uri.parse("package:$packageName"))
    runCatching { startActivity(intent) }.onFailure {
        runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }
}
