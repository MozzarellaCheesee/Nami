package dev.nami.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "tracks",
    foreignKeys = [
        ForeignKey(
            entity = ArtistEntity::class,
            parentColumns = ["id"],
            childColumns = ["artistId"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = AlbumEntity::class,
            parentColumns = ["id"],
            childColumns = ["albumId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index("albumId"),
        Index("artistId"),
        Index("dateAdded"),
        Index("lastPlayed"),
        // Not unique - CUE-derived tracks (см. cueStartMs/cueEndMs) intentionally share one path
        // across several rows, one per track carved out of the same physical album image file.
        Index(value = ["path"]),
    ],
)
data class TrackEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artistId: String?,
    val albumId: String?,
    val trackNo: Int?,
    val discNo: Int?,
    val durationMs: Long,
    val path: String,
    val format: String,
    val sizeBytes: Long,
    val dateAdded: Long,
    val lastPlayed: Long?,
    val playCount: Int,
    val genre: String? = null,
    val deletedAt: Long? = null,
    val artworkPath: String? = null,
    /** For the "Аудиотракт" screen - null for lossy formats lofty can't report a bit depth for. */
    val sampleRateHz: Int? = null,
    val bitDepth: Int? = null,
    val channels: Int? = null,
    /** ReplayGain-lite: RMS-loudness gain to reach a -18dBFS target, scanned once on first play.
     * Null until scanned (or if scanning failed) - not true EBU R128 (no true-peak/gating), just
     * simple RMS over the decoded track. */
    val replayGainDb: Float? = null,
    /** "Заметки к треку" (План.md §22.17) - free-text personal comment, null until the user
     * writes one. */
    val note: String? = null,
    /** План.md §22.13 "правила автоочереди" ("избегать треков, скипнутых 3+ раз") - incremented
     * when the user skips away from this track before it's played substantially (see
     * PlayerRepositoryImpl.skipNext), not on every skipNext call regardless of position. */
    val skipCount: Int = 0,
    /** BPM/key (План.md §3's Track.bpm/musicalKey) - scanned once via BpmKeyAnalyzer, cached
     * here like replayGainDb. Null until scanned or on a decode/analysis failure. */
    val bpm: Float? = null,
    val musicalKey: String? = null,
    /** План.md §3's full Track model - user rating, 1-5, null = not rated. */
    val rating: Int? = null,
    /** Same shape as lastPlayed, set once and never overwritten - "когда впервые услышал" is
     * data the app already has for free the first time incrementPlayCount fires. */
    val firstPlayed: Long? = null,
    /** CRC32 of the whole file, computed once at import - a real (if simple, no chromaprint)
     * per-file identity check that strengthens the title/artist/duration dedup heuristic
     * LibraryHealthReport already uses: two files with different tags but the same bytes (a
     * re-rip, a re-tag) now hash-match even when their metadata doesn't. */
    val fileHash: String? = null,
    /** Хвост группы C "CUE-поддержка" - см. core.model.Track's identical doc. */
    val cueStartMs: Long? = null,
    val cueEndMs: Long? = null,
    /** П.md §23.19 - 64-битный хеш RMS-огибающей (AudioFingerprint), считается по требованию из
     * "Здоровья библиотеки". В отличие от fileHash ловит один и тот же трек в разных форматах и
     * битрейтах, где байты файла заведомо не совпадают. Null - ещё не сканировался или не
     * декодировался. */
    val audioFingerprint: Long? = null,
    /** П.md §20 "цепочки" - "после этого трека всегда ставь вот этот". Одна ссылка, а не список:
     * цепочка по определению линейна, а из одиночных звеньев собирается любая длинная. Ссылка
     * намеренно без ForeignKey: удаление трека-преемника должно оставлять звено висящим и молча
     * игнорироваться при построении очереди, а не каскадом чистить чужие строки. */
    val chainNextTrackId: String? = null,
)
