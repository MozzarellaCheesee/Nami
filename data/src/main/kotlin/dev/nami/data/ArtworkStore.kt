package dev.nami.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ArtworkStore @Inject constructor(@ApplicationContext private val context: Context) {
    /** Сохраняет обложку и возвращает путь к файлу.
     *
     * В имя входит метка времени: Coil кеширует картинку в памяти по строке пути, поэтому при
     * постоянном имени новая обложка не показывалась бы до перезапуска приложения. Меняющийся
     * путь сам сдвигает ключ кеша - иначе пришлось бы задавать memoryCacheKey в каждом из
     * шести десятков мест, где стоит AsyncImage. Прежние файлы этого же владельца удаляются,
     * так что копии не накапливаются. */
    fun save(albumId: String, bytes: ByteArray): String? {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val artworkDir = File(context.filesDir, "artwork").apply { mkdirs() }
        val prefix = "${albumId}_full"
        val destination = File(artworkDir, "${prefix}_${System.currentTimeMillis()}.webp")
        // WEBP_LOSSY needs API 30+; minSdk here is 26, so fall back to the older
        // (deprecated but still functional) WEBP constant on pre-R devices.
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }
        destination.outputStream().use { output ->
            bitmap.compress(format, 90, output)
        }
        // Старые версии этой же обложки (включая файл прежнего формата без метки времени).
        artworkDir.listFiles()?.forEach { f ->
            // Хвост обязан быть именно меткой времени: иначе владелец с id "x" сносил бы
            // файлы владельца с id "x_full", чьё имя тоже начинается с "x_full_".
            val stale = f.name == "$prefix.webp" ||
                f.name.removePrefix("${prefix}_").removeSuffix(".webp")
                    .let { it != f.name && it.isNotEmpty() && it.all(Char::isDigit) }
            if (stale && f.name != destination.name) f.delete()
        }
        return destination.path
    }
}
