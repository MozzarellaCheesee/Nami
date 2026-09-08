package dev.nami.feature.library

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import dev.nami.core.designsystem.NamiAlertDialog

/** Empty field clears the year (some releases genuinely don't have one) rather than rejecting
 * the save - this is the only field on the album where "unset" is a valid, common answer. */
@Composable
fun EditYearDialog(
    currentYear: Int?,
    onSave: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(currentYear?.toString() ?: "") }
    NamiAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Год выпуска") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { value -> if (value.length <= 4 && value.all { it.isDigit() }) text = value },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(text.toIntOrNull())
                onDismiss()
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
