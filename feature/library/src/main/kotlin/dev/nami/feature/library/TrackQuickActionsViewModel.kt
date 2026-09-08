package dev.nami.feature.library

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.nami.core.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** "Начать радио"/"Поделиться карточкой" перенесены из TrackInfoScreen в общее меню "ещё" у
 * трека (везде, где оно есть - Библиотека/Альбом/Артист) - живёт на уровне NamiNavHost, а не
 * внутри какого-то одного экрана, раз действие теперь доступно с любого из них. */
@HiltViewModel
class TrackQuickActionsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val _shareCardUri = MutableStateFlow<Uri?>(null)
    val shareCardUri: StateFlow<Uri?> = _shareCardUri

    fun shareCard(track: Track) {
        viewModelScope.launch {
            val uri = withContext(Dispatchers.Default) {
                val bitmap = TrackCardRenderer.render(track)
                TrackCardRenderer.saveAndGetShareUri(context, bitmap)
            }
            _shareCardUri.value = uri
        }
    }

    fun shareCardUriShown() {
        _shareCardUri.value = null
    }
}
