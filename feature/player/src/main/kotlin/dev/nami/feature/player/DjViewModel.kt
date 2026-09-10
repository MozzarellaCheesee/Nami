package dev.nami.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nami.core.model.Track
import dev.nami.domain.DjDeck
import dev.nami.domain.DjDeckState
import dev.nami.domain.DjRepository
import dev.nami.domain.LibraryRepository
import dev.nami.domain.PlayableTrack
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Хвост группы C "DJ-режим" - см. DjRepository для честного скоупа (без битмэтчинга/темпа). */
@HiltViewModel
class DjViewModel @Inject constructor(
    private val djRepository: DjRepository,
    libraryRepository: LibraryRepository,
) : ViewModel() {
    val deckA: StateFlow<DjDeckState> = djRepository.deckA
    val deckB: StateFlow<DjDeckState> = djRepository.deckB
    val crossfade: StateFlow<Float> = djRepository.crossfade

    /** Живой список: экран держат открытым, и подгруженный в это время трек должен появиться в
     * выборе для деки сам. */
    val tracks: StateFlow<List<Track>> = libraryRepository.allTracksOrderedFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun loadDeck(deck: DjDeck, track: Track) {
        djRepository.loadDeck(
            deck,
            PlayableTrack(
                id = track.id,
                title = track.title,
                artistName = track.artistName,
                path = track.path,
                artworkPath = track.albumArtworkPath,
                format = track.format,
                durationMs = track.durationMs,
            ),
        )
    }

    fun toggleDeck(deck: DjDeck) = djRepository.toggleDeck(deck)
    fun seekDeck(deck: DjDeck, positionMs: Long) = djRepository.seekDeck(deck, positionMs)
    fun setCrossfade(value: Float) = djRepository.setCrossfade(value)

    override fun onCleared() {
        djRepository.release()
    }
}
