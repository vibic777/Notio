package com.notebudget.app.data.transfer

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.ByteArrayOutputStream

/**
 * PDF through the platform's `PdfDocument` (D100): A4 pages, the system font (so Cyrillic and other scripts
 * print), paragraphs wrapped to the page and continued on the next page. Nothing is downloaded or installed.
 */
class AndroidPdfWriter : PdfWriter {

    override fun write(blocks: List<PdfBlock>): ByteArray {
        val document = PdfDocument()
        var pageNumber = 1
        var page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
        var y = MARGIN.toFloat()
        try {
            for (block in blocks) {
                val paint = paintFor(block.style)
                val text = block.text.ifEmpty { " " }
                val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, CONTENT_WIDTH)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(0f, 1.15f)
                    .setIncludePad(false)
                    .build()
                for (line in 0 until layout.lineCount) {
                    val top = layout.getLineTop(line)
                    val bottom = layout.getLineBottom(line)
                    if (y + (bottom - top) > PAGE_HEIGHT - MARGIN) {
                        document.finishPage(page)
                        pageNumber++
                        page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
                        y = MARGIN.toFloat()
                    }
                    val canvas = page.canvas
                    canvas.save()
                    canvas.translate(MARGIN.toFloat(), y - top)
                    canvas.clipRect(0f, top.toFloat(), CONTENT_WIDTH.toFloat(), bottom.toFloat())
                    layout.draw(canvas)
                    canvas.restore()
                    y += bottom - top
                }
                y += if (block.style == PdfStyle.BODY) 2f else 6f
            }
            document.finishPage(page)
            val out = ByteArrayOutputStream()
            document.writeTo(out)
            return out.toByteArray()
        } finally {
            document.close()
        }
    }

    private fun paintFor(style: PdfStyle) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF000000.toInt()
        when (style) {
            PdfStyle.TITLE -> { textSize = 22f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
            PdfStyle.HEADING -> { textSize = 15f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
            PdfStyle.BODY -> { textSize = 12f; typeface = Typeface.DEFAULT }
        }
    }

    private companion object {
        // A4 in PostScript points.
        const val PAGE_WIDTH = 595
        const val PAGE_HEIGHT = 842
        const val MARGIN = 48
        const val CONTENT_WIDTH = PAGE_WIDTH - 2 * MARGIN
    }
}
