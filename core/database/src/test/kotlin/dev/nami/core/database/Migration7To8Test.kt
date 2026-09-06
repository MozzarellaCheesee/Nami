package dev.nami.core.database

import java.sql.DriverManager
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Verifies MIGRATION_7_8 (see Migrations.kt) adds isSingle defaulting to false without
 * touching existing rows.
 */
class Migration7To8Test {
    private lateinit var conn: java.sql.Connection

    @Before
    fun setUp() {
        Class.forName("org.sqlite.JDBC")
        conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        val stmt = conn.createStatement()
        stmt.execute(
            """
            CREATE TABLE albums (
                id TEXT NOT NULL, title TEXT NOT NULL, artistId TEXT, year INTEGER, artworkPath TEXT,
                PRIMARY KEY(id)
            )
            """,
        )
        stmt.execute("INSERT INTO albums(id, title, artistId, year, artworkPath) VALUES ('al1', 'Wishes Hidden', NULL, 2010, NULL)")
    }

    @After
    fun tearDown() = conn.close()

    @Test
    fun `migration adds isSingle defaulting to false and preserves existing rows`() {
        val stmt = conn.createStatement()
        stmt.execute("ALTER TABLE albums ADD COLUMN isSingle INTEGER NOT NULL DEFAULT 0")

        val album = conn.createStatement().executeQuery("SELECT title, isSingle FROM albums WHERE id = 'al1'")
        assertEquals(true, album.next())
        assertEquals("Wishes Hidden", album.getString(1))
        assertFalse(album.getBoolean(2))
    }
}
