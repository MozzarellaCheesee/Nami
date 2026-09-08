package dev.nami.feature.player

import androidx.lifecycle.ViewModel
import androidx.media3.common.util.UnstableApi
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nami.domain.SettingsRepository
import dev.nami.player.remote.RemoteCastController
import dev.nami.player.remote.RemoteDevice
import dev.nami.player.remote.RemoteKind
import javax.inject.Inject

@UnstableApi
@HiltViewModel
class CastPickerViewModel @Inject constructor(
    private val controller: RemoteCastController,
    private val settings: SettingsRepository,
) : ViewModel() {
    val devices = controller.devices
    val searching = controller.searching
    val connected = controller.connected
    val error = controller.error

    /** Beta-экосистемы показываются и ищутся только при включённом тумблере - см. SettingsRepository. */
    fun isKindEnabled(kind: RemoteKind): Boolean = when (kind) {
        RemoteKind.DLNA -> true
        RemoteKind.AIRPLAY -> settings.airPlayEnabled.value
        RemoteKind.YANDEX -> settings.yandexStationEnabled.value
    }

    fun search() = controller.search()
    fun connect(device: RemoteDevice) = controller.connect(device)
    fun disconnect() = controller.disconnect()
}
