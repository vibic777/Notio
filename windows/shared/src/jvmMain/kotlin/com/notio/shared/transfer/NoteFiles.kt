package com.notio.shared.transfer

import com.notio.shared.data.AttachmentStore
import com.notio.shared.data.NotioDatabase
import com.notio.shared.domain.BudgetCalculator
import com.notio.shared.domain.BudgetCurrency
import com.notio.shared.domain.BudgetNode
import com.notio.shared.domain.BudgetNodeKind
import com.notio.shared.domain.Clock
import com.notio.shared.domain.Comment
import com.notio.shared.domain.ExportNaming
import com.notio.shared.domain.IdGenerator
import com.notio.shared.domain.Note
import com.notio.shared.domain.NoteType
import com.notio.shared.domain.RichDocument
import com.notio.shared.domain.RichMarkdown
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** One way to export a single note (Android D62, D24). [key] is stable; [label] is the name of the Android string shown to the user. */
data class ExportFormat(val key: String, val label: String, val extension: String, val mimeType: String)

class ExportedFile(val fileName: String, val mimeType: String, val bytes: ByteArray)

/** The JSON file of the "with structure" export (Android `StructuredNoteFile`, D24): the phone and the desktop read each other's. */
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
        val text = bytes.toString(Charsets.UTF_8).trimStart('﻿', ' ', '\n', '\r', '\t')
        if (!text.startsWith("{")) return null
        val file = try {
            TransferFormat.json.decodeFromString(NoteFile.serializer(), text)
        } catch (_: SerializationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (file.format != TransferFormat.NOTE) return null
        if (file.minReaderVersion > TransferFormat.FORMAT_VERSION) throw TransferException(TransferError.UNSUPPORTED_VERSION)
        return file
    }
}

/** The copy's name (Android `CloneNames`, D179): "title_clone1", then 2, 3 ... whichever is free in the folder. */
object CloneNames {
    private const val NUMBER = "%1\$d"

    fun pattern(format: String): Regex =
        Regex(Regex.escape(format.substringBefore(NUMBER)) + "\\d+" + Regex.escape(format.substringAfter(NUMBER)) + "$")

    fun next(title: String, existing: Collection<String>, format: String): String {
        val base = title.replace(pattern(format), "").ifBlank { title }
        val taken = existing.map { it.lowercase() }.toSet()
        var n = 1
        while (true) {
            val candidate = base + format.replace(NUMBER, n.toString())
            if (candidate.lowercase() !in taken) return candidate
            n++
        }
    }
}

/**
 * Export, import and cloning of single notes (Android `NoteFileService`, `Handlers`, `NoteCloneService`; AGENT.md D27). The formats of
 * each kind are the phone's, byte for byte where the file is ours (the structured JSON, Markdown, text snapshots), so a file exported
 * on one device imports on the other. The desktop keeps a note's files under their backup names (D07), so the files of every kind
 * (drawing, vector layer, recordings, photos) are copied and exported alike, without a handler per kind.
 */
class NoteFileService(
    private val db: NotioDatabase,
    private val files: AttachmentStore,
    private val appVersion: String,
    private val pdf: PdfWriter = PdfBoxWriter(),
    /** Merges the raster PNG (or nothing) and the vector layer (the JSON file, or nothing) into one PNG; drawn by the app's renderer. */
    private val flatten: suspend (png: ByteArray?, vector: ByteArray?) -> ByteArray? = { png, _ -> png },
    /** Re-encodes PNG bytes as JPEG on a white background. */
    private val toJpeg: suspend (ByteArray) -> ByteArray = { it },
    /** Checks a vector layer before it is stored (a damaged one must not poison the drawing). */
    private val vectorIsValid: (ByteArray) -> Boolean = { runCatching { Json.parseToJsonElement(it.toString(Charsets.UTF_8)) is JsonObject }.getOrDefault(false) },
    private val naming: () -> ExportNaming = { ExportNaming() },
    private val clock: Clock = Clock.System,
    private val ids: IdGenerator = IdGenerator.Random,
) {

    fun formatsFor(note: Note): List<ExportFormat> = when (note.type) {
        NoteType.TEXT, NoteType.WORD -> listOf(MARKDOWN, TXT, PDF)
        // A task (Android D239) is exported as its text: the description (the phone: TextTransferHandler).
        NoteType.TASK -> listOf(MARKDOWN, TXT, PDF)
        NoteType.LIST -> listOf(STRUCTURE, SNAPSHOT_MD, TXT, PDF)
        NoteType.BUDGET -> listOf(STRUCTURE, SNAPSHOT_TXT)
        NoteType.SCHEDULE -> listOf(STRUCTURE, TXT, PDF)
        NoteType.DRAWING, NoteType.BLUEPRINT -> listOf(PNG, JPEG, LAYERS)
        NoteType.AUDIO -> if (audioFiles(note).size > 1) listOf(ZIP_CLIPS) else listOf(M4A)
        else -> mediaFormats(note)
    }

    private fun mediaFormats(note: Note): List<ExportFormat> {
        val kind = com.notio.shared.domain.MediaKind.of(note.type) ?: return emptyList()
        val items = mediaFiles(note, kind)
        if (items.isEmpty()) return emptyList()
        if (items.size > 1) return listOf(MEDIA_ZIP)
        return listOf(ExportFormat("file", if (kind.isVideo) "export_format_video" else "export_format_photo", kind.extension, kind.mime))
    }

    /** The items of a photo / video / screen note that have their file, in the note's order (`item-<id>.<ext>`, Android D147). */
    private fun mediaFiles(note: Note, kind: com.notio.shared.domain.MediaKind): List<Pair<com.notio.shared.domain.MediaItem, java.io.File>> =
        com.notio.shared.domain.MediaDocument.parse(note.extras).items.mapNotNull { item ->
            runCatching { files.file(note.id, "item-" + item.id + "." + kind.extension) }.getOrNull()?.let { item to it }
        }

    suspend fun export(note: Note, format: ExportFormat): ExportedFile {
        // The tags go into the structured file only (D109); other formats ignore them.
        val tagged = note.copy(tags = db.tagsOf(note.id).map { it.name })
        val base = naming().baseName(note.title, itemTitle = null, note.createdAt, note.updatedAt, clock.now())
        return ExportedFile(safeFileName(base) + "." + format.extension, format.mimeType, exportBytes(tagged, format))
    }

    private suspend fun exportBytes(note: Note, format: ExportFormat): ByteArray = when (note.type) {
        NoteType.TEXT, NoteType.WORD -> {
            // A formatted note (D211) goes out through its Markdown form: bold, italic, strikethrough and lists come along.
            val text = RichDocument.decode(note.extras, note.contentText.orEmpty())?.let { RichMarkdown.toMarkdown(it) } ?: note.contentText.orEmpty()
            when (format.key) {
                TXT.key -> PlainText.fromMarkdown(text).toByteArray(Charsets.UTF_8)
                PDF.key -> pdf.write(PlainText.withTitle(note.title, PlainText.blocksFromMarkdown(text)))
                else -> text.toByteArray(Charsets.UTF_8)
            }
        }
        // A task (Android D239): its description, the text of the note (its extras are the task's own fields, not styles).
        NoteType.TASK -> when (format.key) {
            TXT.key -> PlainText.fromMarkdown(note.contentText.orEmpty()).toByteArray(Charsets.UTF_8)
            PDF.key -> pdf.write(PlainText.withTitle(note.title, PlainText.blocksFromMarkdown(note.contentText.orEmpty())))
            else -> note.contentText.orEmpty().toByteArray(Charsets.UTF_8)
        }
        NoteType.LIST -> when (format.key) {
            StructuredNoteFile.KEY -> StructuredNoteFile.encode(note, null, clock.now(), appVersion)
            TXT.key -> PlainText.fromList(note.contentText.orEmpty()).toByteArray(Charsets.UTF_8)
            PDF.key -> pdf.write(PlainText.withTitle(note.title, PlainText.blocksFromList(note.contentText.orEmpty())))
            else -> note.contentText.orEmpty().toByteArray(Charsets.UTF_8)
        }
        NoteType.BUDGET -> when (format.key) {
            StructuredNoteFile.KEY -> StructuredNoteFile.encode(note, budgetPayload(note.id), clock.now(), appVersion)
            else -> budgetSnapshot(db.budgetNodesOf(note.id), BudgetCurrency.labelOf(note.extras)).toByteArray(Charsets.UTF_8)
        }
        NoteType.SCHEDULE -> when (format.key) {
            StructuredNoteFile.KEY -> StructuredNoteFile.encode(note, null, clock.now(), appVersion)
            PDF.key -> pdf.write(PlainText.withTitle(note.title, PlainText.blocksFromMarkdown(note.contentText.orEmpty())))
            else -> note.contentText.orEmpty().toByteArray(Charsets.UTF_8)
        }
        NoteType.DRAWING, NoteType.BLUEPRINT -> {
            if (format.key == StructuredNoteFile.KEY) {
                StructuredNoteFile.encode(note, drawingPayload(note.id), clock.now(), appVersion)
            } else {
                val png = read(note.id, DRAWING_FILE)
                val vector = read(note.id, VECTOR_FILE)
                if (png == null && vector == null) throw TransferException(TransferError.CORRUPT)
                val flat = flatten(png, vector) ?: throw TransferException(TransferError.CORRUPT)
                if (format.key == JPEG.key) toJpeg(flat) else flat
            }
        }
        NoteType.AUDIO -> {
            // The recordings in the note's order; several go in a zip as "<note title> - <recording name>.m4a" (Android D112).
            val clips = audioFiles(note)
            if (clips.isEmpty()) throw TransferException(TransferError.CORRUPT)
            if (format.key != ZIP_CLIPS.key) {
                clips.first().second.readBytes()
            } else {
                val names = AudioClipNames.exportNames(note.title, clips.map { it.first })
                val out = ByteArrayOutputStream()
                ZipOutputStream(out).use { zip ->
                    clips.forEachIndexed { index, (_, file) ->
                        zip.putNextEntry(ZipEntry(names[index]))
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
                out.toByteArray()
            }
        }
        else -> {
            // Photo / video / screen notes: the file itself, or all of them in a zip as "001-<name>.<ext>" (Android MediaTransferHandler).
            val kind = com.notio.shared.domain.MediaKind.of(note.type) ?: throw TransferException(TransferError.CORRUPT)
            val all = mediaFiles(note, kind)
            if (all.isEmpty()) throw TransferException(TransferError.CORRUPT)
            if (format.extension != "zip") {
                all.first().second.readBytes()
            } else {
                val out = ByteArrayOutputStream()
                ZipOutputStream(out).use { zip ->
                    all.forEachIndexed { index, (item, file) ->
                        val name = ExportNaming.safe(item.name).ifBlank { kind.name.lowercase() }
                        zip.putNextEntry(ZipEntry("%03d-%s.%s".format(index + 1, name, kind.extension)))
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
                out.toByteArray()
            }
        }
    }

    /**
     * Makes a note from [bytes] in [folderId]: our structured JSON (any kind), Markdown or text (a TEXT note), PNG or JPEG (a drawing),
     * MPEG-4 audio (a recording). The note always gets a new id (INV-12).
     * @throws TransferException when the folder is missing or no kind recognises the file.
     */
    suspend fun import(fileName: String, bytes: ByteArray, folderId: String): Note {
        db.folders().firstOrNull { it.id == folderId && it.deletedAt == null } ?: throw TransferException(TransferError.FOLDER_NOT_FOUND)
        val structured = StructuredNoteFile.decode(bytes)
        val baseTitle = fileName.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.').ifBlank { "Note" }
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val now = clock.now()
        val fresh = Note(
            id = ids.newId(), folderId = folderId, title = baseTitle, type = NoteType.TEXT, colorHex = null, isPinned = false,
            isFavorite = false, isTitleBold = false, contentText = null, createdAt = now, updatedAt = now,
            sortOrder = (db.notesOf(folderId).maxOfOrNull { it.sortOrder } ?: -1) + 1,
        )
        val note: Note
        var attachment: Pair<String, ByteArray>? = null
        when {
            structured != null -> {
                val r = structured.note
                note = fresh.copy(
                    title = r.title.trim().ifBlank { "Note" }, type = NoteType(r.type), colorHex = r.colorHex,
                    isTitleBold = r.isTitleBold, contentText = r.contentText, extras = r.extras, comment = Comment.clean(r.comment),
                )
            }
            ext in setOf("md", "markdown", "txt") && bytes.none { it == 0.toByte() } ->
                note = fresh.copy(contentText = bytes.toString(Charsets.UTF_8).removePrefix("﻿"))
            isImage(bytes) && bytes.size <= MAX_IMAGE_BYTES -> {
                note = fresh.copy(type = NoteType.DRAWING)
                attachment = DRAWING_FILE to bytes
            }
            isMpeg4(bytes) && bytes.size <= MAX_AUDIO_BYTES -> {
                note = fresh.copy(type = NoteType.AUDIO)
                attachment = AUDIO_FILE to bytes
            }
            else -> throw TransferException(TransferError.UNRECOGNIZED_FILE)
        }
        db.upsertNote(note)
        try {
            if (structured?.note?.tags?.isNotEmpty() == true) db.assignTags(note.id, structured.note.tags, now)
            attachment?.let { (name, data) -> files.write(note.id, name, data.inputStream()) }
            when (note.type) {
                NoteType.BUDGET -> restoreBudget(note, structured?.payload)
                NoteType.DRAWING, NoteType.BLUEPRINT -> structured?.payload?.let { restoreDrawing(note.id, it) }
            }
        } catch (e: Exception) {
            runCatching { files.deleteNote(note.id); db.deleteNote(note.id) }
            throw if (e is TransferException) e else TransferException(TransferError.CORRUPT, e)
        }
        return note
    }

    /**
     * A copy of the note in the same folder (Android D179): a free "_clone N" name, not pinned, new dates, placed last, with its own copy
     * of the budget tree (new ids, totals recalculated), of its files and of its tags. Reminders are not copied, as on the phone.
     */
    fun clone(noteId: String, suffixFormat: String): Note {
        val original = db.note(noteId)?.takeIf { it.deletedAt == null } ?: throw TransferException(TransferError.CORRUPT)
        val inFolder = db.notesOf(original.folderId)
        val now = clock.now()
        val copy = original.copy(
            id = ids.newId(),
            title = CloneNames.next(original.title, inFolder.map { it.title }, suffixFormat),
            isPinned = false, pinnedAt = 0, isFavorite = false, createdAt = now, updatedAt = now, deletedAt = null,
            sortOrder = (inFolder.maxOfOrNull { it.sortOrder } ?: -1) + 1, tags = emptyList(),
        )
        try {
            db.transaction {
                db.upsertNote(copy)
                if (copy.type == NoteType.BUDGET) restoreBudget(copy, budgetPayload(original.id))
                val tags = db.tagsOf(original.id).map { it.id }.toSet()
                if (tags.isNotEmpty()) db.setNoteTags(copy.id, tags)
            }
            for (file in files.list(original.id)) file.inputStream().use { files.write(copy.id, file.name, it) }
        } catch (e: Exception) {
            runCatching { files.deleteNote(copy.id); db.deleteNote(copy.id) }
            throw TransferException(TransferError.CORRUPT, e)
        }
        return copy
    }

    /** The clips of an audio note that have their file, in the note's order (a note from before D112 has the one `audio.m4a`). */
    private fun audioFiles(note: Note): List<Pair<com.notio.shared.domain.AudioClip, java.io.File>> {
        val doc = com.notio.shared.domain.AudioDocument.forNote(note.extras, files.file(note.id, AUDIO_FILE) != null)
        return doc.clips.mapNotNull { clip ->
            val name = if (clip.id == com.notio.shared.domain.AudioClip.LEGACY_ID) AUDIO_FILE else "clip-" + clip.id + ".m4a"
            runCatching { files.file(note.id, name) }.getOrNull()?.let { clip to it }
        }
    }

    // ---- budget ----

    private fun budgetPayload(noteId: String): JsonObject {
        val records = db.budgetNodesOf(noteId).map { it.toRecord() }
        return JsonObject(mapOf("nodes" to TransferFormat.json.encodeToJsonElement(ListSerializer(BudgetNodeRecord.serializer()), records)))
    }

    /** The tree of a file with new ids and every total recalculated (INV-13), so a file with wrong totals cannot corrupt it. */
    private fun restoreBudget(note: Note, payload: JsonObject?) {
        val raw = (payload?.get("nodes") as? JsonArray)?.let {
            TransferFormat.json.decodeFromJsonElement(ListSerializer(BudgetNodeRecord.serializer()), it)
        }.orEmpty().map { it.toDomain() }
        val newIds = raw.associate { it.id to ids.newId() }
        val now = clock.now()
        var nodes = raw.map { it.copy(id = newIds.getValue(it.id), noteId = note.id, parentId = it.parentId?.let(newIds::get)) }
        val roots = nodes.filter { it.parentId == null }
        if (roots.size > 1) throw TransferException(TransferError.CORRUPT)
        if (roots.isEmpty()) {
            nodes = listOf(
                BudgetNode(
                    id = ids.newId(), noteId = note.id, parentId = null, title = note.title, kind = BudgetNodeKind.GROUP,
                    manualSumMinor = 0, manualGoalMinor = 0, comment = null, cachedSumMinor = 0, cachedGoalMinor = 0,
                    cachedRemainderMinor = 0, isExpanded = true, sortOrder = 0, createdAt = now, updatedAt = now,
                ),
            )
        }
        nodes = nodes.map { if (it.parentId == null) it.copy(kind = BudgetNodeKind.GROUP) else it }
        val byId = nodes.associateBy { it.id }
        val valid = nodes.filter { n -> n.parentId == null || byId[n.parentId]?.kind == BudgetNodeKind.GROUP }
        val children = valid.groupBy { it.parentId }
        val reachable = ArrayList<BudgetNode>()
        val seen = HashSet<String>()
        fun walk(n: BudgetNode) {
            if (seen.add(n.id)) { reachable += n; children[n.id].orEmpty().forEach(::walk) }
        }
        children[null].orEmpty().forEach(::walk)
        db.transaction { BudgetCalculator.recalculateAll(reachable).forEach(db::upsertNode) }
    }

    /** Indented, locale-independent text: `Name: sum 10.00 / goal 20.00 / remainder 10.00` (the phone's snapshot). */
    fun budgetSnapshot(nodes: List<BudgetNode>, currency: String? = null): String {
        val byParent = nodes.groupBy { it.parentId }
        val lines = ArrayList<String>()
        fun money(minor: Long) = BigDecimal.valueOf(minor, 2).toPlainString() + if (currency.isNullOrEmpty()) "" else " $currency"
        fun walk(n: BudgetNode, depth: Int) {
            val name = if (n.kind == BudgetNodeKind.LEAF && n.quantity > 1) "${n.title} x${n.quantity}" else n.title
            lines += "  ".repeat(depth) + (if (depth == 0) "" else "- ") +
                "$name: sum ${money(n.cachedSumMinor)} / goal ${money(n.cachedGoalMinor)} / remainder ${money(n.cachedRemainderMinor)}"
            n.comment?.let { lines += "  ".repeat(depth + 1) + it }
            byParent[n.id].orEmpty().sortedBy { it.sortOrder }.forEach { walk(it, depth + 1) }
        }
        byParent[null].orEmpty().forEach { walk(it, 0) }
        return lines.joinToString("\n")
    }

    // ---- drawing ----

    /** The layers for a structured file: the raster PNG as base64 and the vector document as JSON (the phone's "Layers"). */
    private fun drawingPayload(noteId: String): JsonObject {
        val fields = LinkedHashMap<String, JsonElement>()
        read(noteId, DRAWING_FILE)?.let { fields["raster"] = JsonPrimitive(Base64.getEncoder().encodeToString(it)) }
        read(noteId, VECTOR_FILE)?.let { bytes ->
            runCatching { Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) }.getOrNull()?.let { fields["vector"] = it }
        }
        return JsonObject(fields)
    }

    private fun restoreDrawing(noteId: String, payload: JsonObject) {
        val raster = (payload["raster"] as? JsonPrimitive)?.contentOrNull?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
        if (raster != null && isImage(raster)) files.write(noteId, DRAWING_FILE, raster.inputStream())
        val vector = payload["vector"]?.toString()?.toByteArray(Charsets.UTF_8)
        if (vector != null && vectorIsValid(vector)) files.write(noteId, VECTOR_FILE, vector.inputStream())
    }

    private fun read(noteId: String, name: String): ByteArray? = runCatching { files.file(noteId, name)?.readBytes() }.getOrNull()

    private fun isImage(b: ByteArray): Boolean =
        (b.size > 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte()) ||
            (b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte())

    /** An ISO base media file starts with a box whose type at offset 4 is ftyp. */
    private fun isMpeg4(b: ByteArray): Boolean =
        b.size > 12 && b[4] == 'f'.code.toByte() && b[5] == 't'.code.toByte() && b[6] == 'y'.code.toByte() && b[7] == 'p'.code.toByte()

    private fun safeFileName(title: String): String = ExportNaming.safe(title).ifBlank { "note" }.take(80)

    companion object {
        val MARKDOWN = ExportFormat("md", "export_format_markdown", "md", "text/markdown")
        val STRUCTURE = ExportFormat(StructuredNoteFile.KEY, "export_format_structure", StructuredNoteFile.EXTENSION, StructuredNoteFile.MIME)
        val SNAPSHOT_MD = ExportFormat("snapshot", "export_format_snapshot", "md", "text/markdown")
        val SNAPSHOT_TXT = ExportFormat("snapshot", "export_format_snapshot", "txt", "text/plain")
        val PNG = ExportFormat("png", "export_format_png", "png", "image/png")
        val JPEG = ExportFormat("jpeg", "export_format_jpeg", "jpg", "image/jpeg")
        val TXT = ExportFormat("txt", "export_format_txt", "txt", "text/plain")
        val PDF = ExportFormat("pdf", "export_format_pdf", "pdf", "application/pdf")
        val M4A = ExportFormat("m4a", "export_format_audio", "m4a", "audio/mp4")
        val ZIP_CLIPS = ExportFormat("zip", "export_format_audio_zip", "zip", "application/zip")
        val MEDIA_ZIP = ExportFormat("zip", "export_format_media_zip", "zip", "application/zip")
        val LAYERS = ExportFormat(StructuredNoteFile.KEY, "export_format_layers", StructuredNoteFile.EXTENSION, StructuredNoteFile.MIME)

        const val DRAWING_FILE = "drawing.png"
        const val VECTOR_FILE = "vector.json"
        const val AUDIO_FILE = "audio.m4a"
        private const val MAX_IMAGE_BYTES = 30 * 1024 * 1024
        /** The biggest file an import can make sense of: an audio clip. Bigger ones are refused before they are read. */
        const val MAX_IMPORT_BYTES = 200 * 1024 * 1024
        private const val MAX_AUDIO_BYTES = MAX_IMPORT_BYTES
    }
}

/** File names of the recordings of a note when they are exported together (Android D112). */
object AudioClipNames {
    /**
     * "<note title> - <clip name>.m4a" for each clip, in order. A clip without a name is called "recording N"; two clips with the same
     * name get " (2)", " (3)" so no file overwrites another.
     */
    fun exportNames(noteTitle: String, clips: List<com.notio.shared.domain.AudioClip>): List<String> {
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
