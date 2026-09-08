package dev.nami.domain

sealed interface ImportSource {
    data class Files(val uris: List<String>) : ImportSource
    data class Folder(val treeUri: String) : ImportSource
    /** П.md §2 "Импорт .zip-архивов с распаковкой на лету". */
    data class Zip(val uri: String) : ImportSource
}
