package com.notebudget.app.data.transfer

import com.notebudget.app.domain.model.BudgetNode
import com.notebudget.app.domain.model.BudgetNodeKind
import com.notebudget.app.domain.model.FeaturedLayout
import com.notebudget.app.domain.model.Comment
import com.notebudget.app.domain.model.Folder
import com.notebudget.app.domain.model.Note
import com.notebudget.app.domain.model.NoteType
import com.notebudget.app.domain.model.Reminder
import com.notebudget.app.domain.model.ReminderStatus
import com.notebudget.app.domain.model.ReminderType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * File formats of full backups and single-note files (AGENT_EN.md §6.13, D62).
 *
 * COMPATIBILITY RULES — these files are the way users move data between phones and app
 * versions, so they are never broken:
 *  - every field has a default, so files written by an OLDER version still load;
 *  - readers ignore unknown fields, so files written by a NEWER version still load;
 *  - enums travel as strings; unknown values fall back to a safe default on import;
 *  - `formatVersion` is bumped only for a change old readers cannot understand; such a file
 *    also raises `minReaderVersion`, and a reader refuses a file whose `minReaderVersion`
 *    is above [FORMAT_VERSION] instead of guessing;
 *  - unknown note kinds are kept as they are (see `NoteType`).
 */
object TransferFormat {
    const val BACKUP = "notebudget-backup"
    const val NOTE = "notebudget-note"

    /** Highest format version this build can read and the one it writes. */
    const val FORMAT_VERSION = 1

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
        prettyPrint = false
    }
}

@Serializable
data class FolderRecord(
    val id: String,
    val name: String,
    val colorHex: String = "#3366FF",
    val styleId: String = "SOFT",
    val icon: String? = null,
    val sortOrder: Int = 0,
    val featuredNoteId: String? = null,
    val featuredMetricsMask: Int = 0,
    val featuredLayout: String = "COMPACT",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val deletedAt: Long? = null,
    /** Pinned folders (D136); absent in files made before it existed. */
    val isPinned: Boolean = false,
    val pinnedAt: Long = 0,
    /** A comment on the folder (D177); absent in older files. */
    val comment: String? = null,
    /** NOTES or TASKS (D239); absent in older files = NOTES. */
    val kind: String = "NOTES",
)

@Serializable
data class NoteRecord(
    val id: String,
    val folderId: String = "",
    val title: String,
    val type: String = "TEXT",
    val colorHex: String? = null,
    val isPinned: Boolean = false,
    val isFavorite: Boolean = false,
    val isTitleBold: Boolean = false,
    val contentText: String? = null,
    val extras: String? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val deletedAt: Long? = null,
    /** Names of the note's tags (D109). Tags travel by name; absent in files made before tags existed. */
    val tags: List<String> = emptyList(),
    /** Pin time and manual order (D136); absent in files made before they existed. */
    val pinnedAt: Long = 0,
    val sortOrder: Int = 0,
    /** A comment on the note (D177); absent in older files. */
    val comment: String? = null,
    /** The content is hidden until the eye is pressed (D363); absent in older files, and an older Notio skips it. */
    val hidden: Boolean = false,
)

@Serializable
data class BudgetNodeRecord(
    val id: String,
    val noteId: String = "",
    val parentId: String? = null,
    val title: String,
    val kind: String = "LEAF",
    val manualSumMinor: Long = 0,
    val manualGoalMinor: Long = 0,
    val comment: String? = null,
    val cachedSumMinor: Long = 0,
    val cachedGoalMinor: Long = 0,
    val cachedRemainderMinor: Long = 0,
    val isExpanded: Boolean = true,
    val sortOrder: Int = 0,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    /** How many times a leaf repeats (D94). Absent in files made before it existed: counts as 1. */
    val quantity: Int = 1,
)

@Serializable
data class ReminderRecord(
    val id: String,
    val noteId: String,
    val type: String = "ONE_TIME",
    val triggerAt: Long,
    val isEnabled: Boolean = true,
    val status: String = "SCHEDULED",
    val soundEnabled: Boolean = true,
    val soundUri: String? = null,
    val vibrationEnabled: Boolean = true,
    val popupEnabled: Boolean = true,
    val requiresConfirmation: Boolean = false,
    val repeatUntilConfirmed: Boolean = false,
    val repeatIntervalMinutes: Int? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    /** Volume, fade-in, extra rings and recurrence (D130); absent in backups made before they existed. */
    val volumePercent: Int = 100,
    val rampSeconds: Int = 0,
    val repeatCount: Int = 0,
    val repeatsDone: Int = 0,
    val recurrence: String = "NONE",
    val weekdaysMask: Int = 0,
    val monthDay: Int = 0,
    /** Snooze (D150); absent in older backups. */
    val snoozedUntil: Long = 0,
)

/** An item in the trash of photos, videos and recordings (D156): the list travels in a backup, the files never do. */
@Serializable
data class MediaTrashRecord(
    val id: String,
    val noteId: String = "",
    val kind: String = "PHOTO",
    val title: String = "",
    val itemJson: String = "",
    val position: Int = 0,
    val noteTitle: String = "",
    val deletedAt: Long = 0,
    val sizeBytes: Long = 0,
)

fun com.notebudget.app.domain.model.TrashedMedia.toRecord() = MediaTrashRecord(id, noteId, kind, title, itemJson, position, noteTitle, deletedAt, sizeBytes)

fun MediaTrashRecord.toDomain() = com.notebudget.app.domain.model.TrashedMedia(id, noteId, kind, title, itemJson, position, noteTitle, deletedAt, sizeBytes)

/** A line of the log of a budget note (D166); absent in older backups. */
@Serializable
data class BudgetLogRecord(
    val id: String,
    val noteId: String = "",
    val at: Long = 0,
    val kind: String = "",
    val title: String = "",
    val oldValue: Long? = null,
    val newValue: Long? = null,
    val detail: String? = null,
    /** The item or group of the line (D282); absent in older backups. */
    val nodeId: String? = null,
)

fun com.notebudget.app.domain.model.BudgetLogEntry.toRecord() = BudgetLogRecord(id, noteId, at, kind, title, oldValue, newValue, detail, nodeId)

fun BudgetLogRecord.toDomain() = com.notebudget.app.domain.model.BudgetLogEntry(id, noteId, at, kind, title, oldValue, newValue, detail, nodeId)

/** Non-secret settings only (INV-11): never passwords, hashes or biometrics. */
@Serializable
data class SettingsRecord(
    val languageTag: String? = null,
    val theme: String? = null,
    val notificationPrivacy: String? = null,
    /** Trash retention in days (D93); absent in backups made before the trash screen existed. */
    val trashRetentionDays: Int? = null,
    /** Units the user added (D92); absent in older backups. Applied on REPLACE like the other settings. */
    val customUnits: List<com.notebudget.app.domain.model.CustomUnit>? = null,
    /** Currencies the user added (D170); absent in older backups. */
    val customCurrencies: List<com.notebudget.app.domain.model.CustomCurrency>? = null,
    /** Colour theme name (D105); absent in older backups. */
    val colorTheme: String? = null,
    /** Text size name (D119); absent in older backups. */
    val fontSize: String? = null,
    /** Sort choices such as `NAME:asc` (D121); absent in older backups. */
    val folderSort: String? = null,
    val noteSort: String? = null,
    /** Slot name -> `COLOR:#RRGGBB` (D110); pictures are not part of a backup. Absent in older backups. */
    val backgrounds: Map<String, String>? = null,
    /** Date/time format and export file naming as JSON (D138, D139); absent in older backups. */
    val dateTimeFormats: String? = null,
    val exportNaming: String? = null,
    /** Which time stamps are shown (D157); absent in older backups. */
    val stampDisplay: String? = null,
    /** How many lines a note card previews (D159); absent in older backups. */
    val previewLines: Int? = null,
    /** The picture on the card of a photo / video note and of a drawing (D160); absent in older backups. */
    val mediaPreview: String? = null,
    val drawingPreview: Boolean? = null,
    /** How strongly cards show their colour, % (D273); absent in older backups. */
    val cardTint: Int? = null,
    /** Whether cards show the comment (D177); absent in older backups. */
    val commentDisplay: String? = null,
    /** How many steps back the editors can undo (D197); absent in older backups. */
    val undoSteps: Int? = null,
    /** The colour of the "Your colour" theme (D219); absent in older backups. */
    val customThemeColor: Int? = null,
    /** Which kinds of note show "created" / "changed" at their bottom, as JSON (D198); absent in older backups. */
    val footerStamps: String? = null,
)

@Serializable
data class BackupFile(
    val format: String = TransferFormat.BACKUP,
    val formatVersion: Int = TransferFormat.FORMAT_VERSION,
    val minReaderVersion: Int = 1,
    val createdAt: Long = 0,
    val appVersion: String = "",
    val folders: List<FolderRecord> = emptyList(),
    val notes: List<NoteRecord> = emptyList(),
    val budgetNodes: List<BudgetNodeRecord> = emptyList(),
    val reminders: List<ReminderRecord> = emptyList(),
    val settings: SettingsRecord? = null,
    /** The trash of media items (D156), the list only; absent in older backups. */
    val mediaTrash: List<MediaTrashRecord> = emptyList(),
    /** The logs of the budget notes (D166); absent in older backups. */
    val budgetLog: List<BudgetLogRecord> = emptyList(),
)

/** A single note as a structured file (LIST / BUDGET "with structure", D24). */
@Serializable
data class NoteFile(
    val format: String = TransferFormat.NOTE,
    val formatVersion: Int = TransferFormat.FORMAT_VERSION,
    val minReaderVersion: Int = 1,
    val createdAt: Long = 0,
    val appVersion: String = "",
    val note: NoteRecord,
    /** Type-specific data, e.g. the budget tree. Owned by the note type's handler. */
    val payload: JsonObject? = null,
)

// ---- domain <-> record ----

fun Folder.toRecord() = FolderRecord(
    id, name, colorHex, styleId, icon, sortOrder, featuredNoteId, featuredMetricsMask,
    featuredLayout.name, createdAt, updatedAt, deletedAt, isPinned, pinnedAt, comment, kind.name,
)

fun FolderRecord.toDomain() = Folder(
    id = id,
    name = name,
    colorHex = colorHex,
    styleId = styleId,
    icon = icon,
    sortOrder = sortOrder,
    featuredNoteId = featuredNoteId,
    featuredMetricsMask = featuredMetricsMask and 7,
    featuredLayout = runCatching { FeaturedLayout.valueOf(featuredLayout) }.getOrDefault(FeaturedLayout.COMPACT),
    createdAt = createdAt,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
    isPinned = isPinned,
    pinnedAt = pinnedAt,
    comment = Comment.clean(comment),
    kind = com.notebudget.app.domain.model.FolderKind.of(kind),
)

fun Note.toRecord() = NoteRecord(
    id, folderId, title, type.id, colorHex, isPinned, isFavorite, isTitleBold, contentText, extras,
    createdAt, updatedAt, deletedAt, tags, pinnedAt, sortOrder, comment, hidden,
)

/** A note of a file. A favourite of an older file comes in as a pinned note: the star was merged into the pin (D180). */
fun NoteRecord.toDomain() = Note(
    id = id,
    folderId = folderId,
    title = title,
    type = NoteType(type),
    colorHex = colorHex,
    isPinned = isPinned || isFavorite,
    isFavorite = false,
    isTitleBold = isTitleBold,
    contentText = contentText,
    createdAt = createdAt,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
    extras = extras,
    tags = tags,
    pinnedAt = if (isPinned) pinnedAt else if (isFavorite) updatedAt else 0,
    sortOrder = sortOrder,
    comment = Comment.clean(comment),
    hidden = hidden,
)

fun BudgetNode.toRecord() = BudgetNodeRecord(
    id, noteId, parentId, title, kind.name, manualSumMinor, manualGoalMinor, comment,
    cachedSumMinor, cachedGoalMinor, cachedRemainderMinor, isExpanded, sortOrder, createdAt, updatedAt, quantity,
)

fun BudgetNodeRecord.toDomain() = BudgetNode(
    id = id,
    noteId = noteId,
    parentId = parentId,
    title = title,
    kind = runCatching { BudgetNodeKind.valueOf(kind) }.getOrDefault(BudgetNodeKind.LEAF),
    manualSumMinor = manualSumMinor,
    manualGoalMinor = manualGoalMinor,
    comment = comment,
    cachedSumMinor = cachedSumMinor,
    cachedGoalMinor = cachedGoalMinor,
    cachedRemainderMinor = cachedRemainderMinor,
    isExpanded = isExpanded,
    sortOrder = sortOrder,
    createdAt = createdAt,
    updatedAt = updatedAt,
    // A file from somewhere else may hold nonsense: fall back to a single piece.
    quantity = quantity.takeIf { it in 1..BudgetNode.MAX_QUANTITY } ?: 1,
)

fun Reminder.toRecord() = ReminderRecord(
    id, noteId, type.name, triggerAt, isEnabled, status.name, soundEnabled, soundUri, vibrationEnabled,
    popupEnabled, requiresConfirmation, repeatUntilConfirmed, repeatIntervalMinutes, createdAt, updatedAt,
    volumePercent, rampSeconds, repeatCount, repeatsDone, recurrence.name, weekdaysMask, monthDay, snoozedUntil,
)

fun ReminderRecord.toDomain() = Reminder(
    id = id,
    noteId = noteId,
    type = runCatching { ReminderType.valueOf(type) }.getOrDefault(ReminderType.ONE_TIME),
    triggerAt = triggerAt,
    isEnabled = isEnabled,
    status = runCatching { ReminderStatus.valueOf(status) }.getOrDefault(ReminderStatus.SCHEDULED),
    soundEnabled = soundEnabled,
    soundUri = soundUri,
    vibrationEnabled = vibrationEnabled,
    popupEnabled = popupEnabled,
    requiresConfirmation = requiresConfirmation,
    repeatUntilConfirmed = repeatUntilConfirmed,
    repeatIntervalMinutes = repeatIntervalMinutes,
    createdAt = createdAt,
    updatedAt = updatedAt,
    volumePercent = volumePercent.coerceIn(10, 100),
    rampSeconds = rampSeconds.coerceIn(0, 300),
    repeatCount = repeatCount.coerceIn(0, 50),
    repeatsDone = repeatsDone.coerceAtLeast(0),
    recurrence = runCatching { com.notebudget.app.domain.model.Recurrence.valueOf(recurrence) }.getOrDefault(com.notebudget.app.domain.model.Recurrence.NONE),
    weekdaysMask = weekdaysMask and 127,
    monthDay = monthDay.coerceIn(0, 31),
    snoozedUntil = snoozedUntil.coerceAtLeast(0),
)
