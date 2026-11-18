package com.notebudget.app.data.transfer

import androidx.annotation.StringRes
import com.notebudget.app.domain.model.Note
import com.notebudget.app.domain.model.NoteType
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlinx.serialization.json.JsonObject

/** Type-specific data that travels with a note: a JSON [payload] and binary [attachments]. */
class NoteExtras(
    val payload: JsonObject? = null,
    val attachments: Map<String, ByteArray> = emptyMap(),
)

/** A file of a note for a full backup, opened only at the moment it is written, so it never has to be held in memory (D174). */
class AttachmentSource(val name: String, val size: Long, val open: () -> InputStream)

/** Up to [limit] bytes of the stream; `null` when there is more (the caller then leaves the file out). */
fun InputStream.readAtMost(limit: Long): ByteArray? {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    var total = 0L
    while (true) {
        val n = read(buffer)
        if (n < 0) break
        total += n
        if (total > limit) return null
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}

/** One way to export a single note (D62, D24). [key] is stable; [labelRes] is shown to the user. */
data class ExportFormat(
    val key: String,
    @StringRes val labelRes: Int,
    val extension: String,
    val mimeType: String,
)

/** A note created from a foreign file (Markdown, PNG, ...) or from a structured note file. */
class ImportedNote(
    val title: String,
    val type: NoteType,
    val contentText: String? = null,
    val colorHex: String? = null,
    val isTitleBold: Boolean = false,
    val extras: String? = null,
    val data: NoteExtras = NoteExtras(),
    /** Tag names that come with the file (D109); empty for foreign files. */
    val tags: List<String> = emptyList(),
    /** A comment that comes with the file (D177); `null` for foreign files. */
    val comment: String? = null,
)

/**
 * The data side of a note kind: how its notes are exported, imported and backed up. One
 * handler per kind, registered together with the kind's UI (see `NoteTypeDefinition`), so a
 * new kind is one class and one registration. Defaults suit a kind whose whole content is
 * `contentText`.
 */
interface NoteTransferHandler {
    /** Export choices for a single note, in the order they are offered. */
    val exportFormats: List<ExportFormat>

    suspend fun export(note: Note, format: ExportFormat): ByteArray

    /** The export choices of one particular note; by default the same for every note of the kind. */
    fun exportFormatsFor(note: Note): List<ExportFormat> = exportFormats

    /** Type-specific data written into a structured single-note file. */
    suspend fun collect(note: Note): NoteExtras = NoteExtras()

    /** Restores [extras] for [note], which is already stored under its final id. */
    suspend fun restore(note: Note, extras: NoteExtras) {}

    /**
     * Files this kind keeps outside the database, for a full backup. Relational data (budget
     * nodes) is NOT included here: the backup carries the database tables directly.
     */
    suspend fun backupAttachments(note: Note): Map<String, ByteArray> = emptyMap()

    suspend fun restoreBackupAttachments(note: Note, files: Map<String, ByteArray>) {}

    /**
     * The same files for a full backup as streams (D174). The default suits kinds with small files (a drawing, a vector layer): it
     * reads [backupAttachments]. Kinds with big files (audio, photo, video, screen) override it and open the file itself.
     */
    suspend fun backupAttachmentSources(note: Note): List<AttachmentSource> =
        backupAttachments(note).map { (name, bytes) -> AttachmentSource(name, bytes.size.toLong()) { ByteArrayInputStream(bytes) } }

    /**
     * Restores ONE file of a backup from [input] (which must not be closed) and tells whether it was stored (D174). The default reads a
     * small file into memory (at most [SMALL_ATTACHMENT_LIMIT]) and uses [restoreBackupAttachments]; kinds with big files override it
     * and copy the stream to disk.
     */
    suspend fun restoreBackupAttachment(note: Note, name: String, input: InputStream): Boolean {
        val bytes = input.readAtMost(SMALL_ATTACHMENT_LIMIT) ?: return false
        restoreBackupAttachments(note, mapOf(name to bytes))
        return true
    }

    /**
     * Copies everything this kind keeps outside the note record from [from] to [to], which is already stored under its own id (a clone,
     * D179). The default copies the files of a backup one by one as streams; a kind whose data is rows of the database overrides it.
     */
    suspend fun copyData(from: Note, to: Note) {
        for (source in backupAttachmentSources(from)) {
            source.open().use { restoreBackupAttachment(to, source.name, it) }
        }
    }

    /** Deletes the files of one note that is being deleted for good (trash purge). */
    suspend fun deleteAttachments(note: Note) {}

    /** Called before a REPLACE import so that no orphan files stay behind. */
    suspend fun clearAttachments() {}

    /** Recognises a file of this kind that is not a structured note file, e.g. `.md` or `.png`. */
    suspend fun importFile(fileName: String, bytes: ByteArray): ImportedNote? = null
}

/** The biggest file a kind without its own streaming restore takes into memory. */
const val SMALL_ATTACHMENT_LIMIT = 64L * 1024 * 1024
