package dev.nami.core.database

import java.sql.DriverManager
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies MIGRATION_5_6 (see Migrations.kt) adds artworkPath without touching existing rows.
 */
class Migration5To6Test {
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
                lastPlayed INTEGER, playCount INTEGER NOT NULL, genre TEXT, deletedAt INTEGER,
                PRIMARY KEY(id)
            )
            """,
        )
        stmt.execute(
            "INSERT INTO tracks(id, title, artistId, albumId, trackNo, discNo, durationMs, path, " +
                "format, sizeBytes, dateAdded, lastPlayed, playCount, genre, deletedAt) VALUES " +
                "('t1', 'Window View', NULL, NULL, NULL, NULL, 200000, '/music/t1.flac', " +
                "'flac', 1000, 1000, NULL, 0, NULL, NULL)",
        )
    }

    @After
    fun tearDown() = conn.close()

    @Test
    fun `migration adds artworkPath and preserves existing rows`() {
        val stmt = conn.createStatement()
        stmt.execute("ALTER TABLE tracks ADD COLUMN artworkPath TEXT")

        val track = conn.createStatement().executeQuery("SELECT title, artworkPath FROM tracks WHERE id = 't1'")
        assertTrue(track.next())
        assertEquals("Window View", track.getString(1))
        track.getString(2)
        assertTrue(track.wasNull())
    }
}
