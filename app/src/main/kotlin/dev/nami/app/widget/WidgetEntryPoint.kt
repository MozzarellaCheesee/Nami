package dev.nami.app.widget

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.nami.data.AppSettingsRepository
import dev.nami.domain.LibraryRepository
import dev.nami.domain.PlayerRepository
import dev.nami.domain.PlaylistRepository

/** Группа E "виджеты" - GlanceAppWidget/ActionCallback не проходят через обычный Hilt-граф
 * (не @AndroidEntryPoint компоненты), это стандартный обходной путь через EntryPointAccessors. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun playerRepository(): PlayerRepository
    fun libraryRepository(): LibraryRepository
    fun settingsRepository(): AppSettingsRepository
    fun playlistRepository(): PlaylistRepository
}

private fun entryPoint(context: android.content.Context): WidgetEntryPoint =
    EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java)

fun widgetPlayerRepository(context: android.content.Context): PlayerRepository = entryPoint(context).playerRepository()
fun widgetLibraryRepository(context: android.content.Context): LibraryRepository = entryPoint(context).libraryRepository()
fun widgetSettingsRepository(context: android.content.Context): AppSettingsRepository = entryPoint(context).settingsRepository()
fun widgetPlaylistRepository(context: android.content.Context): PlaylistRepository = entryPoint(context).playlistRepository()
