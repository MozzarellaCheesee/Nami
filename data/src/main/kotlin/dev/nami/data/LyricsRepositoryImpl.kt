package dev.nami.data

import dev.nami.core.model.Lyrics
import dev.nami.core.model.WordTiming
import dev.nami.core.model.WordToken
import dev.nami.domain.LyricsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

class LyricsRepositoryImpl @Inject constructor() : LyricsRepository {

    private fun lrcFile(path: String) = File(sibling(path, ".lrc"))

    // "/music/track.flac" -> "/music/track.lrc" -- same basename next to the audio file, the
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

    override suspend fun fetchFromLrcLib(title: String, artistName: String?, durationMs: Long): Lyrics? =
        withContext(Dispatchers.IO) {
            LrcLibClient.findSyncedLyrics(title, artistName, durationMs)?.let { LrcParser.parse(it) }
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

    override suspend fun translateToRussian(lines: List<String>): List<String>? =
        MlKitTranslator.translateToRussian(lines)

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
    // "wordstartMsendMs", words separated by tabs -- plain text, same spirit as the
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
