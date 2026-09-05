package dev.nami.app

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nami.core.model.PlaylistId
import dev.nami.domain.ImportM3u8Result
import dev.nami.domain.PlaylistRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlaylistActionsViewModel @Inject constructor(
    private val playlistRepository: PlaylistRepository,
) : ViewModel() {

    private var pendingCoverTarget: PlaylistId? = null
    private var pendingExportTarget: PlaylistId? = null
    private var pendingImportName: String? = null

    private val _lastImportResult = MutableStateFlow<ImportM3u8Result?>(null)
    val lastImportResult: StateFlow<ImportM3u8Result?> = _lastImportResult.asStateFlow()

    fun requestCoverPick(id: PlaylistId) {
        pendingCoverTarget = id
    }

    fun onCoverPicked(uri: Uri) {
        val target = pendingCoverTarget?.also { pendingCoverTarget = null } ?: return
        viewModelScope.launch {
            try {
                playlistRepository.setCoverImage(target, uri.toString())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // SAF I/O can fail (revoked URI grant, IOException): swallow so viewModelScope survives.
            }
        }
    }

    fun requestExport(id: PlaylistId) {
        pendingExportTarget = id
    }

    fun onExportDestinationPicked(uri: Uri) {
        val target = pendingExportTarget?.also { pendingExportTarget = null } ?: return
        viewModelScope.launch {
            try {
                playlistRepository.exportM3u8(target, uri.toString())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // SAF I/O can fail (revoked URI grant, IOException): swallow so viewModelScope survives.
            }
        }
    }

    fun requestImport(playlistName: String) {
        pendingImportName = playlistName
    }

    fun onImportSourcePicked(uri: Uri) {
        val name = pendingImportName?.also { pendingImportName = null } ?: return
        viewModelScope.launch {
            try {
                _lastImportResult.value = playlistRepository.importM3u8(uri.toString(), name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // SAF I/O can fail (revoked URI grant, IOException): swallow so viewModelScope survives.
            }
        }
    }
}
