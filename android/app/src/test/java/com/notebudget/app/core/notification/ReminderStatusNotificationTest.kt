package com.notebudget.app.core.notification

import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import com.notebudget.app.domain.model.DateTimeFormats
import com.notebudget.app.domain.model.Reminder
import com.notebudget.app.domain.model.ReminderStatus
import com.notebudget.app.domain.model.ReminderType
import com.notebudget.app.domain.usecase.FakeNoteRepository
import com.notebudget.app.domain.usecase.FakeReminderRepository
import com.notebudget.app.domain.usecase.FakeStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * D365: system status notifications no longer show reminder details.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReminderStatusNotificationTest {
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val manager = app.getSystemService(NotificationManager::class.java)
    private val store = FakeStore()
    private val formats = DateTimeFormats()

    private fun status() = ReminderStatusNotification(app, FakeReminderRepository(store), FakeNoteRepository(store), now = { 1_000 })

    private fun reminder(id: String, at: Long) = Reminder(
        id = id, noteId = "n1", type = ReminderType.entries.first(), triggerAt = at, isEnabled = true,
        status = ReminderStatus.SCHEDULED, soundEnabled = true, soundUri = null, vibrationEnabled = false, popupEnabled = false,
        requiresConfirmation = false, repeatUntilConfirmed = false, repeatIntervalMinutes = null, createdAt = 0, updatedAt = 0,
    )

    private fun shown() = shadowOf(manager).allNotifications.singleOrNull()

    @Test
    fun `reminders are not shown in the system status notification`() = runBlocking {
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        store.reminders.value = listOf(reminder("r1", 5_000_000))
        val status = status()

        status.refresh(enabled = true, formats = formats)
        assertNull(shown())
        assertEquals(0, shadowOf(manager).allNotifications.size)
    }
}
