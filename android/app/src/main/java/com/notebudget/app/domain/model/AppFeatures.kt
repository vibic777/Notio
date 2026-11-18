package com.notebudget.app.domain.model

/**
 * The sections of the app the person turns on or off (D366): folders of a kind, every note kind,
 * the reminders, the calendar and the sync. Off hides, never deletes: the data stays in the base,
 * is still exported and synced, and comes back with the switch.
 *
 * Persisted in the settings DataStore as the set of enabled ids under `features_enabled`.
 */
enum class AppFeature(val id: String, val noteType: NoteType?, val folderKind: FolderKind?) {
    NOTE_FOLDERS("folders.notes", null, FolderKind.NOTES),
    TASK_FOLDERS("folders.tasks", null, FolderKind.TASKS),
    NOTE_TEXT("note.TEXT", NoteType.TEXT, null),
    NOTE_WORD("note.WORD", NoteType.WORD, null),
    NOTE_LIST("note.LIST", NoteType.LIST, null),
    NOTE_BUDGET("note.BUDGET", NoteType.BUDGET, null),
    NOTE_DRAWING("note.DRAWING", NoteType.DRAWING, null),
    NOTE_BLUEPRINT("note.BLUEPRINT", NoteType.BLUEPRINT, null),
    NOTE_AUDIO("note.AUDIO", NoteType.AUDIO, null),
    NOTE_SCHEDULE("note.SCHEDULE", NoteType.SCHEDULE, null),
    NOTE_TASK("note.TASK", NoteType.TASK, null),
    NOTE_PHOTO("note.PHOTO", NoteType("PHOTO"), null),
    NOTE_VIDEO("note.VIDEO", NoteType("VIDEO"), null),
    NOTE_SCREEN("note.SCREEN", NoteType("SCREEN"), null),
    REMINDERS("reminders", null, null),
    CALENDAR("calendar", null, null),
    SYNC("sync", null, null);

    companion object {
        fun of(id: String?): AppFeature? = entries.firstOrNull { it.id == id }

        /** The feature a note kind is under, or null for a kind this version does not know (kept as enabled). */
        fun ofNote(type: NoteType): AppFeature? = entries.firstOrNull { it.noteType == type }

        /** The kinds whose switch the first-run page and the Sections setting show, in their order. */
        val NOTE_KINDS = entries.filter { it.noteType != null }

        /** What a new install starts with: note folders and plain text notes (owner, D366). */
        val FIRST_RUN = setOf(NOTE_FOLDERS, NOTE_TEXT)

        /** Everything on: what an install made before D366 migrates to. */
        val ALL = entries.toSet()

        /**
         * Whether [feature] may be turned off while [on] is the enabled set.
         * A folder kind cannot go off while a kind that lives in it is on (the owner's own logical rule, D366);
         * REMINDERS cannot go off while a note kind is on that they can ring for - it can, they ring for every kind,
         * so no lock there.
         */
        fun mayTurnOff(feature: AppFeature, on: Set<AppFeature>): Boolean = when (feature) {
            NOTE_FOLDERS -> on.none { it.noteType != null && it.noteType != NoteType.TASK }
            TASK_FOLDERS -> NOTE_TASK !in on
            else -> true
        }

        /**
         * Whether [feature] may be turned on while [on] is the enabled set:
         * a note kind needs its folder kind first, a task needs task folders.
         */
        fun mayTurnOn(feature: AppFeature, on: Set<AppFeature>): Boolean = when (feature) {
            NOTE_TASK -> TASK_FOLDERS in on
            in NOTE_KINDS -> NOTE_FOLDERS in on
            else -> true
        }

        /** The lock reason of a switch that cannot move, for the hint under it (null = free to move). */
        fun lockReason(feature: AppFeature, on: Set<AppFeature>): LockReason? = when {
            feature in on && !mayTurnOff(feature, on) -> LockReason.KINDS_STILL_ON
            feature !in on && !mayTurnOn(feature, on) -> LockReason.NEEDS_FOLDERS_FIRST
            else -> null
        }
    }

    enum class LockReason { KINDS_STILL_ON, NEEDS_FOLDERS_FIRST }
}
