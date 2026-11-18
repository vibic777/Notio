package com.notio.shared.transfer

import java.io.ByteArrayOutputStream
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts

/**
 * PDF on the desktop (Android D100, AGENT.md D27): the same page as the phone's `AndroidPdfWriter` — A4, margins of 48 points,
 * title 22 bold, heading 15 bold, body 12, line spacing 1.15, paragraphs wrapped to the page and continued on the next one.
 * The app's Roboto is embedded (subset), so Cyrillic and other scripts print; a character the font lacks becomes "?".
 */
class PdfBoxWriter(
    /** The regular and bold TrueType files; `null` falls back to Helvetica (Latin only), which the tests use. */
    private val fonts: () -> Pair<ByteArray, ByteArray>? = ::classpathRoboto,
) : PdfWriter {

    override fun write(blocks: List<PdfBlock>): ByteArray = PDDocument().use { doc ->
        val files = fonts()
        val regular: PDFont = files?.let { PDType0Font.load(doc, it.first.inputStream()) } ?: PDType1Font(Standard14Fonts.FontName.HELVETICA)
        val bold: PDFont = files?.let { PDType0Font.load(doc, it.second.inputStream()) } ?: PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD)
        var stream: PDPageContentStream? = null
        var y = 0f
        fun newPage() {
            stream?.close()
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            stream = PDPageContentStream(doc, page)
            y = PAGE_HEIGHT - MARGIN
        }
        newPage()
        for (block in blocks) {
            val (font, size) = when (block.style) {
                PdfStyle.TITLE -> bold to 22f
                PdfStyle.HEADING -> bold to 15f
                PdfStyle.BODY -> regular to 12f
            }
            val lineHeight = size * LINE_SPACING
            for (line in wrap(printable(block.text, font), font, size)) {
                if (y - lineHeight < MARGIN) newPage()
                y -= lineHeight
                if (line.isNotEmpty()) {
                    stream!!.beginText()
                    stream!!.setFont(font, size)
                    stream!!.newLineAtOffset(MARGIN, y + (lineHeight - size) / 2 + size * 0.2f)
                    stream!!.showText(line)
                    stream!!.endText()
                }
            }
            y -= if (block.style == PdfStyle.BODY) 2f else 6f
        }
        stream?.close()
        val out = ByteArrayOutputStream()
        doc.save(out)
        out.toByteArray()
    }

    /** The text with every character the font cannot draw replaced, and tabs as spaces. */
    private fun printable(text: String, font: PDFont): String {
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val ch = String(Character.toChars(cp))
            i += ch.length
            out.append(
                when {
                    ch == "\t" -> "    "
                    ch == "\r" -> ""
                    Character.isISOControl(cp) && ch != "\n" -> ""
                    ch == "\n" -> "\n"
                    runCatching { font.encode(ch) }.isSuccess -> ch
                    else -> "?"
                },
            )
        }
        return out.toString()
    }

    /** Lines no wider than the page: broken at spaces, and inside a word only when the word alone is too wide. */
    private fun wrap(text: String, font: PDFont, size: Float): List<String> {
        fun width(s: String) = font.getStringWidth(s) / 1000f * size
        val lines = ArrayList<String>()
        for (paragraph in text.split('\n')) {
            if (paragraph.isEmpty()) { lines += ""; continue }
            var current = StringBuilder()
            for (word in paragraph.split(Regex("(?<= )"))) {
                if (width(current.toString() + word) <= CONTENT_WIDTH) { current.append(word); continue }
                if (current.isNotEmpty()) { lines += current.toString().trimEnd(); current = StringBuilder() }
                var rest = word
                while (width(rest) > CONTENT_WIDTH) {
                    var n = rest.length - 1
                    while (n > 1 && width(rest.substring(0, n)) > CONTENT_WIDTH) n--
                    lines += rest.substring(0, n)
                    rest = rest.substring(n)
                }
                current.append(rest)
            }
            lines += current.toString().trimEnd()
        }
        return lines
    }

    companion object {
        private const val PAGE_HEIGHT = 842f
        private const val MARGIN = 48f
        private const val CONTENT_WIDTH = 595f - 2 * MARGIN
        private const val LINE_SPACING = 1.15f

        /** Roboto from the app's resources (`/font/roboto_regular.ttf`, `/font/roboto_bold.ttf`); `null` when they are not there. */
        fun classpathRoboto(): Pair<ByteArray, ByteArray>? {
            fun read(name: String) = PdfBoxWriter::class.java.getResourceAsStream("/font/$name")?.use { it.readBytes() }
            return read("roboto_regular.ttf")?.let { r -> read("roboto_bold.ttf")?.let { b -> r to b } }
        }
    }
}
