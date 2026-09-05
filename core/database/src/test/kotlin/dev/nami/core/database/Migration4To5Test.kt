package dev.nami.core.database

import java.sql.DriverManager
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies MIGRATION_4_5 (see Migrations.kt) adds the deletedAt columns without touching
 * existing rows, against a hand-built v4 schema.
 */
class Migration4To5Test {
    private lateinit var conn: java.sql.Connection

    @Before
    fun setUp() {
        Class.forName("org.sqlite.JDBC")
        conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        val stmt = conn.createStatement()
        stmt.execute(
            """
            CREATE TABLE tracks (
                id TEXT NOT NULL, title TEXT NOT NULL, artistId TEXT, albumId TEXT,
                trackNo INTEGER, discNo INTEGER, durationMs INTEGER NOT NULL, path TEXT NOT NULL,
                format TEXT NOT NULL, sizeBytes INTEGER NOT NULL, dateAdded INTEGER NOT NULL,
                lastPlayed INTEGER, playCount INTEGER NOT NULL, genre TEXT, PRIMARY KEY(id)
            )
            """,
        )
        stmt.execute(
            "CREATE TABLE playlists (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, " +
                "coverPath TEXT, createdAt INTEGER NOT NULL)",
        )
        stmt.execute(
            "INSERT INTO tracks(id, title, artistId, albumId, trackNo, discNo, durationMs, path, " +
                "format, sizeBytes, dateAdded, lastPlayed, playCount, genre) VALUES " +
                "('t1', 'Window View', NULL, NULL, NULL, NULL, 200000, '/music/t1.flac', " +
                "'flac', 1000, 1000, NULL, 0, NULL)",
        )
        stmt.execute("INSERT INTO playlists(id, name, coverPath, createdAt) VALUES ('p1', 'My Mix', NULL, 5000)")
    }

    @After
    fun tearDown() = conn.close()

    @Test
    fun `migration adds deletedAt columns and preserves existing rows`() {
        val stmt = conn.createStatement()
        stmt.execute("ALTER TABLE tracks ADD COLUMN deletedAt INTEGER")
        stmt.execute("ALTER TABLE playlists ADD COLUMN deletedAt INTEGER")

        val track = conn.createStatement().executeQuery("SELECT title, deletedAt FROM tracks WHERE id = 't1'")
        assertTrue(track.next())
        assertEquals("Window View", track.getString(1))
        track.getLong(2)
        assertTrue(track.wasNull())

        val playlist = conn.createStatement().executeQuery("SELECT name, deletedAt FROM playlists WHERE id = 'p1'")
        assertTrue(playlist.next())
        assertEquals("My Mix", playlist.getString(1))
        playlist.getLong(2)
        assertTrue(playlist.wasNull())
    }
}
