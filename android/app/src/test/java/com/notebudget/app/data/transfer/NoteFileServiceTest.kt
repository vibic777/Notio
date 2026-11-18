package com.notebudget.app.data.transfer

import com.notebudget.app.domain.model.BudgetNodeKind
import com.notebudget.app.domain.model.NoteType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NoteFileServiceTest {

    private fun expectError(error: TransferError, block: suspend () -> Unit) {
        try {
            runBlocking { block() }
            fail("expected $error")
        } catch (e: TransferException) {
            assertEquals(error, e.error)
        }
    }

    @Test
    fun `text note exports as markdown and imports back as text`() = runBlocking {
        val t = TransferEnv()
        val f = t.folder("F")
        val note = t.note(f.id, "My: idea?", NoteType.TEXT, "# Heading\n- [x] done")
        val format = t.files.formatsFor(note).first { it.key == "md" }
        val file = t.files.export(note, format)
        assertEquals("My_ idea_.md", file.fileName)
        assertEquals("text/markdown", file.mimeType)

        val imported = t.files.import(file.fileName, file.bytes, f.id)
        assertEquals(NoteType.TEXT, imported.type)
        assertEquals("# Heading\n- [x] done", imported.contentText)
        assertEquals("My_ idea_", imported.title)
        assertTrue(imported.id != note.id)
    }

    @Test
    fun `list structure round trips and the snapshot comes back as text`() = runBlocking {
        val t = TransferEnv()
        val f = t.folder("F")
        val note = t.env.notes.save(t.note(f.id, "Shopping", NoteType.LIST, "- [ ] milk\n- [x] bread").copy(colorHex = "#00FF00", isTitleBold = true))
            .let { (it as com.notebudget.app.domain.model.DomainResult.Success).value }
        val json = t.files.formatsFor(note).first { it.key == "json" }
        val snapshot = t.files.formatsFor(note).first { it.key == "snapshot" }

        val back = t.files.import("Shopping.json", t.files.export(note, json).bytes, f.id)
        assertEquals(NoteType.LIST, back.type)
        assertEquals("- [ ] milk\n- [x] bread", back.contentText)
        assertEquals("#00FF00", back.colorHex)
        assertTrue(back.isTitleBold)

        val asText = t.files.import("Shopping.md", t.files.export(note, snapshot).bytes, f.id)
        assertEquals(NoteType.TEXT, asText.type) // result only -> TEXT (D24)
    }

    @Test
    fun `budget structure export and import rebuilds the tree with new ids and correct totals`() = runBlocking {
        val t = TransferEnv()
        val f = t.folder("F")
        val note = t.budget(f.id, "Trip")
        val json = t.files.formatsFor(note).first { it.key == "json" }
        val file = t.files.export(note, json)

        val imported = t.files.import(file.fileName, file.bytes, f.id)
        assertEquals(NoteType.BUDGET, imported.type)
        val original = t.nodes(note.id)
        val copy = t.nodes(imported.id)
        assertEquals(original.size, copy.size)
        assertTrue(copy.none { c -> original.any { it.id == c.id } })
        assertEquals(original.map { it.title to it.cachedSumMinor }.sortedBy { it.first }, copy.map { it.title to it.cachedSumMinor }.sortedBy { it.first })
        val root = copy.single { it.parentId == null }
        assertEquals(BudgetNodeKind.GROUP, root.kind)
        assertEquals(51_000L, root.cachedSumMinor)
        assertEquals(42_500L, root.cachedGoalMinor)
        assertEquals(-8_500L, root.cachedRemainderMinor)
    }

    @Test
    fun `a budget file with wrong cached totals is repaired on import`() = runBlocking {
        val t = TransferEnv()
        val f = t.folder("F")
        val json = """{"format":"notebudget-note","formatVersion":1,"minReaderVersion":1,
          "note":{"id":"x","title":"Lie","type":"BUDGET"},
          "payload":{"nodes":[
            {"id":"r","noteId":"x","title":"Lie","kind":"GROUP","cachedSumMinor":999999,"cachedGoalMinor":1,"cachedRemainderMinor":5},
            {"id":"a","noteId":"x","parentId":"r","title":"A","kind":"LEAF","manualSumMinor":700,"manualGoalMinor":1000,"cachedSumMinor":1,"cachedGoalMinor":1}]}}"""
        val note = t.files.import("lie.json", json.toByteArray(), f.id)
        val root = t.nodes(note.id).single { it.parentId == null }
        assertEquals(700L, root.cachedSumMinor)
        assertEquals(300L, root.cachedRemainderMinor)
    }

    @Test
    fun `budget snapshot lists every line with totals and imports as text`() = runBlocking {
        val t = TransferEnv()
        val f = t.folder("F")
        val note = t.budget(f.id, "Trip")
        val snapshot = t.files.export(note, t.files.formatsFor(note).first { it.key == "snapshot" })
        val text = snapshot.bytes.toString(Charsets.UTF_8)
        assertTrue(text, text.startsWith("Trip: sum 510.00 / goal 425.00 / remainder -85.00"))
        assertTrue(text.contains("  - Food: sum 10.00 / goal 25.00 / remainder 15.00"))
        assertTrue(text.contains("    - Bread: sum 10.00"))
        assertEquals(NoteType.TEXT, t.files.import(snapshot.fileName, snapshot.bytes, f.id).type)
    }

    @Test
    fun `png and audio files are recognised by their signature, not their name`() = runBlocking {
        val t = TransferEnv()
        val f = t.folder("F")
        val picture = t.files.import("scan.dat", t.fakePng(4), f.id)
        assertEquals(NoteType.DRAWING, picture.type)
        assertEquals("scan", picture.title)
        assertArrayEquals(t.fakePng(4), t.drawings.read(picture.id))

        val voice = t.files.import("Voice 001.m4a", t.fakeM4a(5), f.id)
        assertEquals(NoteType.AUDIO, voice.type)
        assertArrayEquals(t.fakeM4a(5), t.audio.read(voice.id))
    }

    @Test
    fun `drawing and audio export their file`() = runBlocking {
        val t = TransferEnv()
        val f = t.folder("F")
        val drawing = t.note(f.id, "Pic", NoteType.DRAWING).also { t.drawings.write(it.id, t.fakePng(7)) }
        val png = t.files.export(drawing, t.files.formatsFor(drawing).first { it.key == "png" })
        assertEquals("Pic.png", png.fileName)
        assertArrayEquals(t.fakePng(7), png.bytes)
        assertEquals(listOf("png", "jpeg", StructuredNoteFile.KEY), t.files.formatsFor(drawing).map { it.key })

        val audio = t.note(f.id, "Memo", NoteType.AUDIO).also { t.audio.write(it.id, t.fakeM4a(8)) }
        val m4a = t.files.export(audio, t.files.formatsFor(audio).single())
        assertEquals("Memo.m4a", m4a.fileName)
        assertArrayEquals(t.fakeM4a(8), m4a.bytes)
    }

    @Test
    fun `an empty drawing cannot be exported and does not crash`() {
        val t = TransferEnv()
        expectError(TransferError.CORRUPT) {
            val f = t.folder("F")
            val d = t.note(f.id, "Blank", NoteType.DRAWING)
            t.files.export(d, t.files.formatsFor(d).first())
        }
    }

    @Test
    fun `unknown files and missing folders are reported`() {
        val t = TransferEnv()
        expectError(TransferError.UNRECOGNIZED_FILE) { t.files.import("archive.zip", byteArrayOf(80, 75, 3, 4, 1, 2, 3), t.folder("F").id) }
        expectError(TransferError.UNRECOGNIZED_FILE) { t.files.import("x.json", """{"some":"json"}""".toByteArray(), t.folder("G").id) }
        expectError(TransferError.FOLDER_NOT_FOUND) { t.files.import("a.md", "x".toByteArray(), "nope") }
    }

    @Test
    fun `a binary file named md is not imported as text`() {
        val t = TransferEnv()
        expectError(TransferError.UNRECOGNIZED_FILE) { t.files.import("fake.md", byteArrayOf(1, 0, 2, 0), t.folder("F").id) }
    }

    @Test
    fun `a structured file from a newer version is refused`() {
        val t = TransferEnv()
        val future = """{"format":"notebudget-note","formatVersion":7,"minReaderVersion":7,"note":{"id":"a","title":"t"}}"""
        expectError(TransferError.UNSUPPORTED_VERSION) { t.files.import("n.json", future.toByteArray(), t.folder("F").id) }
    }

    @Test
    fun `an imported note lands in the chosen folder with a fresh timestamp and no pin`() = runBlocking {
        val t = TransferEnv()
        val a = t.folder("A")
        val b = t.folder("B")
        t.env.clock.current = 5_000
        val note = t.files.import("x.md", "hi".toByteArray(), b.id)
        assertEquals(b.id, note.folderId)
        assertEquals(5_000L, note.createdAt)
        assertTrue(!note.isPinned && !note.isFavorite && a.id != note.folderId)
    }
}
