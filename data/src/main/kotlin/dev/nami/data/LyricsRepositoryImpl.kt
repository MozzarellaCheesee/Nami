package dev.nami.data

import dev.nami.core.model.Lyrics
import dev.nami.core.model.LyricLine
import dev.nami.core.model.WordTiming
import dev.nami.core.model.WordToken
import dev.nami.domain.LyricsRepository
import dev.nami.domain.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

class LyricsRepositoryImpl @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : LyricsRepository {

    private fun lrcFile(path: String) = File(sibling(path, ".lrc"))

    // "/music/track.flac" -> "/music/track.lrc" - same basename next to the audio file, the
    // convention every desktop player and most phone players already look for.
    private fun sibling(path: String, newExtension: String): String {
        val dot = path.lastIndexOf('.')
        val base = if (dot > path.lastIndexOf('/')) path.substring(0, dot) else path
        return base + newExtension
    }

    override fun lyricsForPath(path: String): Flow<Lyrics?> = flow {
        emit(
            withContext(Dispatchers.IO) {
                val file = lrcFile(path)
                if (file.exists()) LrcParser.parse(file.readText()) else null
            },
        )
    }

    override suspend fun saveLyrics(path: String, lyrics: Lyrics) {
        withContext(Dispatchers.IO) {
            lrcFile(path).writeText(LrcParser.format(lyrics))
        }
    }

    override suspend fun importLyricsFile(path: String, rawText: String): Boolean {
        val lyrics = LrcParser.parse(rawText)
        if (lyrics.lines.isEmpty()) return false
        saveLyrics(path, lyrics)
        return true
    }

    override suspend fun fetchFromLrcLib(title: String, artistName: String?, durationMs: Long): Lyrics? =
        withContext(Dispatchers.IO) {
            LrcLibClient.findSyncedLyrics(title, artistName, durationMs)?.let { LrcParser.parse(it) }
        }

    override suspend fun fetchFromStands4(title: String, artistName: String?, durationMs: Long): Lyrics? {
        val uid = settingsRepository.stands4Uid.value
        val token = settingsRepository.stands4Token.value
        if (uid.isBlank() || token.isBlank()) return null
        if (settingsRepository.stands4RequestsToday.value >= SettingsRepository.STANDS4_DAILY_LIMIT) return null
        // Counts the attempt regardless of hit/miss - STANDS4 bills the request either way.
        settingsRepository.recordStands4Request()
        return withContext(Dispatchers.IO) {
            Stands4Client.findPlainLyrics(title, artistName, uid, token)?.let { plainLyricsToApproxSynced(it, durationMs) }
        }
    }

    /** STANDS4 has no per-line timestamps - spreads non-blank lines evenly across [durationMs]
     * (or 3s/line if the duration isn't known) so the existing synced-lyrics screen still has
     * something to highlight/autoscroll to, instead of needing a whole separate "plain lyrics"
     * rendering path just for this one fallback source. Approximate, not real sync - documented
     * on [dev.nami.domain.LyricsRepository.fetchFromStands4]. */
    private fun plainLyricsToApproxSynced(rawText: String, durationMs: Long): Lyrics? {
        val textLines = rawText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (textLines.isEmpty()) return null
        val stepMs = if (durationMs > 0) durationMs / textLines.size else 3_000L
        return Lyrics(textLines.mapIndexed { index, text -> LyricLine(timeMs = index * stepMs, text = text) })
    }

    private fun translationFile(path: String) = File(sibling(path, ".ru.txt"))

    override fun translationForPath(path: String): Flow<List<String>?> = flow {
        emit(
            withContext(Dispatchers.IO) {
                val file = translationFile(path)
                if (file.exists()) file.readLines() else null
            },
        )
    }

    override suspend fun saveTranslation(path: String, lines: List<String>) {
        withContext(Dispatchers.IO) {
            translationFile(path).writeText(lines.joinToString("\n"))
        }
    }

    override suspend fun translateToRussian(lines: List<String>): List<String>? {
        val apiKey = settingsRepository.deeplApiKey.value
        if (apiKey.isNotBlank()) {
            translateWithDeepl(lines, apiKey)?.let { return it }
            // DeepL failed (bad key, quota, no network) - fall through to the offline fallback
            // rather than showing nothing.
        }
        return MlKitTranslator.translateToRussian(lines)
    }

    private suspend fun translateWithDeepl(lines: List<String>, apiKey: String): List<String>? =
        withContext(Dispatchers.IO) {
            val groups = SentenceGrouper.group(lines)
            if (groups.isEmpty()) return@withContext lines.map { it }
            val translated = DeeplClient.translate(groups.map { it.text }, apiKey) ?: return@withContext null
            val result = arrayOfNulls<String>(lines.size)
            groups.forEachIndexed { gi, group -> group.indices.forEach { idx -> result[idx] = translated.getOrNull(gi) ?: "" } }
            for (i in lines.indices) if (lines[i].isBlank()) result[i] = lines[i]
            result.map { it.orEmpty() }
        }

    private fun romajiFile(path: String) = File(sibling(path, ".romaji.txt"))

    override fun romajiForPath(path: String): Flow<List<String>?> = flow {
        emit(
            withContext(Dispatchers.IO) {
                val file = romajiFile(path)
                if (file.exists()) file.readLines() else null
            },
        )
    }

    override suspend fun saveRomaji(path: String, lines: List<String>) {
        withContext(Dispatchers.IO) {
            romajiFile(path).writeText(lines.joinToString("\n"))
        }
    }

    override suspend fun generateRomaji(lines: List<String>): List<String> =
        withContext(Dispatchers.Default) { RomajiGenerator.generate(lines) }

    override suspend fun tokenizeLine(line: String): List<WordToken> =
        withContext(Dispatchers.Default) { WordTokenizer.tokenize(line) }

    private fun wordTimingsFile(path: String) = File(sibling(path, ".words.txt"))

    // One line per lyric line (blank if no words matched that line); each word as
    // "wordstartMsendMs", words separated by tabs - plain text, same spirit as the
    // other sidecar caches here, no JSON dependency needed for this shape.
    override fun wordTimingsForPath(path: String): Flow<List<List<WordTiming>>?> = flow {
        emit(
            withContext(Dispatchers.IO) {
                val file = wordTimingsFile(path)
                if (!file.exists()) return@withContext null
                file.readLines().map { line ->
                    if (line.isBlank()) {
                        emptyList()
                    } else {
                        line.split("\t").mapNotNull { entry ->
                            val parts = entry.split("")
                            if (parts.size != 3) return@mapNotNull null
                            val start = parts[1].toLongOrNull() ?: return@mapNotNull null
                            val end = parts[2].toLongOrNull() ?: return@mapNotNull null
                            WordTiming(parts[0], start, end)
                        }
                    }
                }
            },
        )
    }

    override suspend fun saveWordTimings(path: String, perLine: List<List<WordTiming>>) {
        withContext(Dispatchers.IO) {
            val text = perLine.joinToString("\n") { words ->
                words.joinToString("\t") { "${it.word}${it.startMs}${it.endMs}" }
            }
            wordTimingsFile(path).writeText(text)
        }
    }
}
