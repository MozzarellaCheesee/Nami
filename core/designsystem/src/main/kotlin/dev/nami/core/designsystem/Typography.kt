package dev.nami.core.designsystem

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Три гарнитуры по Дизайн.md ("Типографика"): Archivo для интерфейса на латинице, Zen Kaku
 * Gothic New для японского интерфейса, Shippori Mincho только на экране лирики.
 *
 * Archivo идёт тремя статичными начертаниями (400/500/600) вместо переменного шрифта - ближайшее
 * из трёх берётся и для 450/550 из шкалы, спорить о точных 450/550 в статичном шрифте бессмысленно.
 */
object NamiFonts {
    val Archivo = FontFamily(
        Font(R.font.archivo_regular, FontWeight.Normal),
        Font(R.font.archivo_medium, FontWeight.Medium),
        Font(R.font.archivo_semibold, FontWeight.SemiBold),
    )

    val ZenKakuGothicNew = FontFamily(
        Font(R.font.zen_kaku_gothic_new_regular, FontWeight.Normal),
        Font(R.font.zen_kaku_gothic_new_medium, FontWeight.Medium),
    )

    /** Только для экрана лирики - см. lyricsFontPath в SettingsRepository для
     * пользовательского оверрайда поверх этого дефолта. */
    val ShipporiMincho = FontFamily(
        Font(R.font.shippori_mincho_regular, FontWeight.Normal),
        Font(R.font.shippori_mincho_medium, FontWeight.Medium),
    )
}

/** Шкала из Дизайн.md ("Типографика"): размер / начертание / межстрочный / трекинг, один
 * [TextStyle] на роль. Табличные цифры (технические данные) включены через [FontFeatureSettings]
 * на стороне вызова, где это применимо - сама гарнитура не задаёт их автоматически. */
object NamiType {
    val ScreenTitle = TextStyle(
        fontFamily = NamiFonts.Archivo,
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.4f).sp,
    )
    val TrackTitle = TextStyle(
        fontFamily = NamiFonts.Archivo,
        fontWeight = FontWeight.Medium,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        letterSpacing = (-0.2f).sp,
    )
    val ListTitle = TextStyle(
        fontFamily = NamiFonts.Archivo,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
    )
    val Secondary = TextStyle(
        fontFamily = NamiFonts.Archivo,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.sp,
    )
    val Caption = TextStyle(
        fontFamily = NamiFonts.Archivo,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.2f.sp,
    )
    val TechData = TextStyle(
        fontFamily = NamiFonts.Archivo,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.6f.sp,
    )
    val LyricsActive = TextStyle(
        fontFamily = NamiFonts.ShipporiMincho,
        fontWeight = FontWeight.Medium,
        fontSize = 22.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.sp,
    )
    val LyricsNeighbor = TextStyle(
        fontFamily = NamiFonts.ShipporiMincho,
        fontWeight = FontWeight.Normal,
        fontSize = 18.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp,
    )
    val Furigana = TextStyle(
        fontFamily = NamiFonts.ZenKakuGothicNew,
        fontWeight = FontWeight.Normal,
        fontSize = 10.sp,
        lineHeight = 12.sp,
        letterSpacing = 0.sp,
    )
    val Romanization = TextStyle(
        fontFamily = NamiFonts.Archivo,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.3f.sp,
    )
    val Translation = TextStyle(
        fontFamily = NamiFonts.Archivo,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 19.sp,
        letterSpacing = 0.sp,
    )
}
