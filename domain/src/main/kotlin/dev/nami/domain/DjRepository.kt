package dev.nami.domain

import kotlinx.coroutines.flow.StateFlow

enum class DjDeck { A, B }

data class DjDeckState(
    val track: PlayableTrack? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val isPlaying: Boolean = false,
    val speed: Float = 1.0f,
    val baseBpm: Float? = null,
    val effectiveBpm: Float? = null,
)

/** Хвост группы C "DJ-режим" - два независимых плеера, ручной кроссфейдер и авто-битмэтчинг. */
interface DjRepository {
    val deckA: StateFlow<DjDeckState>
    val deckB: StateFlow<DjDeckState>
    val crossfade: StateFlow<Float>

    fun loadDeck(deck: DjDeck, track: PlayableTrack)
    fun toggleDeck(deck: DjDeck)
    fun seekDeck(deck: DjDeck, positionMs: Long)
    fun setCrossfade(value: Float)

    /** Регулировка скорости деки (0.5x..2.0x) с сохранением тональности. */
    fun setDeckSpeed(deck: DjDeck, speed: Float)

    /** Авто-битмэтчинг: подгоняет темп [targetDeck] под текущий эффективный BPM [sourceDeck]. */
    fun syncBpm(targetDeck: DjDeck, sourceDeck: DjDeck)

    /** Питч-бенд / nudge: кратковременный сдвиг позиции на [deltaMs] для попадания в долю. */
    fun nudge(deck: DjDeck, deltaMs: Long)

    /** Останавливает оба плеера и освобождает ресурсы - вызывается когда экран закрывается. */
    fun release()
}

