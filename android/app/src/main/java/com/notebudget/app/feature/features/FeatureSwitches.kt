package com.notebudget.app.feature.features

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.notebudget.app.R
import com.notebudget.app.core.ui.tip.Button
import com.notebudget.app.core.ui.tip.TextButton
import com.notebudget.app.core.ui.tip.stringResource
import com.notebudget.app.domain.model.AppFeature
import kotlinx.coroutines.launch

/** The switch rows of the sections, in the order the wizard and the setting show them (D366). */
val FEATURES_ORDER: List<AppFeature> = listOf(
    AppFeature.NOTE_FOLDERS, AppFeature.TASK_FOLDERS,
) + AppFeature.NOTE_KINDS + listOf(AppFeature.REMINDERS, AppFeature.CALENDAR, AppFeature.SYNC)

/** The name of a section switch: the names of the kinds and folders are reused, only the three pages get new keys. */
fun featureNameRes(feature: AppFeature): Int = when (feature) {
    AppFeature.NOTE_FOLDERS -> R.string.folder_kind_notes
    AppFeature.TASK_FOLDERS -> R.string.folder_kind_tasks
    AppFeature.NOTE_TEXT -> R.string.note_type_text
    AppFeature.NOTE_WORD -> R.string.note_type_word
    AppFeature.NOTE_LIST -> R.string.note_type_list
    AppFeature.NOTE_BUDGET -> R.string.note_type_budget
    AppFeature.NOTE_DRAWING -> R.string.note_type_drawing
    AppFeature.NOTE_BLUEPRINT -> R.string.note_type_blueprint
    AppFeature.NOTE_AUDIO -> R.string.note_type_audio
    AppFeature.NOTE_SCHEDULE -> R.string.note_type_schedule
    AppFeature.NOTE_TASK -> R.string.note_type_task
    AppFeature.NOTE_PHOTO -> R.string.note_type_photo
    AppFeature.NOTE_VIDEO -> R.string.note_type_video
    AppFeature.NOTE_SCREEN -> R.string.note_type_screen
    AppFeature.REMINDERS -> R.string.reminders_title
    AppFeature.CALENDAR -> R.string.calendar_title
    AppFeature.SYNC -> R.string.help_sync_title
}

/**
 * The rows of the sections (D366): the same list serves the first-run page and Settings -> Sections.
 * [countOf] tells how many objects turning a section off would hide; a non-zero count first asks
 * "hidden, not deleted". A switch that a dependency forbids is greyed and says why under it.
 */
@Composable
fun FeatureSwitches(
    enabled: Set<AppFeature>,
    countOf: suspend (AppFeature) -> Int,
    onSet: suspend (AppFeature, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var asking by remember { mutableStateOf<AppFeature?>(null) }
    var askingCount by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FEATURES_ORDER.forEach { feature ->
            val on = feature in enabled
            val lock = AppFeature.lockReason(feature, enabled)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(featureNameRes(feature)),
                    Modifier.weight(1f),
                    color = if (lock != null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
                Switch(
                    checked = on,
                    enabled = lock == null && !busy,
                    onCheckedChange = { want ->
                        if (want) {
                            busy = true
                            scope.launch { runCatching { onSet(feature, true) }; busy = false }
                        } else {
                            scope.launch {
                                val n = runCatching { countOf(feature) }.getOrDefault(0)
                                if (n > 0) {
                                    asking = feature
                                    askingCount = n
                                } else {
                                    busy = true
                                    runCatching { onSet(feature, false) }
                                    busy = false
                                }
                            }
                        }
                    },
                )
            }
            if (lock != null) {
                Text(
                    stringResource(
                        when (lock) {
                            AppFeature.LockReason.KINDS_STILL_ON -> R.string.features_lock_kinds_on
                            AppFeature.LockReason.NEEDS_FOLDERS_FIRST -> R.string.features_lock_needs_folders
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }

    asking?.let { feature ->
        AlertDialog(
            onDismissRequest = { asking = null },
            title = { Text(stringResource(R.string.features_hide_title)) },
            text = { Text(stringResource(R.string.features_hide_body, stringResource(featureNameRes(feature)), askingCount)) },
            confirmButton = {
                Button(onClick = {
                    val f = feature
                    asking = null
                    busy = true
                    scope.launch { runCatching { onSet(f, false) }; busy = false }
                }) { Text(stringResource(R.string.features_hide_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { asking = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}
