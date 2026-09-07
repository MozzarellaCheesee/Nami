package dev.nami.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nami.domain.VocabularyRepository
import dev.nami.domain.VocabularyWord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class QuizQuestion(
    val prompt: String,
    val choices: List<String>,
    val correctIndex: Int,
)

data class QuizUiState(
    val question: QuizQuestion? = null,
    val selectedIndex: Int? = null,
    val correctCount: Int = 0,
    val totalCount: Int = 0,
    /** Fewer than 4 saved words -- not enough to build a 4-choice question. */
    val notEnoughWords: Boolean = false,
)

/** План.md's "режим изучения ... квиз «вставь пропущенное слово в строку» по трекам, которые
 * слушаешь чаще всего" -- "чаще всего" would need per-track play-count join this pass didn't
 * add; questions are instead drawn from the whole saved vocabulary (already scoped to lines/
 * tracks the user cared enough about to save a word from), which is the same practical target
 * without a separate query. */
@HiltViewModel
class QuizViewModel @Inject constructor(
    private val vocabularyRepository: VocabularyRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(QuizUiState())
    val uiState: StateFlow<QuizUiState> = _uiState

    private var pool: List<VocabularyWord> = emptyList()

    init {
        viewModelScope.launch {
            pool = vocabularyRepository.words().first()
            if (pool.size < 4) {
                _uiState.value = QuizUiState(notEnoughWords = true)
            } else {
                nextQuestion()
            }
        }
    }

    fun nextQuestion() {
        val target = pool.random()
        // The blanked prompt only makes sense if the word actually appears in its own context
        // line -- falls back to a plain "translate this word" prompt otherwise (contextLine was
        // saved from lyrics text, but base-form vs. surface-form conjugation can mismatch).
        val prompt = if (target.contextLine.contains(target.word)) {
            target.contextLine.replace(target.word, "___")
        } else {
            "${target.word} (${target.reading}) - что это значит?"
        }
        val distractors = pool.filter { it.id != target.id }.shuffled().take(3).map { it.meaning }
        val choices = (distractors + target.meaning).shuffled()
        _uiState.value = _uiState.value.copy(
            question = QuizQuestion(prompt, choices, choices.indexOf(target.meaning)),
            selectedIndex = null,
        )
    }

    fun answer(index: Int) {
        val question = _uiState.value.question ?: return
        if (_uiState.value.selectedIndex != null) return // already answered this one
        val correct = index == question.correctIndex
        _uiState.value = _uiState.value.copy(
            selectedIndex = index,
            correctCount = _uiState.value.correctCount + if (correct) 1 else 0,
            totalCount = _uiState.value.totalCount + 1,
        )
    }
}
