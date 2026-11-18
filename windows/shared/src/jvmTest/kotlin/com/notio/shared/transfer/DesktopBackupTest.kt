package com.notio.shared.transfer

import com.notio.shared.data.AttachmentStore
import com.notio.shared.data.NotioDatabase
import com.notio.shared.data.SettingsStore
import com.notio.shared.domain.NoteType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The desktop store and backup service against the Android backup format (AGENT.md D07). */
class DesktopBackupTest {
    private val dirs = ArrayList<File>()

    private inner class Env {
        val dir: File = Files.createTempDirectory("notio-test").toFile().also { dirs += it }
        val db = NotioDatabase.open(File(dir, "notio.db"))
        val files = AttachmentStore(File(dir, "attachments"))
        val settings = SettingsStore(File(dir, "settings.json"))
        val backup = BackupService(db, files, settings)

        fun loadDemo(mode: ImportMode = ImportMode.MERGE): ImportReport {
            val bytes = ByteArrayOutputStream().also { DemoBackup.write(it, NOW) }.toByteArray()
            return backup.importFrom({ ByteArrayInputStream(bytes) }, mode)
        }

        fun exported(password: String? = null): ByteArray = ByteArrayOutputStream().also { backup.export(it, password) }.toByteArray()
    }

    @AfterTest
    fun cleanUp() {
        dirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun demoImportFillsTheDatabase() {
        val env = Env()
        val report = env.loadDemo()
        assertEquals(3, report.stats.foldersAdded)
        assertEquals(8, report.stats.notesAdded)
        assertEquals(1, report.filesRestored)
        val budget = env.db.budgetNodesOf("demo-budget").associateBy { it.id }
        // Recalculated on import (INV-13): food = 10.00 + 2 x 90.00, the root adds the rent.
        assertEquals(19_000, budget.getValue("demo-b-food").cachedSumMinor)
        assertEquals(69_000, budget.getValue("demo-b-root").cachedSumMinor)
        assertEquals(-10_000, budget.getValue("demo-b-rent").cachedRemainderMinor)
        assertEquals(listOf("магазин", "семья"), env.db.tagsOf("demo-shopping").map { it.name })
        assertNotNull(env.files.file("demo-drawing", "drawing.png"))
        assertEquals(2, env.db.budgetLogOf("demo-budget").size)
        assertEquals(1, env.db.remindersOf("demo-meeting").size)
    }

    @Test
    fun exportThenImportElsewhereKeepsEverything() {
        val a = Env()
        // REPLACE: the settings of a backup are applied only by a full restore, as on Android.
        a.loadDemo(ImportMode.REPLACE)
        val bytes = a.exported()
        val b = Env()
        b.backup.importFrom({ ByteArrayInputStream(bytes) }, ImportMode.REPLACE)
        val left = a.db.readAll()
        val right = b.db.readAll()
        assertEquals(left.folders.sortedBy { it.id }, right.folders.sortedBy { it.id })
        assertEquals(left.notes.sortedBy { it.id }, right.notes.sortedBy { it.id })
        assertEquals(left.nodes.sortedBy { it.id }, right.nodes.sortedBy { it.id })
        assertEquals(left.reminders, right.reminders)
        fun names(db: NotioDatabase) = db.tagAssignments().mapValues { (_, tags) -> tags.map { it.name } }
        assertEquals(names(a.db), names(b.db))
        assertEquals(a.db.budgetLog().sortedBy { it.id }, b.db.budgetLog().sortedBy { it.id })
        assertContentEquals(
            a.files.file("demo-drawing", "drawing.png")!!.readBytes(),
            b.files.file("demo-drawing", "drawing.png")!!.readBytes(),
        )
        assertEquals("ru", b.settings.read().languageTag)
    }

    @Test
    fun protectedBackupNeedsItsPassword() {
        val a = Env()
        a.loadDemo()
        val bytes = a.exported(password = "secret-1")
        assertTrue(BackupCrypto.isProtected(bytes.copyOf(BackupCrypto.HEAD_BYTES)))
        val b = Env()
        val required = assertFailsWith<TransferException> { b.backup.importFrom({ ByteArrayInputStream(bytes) }, ImportMode.MERGE) }
        assertEquals(TransferError.PASSWORD_REQUIRED, required.error)
        val wrong = assertFailsWith<TransferException> { b.backup.importFrom({ ByteArrayInputStream(bytes) }, ImportMode.MERGE, "nope") }
        assertEquals(TransferError.WRONG_PASSWORD, wrong.error)
        b.backup.importFrom({ ByteArrayInputStream(bytes) }, ImportMode.MERGE, "secret-1")
        assertEquals(8, b.db.notes().size)
    }

    @Test
    fun mergeTwiceDoesNotDuplicateButNewIdsDoes() {
        val env = Env()
        env.loadDemo()
        val again = env.loadDemo(ImportMode.MERGE)
        assertEquals(0, again.stats.notesAdded)
        assertEquals(8, env.db.notes().size)
        env.loadDemo(ImportMode.NEW_IDS)
        assertEquals(16, env.db.notes().size)
        assertEquals(2, env.db.notes().count { it.type == NoteType.BUDGET })
        // Same-name folders are folded together (D52), so no new folders.
        assertEquals(3, env.db.folders().size)
    }

    private companion object {
        const val NOW = 1_790_000_000_000L
    }
}
