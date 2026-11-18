package com.notio.shared.transfer

import com.notio.shared.domain.IdGenerator
import com.notio.shared.domain.BudgetNode
import com.notio.shared.domain.BudgetNodeKind
import com.notio.shared.domain.Folder
import com.notio.shared.domain.Note
import com.notio.shared.domain.NoteType
import com.notio.shared.domain.Reminder
import com.notio.shared.domain.BudgetCalculator

/** How to treat data that already exists in the app (D63). */
enum class ImportMode {
    /** Keep everything; for the same id the newer `updatedAt` wins. */
    MERGE,

    /** Delete everything first, then load the backup. */
    REPLACE,

    /** Load everything as new copies with fresh ids. */
    NEW_IDS,
}

/** A whole database worth of data (trashed items included). */
data class BackupData(
    val folders: List<Folder>,
    val notes: List<Note>,
    val nodes: List<BudgetNode>,
    val reminders: List<Reminder>,
)

/** What the store must do, atomically. */
data class ImportPlan(
    val clearAll: Boolean,
    val folders: List<Folder>,
    val notes: List<Note>,
    /** Notes whose budget nodes are replaced by [nodes]. */
    val replaceNodesOf: Set<String>,
    val nodes: List<BudgetNode>,
    val reminders: List<Reminder>,
)

data class ImportStats(
    val foldersAdded: Int = 0,
    /** Incoming folders that were folded into an existing folder of the same name. */
    val foldersMerged: Int = 0,
    val notesAdded: Int = 0,
    val notesUpdated: Int = 0,
    val notesSkipped: Int = 0,
)

class PlannedImport(
    val plan: ImportPlan,
    val stats: ImportStats,
    /** Incoming note id -> id it is stored under. Skipped notes are absent. */
    val noteIds: Map<String, String>,
)

/**
 * Pure planning of an import: decides, from the current data and the incoming data, what is
 * inserted, updated or skipped. No I/O, so every rule is unit-tested. INV-1, INV-3, INV-13.
 */
object ImportPlanner {

    fun plan(
        current: BackupData,
        incoming: BackupData,
        mode: ImportMode,
        ids: IdGenerator = IdGenerator.Random,
        now: Long = System.currentTimeMillis(),
    ): PlannedImport {
        val clean = sanitize(incoming, now)
        return when (mode) {
            ImportMode.REPLACE -> replace(clean)
            ImportMode.MERGE -> merge(current, clean, ids, freshIds = false)
            ImportMode.NEW_IDS -> merge(current, clean, ids, freshIds = true)
        }
    }

    // ---- REPLACE ----

    private fun replace(data: BackupData): PlannedImport {
        if (data.folders.none { it.deletedAt == null }) throw TransferException(TransferError.NO_FOLDERS)
        val plan = ImportPlan(
            clearAll = true,
            folders = fixFeatured(data.folders, data.notes),
            notes = data.notes,
            replaceNodesOf = emptySet(),
            nodes = data.nodes,
            reminders = data.reminders,
        )
        return PlannedImport(
            plan,
            ImportStats(foldersAdded = data.folders.size, notesAdded = data.notes.size),
            data.notes.associate { it.id to it.id },
        )
    }

    // ---- REPLACE of chosen folders ----

    /**
     * A replacement of chosen folders only (D325): the folders [scope] of this device (and the ones an incoming folder is
     * folded into by its name) become what comes. Every incoming note is stored as it comes, whichever is newer; a note that
     * is in one of these folders here and does not come goes to the trash, where it can be taken back. Everything outside
     * these folders stays as it is.
     */
    fun planFolders(current: BackupData, incoming: BackupData, scope: Set<String>, now: Long = System.currentTimeMillis()): PlannedImport {
        val data = sanitize(incoming, now)
        // Nothing came: the folders of this device are not emptied by an answer that names none of them.
        if (data.folders.none { it.deletedAt == null }) throw TransferException(TransferError.NO_FOLDERS)
        val currentFolders = current.folders.associateBy { it.id }
        val currentNotes = current.notes.associateBy { it.id }
        val activeByName = current.folders.filter { it.deletedAt == null }.associateBy { it.name.lowercase() }

        val folderAlias = HashMap<String, String>()
        val folderOut = ArrayList<Folder>()
        var added = 0
        var merged = 0
        var nextOrder = (current.folders.maxOfOrNull { it.sortOrder } ?: -1) + 1
        for (folder in data.folders) {
            val sameId = currentFolders[folder.id]
            val sameName = if (folder.deletedAt == null) activeByName[folder.name.lowercase()] else null
            when {
                sameId != null -> {
                    folderAlias[folder.id] = sameId.id
                    folderOut += folder.copy(sortOrder = sameId.sortOrder)
                    merged++
                }

                sameName != null -> {
                    folderAlias[folder.id] = sameName.id
                    merged++
                }

                else -> {
                    folderAlias[folder.id] = folder.id
                    folderOut += folder.copy(sortOrder = nextOrder++)
                    added++
                }
            }
        }
        val replaced = scope.filterTo(HashSet()) { it in currentFolders } + folderAlias.values
        val fallbackFolder = folderAlias.values.first()

        val noteOut = data.notes.map { it.copy(folderId = folderAlias[it.folderId] ?: fallbackFolder) }
        val incomingIds = noteOut.mapTo(HashSet()) { it.id }
        val gone = current.notes
            .filter { it.folderId in replaced && it.deletedAt == null && it.id !in incomingIds }
            .map { it.copy(deletedAt = now, updatedAt = now) }

        // INV-3 for the folders that come and for the ones of this device that lose their featured note to the trash.
        val writtenIds = folderOut.mapTo(HashSet()) { it.id }
        val kept = current.folders.filter { it.id in replaced && it.id !in writtenIds }
        val changedIds = incomingIds + gone.map { it.id }
        val notesAfter = noteOut + gone + current.notes.filter { it.id !in changedIds }
        val folders = fixFeatured(folderOut, notesAfter) + fixFeatured(kept, notesAfter).filter { it !in kept }

        return PlannedImport(
            plan = ImportPlan(
                clearAll = false,
                folders = folders,
                notes = noteOut + gone,
                replaceNodesOf = noteOut.filter { it.type == NoteType.BUDGET }.map { it.id }.toSet(),
                nodes = data.nodes,
                reminders = data.reminders,
            ),
            stats = ImportStats(
                foldersAdded = added,
                foldersMerged = merged,
                notesAdded = noteOut.count { it.id !in currentNotes },
                notesUpdated = noteOut.count { it.id in currentNotes },
            ),
            noteIds = noteOut.associate { it.id to it.id },
        )
    }

    // ---- MERGE and NEW_IDS ----

    private fun merge(current: BackupData, data: BackupData, ids: IdGenerator, freshIds: Boolean): PlannedImport {
        val currentFolders = current.folders.associateBy { it.id }
        val currentNotes = current.notes.associateBy { it.id }
        val activeByName = current.folders.filter { it.deletedAt == null }.associateBy { it.name.lowercase() }

        // Folders: same id -> newer wins; same name (D52) -> fold into the existing folder.
        val folderAlias = HashMap<String, String>()
        val folderOut = ArrayList<Folder>()
        var added = 0
        var merged = 0
        var nextOrder = (current.folders.maxOfOrNull { it.sortOrder } ?: -1) + 1
        for (folder in data.folders) {
            val sameId = if (freshIds) null else currentFolders[folder.id]
            val sameName = if (folder.deletedAt == null) activeByName[folder.name.lowercase()] else null
            when {
                sameId != null -> {
                    folderAlias[folder.id] = sameId.id
                    if (folder.updatedAt > sameId.updatedAt) {
                        folderOut += folder.copy(sortOrder = sameId.sortOrder)
                    }
                    merged++
                }

                sameName != null -> {
                    folderAlias[folder.id] = sameName.id
                    merged++
                }

                else -> {
                    val id = if (freshIds) ids.newId() else folder.id
                    folderAlias[folder.id] = id
                    folderOut += folder.copy(id = id, sortOrder = nextOrder++)
                    added++
                }
            }
        }
        val fallbackFolder = folderAlias.values.firstOrNull()
            ?: current.folders.firstOrNull { it.deletedAt == null }?.id

        // Notes: same id -> newer wins (MERGE only); everything else is added.
        val noteMap = LinkedHashMap<String, String>()
        val noteOut = ArrayList<Note>()
        var notesAdded = 0
        var notesUpdated = 0
        var notesSkipped = 0
        for (note in data.notes) {
            val folderId = folderAlias[note.folderId] ?: fallbackFolder
                ?: throw TransferException(TransferError.NO_FOLDERS)
            val existing = if (freshIds) null else currentNotes[note.id]
            when {
                existing == null -> {
                    val id = if (freshIds) ids.newId() else note.id
                    noteMap[note.id] = id
                    noteOut += note.copy(id = id, folderId = folderId)
                    notesAdded++
                }

                note.updatedAt > existing.updatedAt -> {
                    noteMap[note.id] = existing.id
                    noteOut += note.copy(folderId = folderId)
                    notesUpdated++
                }

                else -> notesSkipped++
            }
        }

        // Budget nodes and reminders follow their notes; a skipped note keeps its own.
        val nodeIds = HashMap<String, String>()
        val nodeOut = data.nodes.filter { it.noteId in noteMap }.map { node ->
            val id = if (freshIds) ids.newId().also { nodeIds[node.id] = it } else node.id
            node.copy(id = id, noteId = noteMap.getValue(node.noteId))
        }.map { node ->
            // Parent ids are remapped after all new node ids are known.
            if (freshIds) node.copy(parentId = node.parentId?.let { nodeIds[it] }) else node
        }
        val reminderOut = data.reminders.filter { it.noteId in noteMap }.map { r ->
            r.copy(
                id = if (freshIds) ids.newId() else r.id,
                noteId = noteMap.getValue(r.noteId),
            )
        }

        val featuredFolders = fixFeatured(
            folderOut.map { f ->
                // A kept (skipped) note still exists under its own id; fresh ids never do.
                f.copy(featuredNoteId = f.featuredNoteId?.let { noteMap[it] ?: it.takeUnless { freshIds } })
            },
            noteOut + current.notes,
        )
        return PlannedImport(
            plan = ImportPlan(
                clearAll = false,
                folders = featuredFolders,
                notes = noteOut,
                replaceNodesOf = noteOut.filter { it.type == NoteType.BUDGET }.map { it.id }.toSet(),
                nodes = nodeOut,
                reminders = reminderOut,
            ),
            stats = ImportStats(added, merged, notesAdded, notesUpdated, notesSkipped),
            noteIds = noteMap,
        )
    }

    // ---- validation and repair ----

    /**
     * Rejects unsafe ids, drops rows that point nowhere, gives every BUDGET note a valid tree
     * and recomputes all cached budget values (INV-13).
     */
    private fun sanitize(data: BackupData, now: Long): BackupData {
        val allIds = data.folders.map { it.id } + data.notes.map { it.id } +
            data.nodes.map { it.id } + data.reminders.map { it.id }
        if (allIds.any { !SAFE_ID.matches(it) }) throw TransferException(TransferError.CORRUPT)
        if (data.folders.map { it.id }.toSet().size != data.folders.size ||
            data.notes.map { it.id }.toSet().size != data.notes.size ||
            data.nodes.map { it.id }.toSet().size != data.nodes.size
        ) {
            throw TransferException(TransferError.CORRUPT)
        }

        // INV-1: a note whose folder is not in the file goes to the first active folder of the file.
        val folderIds = data.folders.map { it.id }.toSet()
        val fallback = data.folders.firstOrNull { it.deletedAt == null }?.id
        val notes = data.notes.map { n ->
            if (n.folderId in folderIds || fallback == null) n else n.copy(folderId = fallback)
        }

        val noteIds = notes.map { it.id }.toSet()
        val nodesByNote = data.nodes.groupBy { it.noteId }
        val nodes = ArrayList<BudgetNode>()
        for (note in notes.filter { it.type == NoteType.BUDGET }) {
            nodes += validTree(note, nodesByNote[note.id].orEmpty(), now)
        }
        return BackupData(
            folders = data.folders,
            notes = notes,
            nodes = nodes,
            reminders = data.reminders.filter { it.noteId in noteIds },
        )
    }

    private fun validTree(note: Note, raw: List<BudgetNode>, now: Long): List<BudgetNode> {
        val byId = raw.associateBy { it.id }
        val roots = raw.filter { it.parentId == null }
        if (roots.size > 1) throw TransferException(TransferError.CORRUPT)
        val root = roots.singleOrNull() ?: BudgetNode(
            id = "root-" + note.id, noteId = note.id, parentId = null, title = note.title,
            kind = BudgetNodeKind.GROUP, manualSumMinor = 0, manualGoalMinor = 0, comment = null,
            cachedSumMinor = 0, cachedGoalMinor = 0, cachedRemainderMinor = 0,
            isExpanded = true, sortOrder = 0, createdAt = now, updatedAt = now,
        )
        val children = raw.filter { it.parentId != null && it.parentId in byId }.groupBy { it.parentId }
        // Keep only nodes reachable from the root, and never below a LEAF (INV-4).
        val kept = ArrayList<BudgetNode>()
        val seen = HashSet<String>()
        fun walk(node: BudgetNode) {
            if (!seen.add(node.id)) throw TransferException(TransferError.CORRUPT)
            kept += node
            if (node.kind == BudgetNodeKind.GROUP) children[node.id].orEmpty().forEach(::walk)
        }
        walk(if (root.kind == BudgetNodeKind.GROUP) root else root.copy(kind = BudgetNodeKind.GROUP))
        return BudgetCalculator.recalculateAll(kept)
    }

    /** INV-3: a featured link must point at an existing, active note of the same folder. */
    private fun fixFeatured(folders: List<Folder>, notes: List<Note>): List<Folder> {
        val active = notes.filter { it.deletedAt == null }.associateBy { it.id }
        return folders.map { f ->
            val featured = f.featuredNoteId?.let { active[it] }
            if (featured != null && featured.folderId == f.id) f
            else f.copy(featuredNoteId = null, featuredMetricsMask = 0)
        }
    }

    private val SAFE_ID = Regex("[A-Za-z0-9_-]{1,64}")
}
