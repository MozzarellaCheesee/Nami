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

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tracks ADD COLUMN deletedAt INTEGER")
        db.execSQL("ALTER TABLE playlists ADD COLUMN deletedAt INTEGER")
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tracks ADD COLUMN artworkPath TEXT")
    }
}

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE artists ADD COLUMN photoPath TEXT")
    }
}

val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE albums ADD COLUMN isSingle INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS album_artists (
                albumId TEXT NOT NULL,
                artistId TEXT NOT NULL,
                PRIMARY KEY(albumId, artistId),
                FOREIGN KEY(albumId) REFERENCES albums(id) ON DELETE CASCADE,
                FOREIGN KEY(artistId) REFERENCES artists(id) ON DELETE CASCADE
            )
            """,
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_album_artists_artistId ON album_artists(artistId)")
        // Every album already had at most one artist - seed the join table from it so existing
        // albums keep showing their artist once queries switch to reading from this table.
        db.execSQL(
            """
            INSERT INTO album_artists(albumId, artistId)
            SELECT id, artistId FROM albums WHERE artistId IS NOT NULL
            """,
        )
    }
}

val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS vocabulary (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                word TEXT NOT NULL,
                reading TEXT NOT NULL,
                meaning TEXT NOT NULL,
                contextLine TEXT NOT NULL,
                trackTitle TEXT NOT NULL,
                addedAt INTEGER NOT NULL
            )
            """,
        )
    }
}

/** Этап 4's "Аудиотракт" screen - existing tracks just show "неизвестно" for these until
 * re-imported (a real analyzer/re-scan pass is a separate feature, not this migration's job). */
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tracks ADD COLUMN sampleRateHz INTEGER")
        db.execSQL("ALTER TABLE tracks ADD COLUMN bitDepth INTEGER")
        db.execSQL("ALTER TABLE tracks ADD COLUMN channels INTEGER")
    }
}

val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tracks ADD COLUMN replayGainDb REAL")
    }
}

val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Defaults to 0 (false) for every existing row - the Liked playlist itself is created
        // lazily, the first time anything is liked, not backfilled here.
        db.execSQL("ALTER TABLE playlists ADD COLUMN isLiked INTEGER NOT NULL DEFAULT 0")
    }
}

/** Этап 6's "Метки моментов" (План.md §22.1) - a new table, no existing columns touched. */
val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS moments (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                trackId TEXT NOT NULL,
                positionMs INTEGER NOT NULL,
                label TEXT NOT NULL,
                color INTEGER NOT NULL,
                createdAt INTEGER NOT NULL
            )
            """,
        )
    }
}

/** Этап 6's "A-B петли с сохранением" (План.md §22.2) - a new table, no existing columns touched. */
/** Этап 6's "заметки к треку" (План.md §22.17). */
val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tracks ADD COLUMN note TEXT")
    }
}

/** Этап 6's "главы и закладки" (План.md §22.16) - reuses the moments table, see MomentEntity. */
val MIGRATION_16_17 = object : Migration(16, 17) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE moments ADD COLUMN isChapter INTEGER NOT NULL DEFAULT 0")
    }
}

/** Real album-level trash - see AlbumEntity.deletedAt's own doc for why the old
 * EXISTS(non-deleted track)-only approach could leave a "deleted" album still visible. */
val MIGRATION_17_18 = object : Migration(17, 18) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE albums ADD COLUMN deletedAt INTEGER")
    }
}

/** Этап 6's "правила автоочереди" (План.md §22.13) - tracks how often a track gets skipped. */
val MIGRATION_18_19 = object : Migration(18, 19) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tracks ADD COLUMN skipCount INTEGER NOT NULL DEFAULT 0")
    }
}

/** BPM/тональность (План.md §3) - BpmKeyAnalyzer's cached result per track. */
val MIGRATION_19_20 = object : Migration(19, 20) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tracks ADD COLUMN bpm REAL")
        db.execSQL("ALTER TABLE tracks ADD COLUMN musicalKey TEXT")
    }
}

/** Умные плейлисты (П.md §20). */
val MIGRATION_20_21 = object : Migration(20, 21) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE playlists ADD COLUMN isSmart INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE playlists ADD COLUMN smartQueryJson TEXT")
    }
}

/** B1 "Статистика" (План.md §23.22) - play history log. */
val MIGRATION_21_22 = object : Migration(21, 22) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS play_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                trackId TEXT NOT NULL,
                playedAt INTEGER NOT NULL,
                durationMs INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }
}

/** П.md §3 "модель данных" - rest of Track's full field list closed out. */
val MIGRATION_22_23 = object : Migration(22, 23) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tracks ADD COLUMN rating INTEGER")
        db.execSQL("ALTER TABLE tracks ADD COLUMN firstPlayed INTEGER")
        db.execSQL("ALTER TABLE tracks ADD COLUMN fileHash TEXT")
    }
}

/** П.md §3 - пользовательские цветные теги (Tag/TrackTag), отдельно от genre. */
val MIGRATION_23_24 = object : Migration(23, 24) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS tags (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, colorArgb INTEGER NOT NULL)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS track_tags (
                trackId TEXT NOT NULL,
                tagId TEXT NOT NULL,
                PRIMARY KEY(trackId, tagId),
                FOREIGN KEY(trackId) REFERENCES tracks(id) ON DELETE CASCADE,
                FOREIGN KEY(tagId) REFERENCES tags(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_track_tags_trackId ON track_tags(trackId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_track_tags_tagId ON track_tags(tagId)")
    }
}

/** Хвост группы C "CUE-поддержка" - несколько tracks-строк делят один физический файл. */
val MIGRATION_24_25 = object : Migration(24, 25) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tracks ADD COLUMN cueStartMs INTEGER")
        db.execSQL("ALTER TABLE tracks ADD COLUMN cueEndMs INTEGER")
        // Was unique - CUE tracks now intentionally share one path across several rows.
        db.execSQL("DROP INDEX IF EXISTS index_tracks_path")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_tracks_path ON tracks(path)")
    }
}

val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS loops (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                trackId TEXT NOT NULL,
                startMs INTEGER NOT NULL,
                endMs INTEGER NOT NULL,
                name TEXT NOT NULL,
                createdAt INTEGER NOT NULL
            )
            """,
        )
    }
}
