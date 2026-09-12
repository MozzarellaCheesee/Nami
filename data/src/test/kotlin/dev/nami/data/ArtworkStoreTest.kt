package dev.nami.data

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowBitmapFactory
import java.io.ByteArrayOutputStream
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
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
    fun `save gives each version its own path and drops the previous file`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = ArtworkStore(context)
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().apply {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, this)
        }.toByteArray()

        val first = store.save("album-3", bytes)
        // Метка времени в имени имеет разрешение в миллисекунду.
        Thread.sleep(2)
        val second = store.save("album-3", bytes)

        assertNotNull(first)
        assertNotNull(second)
        // Coil кеширует картинку по строке пути, поэтому одинаковый путь означал бы старую
        // обложку на экране до перезапуска приложения.
        assertNotEquals(first, second, "новая обложка обязана получить новый путь")
        assertTrue(java.io.File(second).exists())
        assertFalse(java.io.File(first).exists(), "прежний файл не должен оставаться")
    }

    @Test
    fun `save keeps files of an owner whose id extends another`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = ArtworkStore(context)
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().apply {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, this)
        }.toByteArray()

        // Имя файла - "<id>_full_<метка>.webp", поэтому у владельца "x" и у владельца
        // "x_full" имена начинаются одинаково: чистка не должна их путать.
        val nested = store.save("x_full", bytes)
        Thread.sleep(2)
        store.save("x", bytes)

        assertNotNull(nested)
        assertTrue(java.io.File(nested).exists(), "чужой файл со схожим именем удалён")
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
