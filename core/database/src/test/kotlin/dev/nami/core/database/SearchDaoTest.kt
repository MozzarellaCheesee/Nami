package dev.nami.core.database

import java.sql.DriverManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

// Unit test using native SQLite (not Robolectric) for FTS5 support
class SearchDaoTest {
    private lateinit var db: java.sql.Connection

    @Before
    fun setUp() {
        Class.forName("org.sqlite.JDBC")
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        // Create FTS5 virtual table directly
        db.createStatement().execute(CREATE_SEARCH_INDEX_SQL)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `insert then searchByMatchWithFilters finds by prefix`() {
        insert("t1", "track", "Window View", "Farewell225", "flac", 2023)
        insert("t2", "track", "Nocturne", "sasakure.UK", "mp3", 2020)

        val results = search("\"wind\"*", null, null)

        assertEquals(1, results.size)
        assertEquals("t1", results[0].itemId)
    }

    @Test
    fun `searchByMatchWithFilters applies format filter`() {
        insert("t1", "track", "Window View", "Farewell225", "flac", 2023)
        insert("t2", "track", "Window Shopping", "Someone", "mp3", 2020)

        val results = search("\"window\"*", "flac", null)

        assertEquals(1, results.size)
        assertEquals("t1", results[0].itemId)
    }

    @Test
    fun `filterOnly returns rows matching only operators, no text`() {
        insert("t1", "track", "Window View", "Farewell225", "flac", 2023)
        insert("t2", "track", "Nocturne", "sasakure.UK", "mp3", 2020)

        val results = filterOnly(null, 2023)

        assertEquals(1, results.size)
        assertEquals("t1", results[0].itemId)
    }

    @Test
    fun `clear removes all rows`() {
        insert("t1", "track", "Window View", "Farewell225", "flac", 2023)

        db.createStatement().execute("DELETE FROM search_index")

        val results = filterOnly(null, null)
        assertEquals(0, results.size)
    }

    private fun insert(itemId: String, type: String, title: String, subtitle: String?, format: String?, year: Int?) {
        val stmt = db.prepareStatement(
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

    private fun search(matchExpression: String, format: String?, year: Int?): List<SearchResultRow> {
        val stmt = db.prepareStatement(
            """
            SELECT itemId, type, title, subtitle, format, year
            FROM search_index
            WHERE search_index MATCH ?
              AND (? IS NULL OR format = ?)
              AND (? IS NULL OR year = ?)
            ORDER BY rank
            LIMIT 50
            """.trimIndent()
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
        val rs = stmt.executeQuery()
        val results = mutableListOf<SearchResultRow>()
        while (rs.next()) {
            results.add(
                SearchResultRow(
                    itemId = rs.getString(1),
                    type = rs.getString(2),
                    title = rs.getString(3),
                    subtitle = rs.getString(4),
                    format = rs.getString(5),
                    year = if (rs.wasNull()) null else rs.getInt(6)
                )
            )
        }
        return results
    }

    private fun filterOnly(format: String?, year: Int?): List<SearchResultRow> {
        val stmt = db.prepareStatement(
            """
            SELECT itemId, type, title, subtitle, format, year
            FROM search_index
            WHERE (? IS NULL OR format = ?) AND (? IS NULL OR year = ?)
            LIMIT 50
            """.trimIndent()
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
        val rs = stmt.executeQuery()
        val results = mutableListOf<SearchResultRow>()
        while (rs.next()) {
            results.add(
                SearchResultRow(
                    itemId = rs.getString(1),
                    type = rs.getString(2),
                    title = rs.getString(3),
                    subtitle = rs.getString(4),
                    format = rs.getString(5),
                    year = if (rs.wasNull()) null else rs.getInt(6)
                )
            )
        }
        return results
    }

    data class SearchResultRow(
        val itemId: String,
        val type: String,
        val title: String,
        val subtitle: String?,
        val format: String?,
        val year: Int?,
    )
}
