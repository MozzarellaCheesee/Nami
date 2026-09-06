package dev.nami.app

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nami.core.model.AlbumId
import dev.nami.core.model.ArtistId
import dev.nami.domain.LibraryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MetadataActionsViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private var pendingAlbumCoverTarget: AlbumId?
        get() = savedStateHandle.get<String>("pendingAlbumCoverTarget")?.let(::AlbumId)
        set(value) { savedStateHandle["pendingAlbumCoverTarget"] = value?.value }

    private var pendingArtistPhotoTarget: ArtistId?
        get() = savedStateHandle.get<String>("pendingArtistPhotoTarget")?.let(::ArtistId)
        set(value) { savedStateHandle["pendingArtistPhotoTarget"] = value?.value }

    fun requestAlbumCoverPick(id: AlbumId) {
        pendingAlbumCoverTarget = id
    }

    fun onAlbumCoverPicked(uri: Uri) {
        val target = pendingAlbumCoverTarget?.also { pendingAlbumCoverTarget = null } ?: return
        viewModelScope.launch {
            try {
                libraryRepository.setAlbumCover(target, uri.toString())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // SAF I/O can fail (revoked URI grant, IOException): swallow so viewModelScope survives.
            }
        }
    }

    fun requestArtistPhotoPick(id: ArtistId) {
        pendingArtistPhotoTarget = id
    }

    fun onArtistPhotoPicked(uri: Uri) {
        val target = pendingArtistPhotoTarget?.also { pendingArtistPhotoTarget = null } ?: return
        viewModelScope.launch {
            try {
                libraryRepository.setArtistPhoto(target, uri.toString())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // SAF I/O can fail (revoked URI grant, IOException): swallow so viewModelScope survives.
            }
        }
    }
}
