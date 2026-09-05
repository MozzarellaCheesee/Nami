package dev.nami.core.database

import java.sql.DriverManager
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Verifies MIGRATION_2_3's backfill INSERT...SELECT statements (see Migrations.kt) against a
 * v2 schema populated with pre-existing library rows, ensuring an upgrading user's search index
 * is not left empty until their next import.
 *
 * Uses native sqlite-jdbc rather than Room's MigrationTestHelper for the same reason as
 * SearchDaoTest.kt: Robolectric's bundled SQLite lacks the FTS5 module on this environment.
 * The three INSERT...SELECT statements are copied verbatim from MIGRATION_2_3.migrate() and run
 * against hand-built v2 tables (schema copied from schemas/.../2.json), then the resulting
 * search_index contents are asserted.
 */
class Migration2To3Test {
    private lateinit var conn: java.sql.Connection

    @Before
    fun setUp() {
        Class.forName("org.sqlite.JDBC")
        conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        val stmt = conn.createStatement()
        stmt.execute(
            "CREATE TABLE artists (id TEXT NOT NULL, name TEXT NOT NULL, sortName TEXT NOT NULL, PRIMARY KEY(id))",
        )
        stmt.execute(
            "CREATE TABLE albums (id TEXT NOT NULL, title TEXT NOT NULL, artistId TEXT, year INTEGER, " +
                "artworkPath TEXT, PRIMARY KEY(id))",
        )
        stmt.execute(
            "CREATE TABLE tracks (id TEXT NOT NULL, title TEXT NOT NULL, artistId TEXT, albumId TEXT, " +
                "format TEXT NOT NULL, PRIMARY KEY(id))",
        )
        stmt.execute(CREATE_SEARCH_INDEX_SQL)
    }

    @After
    fun tearDown() = conn.close()

    @Test
    fun `migration backfills existing tracks, albums and artists into search_index`() {
        val stmt = conn.createStatement()
        stmt.execute("INSERT INTO artists(id, name, sortName) VALUES ('a1', 'Farewell225', 'Farewell225')")
        stmt.execute(
            "INSERT INTO albums(id, title, artistId, year) VALUES ('al1', 'Doujin Compilation', 'a1', 2023)",
        )
        stmt.execute(
            "INSERT INTO tracks(id, title, artistId, albumId, format) " +
                "VALUES ('t1', 'Window View', 'a1', 'al1', 'flac')",
        )

        // Verbatim copy of MIGRATION_2_3.migrate()'s three backfill statements.
        stmt.execute(
            """
            INSERT INTO search_index(itemId, type, title, subtitle, format, year)
            SELECT tracks.id, 'track', tracks.title, artists.name, tracks.format, albums.year
            FROM tracks
            LEFT JOIN artists ON tracks.artistId = artists.id
            LEFT JOIN albums ON tracks.albumId = albums.id
            """,
        )
        stmt.execute(
            """
            INSERT INTO search_index(itemId, type, title, subtitle, format, year)
            SELECT albums.id, 'album', albums.title, artists.name, NULL, NULL
            FROM albums
            LEFT JOIN artists ON albums.artistId = artists.id
            """,
        )
        stmt.execute(
            "INSERT INTO search_index(itemId, type, title, subtitle, format, year) " +
                "SELECT id, 'artist', name, NULL, NULL, NULL FROM artists",
        )

        val rs = conn.createStatement().executeQuery(
            "SELECT itemId, type, title, subtitle, format, year FROM search_index ORDER BY type",
        )
        val rows = mutableListOf<List<String?>>()
        while (rs.next()) {
            rows.add(listOf(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)))
        }

        assertEquals(3, rows.size)
        assertEquals(listOf("al1", "album", "Doujin Compilation", "Farewell225", null, null), rows[0])
        assertEquals(listOf("a1", "artist", "Farewell225", null, null, null), rows[1])
        assertEquals(listOf("t1", "track", "Window View", "Farewell225", "flac", "2023"), rows[2])
    }
}
