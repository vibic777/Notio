package com.notio.shared.transfer

import com.notio.shared.SequentialIds
import com.notio.shared.data.AttachmentStore
import com.notio.shared.data.NotioDatabase
import com.notio.shared.data.NotioEditor
import com.notio.shared.domain.BudgetNodeKind
import com.notio.shared.domain.Clock
import com.notio.shared.domain.IdGenerator
import com.notio.shared.domain.NoteType
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper

/** Single-note export, import and cloning (Android NoteFileService, Handlers, NoteCloneService; AGENT.md D27). */
class NoteFilesTest {
    private val dir = Files.createTempDirectory("notio-files").toFile()
    private var now = 5_000L
    private var counter = 0
    private val db = NotioDatabase.inMemory()
    private val files = AttachmentStore(dir)
    private val editor = NotioEditor(db, files, Clock { now }, SequentialIds())
    private val service = NoteFileService(db, files, "test", PdfBoxWriter(::roboto), clock = Clock { now }, ids = IdGenerator { "x-" + (++counter) })
    private val folder = editor.createFolder("Дом", "#3366FF", "SOFT")

    private fun roboto(): Pair<ByteArray, ByteArray> {
        val fonts = File("../desktopApp/src/jvmMain/resources/font")
        return File(fonts, "roboto_regular.ttf").readBytes() to File(fonts, "roboto_bold.ttf").readBytes()
    }

    @AfterTest
    fun cleanUp() {
        db.close()
        dir.deleteRecursively()
    }

    @Test
    fun textNoteGoesOutAsMarkdownPlainTextAndPdfWithCyrillic() = runBlocking {
        val note = editor.createNote(folder.id, "Заметка", NoteType.TEXT, "# Список\n- **молоко** и *хлеб*")
        assertEquals(listOf("md", "txt", "pdf"), service.formatsFor(note).map { it.extension })
        assertEquals("# Список\n- **молоко** и *хлеб*", service.export(note, NoteFileService.MARKDOWN).bytes.toString(Charsets.UTF_8))
        val plain = service.export(note, NoteFileService.TXT).bytes.toString(Charsets.UTF_8)
        assertTrue("молоко и хлеб" in plain && "**" !in plain, plain)
        val pdf = service.export(note, NoteFileService.PDF)
        assertEquals("Заметка.pdf", pdf.fileName)
        val text = Loader.loadPDF(pdf.bytes).use { PDFTextStripper().getText(it) }
        assertTrue("Заметка" in text && "молоко и хлеб" in text, text)
    }

    @Test
    fun longTextWrapsOntoSeveralPages() {
        val blocks = PlainText.withTitle("T", List(200) { PdfBlock("Строка номер $it " + "слово ".repeat(30)) })
        val bytes = PdfBoxWriter(::roboto).write(blocks)
        Loader.loadPDF(bytes).use { assertTrue(it.numberOfPages > 5) }
        // Without the font a character outside Latin becomes "?", never an exception.
        Loader.loadPDF(PdfBoxWriter { null }.write(listOf(PdfBlock("Привет 😀 ok")))).use { assertEquals(1, it.numberOfPages) }
    }

    @Test
    fun budgetWithStructureComesBackWithNewIdsAndRecalculatedTotals() = runBlocking {
        val budget = editor.createNote(folder.id, "Отпуск", NoteType.BUDGET)
        val root = db.budgetNodesOf(budget.id).single()
        val leaf = root.copy(id = "leaf", parentId = root.id, title = "Билеты", kind = BudgetNodeKind.LEAF, manualSumMinor = 1500, manualGoalMinor = 5000)
        com.notio.shared.domain.BudgetCalculator.recalculateAll(listOf(root, leaf)).forEach(db::upsertNode)
        db.assignTags(budget.id, listOf("поездка"))
        val file = service.export(db.note(budget.id)!!, NoteFileService.STRUCTURE)
        assertTrue(file.bytes.toString(Charsets.UTF_8).contains("\"поездка\""))

        val imported = service.import(file.fileName, file.bytes, folder.id)
        assertEquals(NoteType.BUDGET, imported.type)
        val nodes = db.budgetNodesOf(imported.id)
        assertEquals(2, nodes.size)
        assertTrue(nodes.none { it.id == "leaf" || it.id == root.id })
        assertEquals(1500, nodes.single { it.parentId == null }.cachedSumMinor)
        assertEquals(listOf("поездка"), db.tagsOf(imported.id).map { it.name })

        val snapshot = service.export(db.note(budget.id)!!, NoteFileService.SNAPSHOT_TXT).bytes.toString(Charsets.UTF_8)
        assertEquals("Отпуск: sum 15.00 / goal 50.00 / remainder 35.00\n  - Билеты: sum 15.00 / goal 50.00 / remainder 35.00", snapshot)
    }

    @Test
    fun foreignFilesBecomeTextDrawingAndAudioNotes() = runBlocking {
        val md = service.import("идеи.md", "\uFEFF# Идеи".toByteArray(), folder.id)
        assertEquals(NoteType.TEXT, md.type)
        assertEquals("идеи", md.title)
        assertEquals("# Идеи", md.contentText)
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10, 0, 0)
        val drawing = service.import("C:\\pics\\кот.png", png, folder.id)
        assertEquals(NoteType.DRAWING, drawing.type)
        assertEquals("кот", drawing.title)
        assertEquals(listOf("drawing.png"), files.list(drawing.id).map { it.name })
        val m4a = ByteArray(20).also { "ftyp".forEachIndexed { i, c -> it[4 + i] = c.code.toByte() } }
        assertEquals(NoteType.AUDIO, service.import("voice.m4a", m4a, folder.id).type)
        assertEquals(TransferError.UNRECOGNIZED_FILE, assertFailsWith<TransferException> { service.import("a.bin", byteArrayOf(0, 1, 2), folder.id) }.error)
        assertEquals(TransferError.FOLDER_NOT_FOUND, assertFailsWith<TransferException> { service.import("a.md", "x".toByteArray(), "nope") }.error)
    }

    @Test
    fun drawingLayersTravelInTheStructuredFile() = runBlocking {
        val note = editor.createNote(folder.id, "Схема", NoteType.DRAWING)
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3, 4, 5, 6)
        files.write(note.id, "drawing.png", png.inputStream())
        files.write(note.id, "vector.json", """{"version":1,"shapes":[]}""".byteInputStream())
        val file = service.export(note, NoteFileService.LAYERS)
        val back = service.import(file.fileName, file.bytes, folder.id)
        assertTrue(files.file(back.id, "drawing.png")!!.readBytes().contentEquals(png))
        assertEquals("""{"version":1,"shapes":[]}""", files.file(back.id, "vector.json")!!.readText())
    }

    @Test
    fun cloneGetsAFreeNameItsOwnTreeFilesAndTags() {
        val budget = editor.createNote(folder.id, "Бюджет", NoteType.BUDGET)
        db.assignTags(budget.id, listOf("дом"))
        files.write(budget.id, "drawing.png", byteArrayOf(1).inputStream())
        val first = service.clone(budget.id, "_клон%1\$d")
        assertEquals("Бюджет_клон1", first.title)
        assertEquals(1, db.budgetNodesOf(first.id).size)
        assertTrue(db.budgetNodesOf(first.id).single().id != db.budgetNodesOf(budget.id).single().id)
        assertEquals(listOf("дом"), db.tagsOf(first.id).map { it.name })
        assertEquals(listOf("drawing.png"), files.list(first.id).map { it.name })
        // A clone of a clone counts on from the original name.
        assertEquals("Бюджет_клон2", service.clone(first.id, "_клон%1\$d").title)
    }
}
