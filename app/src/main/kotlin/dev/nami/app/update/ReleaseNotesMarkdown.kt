package dev.nami.app.update

// Наши GitHub-релизы (.github/workflows/release.yml) собирают тело релиза из фиксированного
// набора markdown-конструкций: заголовки "## "/"### ", маркированные списки "* " с вложением
// через два пробела, `код` в бэктиках, **жирный** текст и блоки кода в тройных бэктиках.
// Полноценный парсер markdown ради этого не нужен — тут разобраны только эти случаи.
// Никаких android/compose-типов здесь нет специально, чтобы парсер можно было проверить
// обычным JUnit-тестом на JVM, а конвертацию в AnnotatedString делает уже экран настроек.

data class MdInlineSegment(
    val text: String,
    val bold: Boolean = false,
    val code: Boolean = false,
)

sealed class MdBlock {
    data class Heading(val segments: List<MdInlineSegment>) : MdBlock()
    data class Bullet(val indent: Int, val segments: List<MdInlineSegment>) : MdBlock()
    data class CodeBlock(val lines: List<String>) : MdBlock()
    data class Paragraph(val segments: List<MdInlineSegment>) : MdBlock()
}

private val INLINE_REGEX = Regex("""`([^`]+)`|\*\*([^*]+)\*\*""")

/** Разбирает "`код`" и "**жирный**" внутри одной строки на сегменты для AnnotatedString. */
fun parseInline(text: String): List<MdInlineSegment> {
    val segments = mutableListOf<MdInlineSegment>()
    var lastEnd = 0
    for (match in INLINE_REGEX.findAll(text)) {
        if (match.range.first > lastEnd) {
            segments += MdInlineSegment(text.substring(lastEnd, match.range.first))
        }
        val code = match.groups[1]?.value
        if (code != null) {
            segments += MdInlineSegment(code, code = true)
        } else {
            segments += MdInlineSegment(match.groups[2]!!.value, bold = true)
        }
        lastEnd = match.range.last + 1
    }
    if (lastEnd < text.length) segments += MdInlineSegment(text.substring(lastEnd))
    return segments
}

/** Разбирает тело релиза GitHub на блоки для отображения в настройках. */
fun parseReleaseNotes(markdown: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    var codeFenceLines: MutableList<String>? = null

    for (rawLine in markdown.lineSequence()) {
        val fence = codeFenceLines
        if (fence != null) {
            if (rawLine.trim().startsWith("```")) {
                blocks += MdBlock.CodeBlock(fence)
                codeFenceLines = null
            } else {
                fence += rawLine
            }
            continue
        }

        val trimmed = rawLine.trim()
        when {
            trimmed.isEmpty() -> Unit
            trimmed.startsWith("```") -> codeFenceLines = mutableListOf()
            trimmed.startsWith("### ") -> blocks += MdBlock.Heading(parseInline(trimmed.removePrefix("### ")))
            trimmed.startsWith("## ") -> blocks += MdBlock.Heading(parseInline(trimmed.removePrefix("## ")))
            trimmed.startsWith("* ") || trimmed.startsWith("- ") -> {
                val indent = (rawLine.length - rawLine.trimStart(' ').length) / 2
                blocks += MdBlock.Bullet(indent, parseInline(trimmed.removePrefix("* ").removePrefix("- ")))
            }
            else -> blocks += MdBlock.Paragraph(parseInline(trimmed))
        }
    }
    // Незакрытый блок кода (например, тело обрезано) — отдаём как есть, лучше показать текст,
    // чем потерять его молча.
    codeFenceLines?.let { blocks += MdBlock.CodeBlock(it) }
    return blocks
}
