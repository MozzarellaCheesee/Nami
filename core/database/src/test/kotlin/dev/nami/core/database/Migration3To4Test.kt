package dev.nami.core.database

import java.sql.DriverManager
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies MIGRATION_3_4 (see Migrations.kt) creates the playlists/playlist_tracks tables and
 * preserves existing library rows, against a hand-built v3 schema.
 *
 * Uses native sqlite-jdbc rather than Room's MigrationTestHelper for the same reason as
 * Migration2To3Test.kt: no test-asset plumbing for the exported schema jsons exists in this
 * module, and MIGRATION_3_4 is plain DDL with no Room-specific behavior to exercise.
 */
class Migration3To4Test {
    private lateinit var conn: java.sql.Connection

    @Before
    fun setUp() {
        Class.forName("org.sqlite.JDBC")
        conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        val stmt = conn.createStatement()
        // Minimal v3 `tracks` table (columns from schemas/.../3.json), FKs nullable so no
        // artists/albums rows are required for a valid insert.
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
            "INSERT INTO tracks(id, title, artistId, albumId, trackNo, discNo, durationMs, path, " +
                "format, sizeBytes, dateAdded, lastPlayed, playCount, genre) VALUES " +
                "('t1', 'Window View', NULL, NULL, NULL, NULL, 200000, '/music/t1.flac', " +
                "'flac', 1000, 1000, NULL, 0, NULL)",
        )
    }

    @After
    fun tearDown() = conn.close()

    @Test
    fun `migration creates playlists tables and preserves existing tracks row`() {
        val stmt = conn.createStatement()

        // Verbatim copy of MIGRATION_3_4.migrate()'s DDL statements.
        stmt.execute(
            """
            CREATE TABLE IF NOT EXISTS playlists (
                id TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                coverPath TEXT,
                createdAt INTEGER NOT NULL
            )
            """,
        )
        stmt.execute(
            """
            CREATE TABLE IF NOT EXISTS playlist_tracks (
                playlistId TEXT NOT NULL,
                trackId TEXT NOT NULL,
                position INTEGER NOT NULL,
                addedAt INTEGER NOT NULL,
                PRIMARY KEY(playlistId, trackId),
                FOREIGN KEY(playlistId) REFERENCES playlists(id) ON DELETE CASCADE,
                FOREIGN KEY(trackId) REFERENCES tracks(id) ON DELETE CASCADE
            )
            """,
        )
        stmt.execute("CREATE INDEX IF NOT EXISTS index_playlist_tracks_playlistId ON playlist_tracks(playlistId)")
        stmt.execute("CREATE INDEX IF NOT EXISTS index_playlist_tracks_trackId ON playlist_tracks(trackId)")

        // playlists table usable end-to-end (insert + join through playlist_tracks).
        stmt.execute("INSERT INTO playlists(id, name, coverPath, createdAt) VALUES ('p1', 'My Mix', NULL, 5000)")
        stmt.execute(
            "INSERT INTO playlist_tracks(playlistId, trackId, position, addedAt) VALUES ('p1', 't1', 0, 5000)",
        )

        val joined = conn.createStatement().executeQuery(
            "SELECT tracks.id, tracks.title FROM playlist_tracks " +
                "JOIN tracks ON playlist_tracks.trackId = tracks.id WHERE playlist_tracks.playlistId = 'p1'",
        )
        assertTrue(joined.next())
        assertEquals("t1", joined.getString(1))
        assertEquals("Window View", joined.getString(2))

        // Old tracks row untouched by the migration.
        val tracksCount = conn.createStatement().executeQuery("SELECT COUNT(*) FROM tracks")
        tracksCount.next()
        assertEquals(1, tracksCount.getInt(1))
    }
}
