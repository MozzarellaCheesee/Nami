package dev.nami.domain

import kotlinx.coroutines.flow.StateFlow

enum class DjDeck { A, B }

data class DjDeckState(
    val track: PlayableTrack? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val isPlaying: Boolean = false,
)

/** Хвост группы C "DJ-режим" -- сознательно узкий скоуп: два независимых плеера, ручной
 * кроссфейдер громкости между ними. Никакого битмэтчинга/синхронизации темпа/питч-шифта --
 * реальный DJ-микшер такого уровня это отдельный аудио-движок, не то, что можно честно собрать
 * поверх ExoPlayer за один заход. [crossfade] 0 = только дека A, 1 = только дека B. */
interface DjRepository {
    val deckA: StateFlow<DjDeckState>
    val deckB: StateFlow<DjDeckState>
    val crossfade: StateFlow<Float>

    fun loadDeck(deck: DjDeck, track: PlayableTrack)
    fun toggleDeck(deck: DjDeck)
    fun seekDeck(deck: DjDeck, positionMs: Long)
    fun setCrossfade(value: Float)

    /** Останавливает оба плеера и освобождает ресурсы -- вызывается когда экран закрывается. */
    fun release()
}
