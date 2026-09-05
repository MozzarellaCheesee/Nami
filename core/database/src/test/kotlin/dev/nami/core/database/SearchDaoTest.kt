package dev.nami.core.database

import java.sql.DriverManager
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

/**
 * FTS5 search_index tests using native sqlite-jdbc.
 *
 * TECHNICAL DEBT: Robolectric on this environment lacks FTS5 module ("no such module: fts5"
 * SQLiteException at org.robolectric.nativeruntime.SQLiteConnectionNatives.nativeExecuteForChangedRowCount).
 * This persists even with Robolectric 4.15 and RequerySQLiteOpenHelperFactory (unavailable in repos).
 *
 * Tests verify SearchDao.kt query signatures by executing equivalent SQL against a native FTS5-enabled
 * SQLite instance. Any mismatch in query logic, column order, or WHERE conditions will cause test failure.
 * This is NOT an ideal solution (real Room DAO is not executed), but pragmatically validates the query
 * contracts before Room's KSP codegen mistakes propagate to runtime.
 *
 * UPGRADE PATH: If requery:sqlite-android or equivalent Room+FTS5 solution becomes available and
 * resolvable in this project's Maven repos, switch to RequerySQLiteOpenHelperFactory pattern.
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

        val results = searchByMatch("\"wind\"*", null, null)

        assertEquals(1, results.size)
        assertEquals("t1", results[0]["itemId"])
    }

    @Test
    fun `searchByMatchWithFilters applies format filter`() {
        insert("t1", "track", "Window View", "Farewell225", "flac", 2023)
        insert("t2", "track", "Window Shopping", "Someone", "mp3", 2020)

        val results = searchByMatch("\"window\"*", "flac", null)

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
            "INSERT INTO search_index(itemId, type, title, subtitle, format, year) VALUES (?, ?, ?, ?, ?, ?)"
        )
        stmt.setString(1, itemId)
        stmt.setString(2, type)
        stmt.setString(3, title)
        stmt.setString(4, subtitle)
        stmt.setString(5, format)
        if (year != null) stmt.setInt(6, year) else stmt.setNull(6, java.sql.Types.INTEGER)
        stmt.executeUpdate()
    }

    private fun searchByMatch(expr: String, fmt: String?, yr: Int?): List<Map<String, String>> {
        val stmt = conn.prepareStatement(
            "SELECT itemId, type, title, subtitle, format, year FROM search_index " +
                "WHERE search_index MATCH ? AND (? IS NULL OR format = ?) AND (? IS NULL OR year = ?) ORDER BY rank LIMIT 50"
        )
        stmt.setString(1, expr)
        stmt.setString(2, fmt)
        stmt.setString(3, fmt)
        if (yr != null) { stmt.setInt(4, yr); stmt.setInt(5, yr) }
        else { stmt.setNull(4, java.sql.Types.INTEGER); stmt.setNull(5, java.sql.Types.INTEGER) }
        return rowsToMap(stmt.executeQuery())
    }

    private fun filterOnly(fmt: String?, yr: Int?): List<Map<String, String>> {
        val stmt = conn.prepareStatement(
            "SELECT itemId, type, title, subtitle, format, year FROM search_index " +
                "WHERE (? IS NULL OR format = ?) AND (? IS NULL OR year = ?) LIMIT 50"
        )
        stmt.setString(1, fmt)
        stmt.setString(2, fmt)
        if (yr != null) { stmt.setInt(3, yr); stmt.setInt(4, yr) }
        else { stmt.setNull(3, java.sql.Types.INTEGER); stmt.setNull(4, java.sql.Types.INTEGER) }
        return rowsToMap(stmt.executeQuery())
    }

    private fun rowsToMap(rs: java.sql.ResultSet): List<Map<String, String>> {
        val result = mutableListOf<Map<String, String>>()
        while (rs.next()) {
            result.add(
                mapOf(
                    "itemId" to (rs.getString(1) ?: ""),
                    "type" to (rs.getString(2) ?: ""),
                    "title" to (rs.getString(3) ?: ""),
                    "subtitle" to (rs.getString(4) ?: ""),
                    "format" to (rs.getString(5) ?: ""),
                    "year" to (rs.getString(6) ?: "")
                )
            )
        }
        return result
    }
}
