package dev.nami.data

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowBitmapFactory
import java.io.ByteArrayOutputStream
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class ArtworkStoreTest {

    @Test
    fun `save writes a webp file and returns its path`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = ArtworkStore(context)
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().apply {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, this)
        }.toByteArray()

        val path = store.save("album-1", bytes)

        assertNotNull(path)
        assertTrue(java.io.File(path).exists())
    }

    @Test
    fun `save returns null for undecodable bytes`() {
        ShadowBitmapFactory.setAllowInvalidImageData(false)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = ArtworkStore(context)

        val path = store.save("album-2", byteArrayOf(1, 2, 3))

        assertNull(path)
    }
}
