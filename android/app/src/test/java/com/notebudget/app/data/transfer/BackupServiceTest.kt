package com.notebudget.app.data.transfer

import com.notebudget.app.domain.model.DomainResult
import com.notebudget.app.domain.model.Note
import com.notebudget.app.domain.model.NoteType
import com.notebudget.app.domain.model.ReminderDraft
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.descriptors.elementNames
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupServiceTest {

    private fun export(t: TransferEnv): ByteArray = ByteArrayOutputStream().also { runBlocking { t.backup.export(it) } }.toByteArray()

    private suspend fun import(t: TransferEnv, bytes: ByteArray, mode: ImportMode) =
        t.backup.import(ByteArrayInputStream(bytes), mode)

    /** Everything comparable, ids included. */
    private fun snapshot(t: TransferEnv) = t.env.store.let {
        listOf(it.folders.value.sortedBy { f -> f.id }, it.notes.value.sortedBy { n -> n.id },
            it.nodes.value.sortedBy { n -> n.id }, it.reminders.value.sortedBy { r -> r.id })
    }

    /** A phone with a folder, notes of every kind, a featured budget, a reminder and a trashed note. */
    private fun populate(t: TransferEnv): Map<String, Note> = runBlocking {
        val work = t.folder("Work")
        val home = t.folder("Home")
        val text = t.note(work.id, "Text", NoteType.TEXT, "hello")
        val list = t.note(work.id, "List", NoteType.LIST, "- [ ] a\n- [x] b")
        val budget = t.budget(home.id, "Budget")
        t.env.notes.save(budget.copy(colorHex = "#FF0000"))
        t.env.notes.updateExtras(budget.id, """{"totalAtBottom":true}""")
        val drawing = t.note(home.id, "Drawing", NoteType.DRAWING)
        t.drawings.write(drawing.id, t.fakePng(1))
        val audio = t.note(home.id, "Audio", NoteType.AUDIO)
        t.audio.write(audio.id, t.fakeM4a(2))
        val trashed = t.note(work.id, "Trashed", NoteType.TEXT, "gone")
        t.env.notes.delete(trashed.id)
        t.env.folders.setFeatured(home.id, budget.id, 7, com.notebudget.app.domain.model.FeaturedLayout.COMPACT)
        t.env.reminders.create(text.id, ReminderDraft(triggerAt = t.env.clock.now() + 60_000))
        mapOf("text" to text, "list" to list, "budget" to budget, "drawing" to drawing, "audio" to audio)
    }

    @Test
    fun `an export of chosen folders carries only them and what is in them (sync, D226)`() = runBlocking {
        val source = TransferEnv()
        val notes = populate(source)
        val home = source.env.store.folders.value.first { it.name == "Home" }
        val bytes = ByteArrayOutputStream().also { source.backup.export(it, folderIds = setOf(home.id)) }.toByteArray()

        val target = TransferEnv()
        import(target, bytes, ImportMode.MERGE)
        assertEquals(listOf("Home"), target.env.store.folders.value.map { it.name })
        val titles = target.env.store.notes.value.map { it.title }.toSet()
        assertEquals(setOf("Budget", "Drawing", "Audio"), titles)
        assertTrue(target.env.store.reminders.value.isEmpty()) // the reminder belongs to a note of "Work"
        assertTrue(target.env.store.nodes.value.all { n -> n.noteId == notes.getValue("budget").id })
    }

    @Test
    fun `export then replace-import into an empty phone restores everything exactly`() = runBlocking {
        val source = TransferEnv()
        val notes = populate(source)
        val file = export(source)
        val before = snapshot(source)

        val target = TransferEnv()
        target.folder("Existing junk") // will be wiped
        val report = import(target, file, ImportMode.REPLACE)

        assertEquals(before, snapshot(target))
        assertEquals(source.settings.value, target.settings.value)
        assertArrayEquals(source.fakePng(1), target.drawings.read(notes.getValue("drawing").id))
        assertArrayEquals(source.fakeM4a(2), target.audio.read(notes.getValue("audio").id))
        assertEquals(6, report.stats.notesAdded)
        // D365: the reminder record is restored, no system alarm is armed.
        assertEquals(1, target.env.store.reminders.value.size)
        assertTrue(target.env.scheduler.alarms.isEmpty())
    }

    @Test
    fun `the backup holds no secrets and no unexpected settings`() = runBlocking {
        val t = TransferEnv()
        populate(t)
        val zip = export(t)
        val json = BackupCodec.readManifest(ByteArrayInputStream(zip))
        val text = TransferFormat.json.encodeToString(BackupFile.serializer(), json).lowercase()
        for (secret in listOf("password", "hash", "salt", "pattern", "biometric")) {
            assertTrue("backup mentions $secret", secret !in text)
        }
        assertEquals(setOf("languageTag", "theme", "notificationPrivacy", "trashRetentionDays", "customUnits", "customCurrencies", "colorTheme", "fontSize", "folderSort", "noteSort", "backgrounds", "dateTimeFormats", "exportNaming", "stampDisplay", "previewLines", "mediaPreview", "drawingPreview", "cardTint", "commentDisplay", "undoSteps", "footerStamps", "customThemeColor"), SettingsRecord.serializer().descriptor.elementNames.toSet())
    }

    @Test
    fun `replace removes drawings and alarms the backup does not have`() = runBlocking {
        val backupSource = TransferEnv()
        backupSource.folder("Only")
        val file = export(backupSource)

        val phone = TransferEnv()
        val notes = populate(phone)
        assertTrue(phone.env.store.reminders.value.isNotEmpty())
        import(phone, file, ImportMode.REPLACE)

        assertNull(phone.drawings.read(notes.getValue("drawing").id))
        assertNull(phone.audio.read(notes.getValue("audio").id))
        assertTrue(phone.env.store.notes.value.isEmpty())
        assertTrue(phone.env.scheduler.alarms.isEmpty())
    }

    @Test
    fun `merge into a phone that already has the data changes nothing and adds nothing`() = runBlocking {
        val t = TransferEnv()
        populate(t)
        val before = snapshot(t)
        val report = import(t, export(t), ImportMode.MERGE)
        assertEquals(before, snapshot(t))
        assertEquals(0, report.stats.notesAdded)
        assertEquals(6, report.stats.notesSkipped)
    }

    @Test
    fun `merge brings in what is missing and lets a newer remote note win`() = runBlocking {
        val remote = TransferEnv()
        val rf = remote.folder("Work")
        val rn = remote.note(rf.id, "Shared", NoteType.TEXT, "remote text")
        val onlyRemote = remote.note(rf.id, "Only remote", NoteType.TEXT, "x")
        val file = export(remote)

        val local = TransferEnv()
        val lf = local.folder("Work") // same name, different id
        val ln = local.env.notes.create(lf.id, "Shared", NoteType.TEXT, contentText = "local text") as DomainResult.Success
        // Make the local copy the same note (same id) but older than the remote one.
        local.env.store.notes.value = listOf(ln.value.copy(id = rn.id, updatedAt = rn.updatedAt - 1))

        val report = import(local, file, ImportMode.MERGE)

        val byId = local.env.store.notes.value.associateBy { it.id }
        assertEquals("remote text", byId.getValue(rn.id).contentText)
        assertNotNull(byId[onlyRemote.id])
        assertEquals(1, local.env.store.folders.value.size) // "Work" folded, not duplicated
        assertEquals(lf.id, byId.getValue(onlyRemote.id).folderId)
        assertEquals(1, report.stats.notesAdded)
        assertEquals(1, report.stats.notesUpdated)
    }

    @Test
    fun `new ids mode never touches existing data and copies drawings under the new id`() = runBlocking {
        val t = TransferEnv()
        val notes = populate(t)
        val before = t.env.store.notes.value.map { it.id }.toSet()
        import(t, export(t), ImportMode.NEW_IDS)

        val after = t.env.store.notes.value
        assertEquals(12, after.size)
        val added = after.filter { it.id !in before }
        assertEquals(6, added.size)
        val newDrawing = added.single { it.type == NoteType.DRAWING }
        assertArrayEquals(t.fakePng(1), t.drawings.read(newDrawing.id))
        assertArrayEquals(t.fakePng(1), t.drawings.read(notes.getValue("drawing").id)) // original intact
        // Each imported budget has its own nodes, all consistent.
        val newBudget = added.single { it.type == NoteType.BUDGET }
        val nodes = t.nodes(newBudget.id)
        assertEquals(t.nodes(notes.getValue("budget").id).size, nodes.size)
        assertTrue(nodes.none { n -> t.nodes(notes.getValue("budget").id).any { it.id == n.id } })
    }

    @Test
    fun `notes of an unknown kind survive an import and a re-export`() = runBlocking {
        val source = TransferEnv()
        val f = source.folder("F")
        source.env.store.notes.value = listOf(source.note(f.id, "Mind", NoteType.TEXT).copy(type = NoteType("MINDMAP"), extras = """{"nodes":[1,2]}"""))
        val target = TransferEnv()
        import(target, export(source), ImportMode.REPLACE)
        val restored = target.env.store.notes.value.single()
        assertEquals(NoteType("MINDMAP"), restored.type)
        assertEquals("""{"nodes":[1,2]}""", restored.extras)
        // and it can go out again unchanged
        val again = TransferEnv()
        import(again, export(target), ImportMode.REPLACE)
        assertEquals(restored, again.env.store.notes.value.single())
    }

    @Test
    fun `a failing import leaves the data untouched`() = runBlocking {
        val t = TransferEnv()
        populate(t)
        val before = snapshot(t)
        val bad = ByteArrayOutputStream().also {
            BackupCodec.write(it, BackupFile(folders = listOf(FolderRecord("f", "F")), notes = listOf(NoteRecord("../evil", "f", "x"))), emptyMap())
        }.toByteArray()
        try {
            import(t, bad, ImportMode.REPLACE)
            error("should have failed")
        } catch (e: TransferException) {
            assertEquals(TransferError.CORRUPT, e.error)
        }
        assertEquals(before, snapshot(t))
        assertTrue("the reminders of the untouched data are still there", t.env.store.reminders.value.isNotEmpty())
    }

    @Test
    fun `file name carries the date`() {
        val t = TransferEnv()
        assertTrue(t.backup.suggestedFileName().matches(Regex("notebudget-backup-\\d{4}-\\d{2}-\\d{2}\\.json\\.zip")))
    }
}
