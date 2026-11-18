package com.notebudget.app.data.transfer

import com.notebudget.app.domain.model.ListDocument
import com.notebudget.app.domain.model.ListEntry
import com.notebudget.app.domain.model.NoteType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** TXT and PDF exports of text notes and lists (D100): Markdown syntax is removed, the content stays. */
class TextExportTest {

    @Test
    fun `inline markdown is removed but text with stray asterisks is left alone`() {
        assertEquals("bold italic strike code", PlainText.inline("**bold** *italic* ~~strike~~ `code`"))
        assertEquals("a link (https://x.org) and pic", PlainText.inline("a [link](https://x.org) and ![pic](p.png)"))
        assertEquals("2 * 3 = 6", PlainText.inline("2 * 3 = 6"))
        assertEquals("snake_case_name", PlainText.inline("snake_case_name"))
        assertEquals("under", PlainText.inline("_under_"))
    }

    @Test
    fun `a text note becomes plain text line by line`() {
        val md = "# Plan\n**Total** is *big*\n- [x] done\n- [ ] todo\n- bullet\n> quote\n---\n```\ncode **kept**\n```\n1. first"
        assertEquals(
            "Plan\nTotal is big\n[x] done\n[ ] todo\n• bullet\nquote\n————————\ncode **kept**\n1. first",
            PlainText.fromMarkdown(md),
        )
        assertEquals(PdfStyle.HEADING, PlainText.blocksFromMarkdown(md).first().style)
    }

    @Test
    fun `a text note offers markdown, txt and pdf and each one holds the note`() = runBlocking {
        val t = TransferEnv()
        val f = t.folder("F")
        val note = t.note(f.id, "Итоги", NoteType.TEXT, "# Заголовок\nЖирный **текст**")
        assertEquals(listOf("md", "txt", "pdf"), t.files.formatsFor(note).map { it.key })

        val md = t.files.export(note, t.files.formatsFor(note)[0])
        assertEquals("Итоги.md", md.fileName)
        assertEquals("# Заголовок\nЖирный **текст**", md.bytes.toString(Charsets.UTF_8))

        val txt = t.files.export(note, t.files.formatsFor(note)[1])
        assertEquals("Итоги.txt", txt.fileName)
        assertEquals("text/plain", txt.mimeType)
        assertEquals("Заголовок\nЖирный текст", txt.bytes.toString(Charsets.UTF_8))

        val pdf = t.files.export(note, t.files.formatsFor(note)[2])
        assertEquals("Итоги.pdf", pdf.fileName)
        assertEquals("application/pdf", pdf.mimeType)
        assertEquals("%PDF-FAKE\nTITLE:Итоги\nBODY:\nHEADING:Заголовок\nBODY:Жирный текст", pdf.bytes.toString(Charsets.UTF_8))
    }

    @Test
    fun `a price list exports its check boxes, prices and totals as txt and pdf`() = runBlocking {
        val t = TransferEnv()
        val f = t.folder("F")
        val doc = ListDocument(
            mode = ListDocument.MODE_PRICE,
            entries = listOf(
                ListEntry("a", text = "apples", quantityMilli = 2_000, unit = "pcs", priceMilli = 50_000, priceUnit = "rub"),
                ListEntry("b", text = "bananas", done = true, quantityMilli = 3_000, unit = "pcs", priceMilli = 100_000, priceUnit = "rub"),
            ),
        )
        val encoded = doc.encode({ it }, emptyMap())
        val note = t.note(f.id, "Fruit", NoteType.LIST, encoded.contentText)
        t.env.notes.updateExtras(note.id, encoded.extras)
        val saved = t.env.noteRepo.getById(note.id)!!
        assertEquals(listOf("json", "snapshot", "txt", "pdf"), t.files.formatsFor(saved).map { it.key })

        val txt = t.files.export(saved, t.files.formatsFor(saved).first { it.key == "txt" })
        assertEquals("Fruit.txt", txt.fileName)
        assertEquals(
            "[ ] apples — 2 pcs × 50 rub = 100 rub\n[x] bananas — 3 pcs × 100 rub = 300 rub\n= Total: 5 pcs; 400 rub",
            txt.bytes.toString(Charsets.UTF_8),
        )

        val pdf = t.files.export(saved, t.files.formatsFor(saved).first { it.key == "pdf" }).bytes.toString(Charsets.UTF_8)
        assertTrue(pdf.contains("TITLE:Fruit"))
        assertTrue(pdf.contains("BODY:[x] bananas — 3 pcs × 100 rub = 300 rub"))
        assertTrue(pdf.contains("HEADING:Total: 5 pcs; 400 rub"))
    }
}
