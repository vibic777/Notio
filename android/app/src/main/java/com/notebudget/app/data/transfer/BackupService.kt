package com.notebudget.app.data.transfer

import com.notebudget.app.core.common.Clock
import com.notebudget.app.core.common.IdGenerator
import com.notebudget.app.domain.model.NoteType
import com.notebudget.app.domain.repository.ReminderScheduler
import com.notebudget.app.domain.repository.TagRepository
import com.notebudget.app.domain.usecase.TagUseCases
import com.notebudget.app.domain.usecase.ReminderUseCases
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Where the transfer code finds the handler of a note kind. Implemented by the type registry. */
interface NoteHandlerLookup {
    /** `null` for a kind this version does not know; such notes are transferred as plain data. */
    fun handlerFor(type: NoteType): NoteTransferHandler?

    val allHandlers: Collection<NoteTransferHandler>
}

/**
 * The password that protects exported backups (D134). It is kept so that it can be shown again, hence stored reversibly
 * (encrypted by the phone's Keystore) and never inside a backup or a log.
 */
data class BackupPasswordSettings(val enabled: Boolean = false, val password: String = "") {
    /** The password to protect an export with, or `null` when exports are not protected. */
    val active: String? get() = password.takeIf { enabled && it.isNotEmpty() }
}

interface BackupPasswordStore {
    suspend fun load(): BackupPasswordSettings
    suspend fun save(settings: BackupPasswordSettings)
}

/** Non-secret settings that travel in a backup (INV-11). */
interface SettingsPort {
    suspend fun read(): SettingsRecord
    suspend fun write(settings: SettingsRecord)
}

/** [filesRestored] / [filesSkipped]: the photos, videos, recordings and drawings put back, and the ones that could not be (D174). */
data class ImportReport(val stats: ImportStats, val mode: ImportMode, val filesRestored: Int = 0, val filesSkipped: Int = 0)

/** What an export wrote: files put into the backup and files left out because of a size limit (D174). */
data class BackupExportReport(val filesWritten: Int, val filesSkipped: Int)

/**
 * Full backup and restore (§6.13): a ZIP with `backup.json` and the files kept outside the
 * database. Contains no secrets (INV-11). Import modes: [ImportMode].
 */
class BackupService(
    private val store: BackupStore,
    private val handlers: NoteHandlerLookup,
    private val scheduler: ReminderScheduler,
    private val reminders: ReminderUseCases,
    private val settings: SettingsPort,
    private val appVersion: String,
    private val clock: Clock = Clock.System,
    private val ids: IdGenerator = IdGenerator.Random,
    private val tagRepository: TagRepository? = null,
    private val tagUseCases: TagUseCases? = null,
    private val passwords: BackupPasswordStore? = null,
    private val mediaTrash: com.notebudget.app.domain.usecase.MediaTrashUseCases? = null,
    private val budgetLog: com.notebudget.app.domain.model.BudgetLogRepository? = null,
) {

    /** The password the person set for exports, when protection is switched on. */
    suspend fun exportPassword(): String? = passwords?.load()?.active

    /** The saved password, switched on or not: an import tries it first on a protected file. */
    suspend fun savedPassword(): String? = passwords?.load()?.password?.takeIf { it.isNotEmpty() }

    /** e.g. `notebudget-backup-2026-09-20.json.zip`; a protected backup ends in `.enc` (it is not a ZIP any more). */
    fun suggestedFileName(protectedFile: Boolean = false): String =
        "notebudget-backup-" + SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(clock.now())) +
            if (protectedFile) ".json.zip.enc" else ".json.zip"

    /**
     * Writes the backup; with [password] the whole file is encrypted (D134), `null` writes the ordinary ZIP. The files are copied one
     * by one from disk (D174), so a backup of any size needs no more memory than a small one; it only takes longer.
     * [onProgress] is told how many files have been written.
     */
    suspend fun export(
        out: OutputStream,
        password: String? = null,
        folderIds: Set<String>? = null,
        withReminders: Boolean = true,
        onProgress: (files: Int, total: Int) -> Unit = { _, _ -> },
    ): BackupExportReport {
        val target = if (password.isNullOrEmpty()) out else BackupCrypto.encrypt(out, password)
        // [folderIds]: only these folders and what is in them (a sync of chosen folders, D226); `null` = everything.
        // A sync without the reminders (D319): they are left out of what is sent.
        val data = store.readAll().let { if (withReminders) it else it.copy(reminders = emptyList()) }.let { all ->
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
        val keptNotes = data.notes.mapTo(HashSet()) { it.id }
        val tagsByNote = tagRepository?.getAssignments().orEmpty()
        val attachments = ArrayList<AttachmentEntry>()
        for (note in data.notes) {
            for (source in handlers.handlerFor(note.type)?.backupAttachmentSources(note).orEmpty()) {
                attachments += AttachmentEntry(BackupCodec.attachmentPath(note.id, source.name), source.size, source.open)
            }
        }
        val file = BackupFile(
            createdAt = clock.now(),
            appVersion = appVersion,
            folders = data.folders.map { it.toRecord() },
            notes = data.notes.map { note -> note.copy(tags = tagsByNote[note.id].orEmpty().map { it.name }).toRecord() },
            budgetNodes = data.nodes.map { it.toRecord() },
            reminders = data.reminders.map { it.toRecord() },
            settings = settings.read(),
            mediaTrash = mediaTrash?.all().orEmpty().filter { folderIds == null || it.noteId in keptNotes }.map { it.toRecord() },
            budgetLog = budgetLog?.getAll().orEmpty().filter { folderIds == null || it.noteId in keptNotes }.map { it.toRecord() },
        )
        val result = BackupCodec.writeStreaming(target, file, attachments) { done -> onProgress(done, attachments.size) }
        // The wrapper writes its last chunk when it is closed; the plain ZIP stream is closed by the caller.
        if (target !== out) target.close()
        return BackupExportReport(result.written, result.skipped)
    }

    /**
     * Imports a backup from a one-way stream. The stream is first copied to a temporary file (on disk, not in memory), because the
     * import reads the file twice (see [importFrom]). When the source can be opened again, prefer [importFrom].
     * @throws TransferException with the reason when the file cannot be imported.
     */
    suspend fun import(
        input: InputStream,
        mode: ImportMode,
        password: String? = null,
        onProgress: (files: Int) -> Unit = {},
    ): ImportReport {
        val temp = File.createTempFile("notio-import-", ".tmp")
        try {
            temp.outputStream().use { input.copyTo(it, 64 * 1024) }
            return importFrom({ temp.inputStream() }, mode, password, onProgress = onProgress)
        } finally {
            temp.delete()
        }
    }

    private fun openPlain(open: () -> InputStream?, password: String?): InputStream {
        val raw = open() ?: throw TransferException(TransferError.NOT_A_BACKUP)
        val (stream, protected) = BackupCrypto.peek(raw)
        return when {
            !protected -> stream
            password.isNullOrEmpty() -> { runCatching { raw.close() }; throw TransferException(TransferError.PASSWORD_REQUIRED) }
            else -> BackupCrypto.decrypt(stream, password)
        }
    }

    /**
     * Imports a backup by streaming (D174): [open] is called twice. The first pass reads only the manifest (the notes, folders,
     * budgets ...) and applies it as before, all or nothing; the second pass copies the files of the backup (photos, videos,
     * recordings, drawings) one by one to disk, so a backup of any size needs no more memory than a small one; it takes longer.
     * A file that cannot be restored (damaged, not what it claims, too big) is skipped and counted in the report, and the rest goes on.
     * [replaceFolders] with [ImportMode.REPLACE]: a replacement of these folders only (D325, [ImportPlanner.planFolders]) —
     * the rest of this device, its settings included, stays as it is.
     * @throws TransferException with the reason when the file cannot be imported.
     */
    suspend fun importFrom(
        open: () -> InputStream?,
        mode: ImportMode,
        password: String? = null,
        withReminders: Boolean = true,
        replaceFolders: Set<String>? = null,
        onProgress: (files: Int) -> Unit = {},
    ): ImportReport {
        val file = openPlain(open, password).use { BackupCodec.readManifest(it) }
        val current = store.readAll()
        val incomingNotes = file.notes.map { it.toDomain() }
        val incomingNoteIds = incomingNotes.mapTo(HashSet()) { it.id }
        val incoming = BackupData(
            folders = file.folders.map { it.toDomain() },
            notes = incomingNotes,
            nodes = file.budgetNodes.map { it.toDomain() },
            // A sync without the reminders (D319) must leave the reminders of this device as they are, and an updated note takes
            // the reminders that come with it in place of its own: so the note's own are given back to it. Copies get none.
            reminders = when {
                withReminders -> file.reminders.map { it.toDomain() }
                mode == ImportMode.NEW_IDS -> emptyList()
                else -> current.reminders.filter { it.noteId in incomingNoteIds }
            },
        )
        val folderScope = replaceFolders?.takeIf { mode == ImportMode.REPLACE }
        val planned = if (folderScope != null) ImportPlanner.planFolders(current, incoming, folderScope, clock.now())
        else ImportPlanner.plan(current, incoming, mode, ids, clock.now())
        // What follows the notes (the tags, the trash of media, the logs, the settings) goes as in a merge then.
        val after = if (folderScope != null) ImportMode.MERGE else mode

        // Alarms of reminders that are about to disappear must not outlive them.
        if (planned.plan.clearAll) current.reminders.forEach { scheduler.cancel(it.id) }
        // The notes a replacement of folders puts into the trash (D325): their reminders are not active there.
        if (folderScope != null) {
            val trashed = planned.plan.notes.filter { it.deletedAt != null }.mapTo(HashSet()) { it.id }
            current.reminders.filter { it.noteId in trashed }.forEach { scheduler.cancel(it.id) }
        }
        try {
            store.apply(planned.plan)
        } catch (e: Exception) {
            reminders.restoreAll()
            throw if (e is TransferException) e else TransferException(TransferError.CORRUPT, e)
        }

        if (planned.plan.clearAll) handlers.allHandlers.forEach { it.clearAttachments() }
        val finalNotes = planned.plan.notes.associateBy { it.id }
        var restored = 0
        var skipped = 0
        if (finalNotes.isNotEmpty()) {
            // The files come after the database: whatever goes wrong here leaves the notes in place and is counted, not thrown.
            try {
                BackupCodec.AttachmentReader(openPlain(open, password)).use { reader ->
                    while (true) {
                        val item = reader.next() ?: break
                        // The id in the file is only a key into the map of the import, never a path.
                        val note = planned.noteIds[item.noteId]?.let { finalNotes[it] } ?: continue
                        val stored = try {
                            handlers.handlerFor(note.type)?.restoreBackupAttachment(note, item.name, item.stream) ?: false
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            false
                        }
                        if (stored) restored++ else skipped++
                        onProgress(restored + skipped)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // A damaged tail of the file: what was read is kept, the rest is reported as missing.
                skipped++
            }
        }
        restoreTags(file.notes, planned.noteIds, finalNotes.keys, after)
        // The trash of media items comes back as a list (its files are never in a backup): an item can be restored while its
        // file is still on this phone. With new ids the notes are other notes, so the list is not applied (D156).
        if (file.mediaTrash.isNotEmpty() || after == ImportMode.REPLACE) {
            val rows = file.mediaTrash.map { it.toDomain() }
            when (after) {
                ImportMode.REPLACE -> mediaTrash?.replaceFromBackup(rows)
                ImportMode.MERGE -> mediaTrash?.mergeFromBackup(rows)
                ImportMode.NEW_IDS -> Unit
            }
        }
        restoreBudgetLog(file.budgetLog, planned.noteIds, after)
        if (after == ImportMode.REPLACE) file.settings?.let { settings.write(it) }
        reminders.restoreAll()
        return ImportReport(planned.stats, mode, filesRestored = restored, filesSkipped = skipped)
    }

    /** Whether the file is a password-protected backup (D134): the caller then asks for the password. */
    fun isProtected(input: InputStream): Pair<InputStream, Boolean> = BackupCrypto.peek(input)

    /**
     * The logs of the budget notes come back with their notes (D166): a full restore replaces every log, a merge adds the lines that
     * are missing, "new ids" gives the lines to the copies of their notes. A line whose note is not there is dropped.
     */
    private suspend fun restoreBudgetLog(records: List<BudgetLogRecord>, noteIds: Map<String, String>, mode: ImportMode) {
        val repo = budgetLog ?: return
        if (records.isEmpty() && mode != ImportMode.REPLACE) return
        val present = store.readAll().notes.map { it.id }.toSet()
        val rows = when (mode) {
            ImportMode.NEW_IDS -> records.mapNotNull { r ->
                val newNote = noteIds[r.noteId] ?: return@mapNotNull null
                // A copy gets new ids for its items too, so its lines no longer point to one: they cannot be undone (D282).
                if (newNote in present) r.toDomain().copy(id = ids.newId(), noteId = newNote, nodeId = null) else null
            }
            else -> records.filter { it.noteId in present }.map { it.toDomain() }
        }
        when (mode) {
            ImportMode.REPLACE -> repo.replaceAll(rows)
            else -> repo.add(rows)
        }
    }

    /** Puts the tags of the imported notes back by name; a full restore first drops tags nobody has any more. */
    private suspend fun restoreTags(records: List<NoteRecord>, noteIds: Map<String, String>, finalIds: Set<String>, mode: ImportMode) {
        val useCases = tagUseCases ?: return
        val byOldId = records.associateBy { it.id }
        for ((oldId, newId) in noteIds) {
            val names = byOldId[oldId]?.tags.orEmpty()
            if (names.isNotEmpty() && newId in finalIds) useCases.assignByNames(newId, names)
        }
        if (mode == ImportMode.REPLACE) tagRepository?.deleteUnused()
    }
}
