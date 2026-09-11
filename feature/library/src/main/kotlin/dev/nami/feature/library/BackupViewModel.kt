package dev.nami.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nami.domain.BackupProgress
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

    private val _exportProgress = MutableStateFlow<BackupProgress?>(null)
    val exportProgress: StateFlow<BackupProgress?> = _exportProgress

    fun exportLibrary(destinationUri: String) {
        viewModelScope.launch {
            _exportProgress.value = BackupProgress(0, 1, "Подготовка...")
            val result = backupRepository.exportLibrary(destinationUri) { progress ->
                _exportProgress.value = progress
            }
            _exportProgress.value = null
            _exportResult.value = result
        }
    }

    fun exportResultShown() {
        _exportResult.value = null
    }
}
