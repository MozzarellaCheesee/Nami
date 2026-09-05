package dev.nami.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.SkipQueryVerification

@Dao
interface SearchDao {
    @Query("DELETE FROM search_index")
    @SkipQueryVerification
    suspend fun clear()

    @Query(
        "INSERT INTO search_index(itemId, type, title, subtitle, format, year) " +
            "VALUES (:itemId, :type, :title, :subtitle, :format, :year)",
    )
    @SkipQueryVerification
    suspend fun insert(itemId: String, type: String, title: String, subtitle: String?, format: String?, year: Int?)

    @Query(
        """
        SELECT itemId, type, title, subtitle, format, year
        FROM search_index
        WHERE search_index MATCH :matchExpression
          AND (:format IS NULL OR format = :format)
          AND (:year IS NULL OR year = :year)
        ORDER BY rank
        LIMIT 50
        """,
    )
    @SkipQueryVerification
    suspend fun searchByMatchWithFilters(matchExpression: String, format: String?, year: Int?): List<SearchResultRow>

    @Query(
        """
        SELECT itemId, type, title, subtitle, format, year
        FROM search_index
        WHERE (:format IS NULL OR format = :format) AND (:year IS NULL OR year = :year)
        LIMIT 50
        """,
    )
    @SkipQueryVerification
    suspend fun filterOnly(format: String?, year: Int?): List<SearchResultRow>

    data class SearchResultRow(
        val itemId: String,
        val type: String,
        val title: String,
        val subtitle: String?,
        val format: String?,
        val year: Int?,
    )
}
