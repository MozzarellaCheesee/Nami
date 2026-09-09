package dev.nami.data

import androidx.test.core.app.ApplicationProvider
import dev.nami.domain.DEFAULT_NOW_PLAYING_BLOCKS
import dev.nami.domain.DEFAULT_NOW_PLAYING_MORE_ITEMS
import dev.nami.domain.NowPlayingBlock
import dev.nami.domain.NowPlayingMoreAccent
import dev.nami.domain.NowPlayingMoreConfig
import dev.nami.domain.NowPlayingMoreItem
import dev.nami.domain.NowPlayingMoreSection
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppSettingsRepositoryTest {

    @Test
    fun `defaults to false and persists a change`() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val repo = AppSettingsRepository(context)

        assertFalse(repo.autoOpenPlayer.value)

        repo.setAutoOpenPlayer(true)
        assertEquals(true, repo.autoOpenPlayer.value)

        // A second instance reading the same SharedPreferences file sees the persisted value.
        val repo2 = AppSettingsRepository(context)
        assertEquals(true, repo2.autoOpenPlayer.value)
    }

    /** Форма/плотность из редактора темы (П.md §26) - у них свой JSON-разбор, а не просто
     * getBoolean, поэтому проверяется отдельно: и запись-чтение, и сброс. */
    @Test
    fun `theme shape and density survive a restart and reset`() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val repo = AppSettingsRepository(context)

        repo.setThemeShapeOverride("Card", 4)
        repo.setThemeDensityScale(0.85f)

        val reopened = AppSettingsRepository(context)
        assertEquals(4, reopened.themeShapeOverrides.value["Card"])
        assertEquals(0.85f, reopened.themeDensityScale.value)

        reopened.resetThemeShapeAndDensity()
        assertEquals(emptyMap(), AppSettingsRepository(context).themeShapeOverrides.value)
        assertEquals(1f, AppSettingsRepository(context).themeDensityScale.value)
        assertEquals(1f, AppSettingsRepository(context).themeFontScale.value)
    }

    /** Порядок блоков Now Playing (П.md §17) хранится списком имён - проверяется то же, что у
     * главного экрана: сохранённый порядок переживает перезапуск, мусор не ломает чтение, а
     * блок, которого в сохранённом списке нет, дописывается в конец. */
    @Test
    fun `порядок блоков плеера переживает перезапуск и чинит неполный список`() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val repo = AppSettingsRepository(context)

        repo.setNowPlayingBlockOrder(listOf(NowPlayingBlock.PILLS, NowPlayingBlock.TRANSPORT))

        val reopened = AppSettingsRepository(context)
        val order = reopened.nowPlayingBlockOrder.value
        assertEquals(listOf(NowPlayingBlock.PILLS, NowPlayingBlock.TRANSPORT), order.take(2))
        assertEquals(DEFAULT_NOW_PLAYING_BLOCKS.size, order.size)
        assertEquals(DEFAULT_NOW_PLAYING_BLOCKS.toSet(), order.toSet())
    }

    /** Меню "Ещё": сохранённые секция/цвет/порядок переживают перезапуск, а пункт, которого в
     * сохранённом списке нет (новая версия приложения), дописывается в конец со своим дефолтом. */
    @Test
    fun `конфиг меню Ещё переживает перезапуск и дописывает новые пункты`() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val repo = AppSettingsRepository(context)

        repo.setNowPlayingMoreItems(
            listOf(
                NowPlayingMoreConfig(NowPlayingMoreItem.SLEEP_TIMER, NowPlayingMoreSection.LIST, NowPlayingMoreAccent.SHU),
                NowPlayingMoreConfig(NowPlayingMoreItem.CAST, NowPlayingMoreSection.HIDDEN),
            ),
        )

        val items = AppSettingsRepository(context).nowPlayingMoreItems.value
        assertEquals(NowPlayingMoreItem.SLEEP_TIMER, items[0].item)
        assertEquals(NowPlayingMoreSection.LIST, items[0].section)
        assertEquals(NowPlayingMoreAccent.SHU, items[0].accent)
        assertEquals(NowPlayingMoreSection.HIDDEN, items[1].section)
        assertEquals(DEFAULT_NOW_PLAYING_MORE_ITEMS.map { it.item }.toSet(), items.map { it.item }.toSet())
    }
}
