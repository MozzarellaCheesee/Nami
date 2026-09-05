package dev.nami.data.mapper

import dev.nami.core.database.entity.ArtistEntity
import dev.nami.core.model.Artist
import dev.nami.core.model.ArtistId

fun ArtistEntity.toDomain(): Artist = Artist(
    id = ArtistId(id),
    name = name,
    sortName = sortName,
)
