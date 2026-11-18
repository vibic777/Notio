package com.notebudget.app.data.transfer

import com.notebudget.app.domain.model.AudioClip
import com.notebudget.app.domain.model.AudioDocument
import com.notebudget.app.domain.model.Note
import com.notebudget.app.domain.model.NoteType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Backups are written and read as streams (D174): files are copied one by one, whatever their size; bad files are skipped and counted. */
class StreamingBackupTest {

    /** A big "recording": `size` bytes that look like an MPEG-4 file at the start (the signature the audio kind checks). */
    private fun bigM4a(size: Int, seed: Int = 1): ByteArray {
        val bytes = ByteArray(size)
        var x = seed * 2654435761L
        for (i in bytes.indices) { x = x * 6364136223846793005L + 1442695040888963407L; bytes[i] = (x ushr 33).toByte() }
        val head = byteArrayOf(0, 0, 0, 0x20, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(), 'M'.code.toByte(), '4'.code.toByte(), 'A'.code.toByte(), ' '.code.toByte())
        head.copyInto(bytes)
        return bytes
    }

    private suspend fun TransferEnv.audioNote(vararg clips: Pair<String, ByteArray>): Note {
        val f = folder("F")
        val note = note(f.id, "Lecture", NoteType.AUDIO)
        val list = clips.mapIndexed { i, (id, _) -> AudioClip(id = id, name = "clip $id", comment = "", createdAt = i.toLong()) }
        clips.forEach { (id, bytes) -> audio.write(AudioDocument.fileKey(note.id, id), bytes) }
        env.notes.updateExtras(note.id, AudioDocument(clips = list).encode())
        return env.noteRepo.getById(note.id)!!
    }

    private fun export(t: TransferEnv, password: String? = null, onProgress: (Int, Int) -> Unit = { _, _ -> }): Pair<ByteArray, BackupExportReport> {
        val out = ByteArrayOutputStream()
        val report = runBlocking { t.backup.export(out, password, onProgress = onProgress) }
        return out.toByteArray() to report
    }

    private fun entries(zip: ByteArray): List<String> {
        val names = ArrayList<String>()
        ZipInputStream(ByteArrayInputStream(zip)).use { z -> while (true) { val e = z.nextEntry ?: break; names += e.name } }
        return names
    }

    private fun rebuild(zip: ByteArray, transform: (MutableList<Pair<String, ByteArray>>) -> Unit): ByteArray {
        val parts = ArrayList<Pair<String, ByteArray>>()
        ZipInputStream(ByteArrayInputStream(zip)).use { z -> while (true) { val e = z.nextEntry ?: break; parts += e.name to z.readBytes() } }
        transform(parts)
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> parts.forEach { (name, bytes) -> z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() } }
        return out.toByteArray()
    }

    @Test
    fun `a backup with a big recording round-trips byte for byte`() = runBlocking {
        val a = TransferEnv()
        val big = bigM4a(12 * 1024 * 1024, 7)
        val note = a.audioNote("c1" to big, "c2" to bigM4a(50_000, 9))
        val (file, report) = export(a)
        assertEquals(2, report.filesWritten)
        assertEquals(0, report.filesSkipped)

        val b = TransferEnv()
        val result = b.backup.import(ByteArrayInputStream(file), ImportMode.REPLACE)

        assertEquals(2, result.filesRestored)
        assertEquals(0, result.filesSkipped)
        assertArrayEquals(big, b.audio.read(AudioDocument.fileKey(note.id, "c1")))
        assertArrayEquals(bigM4a(50_000, 9), b.audio.read(AudioDocument.fileKey(note.id, "c2")))
    }

    @Test
    fun `the manifest comes first in the file and progress counts the files`() = runBlocking {
        val a = TransferEnv()
        a.audioNote("c1" to bigM4a(1_000), "c2" to bigM4a(2_000))
        val seen = ArrayList<Pair<Int, Int>>()
        val (file, _) = export(a) { done, total -> seen += done to total }

        assertEquals(BackupCodec.MANIFEST, entries(file).first())
        assertEquals(listOf(1 to 2, 2 to 2), seen)
    }

    @Test
    fun `a protected backup streams too, and a wrong password stops before anything changes`() = runBlocking {
        val a = TransferEnv()
        val note = a.audioNote("c1" to bigM4a(3 * 1024 * 1024, 3))
        val (file, _) = export(a, password = "s3cret")

        val b = TransferEnv()
        b.folder("Existing")
        val before = b.env.store.folders.value
        try {
            b.backup.import(ByteArrayInputStream(file), ImportMode.REPLACE, "wrong")
            fail("the wrong password must be refused")
        } catch (e: TransferException) {
            assertEquals(TransferError.WRONG_PASSWORD, e.error)
        }
        assertEquals(before, b.env.store.folders.value)

        b.backup.import(ByteArrayInputStream(file), ImportMode.REPLACE, "s3cret")
        assertArrayEquals(bigM4a(3 * 1024 * 1024, 3), b.audio.read(AudioDocument.fileKey(note.id, "c1")))
    }

    @Test
    fun `the source is opened again for the second pass and nothing is copied to memory first`() = runBlocking {
        val a = TransferEnv()
        a.audioNote("c1" to bigM4a(200_000))
        val (file, _) = export(a)

        var opens = 0
        val b = TransferEnv()
        val result = b.backup.importFrom({ opens++; ByteArrayInputStream(file) }, ImportMode.REPLACE)

        assertEquals(2, opens) // the manifest pass and the files pass
        assertEquals(1, result.filesRestored)
    }

    @Test
    fun `a backup written the old way, all in memory, still imports`() = runBlocking {
        val a = TransferEnv()
        val note = a.audioNote("c1" to bigM4a(30_000, 4))
        val (file, _) = export(a)
        val manifest = BackupCodec.readManifest(ByteArrayInputStream(file))
        val attachments = HashMap<String, ByteArray>()
        BackupCodec.AttachmentReader(ByteArrayInputStream(file)).use { reader ->
            while (true) {
                val item = reader.next() ?: break
                attachments[BackupCodec.attachmentPath(item.noteId, item.name)] = item.stream.readBytes()
            }
        }
        val old = ByteArrayOutputStream().also { BackupCodec.write(it, manifest, attachments) }.toByteArray()

        val b = TransferEnv()
        val result = b.backup.import(ByteArrayInputStream(old), ImportMode.REPLACE)

        assertEquals(1, result.filesRestored)
        assertArrayEquals(bigM4a(30_000, 4), b.audio.read(AudioDocument.fileKey(note.id, "c1")))
    }

    @Test
    fun `a file that has its manifest late is still read`() = runBlocking {
        val a = TransferEnv()
        val note = a.audioNote("c1" to bigM4a(5_000, 5))
        val (file, _) = export(a)
        val shuffled = rebuild(file) { it.reverse() } // the attachments first, the manifest last

        val b = TransferEnv()
        val result = b.backup.import(ByteArrayInputStream(shuffled), ImportMode.REPLACE)

        assertEquals(1, result.filesRestored)
        assertArrayEquals(bigM4a(5_000, 5), b.audio.read(AudioDocument.fileKey(note.id, "c1")))
    }

    @Test
    fun `files that are not what they claim, names that climb out of a folder and unknown notes are never written`() = runBlocking {
        val a = TransferEnv()
        val note = a.audioNote("c1" to bigM4a(4_000, 6))
        val (file, _) = export(a)
        val hostile = rebuild(file) { parts ->
            parts += "attachments/${note.id}/clip-c2.m4a" to "this is text, not an audio file at all".toByteArray()
            parts += "attachments/${note.id}/../../evil.m4a" to bigM4a(2_000)
            parts += "attachments/${note.id}/sub/dir.m4a" to bigM4a(2_000)
            parts += "attachments/no-such-note/clip-c9.m4a" to bigM4a(2_000)
            parts += "attachments/${note.id}/clip-x y.m4a" to bigM4a(2_000)
            parts += "elsewhere/readme.txt" to "hello".toByteArray()
        }

        val b = TransferEnv()
        val result = b.backup.import(ByteArrayInputStream(hostile), ImportMode.REPLACE)

        assertEquals(1, result.filesRestored) // the real clip
        assertEquals(2, result.filesSkipped) // the text posing as audio, and the clip id with a space
        assertNull(b.audio.fileIfExists(AudioDocument.fileKey(note.id, "c2")))
        assertNull(b.audio.fileIfExists("evil"))
        assertArrayEquals(bigM4a(4_000, 6), b.audio.read(AudioDocument.fileKey(note.id, "c1")))
        // Nothing was left half written.
        assertTrue(b.dir.walkTopDown().none { it.name.endsWith(".tmp") })
    }

    @Test
    fun `a damaged tail keeps what was read and reports the rest as missing`() = runBlocking {
        val a = TransferEnv()
        val note = a.audioNote("c1" to bigM4a(60_000, 8))
        val (file, _) = export(a)
        val cut = file.copyOf(file.size - 20_000) // the file was cut in the middle of the clip

        val b = TransferEnv()
        val result = b.backup.import(ByteArrayInputStream(cut), ImportMode.REPLACE)

        assertTrue(result.filesSkipped >= 1)
        assertEquals(0, result.filesRestored)
        // The notes came in; the half file did not stay.
        assertEquals(note.title, b.env.noteRepo.getById(note.id)!!.title)
        assertNull(b.audio.fileIfExists(AudioDocument.fileKey(note.id, "c1")))
    }

    @Test
    fun `a file over the size limit is left out and counted, and so is what would pass the total`() {
        val manifest = BackupFile()
        fun entry(name: String, size: Int) = AttachmentEntry("attachments/n/$name", size.toLong()) { ByteArrayInputStream(ByteArray(size)) }

        val out = ByteArrayOutputStream()
        val result = BackupCodec.writeStreaming(
            out, manifest, listOf(entry("a.m4a", 100), entry("big.m4a", 5_000), entry("b.m4a", 100), entry("c.m4a", 100)),
            maxFileBytes = 1_000, maxTotalBytes = 250,
        )

        assertEquals(2, result.written) // a and b; c would pass the total of 250
        assertEquals(2, result.skipped) // big is over the file limit, c over the total
        assertEquals(listOf("backup.json", "attachments/n/a.m4a", "attachments/n/b.m4a"), entries(out.toByteArray()))
    }

    @Test
    fun `an entry bigger than allowed is refused while it is read`() {
        val zip = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { z ->
                z.putNextEntry(ZipEntry("attachments/n/a.m4a")); z.write(ByteArray(500)); z.closeEntry()
            }
        }.toByteArray()

        BackupCodec.AttachmentReader(ByteArrayInputStream(zip), maxFileBytes = 100).use { reader ->
            val item = reader.next()!!
            val stream: InputStream = item.stream
            try {
                stream.readBytes()
                fail("the limit must stop the read")
            } catch (e: TransferException) {
                assertEquals(TransferError.CORRUPT, e.error)
            }
        }
    }

    /** Writes [size] bytes to [file] a chunk at a time (never all at once), starting like an MPEG-4 file; returns their CRC-32. */
    private fun writeHuge(file: java.io.File, size: Long): Long {
        val crc = java.util.zip.CRC32()
        val chunk = bigM4a(1024 * 1024, 11)
        file.parentFile.mkdirs()
        file.outputStream().buffered().use { out ->
            var left = size
            while (left > 0) {
                val n = minOf(left, chunk.size.toLong()).toInt()
                out.write(chunk, 0, n)
                crc.update(chunk, 0, n)
                left -= n
            }
        }
        return crc.value
    }

    private fun crcOf(file: java.io.File): Long {
        val crc = java.util.zip.CRC32()
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; crc.update(buffer, 0, n) }
        }
        return crc.value
    }

    @Test
    fun `a backup bigger than the memory of the test JVM is written and read back`() = runBlocking {
        val size = 700L * 1024 * 1024 // more than the heap of a Gradle test worker (512 MB): an in-memory backup would fail here
        val tmp = java.nio.file.Files.createTempDirectory("notio-huge").toFile()
        org.junit.Assume.assumeTrue("needs about 2.5 GB of free disk", tmp.usableSpace > 3L * 1024 * 1024 * 1024)
        try {
            val a = TransferEnv()
            val f = a.folder("F")
            val note = a.note(f.id, "Huge", NoteType.AUDIO)
            val key = AudioDocument.fileKey(note.id, "c1")
            val crc = writeHuge(a.audio.fileOf(key)!!, size)
            a.env.notes.updateExtras(note.id, AudioDocument(clips = listOf(AudioClip(id = "c1", name = "huge", comment = "", createdAt = 1))).encode())

            val backup = java.io.File(tmp, "backup.zip")
            val report = backup.outputStream().buffered().use { runBlocking { a.backup.export(it) } }
            assertEquals(1, report.filesWritten)

            val b = TransferEnv()
            val result = b.backup.importFrom({ backup.inputStream() }, ImportMode.REPLACE)

            assertEquals(1, result.filesRestored)
            val restored = b.audio.fileIfExists(key)!!
            assertEquals(size, restored.length())
            assertEquals(crc, crcOf(restored))
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `readAtMost stops at the limit and small files come through whole`() {
        assertArrayEquals(byteArrayOf(1, 2, 3), ByteArrayInputStream(byteArrayOf(1, 2, 3)).readAtMost(3))
        assertNull(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)).readAtMost(3))
        assertFalse(ByteArrayInputStream(ByteArray(0)).readAtMost(10)!!.isNotEmpty())
    }
}
