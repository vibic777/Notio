package com.notebudget.app.data.transfer

import com.notebudget.app.R
import com.notebudget.app.data.media.MediaStore
import com.notebudget.app.domain.model.ExportNaming
import com.notebudget.app.domain.model.MediaDocument
import com.notebudget.app.domain.model.MediaItem
import com.notebudget.app.domain.model.MediaKind
import com.notebudget.app.domain.model.Note
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * PHOTO, VIDEO and SCREEN notes (D147): the items are files in the [MediaStore]; the list of them is in `note.extras`.
 * Export gives the file itself (one item) or a ZIP of the files (several). A backup carries every file as a stream (D174), so no
 * size limit comes from memory. It does not claim foreign files on import (a JPEG or an MP4 stays a drawing or an audio note).
 */
class MediaTransferHandler(
    private val kind: MediaKind,
    private val store: MediaStore,
    /** The files of items in the trash, which a purge of the note and a full restore must not remove (D156). */
    private val protection: com.notebudget.app.domain.model.TrashProtection? = null,
) : NoteTransferHandler {
    private val single = ExportFormat("file", if (kind.isVideo) R.string.export_format_video else R.string.export_format_photo, kind.extension, kind.mime)
    private val zip = ExportFormat("zip", R.string.export_format_media_zip, "zip", "application/zip")

    override val exportFormats = listOf(single)

    private fun items(note: Note) = MediaDocument.parse(note.extras).items

    private fun key(note: Note, item: MediaItem) = MediaDocument.fileKey(note.id, item.id)

    override fun exportFormatsFor(note: Note): List<ExportFormat> = if (items(note).size > 1) listOf(zip) else listOf(single)

    override suspend fun export(note: Note, format: ExportFormat): ByteArray {
        val items = items(note)
        if (format.key != zip.key) {
            val first = items.firstOrNull() ?: throw TransferException(TransferError.CORRUPT)
            return store.read(key(note, first), kind)?.takeIf { it.size <= MAX_EXPORT_BYTES } ?: throw TransferException(TransferError.CORRUPT)
        }
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { out ->
            items.forEachIndexed { index, item ->
                val file = store.existing(key(note, item), kind) ?: return@forEachIndexed
                if (file.length() > MAX_EXPORT_BYTES) return@forEachIndexed
                val name = ExportNaming.safe(item.name).ifBlank { kind.name.lowercase() }
                out.putNextEntry(ZipEntry("%03d-%s.%s".format(index + 1, name, kind.extension)))
                file.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    override suspend fun collect(note: Note): NoteExtras = NoteExtras(attachments = backupAttachments(note))

    override suspend fun restore(note: Note, extras: NoteExtras) = restoreBackupAttachments(note, extras.attachments)

    /** The files as bytes, for a single-note file (small); a full backup uses [backupAttachmentSources] instead. */
    override suspend fun backupAttachments(note: Note): Map<String, ByteArray> {
        val files = LinkedHashMap<String, ByteArray>()
        for (item in items(note)) {
            val file = store.existing(key(note, item), kind) ?: continue
            if (file.length() > MAX_SINGLE_NOTE_FILE_BYTES) continue
            files[PREFIX + item.id + "." + kind.extension] = file.readBytes()
        }
        return files
    }

    override suspend fun backupAttachmentSources(note: Note): List<AttachmentSource> = items(note).mapNotNull { item ->
        val file = store.existing(key(note, item), kind) ?: return@mapNotNull null
        AttachmentSource(PREFIX + item.id + "." + kind.extension, file.length()) { file.inputStream() }
    }

    override suspend fun restoreBackupAttachment(note: Note, name: String, input: java.io.InputStream): Boolean {
        if (!name.startsWith(PREFIX) || !name.endsWith("." + kind.extension)) return false
        val id = name.removePrefix(PREFIX).removeSuffix("." + kind.extension)
        if (!ITEM_ID.matches(id)) return false
        return store.writeStream(MediaDocument.fileKey(note.id, id), kind, input, ::looksRight)
    }

    override suspend fun restoreBackupAttachments(note: Note, files: Map<String, ByteArray>) {
        for ((name, bytes) in files) {
            if (!name.startsWith(PREFIX) || !name.endsWith("." + kind.extension)) continue
            val id = name.removePrefix(PREFIX).removeSuffix("." + kind.extension)
            if (!ITEM_ID.matches(id) || !looksRight(bytes)) continue
            store.write(MediaDocument.fileKey(note.id, id), kind, bytes)
        }
    }

    private fun looksRight(b: ByteArray): Boolean = if (kind.isVideo) {
        b.size > 12 && b[4] == 'f'.code.toByte() && b[5] == 't'.code.toByte() && b[6] == 'y'.code.toByte() && b[7] == 'p'.code.toByte()
    } else {
        b.size > 4 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()
    }

    override suspend fun clearAttachments() = store.deleteAll(protection?.allKeys().orEmpty())

    override suspend fun deleteAttachments(note: Note) = store.deleteForNote(note.id, protection?.keysOf(note.id).orEmpty())

    override suspend fun importFile(fileName: String, bytes: ByteArray): ImportedNote? = null

    companion object {
        private const val PREFIX = "item-"
        private val ITEM_ID = Regex("[A-Za-z0-9]{1,32}")

        /** A file bigger than this is left out of a single-note file (which is built in memory); a full backup has no such limit. */
        const val MAX_SINGLE_NOTE_FILE_BYTES = 40L * 1024 * 1024
        private const val MAX_EXPORT_BYTES = 200L * 1024 * 1024
    }
}
