package dev.nami.data

import dev.nami.core.model.Track
import dev.nami.core.model.TrackId
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Задача "неправильное кол-во треков при отправке всех на сервер": total/ошибок расходились с
 * реальностью, потому что зеркала серверной библиотеки, дубли одного файла и записи об уже
 * удалённых с диска файлах попадали в общий счётчик наравне с настоящими кандидатами на отправку.
 */
class ServerLibraryRepositoryUploadCandidatesTest {

    private fun track(id: String, path: String) = Track(
        id = TrackId(id),
        title = id,
        artistId = null,
        albumId = null,
        durationMs = 1000L,
        path = path,
        format = "mp3",
        sizeBytes = 100L,
        dateAdded = 0L,
    )

    @Test
    fun `mirrors, duplicate paths and missing files are excluded from the count`() {
        val tracks = listOf(
            track("1", "/music/a.mp3"),
            track("2", "/music/a.mp3"), // тот же файл (несколько артистов) - не должен считаться дважды
            track("3", "nami-server://42"), // зеркало - слать некуда
            track("4", "/music/missing.mp3"), // файла больше нет на диске
        )
        val exists: (String) -> Boolean = { it != "/music/missing.mp3" }

        val (upload, withoutMirrorsSize, missing) = uploadCandidates(tracks, exists)

        // Дубль /music/a.mp3 схлопнут, зеркало убрано, missing.mp3 не годится к отправке -
        // реально отправляем только один трек.
        assertEquals(1, upload.size)
        assertEquals("/music/a.mp3", upload.single().path)
        // total без зеркал и дублей: a.mp3 (once) + missing.mp3 = 2.
        assertEquals(2, withoutMirrorsSize)
        assertEquals(1, missing)
    }

    @Test
    fun `all tracks present and no mirrors keeps everything`() {
        val tracks = listOf(track("1", "/a.mp3"), track("2", "/b.mp3"))
        val (upload, total, missing) = uploadCandidates(tracks) { true }
        assertEquals(2, upload.size)
        assertEquals(2, total)
        assertEquals(0, missing)
    }
}
