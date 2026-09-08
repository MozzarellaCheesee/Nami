package dev.nami.data

import androidx.test.core.app.ApplicationProvider
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
    }
}
