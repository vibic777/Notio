package com.notebudget.app.core.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.notebudget.app.NoteBudgetApplication
import com.notebudget.app.core.common.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Runs [block] off the main thread while keeping the broadcast alive (`goAsync`). A failure is written to the app log instead of
 * closing the app in the background (D231): a reminder that cannot be shown is then visible there, not a silent crash.
 */
private fun BroadcastReceiver.runAsync(block: suspend () -> Unit) {
    val pending = goAsync()
    val receiver = javaClass.simpleName
    CoroutineScope(Dispatchers.Default).launch {
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            com.notebudget.app.core.common.AppLog.critical("reminder", "$receiver failed: a reminder may not have been shown", e)
        } finally {
            pending.finish()
        }
    }
}

private fun Context.container(): AppContainer = (applicationContext as NoteBudgetApplication).container

private fun Intent.reminderId(): String? = data?.lastPathSegment

/** Alarm fired, or the user pressed "Done" on a notification. */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.reminderId() ?: return
        val action = intent.action
        val container = context.container()
        runAsync {
            try { handle(action, id, intent, context, container) } finally { container.refreshReminderStatus() }
        }
    }

    private suspend fun handle(action: String?, id: String, intent: Intent, context: Context, container: AppContainer) {
        run {
            when (action) {
                ACTION_FIRE -> {
                    // A snooze has an alarm of its own (D150): its id is the reminder's id with a prefix.
                    val snoozed = id.startsWith(SNOOZE_PREFIX)
                    val real = id.removePrefix(SNOOZE_PREFIX)
                    val fired = if (snoozed) container.reminderUseCases.onSnoozeFired(real) else container.reminderUseCases.onFired(real)
                    // What woke the phone, for the journal (D345): never a word of the note.
                    com.notebudget.app.core.common.AppLog.i("reminder", "an alarm went off (" + real.take(8) + (if (snoozed) ", a snooze" else "") + "): " + describe(fired))
                    fired?.let { reminder ->
                        val note = container.noteRepository.getById(reminder.noteId)
                        val privacy = container.settings.notificationPrivacy.first()
                        val snooze = container.settings.snoozeMinutes.first()
                        // A ring nobody answers lights the screen for less each time (D351).
                        val ring = RingSeries.ring(context, reminder.id, reminder.repeatIntervalMinutes)
                        container.reminderNotifier.show(reminder, note, privacy, snooze, ring)
                        container.reminderNotifier.startAlarmScreen(reminder, note, privacy, snooze, ring)
                    }
                }

                ACTION_TEST -> {
                    // "Test the alarm" in Settings: a reminder that is not stored anywhere rings like a real one (D152).
                    val now = System.currentTimeMillis()
                    val test = com.notebudget.app.domain.model.Reminder(
                        id = "test", noteId = "", type = com.notebudget.app.domain.model.ReminderType.ONE_TIME, triggerAt = now,
                        isEnabled = true, status = com.notebudget.app.domain.model.ReminderStatus.FIRED, soundEnabled = true, soundUri = null,
                        vibrationEnabled = true, popupEnabled = true, requiresConfirmation = false, repeatUntilConfirmed = false,
                        repeatIntervalMinutes = null, createdAt = now, updatedAt = now,
                    )
                    val snooze = container.settings.snoozeMinutes.first()
                    val privacy = container.settings.notificationPrivacy.first()
                    container.reminderNotifier.show(test, null, privacy, snooze)
                    container.reminderNotifier.startAlarmScreen(test, null, privacy, snooze)
                }

                ACTION_SNOOZE -> {
                    val minutes = intent.getIntExtra(EXTRA_MINUTES, 10)
                    container.reminderUseCases.snooze(id, minutes)
                    container.reminderNotifier.dismiss(id)
                }

                ACTION_DISMISS -> container.reminderNotifier.dismiss(id)

                ACTION_SILENCE -> com.notebudget.app.core.notification.ReminderRingService.stop(context)

                ACTION_CONFIRM -> {
                    container.reminderUseCases.onConfirmed(id)
                    container.reminderNotifier.dismiss(id)
                }
            }
        }
    }

    /** How the reminder rings and whether it rings again: what its alarm costs (D345). */
    private fun describe(fired: com.notebudget.app.domain.model.Reminder?): String {
        if (fired == null) return "nothing to ring"
        val interval = fired.repeatIntervalMinutes?.takeIf { it > 0 }
        return listOfNotNull(
            "rings",
            "sound".takeIf { fired.soundEnabled },
            "the alarm screen".takeIf { fired.popupEnabled },
            "waits for Done".takeIf { fired.requiresConfirmation },
            when {
                interval == null -> null
                fired.requiresConfirmation && fired.repeatUntilConfirmed -> "again every $interval min until Done"
                fired.repeatCount > 0 -> "extra ring " + fired.repeatsDone + " of " + fired.repeatCount + ", every $interval min"
                else -> null
            },
        ).joinToString(", ")
    }

    companion object {
        const val ACTION_FIRE = "com.notebudget.app.action.REMINDER_FIRE"
        const val ACTION_CONFIRM = "com.notebudget.app.action.REMINDER_CONFIRM"
        const val ACTION_SILENCE = "com.notebudget.app.action.REMINDER_SILENCE"
        const val ACTION_SNOOZE = "com.notebudget.app.action.REMINDER_SNOOZE"
        const val ACTION_DISMISS = "com.notebudget.app.action.REMINDER_DISMISS"
        const val ACTION_TEST = "com.notebudget.app.action.REMINDER_TEST"
        const val EXTRA_MINUTES = "minutes"
        private const val SNOOZE_PREFIX = "snooze-"
    }
}

/**
 * Restores budget merge alarms and removes legacy reminder alarms when the app is opened (D365). Previously this method set
 * alarm again and showed due reminders: after a reboot, an update, or when the app is opened. D365 keeps only merge scheduling
 * and removes old reminder alarms once on the next app start.
 */
internal suspend fun rearmAlarms(container: AppContainer) {
    // The alarms of budget merges come back too (D237).
    runCatching { container.budgetMerge.rescheduleAll() }
    // A merge whose time passed has no alarm (D345): it is done here.
    runCatching { container.budgetMerge.runDue() }
    // D365: remove intents created by pre-D365 versions without touching stored reminder state.
    runCatching {
        com.notebudget.app.core.alarm.AndroidReminderScheduler(container.appContext).cancelAllLegacy {
            container.database.reminderDao().getEvery().map { it.id }
        }
    }
}

/**
 * Restores budget merge scheduling and background sync after a reboot or app update.
 */
class RescheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // The receiver is exported so the system can call it; only the system broadcasts it listens for are acted on, so another
        // app cannot make it re-post notifications by sending it an arbitrary intent.
        if (intent.action !in ACTIONS) return
        val container = context.container()
        com.notebudget.app.core.common.AppLog.i("app", "restoring merge alarms and background sync: " + intent.action?.substringAfterLast('.'))
        runAsync { rearmAlarms(container) }
        // "Always ready for a sync" (D342): the phone waits again after its restart and after an update of the app.
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            runCatching { com.notebudget.app.data.sync.SyncListenService.follow(context) }
        }
    }

    private companion object {
        // Reminder alarms are paused (D365), but the budget merge alarms still follow the clock: a change of the
        // time or the zone re-arms them, or a merge would fire at the wrong local time.
        val ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
        )
    }
}
