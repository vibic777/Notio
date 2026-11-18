package com.notebudget.app.data.transfer

import com.notebudget.app.domain.model.NoteType
import com.notebudget.app.feature.drawing.Point
import com.notebudget.app.feature.drawing.Shape
import com.notebudget.app.feature.drawing.VectorDocument
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The vector layer of a drawing travels with the raster one, and never breaks an import (D84). */
class DrawingLayersTransferTest {
    private val shapes = listOf(
        Shape("r1", "RECT", listOf(Point(10f, 10f), Point(200f, 120f)), fillColor = 0xFF00FF00.toInt()),
        Shape("p1", "PATH", listOf(Point(0f, 0f), Point(30f, 40f), Point(60f, 0f))),
        Shape("f1", "HEXAGON", listOf(Point(1f, 2f))), // a kind this version does not know
    )

    private fun export(t: TransferEnv): ByteArray =
        ByteArrayOutputStream().also { runBlocking { t.backup.export(it) } }.toByteArray()

    private suspend fun replaceImport(t: TransferEnv, bytes: ByteArray) =
        t.backup.import(ByteArrayInputStream(bytes), ImportMode.REPLACE)

    private suspend fun drawing(t: TransferEnv, withVector: Boolean = true) =
        t.note(t.folder("Art").id, "Sketch", NoteType.DRAWING).also {
            t.drawings.write(it.id, t.fakePng(3))
            if (withVector) t.vectors.write(it.id, VectorDocument(shapes = shapes).encode())
        }

    private fun zipEntries(bytes: ByteArray): List<String> {
        val names = ArrayList<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            generateSequence { zip.nextEntry }.forEach { names += it.name }
        }
        return names
    }

    @Test
    fun `a backup carries both layers and restores them exactly`() = runBlocking {
        val source = TransferEnv()
        val note = drawing(source)
        val file = export(source)
        assertTrue(zipEntries(file).any { it.endsWith(DrawingTransferHandler.VECTOR_FILE) })

        val target = TransferEnv()
        replaceImport(target, file)
        assertArrayEquals(source.fakePng(3), target.drawings.read(note.id))
        val restored = VectorDocument.decode(target.vectors.read(note.id)!!)!!
        assertEquals(shapes, restored.shapes) // the unknown HEXAGON included
    }

    @Test
    fun `a backup of a raster-only drawing (made before the vector layer) restores as raster-only`() = runBlocking {
        val source = TransferEnv()
        val note = drawing(source, withVector = false)
        val file = export(source)
        assertTrue(zipEntries(file).none { it.endsWith(DrawingTransferHandler.VECTOR_FILE) })

        val target = TransferEnv()
        replaceImport(target, file)
        assertArrayEquals(source.fakePng(3), target.drawings.read(note.id))
        assertNull(target.vectors.read(note.id))
    }

    @Test
    fun `a damaged vector layer in a backup is skipped and does not stop the import`() = runBlocking {
        val source = TransferEnv()
        val note = drawing(source, withVector = false)
        source.vectors.write(note.id, "{ this is not json".toByteArray())
        val file = export(source)

        val target = TransferEnv()
        replaceImport(target, file)
        assertArrayEquals(source.fakePng(3), target.drawings.read(note.id))
        assertNull(target.vectors.read(note.id))
    }

    @Test
    fun `replace wipes the vector layers of the old data`() = runBlocking {
        val source = TransferEnv()
        drawing(source, withVector = false)
        val file = export(source)

        val target = TransferEnv()
        val old = drawing(target) // has a vector layer that the backup does not
        replaceImport(target, file)
        assertNull(target.vectors.read(old.id))
    }

    @Test
    fun `a drawing exports as layers and imports back with editable shapes`() = runBlocking {
        val t = TransferEnv()
        val note = drawing(t)
        val layers = t.files.formatsFor(note).single { it.key == StructuredNoteFile.KEY }
        val exported = t.files.export(note, layers)
        assertEquals("Sketch.json", exported.fileName)

        val target = t.folder("Inbox")
        val imported = t.files.import(exported.fileName, exported.bytes, target.id)
        assertEquals(NoteType.DRAWING, imported.type)
        assertTrue(imported.id != note.id) // INV-12: always a new note
        assertArrayEquals(t.fakePng(3), t.drawings.read(imported.id))
        assertEquals(shapes, VectorDocument.decode(t.vectors.read(imported.id)!!)!!.shapes)
    }

    @Test
    fun `a layers file with only shapes imports as a drawing without a raster layer`() = runBlocking {
        val t = TransferEnv()
        val note = drawing(t, withVector = true)
        t.drawings.delete(note.id)
        val exported = t.files.export(note, t.files.formatsFor(note).single { it.key == StructuredNoteFile.KEY })
        val imported = t.files.import(exported.fileName, exported.bytes, t.folder("Inbox").id)
        assertNull(t.drawings.read(imported.id))
        assertNotNull(t.vectors.read(imported.id))
    }

    @Test
    fun `png export of a drawing with nothing on it is refused, not an empty file`() = runBlocking {
        val t = TransferEnv()
        val blank = t.note(t.folder("Art").id, "Blank", NoteType.DRAWING)
        val png = t.files.formatsFor(blank).single { it.key == "png" }
        try {
            t.files.export(blank, png)
            fail("expected TransferException")
        } catch (e: TransferException) {
            assertEquals(TransferError.CORRUPT, e.error)
        }
    }

    @Test
    fun `png export of a vector-only drawing works because the layers are flattened`() = runBlocking {
        val t = TransferEnv()
        val note = drawing(t, withVector = true)
        t.drawings.delete(note.id)
        val png = t.files.export(note, t.files.formatsFor(note).single { it.key == "png" })
        assertTrue(png.bytes.isNotEmpty())
    }
}
