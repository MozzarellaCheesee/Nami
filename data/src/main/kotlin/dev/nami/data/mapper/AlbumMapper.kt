package dev.nami.data.mapper

import dev.nami.core.database.dao.AlbumDao
import dev.nami.core.database.entity.AlbumEntity
import dev.nami.core.model.Album
import dev.nami.core.model.AlbumId
import dev.nami.core.model.AlbumSummary
import dev.nami.core.model.ArtistId
import dev.nami.domain.TrashedAlbum

fun AlbumDao.AlbumListRow.toDomain(): AlbumSummary = AlbumSummary(
    id = AlbumId(id),
    title = title,
    artistName = artistName,
    artworkPath = artworkPath,
)

fun AlbumDao.TrashedAlbumRow.toTrashedDomain(): TrashedAlbum = TrashedAlbum(
    album = AlbumSummary(id = AlbumId(id), title = title, artistName = artistName, artworkPath = artworkPath),
    deletedAt = deletedAt,
)

fun AlbumEntity.toDomain(): Album = Album(
    id = AlbumId(id),
    title = title,
    artistId = artistId?.let(::ArtistId),
    year = year,
    artworkPath = artworkPath,
    isSingle = isSingle,
)
