package com.notio.shared.transfer

import com.notio.shared.domain.BudgetNode
import com.notio.shared.domain.BudgetNodeKind
import com.notio.shared.domain.Folder
import com.notio.shared.domain.Note
import com.notio.shared.domain.NoteType
import com.notio.shared.domain.FeaturedLayout
import com.notio.shared.SequentialIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ImportPlannerTest {
    private fun folder(id: String, name: String, updated: Long = 1, order: Int = 0, featured: String? = null, deleted: Long? = null) =
        Folder(id, name, "#3366FF", "SOFT", null, order, featured, if (featured != null) 7 else 0,
            FeaturedLayout.COMPACT, 1, updated, deleted)

    private fun note(id: String, folderId: String, title: String = id, updated: Long = 1, type: NoteType = NoteType.TEXT, deleted: Long? = null) =
        Note(id, folderId, title, type, null, false, false, false, "c-$id", 1, updated, deleted)

    private fun node(id: String, noteId: String, parent: String?, kind: BudgetNodeKind, sum: Long = 0, goal: Long = 0, order: Int = 0) =
        BudgetNode(id, noteId, parent, id, kind, sum, goal, null, 999, 999, 999, true, order, 1, 1)

    private val empty = BackupData(emptyList(), emptyList(), emptyList(), emptyList())

    private fun expectError(error: TransferError, block: () -> Unit) {
        try {
            block()
            fail("expected $error")
        } catch (e: TransferException) {
            assertEquals(error, e.error)
        }
    }

    @Test
    fun `replace loads everything as is and clears first`() {
        val incoming = BackupData(listOf(folder("f1", "A"), folder("f2", "B", deleted = 5)), listOf(note("n1", "f1")), emptyList(), emptyList())
        val current = BackupData(listOf(folder("old", "Old")), listOf(note("o", "old")), emptyList(), emptyList())
        val planned = ImportPlanner.plan(current, incoming, ImportMode.REPLACE)
        assertTrue(planned.plan.clearAll)
        assertEquals(listOf("f1", "f2"), planned.plan.folders.map { it.id })
        assertEquals(listOf("n1"), planned.plan.notes.map { it.id })
    }

    @Test
    fun `replace refuses a backup without an active folder`() {
        val incoming = BackupData(listOf(folder("f", "Trashed", deleted = 1)), emptyList(), emptyList(), emptyList())
        expectError(TransferError.NO_FOLDERS) { ImportPlanner.plan(empty, incoming, ImportMode.REPLACE) }
    }

    @Test
    fun `merge adds new notes, keeps the newer version and skips the older`() {
        val current = BackupData(
            listOf(folder("f", "Home")),
            listOf(note("same-old", "f", "local", updated = 10), note("same-new", "f", "local", updated = 50)),
            emptyList(), emptyList(),
        )
        val incoming = BackupData(
            listOf(folder("f", "Home")),
            listOf(
                note("same-old", "f", "remote", updated = 20), // remote newer -> update
                note("same-new", "f", "remote", updated = 20), // remote older -> skip
                note("brand-new", "f", "fresh", updated = 5),
            ),
            emptyList(), emptyList(),
        )
        val planned = ImportPlanner.plan(current, incoming, ImportMode.MERGE)
        assertEquals(setOf("same-old", "brand-new"), planned.plan.notes.map { it.id }.toSet())
        assertEquals("remote", planned.plan.notes.first { it.id == "same-old" }.title)
        assertEquals(ImportStats(foldersAdded = 0, foldersMerged = 1, notesAdded = 1, notesUpdated = 1, notesSkipped = 1), planned.stats)
        assertTrue("skipped notes are not mapped", "same-new" !in planned.noteIds)
        assertTrue(!planned.plan.clearAll)
    }

    @Test
    fun `merge folds a folder with the same name but another id into the existing one`() {
        val current = BackupData(listOf(folder("local", "Work")), emptyList(), emptyList(), emptyList())
        val incoming = BackupData(listOf(folder("remote", "work")), listOf(note("n", "remote")), emptyList(), emptyList())
        val planned = ImportPlanner.plan(current, incoming, ImportMode.MERGE)
        assertTrue(planned.plan.folders.isEmpty())
        assertEquals("local", planned.plan.notes.single().folderId)
        assertEquals(1, planned.stats.foldersMerged)
    }

    @Test
    fun `new ids mode gives everything fresh ids and keeps the relations`() {
        val incoming = BackupData(
            listOf(folder("f", "Fresh", featured = "n")),
            listOf(note("n", "f", type = NoteType.BUDGET)),
            listOf(node("r", "n", null, BudgetNodeKind.GROUP), node("leaf", "n", "r", BudgetNodeKind.LEAF, 100, 200)),
            emptyList(),
        )
        val current = BackupData(listOf(folder("f", "Existing")), listOf(note("n", "f")), emptyList(), emptyList())
        val planned = ImportPlanner.plan(current, incoming, ImportMode.NEW_IDS, SequentialIds())
        val newNote = planned.plan.notes.single()
        val newFolder = planned.plan.folders.single()
        assertTrue(newNote.id != "n" && newFolder.id != "f")
        assertEquals(newFolder.id, newNote.folderId)
        assertEquals(newNote.id, newFolder.featuredNoteId)
        val nodes = planned.plan.nodes
        assertEquals(2, nodes.size)
        assertTrue(nodes.all { it.id != "r" && it.id != "leaf" && it.noteId == newNote.id })
        val root = nodes.single { it.parentId == null }
        assertEquals(root.id, nodes.single { it.parentId != null }.parentId)
        assertEquals(newNote.id, planned.noteIds["n"])
    }

    @Test
    fun `budget totals are recomputed from the leaves, never trusted from the file`() {
        val incoming = BackupData(
            listOf(folder("f", "F")),
            listOf(note("n", "f", type = NoteType.BUDGET)),
            listOf(
                node("r", "n", null, BudgetNodeKind.GROUP),
                node("g", "n", "r", BudgetNodeKind.GROUP),
                node("a", "n", "g", BudgetNodeKind.LEAF, 300, 500),
                node("b", "n", "g", BudgetNodeKind.LEAF, 200, 100),
            ),
            emptyList(),
        )
        val nodes = ImportPlanner.plan(empty, incoming, ImportMode.REPLACE).plan.nodes.associateBy { it.id }
        assertEquals(500L, nodes.getValue("g").cachedSumMinor)
        assertEquals(600L, nodes.getValue("r").cachedGoalMinor)
        assertEquals(100L, nodes.getValue("r").cachedRemainderMinor)
    }

    @Test
    fun `a budget note without nodes gets a root, orphans and nodes below a leaf are dropped`() {
        val incoming = BackupData(
            listOf(folder("f", "F")),
            listOf(note("empty", "f", type = NoteType.BUDGET), note("odd", "f", type = NoteType.BUDGET)),
            listOf(
                node("r", "odd", null, BudgetNodeKind.GROUP),
                node("leaf", "odd", "r", BudgetNodeKind.LEAF),
                node("below", "odd", "leaf", BudgetNodeKind.LEAF),
                node("orphan", "odd", "missing", BudgetNodeKind.LEAF),
            ),
            emptyList(),
        )
        val plan = ImportPlanner.plan(empty, incoming, ImportMode.REPLACE).plan
        assertEquals(1, plan.nodes.count { it.noteId == "empty" && it.parentId == null })
        assertEquals(setOf("r", "leaf"), plan.nodes.filter { it.noteId == "odd" }.map { it.id }.toSet())
    }

    @Test
    fun `two roots or an unsafe id make the file corrupt`() {
        val twoRoots = BackupData(
            listOf(folder("f", "F")), listOf(note("n", "f", type = NoteType.BUDGET)),
            listOf(node("r1", "n", null, BudgetNodeKind.GROUP), node("r2", "n", null, BudgetNodeKind.GROUP)), emptyList(),
        )
        expectError(TransferError.CORRUPT) { ImportPlanner.plan(empty, twoRoots, ImportMode.MERGE) }

        val evil = BackupData(listOf(folder("f", "F")), listOf(note("../../etc/passwd", "f")), emptyList(), emptyList())
        expectError(TransferError.CORRUPT) { ImportPlanner.plan(empty, evil, ImportMode.MERGE) }

        val duplicate = BackupData(listOf(folder("f", "F")), listOf(note("n", "f"), note("n", "f")), emptyList(), emptyList())
        expectError(TransferError.CORRUPT) { ImportPlanner.plan(empty, duplicate, ImportMode.REPLACE) }
    }

    @Test
    fun `a featured link to a missing, trashed or foreign note is cleared`() {
        val incoming = BackupData(
            listOf(folder("f1", "A", featured = "gone"), folder("f2", "B", featured = "n2"), folder("f3", "C", featured = "n3")),
            listOf(note("n2", "f1"), note("n3", "f3")),
            emptyList(), emptyList(),
        )
        val folders = ImportPlanner.plan(empty, incoming, ImportMode.REPLACE).plan.folders.associateBy { it.id }
        assertNull(folders.getValue("f1").featuredNoteId)
        assertNull(folders.getValue("f2").featuredNoteId) // note n2 lives in another folder
        assertEquals("n3", folders.getValue("f3").featuredNoteId)
        assertEquals(0, folders.getValue("f1").featuredMetricsMask)
    }

    @Test
    fun `an orphan note goes to a fallback folder instead of breaking the one-folder rule`() {
        val incoming = BackupData(listOf(folder("f", "F")), listOf(note("n", "nowhere")), emptyList(), emptyList())
        val planned = ImportPlanner.plan(empty, incoming, ImportMode.MERGE)
        assertEquals("f", planned.plan.notes.single().folderId)
    }

    @Test
    fun `a replacement of chosen folders takes what comes, trashes the rest of them and leaves the other folders`() {
        val current = BackupData(
            listOf(folder("home", "Home", featured = "only-here"), folder("work", "Work")),
            listOf(
                note("both", "home", "local", updated = 50), note("only-here", "home"), note("trashed-here", "home", deleted = 3),
                note("w", "work"),
            ),
            emptyList(), emptyList(),
        )
        // The other device has the folder under another id; its version of the note is older and still wins.
        val incoming = BackupData(
            listOf(folder("their-home", "home")),
            listOf(note("both", "their-home", "theirs", updated = 10), note("new", "their-home")),
            emptyList(), emptyList(),
        )
        val planned = ImportPlanner.planFolders(current, incoming, setOf("home", "their-home"), now = 99)
        val notes = planned.plan.notes.associateBy { it.id }
        assertTrue(!planned.plan.clearAll)
        assertEquals(setOf("both", "new", "only-here"), notes.keys)
        assertEquals("theirs", notes.getValue("both").title)
        assertEquals("home", notes.getValue("new").folderId)
        assertEquals(99L, notes.getValue("only-here").deletedAt)
        // The folder of this device stays, without the featured note that went to the trash (INV-3).
        assertNull(planned.plan.folders.single { it.id == "home" }.featuredNoteId)
        assertEquals(ImportStats(foldersAdded = 0, foldersMerged = 1, notesAdded = 1, notesUpdated = 1), planned.stats)
    }

    @Test
    fun `a replacement of chosen folders is refused when no folder comes`() {
        val current = BackupData(listOf(folder("home", "Home")), listOf(note("n", "home")), emptyList(), emptyList())
        expectError(TransferError.NO_FOLDERS) { ImportPlanner.planFolders(current, empty, setOf("home"), now = 9) }
    }
}
