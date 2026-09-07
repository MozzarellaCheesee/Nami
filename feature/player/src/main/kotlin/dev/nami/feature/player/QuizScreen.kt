package dev.nami.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.nami.core.designsystem.NamiColors

/** План.md's "режим изучения": квиз «вставь пропущенное слово в строку» built from the saved
 * vocabulary. Beta, same as study mode itself -- entry point in VocabularyScreen only shows when
 * study mode is on in Settings. */
@Composable
fun QuizScreen(onBack: () -> Unit, viewModel: QuizViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()

    Box(modifier = Modifier.fillMaxSize().background(NamiColors.Ink900)) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Outlined.ArrowBack, contentDescription = "Назад", tint = NamiColors.Paper100)
                }
                Text(text = "Квиз", color = NamiColors.Paper100, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (uiState.totalCount > 0) {
                    Text(
                        text = "${uiState.correctCount}/${uiState.totalCount}",
                        color = NamiColors.Paper70,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(end = 20.dp),
                    )
                }
            }

            when {
                uiState.notEnoughWords -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Нужно хотя бы 4 слова в словарике - тапай слова в тексте песни",
                        color = NamiColors.Paper70,
                        modifier = Modifier.padding(horizontal = 32.dp),
                    )
                }
                uiState.question == null -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.CircularProgressIndicator(color = NamiColors.Paper70)
                }
                else -> {
                    val question = uiState.question!!
                    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
                        Text(
                            text = question.prompt,
                            color = NamiColors.Paper100,
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.padding(vertical = 32.dp),
                        )
                        question.choices.forEachIndexed { index, choice ->
                            val selected = uiState.selectedIndex
                            val backgroundColor = when {
                                selected == null -> NamiColors.Ink800
                                index == question.correctIndex -> NamiColors.Wakaba.copy(alpha = 0.3f)
                                index == selected -> NamiColors.Shu.copy(alpha = 0.3f)
                                else -> NamiColors.Ink800
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 12.dp)
                                    .background(backgroundColor, RoundedCornerShape(12.dp))
                                    .clickable(enabled = selected == null) { viewModel.answer(index) }
                                    .padding(16.dp),
                            ) {
                                Text(text = choice, color = NamiColors.Paper100)
                            }
                        }
                        if (uiState.selectedIndex != null) {
                            TextButton(onClick = viewModel::nextQuestion, modifier = Modifier.padding(top = 12.dp)) {
                                Text("Дальше", color = NamiColors.Shu)
                            }
                        }
                    }
                }
            }
        }
    }
}
