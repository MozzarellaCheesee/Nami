package dev.nami.core.database.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.nami.core.database.CREATE_SEARCH_INDEX_SQL

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tracks ADD COLUMN genre TEXT")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(CREATE_SEARCH_INDEX_SQL)
        db.execSQL(
            """
            INSERT INTO search_index(itemId, type, title, subtitle, format, year)
            SELECT tracks.id, 'track', tracks.title, artists.name, tracks.format, albums.year
            FROM tracks
            LEFT JOIN artists ON tracks.artistId = artists.id
            LEFT JOIN albums ON tracks.albumId = albums.id
            """,
        )
        db.execSQL(
            """
            INSERT INTO search_index(itemId, type, title, subtitle, format, year)
            SELECT albums.id, 'album', albums.title, artists.name, NULL, NULL
            FROM albums
            LEFT JOIN artists ON albums.artistId = artists.id
            """,
        )
        db.execSQL(
            "INSERT INTO search_index(itemId, type, title, subtitle, format, year) " +
                "SELECT id, 'artist', name, NULL, NULL, NULL FROM artists",
        )
    }
}
