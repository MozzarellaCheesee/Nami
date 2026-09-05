package dev.nami.app

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nami.core.model.PlaylistId
import dev.nami.domain.PlaylistRepository
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlaylistActionsViewModel @Inject constructor(
    private val playlistRepository: PlaylistRepository,
) : ViewModel() {

    private var pendingCoverTarget: PlaylistId? = null
    private var pendingExportTarget: PlaylistId? = null
    private var pendingImportName: String? = null

    fun requestCoverPick(id: PlaylistId) {
        pendingCoverTarget = id
    }

    fun onCoverPicked(uri: Uri) {
        val target = pendingCoverTarget ?: return
        viewModelScope.launch { playlistRepository.setCoverImage(target, uri.toString()) }
    }

    fun requestExport(id: PlaylistId) {
        pendingExportTarget = id
    }

    fun onExportDestinationPicked(uri: Uri) {
        val target = pendingExportTarget ?: return
        viewModelScope.launch { playlistRepository.exportM3u8(target, uri.toString()) }
    }

    fun requestImport(playlistName: String) {
        pendingImportName = playlistName
    }

    fun onImportSourcePicked(uri: Uri) {
        val name = pendingImportName ?: return
        viewModelScope.launch { playlistRepository.importM3u8(uri.toString(), name) }
    }
}
