package com.notebudget.app.data.transfer

import com.notebudget.app.core.common.Clock
import com.notebudget.app.core.common.IdGenerator
import com.notebudget.app.domain.model.DomainError
import com.notebudget.app.domain.model.DomainResult
import com.notebudget.app.domain.model.Note
import com.notebudget.app.domain.model.asFailure
import com.notebudget.app.domain.model.asSuccess
import com.notebudget.app.domain.repository.NoteRepository
import com.notebudget.app.domain.repository.TagRepository
import com.notebudget.app.domain.usecase.TagUseCases
import kotlinx.coroutines.CancellationException

/** The names of clones (D179): the title, then a postfix with a number, `_clone1`, `_clone2` ..., so no two notes of a folder are named alike. */
object CloneNames {
    private const val NUMBER = "%1\$d"

    /** The postfix of a clone as a pattern, to cut it off a title that already has one (a clone of a clone gets the next free number). */
    fun pattern(format: String): Regex =
        Regex(Regex.escape(format.substringBefore(NUMBER)) + "\\d+" + Regex.escape(format.substringAfter(NUMBER)) + "$")

    /** The first `title + postfix(n)` (n = 1, 2, ...) that no name in [existing] has, ignoring case. [format] is the postfix with `%1$d`. */
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
 * Clones a note of any kind (D179): a new note in the same folder with the same content and its own copy of everything the kind keeps
 * outside the note record (the files of a drawing, a recording, a photo; the rows of a budget). The clone is named `title_clone1`
 * (the next free number). It has the same colour, comment and tags; it is not pinned or a favourite, and has no reminders; its
 * history (the log of a budget) starts empty. If anything fails halfway the half-made clone is removed.
 */
class NoteCloneService(
    private val notes: NoteRepository,
    private val handlers: NoteHandlerLookup,
    private val tagRepository: TagRepository? = null,
    private val tagUseCases: TagUseCases? = null,
    private val clock: Clock = Clock.System,
    private val ids: IdGenerator = IdGenerator.Random,
) {

    /** [suffixFormat] is the postfix with `%1$d` in the language of the app, e.g. `_clone%1$d`. */
    suspend fun clone(noteId: String, suffixFormat: String): DomainResult<Note> {
        val original = notes.getById(noteId) ?: return DomainError.NOTE_NOT_FOUND.asFailure()
        val inFolder = notes.getByFolder(original.folderId)
        val now = clock.now()
        val copy = original.copy(
            id = ids.newId(),
            title = CloneNames.next(original.title, inFolder.map { it.title }, suffixFormat),
            isPinned = false,
            pinnedAt = 0,
            isFavorite = false,
            createdAt = now,
            updatedAt = now,
            deletedAt = null,
            sortOrder = (inFolder.maxOfOrNull { it.sortOrder } ?: -1) + 1,
            tags = emptyList(),
        )
        notes.insert(copy)
        try {
            handlers.handlerFor(original.type)?.copyData(original, copy)
            val tagIds = tagRepository?.getAssignments()?.get(original.id).orEmpty().map { it.id }.toSet()
            if (tagIds.isNotEmpty()) tagUseCases?.setNoteTags(copy.id, tagIds)
        } catch (e: CancellationException) {
            discard(copy)
            throw e
        } catch (e: Exception) {
            discard(copy)
            return DomainError.CLONE_FAILED.asFailure()
        }
        return copy.asSuccess()
    }

    /** Removes a clone that could not be finished: the note and whatever files were already copied. */
    private suspend fun discard(copy: Note) {
        runCatching {
            handlers.handlerFor(copy.type)?.deleteAttachments(copy)
            notes.trash(copy.id, clock.now())
            notes.purge(copy.id)
        }
    }
}
