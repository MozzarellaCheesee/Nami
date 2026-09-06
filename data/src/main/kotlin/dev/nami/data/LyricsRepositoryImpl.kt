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
}
