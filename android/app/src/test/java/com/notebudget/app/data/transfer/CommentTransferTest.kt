package com.notebudget.app.data.transfer

import com.notebudget.app.domain.model.NoteType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The comment of a note and of a folder (D177) travels in backups and in single-note files, and old files have none. */
class CommentTransferTest {

    @Test
    fun `a full backup brings the comments back`() = runBlocking {
        val a = TransferEnv()
        val folder = a.folder("F")
        val note = a.note(folder.id, "N", NoteType.TEXT, "body")
        a.env.notes.setComment(note.id, "call the bank")
        a.env.folders.setComment(folder.id, "work")
        val out = ByteArrayOutputStream().also { runBlocking { a.backup.export(it) } }.toByteArray()

        val b = TransferEnv()
        b.backup.import(ByteArrayInputStream(out), ImportMode.REPLACE)

        assertEquals("call the bank", b.env.noteRepo.getById(note.id)!!.comment)
        assertEquals("work", b.env.folderRepo.getById(folder.id)!!.comment)
    }

    @Test
    fun `a single note file keeps the comment of a list`() = runBlocking {
        val t = TransferEnv()
        val folder = t.folder("F")
        val note = t.note(folder.id, "Groceries", NoteType.LIST, "- [ ] milk")
        t.env.notes.setComment(note.id, "for Saturday")
        val latest = t.env.noteRepo.getById(note.id)!!
        val structure = t.files.formatsFor(latest).first { it.key == "json" }
        val file = t.files.export(latest, structure)

        val imported = t.files.import(file.fileName, file.bytes, t.folder("Inbox").id)

        assertEquals("for Saturday", imported.comment)
    }

    @Test
    fun `records made before comments existed load without one`() {
        val note = TransferFormat.json.decodeFromString(NoteRecord.serializer(), """{"id":"n","title":"T"}""")
        val folder = TransferFormat.json.decodeFromString(FolderRecord.serializer(), """{"id":"f","name":"F"}""")
        assertNull(note.comment)
        assertNull(folder.comment)
        assertNull(TransferFormat.json.decodeFromString(NoteRecord.serializer(), """{"id":"n","title":"T","comment":"   "}""").toDomain().comment)
    }

    @Test
    fun `a favourite of an older file comes in as a pinned note`() {
        val star = TransferFormat.json.decodeFromString(
            NoteRecord.serializer(), """{"id":"n","title":"T","isFavorite":true,"updatedAt":1234}""",
        ).toDomain()
        assertEquals(true, star.isPinned)
        assertEquals(false, star.isFavorite)
        assertEquals(1234L, star.pinnedAt)

        // A note that was pinned and a favourite keeps its own pin time.
        val both = TransferFormat.json.decodeFromString(
            NoteRecord.serializer(), """{"id":"n","title":"T","isPinned":true,"isFavorite":true,"pinnedAt":99,"updatedAt":1234}""",
        ).toDomain()
        assertEquals(99L, both.pinnedAt)

        val plain = TransferFormat.json.decodeFromString(NoteRecord.serializer(), """{"id":"n","title":"T"}""").toDomain()
        assertEquals(false, plain.isPinned)
        assertEquals(0L, plain.pinnedAt)
    }
}
