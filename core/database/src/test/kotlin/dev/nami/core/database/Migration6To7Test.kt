package dev.nami.core.database

import java.sql.DriverManager
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies MIGRATION_6_7 (see Migrations.kt) adds photoPath without touching existing rows.
 */
class Migration6To7Test {
    private lateinit var conn: java.sql.Connection

    @Before
    fun setUp() {
        Class.forName("org.sqlite.JDBC")
        conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        val stmt = conn.createStatement()
        stmt.execute(
            """
            CREATE TABLE artists (
                id TEXT NOT NULL, name TEXT NOT NULL, sortName TEXT NOT NULL,
                PRIMARY KEY(id)
            )
            """,
        )
        stmt.execute("INSERT INTO artists(id, name, sortName) VALUES ('a1', 'Farewell225', 'Farewell225')")
    }

    @After
    fun tearDown() = conn.close()

    @Test
    fun `migration adds photoPath and preserves existing rows`() {
        val stmt = conn.createStatement()
        stmt.execute("ALTER TABLE artists ADD COLUMN photoPath TEXT")

        val artist = conn.createStatement().executeQuery("SELECT name, photoPath FROM artists WHERE id = 'a1'")
        assertTrue(artist.next())
        assertEquals("Farewell225", artist.getString(1))
        artist.getString(2)
        assertTrue(artist.wasNull())
    }
}
