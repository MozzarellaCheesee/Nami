package dev.nami.data

import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Source language is always Japanese for now -- this whole feature exists for "watching
 * Japanese music turn into language study" (План.md's positioning line), other source languages
 * are a later generalization, not this pass. Model downloads once per app-install over network
 * (opt-in: only ever triggered by the user tapping "Перевод"), then runs fully offline. */
object MlKitTranslator {
    suspend fun translateToRussian(lines: List<String>): List<String>? {
        val translator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.JAPANESE)
                .setTargetLanguage(TranslateLanguage.RUSSIAN)
                .build(),
        )
        return try {
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            // A Latin-heavy line (English mixed into the lyrics) fed to a JA->RU translator as
            // if it were all Japanese comes back garbled -- passed through untranslated instead.
            lines.map { line ->
                if (line.isBlank() || !isJapaneseDominant(line)) line else translator.translate(line).await()
            }
        } catch (e: Exception) {
            Log.w("MlKitTranslator", "translation failed: ${e.message}")
            null
        } finally {
            translator.close()
        }
    }

    // ML Kit returns Play Services Tasks, not coroutines/futures -- kotlinx-coroutines-play-services
    // would be one dependency for exactly this one bridge; a dozen lines of suspendCancellableCoroutine
    // does the same job without it.
    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resumeWithException(it) }
    }
}
