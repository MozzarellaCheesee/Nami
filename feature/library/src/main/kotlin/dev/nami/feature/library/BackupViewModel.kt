package dev.nami.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nami.domain.BackupRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** П.md §2 "Полный экспорт в .zip" - отдельная ViewModel, не часть LibraryViewModel, чтобы не
 * плодить очередной конструкторский параметр там (и не чинить 10 тестовых fake-ов ради одной
 * кнопки в Настройках). */
@HiltViewModel
class BackupViewModel @Inject constructor(
    private val backupRepository: BackupRepository,
) : ViewModel() {
    private val _exportResult = MutableStateFlow<Boolean?>(null)
    val exportResult: StateFlow<Boolean?> = _exportResult

    fun exportLibrary(destinationUri: String) {
        viewModelScope.launch {
            _exportResult.value = backupRepository.exportLibrary(destinationUri)
        }
    }

    fun exportResultShown() {
        _exportResult.value = null
    }
}
