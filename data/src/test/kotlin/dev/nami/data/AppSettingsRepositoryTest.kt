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
}
