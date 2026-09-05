package dev.nami.domain

sealed interface ImportSource {
    data class Files(val uris: List<String>) : ImportSource
}
