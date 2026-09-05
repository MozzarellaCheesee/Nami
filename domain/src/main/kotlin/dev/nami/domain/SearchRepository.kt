package dev.nami.domain

interface SearchRepository {
    suspend fun search(query: String): List<SearchResult>
    suspend fun rebuildIndex()
}
