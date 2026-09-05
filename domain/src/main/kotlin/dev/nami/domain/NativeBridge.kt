package dev.nami.domain

import dev.nami.core.model.TagResult

interface NativeBridge {
    suspend fun readTags(path: String): TagResult?
}
