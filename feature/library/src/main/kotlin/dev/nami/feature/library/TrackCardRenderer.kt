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
    private const val HEIGHT = 1650
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
                    // CANVAS's absolute origin, not at coverRect's corner - coverRect sits at
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

        // Скраббер - настоящая волна этого трека (тот же сканер что у живого скраббера в
        // приложении), без полосы "пройдено/осталось" - это статичная картинка, не живой плеер,
        // выдумывать позицию воспроизведения нечестно. Время трека показано тут же (0:00 и
        // длительность), поэтому дальше по карточке оно не дублируется.
        val scrubberY = COVER_TOP + COVER_SIZE + 210f
        val scrubberLeft = coverLeft
        val scrubberRight = coverLeft + COVER_SIZE
        val waveform = runCatching { dev.nami.player.waveform.WaveformScanner.scan(track.path) }.getOrNull()
        val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#9B9A97") }
        if (waveform != null && waveform.isNotEmpty()) {
            val maxBarHeight = 70f
            val baseline = scrubberY + maxBarHeight
            val unit = COVER_SIZE / (waveform.size * 3f - 1f)
            val barWidth = unit * 2f
            waveform.forEachIndexed { i, level ->
                val x = scrubberLeft + i * (barWidth + unit)
                val height = level.coerceIn(0.05f, 1f) * maxBarHeight
                canvas.drawRoundRect(RectF(x, baseline - height, x + barWidth, baseline + height), barWidth / 2, barWidth / 2, wavePaint)
            }
        } else {
            // Decode failed (unreadable/moved file) - flat line beats a blank gap.
            canvas.drawRoundRect(RectF(scrubberLeft, scrubberY + 67f, scrubberRight, scrubberY + 73f), 3f, 3f, wavePaint)
        }

        val timePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#7A7C82")
            textSize = 28f
        }
        canvas.drawText("0:00", scrubberLeft, scrubberY + 190f, timePaint)
        val durationText = formatDuration(track.durationMs)
        timePaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(durationText, scrubberRight, scrubberY + 190f, timePaint)

        // Те же контролы что в Now Playing - реальный контур skip-prev/next (полоса+
        // треугольник), не абстрактные треугольники.
        val controlsY = scrubberY + 270f
        val controlPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#EDEAE4") }
        drawSkipGlyph(canvas, WIDTH / 2f - 140f, controlsY, 34f, isNext = false, paint = controlPaint)
        canvas.drawCircle(WIDTH / 2f, controlsY, 64f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#C24A34") })
        drawTriangle(canvas, WIDTH / 2f + 10f, controlsY, 30f, pointsRight = true, paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#EDEAE4") })
        drawSkipGlyph(canvas, WIDTH / 2f + 140f, controlsY, 34f, isNext = true, paint = controlPaint)

        var detailsY = controlsY + 130f

        val metaParts = mutableListOf<String>()
        track.genre?.takeIf { it.isNotBlank() }?.let { metaParts += it }
        metaParts += buildString {
            append(track.format.uppercase())
            val rate = track.sampleRateHz
            val depth = track.bitDepth
            if (rate != null && depth != null) append(" ${rate / 1000}kHz/${depth}bit")
        }
        val metaPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#7A7C82")
            textSize = 34f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(
            ellipsize(metaParts.joinToString("   ·   "), metaPaint, WIDTH - 100f),
            WIDTH / 2f,
            detailsY,
            metaPaint,
        )

        val watermarkPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#7A7C82")
            textSize = 32f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("波 NAMI", WIDTH / 2f, HEIGHT - 60f, watermarkPaint)

        return bitmap
    }

    /** Simple play/skip triangle glyph centered at ([cx], [cy]) - prev/next use two of these
     * side by side with a thin bar (a plain triangle reads fine at this size without the bar). */
    private fun drawTriangle(canvas: Canvas, cx: Float, cy: Float, size: Float, pointsRight: Boolean, paint: Paint) {
        val path = android.graphics.Path()
        if (pointsRight) {
            path.moveTo(cx - size / 2, cy - size)
            path.lineTo(cx - size / 2, cy + size)
            path.lineTo(cx + size / 2, cy)
        } else {
            path.moveTo(cx + size / 2, cy - size)
            path.lineTo(cx + size / 2, cy + size)
            path.lineTo(cx - size / 2, cy)
        }
        path.close()
        canvas.drawPath(path, paint)
    }

    /** Same contour as Now Playing's skip-prev/next glyphs (Material Rounded SkipNext/
     * SkipPrevious) - triangle + bar, not the plain arrow of [drawTriangle]. */
    private fun drawSkipGlyph(canvas: Canvas, cx: Float, cy: Float, size: Float, isNext: Boolean, paint: Paint) {
        val barWidth = size * 0.24f
        val barHeight = size * 2f
        if (isNext) {
            drawTriangle(canvas, cx - size * 0.35f, cy, size, pointsRight = true, paint = paint)
            val barLeft = cx + size * 0.55f
            canvas.drawRect(barLeft, cy - barHeight / 2, barLeft + barWidth, cy + barHeight / 2, paint)
        } else {
            val barLeft = cx - size * 0.55f - barWidth
            canvas.drawRect(barLeft, cy - barHeight / 2, barLeft + barWidth, cy + barHeight / 2, paint)
            drawTriangle(canvas, cx + size * 0.35f, cy, size, pointsRight = false, paint = paint)
        }
    }

    private fun formatDuration(durationMs: Long): String {
        val totalSec = durationMs / 1000
        return "%d:%02d".format(totalSec / 60, totalSec % 60)
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
