package dev.nami.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Единое правило дедупа (см. DedupKey) - тесты откатывались вручную на старое поведение
 * (normalize без trim/lowercase, matches без сравнения альбома), оба падали как положено. */
class DedupKeyTest {

    @Test
    fun `normalize trims and lowercases`() {
        assertTrue(DedupKey.normalize("  Song Title ") == DedupKey.normalize("song title"))
    }

    @Test
    fun `normalize of null is empty`() {
        assertTrue(DedupKey.normalize(null) == "")
    }

    @Test
    fun `matches is case and whitespace insensitive across all three fields`() {
        assertTrue(
            DedupKey.matches(
                title = "  Song ", artistName = "Artist", albumName = "Album",
                otherTitle = "song", otherArtistName = "ARTIST", otherAlbumName = " album ",
            ),
        )
    }

    @Test
    fun `different album is not a duplicate`() {
        assertFalse(
            DedupKey.matches(
                title = "Song", artistName = "Artist", albumName = "Album One",
                otherTitle = "Song", otherArtistName = "Artist", otherAlbumName = "Album Two",
            ),
        )
    }

    @Test
    fun `unknown album does not block a match`() {
        // Локальный файл часто импортируется без тега альбома, а сервер тот же трек знает с
        // альбомом. При строгом сравнении в библиотеке появлялась бы пара одинаковых записей -
        // ровно то, чего требовалось избежать.
        assertTrue(
            DedupKey.matches(
                title = "Song", artistName = "Artist", albumName = null,
                otherTitle = "Song", otherArtistName = "Artist", otherAlbumName = null,
            ),
        )
        assertTrue(
            DedupKey.matches(
                title = "Song", artistName = "Artist", albumName = null,
                otherTitle = "Song", otherArtistName = "Artist", otherAlbumName = "Some Album",
            ),
        )
        assertTrue(
            DedupKey.matches(
                title = "Song", artistName = "Artist", albumName = "   ",
                otherTitle = "Song", otherArtistName = "Artist", otherAlbumName = "Some Album",
            ),
        )
    }

    @Test
    fun `two known but different albums stay separate tracks`() {
        assertFalse(
            DedupKey.matches(
                title = "Song", artistName = "Artist", albumName = "Live",
                otherTitle = "Song", otherArtistName = "Artist", otherAlbumName = "Studio",
            ),
        )
    }
}
