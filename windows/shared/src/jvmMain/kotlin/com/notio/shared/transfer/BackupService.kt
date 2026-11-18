package com.notio.shared.transfer

import com.notio.shared.AppInfo
import com.notio.shared.data.AttachmentStore
import com.notio.shared.data.NotioDatabase
import com.notio.shared.data.SettingsStore
import com.notio.shared.domain.Clock
import com.notio.shared.domain.IdGenerator
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** [filesRestored] / [filesSkipped]: the photos, videos, recordings and drawings put back, and the ones that could not be. */
data class ImportReport(val stats: ImportStats, val mode: ImportMode, val filesRestored: Int = 0, val filesSkipped: Int = 0)

/** What an export wrote: files put into the backup and files left out because of a size limit. */
data class BackupExportReport(val filesWritten: Int, val filesSkipped: Int)

/**
 * Full backup and restore, the desktop side of the Android `BackupService` (Android §6.13, D134, D174; AGENT.md D07):
 * the same ZIP (`backup.json` + `attachments/<noteId>/<name>`), the same password wrapper, the same import modes and
 * planner. A backup written here opens on the phone and the other way round.
 */
class BackupService(
    private val db: NotioDatabase,
    private val files: AttachmentStore,
    private val settings: SettingsStore,
    private val clock: Clock = Clock.System,
    private val ids: IdGenerator = IdGenerator.Random,
    private val appVersion: String = "desktop-" + AppInfo.VERSION,
) {

    /** e.g. `notebudget-backup-2026-09-24.json.zip`; a protected backup ends in `.enc`, like on Android. */
    fun suggestedFileName(protectedFile: Boolean = false): String =
        "notebudget-backup-" + SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(clock.now())) +
            if (protectedFile) ".json.zip.enc" else ".json.zip"

    /** Writes the backup; with [password] the whole file is encrypted, `null` writes the ordinary ZIP. [folderIds]: only these. */
    fun export(out: OutputStream, password: String? = null, folderIds: Set<String>? = null, withReminders: Boolean = true): BackupExportReport {
        val target = if (password.isNullOrEmpty()) out else BackupCrypto.encrypt(out, password)
        // A sync without the reminders (D319): they are left out of what is sent.
        val data = db.readAll().let { if (withReminders) it else it.copy(reminders = emptyList()) }.let { all ->
            if (folderIds == null) all else {
                val notes = all.notes.filter { it.folderId in folderIds }
                val noteIds = notes.mapTo(HashSet()) { it.id }
                BackupData(
                    folders = all.folders.filter { it.id in folderIds },
                    notes = notes,
                    nodes = all.nodes.filter { it.noteId in noteIds },
                    reminders = all.reminders.filter { it.noteId in noteIds },
                )
            }
        }
        val kept = data.notes.mapTo(HashSet()) { it.id }
        val tags = db.tagAssignments()
        val attachments = data.notes.flatMap { note ->
            files.list(note.id).map { f -> AttachmentEntry(BackupCodec.attachmentPath(note.id, f.name), f.length()) { f.inputStream() } }
        }
        val file = BackupFile(
            createdAt = clock.now(),
            appVersion = appVersion,
            folders = data.folders.map { it.toRecord() },
            notes = data.notes.map { n -> n.copy(tags = tags[n.id].orEmpty().map { it.name }).toRecord() },
            budgetNodes = data.nodes.map { it.toRecord() },
            reminders = data.reminders.map { it.toRecord() },
            settings = settings.read(),
            mediaTrash = db.mediaTrash().filter { folderIds == null || it.noteId in kept },
            budgetLog = db.budgetLog().filter { folderIds == null || it.noteId in kept },
        )
        val result = BackupCodec.writeStreaming(target, file, attachments)
        if (target !== out) target.close()
        return BackupExportReport(result.written, result.skipped)
    }

    /** Whether [file] is a password-protected backup: the caller then asks for the password. */
    fun isProtected(file: File): Boolean = file.inputStream().use { BackupCrypto.peek(it).second }

    /**
     * The reminders an import brings. A sync without them (D319) must leave the reminders of this device as they are, and an
     * updated note takes the reminders that come with it in place of its own: so the note's own are given back to it. Copies
     * (new ids) are new notes and get none.
     */
    private fun remindersToImport(
        sent: List<com.notio.shared.domain.Reminder>, current: BackupData, incomingNoteIds: Set<String>, mode: ImportMode, withReminders: Boolean,
    ): List<com.notio.shared.domain.Reminder> = when {
        withReminders -> sent
        mode == ImportMode.NEW_IDS -> emptyList()
        else -> current.reminders.filter { it.noteId in incomingNoteIds }
    }

    fun importFile(file: File, mode: ImportMode, password: String? = null): ImportReport =
        importFrom({ file.inputStream() }, mode, password)

    private fun openPlain(open: () -> InputStream, password: String?): InputStream {
        val raw = open()
        val (stream, protected) = BackupCrypto.peek(raw)
        return when {
            !protected -> stream
            password.isNullOrEmpty() -> { runCatching { raw.close() }; throw TransferException(TransferError.PASSWORD_REQUIRED) }
            else -> BackupCrypto.decrypt(stream, password)
        }
    }

    /**
     * Imports a backup in two passes over [open], like Android D174: the manifest first (applied all or nothing), then the
     * files one by one to disk. A file that cannot be restored is counted, not thrown.
     * [replaceFolders] with [ImportMode.REPLACE]: a replacement of these folders only (Android D325, [ImportPlanner.planFolders]) —
     * the rest of this device, its settings included, stays as it is.
     * @throws TransferException with the reason when the file cannot be imported.
     */
    fun importFrom(
        open: () -> InputStream, mode: ImportMode, password: String? = null, withReminders: Boolean = true, replaceFolders: Set<String>? = null,
    ): ImportReport {
        val file = openPlain(open, password).use { BackupCodec.readManifest(it) }
        val current = db.readAll()
        val incomingNotes = file.notes.map { it.toDomain() }
        val incoming = BackupData(
            folders = file.folders.map { it.toDomain() },
            notes = incomingNotes,
            nodes = file.budgetNodes.map { it.toDomain() },
            reminders = remindersToImport(file.reminders.map { it.toDomain() }, current, incomingNotes.mapTo(HashSet()) { it.id }, mode, withReminders),
        )
        val folderScope = replaceFolders?.takeIf { mode == ImportMode.REPLACE }
        val planned = if (folderScope != null) ImportPlanner.planFolders(current, incoming, folderScope, clock.now())
        else ImportPlanner.plan(current, incoming, mode, ids, clock.now())
        // What follows the notes (the tags, the trash of media, the logs, the settings) goes as in a merge then.
        val after = if (folderScope != null) ImportMode.MERGE else mode
        try {
            db.apply(planned.plan)
        } catch (e: Exception) {
            throw if (e is TransferException) e else TransferException(TransferError.CORRUPT, e)
        }
        if (planned.plan.clearAll) files.clearAll()

        val finalNotes = planned.plan.notes.associateBy { it.id }
        var restored = 0
        var skipped = 0
        if (finalNotes.isNotEmpty()) {
            try {
                BackupCodec.AttachmentReader(openPlain(open, password)).use { reader ->
                    while (true) {
                        val item = reader.next() ?: break
                        // The id in the file is only a key into the map of the import, never a path.
                        val noteId = planned.noteIds[item.noteId]?.takeIf { it in finalNotes } ?: continue
                        val ok = try {
                            files.write(noteId, item.name, item.stream)
                            true
                        } catch (e: Exception) {
                            false
                        }
                        if (ok) restored++ else skipped++
                    }
                }
            } catch (e: Exception) {
                // A damaged tail of the file: what was read is kept, the rest is reported as missing.
                skipped++
            }
        }

        // Tags come back by name.
        val byOldId = file.notes.associateBy { it.id }
        for ((oldId, newId) in planned.noteIds) {
            val names = byOldId[oldId]?.tags.orEmpty()
            if (names.isNotEmpty() && newId in finalNotes) db.assignTags(newId, names, clock.now())
        }
        if (after == ImportMode.REPLACE) db.deleteUnusedTags()

        // The media trash comes back as a list (its files are never in a backup); with new ids it is not applied.
        when (after) {
            ImportMode.REPLACE -> db.replaceMediaTrash(file.mediaTrash)
            ImportMode.MERGE -> db.addMediaTrash(file.mediaTrash)
            ImportMode.NEW_IDS -> Unit
        }

        // The logs of the budget notes follow their notes.
        if (file.budgetLog.isNotEmpty() || after == ImportMode.REPLACE) {
            val present = db.notes().mapTo(HashSet()) { it.id }
            val rows = when (after) {
                ImportMode.NEW_IDS -> file.budgetLog.mapNotNull { r ->
                    val newNote = planned.noteIds[r.noteId] ?: return@mapNotNull null
                    // A copy gets new ids for its items too, so its lines no longer point to one: they cannot be undone (Android D282).
                    if (newNote in present) r.copy(id = ids.newId(), noteId = newNote, nodeId = null) else null
                }
                else -> file.budgetLog.filter { it.noteId in present }
            }
            if (after == ImportMode.REPLACE) db.replaceBudgetLog(rows) else db.addBudgetLog(rows)
        }
        if (after == ImportMode.REPLACE) file.settings?.let { settings.write(it) }
        return ImportReport(planned.stats, mode, restored, skipped)
    }
}
