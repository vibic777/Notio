package com.notio.shared.transfer

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Compatibility of the file formats. The GOLDEN_* strings are files as version 1 wrote them:
 * they must load in every later version. Never edit them to make a test pass; if a format
 * change breaks one of these tests, the change is wrong (or needs a new format version).
 */
class BackupCodecTest {

    private fun zip(manifest: String?, extra: Map<String, ByteArray> = emptyMap()): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            if (manifest != null) {
                z.putNextEntry(ZipEntry("backup.json")); z.write(manifest.toByteArray()); z.closeEntry()
            }
            extra.forEach { (name, bytes) -> z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() }
        }
        return out.toByteArray()
    }

    private fun expectError(error: TransferError, block: () -> Unit) {
        try {
            block(); fail("expected $error")
        } catch (e: TransferException) {
            assertEquals(error, e.error)
        }
    }

    @Test
    fun `write then read gives the same data and attachments`() {
        val file = BackupFile(
            createdAt = 5, appVersion = "1.2",
            folders = listOf(FolderRecord("f", "Work")),
            notes = listOf(NoteRecord("n", "f", "Title", "DRAWING")),
            settings = SettingsRecord("de", "LIGHT", "FULL"),
        )
        val out = ByteArrayOutputStream()
        BackupCodec.write(out, file, mapOf(BackupCodec.attachmentPath("n", "drawing.png") to byteArrayOf(1, 2, 3)))
        assertEquals(file, BackupCodec.readManifest(ByteArrayInputStream(out.toByteArray())))
        BackupCodec.AttachmentReader(ByteArrayInputStream(out.toByteArray())).use { reader ->
            val only = reader.next()!!
            assertEquals("n", only.noteId)
            assertEquals("drawing.png", only.name)
            assertArrayEquals(byteArrayOf(1, 2, 3), only.stream.readBytes())
            assertTrue(reader.next() == null)
        }
    }

    @Test
    fun `a manifest bigger than the memory limit is refused with its own reason, a smaller one opens`() {
        val file = BackupFile(notes = listOf(NoteRecord("n", "f", "Big", "TEXT", contentText = "x".repeat(200_000))))
        val bytes = ByteArrayOutputStream().also { BackupCodec.write(it, file, emptyMap()) }.toByteArray()
        expectError(TransferError.TOO_LARGE) { BackupCodec.readManifest(ByteArrayInputStream(bytes), maxBytes = 100_000) }
        assertEquals(file, BackupCodec.readManifest(ByteArrayInputStream(bytes), maxBytes = 300_000))
    }

    @Test
    fun `the manifest limit follows the memory, between 32 and 256 MB`() {
        val mb = 1024L * 1024
        assertEquals(32 * mb, BackupCodec.manifestLimit(64 * mb))
        assertEquals(64 * mb, BackupCodec.manifestLimit(256 * mb))
        assertEquals(128 * mb, BackupCodec.manifestLimit(512 * mb))
        assertEquals(256 * mb, BackupCodec.manifestLimit(4096 * mb))
    }

    @Test
    fun `a backup written by version 1 still loads`() {
        val file = BackupCodec.readManifest(ByteArrayInputStream(zip(GOLDEN_V1)))
        assertEquals(1, file.formatVersion)
        assertEquals("Groceries", file.notes.single { it.id == "n1" }.title)
        assertEquals("BUDGET", file.notes.single { it.id == "n2" }.type)
        assertEquals(2, file.budgetNodes.size)
        assertEquals(50_00L, file.budgetNodes.first { it.id == "b2" }.manualSumMinor)
        assertEquals("ru", file.settings?.languageTag)
    }

    @Test
    fun `a file from a newer version with unknown fields and kinds still loads`() {
        val file = BackupCodec.readManifest(ByteArrayInputStream(zip(FUTURE_MINOR)))
        val note = file.notes.single()
        assertEquals("MINDMAP", note.type) // an unknown kind is kept, not rejected
        assertEquals("Mind", note.title)
        assertEquals(2, file.formatVersion)
    }

    @Test
    fun `a file that needs a newer reader is refused`() {
        expectError(TransferError.UNSUPPORTED_VERSION) {
            BackupCodec.readManifest(ByteArrayInputStream(zip("""{"format":"notebudget-backup","formatVersion":9,"minReaderVersion":9}""")))
        }
    }

    @Test
    fun `garbage is reported, never crashes`() {
        expectError(TransferError.NOT_A_BACKUP) { BackupCodec.readManifest(ByteArrayInputStream("hello".toByteArray())) }
        expectError(TransferError.NOT_A_BACKUP) { BackupCodec.readManifest(ByteArrayInputStream(zip(null))) }
        expectError(TransferError.NOT_A_BACKUP) { BackupCodec.readManifest(ByteArrayInputStream(zip("{not json"))) }
        expectError(TransferError.NOT_A_BACKUP) { BackupCodec.readManifest(ByteArrayInputStream(zip("""{"format":"other"}"""))) }
    }

    // `structured note files follow the same rules`: single-note files (StructuredNoteFile) are not ported yet.

    private companion object {
        /** Exactly the shape version 1 writes (see [BackupFile]); do not edit. */
        const val GOLDEN_V1 = """{"format":"notebudget-backup","formatVersion":1,"minReaderVersion":1,"createdAt":1789900000000,"appVersion":"0.1.0",
"folders":[{"id":"f1","name":"My Notes","colorHex":"#3366FF","styleId":"SOFT","icon":null,"sortOrder":0,"featuredNoteId":"n2","featuredMetricsMask":7,"featuredLayout":"COMPACT","createdAt":1,"updatedAt":2,"deletedAt":null}],
"notes":[{"id":"n1","folderId":"f1","title":"Groceries","type":"LIST","colorHex":null,"isPinned":true,"isFavorite":false,"isTitleBold":false,"contentText":"- [ ] milk\n- [x] bread","extras":null,"createdAt":1,"updatedAt":2,"deletedAt":null},
{"id":"n2","folderId":"f1","title":"Trip","type":"BUDGET","colorHex":"#FF0000","isPinned":false,"isFavorite":true,"isTitleBold":true,"contentText":null,"extras":"{\"totalAtBottom\":true}","createdAt":1,"updatedAt":3,"deletedAt":null}],
"budgetNodes":[{"id":"b1","noteId":"n2","parentId":null,"title":"Trip","kind":"GROUP","manualSumMinor":0,"manualGoalMinor":0,"comment":null,"cachedSumMinor":5000,"cachedGoalMinor":9000,"cachedRemainderMinor":4000,"isExpanded":true,"sortOrder":0,"createdAt":1,"updatedAt":1},
{"id":"b2","noteId":"n2","parentId":"b1","title":"Hotel","kind":"LEAF","manualSumMinor":5000,"manualGoalMinor":9000,"comment":"deposit paid","cachedSumMinor":5000,"cachedGoalMinor":9000,"cachedRemainderMinor":4000,"isExpanded":true,"sortOrder":0,"createdAt":1,"updatedAt":1}],
"reminders":[{"id":"r1","noteId":"n1","type":"ONE_TIME","triggerAt":1790000000000,"isEnabled":true,"status":"SCHEDULED","soundEnabled":true,"soundUri":null,"vibrationEnabled":true,"popupEnabled":true,"requiresConfirmation":false,"repeatUntilConfirmed":false,"repeatIntervalMinutes":null,"createdAt":1,"updatedAt":1}],
"settings":{"languageTag":"ru","theme":"DARK","notificationPrivacy":"GENERIC"}}"""

        /** A later version that only ADDED things: extra fields, a new note kind, a bumped (compatible) version. */
        const val FUTURE_MINOR = """{"format":"notebudget-backup","formatVersion":2,"minReaderVersion":1,"somethingNew":{"a":1},
"folders":[{"id":"f1","name":"F","colour":"blue"}],
"notes":[{"id":"m1","folderId":"f1","title":"Mind","type":"MINDMAP","payloadV2":[1,2,3]}]}"""
    }
}
