package dev.nami.domain

import dev.nami.core.model.Track
import kotlin.random.Random

/** Хвост группы C "офлайн-радио от трека" -- без сети и без реального ML-рекомендателя (см.
 * этого проекта установившееся правило не выдавать эвристику за то, чем она не является):
 * похожесть считается по тому, что уже есть в библиотеке локально -- тот же артист, тот же жанр,
 * близкий BPM. Не история прослушиваний, не аудио-анализ содержимого. */
object RadioBuilder {
    private const val QUEUE_SIZE = 40
    private const val ARTIST_SCORE = 3
    private const val GENRE_SCORE = 2
    private const val BPM_SCORE = 1
    private const val BPM_CLOSE_ENOUGH = 15f

    /** [seed] first, then up to [QUEUE_SIZE]-1 more tracks from [library] (seed excluded from the
     * pool), weighted random draw favoring closer matches -- not a flat sort, so two radios from
     * the same seed don't play in the exact same order every time. */
    fun build(seed: Track, library: List<Track>, random: Random = Random.Default): List<Track> {
        val pool = library.filter { it.id != seed.id }
        if (pool.isEmpty()) return listOf(seed)

        val scored = pool.map { candidate -> candidate to score(seed, candidate) }.toMutableList()
        val queue = mutableListOf(seed)
        val target = minOf(QUEUE_SIZE, pool.size + 1)

        while (queue.size < target && scored.isNotEmpty()) {
            // Weight = score+1 (so a zero-score track can still be picked, just rarely) --
            // total-weight roulette pick, same idea as a weighted shuffle.
            val totalWeight = scored.sumOf { (it.second + 1).toLong() }
            var pick = (random.nextDouble() * totalWeight).toLong()
            var index = 0
            while (index < scored.size - 1 && pick >= scored[index].second + 1) {
                pick -= scored[index].second + 1
                index++
            }
            queue.add(scored.removeAt(index).first)
        }
        return queue
    }

    private fun score(seed: Track, candidate: Track): Int {
        var score = 0
        if (seed.artistId != null && seed.artistId == candidate.artistId) score += ARTIST_SCORE
        if (!seed.genre.isNullOrBlank() && seed.genre.equals(candidate.genre, ignoreCase = true)) score += GENRE_SCORE
        val seedBpm = seed.bpm
        val candidateBpm = candidate.bpm
        if (seedBpm != null && candidateBpm != null && kotlin.math.abs(seedBpm - candidateBpm) <= BPM_CLOSE_ENOUGH) {
            score += BPM_SCORE
        }
        return score
    }
}
