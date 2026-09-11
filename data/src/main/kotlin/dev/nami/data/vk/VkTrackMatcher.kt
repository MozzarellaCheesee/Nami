package dev.nami.data.vk

import dev.nami.data.spotify.SpotifyTrackMeta
import kotlin.math.abs

/**
 * Алгоритм сопоставления Spotify-трека с кандидатами из VK Музыки.
 * Реализован в строгом соответствии с «Скачивание из спотика.md» §3 (Шаг 2).
 */
object VkTrackMatcher {

    private val SUSPICIOUS_WORDS = listOf(
        "кавер", "cover", "remix", "ремикс", "live", "лайв",
        "клип", "караоке", "karaoke", "tribute", "трибьют"
    )

    fun findBestMatch(spotify: SpotifyTrackMeta, vkResults: List<VkTrack>): VkMatchCandidate {
        val candidates = scoreAll(spotify, vkResults)
        return candidates.firstOrNull() ?: VkMatchCandidate(
            vkTrack = VkTrack(0, 0, "", "", 0, null, null),
            score = 0,
            status = MatchStatus.NOT_FOUND,
            reason = "Нет кандидатов в VK",
        )
    }

    fun scoreAll(spotify: SpotifyTrackMeta, vkResults: List<VkTrack>): List<VkMatchCandidate> {
        val normSpotTitle = normalize(spotify.title)
        val spotifyFirstArtist = spotify.artists.firstOrNull().orEmpty()
        val normSpotArtist = normalize(spotifyFirstArtist)
        val spotDurationSec = (spotify.durationMs / 1000).toInt()

        val candidates = mutableListOf<VkMatchCandidate>()

        for (vk in vkResults) {
            // Жёсткий фильтр длительности: delta <= 5 сек
            val deltaSec = abs(vk.durationSec - spotDurationSec)
            if (deltaSec > 5) {
                continue
            }

            var score = 0
            val normVkTitle = normalize(vk.title)
            val normVkArtist = normalize(vk.artist)

            // Название: +50 за точное, +25 за вхождение
            if (normSpotTitle == normVkTitle) {
                score += 50
            } else if (normSpotTitle.isNotEmpty() && (normVkTitle.contains(normSpotTitle) || normSpotTitle.contains(normVkTitle))) {
                score += 25
            }

            // Артист: +30 за точное, +15 за вхождение
            if (normSpotArtist == normVkArtist) {
                score += 30
            } else if (normSpotArtist.isNotEmpty() && (normVkArtist.contains(normSpotArtist) || normSpotArtist.contains(normVkArtist))) {
                score += 15
            }

            // Длительность: +10 за ±2 сек
            if (deltaSec <= 2) {
                score += 10
            }

            // Битрейт: +5 за 320 kbps (или если ссылка прямая)
            if (vk.approxBitrateKbps == null || vk.approxBitrateKbps >= 320) {
                score += 5
            }

            // Штраф за подозрение на кавер/ремикс/live, если оригинал не содержит таких слов
            var hasSuspicion = false
            val vkLower = vk.title.lowercase()
            val spotLower = spotify.title.lowercase()
            for (word in SUSPICIOUS_WORDS) {
                if (vkLower.contains(word) && !spotLower.contains(word)) {
                    hasSuspicion = true
                    score -= 20
                    break
                }
            }

            val status = when {
                score >= 60 && !hasSuspicion -> MatchStatus.CONFIDENT
                score >= 40 -> MatchStatus.NEEDS_REVIEW
                else -> MatchStatus.NOT_FOUND
            }

            val reason = when {
                hasSuspicion -> "Возможно кавер или ремикс"
                deltaSec > 3 -> "Отличие по длительности: ${deltaSec}с"
                status == MatchStatus.CONFIDENT -> "Высокая точность"
                status == MatchStatus.NEEDS_REVIEW -> "Требуется проверка"
                else -> "Низкий скор ($score)"
            }

            candidates.add(
                VkMatchCandidate(
                    vkTrack = vk,
                    score = score,
                    status = status,
                    reason = reason,
                )
            )
        }

        return candidates.sortedByDescending { it.score }
    }

    private fun normalize(text: String): String {
        return text.lowercase()
            .replace("ё", "е")
            .replace(Regex("feat\\.?|ft\\.?", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\(.*?\\)"), "")
            .replace(Regex("\\[.*?\\]"), "")
            .replace(Regex("[^a-zа-я0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
