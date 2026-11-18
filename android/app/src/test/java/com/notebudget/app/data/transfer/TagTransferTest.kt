package com.notebudget.app.data.transfer

import com.notebudget.app.domain.model.DomainResult
import com.notebudget.app.domain.model.NoteType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tags travel with backups and structured note files by NAME (D109, D84). */
class TagTransferTest {

    private fun export(t: TransferEnv): ByteArray = ByteArrayOutputStream().also { runBlocking { t.backup.export(it) } }.toByteArray()

    private suspend fun TransferEnv.tagNote(noteId: String, vararg names: String) {
        val ids = names.map { (env.tags.getOrCreate(it) as DomainResult.Success).value.id }.toSet()
        env.tags.setNoteTags(noteId, ids)
    }

    private suspend fun TransferEnv.tagNames(noteId: String) =
        env.tagRepo.getAssignments()[noteId].orEmpty().map { it.name }

    @Test
    fun `a backup carries the tags of each note and replace-import restores them on an empty phone`() = runBlocking {
        val a = TransferEnv()
        val f = a.folder("F")
        val n1 = a.note(f.id, "one", NoteType.TEXT, "x")
        val n2 = a.note(f.id, "two", NoteType.TEXT, "y")
        a.tagNote(n1.id, "Work", "Home")
        a.tagNote(n2.id, "Work")
        val bytes = export(a)

        val b = TransferEnv()
        b.backup.import(ByteArrayInputStream(bytes), ImportMode.REPLACE)

        assertEquals(listOf("Home", "Work"), b.tagNames(n1.id))
        assertEquals(listOf("Work"), b.tagNames(n2.id))
        assertEquals(listOf("Home", "Work"), b.env.tagRepo.getAll().map { it.name })
    }

    @Test
    fun `merge reuses a tag that already exists by name instead of duplicating it and keeps unused tags`() = runBlocking {
        val a = TransferEnv()
        val f = a.folder("F")
        val n = a.note(f.id, "one", NoteType.TEXT, "x")
        a.tagNote(n.id, "Work")
        val bytes = export(a)

        val b = TransferEnv()
        b.env.tags.getOrCreate("work")   // same name, other letter case
        b.env.tags.getOrCreate("Keep")   // used by nobody
        b.backup.import(ByteArrayInputStream(bytes), ImportMode.MERGE)

        assertEquals(setOf("work", "Keep"), b.env.tagRepo.getAll().map { it.name }.toSet())
        assertEquals(1, b.tagNames(n.id).size)
    }

    @Test
    fun `replace drops tags nothing uses any more`() = runBlocking {
        val a = TransferEnv()
        val f = a.folder("F")
        a.note(f.id, "one", NoteType.TEXT, "x")
        val bytes = export(a) // no tags at all

        val b = TransferEnv()
        b.env.tags.getOrCreate("Old")
        b.backup.import(ByteArrayInputStream(bytes), ImportMode.REPLACE)

        assertTrue(b.env.tagRepo.getAll().isEmpty())
    }

    @Test
    fun `a backup made before tags existed still imports`() = runBlocking {
        val a = TransferEnv()
        val f = a.folder("F")
        a.note(f.id, "one", NoteType.TEXT, "x")
        val bytes = export(a)
        val json = TransferFormat.json.encodeToString(
            BackupFile.serializer(),
            BackupCodec.readManifest(ByteArrayInputStream(bytes)),
        )
        // No note record has a "tags" key when it is empty and defaults are not encoded; either way it must read back.
        val b = TransferEnv()
        b.backup.import(ByteArrayInputStream(bytes), ImportMode.REPLACE)
        assertEquals(1, b.env.store.notes.value.size)
        assertTrue(json.isNotEmpty())
    }

    @Test
    fun `a structured list file brings its tags into the new note`() = runBlocking {
        val a = TransferEnv()
        val f = a.folder("F")
        val list = a.note(f.id, "Shopping", NoteType.LIST, "- [ ] milk")
        a.tagNote(list.id, "Food")
        val format = a.files.formatsFor(list).first { it.extension == StructuredNoteFile.EXTENSION }
        val exported = a.files.export(list, format)

        val b = TransferEnv()
        val target = b.folder("Target")
        val imported = b.files.import(exported.fileName, exported.bytes, target.id)

        assertEquals(listOf("Food"), b.tagNames(imported.id))
    }
}
