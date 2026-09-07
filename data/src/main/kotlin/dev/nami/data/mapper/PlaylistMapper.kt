package dev.nami.data.mapper

import dev.nami.core.database.dao.PlaylistDao
import dev.nami.core.database.entity.PlaylistEntity
import dev.nami.core.model.Playlist
import dev.nami.core.model.PlaylistId
import dev.nami.core.model.PlaylistSummary
import dev.nami.domain.TrashedPlaylist

fun PlaylistDao.PlaylistListRow.toDomain(): PlaylistSummary = PlaylistSummary(
    id = PlaylistId(id),
    name = name,
    coverPath = coverPath,
    trackCount = trackCount,
    isLiked = isLiked,
    isSmart = isSmart,
)

fun PlaylistEntity.toDomain(): Playlist = Playlist(
    id = PlaylistId(id),
    name = name,
    coverPath = coverPath,
    isLiked = isLiked,
    isSmart = isSmart,
    smartQueryJson = smartQueryJson,
)

fun PlaylistDao.PlaylistListRow.toTrashedDomain(): TrashedPlaylist = TrashedPlaylist(
    playlist = toDomain(),
    deletedAt = requireNotNull(deletedAt),
)
