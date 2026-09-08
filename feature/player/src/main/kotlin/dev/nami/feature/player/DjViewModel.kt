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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Хвост группы C "DJ-режим" -- см. DjRepository для честного скоупа (без битмэтчинга/темпа). */
@HiltViewModel
class DjViewModel @Inject constructor(
    private val djRepository: DjRepository,
    libraryRepository: LibraryRepository,
) : ViewModel() {
    val deckA: StateFlow<DjDeckState> = djRepository.deckA
    val deckB: StateFlow<DjDeckState> = djRepository.deckB
    val crossfade: StateFlow<Float> = djRepository.crossfade

    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks: StateFlow<List<Track>> = _tracks

    init {
        viewModelScope.launch { _tracks.value = libraryRepository.allTracksOrdered() }
    }

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
