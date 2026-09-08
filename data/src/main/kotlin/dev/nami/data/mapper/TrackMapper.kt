package dev.nami.data.mapper

import dev.nami.core.database.dao.PlaylistTrackDao
import dev.nami.core.database.dao.TrackDao
import dev.nami.core.database.entity.TrackEntity
import dev.nami.core.model.AlbumId
import dev.nami.core.model.ArtistId
import dev.nami.core.model.Track
import dev.nami.core.model.TrackId
import dev.nami.domain.TrashedTrack

fun TrackEntity.toDomain(albumArtworkPath: String? = null, artistName: String? = null): Track = Track(
    id = TrackId(id),
    title = title,
    artistId = artistId?.let(::ArtistId),
    albumId = albumId?.let(::AlbumId),
    trackNo = trackNo,
    discNo = discNo,
    durationMs = durationMs,
    path = path,
    format = format,
    sizeBytes = sizeBytes,
    dateAdded = dateAdded,
    lastPlayed = lastPlayed,
    playCount = playCount,
    genre = genre,
    albumArtworkPath = albumArtworkPath,
    artistName = artistName,
    sampleRateHz = sampleRateHz,
    bitDepth = bitDepth,
    channels = channels,
    replayGainDb = replayGainDb,
    note = note,
    skipCount = skipCount,
    bpm = bpm,
    musicalKey = musicalKey,
    rating = rating,
    firstPlayed = firstPlayed,
    fileHash = fileHash,
    cueStartMs = cueStartMs,
    cueEndMs = cueEndMs,
)

fun TrackDao.TrackWithArtwork.toDomain(): Track = track.toDomain(albumArtworkPath = albumArtworkPath, artistName = artistName)

fun PlaylistTrackDao.TrackWithArtwork.toDomain(): Track = track.toDomain(albumArtworkPath = albumArtworkPath, artistName = artistName)

fun TrackEntity.toTrashedDomain(): TrashedTrack = TrashedTrack(track = toDomain(), deletedAt = requireNotNull(deletedAt))
