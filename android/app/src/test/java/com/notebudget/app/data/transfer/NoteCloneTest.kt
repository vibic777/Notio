package com.notebudget.app.data.transfer

import com.notebudget.app.data.media.MediaStore
import com.notebudget.app.domain.model.AudioClip
import com.notebudget.app.domain.model.AudioDocument
import com.notebudget.app.domain.model.DomainError
import com.notebudget.app.domain.model.DomainResult
import com.notebudget.app.domain.model.MediaDocument
import com.notebudget.app.domain.model.MediaItem
import com.notebudget.app.domain.model.MediaKind
import com.notebudget.app.domain.model.Note
import com.notebudget.app.domain.model.NoteType
import com.notebudget.app.domain.model.ReminderDraft
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cloning a note of any kind (D179): a copy in the same folder named `title_clone1`, `_clone2` ..., independent of the original. */
class NoteCloneTest {
    private val format = "_clone%1\$d"

    private fun TransferEnv.cloner(lookup: NoteHandlerLookup = this.lookup) =
        NoteCloneService(env.noteRepo, lookup, env.tagRepo, env.tags, env.clock, env.ids)

    private suspend fun NoteCloneService.ok(noteId: String): Note = (clone(noteId, format) as DomainResult.Success).value

    // ---------------- the names ----------------

    @Test
    fun `a clone is named with the next free number, ignoring case, and a clone of a clone keeps counting`() {
        assertEquals("Milk_clone1", CloneNames.next("Milk", listOf("Milk"), format))
        assertEquals("Milk_clone2", CloneNames.next("Milk", listOf("Milk", "Milk_clone1"), format))
        // A gap is filled: the first free number wins.
        assertEquals("Milk_clone2", CloneNames.next("Milk", listOf("Milk_clone1", "milk_CLONE3"), format))
        // The postfix of a clone is cut off first: a clone of "Milk_clone1" is "Milk_clone2", not "Milk_clone1_clone1".
        assertEquals("Milk_clone2", CloneNames.next("Milk_clone1", listOf("Milk", "Milk_clone1"), format))
        // Another language, another postfix.
        assertEquals("Молоко_клон1", CloneNames.next("Молоко", listOf("Молоко"), "_клон%1\$d"))
        assertEquals("Молоко_клон3", CloneNames.next("Молоко_клон2", listOf("Молоко_клон1", "Молоко_клон2"), "_клон%1\$d"))
        // A title that is only a postfix keeps itself.
        assertEquals("_clone1_clone1", CloneNames.next("_clone1", listOf("_clone1"), format))
    }

    // ---------------- notes ----------------

    @Test
    fun `a text note is cloned with its content, colour, comment and tags, but not its pin, star and reminders`() = runBlocking {
        val t = TransferEnv()
        val folder = t.folder("F")
        val note = t.note(folder.id, "Milk", NoteType.TEXT, "two litres")
        t.env.notes.setComment(note.id, "for Saturday")
        t.env.notes.togglePinnedForTest(note)
        val tag = (t.env.tags.create("shop") as DomainResult.Success).value
        t.env.tags.setNoteTags(note.id, setOf(tag.id))
        t.env.reminders.create(note.id, ReminderDraft(triggerAt = t.env.clock.now() + 60_000))
        val original = t.env.noteRepo.getById(note.id)!!

        val copy = t.cloner().ok(note.id)

        assertEquals("Milk_clone1", copy.title)
        assertNotEquals(original.id, copy.id)
        assertEquals(folder.id, copy.folderId)
        assertEquals("two litres", copy.contentText)
        assertEquals("for Saturday", copy.comment)
        assertFalse(copy.isPinned)
        assertFalse(copy.isFavorite)
        assertEquals(listOf("shop"), t.env.tagRepo.getAssignments().getValue(copy.id).map { it.name })
        assertTrue(t.env.reminderRepo.getByNoteIds(listOf(copy.id)).isEmpty())
        // The original is untouched, and both are in the folder.
        assertEquals(original, t.env.noteRepo.getById(note.id))
        assertEquals(setOf("Milk", "Milk_clone1"), t.env.noteRepo.getByFolder(folder.id).map { it.title }.toSet())
    }

    @Test
    fun `cloning again and cloning the clone give clone2 and clone3`() = runBlocking {
        val t = TransferEnv()
        val note = t.note(t.folder("F").id, "Milk", NoteType.TEXT, "x")
        val cloner = t.cloner()

        val first = cloner.ok(note.id)
        val second = cloner.ok(note.id)
        val third = cloner.ok(first.id)

        assertEquals(listOf("Milk_clone1", "Milk_clone2", "Milk_clone3"), listOf(first, second, third).map { it.title })
    }

    @Test
    fun `a budget clone has its own copy of the tree, with the same totals`() = runBlocking {
        val t = TransferEnv()
        val note = t.budget(t.folder("F").id, "Trip")
        val before = t.nodes(note.id)

        val copy = t.cloner().ok(note.id)

        val nodes = t.nodes(copy.id)
        assertEquals(before.size, nodes.size)
        assertEquals(before.map { it.title }.sorted(), nodes.map { it.title }.sorted())
        assertTrue(nodes.none { n -> before.any { it.id == n.id } }) // new ids
        assertTrue(nodes.all { it.noteId == copy.id })
        assertEquals(before.single { it.parentId == null }.cachedSumMinor, nodes.single { it.parentId == null }.cachedSumMinor)
        // The history of the original does not come along.
        assertTrue(t.env.logRepo.getByNote(copy.id).isEmpty())
        // Changing the clone leaves the original alone.
        val bread = nodes.single { it.title == "Bread" }
        t.env.budget.updateLeaf(copy.id, bread.id, 9_999, 9_999)
        assertEquals(before, t.nodes(note.id))
    }

    @Test
    fun `audio, drawing and photo clones get their own files`() = runBlocking {
        val t = TransferEnv()
        val folder = t.folder("F")

        val audio = t.note(folder.id, "Lecture", NoteType.AUDIO)
        val clips = listOf(AudioClip(id = "c1", name = "one"), AudioClip(id = "c2", name = "two"))
        clips.forEachIndexed { i, c -> t.audio.write(AudioDocument.fileKey(audio.id, c.id), t.fakeM4a(i + 1)) }
        t.env.notes.updateExtras(audio.id, AudioDocument(clips = clips).encode())

        val drawing = t.note(folder.id, "Sketch", NoteType.DRAWING)
        t.drawings.write(drawing.id, t.fakePng(4))

        val store = MediaStore(File(t.dir, "media"))
        val photoHandler = MediaTransferHandler(MediaKind.PHOTO, store)
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3, 4, 5, 6, 7, 8)
        val photo = t.note(folder.id, "Trip photos", MediaKind.PHOTO.type)
        store.write(MediaDocument.fileKey(photo.id, "p1"), MediaKind.PHOTO, jpeg)
        t.env.notes.updateExtras(photo.id, MediaDocument(items = listOf(MediaItem(id = "p1", name = "beach"))).encode())
        val lookup = object : NoteHandlerLookup {
            override fun handlerFor(type: NoteType) = if (type == MediaKind.PHOTO.type) photoHandler else t.lookup.handlerFor(type)
            override val allHandlers get() = t.lookup.allHandlers + photoHandler
        }
        val cloner = t.cloner(lookup)

        val audioCopy = cloner.ok(audio.id)
        assertArrayEquals(t.fakeM4a(1), t.audio.read(AudioDocument.fileKey(audioCopy.id, "c1")))
        assertArrayEquals(t.fakeM4a(2), t.audio.read(AudioDocument.fileKey(audioCopy.id, "c2")))
        assertEquals(listOf("one", "two"), AudioDocument.parse(audioCopy.extras)!!.clips.map { it.name })

        val drawingCopy = cloner.ok(drawing.id)
        assertArrayEquals(t.fakePng(4), t.drawings.read(drawingCopy.id))

        val photoCopy = cloner.ok(photo.id)
        assertArrayEquals(jpeg, store.read(MediaDocument.fileKey(photoCopy.id, "p1"), MediaKind.PHOTO))
        assertEquals("beach", MediaDocument.parse(photoCopy.extras).items.single().name)

        // The originals still have their files.
        assertArrayEquals(t.fakeM4a(1), t.audio.read(AudioDocument.fileKey(audio.id, "c1")))
        assertArrayEquals(t.fakePng(4), t.drawings.read(drawing.id))
        assertArrayEquals(jpeg, store.read(MediaDocument.fileKey(photo.id, "p1"), MediaKind.PHOTO))
    }

    @Test
    fun `a clone that fails halfway is removed and the original stays`() = runBlocking {
        val t = TransferEnv()
        val folder = t.folder("F")
        val note = t.note(folder.id, "Milk", NoteType.TEXT, "x")
        val broken = object : NoteHandlerLookup {
            override fun handlerFor(type: NoteType): NoteTransferHandler? = object : NoteTransferHandler {
                override val exportFormats = emptyList<ExportFormat>()
                override suspend fun export(note: Note, format: ExportFormat) = ByteArray(0)
                override suspend fun copyData(from: Note, to: Note) = error("the disk is full")
            }
            override val allHandlers get() = emptyList<NoteTransferHandler>()
        }

        val result = t.cloner(broken).clone(note.id, format)

        assertEquals(DomainError.CLONE_FAILED, (result as DomainResult.Failure).error)
        assertEquals(listOf("Milk"), t.env.noteRepo.getByFolder(folder.id).map { it.title })
        assertTrue(t.env.noteRepo.getTrashed().isEmpty())
        assertNull(t.env.noteRepo.getByIdIncludingDeleted("id-does-not-exist"))
    }

    @Test
    fun `a missing note cannot be cloned`() = runBlocking {
        val t = TransferEnv()
        assertEquals(DomainError.NOTE_NOT_FOUND, (t.cloner().clone("nope", format) as DomainResult.Failure).error)
    }

    /** Pins a note the way the card menu does. */
    private suspend fun com.notebudget.app.domain.usecase.NoteUseCases.togglePinnedForTest(note: Note) {
        save(note.copy(isPinned = true, isFavorite = true))
    }
}
