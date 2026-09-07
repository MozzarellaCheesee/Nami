package dev.nami.core.nativebridge

import dev.nami.core.model.TagResult
import dev.nami.domain.NativeBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TagReaderNativeBridge @Inject constructor() : NativeBridge {

    override suspend fun readTags(path: String): TagResult? = withContext(Dispatchers.IO) {
        try {
            val native = uniffi.tag_reader.readTags(path) ?: return@withContext null
            TagResult(
                title = native.title,
                artist = native.artist,
                album = native.album,
                albumArtist = native.albumArtist,
                trackNo = native.trackNo?.toInt(),
                discNo = native.discNo?.toInt(),
                genre = native.genre,
                year = native.year?.toInt(),
                durationMs = native.durationMs.toLong(),
                artwork = native.artwork,
                artworkMime = native.artworkMime,
                lyrics = native.lyrics,
                sampleRateHz = native.sampleRateHz?.toInt(),
                bitDepth = native.bitDepth?.toInt(),
                channels = native.channels?.toInt(),
            )
        } catch (e: Exception) {
            // Rust panics surface here as exceptions from the generated JNI layer;
            // per Часть XII this must never crash the app.
            null
        }
    }
}
