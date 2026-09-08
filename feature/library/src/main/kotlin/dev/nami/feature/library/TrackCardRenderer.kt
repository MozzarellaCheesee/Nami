package dev.nami.feature.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import android.text.TextPaint
import androidx.core.content.FileProvider
import dev.nami.core.model.Track
import java.io.File

/** Группа D "карточка трека (экспорт-картинка)" - рисуется напрямую через android.graphics
 * Canvas, не через захват Compose-дерева - проще, не зависит от версии Compose graphicsLayer API,
 * тот же прямой подход что уже используется для waveform/artwork в этом проекте. */
object TrackCardRenderer {
    private const val WIDTH = 1080
    private const val HEIGHT = 1350
    private const val COVER_SIZE = 820
    private const val COVER_TOP = 100f
    private const val CORNER_RADIUS = 24f

    fun render(track: Track): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.parseColor("#0C0D0F")) // NamiColors.Ink900

        val coverLeft = (WIDTH - COVER_SIZE) / 2f
        val coverRect = RectF(coverLeft, COVER_TOP, coverLeft + COVER_SIZE, COVER_TOP + COVER_SIZE)
        val coverBitmap = track.albumArtworkPath?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() }

        if (coverBitmap != null) {
            val scaled = Bitmap.createScaledBitmap(coverBitmap, COVER_SIZE, COVER_SIZE, true)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                    // Without this the shader samples from the bitmap's own (0,0) at the
                    // CANVAS's absolute origin, not at coverRect's corner -- coverRect sits at
                    // (coverLeft, COVER_TOP), so the cover appeared shifted, with the edge pixels
                    // clamp-repeated into a solid strip along the right/bottom instead of image.
                    setLocalMatrix(android.graphics.Matrix().apply { setTranslate(coverLeft, COVER_TOP) })
                }
            }
            canvas.drawRoundRect(coverRect, CORNER_RADIUS, CORNER_RADIUS, paint)
        } else {
            canvas.drawRoundRect(
                coverRect, CORNER_RADIUS, CORNER_RADIUS,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1A1B1F") },
            )
            val glyphPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#3A3C43")
                textSize = 180f
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("波", WIDTH / 2f, COVER_TOP + COVER_SIZE / 2f + 60f, glyphPaint)
        }

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#EDEAE4")
            textSize = 56f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(ellipsize(track.title, titlePaint, WIDTH - 100f), WIDTH / 2f, COVER_TOP + COVER_SIZE + 100f, titlePaint)

        track.artistName?.let { artist ->
            val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#9B9A97")
                textSize = 40f
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(ellipsize(artist, artistPaint, WIDTH - 100f), WIDTH / 2f, COVER_TOP + COVER_SIZE + 160f, artistPaint)
        }

        val watermarkPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#7A7C82")
            textSize = 32f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("波 NAMI", WIDTH / 2f, HEIGHT - 60f, watermarkPaint)

        return bitmap
    }

    private fun ellipsize(text: String, paint: TextPaint, maxWidth: Float): String =
        android.text.TextUtils.ellipsize(text, paint, maxWidth, android.text.TextUtils.TruncateAt.END).toString()

    /** Saves to cacheDir/shares/ and returns a content:// Uri via FileProvider, ready for
     * ACTION_SEND. Overwrites the same filename each time - this is a throwaway share artifact,
     * not something the user manages later. */
    fun saveAndGetShareUri(context: Context, bitmap: Bitmap): Uri {
        val dir = File(context.cacheDir, "shares").apply { mkdirs() }
        val file = File(dir, "track_card.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }
}
