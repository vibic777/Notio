package com.notio.shared.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sections of the app the person turns on or off (Android D366): the dependency rules of the switches. */
class AppFeatureTest {
    @Test
    fun `a note kind is found by its type, an unknown kind stays enabled`() {
        assertEquals(AppFeature.NOTE_BUDGET, AppFeature.ofNote(NoteType.BUDGET))
        assertEquals(AppFeature.NOTE_TASK, AppFeature.ofNote(NoteType.TASK))
        assertEquals(AppFeature.NOTE_TEXT, AppFeature.ofNote(NoteType.TEXT))
        assertNull(AppFeature.ofNote(NoteType("FROM_THE_FUTURE")))
        assertEquals(AppFeature.SYNC, AppFeature.of("sync"))
        assertNull(AppFeature.of("nonsense"))
    }

    @Test
    fun `a new install starts with note folders and text only, a migrated one with all`() {
        assertEquals(setOf(AppFeature.NOTE_FOLDERS, AppFeature.NOTE_TEXT), AppFeature.FIRST_RUN)
        assertEquals(AppFeature.entries.toSet(), AppFeature.ALL)
        assertEquals(
            AppFeature.entries.filter { it.noteType != null },
            AppFeature.NOTE_KINDS,
        )
    }

    @Test
    fun `folders cannot go off while a kind that lives in them is on`() {
        // Note folders are locked while any note kind but the task is on.
        assertFalse(AppFeature.mayTurnOff(AppFeature.NOTE_FOLDERS, setOf(AppFeature.NOTE_FOLDERS, AppFeature.NOTE_LIST)))
        assertTrue(AppFeature.mayTurnOff(AppFeature.NOTE_FOLDERS, setOf(AppFeature.NOTE_FOLDERS, AppFeature.TASK_FOLDERS, AppFeature.NOTE_TASK)))
        // Task folders are locked only by the task kind.
        assertFalse(AppFeature.mayTurnOff(AppFeature.TASK_FOLDERS, setOf(AppFeature.TASK_FOLDERS, AppFeature.NOTE_TASK)))
        assertTrue(AppFeature.mayTurnOff(AppFeature.TASK_FOLDERS, setOf(AppFeature.TASK_FOLDERS, AppFeature.NOTE_BUDGET)))
        // Everything else moves freely.
        assertTrue(AppFeature.mayTurnOff(AppFeature.REMINDERS, AppFeature.ALL))
        assertTrue(AppFeature.mayTurnOff(AppFeature.SYNC, AppFeature.ALL))
    }

    @Test
    fun `a kind cannot go on without its folders`() {
        assertFalse(AppFeature.mayTurnOn(AppFeature.NOTE_TASK, setOf(AppFeature.NOTE_FOLDERS)))
        assertTrue(AppFeature.mayTurnOn(AppFeature.NOTE_TASK, setOf(AppFeature.TASK_FOLDERS)))
        assertFalse(AppFeature.mayTurnOn(AppFeature.NOTE_BUDGET, setOf(AppFeature.TASK_FOLDERS)))
        assertTrue(AppFeature.mayTurnOn(AppFeature.NOTE_BUDGET, setOf(AppFeature.NOTE_FOLDERS)))
        assertTrue(AppFeature.mayTurnOn(AppFeature.REMINDERS, emptySet()))
        assertTrue(AppFeature.mayTurnOn(AppFeature.SYNC, emptySet()))
    }

    @Test
    fun `the lock reason says why a switch cannot move`() {
        val on = setOf(AppFeature.NOTE_FOLDERS, AppFeature.NOTE_LIST)
        assertEquals(AppFeature.LockReason.KINDS_STILL_ON, AppFeature.lockReason(AppFeature.NOTE_FOLDERS, on))
        assertEquals(AppFeature.LockReason.NEEDS_FOLDERS_FIRST, AppFeature.lockReason(AppFeature.NOTE_BUDGET, setOf(AppFeature.TASK_FOLDERS)))
        assertNull(AppFeature.lockReason(AppFeature.REMINDERS, AppFeature.ALL))
        assertNull(AppFeature.lockReason(AppFeature.NOTE_LIST, on))
    }
}
