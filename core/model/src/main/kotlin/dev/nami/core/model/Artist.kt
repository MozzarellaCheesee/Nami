package dev.nami.core.model

@JvmInline
value class ArtistId(val value: String)

data class Artist(
    val id: ArtistId,
    val name: String,
    val sortName: String,
    val photoPath: String? = null,
)
