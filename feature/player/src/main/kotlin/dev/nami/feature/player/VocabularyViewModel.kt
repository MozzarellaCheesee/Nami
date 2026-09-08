package dev.nami.feature.player

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.nami.domain.SettingsRepository
import dev.nami.domain.VocabularyRepository
import dev.nami.domain.VocabularyWord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class VocabularyViewModel @Inject constructor(
    private val vocabularyRepository: VocabularyRepository,
    settingsRepository: SettingsRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val words: StateFlow<List<VocabularyWord>> =
        vocabularyRepository.words().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val studyModeEnabled: StateFlow<Boolean> = settingsRepository.studyModeEnabled

    private val _exportedMessage = MutableStateFlow<String?>(null)
    val exportedMessage: StateFlow<String?> = _exportedMessage

    fun remove(id: Long) {
        viewModelScope.launch { vocabularyRepository.remove(id) }
    }

    /** CSV straight into Downloads via MediaStore - no storage permission needed on API 29+,
     * and Anki's plain-CSV importer reads it directly (word, reading, meaning, line, track). */
    fun exportCsv() {
        viewModelScope.launch {
            val csv = vocabularyRepository.exportCsv()
            val fileName = "nami_vocabulary_${System.currentTimeMillis()}.csv"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/")
                }
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                context.contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray()) }
                _exportedMessage.value = "Сохранено в Загрузки: $fileName"
            } else {
                _exportedMessage.value = "Не удалось сохранить файл"
            }
        }
    }

    fun dismissExportedMessage() {
        _exportedMessage.value = null
    }
}
