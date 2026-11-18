package com.notebudget.app.data.transfer

import com.notebudget.app.R
import com.notebudget.app.core.common.Clock
import com.notebudget.app.core.common.IdGenerator
import com.notebudget.app.data.drawing.AudioStore
import com.notebudget.app.data.drawing.DrawingStore
import com.notebudget.app.data.drawing.VectorStore
import com.notebudget.app.feature.drawing.Shape
import com.notebudget.app.feature.drawing.VectorDocument
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import com.notebudget.app.domain.model.AudioClip
import com.notebudget.app.domain.model.AudioDocument
import com.notebudget.app.domain.model.BudgetNode
import com.notebudget.app.domain.model.BudgetNodeKind
import com.notebudget.app.domain.model.Note
import com.notebudget.app.domain.model.NoteType
import com.notebudget.app.domain.repository.BudgetRepository
import com.notebudget.app.domain.usecase.BudgetCalculator
import java.math.BigDecimal
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

private val MARKDOWN = ExportFormat("md", R.string.export_format_markdown, "md", "text/markdown")
private val STRUCTURE = ExportFormat(StructuredNoteFile.KEY, R.string.export_format_structure, StructuredNoteFile.EXTENSION, StructuredNoteFile.MIME)
private val SNAPSHOT_MD = ExportFormat("snapshot", R.string.export_format_snapshot, "md", "text/markdown")
private val SNAPSHOT_TXT = ExportFormat("snapshot", R.string.export_format_snapshot, "txt", "text/plain")
private val PNG = ExportFormat("png", R.string.export_format_png, "png", "image/png")
private val TXT = ExportFormat("txt", R.string.export_format_txt, "txt", "text/plain")
private val PDF = ExportFormat("pdf", R.string.export_format_pdf, "pdf", "application/pdf")
private val M4A = ExportFormat("m4a", R.string.export_format_audio, "m4a", "audio/mp4")
private val ZIP_CLIPS = ExportFormat("zip", R.string.export_format_audio_zip, "zip", "application/zip")
private val LAYERS = ExportFormat(StructuredNoteFile.KEY, R.string.export_format_layers, StructuredNoteFile.EXTENSION, StructuredNoteFile.MIME)
private val JPEG = ExportFormat("jpeg", R.string.export_format_jpeg, "jpg", "image/jpeg")

private fun String.baseName(): String = substringAfterLast('/').substringBeforeLast('.', this).ifBlank { "Note" }
private fun String.extension(): String = substringAfterLast('.', "").lowercase()

/**
 * TEXT: exported as Markdown (the source), as plain text or as a PDF (both without Markdown syntax, D100);
 * recognises `.md`, `.markdown` and `.txt` files (also snapshots of other kinds).
 */
class TextTransferHandler(private val pdf: PdfWriter = AndroidPdfWriter()) : NoteTransferHandler {
    override val exportFormats = listOf(MARKDOWN, TXT, PDF)

    override suspend fun export(note: Note, format: ExportFormat): ByteArray {
        // A formatted note (D211) is exported through its Markdown form: bold, italic, strikethrough and lists come along.
        val text = com.notebudget.app.domain.model.RichDocument.decode(note.extras, note.contentText.orEmpty())
            ?.let { com.notebudget.app.domain.model.RichMarkdown.toMarkdown(it) } ?: note.contentText.orEmpty()
        return when (format.key) {
            TXT.key -> PlainText.fromMarkdown(text).toByteArray(Charsets.UTF_8)
            PDF.key -> pdf.write(PlainText.withTitle(note.title, PlainText.blocksFromMarkdown(text)))
            else -> text.toByteArray(Charsets.UTF_8)
        }
    }

    override suspend fun importFile(fileName: String, bytes: ByteArray): ImportedNote? {
        if (fileName.extension() !in setOf("md", "markdown", "txt")) return null
        if (bytes.any { it == 0.toByte() }) return null
        val text = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
        return ImportedNote(title = fileName.baseName(), type = NoteType.TEXT, contentText = text)
    }
}

/**
 * WORD (D214): exported like a text note, through the Markdown form of its formatting (bold, italic, strikethrough, lists come
 * along). It claims no file on import: a .md or .txt file becomes a Markdown TEXT note, as before.
 */
class WordTransferHandler(pdf: PdfWriter = AndroidPdfWriter()) : NoteTransferHandler {
    private val text = TextTransferHandler(pdf)
    override val exportFormats = text.exportFormats
    override suspend fun export(note: Note, format: ExportFormat): ByteArray = text.export(note, format)
}

/**
 * LIST: the structure (quantities, units, comments, unit totals) is in `note.extras` and a readable copy in
 * `contentText`; both are part of the note record, so the structured file needs no extra payload (D92).
 */
class ListTransferHandler(
    private val clock: Clock = Clock.System,
    private val appVersion: String = "",
    private val pdf: PdfWriter = AndroidPdfWriter(),
) : NoteTransferHandler {
    override val exportFormats = listOf(STRUCTURE, SNAPSHOT_MD, TXT, PDF)

    override suspend fun export(note: Note, format: ExportFormat): ByteArray = when (format.key) {
        StructuredNoteFile.KEY -> StructuredNoteFile.encode(note, null, clock.now(), appVersion)
        TXT.key -> PlainText.fromList(note.contentText.orEmpty()).toByteArray(Charsets.UTF_8)
        PDF.key -> pdf.write(PlainText.withTitle(note.title, PlainText.blocksFromList(note.contentText.orEmpty())))
        else -> note.contentText.orEmpty().toByteArray(Charsets.UTF_8)
    }
}

/**
 * BUDGET: "with structure" carries the whole node tree; "result only" is a readable text
 * snapshot that comes back as a TEXT note (D24). Importing recalculates every cached value
 * (INV-13), so a file with wrong totals cannot corrupt the tree.
 */
class BudgetTransferHandler(
    private val budgets: BudgetRepository,
    private val clock: Clock = Clock.System,
    private val ids: IdGenerator = IdGenerator.Random,
    private val appVersion: String = "",
) : NoteTransferHandler {
    override val exportFormats = listOf(STRUCTURE, SNAPSHOT_TXT)

    override suspend fun export(note: Note, format: ExportFormat): ByteArray = when (format.key) {
        StructuredNoteFile.KEY ->
            StructuredNoteFile.encode(note, collect(note).payload, clock.now(), appVersion)
        else -> snapshot(budgets.getNodes(note.id), com.notebudget.app.domain.model.BudgetCurrency.labelOf(note.extras)).toByteArray(Charsets.UTF_8)
    }

    /** A clone gets its own copy of the tree, with new ids and the totals recalculated (the same code as an import). */
    override suspend fun copyData(from: Note, to: Note) {
        restore(to, NoteExtras(payload = collect(from).payload))
    }

    override suspend fun collect(note: Note): NoteExtras {
        val records = budgets.getNodes(note.id).map { it.toRecord() }
        val array = TransferFormat.json.encodeToJsonElement(ListSerializer(BudgetNodeRecord.serializer()), records)
        return NoteExtras(payload = JsonObject(mapOf("nodes" to array)))
    }

    override suspend fun restore(note: Note, extras: NoteExtras) {
        val array = extras.payload?.get("nodes") as? JsonArray
        val raw = array?.let {
            TransferFormat.json.decodeFromJsonElement(ListSerializer(BudgetNodeRecord.serializer()), it)
        }.orEmpty().map { it.toDomain() }

        val newIds = raw.associate { it.id to ids.newId() }
        val now = clock.now()
        var nodes = raw.map {
            it.copy(id = newIds.getValue(it.id), noteId = note.id, parentId = it.parentId?.let(newIds::get))
        }
        val roots = nodes.filter { it.parentId == null }
        if (roots.size > 1) throw TransferException(TransferError.CORRUPT)
        if (roots.isEmpty()) {
            nodes = listOf(BudgetNode(
                id = ids.newId(), noteId = note.id, parentId = null, title = note.title,
                kind = BudgetNodeKind.GROUP, manualSumMinor = 0, manualGoalMinor = 0, comment = null,
                cachedSumMinor = 0, cachedGoalMinor = 0, cachedRemainderMinor = 0,
                isExpanded = true, sortOrder = 0, createdAt = now, updatedAt = now,
            ))
        }
        // The root of a budget is always a group.
        nodes = nodes.map { if (it.parentId == null) it.copy(kind = BudgetNodeKind.GROUP) else it }
        val byId = nodes.associateBy { it.id }
        // Drop nodes that point at a missing parent, or hang below a LEAF (INV-4).
        val valid = nodes.filter { n ->
            n.parentId == null || byId[n.parentId]?.kind == BudgetNodeKind.GROUP
        }
        budgets.applyChanges(BudgetCalculator.recalculateAll(reachable(valid)), emptyList())
    }

    /** Nodes connected to the root; anything else (orphans, cycles) is left out. */
    private fun reachable(nodes: List<BudgetNode>): List<BudgetNode> {
        val children = nodes.groupBy { it.parentId }
        val out = ArrayList<BudgetNode>()
        val seen = HashSet<String>()
        fun walk(n: BudgetNode) {
            if (seen.add(n.id)) {
                out += n
                children[n.id].orEmpty().forEach(::walk)
            }
        }
        children[null].orEmpty().forEach(::walk)
        return out
    }

    /** Indented, locale-independent text: `Name: sum 10.00 / goal 20.00 / remainder 10.00`. */
    internal fun snapshot(nodes: List<BudgetNode>, currency: String? = null): String {
        val byParent = nodes.groupBy { it.parentId }
        val lines = ArrayList<String>()
        fun money(minor: Long) = BigDecimal.valueOf(minor, 2).toPlainString() + if (currency.isNullOrEmpty()) "" else " $currency"
        fun walk(n: BudgetNode, depth: Int) {
            val name = if (n.kind == BudgetNodeKind.LEAF && n.quantity > 1) "${n.title} x${n.quantity}" else n.title
            val line = "$name: sum ${money(n.cachedSumMinor)} / goal ${money(n.cachedGoalMinor)} / " +
                "remainder ${money(n.cachedRemainderMinor)}"
            lines += "  ".repeat(depth) + (if (depth == 0) "" else "- ") + line
            n.comment?.let { lines += "  ".repeat(depth + 1) + it }
            byParent[n.id].orEmpty().sortedBy { it.sortOrder }.forEach { walk(it, depth + 1) }
        }
        byParent[null].orEmpty().forEach { walk(it, 0) }
        return lines.joinToString("\n")
    }
}

/**
 * DRAWING: a drawing has a raster layer (a PNG file) and a vector layer (a JSON file of shapes).
 * Backups carry both as attachments (`drawing.png`, `vector.json`); a backup made before the
 * vector layer existed simply has no `vector.json` and restores as a raster-only drawing, and an
 * older app reading a newer backup ignores `vector.json` and shows the raster layer. Exported
 * alone, a drawing is either flattened to PNG / JPEG (D42) or saved as JSON with both layers, which
 * keeps the shapes editable when it is imported again. Any PNG or JPEG file is recognised on import
 * by its signature and becomes the raster layer.
 */
class DrawingTransferHandler(
    private val store: DrawingStore,
    private val vectors: VectorStore,
    /** Merges the sheet's background (D265), the raster PNG (or nothing) and the shapes into one PNG; needs Android graphics. */
    private val flatten: suspend (png: ByteArray?, shapes: List<Shape>, width: Int, height: Int, background: com.notebudget.app.feature.drawing.SheetBackground) -> ByteArray,
    /** Re-encodes PNG bytes as JPEG on a white background; needs Android graphics. */
    private val toJpeg: suspend (ByteArray) -> ByteArray,
    private val clock: Clock = Clock.System,
    private val appVersion: String = "",
) : NoteTransferHandler {
    override val exportFormats = listOf(PNG, JPEG, LAYERS)

    private suspend fun shapesOf(noteId: String): List<Shape> =
        vectors.read(noteId)?.let { VectorDocument.decode(it) }?.drawableShapes().orEmpty()

    override suspend fun export(note: Note, format: ExportFormat): ByteArray {
        if (format.key == StructuredNoteFile.KEY) {
            return StructuredNoteFile.encode(note, collect(note).payload, clock.now(), appVersion)
        }
        val png = store.read(note.id)
        val document = vectors.read(note.id)?.let { VectorDocument.decode(it) }
        val shapes = document?.drawableShapes().orEmpty()
        if (png == null && shapes.isEmpty()) throw TransferException(TransferError.CORRUPT)
        // The sheet's own size (D260); 1080 x 1440 for a note without a vector document.
        val (w, h) = document?.sheet() ?: (com.notebudget.app.feature.drawing.SheetSize.DEFAULT_WIDTH to com.notebudget.app.feature.drawing.SheetSize.DEFAULT_HEIGHT)
        // The sheet's background goes into the picture (D265): a transparent PNG shows black in most messengers.
        val flat = flatten(png, shapes, w, h, document?.background() ?: com.notebudget.app.feature.drawing.SheetBackground.DEFAULT)
        return if (format.key == "jpeg") toJpeg(flat) else flat
    }

    /** The layers for a structured single-note file: the raster PNG as base64 and the vector document. */
    override suspend fun collect(note: Note): NoteExtras {
        val fields = LinkedHashMap<String, JsonElement>()
        store.read(note.id)?.let { fields["raster"] = JsonPrimitive(Base64.getEncoder().encodeToString(it)) }
        vectors.read(note.id)?.let { VectorDocument.decode(it) }?.let {
            fields["vector"] = TransferFormat.json.encodeToJsonElement(VectorDocument.serializer(), it)
        }
        return NoteExtras(payload = JsonObject(fields))
    }

    override suspend fun restore(note: Note, extras: NoteExtras) {
        val raster = (extras.payload?.get("raster") as? JsonPrimitive)?.contentOrNull
            ?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
            ?: extras.attachments[FILE]
        val vector = extras.payload?.get("vector")?.let {
            runCatching { TransferFormat.json.decodeFromJsonElement(VectorDocument.serializer(), it) }.getOrNull()
        }
        if (raster != null && isImage(raster)) store.write(note.id, raster)
        if (vector != null) vectors.write(note.id, vector.encode())
    }

    override suspend fun backupAttachments(note: Note): Map<String, ByteArray> {
        val files = LinkedHashMap<String, ByteArray>()
        store.read(note.id)?.let { files[FILE] = it }
        vectors.read(note.id)?.let { files[VECTOR_FILE] = it }
        return files
    }

    override suspend fun restoreBackupAttachments(note: Note, files: Map<String, ByteArray>) {
        files[FILE]?.takeIf { isImage(it) }?.let { store.write(note.id, it) }
        // Only a readable vector layer is restored; a damaged one must not poison the drawing.
        files[VECTOR_FILE]?.takeIf { VectorDocument.decode(it) != null }?.let { vectors.write(note.id, it) }
    }

    override suspend fun clearAttachments() {
        store.deleteAll()
        vectors.deleteAll()
    }

    override suspend fun deleteAttachments(note: Note) {
        store.delete(note.id)
        vectors.delete(note.id)
    }

    override suspend fun importFile(fileName: String, bytes: ByteArray): ImportedNote? {
        if (!isImage(bytes) || bytes.size > MAX_IMAGE_BYTES) return null
        return ImportedNote(
            title = fileName.baseName(),
            type = NoteType.DRAWING,
            data = NoteExtras(attachments = mapOf(FILE to bytes)),
        )
    }

    private fun isImage(b: ByteArray): Boolean =
        (b.size > 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() &&
            b[3] == 'G'.code.toByte()) ||
            (b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte())

    companion object {
        const val FILE = "drawing.png"
        const val VECTOR_FILE = "vector.json"
        private const val MAX_IMAGE_BYTES = 30 * 1024 * 1024
    }
}

/**
 * AUDIO: a note holds several recordings (D112), each an MPEG-4 audio file (.m4a) kept as a file, with a
 * name and a comment in the note's [AudioDocument]. Backups carry the document (in the note record) and one
 * attachment per clip (`clip-<id>.m4a`); a note from before D112 has the single attachment `audio.m4a`,
 * which older backups still contain and which is still read. Exported alone a note gives its one recording
 * as `.m4a`, or all of them as a `.zip` named "<note title> - <clip name>.m4a". Import recognises MPEG-4
 * files by their ftyp header, whatever the file is called, so voice-recorder files work.
 */
class AudioTransferHandler(
    private val store: AudioStore,
    /** The files of recordings in the trash, which a purge of the note and a full restore must not remove (D156). */
    private val protection: com.notebudget.app.domain.model.TrashProtection? = null,
) : NoteTransferHandler {
    override val exportFormats = listOf(M4A)

    private fun document(note: Note) = AudioDocument.forNote(note.extras, store.fileIfExists(note.id) != null)

    override fun exportFormatsFor(note: Note): List<ExportFormat> =
        if (document(note).clips.size > 1) listOf(ZIP_CLIPS) else listOf(M4A)

    override suspend fun export(note: Note, format: ExportFormat): ByteArray {
        val clips = document(note).clips
        if (format.key != ZIP_CLIPS.key) {
            val first = clips.firstOrNull() ?: throw TransferException(TransferError.CORRUPT)
            return store.read(AudioDocument.fileKey(note.id, first.id)) ?: throw TransferException(TransferError.CORRUPT)
        }
        val names = AudioClipNames.exportNames(note.title, clips)
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            clips.forEachIndexed { index, clip ->
                val data = store.read(AudioDocument.fileKey(note.id, clip.id)) ?: return@forEachIndexed
                zip.putNextEntry(ZipEntry(names[index]))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    override suspend fun collect(note: Note): NoteExtras = NoteExtras(attachments = backupAttachments(note))

    override suspend fun restore(note: Note, extras: NoteExtras) = restoreBackupAttachments(note, extras.attachments)

    override suspend fun backupAttachments(note: Note): Map<String, ByteArray> {
        val files = LinkedHashMap<String, ByteArray>()
        for (clip in document(note).clips) {
            val bytes = store.read(AudioDocument.fileKey(note.id, clip.id)) ?: continue
            files[if (clip.id == AudioClip.LEGACY_ID) FILE else CLIP_PREFIX + clip.id + CLIP_SUFFIX] = bytes
        }
        return files
    }

    override suspend fun backupAttachmentSources(note: Note): List<AttachmentSource> = document(note).clips.mapNotNull { clip ->
        val file = store.fileIfExists(AudioDocument.fileKey(note.id, clip.id)) ?: return@mapNotNull null
        AttachmentSource(if (clip.id == AudioClip.LEGACY_ID) FILE else CLIP_PREFIX + clip.id + CLIP_SUFFIX, file.length()) { file.inputStream() }
    }

    override suspend fun restoreBackupAttachment(note: Note, name: String, input: java.io.InputStream): Boolean {
        val key = when {
            name == FILE -> note.id
            name.startsWith(CLIP_PREFIX) && name.endsWith(CLIP_SUFFIX) -> {
                val clipId = name.removePrefix(CLIP_PREFIX).removeSuffix(CLIP_SUFFIX)
                if (!CLIP_ID.matches(clipId)) return false
                AudioDocument.fileKey(note.id, clipId)
            }
            else -> return false
        }
        return store.writeStream(key, input, ::isMpeg4)
    }

    override suspend fun restoreBackupAttachments(note: Note, files: Map<String, ByteArray>) {
        for ((name, bytes) in files) {
            if (!isMpeg4(bytes)) continue
            if (name == FILE) {
                store.write(note.id, bytes)
            } else if (name.startsWith(CLIP_PREFIX) && name.endsWith(CLIP_SUFFIX)) {
                val clipId = name.removePrefix(CLIP_PREFIX).removeSuffix(CLIP_SUFFIX)
                if (CLIP_ID.matches(clipId)) store.write(AudioDocument.fileKey(note.id, clipId), bytes)
            }
        }
    }

    override suspend fun clearAttachments() = store.deleteAll(protection?.allKeys().orEmpty())

    override suspend fun deleteAttachments(note: Note) {
        val keep = protection?.keysOf(note.id).orEmpty()
        if (note.id !in keep) store.delete(note.id)
        store.deleteWithPrefix(note.id + "_", keep)
    }

    override suspend fun importFile(fileName: String, bytes: ByteArray): ImportedNote? {
        if (!isMpeg4(bytes) || bytes.size > MAX_AUDIO_BYTES) return null
        return ImportedNote(
            title = fileName.baseName(),
            type = NoteType.AUDIO,
            data = NoteExtras(attachments = mapOf(FILE to bytes)),
        )
    }

    /** An ISO base media file starts with a box whose type at offset 4 is ftyp. */
    private fun isMpeg4(b: ByteArray): Boolean =
        b.size > 12 && b[4] == 'f'.code.toByte() && b[5] == 't'.code.toByte() &&
            b[6] == 'y'.code.toByte() && b[7] == 'p'.code.toByte()

    companion object {
        /** The single recording of a note made before D112, and of an imported .m4a file. */
        const val FILE = "audio.m4a"
        private const val CLIP_PREFIX = "clip-"
        private const val CLIP_SUFFIX = ".m4a"
        private val CLIP_ID = Regex("[A-Za-z0-9]{1,32}")
        private const val MAX_AUDIO_BYTES = 200 * 1024 * 1024
    }
}

/** File names of the recordings of a note when they are exported together (D112). */
object AudioClipNames {
    /**
     * "<note title> - <clip name>.m4a" for each clip, in order. A clip without a name is called
     * "recording N"; two clips with the same name get " (2)", " (3)" so no file overwrites another.
     */
    fun exportNames(noteTitle: String, clips: List<AudioClip>): List<String> {
        val used = HashSet<String>()
        return clips.mapIndexed { index, clip ->
            val base = sanitize(sanitize(noteTitle, "note") + " - " + sanitize(clip.name, "recording ${index + 1}"), "recording")
            var candidate = base
            var n = 2
            while (!used.add(candidate.lowercase())) candidate = "$base ($n)".also { n++ }
            "$candidate.m4a"
        }
    }

    private fun sanitize(text: String, fallback: String): String =
        text.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trim('.').ifBlank { fallback }.take(80)
}

/**
 * BLUEPRINT (D98): the same files and export code as a drawing (the vector layer, with its layers; a blueprint has no raster
 * sheet, so PNG and JPEG are the shapes drawn on white). It does not claim PNG/JPEG files on import: those stay drawings.
 */
class BlueprintTransferHandler(private val drawings: DrawingTransferHandler) : NoteTransferHandler by drawings {
    override suspend fun importFile(fileName: String, bytes: ByteArray): ImportedNote? = null
}

/**
 * SCHEDULE (D155): the rows are in `note.extras` and a readable copy in `contentText`, both part of the note record, so a
 * structured file needs no extra payload. Exported with structure (JSON), as text or as a PDF.
 */
class ScheduleTransferHandler(
    private val clock: Clock = Clock.System,
    private val appVersion: String = "",
    private val pdf: PdfWriter = AndroidPdfWriter(),
) : NoteTransferHandler {
    override val exportFormats = listOf(STRUCTURE, TXT, PDF)

    override suspend fun export(note: Note, format: ExportFormat): ByteArray = when (format.key) {
        StructuredNoteFile.KEY -> StructuredNoteFile.encode(note, null, clock.now(), appVersion)
        PDF.key -> pdf.write(PlainText.withTitle(note.title, PlainText.blocksFromMarkdown(note.contentText.orEmpty())))
        else -> note.contentText.orEmpty().toByteArray(Charsets.UTF_8)
    }
}
