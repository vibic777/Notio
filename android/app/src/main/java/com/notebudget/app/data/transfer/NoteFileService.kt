package com.notebudget.app.data.transfer

import com.notebudget.app.core.common.Clock
import com.notebudget.app.core.common.IdGenerator
import com.notebudget.app.domain.model.Note
import com.notebudget.app.domain.repository.FolderRepository
import com.notebudget.app.domain.repository.NoteRepository
import com.notebudget.app.domain.repository.TagRepository
import com.notebudget.app.domain.usecase.TagUseCases
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject

class ExportedFile(val fileName: String, val mimeType: String, val bytes: ByteArray)

/** The JSON file used by the "with structure" export of LIST and BUDGET notes (D24). */
object StructuredNoteFile {
    const val KEY = "json"
    const val EXTENSION = "json"
    const val MIME = "application/json"

    fun encode(note: Note, payload: JsonObject?, now: Long, appVersion: String): ByteArray =
        TransferFormat.json.encodeToString(
            NoteFile.serializer(),
            NoteFile(createdAt = now, appVersion = appVersion, note = note.toRecord(), payload = payload),
        ).toByteArray(Charsets.UTF_8)

    /** `null` when [bytes] is not one of our note files; throws when it is but is too new. */
    fun decode(bytes: ByteArray): NoteFile? {
        val text = bytes.toString(Charsets.UTF_8).trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        if (!text.startsWith("{")) return null
        val file = try {
            TransferFormat.json.decodeFromString(NoteFile.serializer(), text)
        } catch (_: SerializationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (file.format != TransferFormat.NOTE) return null
        if (file.minReaderVersion > TransferFormat.FORMAT_VERSION) {
            throw TransferException(TransferError.UNSUPPORTED_VERSION)
        }
        return file
    }
}

/**
 * Export and import of single notes (§6.13, D23, D62). What a kind exports and recognises is
 * decided by its [NoteTransferHandler]; this class only ties the handlers to the database.
 * An imported note always gets a new id and lands in the chosen folder (INV-12).
 */
class NoteFileService(
    private val notes: NoteRepository,
    private val folders: FolderRepository,
    private val handlers: NoteHandlerLookup,
    private val clock: Clock = Clock.System,
    private val ids: IdGenerator = IdGenerator.Random,
    private val tagRepository: TagRepository? = null,
    private val tagUseCases: TagUseCases? = null,
    /** How exported files are named (D139); without it the name is the note's title. */
    private val formats: com.notebudget.app.domain.model.FormatPreferences? = null,
) {

    fun formatsFor(note: Note): List<ExportFormat> = handlers.handlerFor(note.type)?.exportFormatsFor(note).orEmpty()

    suspend fun export(note: Note, format: ExportFormat): ExportedFile {
        val handler = handlers.handlerFor(note.type) ?: throw TransferException(TransferError.UNRECOGNIZED_FILE)
        // The tags go into the structured file only (D109); other formats ignore them.
        val tagged = tagRepository?.getAssignments()?.get(note.id)?.let { list -> note.copy(tags = list.map { it.name }) } ?: note
        val naming = formats?.exportNaming?.first() ?: com.notebudget.app.domain.model.ExportNaming()
        val base = naming.baseName(note.title, itemTitle = null, note.createdAt, note.updatedAt, clock.now())
        return ExportedFile(
            fileName = safeFileName(base) + "." + format.extension,
            mimeType = format.mimeType,
            bytes = handler.export(tagged, format),
        )
    }

    /** @throws TransferException when the folder is missing or no note kind recognises the file. */
    suspend fun import(fileName: String, bytes: ByteArray, folderId: String): Note {
        folders.getById(folderId) ?: throw TransferException(TransferError.FOLDER_NOT_FOUND)
        val imported = StructuredNoteFile.decode(bytes)?.let { file ->
            ImportedNote(
                title = file.note.title,
                type = com.notebudget.app.domain.model.NoteType(file.note.type),
                contentText = file.note.contentText,
                colorHex = file.note.colorHex,
                isTitleBold = file.note.isTitleBold,
                extras = file.note.extras,
                data = NoteExtras(payload = file.payload),
                tags = file.note.tags,
                comment = file.note.comment,
            )
        } ?: handlers.allHandlers.firstNotNullOfOrNull { it.importFile(fileName, bytes) }
            ?: throw TransferException(TransferError.UNRECOGNIZED_FILE)

        val now = clock.now()
        val note = Note(
            id = ids.newId(),
            folderId = folderId,
            title = imported.title.trim().ifBlank { "Note" },
            type = imported.type,
            colorHex = imported.colorHex,
            isPinned = false,
            isFavorite = false,
            isTitleBold = imported.isTitleBold,
            contentText = imported.contentText,
            createdAt = now,
            updatedAt = now,
            extras = imported.extras,
            comment = com.notebudget.app.domain.model.Comment.clean(imported.comment),
        )
        notes.insert(note, budgetRoot = null)
        if (imported.tags.isNotEmpty()) tagUseCases?.assignByNames(note.id, imported.tags)
        try {
            handlers.handlerFor(note.type)?.restore(note, imported.data)
        } catch (e: Exception) {
            notes.trash(note.id, now)
            throw if (e is TransferException) e else TransferException(TransferError.CORRUPT, e)
        }
        return note
    }

    private fun safeFileName(title: String): String =
        title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trim('.').ifBlank { "note" }.take(80)
}
