package com.notebudget.app.data.transfer

import com.notebudget.app.domain.model.DomainResult
import com.notebudget.app.domain.model.Note
import com.notebudget.app.domain.model.NoteType
import com.notebudget.app.feature.drawing.Point
import com.notebudget.app.feature.drawing.Shape
import com.notebudget.app.feature.drawing.VectorDocument
import com.notebudget.app.feature.drawing.VectorLayer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The blueprint kind (D98): vector shapes on layers, exported through the drawing code without a raster sheet. */
class BlueprintTransferTest {
    private val blueprint = NoteType("BLUEPRINT")

    private class Setup {
        val t = TransferEnv()
        val flattened = mutableListOf<Pair<ByteArray?, List<Shape>>>()
        val drawings = DrawingTransferHandler(
            t.drawings, t.vectors,
            flatten = { png, shapes, _, _, _ -> flattened += png to shapes; byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3, 4, 5, 6) },
            toJpeg = { it },
        )
        val blueprints = BlueprintTransferHandler(drawings)
        val lookup = object : NoteHandlerLookup {
            override fun handlerFor(type: NoteType) = when (type.id) {
                "DRAWING" -> drawings
                "BLUEPRINT" -> blueprints
                else -> null
            }
            override val allHandlers get() = listOf<NoteTransferHandler>(drawings, blueprints)
        }
        val files = NoteFileService(t.env.noteRepo, t.env.folderRepo, lookup, t.env.clock, t.env.ids)

        suspend fun blueprintNote(doc: VectorDocument): Note {
            val folder = t.folder("F")
            val note = (t.env.notes.create(folder.id, "Plan", NoteType("BLUEPRINT")) as DomainResult.Success).value
            t.vectors.write(note.id, doc.encode())
            return note
        }
    }

    private fun rect(id: String, layer: String) = Shape(id, "RECT", listOf(Point(0f, 0f), Point(10f, 10f)), layerId = layer)

    private val layered = VectorDocument(
        shapes = listOf(rect("top", "l2"), rect("bottom", "l1"), rect("hidden", "l3")),
        layers = listOf(VectorLayer("l1", "Floor"), VectorLayer("l2", "Furniture"), VectorLayer("l3", "Notes", visible = false)),
    )

    @Test
    fun `the picture of a blueprint holds the visible layers only, bottom layer first, and has no raster`() = runBlocking {
        val s = Setup()
        val note = s.blueprintNote(layered)
        val png = s.files.formatsFor(note).first { it.key == "png" }
        val exported = s.files.export(note, png)

        assertEquals("Plan.png", exported.fileName)
        val (raster, shapes) = s.flattened.single()
        assertNull(raster)
        assertEquals(listOf("bottom", "top"), shapes.map { it.id }) // "hidden" is on a hidden layer
    }

    @Test
    fun `a blueprint with nothing drawn cannot be exported as a picture and says so`() {
        val s = Setup()
        val error = runCatching { runBlocking { s.files.export(s.blueprintNote(VectorDocument()), s.files.formatsFor(NoteType("BLUEPRINT").let { Note("x", "f", "t", it, null, false, false, false, null, 0, 0) }).first { it.key == "png" }) } }
            .exceptionOrNull()
        assertNotNull(error)
        assertEquals(TransferError.CORRUPT, (error as TransferException).error)
    }

    @Test
    fun `the layers file keeps every layer, hidden ones included, and comes back as a blueprint`() = runBlocking {
        val s = Setup()
        val note = s.blueprintNote(layered)
        val layersFormat = s.files.formatsFor(note).first { it.key == StructuredNoteFile.KEY }
        val exported = s.files.export(note, layersFormat)

        val target = s.t.folder("Target")
        val imported = s.files.import(exported.fileName, exported.bytes, target.id)

        assertEquals(blueprint, imported.type)
        val restored = VectorDocument.decode(s.t.vectors.read(imported.id)!!)!!
        assertEquals(layered, restored)
    }

    @Test
    fun `a png file stays a drawing, the blueprint handler does not claim it`() = runBlocking {
        val s = Setup()
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10, 0, 0, 0, 13)
        assertNull(s.blueprints.importFile("plan.png", png))
        val folder = s.t.folder("F")
        val imported = s.files.import("plan.png", png, folder.id)
        assertEquals(NoteType.DRAWING, imported.type)
        assertArrayEquals(png, s.t.drawings.read(imported.id))
    }

    @Test
    fun `deleting a blueprint for good removes its file`() = runBlocking {
        val s = Setup()
        val note = s.blueprintNote(layered)
        s.blueprints.deleteAttachments(note)
        assertNull(s.t.vectors.read(note.id))
    }
}
