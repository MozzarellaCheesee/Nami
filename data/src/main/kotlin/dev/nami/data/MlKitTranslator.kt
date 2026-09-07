package dev.nami.data

import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// This song's lyrics (and plenty of others) mix Japanese and English -- often as a whole English
// line by itself, or "日本語 (English paraphrase)". A single JA->RU translator either garbles the
// English part or silently skips English-only lines entirely (an earlier version of this file did
// that, which just meant no translation at all for those lines). Two translators, one per
// language, chosen per line/segment instead.
private val PARENTHETICAL = Regex("^(.*?)\\s*\\((.*)\\)\\s*$")

object MlKitTranslator {
    suspend fun translateToRussian(lines: List<String>): List<String>? {
        val ja = buildTranslator(TranslateLanguage.JAPANESE)
        val en = buildTranslator(TranslateLanguage.ENGLISH)
        return try {
            ja.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            en.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            // See SentenceGrouper's doc -- a lyric line split mid-sentence across two .lrc
            // timings needs the whole sentence for grammar, not the fragment alone.
            val result = arrayOfNulls<String>(lines.size)
            SentenceGrouper.group(lines).forEach { group ->
                val translated = translateLine(group.text, ja, en)
                group.indices.forEach { idx -> result[idx] = translated }
            }
            for (i in lines.indices) if (lines[i].isBlank()) result[i] = lines[i]
            result.map { it.orEmpty() }
        } catch (e: Exception) {
            Log.w("MlKitTranslator", "translation failed: ${e.message}")
            null
        } finally {
            ja.close()
            en.close()
        }
    }

    private fun buildTranslator(sourceLanguage: String): Translator =
        Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(sourceLanguage)
                .setTargetLanguage(TranslateLanguage.RUSSIAN)
                .build(),
        )

    private suspend fun translateLine(line: String, ja: Translator, en: Translator): String {
        if (line.isBlank()) return line
        // "日本語 (English aside)" -- translate each half with the translator that actually
        // matches its language instead of feeding the whole mixed line to one of them.
        val match = PARENTHETICAL.find(line)
        if (match != null) {
            val main = translateSegment(match.groupValues[1], ja, en)
            val paren = translateSegment(match.groupValues[2], ja, en)
            return "$main ($paren)"
        }
        return translateSegment(line, ja, en)
    }

    private suspend fun translateSegment(text: String, ja: Translator, en: Translator): String {
        if (text.isBlank()) return text
        val translator = if (isJapaneseDominant(text)) ja else en
        return translator.translate(text).await()
    }

    // ML Kit returns Play Services Tasks, not coroutines/futures -- kotlinx-coroutines-play-services
    // would be one dependency for exactly this one bridge; a dozen lines of suspendCancellableCoroutine
    // does the same job without it.
    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resumeWithException(it) }
    }
}
