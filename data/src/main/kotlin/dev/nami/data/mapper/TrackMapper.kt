package dev.nami.data.mapper

import dev.nami.core.database.entity.TrackEntity
import dev.nami.core.model.AlbumId
import dev.nami.core.model.ArtistId
import dev.nami.core.model.Track
import dev.nami.core.model.TrackId
import dev.nami.domain.TrashedTrack

fun TrackEntity.toDomain(): Track = Track(
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
)

fun TrackEntity.toTrashedDomain(): TrashedTrack = TrashedTrack(track = toDomain(), deletedAt = requireNotNull(deletedAt))
