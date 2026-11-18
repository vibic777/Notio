package com.notebudget.app.data.transfer

import com.notebudget.app.data.drawing.AudioStore
import com.notebudget.app.data.drawing.DrawingStore
import com.notebudget.app.data.drawing.VectorStore
import com.notebudget.app.domain.model.BudgetNode
import com.notebudget.app.domain.model.BudgetNodeKind
import com.notebudget.app.domain.model.DomainResult
import com.notebudget.app.domain.model.Folder
import com.notebudget.app.domain.model.Note
import com.notebudget.app.domain.model.NoteType
import com.notebudget.app.domain.usecase.FakeStore
import com.notebudget.app.domain.usecase.TestEnv
import java.io.File
import java.nio.file.Files

/** [BackupStore] over the in-memory [FakeStore], applying a plan the way the Room store does. */
class FakeBackupStore(private val store: FakeStore) : BackupStore {
    override suspend fun readAll() =
        BackupData(store.folders.value, store.notes.value, store.nodes.value, store.reminders.value)

    override suspend fun apply(plan: ImportPlan) {
        if (plan.clearAll) {
            store.folders.value = emptyList()
            store.notes.value = emptyList()
            store.nodes.value = emptyList()
            store.reminders.value = emptyList()
        }
        store.folders.value = store.folders.value.filterNot { f -> plan.folders.any { it.id == f.id } } + plan.folders
        store.notes.value = store.notes.value.filterNot { n -> plan.notes.any { it.id == n.id } } + plan.notes
        store.nodes.value = store.nodes.value.filterNot { it.noteId in plan.replaceNodesOf } + plan.nodes
        store.reminders.value =
            store.reminders.value.filterNot { r -> plan.reminders.any { it.id == r.id } } + plan.reminders
    }
}

class FakeSettings(var value: SettingsRecord = SettingsRecord("ru", "DARK", "TITLE_ONLY", trashRetentionDays = 60)) : SettingsPort {
    override suspend fun read() = value
    override suspend fun write(settings: SettingsRecord) {
        value = settings
    }
}

/** A complete transfer setup on top of a [TestEnv], with real file stores in a temp folder. */
class TransferEnv(val env: TestEnv = TestEnv()) {
    /** Records what a PDF would hold instead of drawing it (the real writer needs Android). */
    val fakePdf = PdfWriter { blocks -> ("%PDF-FAKE\n" + blocks.joinToString("\n") { it.style.name + ":" + it.text }).toByteArray() }
    val dir: File = Files.createTempDirectory("notebudget-test").toFile().also { it.deleteOnExit() }
    val drawings = DrawingStore(File(dir, "drawings"))
    val vectors = VectorStore(File(dir, "drawings-vector"))
    val audio = AudioStore(File(dir, "audio"))
    val handlers = listOf(
        TextTransferHandler(fakePdf),
        ListTransferHandler(pdf = fakePdf),
        BudgetTransferHandler(env.budgetRepo, env.clock, env.ids),
        DrawingTransferHandler(drawings, vectors, flatten = { png, _, _, _, _ -> png ?: fakePng(0) }, toJpeg = { it }),
        AudioTransferHandler(audio),
    )
    private val byType = mapOf(
        NoteType.TEXT to handlers[0], NoteType.LIST to handlers[1], NoteType.BUDGET to handlers[2],
        NoteType.DRAWING to handlers[3], NoteType.AUDIO to handlers[4],
    )
    val lookup = object : NoteHandlerLookup {
        override fun handlerFor(type: NoteType) = byType[type]
        override val allHandlers get() = handlers
    }
    val store = FakeBackupStore(env.store)
    val settings = FakeSettings()
    val backup = BackupService(store, lookup, env.scheduler, env.reminders, settings, "1.0", env.clock, env.ids, env.tagRepo, env.tags, budgetLog = env.logRepo)
    val files = NoteFileService(env.noteRepo, env.folderRepo, lookup, env.clock, env.ids, env.tagRepo, env.tags)

    suspend fun folder(name: String): Folder =
        (env.folders.create(name, "#3366FF", "SOFT") as DomainResult.Success).value

    suspend fun note(folderId: String, title: String, type: NoteType, content: String? = null): Note =
        (env.notes.create(folderId, title, type, contentText = content) as DomainResult.Success).value

    /** A budget note with one group "Food" holding "Bread" (sum 10.00, goal 25.00) and a leaf "Rent". */
    suspend fun budget(folderId: String, title: String = "Budget"): Note {
        val note = note(folderId, title, NoteType.BUDGET)
        val root = env.budgetRepo.getNodes(note.id).single()
        val food = (env.budget.addNode(note.id, root.id, "Food", BudgetNodeKind.GROUP) as DomainResult.Success).value
        val bread = (env.budget.addNode(note.id, food.id, "Bread", BudgetNodeKind.LEAF) as DomainResult.Success).value
        env.budget.updateLeaf(note.id, bread.id, 1_000, 2_500)
        val rent = (env.budget.addNode(note.id, root.id, "Rent", BudgetNodeKind.LEAF) as DomainResult.Success).value
        env.budget.updateLeaf(note.id, rent.id, 50_000, 40_000)
        return note
    }

    suspend fun nodes(noteId: String): List<BudgetNode> = env.budgetRepo.getNodes(noteId)

    /** Bytes that pass for a PNG / MPEG-4 file in the handlers' signature checks. */
    fun fakePng(seed: Int = 0) = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10, seed.toByte(), 1, 2, 3)
    fun fakeM4a(seed: Int = 0) = byteArrayOf(0, 0, 0, 0x20, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(), 'M'.code.toByte(), '4'.code.toByte(), 'A'.code.toByte(), ' '.code.toByte(), seed.toByte(), 9)
}
