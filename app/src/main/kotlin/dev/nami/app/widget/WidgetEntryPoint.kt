package dev.nami.app.widget

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.nami.domain.PlayerRepository

/** Группа E "виджеты" - GlanceAppWidget/ActionCallback не проходят через обычный Hilt-граф
 * (не @AndroidEntryPoint компоненты), это стандартный обходной путь через EntryPointAccessors. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun playerRepository(): PlayerRepository
}

fun widgetPlayerRepository(context: android.content.Context): PlayerRepository =
    EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java).playerRepository()
