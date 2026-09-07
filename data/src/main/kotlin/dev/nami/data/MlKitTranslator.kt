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

// A lyric line that doesn't end on one of these usually isn't a complete sentence -- whoever
// timed the .lrc file split it mid-clause across two lines (common in J-pop, the vocal phrasing
// doesn't line up with sentence grammar). Translating that fragment alone is exactly what makes
// JA->RU come out "кривой" -- MLKit has no cross-line context, so it either drops the dangling
// clause or invents a subject/verb to complete it. Joining continuation lines into one sentence
// before translating gives the model the grammar it actually needs.
private val SENTENCE_END = Regex("[。！？…!?]+[」』）)]*$")

object MlKitTranslator {
    suspend fun translateToRussian(lines: List<String>): List<String>? {
        val ja = buildTranslator(TranslateLanguage.JAPANESE)
        val en = buildTranslator(TranslateLanguage.ENGLISH)
        return try {
            ja.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            en.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            val result = arrayOfNulls<String>(lines.size)
            var groupStart = 0
            for (i in lines.indices) {
                val isBlank = lines[i].isBlank()
                val endsGroup = isBlank || SENTENCE_END.containsMatchIn(lines[i]) || i == lines.lastIndex
                if (!endsGroup) continue
                val group = (groupStart..i).filterNot { lines[it].isBlank() }
                if (group.isNotEmpty()) {
                    val joined = group.joinToString(" ") { lines[it] }
                    val translated = translateLine(joined, ja, en)
                    group.forEach { idx -> result[idx] = translated }
                }
                for (idx in groupStart..i) if (lines[idx].isBlank()) result[idx] = lines[idx]
                groupStart = i + 1
            }
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
