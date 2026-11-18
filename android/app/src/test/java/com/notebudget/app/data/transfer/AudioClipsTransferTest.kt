package com.notebudget.app.data.transfer

import com.notebudget.app.domain.model.AudioClip
import com.notebudget.app.domain.model.AudioDocument
import com.notebudget.app.domain.model.Note
import com.notebudget.app.domain.model.NoteType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Audio notes with several recordings (D112): export, backup, import and compatibility with the single-file layout. */
class AudioClipsTransferTest {

    private fun export(t: TransferEnv): ByteArray = ByteArrayOutputStream().also { runBlocking { t.backup.export(it) } }.toByteArray()

    /** A note with clips "one" (bytes 1) and "two" (bytes 2), the way the audio screen stores them. */
    private suspend fun TransferEnv.audioNote(title: String = "Lecture", clips: Int = 2): Note {
        val f = folder("F")
        val note = note(f.id, title, NoteType.AUDIO)
        val list = (1..clips).map { AudioClip(id = "c$it", name = if (it == 1) "one" else "two", comment = "comment $it", createdAt = it.toLong()) }
        list.forEachIndexed { i, clip -> audio.write(AudioDocument.fileKey(note.id, clip.id), fakeM4a(i + 1)) }
        env.notes.updateExtras(note.id, AudioDocument(clips = list).encode())
        return env.noteRepo.getById(note.id)!!
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                out[entry.name] = zip.readBytes()
            }
        }
        return out
    }

    @Test
    fun `a note with several recordings exports one zip named after the note title and each clip name`() = runBlocking {
        val t = TransferEnv()
        val note = t.audioNote()

        val format = t.files.formatsFor(note).single()
        assertEquals("zip", format.extension)
        val zip = unzip(t.files.export(note, format).bytes)

        assertEquals(listOf("Lecture - one.m4a", "Lecture - two.m4a"), zip.keys.toList())
        assertArrayEquals(t.fakeM4a(1), zip.getValue("Lecture - one.m4a"))
        assertArrayEquals(t.fakeM4a(2), zip.getValue("Lecture - two.m4a"))
    }

    @Test
    fun `a note with one recording exports it as a plain m4a file`() = runBlocking {
        val t = TransferEnv()
        val note = t.audioNote(clips = 1)
        val format = t.files.formatsFor(note).single()
        assertEquals("m4a", format.extension)
        assertArrayEquals(t.fakeM4a(1), t.files.export(note, format).bytes)
    }

    @Test
    fun `clip file names are safe and never collide`() {
        val clips = listOf(
            AudioClip("a", name = "Part: 1"), AudioClip("b", name = "part: 1"), AudioClip("c", name = ""), AudioClip("d", name = "x/y"),
        )
        assertEquals(
            listOf("My_ note - Part_ 1.m4a", "My_ note - part_ 1 (2).m4a", "My_ note - recording 3.m4a", "My_ note - x_y.m4a"),
            AudioClipNames.exportNames("My: note", clips),
        )
    }

    @Test
    fun `a backup carries every clip and replace-import restores the files, names and comments`() = runBlocking {
        val a = TransferEnv()
        val note = a.audioNote()
        val bytes = export(a)

        val b = TransferEnv()
        b.backup.import(ByteArrayInputStream(bytes), ImportMode.REPLACE)

        val restored = b.env.noteRepo.getById(note.id)!!
        val doc = AudioDocument.parse(restored.extras)!!
        assertEquals(listOf("one", "two"), doc.clips.map { it.name })
        assertEquals(listOf("comment 1", "comment 2"), doc.clips.map { it.comment })
        assertArrayEquals(a.fakeM4a(1), b.audio.read(AudioDocument.fileKey(note.id, "c1")))
        assertArrayEquals(a.fakeM4a(2), b.audio.read(AudioDocument.fileKey(note.id, "c2")))
    }

    @Test
    fun `an imported copy under a new note id gets its own clip files, so deleting one note leaves the other's`() = runBlocking {
        val a = TransferEnv()
        val original = a.audioNote()
        val bytes = export(a)

        a.backup.import(ByteArrayInputStream(bytes), ImportMode.NEW_IDS)
        val copy = a.env.store.notes.value.single { it.id != original.id }

        assertNotEquals(original.id, copy.id)
        assertArrayEquals(a.fakeM4a(1), a.audio.read(AudioDocument.fileKey(copy.id, "c1")))
        assertArrayEquals(a.fakeM4a(2), a.audio.read(AudioDocument.fileKey(copy.id, "c2")))

        AudioTransferHandler(a.audio).deleteAttachments(copy)
        assertNull(a.audio.fileIfExists(AudioDocument.fileKey(copy.id, "c1")))
        assertNotNull(a.audio.fileIfExists(AudioDocument.fileKey(original.id, "c1")))
        assertNotNull(a.audio.fileIfExists(AudioDocument.fileKey(original.id, "c2")))
    }

    @Test
    fun `a backup made before multiple recordings restores its single file as the first clip`() = runBlocking {
        val a = TransferEnv()
        val f = a.folder("F")
        val old = a.note(f.id, "Old memo", NoteType.AUDIO)
        a.audio.write(old.id, a.fakeM4a(7)) // the old layout: one file named after the note, no document
        val bytes = export(a)

        val b = TransferEnv()
        b.backup.import(ByteArrayInputStream(bytes), ImportMode.REPLACE)

        assertArrayEquals(a.fakeM4a(7), b.audio.read(old.id))
        val note = b.env.noteRepo.getById(old.id)!!
        assertEquals(listOf(AudioClip.LEGACY_ID), AudioDocument.forNote(note.extras, b.audio.fileIfExists(old.id) != null).clips.map { it.id })
    }

    @Test
    fun `an imported m4a file becomes a note whose one recording is playable as the first clip`() = runBlocking {
        val t = TransferEnv()
        val f = t.folder("F")
        val imported = t.files.import("memo.m4a", t.fakeM4a(5), f.id)
        assertEquals(NoteType.AUDIO, imported.type)
        assertEquals(1, AudioDocument.forNote(imported.extras, t.audio.fileIfExists(imported.id) != null).clips.size)
    }

    @Test
    fun `the document tolerates unknown fields and unreadable extras`() {
        val doc = AudioDocument.parse("""{"v":9,"clips":[{"id":"x","name":"n","future":1}],"other":true}""")!!
        assertEquals("n", doc.clips.single().name)
        assertNull(AudioDocument.parse("not json"))
        assertNull(AudioDocument.parse(null))
        assertTrue(AudioDocument.forNote("garbage", legacyFileExists = false).clips.isEmpty())
        assertEquals(1, AudioDocument.forNote("garbage", legacyFileExists = true).clips.size)
    }
}
