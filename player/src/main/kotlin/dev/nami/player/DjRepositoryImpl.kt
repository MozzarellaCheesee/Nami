package dev.nami.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.nami.domain.DjDeck
import dev.nami.domain.DjDeckState
import dev.nami.domain.DjRepository
import dev.nami.domain.PlayableTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Хвост группы C "DJ-режим" -- см. DjRepository для честного скоупа. Не @Singleton: живёт и
 * умирает вместе с DjViewModel (release() в onCleared), а не всё время работы приложения --
 * два лишних ExoPlayer постоянно в памяти того не стоят. */
class DjRepositoryImpl @Inject constructor(@ApplicationContext context: Context) : DjRepository {
    private val playerA = ExoPlayer.Builder(context).build()
    private val playerB = ExoPlayer.Builder(context).build()

    private val _deckA = MutableStateFlow(DjDeckState())
    private val _deckB = MutableStateFlow(DjDeckState())
    override val deckA: StateFlow<DjDeckState> = _deckA
    override val deckB: StateFlow<DjDeckState> = _deckB

    private val _crossfade = MutableStateFlow(0.5f)
    override val crossfade: StateFlow<Float> = _crossfade

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pollJob: Job? = null

    init {
        applyVolumes()
        pollJob = scope.launch {
            while (true) {
                delay(200)
                _deckA.value = _deckA.value.copy(
                    positionMs = playerA.currentPosition.coerceAtLeast(0),
                    durationMs = playerA.duration.coerceAtLeast(0),
                    isPlaying = playerA.isPlaying,
                )
                _deckB.value = _deckB.value.copy(
                    positionMs = playerB.currentPosition.coerceAtLeast(0),
                    durationMs = playerB.duration.coerceAtLeast(0),
                    isPlaying = playerB.isPlaying,
                )
            }
        }
    }

    private fun playerFor(deck: DjDeck) = if (deck == DjDeck.A) playerA else playerB

    override fun loadDeck(deck: DjDeck, track: PlayableTrack) {
        val player = playerFor(deck)
        player.setMediaItem(MediaItem.fromUri(track.path))
        player.prepare()
        val state = DjDeckState(track = track, positionMs = 0, durationMs = 0, isPlaying = false)
        if (deck == DjDeck.A) _deckA.value = state else _deckB.value = state
    }

    override fun toggleDeck(deck: DjDeck) {
        val player = playerFor(deck)
        if (player.isPlaying) player.pause() else player.play()
    }

    override fun seekDeck(deck: DjDeck, positionMs: Long) {
        playerFor(deck).seekTo(positionMs)
    }

    override fun setCrossfade(value: Float) {
        _crossfade.value = value.coerceIn(0f, 1f)
        applyVolumes()
    }

    // Equal-power-ish linear crossfade -- good enough for a manual mix, not claiming a true
    // constant-loudness curve (that needs a sqrt/cosine taper, not worth it for this scope).
    private fun applyVolumes() {
        val value = _crossfade.value
        playerA.volume = 1f - value
        playerB.volume = value
    }

    override fun release() {
        pollJob?.cancel()
        playerA.release()
        playerB.release()
    }
}
