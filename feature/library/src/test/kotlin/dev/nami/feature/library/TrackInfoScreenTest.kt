package dev.nami.feature.library

import dev.nami.core.model.AlbumId
import dev.nami.core.model.ArtistId
import dev.nami.core.model.Track
import dev.nami.core.model.TrackId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** displayFilePath не должен показывать UUID, которым импорт называет файл на диске
 * (LibraryRepositoryImpl.copyAndIndex) - экран "Информация о треке" должен видеть человека, а не
 * машину. Откат на `track.path.substringAfterLast('/')` (сырое имя файла) этот тест валит. */
class TrackInfoScreenTest {

    private fun track(path: String, title: String, artistName: String?, format: String = "flac") = Track(
        id = TrackId("t1"),
        title = title,
        artistId = ArtistId("a1"),
        albumId = AlbumId("al1"),
        durationMs = 1000,
        path = path,
        format = format,
        sizeBytes = 100,
        dateAdded = 0,
        artistName = artistName,
    )

    @Test
    fun `display path has no uuid, only artist and title`() {
        val uuidPath = "/data/music/a3f2c1e8-1234-4d5e-9abc-1234567890ab.flac"
        val result = displayFilePath(track(uuidPath, "My Song", "My Artist"))
        assertEquals("/data/music/My Artist - My Song.flac", result)
        assertFalse(result.contains("a3f2c1e8"))
    }

    @Test
    fun `missing artist falls back to title only`() {
        val result = displayFilePath(track("/data/music/x.mp3", "Solo Title", null, format = "mp3"))
        assertEquals("/data/music/Solo Title.mp3", result)
    }
}
