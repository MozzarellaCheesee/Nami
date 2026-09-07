package dev.nami.domain

data class MusicBrainzCandidate(
    val title: String,
    val artistName: String?,
    val albumName: String?,
    val year: Int?,
    val genre: String?,
)
