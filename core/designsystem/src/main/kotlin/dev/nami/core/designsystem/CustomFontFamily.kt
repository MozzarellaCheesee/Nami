package dev.nami.core.designsystem

import android.graphics.Typeface
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import java.io.File

/**
 * Собирает [FontFamily] из выбранного пользователем шрифта латиницы/кириллицы и, если выбран,
 * отдельного шрифта для иероглифов (CJK).
 *
 * Compose'овский `FontFamily(a, b)` выбирает ОДИН шрифт по весу/начертанию, а не по покрытию
 * символов - для "латиница отсюда, иероглифы оттуда" он не годится. Настоящий механизм -
 * платформенный [Typeface.CustomFallbackBuilder] (API 29+): основное семейство, следом свои
 * fallback'и, хвостом системный. До API 29 остаётся только латинский шрифт - иероглифы, как и
 * раньше, дорисовывает системный fallback Android, просто не выбранной пользователем гарнитурой.
 */
fun customFontFamily(latinPath: String?, cjkPath: String?): FontFamily? {
    val latinFile = latinPath?.let { File(it) }?.takeIf { it.exists() && it.canRead() && it.length() > 0 }
    val cjkFile = cjkPath?.let { File(it) }?.takeIf { it.exists() && it.canRead() && it.length() > 0 }

    val latinValid = latinFile?.takeIf {
        runCatching { Typeface.createFromFile(it) }.isSuccess
    }?.absolutePath

    val cjkValid = cjkFile?.takeIf {
        runCatching { Typeface.createFromFile(it) }.isSuccess
    }?.absolutePath

    if (latinValid == null && cjkValid == null) return null

    val latinFamily = latinValid?.let {
        runCatching { FontFamily(Font(File(it))) }.getOrNull()
    }

    if (cjkValid == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return latinFamily
    return runCatching { fallbackFamily(latinValid, cjkValid) }.getOrElse { latinFamily }
}

@RequiresApi(Build.VERSION_CODES.Q)
private fun fallbackFamily(latinPath: String?, cjkPath: String): FontFamily {
    val cjk = platformFamily(cjkPath)
    val builder = Typeface.CustomFallbackBuilder(if (latinPath == null) cjk else platformFamily(latinPath))
        .setSystemFallback("sans-serif")
    if (latinPath != null) builder.addCustomFallback(cjk)
    return FontFamily(builder.build())
}

@RequiresApi(Build.VERSION_CODES.Q)
private fun platformFamily(path: String): android.graphics.fonts.FontFamily =
    android.graphics.fonts.FontFamily.Builder(
        android.graphics.fonts.Font.Builder(File(path)).build(),
    ).build()
