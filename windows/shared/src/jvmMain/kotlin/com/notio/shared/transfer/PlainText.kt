package com.notio.shared.transfer

/** How a block of a PDF is drawn (D100). */
enum class PdfStyle { TITLE, HEADING, BODY }

/** One paragraph of a PDF: wrapped to the page width by the writer. An empty [text] is a blank line. */
data class PdfBlock(val text: String, val style: PdfStyle = PdfStyle.BODY)

/** Writes a PDF from blocks. An interface so the export code is testable without Android. */
fun interface PdfWriter {
    fun write(blocks: List<PdfBlock>): ByteArray
}

/**
 * Plain text and PDF views of a note's text (D100). Markdown syntax is removed, so what is exported reads like
 * the note's View, not like its source.
 */
object PlainText {

    private val HEADING = Regex("^\\s{0,3}#{1,6}\\s+(.*?)\\s*#*\\s*$")
    private val TASK_OPEN = Regex("^(\\s*)[-*+]\\s\\[ \\]\\s?(.*)$")
    private val TASK_DONE = Regex("^(\\s*)[-*+]\\s\\[[xX]\\]\\s?(.*)$")
    private val BULLET = Regex("^(\\s*)[-*+]\\s+(.*)$")
    private val QUOTE = Regex("^\\s{0,3}>\\s?(.*)$")
    private val RULE = Regex("^\\s{0,3}([-*_])(\\s*\\1){2,}\\s*$")
    private val FENCE = Regex("^\\s{0,3}(```|~~~).*$")
    private val IMAGE = Regex("!\\[([^\\]]*)]\\(([^)\\s]*)[^)]*\\)")
    private val LINK = Regex("\\[([^\\]]+)]\\(([^)\\s]+)[^)]*\\)")
    private val BOLD_STAR = Regex("\\*\\*(\\S(?:.*?\\S)?)\\*\\*")
    private val BOLD_UNDER = Regex("(?<!\\w)__(\\S(?:.*?\\S)?)__(?!\\w)")
    private val ITALIC_STAR = Regex("(?<![\\w*])\\*(\\S(?:[^*]*?\\S)?)\\*(?![\\w*])")
    private val ITALIC_UNDER = Regex("(?<![\\w])_(\\S(?:[^_]*?\\S)?)_(?!\\w)")
    private val STRIKE = Regex("~~(\\S(?:.*?\\S)?)~~")
    private val CODE = Regex("`([^`]+)`")

    /** Inline Markdown removed: `**bold**`, `*italic*`, `~~strike~~`, `` `code` ``, links and images. */
    fun inline(line: String): String {
        var s = line
        s = IMAGE.replace(s) { it.groupValues[1] }
        s = LINK.replace(s) { m -> if (m.groupValues[1] == m.groupValues[2]) m.groupValues[1] else m.groupValues[1] + " (" + m.groupValues[2] + ")" }
        s = CODE.replace(s) { it.groupValues[1] }
        s = BOLD_STAR.replace(s) { it.groupValues[1] }
        s = BOLD_UNDER.replace(s) { it.groupValues[1] }
        s = STRIKE.replace(s) { it.groupValues[1] }
        s = ITALIC_STAR.replace(s) { it.groupValues[1] }
        s = ITALIC_UNDER.replace(s) { it.groupValues[1] }
        return s
    }

    /** The lines of a Markdown text as plain blocks: headings kept as headings, syntax removed. */
    fun blocksFromMarkdown(markdown: String): List<PdfBlock> {
        val out = ArrayList<PdfBlock>()
        var inFence = false
        for (raw in markdown.replace("\r\n", "\n").split('\n')) {
            if (FENCE.matches(raw)) { inFence = !inFence; continue }
            if (inFence) { out += PdfBlock(raw); continue }
            HEADING.matchEntire(raw)?.let { out += PdfBlock(inline(it.groupValues[1]), PdfStyle.HEADING); continue }
            TASK_DONE.matchEntire(raw)?.let { out += PdfBlock(it.groupValues[1] + "[x] " + inline(it.groupValues[2])); continue }
            TASK_OPEN.matchEntire(raw)?.let { out += PdfBlock(it.groupValues[1] + "[ ] " + inline(it.groupValues[2])); continue }
            if (RULE.matches(raw)) { out += PdfBlock("————————"); continue }
            BULLET.matchEntire(raw)?.let { out += PdfBlock(it.groupValues[1] + "• " + inline(it.groupValues[2])); continue }
            QUOTE.matchEntire(raw)?.let { out += PdfBlock(inline(it.groupValues[1])); continue }
            out += PdfBlock(inline(raw))
        }
        return out
    }

    /** A text note as a `.txt` file. */
    fun fromMarkdown(markdown: String): String = blocksFromMarkdown(markdown).joinToString("\n") { it.text }

    /**
     * A list as text. Its stored readable copy (`- [ ] milk — 2 L`, `= Total: ...`) becomes `[ ] milk — 2 L`;
     * it already carries quantities, prices and totals, in the language the list was saved in.
     */
    fun blocksFromList(readableText: String): List<PdfBlock> = readableText.replace("\r\n", "\n").split('\n').map { line ->
        when {
            line.startsWith("- [x] ", ignoreCase = true) -> PdfBlock("[x] " + line.substring(6))
            line.startsWith("- [ ] ") -> PdfBlock("[ ] " + line.substring(6))
            line.startsWith("= ") -> PdfBlock(line.substring(2), PdfStyle.HEADING)
            else -> PdfBlock(line)
        }
    }

    fun fromList(readableText: String): String = blocksFromList(readableText).joinToString("\n") { block ->
        if (block.style == PdfStyle.HEADING) "= " + block.text else block.text
    }

    /** The title and then the blocks: what a PDF of a note holds. */
    fun withTitle(title: String, blocks: List<PdfBlock>): List<PdfBlock> =
        listOf(PdfBlock(title, PdfStyle.TITLE), PdfBlock("")) + blocks
}
