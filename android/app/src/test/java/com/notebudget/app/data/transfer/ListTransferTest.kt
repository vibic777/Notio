package com.notebudget.app.data.transfer

import com.notebudget.app.domain.model.CustomUnit
import com.notebudget.app.domain.model.ListDocument
import com.notebudget.app.domain.model.ListEntry
import com.notebudget.app.domain.model.Note
import com.notebudget.app.domain.model.NoteType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lists with quantities, units, comments and totals (D92) travel through backups and note files. */
class ListTransferTest {
    private fun label(id: String) = if (id == "u-packs") "packs" else id

    private val document = ListDocument(
        entries = listOf(
            ListEntry("a", text = "Flour", quantityMilli = 1_500, unit = "kg", comment = "wholegrain"),
            ListEntry("b", text = "Tea", quantityMilli = 3_000, unit = "u-packs", done = true),
            ListEntry("t", kind = ListEntry.TOTAL, text = "Kilos", unit = "kg", comment = "for baking"),
        ),
    )

    private suspend fun listNote(t: TransferEnv, folderName: String = "F"): Note {
        val note = t.note(t.folder(folderName).id, "Shopping", NoteType.LIST)
        val encoded = document.encode(::label, mapOf("u-packs" to "packs"))
        t.env.notes.save(note.copy(contentText = encoded.contentText, extras = encoded.extras), withExtras = true)
        return t.env.noteRepo.getById(note.id)!!
    }

    private fun exportBackup(t: TransferEnv): ByteArray =
        ByteArrayOutputStream().also { runBlocking { t.backup.export(it) } }.toByteArray()

    @Test
    fun `a full backup keeps the structure and the text of a list`() = runBlocking {
        val source = TransferEnv()
        val note = listNote(source)
        val file = exportBackup(source)

        val target = TransferEnv()
        target.backup.import(ByteArrayInputStream(file), ImportMode.REPLACE)

        val restored = target.env.noteRepo.getById(note.id)!!
        assertEquals(note.contentText, restored.contentText)
        assertEquals(note.extras, restored.extras)
        val back = ListDocument.fromNote(restored.contentText, restored.extras)
        assertEquals(document.entries, back.entries)
        assertEquals(mapOf("u-packs" to "packs"), back.unitNames)
    }

    @Test
    fun `a single list file with structure imports back with quantities, units and comments`() = runBlocking {
        val t = TransferEnv()
        val note = listNote(t)
        val format = t.files.formatsFor(note).first { it.key == "json" }
        val file = t.files.export(note, format)

        val imported = t.files.import(file.fileName, file.bytes, t.folder("Inbox").id)

        assertEquals(NoteType.LIST, imported.type)
        assertTrue(imported.id != note.id)
        val doc = ListDocument.fromNote(imported.contentText, imported.extras)
        assertEquals(document.entries, doc.entries)
        assertEquals(1_500L, doc.entries[0].quantityMilli)
        assertEquals("wholegrain", doc.entries[0].comment)
        assertEquals(1_500L, doc.totalOf(doc.entries[2]))
    }

    @Test
    fun `the text snapshot of a list carries the quantities and totals`() = runBlocking {
        val t = TransferEnv()
        val note = listNote(t)
        val snapshot = t.files.export(note, t.files.formatsFor(note).first { it.key == "snapshot" })
        assertEquals(
            "- [ ] Flour — 1.5 kg (wholegrain)\n- [x] Tea — 3 packs\n= Kilos: 1.5 kg (for baking)",
            snapshot.bytes.toString(Charsets.UTF_8),
        )
    }

    @Test
    fun `a list file made before quantities existed still imports as plain items`() = runBlocking {
        val t = TransferEnv()
        val note = listNote(t)
        val format = t.files.formatsFor(note).first { it.key == "json" }
        val file = t.files.export(note, format)
        // An old file has no "extras" in the note record.
        val text = file.bytes.toString(Charsets.UTF_8).replace(Regex(""","extras":"(?:[^"\\]|\\.)*""""), "")
        assertTrue(!text.contains("extras"))

        val imported = t.files.import(file.fileName, text.toByteArray(), t.folder("Inbox").id)

        assertNull(imported.extras)
        val doc = ListDocument.fromNote(imported.contentText, imported.extras)
        assertEquals(3, doc.entries.size) // the text copy still lists every row
        assertNotNull(doc.entries.firstOrNull { it.text.startsWith("Flour") })
        assertTrue(doc.entries.all { it.quantityMilli == null })
    }

    @Test
    fun `custom units and their names travel in the settings of a backup`() = runBlocking {
        val source = TransferEnv()
        source.settings.value = source.settings.value.copy(customUnits = listOf(CustomUnit("u-packs", "packs")))
        source.folder("F")
        val file = exportBackup(source)

        val target = TransferEnv()
        target.backup.import(ByteArrayInputStream(file), ImportMode.REPLACE)

        assertEquals(listOf(CustomUnit("u-packs", "packs")), target.settings.value.customUnits)
    }
}
