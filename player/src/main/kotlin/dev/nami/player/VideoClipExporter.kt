package dev.nami.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Экспорт музыкального фрагмента в виде вертикальной видео-открытки MP4 (9:16, 720x1280)
 * для публикаций в Stories, Telegram и соцсетях.
 *
 * Содержит:
 * - Обложку трека со скруглёнными углами и элегантной рамкой
 * - Название трека и имя исполнителя
 * - Синхронизированную строчку лирики / цитату
 * - Динамическую звуковую волну (waveform), пульсирующую под реальную амплитуду трека
 * - H.264 (AVC) + AAC Stereo, упакованные в контейнер MP4
 */
object VideoClipExporter {

    private const val VIDEO_WIDTH = 720
    private const val VIDEO_HEIGHT = 1280
    private const val VIDEO_FPS = 30
    private const val VIDEO_BITRATE = 2_500_000
    private const val AUDIO_BITRATE = 128_000
    private const val TIMEOUT_US = 10_000L

    data class DecodedAudio(
        val sampleRate: Int,
        val channelCount: Int,
        val pcmBytes: ByteArray,
    )

    fun exportVideo(
        audioPath: String,
        artworkPath: String?,
        trackTitle: String,
        artistName: String?,
        lyricLine: String?,
        startMs: Long,
        endMs: Long,
        outputFile: File,
        onProgress: ((Float) -> Unit)? = null,
    ): Boolean {
        val decodedAudio = decodeAudioChunk(audioPath, startMs, endMs) ?: return false
        val durationMs = (endMs - startMs).coerceAtLeast(1000L)
        val totalFrames = ((durationMs / 1000f) * VIDEO_FPS).toInt().coerceAtLeast(30)

        val artworkBitmap = artworkPath?.let { path ->
            try {
                BitmapFactory.decodeFile(path)
            } catch (_: Exception) {
                null
            }
        }

        var muxer: MediaMuxer? = null
        var videoCodec: MediaCodec? = null
        var audioCodec: MediaCodec? = null
        var inputSurface: Surface? = null

        return try {
            outputFile.parentFile?.mkdirs()
            if (outputFile.exists()) outputFile.delete()

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // 1. Видео энкодер H.264
            val videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, VIDEO_WIDTH, VIDEO_HEIGHT).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            videoCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            videoCodec.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = videoCodec.createInputSurface()
            videoCodec.start()

            // 2. Аудио энкодер AAC
            val audioFormat = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                decodedAudio.sampleRate,
                decodedAudio.channelCount,
            ).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            }
            audioCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            audioCodec.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            audioCodec.start()

            var videoTrackIndex = -1
            var audioTrackIndex = -1
            var muxerStarted = false

            val videoBufferInfo = MediaCodec.BufferInfo()
            val audioBufferInfo = MediaCodec.BufferInfo()

            var pcmOffset = 0
            val pcmData = decodedAudio.pcmBytes
            var audioInputDone = false
            var audioOutputDone = false

            val frameAmplitudes = calculateFrameAmplitudes(pcmData, totalFrames)

            // Цикл отрисовки кадров и кодирования звука
            for (frame in 0 until totalFrames) {
                // Отрисовка кадра на Surface
                val canvas = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    inputSurface.lockHardwareCanvas()
                } else {
                    inputSurface.lockCanvas(null)
                }

                val currentAmp = frameAmplitudes.getOrElse(frame) { 0.2f }
                val progressFrac = frame.toFloat() / totalFrames

                drawCardFrame(
                    canvas = canvas,
                    artwork = artworkBitmap,
                    title = trackTitle,
                    artist = artistName,
                    lyric = lyricLine,
                    amplitude = currentAmp,
                    progress = progressFrac,
                    timeElapsedMs = (progressFrac * durationMs).toLong(),
                    totalDurationMs = durationMs,
                    frame = frame,
                )
                inputSurface.unlockCanvasAndPost(canvas)

                // Подача аудио-сэмплов для текущего кадра
                val bytesPerFrame = (pcmData.size / totalFrames).coerceAtLeast(1)
                if (!audioInputDone) {
                    val inIdx = audioCodec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        val inBuf = audioCodec.getInputBuffer(inIdx)
                        if (inBuf != null) {
                            val chunkSize = minOf(bytesPerFrame, pcmData.size - pcmOffset)
                            if (chunkSize > 0) {
                                inBuf.clear()
                                inBuf.put(pcmData, pcmOffset, chunkSize)
                                val ptsUs = ((pcmOffset.toDouble() / (decodedAudio.sampleRate * decodedAudio.channelCount * 2)) * 1_000_000).toLong()
                                audioCodec.queueInputBuffer(inIdx, 0, chunkSize, ptsUs, 0)
                                pcmOffset += chunkSize
                            } else {
                                audioCodec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                audioInputDone = true
                            }
                        }
                    }
                }

                // Сбор закодированного видео
                while (true) {
                    val outIdx = videoCodec.dequeueOutputBuffer(videoBufferInfo, TIMEOUT_US)
                    if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        videoTrackIndex = muxer.addTrack(videoCodec.outputFormat)
                        if (audioTrackIndex >= 0 && !muxerStarted) {
                            muxer.start()
                            muxerStarted = true
                        }
                    } else if (outIdx >= 0) {
                        if (videoBufferInfo.size > 0 && muxerStarted) {
                            val outBuf = videoCodec.getOutputBuffer(outIdx)
                            if (outBuf != null) {
                                muxer.writeSampleData(videoTrackIndex, outBuf, videoBufferInfo)
                            }
                        }
                        videoCodec.releaseOutputBuffer(outIdx, false)
                        if (videoBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    } else {
                        break
                    }
                }

                // Сбор закодированного аудио
                while (!audioOutputDone) {
                    val aOutIdx = audioCodec.dequeueOutputBuffer(audioBufferInfo, TIMEOUT_US)
                    if (aOutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        audioTrackIndex = muxer.addTrack(audioCodec.outputFormat)
                        if (videoTrackIndex >= 0 && !muxerStarted) {
                            muxer.start()
                            muxerStarted = true
                        }
                    } else if (aOutIdx >= 0) {
                        if (audioBufferInfo.size > 0 && muxerStarted) {
                            val aOutBuf = audioCodec.getOutputBuffer(aOutIdx)
                            if (aOutBuf != null) {
                                muxer.writeSampleData(audioTrackIndex, aOutBuf, audioBufferInfo)
                            }
                        }
                        audioCodec.releaseOutputBuffer(aOutIdx, false)
                        if (audioBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            audioOutputDone = true
                        }
                    } else {
                        break
                    }
                }

                onProgress?.invoke((frame + 1).toFloat() / totalFrames)
            }

            videoCodec.signalEndOfInputStream()

            // Дособираем оставшееся видео
            var drainTries = 0
            while (drainTries++ < 50) {
                val outIdx = videoCodec.dequeueOutputBuffer(videoBufferInfo, 20_000)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    videoTrackIndex = muxer.addTrack(videoCodec.outputFormat)
                    if (!muxerStarted && audioTrackIndex >= 0) {
                        muxer.start()
                        muxerStarted = true
                    }
                } else if (outIdx >= 0) {
                    if (videoBufferInfo.size > 0 && muxerStarted) {
                        val outBuf = videoCodec.getOutputBuffer(outIdx)
                        if (outBuf != null) {
                            muxer.writeSampleData(videoTrackIndex, outBuf, videoBufferInfo)
                        }
                    }
                    videoCodec.releaseOutputBuffer(outIdx, false)
                    if (videoBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                } else {
                    break
                }
            }

            // Дособираем оставшееся аудио
            drainTries = 0
            while (!audioOutputDone && drainTries++ < 50) {
                val aOutIdx = audioCodec.dequeueOutputBuffer(audioBufferInfo, 20_000)
                if (aOutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    audioTrackIndex = muxer.addTrack(audioCodec.outputFormat)
                    if (!muxerStarted && videoTrackIndex >= 0) {
                        muxer.start()
                        muxerStarted = true
                    }
                } else if (aOutIdx >= 0) {
                    if (audioBufferInfo.size > 0 && muxerStarted) {
                        val aOutBuf = audioCodec.getOutputBuffer(aOutIdx)
                        if (aOutBuf != null) {
                            muxer.writeSampleData(audioTrackIndex, aOutBuf, audioBufferInfo)
                        }
                    }
                    audioCodec.releaseOutputBuffer(aOutIdx, false)
                    if (audioBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        audioOutputDone = true
                    }
                } else {
                    break
                }
            }

            true
        } catch (e: Exception) {
            outputFile.delete()
            false
        } finally {
            try { videoCodec?.stop() } catch (_: Exception) {}
            try { videoCodec?.release() } catch (_: Exception) {}
            try { audioCodec?.stop() } catch (_: Exception) {}
            try { audioCodec?.release() } catch (_: Exception) {}
            try { muxer?.stop() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
            inputSurface?.release()
        }
    }

    private fun decodeAudioChunk(path: String, startMs: Long, endMs: Long): DecodedAudio? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        return try {
            extractor.setDataSource(path)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null

            val format = extractor.getTrackFormat(trackIndex)
            extractor.selectTrack(trackIndex)
            extractor.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val pcm = ByteArrayOutputStream()
            val bufferInfo = MediaCodec.BufferInfo()
            val endUs = endMs * 1000
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                        val sampleSize = inputBuffer?.let { extractor.readSampleData(it, 0) } ?: -1
                        if (sampleSize < 0 || extractor.sampleTime > endUs) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                if (outputIndex >= 0) {
                    if (bufferInfo.size > 0) {
                        val outputBuffer = codec.getOutputBuffer(outputIndex)
                        if (outputBuffer != null) {
                            val bytes = ByteArray(bufferInfo.size)
                            outputBuffer.get(bytes)
                            pcm.write(bytes)
                        }
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || bufferInfo.presentationTimeUs >= endUs) {
                        outputDone = true
                    }
                }
            }

            DecodedAudio(sampleRate, channelCount, pcm.toByteArray())
        } catch (_: Exception) {
            null
        } finally {
            codec?.release()
            extractor.release()
        }
    }

    private fun calculateFrameAmplitudes(pcmData: ByteArray, frameCount: Int): FloatArray {
        val result = FloatArray(frameCount) { 0.2f }
        if (pcmData.size < 4 || frameCount <= 0) return result

        val shortCount = pcmData.size / 2
        val shortsPerFrame = shortCount / frameCount
        if (shortsPerFrame <= 0) return result

        val buf = ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN)
        for (f in 0 until frameCount) {
            var sumSq = 0.0
            val startShort = f * shortsPerFrame
            val endShort = minOf(startShort + shortsPerFrame, shortCount)
            val count = endShort - startShort
            if (count <= 0) continue

            for (s in startShort until endShort) {
                val sample = buf.getShort(s * 2).toDouble() / 32768.0
                sumSq += sample * sample
            }
            val rms = sqrt(sumSq / count).toFloat()
            result[f] = (rms * 2.5f).coerceIn(0.08f, 1.0f)
        }
        return result
    }

    private fun drawCardFrame(
        canvas: Canvas,
        artwork: Bitmap?,
        title: String,
        artist: String?,
        lyric: String?,
        amplitude: Float,
        progress: Float,
        timeElapsedMs: Long,
        totalDurationMs: Long,
        frame: Int,
    ) {
        val w = VIDEO_WIDTH.toFloat()
        val h = VIDEO_HEIGHT.toFloat()

        // 1. Фоновый градиент (глубокий Ink)
        val bgPaint = Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, h, Color.parseColor("#0F141C"), Color.parseColor("#18202C"), Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // 2. Брендинг сверху: NAMI
        val brandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#606C7E")
            textSize = 24f
            letterSpacing = 0.35f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("NAMI", w / 2f, 120f, brandPaint)

        // 3. Обложка альбома (420x420 с закруглением 32px)
        val artSize = 420f
        val artLeft = (w - artSize) / 2f
        val artTop = 220f
        val artRect = RectF(artLeft, artTop, artLeft + artSize, artTop + artSize)

        // Тень/свечение обложки
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb((40 * amplitude).toInt().coerceIn(20, 90), 78, 117, 255)
        }
        canvas.drawRoundRect(
            artLeft - 8f, artTop - 8f, artLeft + artSize + 8f, artTop + artSize + 8f,
            38f, 38f, shadowPaint,
        )

        if (artwork != null) {
            val roundedArt = getRoundedCornerBitmap(artwork, artSize.toInt(), 32f)
            canvas.drawBitmap(roundedArt, artLeft, artTop, null)
        } else {
            val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#1C2433")
            }
            canvas.drawRoundRect(artRect, 32f, 32f, placeholderPaint)
            val notePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#4E75FF")
                textSize = 96f
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("♪", w / 2f, artTop + artSize / 2f + 32f, notePaint)
        }

        // 4. Название трека
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 44f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        val displayTitle = if (title.length > 26) title.take(25) + "…" else title
        canvas.drawText(displayTitle, w / 2f, 720f, titlePaint)

        // 5. Исполнитель
        val artistPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#9AA4B2")
            textSize = 30f
            textAlign = Paint.Align.CENTER
        }
        val displayArtist = (artist ?: "Неизвестный исполнитель").let {
            if (it.length > 32) it.take(31) + "…" else it
        }
        canvas.drawText(displayArtist, w / 2f, 775f, artistPaint)

        // 6. Синхронизированная лирика / цитата
        if (!lyric.isNullOrBlank()) {
            val lyricBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#151D2A")
            }
            val lyricRect = RectF(60f, 825f, w - 60f, 925f)
            canvas.drawRoundRect(lyricRect, 20f, 20f, lyricBgPaint)

            val lyricPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#E0E6ED")
                textSize = 28f
                textAlign = Paint.Align.CENTER
            }
            val displayLyric = if (lyric.length > 40) lyric.take(39) + "…" else lyric
            canvas.drawText("“$displayLyric”", w / 2f, 885f, lyricPaint)
        }

        // 7. Динамический анимированный эквалайзер/волна
        val barCount = 32
        val waveWidth = w - 120f
        val barWidth = waveWidth / (barCount * 1.5f)
        val waveStartX = 60f
        val waveBaseY = 1040f
        val maxBarHeight = 120f

        val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        for (i in 0 until barCount) {
            val phase = (frame * 0.15f) + (i * 0.25f)
            val waveOsc = (sin(phase) + 1f) / 2f
            val barAmp = (amplitude * 0.7f + waveOsc * 0.3f).coerceIn(0.1f, 1.0f)
            val barH = (barAmp * maxBarHeight).coerceAtLeast(8f)

            val x = waveStartX + i * (barWidth * 1.5f)
            val y1 = waveBaseY - barH / 2f
            val y2 = waveBaseY + barH / 2f

            val r = (78 + (i * 3)).coerceIn(0, 255)
            val g = (117 + (barAmp * 100).toInt()).coerceIn(0, 255)
            val b = 255
            barPaint.color = Color.rgb(r, g, b)

            canvas.drawRoundRect(RectF(x, y1, x + barWidth, y2), barWidth / 2f, barWidth / 2f, barPaint)
        }

        // 8. Таймер и прогресс-бар снизу
        val trackBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#252E3E")
        }
        val fillBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#4E75FF")
        }
        val barY = 1130f
        canvas.drawRoundRect(RectF(60f, barY, w - 60f, barY + 6f), 3f, 3f, trackBarPaint)
        canvas.drawRoundRect(RectF(60f, barY, 60f + (w - 120f) * progress, barY + 6f), 3f, 3f, fillBarPaint)

        val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#748094")
            textSize = 22f
        }
        val elapsedStr = formatTime(timeElapsedMs)
        val totalStr = formatTime(totalDurationMs)
        timePaint.textAlign = Paint.Align.LEFT
        canvas.drawText(elapsedStr, 60f, 1170f, timePaint)
        timePaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(totalStr, w - 60f, 1170f, timePaint)
    }

    private fun getRoundedCornerBitmap(src: Bitmap, size: Int, radius: Float): Bitmap {
        val scaled = Bitmap.createScaledBitmap(src, size, size, true)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = RectF(0f, 0f, size.toFloat(), size.toFloat())

        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(scaled, 0f, 0f, paint)
        return output
    }

    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return "%d:%02d".format(min, sec)
    }
}
