package com.notebudget.app.data.transfer

import com.notebudget.app.domain.model.BudgetNode
import com.notebudget.app.domain.model.BudgetNodeKind
import com.notebudget.app.domain.model.DomainResult
import com.notebudget.app.domain.model.Note
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The quantity of a budget item (D94) travels through backups and single-note files, and old files still load. */
class BudgetQuantityTransferTest {

    /** A budget with a group "Food" holding "Bread" (10.00 / 25.00, times [quantity]) and a leaf "Rent". */
    private suspend fun quantityBudget(t: TransferEnv, quantity: Int): Pair<Note, BudgetNode> {
        val note = t.budget(t.folder("F").id, "Trip")
        val bread = t.nodes(note.id).single { it.title == "Bread" }
        assertTrue(t.env.budget.updateLeaf(note.id, bread.id, 1_000, 2_500, quantity) is DomainResult.Success)
        return note to t.nodes(note.id).single { it.title == "Bread" }
    }

    private fun exportBackup(t: TransferEnv): ByteArray =
        ByteArrayOutputStream().also { runBlocking { t.backup.export(it) } }.toByteArray()

    @Test
    fun `a full backup keeps the quantity and the totals that depend on it`() = runBlocking {
        val source = TransferEnv()
        val (note, bread) = quantityBudget(source, 3)
        assertEquals(3_000, bread.cachedSumMinor)
        val file = exportBackup(source)

        val target = TransferEnv()
        target.backup.import(ByteArrayInputStream(file), ImportMode.REPLACE)

        val restored = target.nodes(note.id).single { it.title == "Bread" }
        assertEquals(3, restored.quantity)
        assertEquals(1_000, restored.manualSumMinor) // still the price of ONE piece
        assertEquals(3_000, restored.cachedSumMinor)
        assertEquals(7_500, restored.cachedGoalMinor)
        // The root includes the three pieces (Rent adds 50 000 sum / 40 000 goal).
        val root = target.nodes(note.id).single { it.parentId == null }
        assertEquals(53_000, root.cachedSumMinor)
        assertEquals(47_500, root.cachedGoalMinor)
    }

    @Test
    fun `a single-note file with structure keeps the quantity`() = runBlocking {
        val t = TransferEnv()
        val (note, _) = quantityBudget(t, 4)
        val structure = t.files.formatsFor(note).first { it.key == "json" }
        val file = t.files.export(note, structure)

        val imported = t.files.import(file.fileName, file.bytes, t.folder("Inbox").id)
        val bread = t.nodes(imported.id).single { it.title == "Bread" }
        assertEquals(4, bread.quantity)
        assertEquals(4_000, bread.cachedSumMinor)
    }

    @Test
    fun `files made before the quantity existed load with one piece each`() = runBlocking {
        val t = TransferEnv()
        val note = t.budget(t.folder("F").id, "Old")
        val structure = t.files.formatsFor(note).first { it.key == "json" }
        val file = t.files.export(note, structure)
        // Strip the field the way an older app's file would not have had it.
        val text = file.bytes.toString(Charsets.UTF_8).replace(Regex(""",?\s*"quantity"\s*:\s*\d+"""), "")
        assertTrue(!text.contains("quantity"))

        val imported = t.files.import(file.fileName, text.toByteArray(), t.folder("Inbox").id)
        val nodes = t.nodes(imported.id)
        assertTrue(nodes.all { it.quantity == 1 })
        assertEquals(nodes.single { it.title == "Bread" }.manualSumMinor, nodes.single { it.title == "Bread" }.cachedSumMinor)
    }

    @Test
    fun `nonsense quantities in a file fall back to one piece`() = runBlocking {
        val t = TransferEnv()
        val note = t.budget(t.folder("F").id, "Bad")
        val structure = t.files.formatsFor(note).first { it.key == "json" }
        val file = t.files.export(note, structure)
        // Force a bad value into every node record.
        val text = file.bytes.toString(Charsets.UTF_8).replace(Regex(""""kind"\s*:\s*"LEAF""""), """"quantity":-5,"kind":"LEAF"""")
        assertTrue(text.contains("-5"))

        val imported = t.files.import(file.fileName, text.toByteArray(), t.folder("Inbox").id)
        assertTrue(t.nodes(imported.id).all { it.quantity == 1 })
    }

    @Test
    fun `the text snapshot shows the quantity next to the name`() = runBlocking {
        val t = TransferEnv()
        val (note, _) = quantityBudget(t, 3)
        val snapshot = t.files.export(note, t.files.formatsFor(note).first { it.key == "snapshot" })
        val text = snapshot.bytes.toString(Charsets.UTF_8)
        assertTrue(text, text.contains("- Bread x3: sum 30.00 / goal 75.00"))
        // A single piece is written without a multiplier.
        assertTrue(text, text.contains("- Rent: sum 500.00"))
    }
}
