package com.notebudget.app.data.transfer

import com.notebudget.app.domain.model.BudgetLogKind
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The log of a budget note (D166) travels in a backup, in every import mode, and a backup without it still loads. */
class BudgetLogTransferTest {

    private fun exportBackup(t: TransferEnv): ByteArray =
        ByteArrayOutputStream().also { runBlocking { t.backup.export(it) } }.toByteArray()

    @Test
    fun `a full backup brings the log back with its note`() = runBlocking {
        val source = TransferEnv()
        val note = source.budget(source.folder("F").id, "Trip")
        val written = source.env.logRepo.getByNote(note.id)
        assertTrue(written.isNotEmpty())
        assertTrue(written.any { it.kind == BudgetLogKind.ITEM_ADDED && it.title == "Bread" })

        val target = TransferEnv()
        target.backup.import(ByteArrayInputStream(exportBackup(source)), ImportMode.REPLACE)

        assertEquals(written, target.env.logRepo.getByNote(note.id))
    }

    @Test
    fun `a full restore replaces the logs that were there`() = runBlocking {
        val source = TransferEnv()
        val note = source.budget(source.folder("F").id, "Trip")
        val file = exportBackup(source)

        // The target has its own budget and its own log (and a stray line); the restore replaces all of it.
        val target = TransferEnv()
        val other = target.budget(target.folder("G").id, "Other")
        target.env.logRepo.add(listOf(com.notebudget.app.domain.model.BudgetLogEntry("stray", other.id, 1, BudgetLogKind.DELETED, "Gone")))
        target.backup.import(ByteArrayInputStream(file), ImportMode.REPLACE)

        assertTrue(target.env.logRepo.getAll().none { it.id == "stray" })
        assertEquals(source.env.logRepo.getAll(), target.env.logRepo.getAll())
        assertEquals(source.env.logRepo.getByNote(note.id), target.env.logRepo.getByNote(note.id))
    }

    @Test
    fun `importing as new copies gives the lines to the copy of the note`() = runBlocking {
        val source = TransferEnv()
        val note = source.budget(source.folder("F").id, "Trip")
        val written = source.env.logRepo.getByNote(note.id)
        val file = exportBackup(source)

        val target = TransferEnv()
        repeat(200) { target.env.ids.newId() } // the target hands out ids the source has not used
        target.backup.import(ByteArrayInputStream(file), ImportMode.NEW_IDS)

        val copy = target.env.store.notes.value.single { it.type == com.notebudget.app.domain.model.NoteType.BUDGET }
        assertTrue(copy.id != note.id)
        val lines = target.env.logRepo.getByNote(copy.id)
        assertEquals(written.map { it.kind to it.title }, lines.map { it.kind to it.title })
        assertTrue(lines.none { l -> written.any { it.id == l.id } }) // new ids
        assertTrue(target.env.logRepo.getAll().all { it.noteId == copy.id })
    }

    @Test
    fun `merging twice does not write the lines twice`() = runBlocking {
        val source = TransferEnv()
        source.budget(source.folder("F").id, "Trip")
        val file = exportBackup(source)

        val target = TransferEnv()
        target.backup.import(ByteArrayInputStream(file), ImportMode.MERGE)
        val once = target.env.logRepo.getAll().size
        target.backup.import(ByteArrayInputStream(file), ImportMode.MERGE)

        assertEquals(once, target.env.logRepo.getAll().size)
    }

    @Test
    fun `a backup made before the log existed loads without one`() {
        val old = TransferFormat.json.decodeFromString(BackupFile.serializer(), """{"format":"notebudget-backup","formatVersion":1}""")
        assertTrue(old.budgetLog.isEmpty())
    }
}
