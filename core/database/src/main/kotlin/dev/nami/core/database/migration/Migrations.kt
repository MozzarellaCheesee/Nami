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

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS playlists (
                id TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                coverPath TEXT,
                createdAt INTEGER NOT NULL
            )
            """,
        )
        db.execSQL(
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
        db.execSQL("CREATE INDEX IF NOT EXISTS index_playlist_tracks_playlistId ON playlist_tracks(playlistId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_playlist_tracks_trackId ON playlist_tracks(trackId)")
    }
}
