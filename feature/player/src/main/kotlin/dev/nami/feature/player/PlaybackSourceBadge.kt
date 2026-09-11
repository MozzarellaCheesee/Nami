package dev.nami.feature.player

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.nami.core.designsystem.NamiColors
import dev.nami.domain.TrackPlaybackSource

/**
 * Иконка источника воспроизведения трека (Дизайн.md §4.13):
 * 1. LOCAL: заполненный аккуратный кружок (●)
 * 2. CACHE: стрелка вниз в окружности (⤓)
 * 3. SERVER: лаконичный контур сервера / облака (☁)
 * 4. UNAVAILABLE: перечёркнутый контур сервера / облака (☁⃠)
 */
@Composable
fun PlaybackSourceIcon(
    source: TrackPlaybackSource,
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    customTint: Color? = null,
) {
    val defaultColor = when (source) {
        TrackPlaybackSource.LOCAL -> NamiColors.Wakaba
        TrackPlaybackSource.CACHE -> NamiColors.Kin
        TrackPlaybackSource.SERVER -> NamiColors.Ai
        TrackPlaybackSource.UNAVAILABLE -> NamiColors.Paper40
    }
    val animatedColor by animateColorAsState(
        targetValue = customTint ?: defaultColor,
        animationSpec = tween(250),
        label = "playbackSourceColor",
    )

    Canvas(
        modifier = modifier
            .size(size)
            .semantics { contentDescription = source.sourceDescription() },
    ) {
        when (source) {
            TrackPlaybackSource.LOCAL -> drawLocalIcon(animatedColor)
            TrackPlaybackSource.CACHE -> drawCacheIcon(animatedColor)
            TrackPlaybackSource.SERVER -> drawServerIcon(animatedColor)
            TrackPlaybackSource.UNAVAILABLE -> drawUnavailableIcon(animatedColor)
        }
    }
}

/**
 * Чип источника воспроизведения с подписью и описанием.
 * При нажатии отображает поясняющий Toast с деталями тракта.
 */
@Composable
fun PlaybackSourceBadge(
    source: TrackPlaybackSource,
    modifier: Modifier = Modifier,
    showLabel: Boolean = true,
) {
    val context = LocalContext.current
    val (bgColor, textColor, label) = when (source) {
        TrackPlaybackSource.LOCAL -> Triple(
            NamiColors.Wakaba.copy(alpha = 0.14f),
            NamiColors.Wakaba,
            "Локальный",
        )
        TrackPlaybackSource.CACHE -> Triple(
            NamiColors.Kin.copy(alpha = 0.14f),
            NamiColors.Kin,
            "Офлайн-кеш",
        )
        TrackPlaybackSource.SERVER -> Triple(
            NamiColors.Ai.copy(alpha = 0.14f),
            NamiColors.Ai,
            "Стриминг Nami",
        )
        TrackPlaybackSource.UNAVAILABLE -> Triple(
            NamiColors.Shu.copy(alpha = 0.14f),
            NamiColors.Paper70,
            "Сервер недоступен",
        )
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bgColor)
            .clickable {
                Toast.makeText(context, source.detailedToastText(), Toast.LENGTH_SHORT).show()
            }
            .padding(horizontal = 6.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            PlaybackSourceIcon(
                source = source,
                size = 13.dp,
                customTint = textColor,
            )
            if (showLabel) {
                AnimatedContent(
                    targetState = label,
                    transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                    label = "playback-source-label",
                ) { text ->
                    Text(
                        text = text,
                        color = textColor,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

private fun TrackPlaybackSource.sourceDescription(): String = when (this) {
    TrackPlaybackSource.LOCAL -> "Локальный файл"
    TrackPlaybackSource.CACHE -> "Офлайн-кеш"
    TrackPlaybackSource.SERVER -> "Серверный стрим"
    TrackPlaybackSource.UNAVAILABLE -> "Сервер недоступен"
}

private fun TrackPlaybackSource.detailedToastText(): String = when (this) {
    TrackPlaybackSource.LOCAL -> "Источник: локальный файл (Bit-perfect, напрямую с устройства)"
    TrackPlaybackSource.CACHE -> "Источник: офлайн-кеш (скачано с сервера NAMI на устройство)"
    TrackPlaybackSource.SERVER -> "Источник: сетевой стриминг с вашего сервера NAMI"
    TrackPlaybackSource.UNAVAILABLE -> "Источник: удалённый трек (сервер недоступен или нет подключения к сети)"
}

// -------------------------------------------------------------
// Векторная отрисовка иконок источника
// -------------------------------------------------------------

/** Заполненный аккуратный кружок */
private fun DrawScope.drawLocalIcon(color: Color) {
    val radius = size.minDimension * 0.38f
    drawCircle(
        color = color,
        radius = radius,
        center = center,
    )
}

/** Круг со стрелкой вниз внутри */
private fun DrawScope.drawCacheIcon(color: Color) {
    val strokeWidth = size.minDimension * 0.12f
    val radius = size.minDimension * 0.42f
    // Внешний контур окружности
    drawCircle(
        color = color,
        radius = radius,
        center = center,
        style = Stroke(width = strokeWidth),
    )
    // Стрелка «вниз»
    val arrowPath = Path().apply {
        val cx = center.x
        val cy = center.y
        val arrowW = radius * 0.44f
        val arrowH = radius * 0.30f
        moveTo(cx - arrowW, cy - arrowH * 0.5f)
        lineTo(cx, cy + arrowH * 0.7f)
        lineTo(cx + arrowW, cy - arrowH * 0.5f)
    }
    drawPath(
        path = arrowPath,
        color = color,
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
    )
}

/** Серверный купол / облако: плоский низ, округлый верх */
private fun DrawScope.drawServerIcon(color: Color) {
    val strokeWidth = size.minDimension * 0.12f
    val w = size.width
    val h = size.height

    val path = Path().apply {
        val bottomY = h * 0.78f
        val leftX = w * 0.15f
        val rightX = w * 0.85f
        val topY = h * 0.24f

        moveTo(leftX, bottomY)
        lineTo(rightX, bottomY)
        // Дуга справа
        cubicTo(
            rightX + w * 0.05f, bottomY,
            rightX + w * 0.02f, h * 0.52f,
            rightX - w * 0.06f, h * 0.44f,
        )
        // Верхний купол
        cubicTo(
            rightX - w * 0.10f, topY - h * 0.06f,
            leftX + w * 0.10f, topY - h * 0.06f,
            leftX + w * 0.06f, h * 0.44f,
        )
        // Дуга слева
        cubicTo(
            leftX - w * 0.02f, h * 0.52f,
            leftX - w * 0.05f, bottomY,
            leftX, bottomY,
        )
        close()
    }

    drawPath(
        path = path,
        color = color,
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
    )
}

/** Перечеркнутый купол / облако (недоступен) */
private fun DrawScope.drawUnavailableIcon(color: Color) {
    // Рисуем контур сервера
    drawServerIcon(color)

    // Косая диагональная черта
    val strokeWidth = size.minDimension * 0.13f
    val w = size.width
    val h = size.height
    drawLine(
        color = color,
        start = Offset(w * 0.82f, h * 0.14f),
        end = Offset(w * 0.18f, h * 0.86f),
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round,
    )
}