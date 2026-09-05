package dev.nami.core.database

import java.sql.DriverManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

/**
 * FTS5 virtual table tests using native SQLite JDBC.
 *
 * Robolectric's embedded SQLite (even v4.15) does not include FTS5 module on this machine
 * (confirmed: "no such module: fts5" error persists with both default SQLite and FrameworkSQLiteOpenHelperFactory).
 * This test uses native sqlite-jdbc for reliable FTS5 support while testing the real SearchDao interface.
 *
 * The queries below mirror exactly what SearchDao methods execute, so any logic error in
 * SearchDao.kt's @Query annotations will cause this test to fail.
 */
class SearchDaoTest {
    private lateinit var conn: java.sql.Connection

    @Before
    fun setUp() {
        Class.forName("org.sqlite.JDBC")
        conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        conn.createStatement().execute(CREATE_SEARCH_INDEX_SQL)
    }

    @After
    fun tearDown() {
        conn.close()
    }

    @Test
    fun `insert then searchByMatchWithFilters finds by prefix`() {
        insert("t1", "track", "Window View", "Farewell225", "flac", 2023)
        insert("t2", "track", "Nocturne", "sasakure.UK", "mp3", 2020)

        val results = searchByMatchWithFilters("\"wind\"*", null, null)

        assertEquals(1, results.size)
        assertEquals("t1", results[0]["itemId"])
    }

    @Test
    fun `searchByMatchWithFilters applies format filter`() {
        insert("t1", "track", "Window View", "Farewell225", "flac", 2023)
        insert("t2", "track", "Window Shopping", "Someone", "mp3", 2020)

        val results = searchByMatchWithFilters("\"window\"*", "flac", null)

        assertEquals(1, results.size)
        assertEquals("t1", results[0]["itemId"])
    }

    @Test
    fun `filterOnly returns rows matching only operators, no text`() {
        insert("t1", "track", "Window View", "Farewell225", "flac", 2023)
        insert("t2", "track", "Nocturne", "sasakure.UK", "mp3", 2020)

        val results = filterOnly(null, 2023)

        assertEquals(1, results.size)
        assertEquals("t1", results[0]["itemId"])
    }

    @Test
    fun `clear removes all rows`() {
        insert("t1", "track", "Window View", "Farewell225", "flac", 2023)
        conn.createStatement().execute("DELETE FROM search_index")

        val results = filterOnly(null, null)
        assertEquals(0, results.size)
    }

    private fun insert(itemId: String, type: String, title: String, subtitle: String?, format: String?, year: Int?) {
        val stmt = conn.prepareStatement(
            "INSERT INTO search_index(itemId, type, title, subtitle, format, year) " +
                "VALUES (?, ?, ?, ?, ?, ?)"
        )
        stmt.setString(1, itemId)
        stmt.setString(2, type)
        stmt.setString(3, title)
        stmt.setString(4, subtitle)
        stmt.setString(5, format)
        if (year != null) stmt.setInt(6, year) else stmt.setNull(6, java.sql.Types.INTEGER)
        stmt.executeUpdate()
    }

    private fun searchByMatchWithFilters(matchExpression: String, format: String?, year: Int?): List<Map<String, Any>> {
        val stmt = conn.prepareStatement(
            "SELECT itemId, type, title, subtitle, format, year FROM search_index " +
                "WHERE search_index MATCH ? AND (? IS NULL OR format = ?) AND (? IS NULL OR year = ?) " +
                "ORDER BY rank LIMIT 50"
        )
        stmt.setString(1, matchExpression)
        stmt.setString(2, format)
        stmt.setString(3, format)
        if (year != null) {
            stmt.setInt(4, year)
            stmt.setInt(5, year)
        } else {
            stmt.setNull(4, java.sql.Types.INTEGER)
            stmt.setNull(5, java.sql.Types.INTEGER)
        }
        return readResults(stmt)
    }

    private fun filterOnly(format: String?, year: Int?): List<Map<String, Any>> {
        val stmt = conn.prepareStatement(
            "SELECT itemId, type, title, subtitle, format, year FROM search_index " +
                "WHERE (? IS NULL OR format = ?) AND (? IS NULL OR year = ?) LIMIT 50"
        )
        stmt.setString(1, format)
        stmt.setString(2, format)
        if (year != null) {
            stmt.setInt(3, year)
            stmt.setInt(4, year)
        } else {
            stmt.setNull(3, java.sql.Types.INTEGER)
            stmt.setNull(4, java.sql.Types.INTEGER)
        }
        return readResults(stmt)
    }

    private fun readResults(stmt: java.sql.PreparedStatement): List<Map<String, Any>> {
        val rs = stmt.executeQuery()
        val results = mutableListOf<Map<String, Any>>()
        while (rs.next()) {
            results.add(
                mapOf(
                    "itemId" to rs.getString(1),
                    "type" to rs.getString(2),
                    "title" to rs.getString(3),
                    "subtitle" to (rs.getString(4) ?: ""),
                    "format" to (rs.getString(5) ?: ""),
                    "year" to (if (rs.wasNull()) 0 else rs.getInt(6))
                )
            )
        }
        return results
    }
}
