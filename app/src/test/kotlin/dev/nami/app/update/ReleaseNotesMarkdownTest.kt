package dev.nami.app.update

import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseNotesMarkdownTest {
    @Test
    fun `заголовок, вложенный список, жирный текст и код разбираются на блоки`() {
        val markdown = """
            ## 🌊 Nami Release v1.0.12

            ### 🆕 Что нового:
            * **Сканирование стало быстрым.** Пояснение.
              * Вложенный пункт с `кодом`.
            * **Версия:** `1.0.12`.
        """.trimIndent()

        val blocks = parseReleaseNotes(markdown)

        assertEquals(5, blocks.size)
        assertEquals(MdBlock.Heading(listOf(MdInlineSegment("🌊 Nami Release v1.0.12"))), blocks[0])
        assertEquals(MdBlock.Heading(listOf(MdInlineSegment("🆕 Что нового:"))), blocks[1])
        assertEquals(
            MdBlock.Bullet(
                indent = 0,
                segments = listOf(
                    MdInlineSegment("Сканирование стало быстрым.", bold = true),
                    MdInlineSegment(" Пояснение."),
                ),
            ),
            blocks[2],
        )
        assertEquals(
            MdBlock.Bullet(
                indent = 1,
                segments = listOf(
                    MdInlineSegment("Вложенный пункт с "),
                    MdInlineSegment("кодом", code = true),
                    MdInlineSegment("."),
                ),
            ),
            blocks[3],
        )
    }

    @Test
    fun `блок кода в тройных бэктиках сохраняется как есть`() {
        val markdown = "```bash\ncurl example.sh | bash\n```"

        val blocks = parseReleaseNotes(markdown)

        assertEquals(listOf(MdBlock.CodeBlock(listOf("curl example.sh | bash"))), blocks)
    }
}
