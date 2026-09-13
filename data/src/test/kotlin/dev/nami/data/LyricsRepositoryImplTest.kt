package dev.nami.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.nami.core.model.LyricLine
import dev.nami.core.model.Lyrics
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class LyricsRepositoryImplTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    // Настоящий AppSettingsRepository, а не фейк: у SettingsRepository под сотню членов, а
    // здесь важно только то, что серверная лирика выключена по умолчанию - значит в сеть
    // тест не пойдёт.
    private val repo = LyricsRepositoryImpl(context, AppSettingsRepository(context))

    private val lyrics = Lyrics(listOf(LyricLine(timeMs = 0, text = "первая строка")))

    /** Путь зеркала серверной библиотеки - "nami-server://42", а не "server_42" (это id строки).
     * Старая проверка перечисляла схемы поимённо, ловила "server_" и пропускала настоящий путь:
     * сайдкар пытался лечь "рядом с файлом", то есть в File("nami-server:/42.lrc"), и запись
     * валила приложение. */
    @Test
    fun `lyrics for a server track are saved without touching the fake path`() = runTest {
        repo.saveLyrics("nami-server://42", lyrics)

        val loaded = repo.lyricsForPath("nami-server://42").first()
        assertNotNull(loaded)
        assertEquals("первая строка", loaded.lines.single().text)
        assertTrue(File(context.cacheDir, "lyrics_cache").listFiles().orEmpty().isNotEmpty())
    }

    @Test
    fun `lyrics for a real file land next to that file`() = runTest {
        val dir = File(context.cacheDir, "music").apply { mkdirs() }
        val track = File(dir, "song.flac").apply { writeText("audio") }

        repo.saveLyrics(track.absolutePath, lyrics)

        assertTrue(File(dir, "song.lrc").exists())
    }

    @Test
    fun `translation for a server track is saved too`() = runTest {
        // Перевод ходит через тот же resolveSiblingFile - если бы правка его не покрывала,
        // падение просто переехало бы с текста на перевод.
        repo.saveTranslation("nami-server://7", listOf("строка"))

        assertEquals(listOf("строка"), repo.translationForPath("nami-server://7").first())
    }
}
