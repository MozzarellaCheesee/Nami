package dev.nami.app

import dev.nami.core.designsystem.NamiColors
import dev.nami.core.designsystem.NamiRadius
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Файл темы приходит извне - от другого человека, из мессенджера. Тут проверяется именно то,
 * чего требует П.md §26: неизвестное игнорируется, некорректное игнорируется по одному полю, а
 * не целым импортом, и ни на чём нет падения. */
@RunWith(RobolectricTestRunner::class)
class ThemeIoTest {

    @Test
    fun `экспорт и импорт возвращают то же самое`() {
        val original = ThemeFile(
            colors = mapOf(NamiColors.TOKEN_SHU to "#112233"),
            shape = mapOf(NamiRadius.TOKEN_CARD to 8),
            densityScale = 0.85f,
            fontScale = 1.1f,
        )
        assertEquals(original, parseThemeFile(encodeThemeFile(original)))
    }

    @Test
    fun `мусор вместо файла не роняет и не применяется`() {
        assertNull(parseThemeFile("не json вовсе"))
        assertNull(parseThemeFile(""))
    }

    @Test
    fun `неизвестные поля и битые значения выкидываются по одному, остальное применяется`() {
        val parsed = parseThemeFile(
            """
            {
              "version": 99,
              "чужое поле": {"что-то": 1},
              "colors": {"Shu": "#C24A34", "Paper100": "не цвет", "НетТакогоТокена": "#000000"},
              "shape": {"Card": 9000, "НетТакого": 3},
              "density": 5.0,
              "fontScale": "строка"
            }
            """.trimIndent(),
        )!!

        assertEquals(mapOf(NamiColors.TOKEN_SHU to "#C24A34"), parsed.colors)
        // Радиус зажимается в допустимый предел, а не принимается как есть.
        assertEquals(mapOf(NamiRadius.TOKEN_CARD to NamiRadius.MAX_DP), parsed.shape)
        assertEquals(1.2f, parsed.densityScale)
        assertNull(parsed.fontScale)
    }

    @Test
    fun `пресеты галереи содержат только известные токены с разбираемым цветом`() {
        THEME_PRESETS.forEach { preset ->
            preset.colors.forEach { (token, hex) ->
                assertTrue(token in NamiColors.EDITABLE_TOKENS, "${preset.name}: неизвестный токен $token")
                assertTrue(
                    runCatching { android.graphics.Color.parseColor(hex) }.isSuccess,
                    "${preset.name}: не разбирается цвет $hex",
                )
            }
        }
    }

    @Test
    fun `коэффициент контраста по WCAG - известные опорные значения`() {
        // Чёрное на белом - максимум по определению формулы, ровно 21:1.
        assertEquals(21.0, contrastRatio(0x000000, 0xFFFFFF), 0.01)
        assertEquals(1.0, contrastRatio(0x777777, 0x777777), 0.01)
        // Порядок аргументов не важен: делится всегда светлое на тёмное.
        assertEquals(contrastRatio(0x000000, 0xFFFFFF), contrastRatio(0xFFFFFF, 0x000000), 0.0001)
        // Серый #767676 на белом - канонический пограничный пример WCAG, чуть выше 4.5:1.
        assertTrue(contrastRatio(0x767676, 0xFFFFFF) >= 4.5)
        assertTrue(contrastRatio(0x787878, 0xFFFFFF) < 4.5)
    }

    @Test
    fun `пресет из обложки трогает только акцентные токены`() {
        val preset = artworkPreset(0xFFC24A34.toInt())
        assertEquals(
            setOf(
                NamiColors.TOKEN_SHU, NamiColors.TOKEN_AI,
                NamiColors.TOKEN_KIN, NamiColors.TOKEN_WAKABA,
            ),
            preset.colors.keys,
        )
        preset.colors.values.forEach {
            assertTrue(runCatching { android.graphics.Color.parseColor(it) }.isSuccess, "не разбирается $it")
        }
    }
}
