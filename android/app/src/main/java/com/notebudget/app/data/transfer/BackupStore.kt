package com.notebudget.app.data.transfer

import androidx.room.withTransaction
import com.notebudget.app.core.database.AppDatabase
import com.notebudget.app.data.mapper.toDomain
import com.notebudget.app.data.mapper.toEntity
import com.notebudget.app.domain.model.BudgetNode

/** Whole-database access for backup and import, kept apart from the everyday repositories. */
interface BackupStore {
    /** Everything, trashed rows included. */
    suspend fun readAll(): BackupData

    /** Applies [plan] in one transaction: all of it or nothing. */
    suspend fun apply(plan: ImportPlan)
}

class RoomBackupStore(private val db: AppDatabase) : BackupStore {
    private val folders = db.folderDao()
    private val notes = db.noteDao()
    private val nodes = db.budgetNodeDao()
    private val reminders = db.reminderDao()

    override suspend fun readAll(): BackupData = db.withTransaction {
        BackupData(
            folders = folders.getEvery().map { it.toDomain() },
            notes = notes.getEvery().map { it.toDomain() },
            nodes = nodes.getEvery().map { it.toDomain() },
            reminders = reminders.getEvery().map { it.toDomain() },
        )
    }

    override suspend fun apply(plan: ImportPlan) {
        db.withTransaction {
            if (plan.clearAll) {
                // Notes first: the note -> folder foreign key is RESTRICT. Deleting notes
                // cascades to budget nodes and reminders.
                notes.deleteAll()
                folders.deleteAll()
            }
            // Folders first (notes point at them); featured links are plain columns.
            plan.folders.forEach { folders.upsert(it.toEntity()) }
            plan.notes.forEach { notes.upsert(it.toEntity()) }
            plan.replaceNodesOf.forEach { nodes.deleteByNote(it) }
            // Parents before children so the parent foreign key is always satisfied.
            val byId = plan.nodes.associateBy { it.id }
            plan.nodes.sortedBy { depthOf(it, byId) }.forEach { nodes.upsert(it.toEntity()) }
            plan.reminders.forEach { reminders.upsert(it.toEntity()) }
        }
    }

    private fun depthOf(node: BudgetNode, byId: Map<String, BudgetNode>): Int {
        var depth = 0
        var current = node
        while (true) {
            current = current.parentId?.let { byId[it] } ?: return depth
            if (++depth > byId.size) return depth
        }
    }
}
