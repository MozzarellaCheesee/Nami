package dev.nami.data

import dev.nami.core.model.Lyrics
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
}
