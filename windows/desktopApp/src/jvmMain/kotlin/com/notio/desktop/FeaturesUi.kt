package com.notio.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.notio.shared.domain.AppFeature
import kotlinx.coroutines.launch

/** The switch rows of the sections, in the order the wizard and the setting show them (Android D366). */
val FEATURES_ORDER: List<AppFeature> = listOf(
    AppFeature.NOTE_FOLDERS, AppFeature.TASK_FOLDERS,
) + AppFeature.NOTE_KINDS + listOf(AppFeature.REMINDERS, AppFeature.CALENDAR, AppFeature.SYNC)

/** The string key of a section's name: the names of the kinds and folders are reused, only the three pages get their own. */
fun featureNameKey(feature: AppFeature): String = when (feature) {
    AppFeature.NOTE_FOLDERS -> "folder_kind_notes"
    AppFeature.TASK_FOLDERS -> "folder_kind_tasks"
    AppFeature.NOTE_TEXT -> "note_type_text"
    AppFeature.NOTE_WORD -> "note_type_word"
    AppFeature.NOTE_LIST -> "note_type_list"
    AppFeature.NOTE_BUDGET -> "note_type_budget"
    AppFeature.NOTE_DRAWING -> "note_type_drawing"
    AppFeature.NOTE_BLUEPRINT -> "note_type_blueprint"
    AppFeature.NOTE_AUDIO -> "note_type_audio"
    AppFeature.NOTE_SCHEDULE -> "note_type_schedule"
    AppFeature.NOTE_TASK -> "note_type_task"
    AppFeature.NOTE_PHOTO -> "note_type_photo"
    AppFeature.NOTE_VIDEO -> "note_type_video"
    AppFeature.NOTE_SCREEN -> "note_type_screen"
    AppFeature.REMINDERS -> "reminders_title"
    AppFeature.CALENDAR -> "calendar_title"
    AppFeature.SYNC -> "help_sync_title"
}

/**
 * The rows of the sections (Android D366): the same list serves the first-run page and Settings -> Sections.
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
                    stringResource(featureNameKey(feature)),
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
                            AppFeature.LockReason.KINDS_STILL_ON -> "features_lock_kinds_on"
                            AppFeature.LockReason.NEEDS_FOLDERS_FIRST -> "features_lock_needs_folders"
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
            title = { Text(stringResource("features_hide_title")) },
            text = { Text(stringResource("features_hide_body", stringResource(featureNameKey(feature)), askingCount)) },
            confirmButton = {
                Button(onClick = {
                    val f = feature
                    asking = null
                    busy = true
                    scope.launch { runCatching { onSet(f, false) }; busy = false }
                }) { Text(stringResource("features_hide_confirm")) }
            },
            dismissButton = {
                TextButton(onClick = { asking = null }) { Text(stringResource("action_cancel")) }
            },
        )
    }
}

/**
 * The first-run page (Android D366): every section of the app as a switch, starting with the defaults
 * (note folders and text notes on). Done writes the set; nothing is asked again.
 */
@Composable
fun FeaturesWizardPage(controller: NotioController, modifier: Modifier = Modifier) {
    var chosen by remember { mutableStateOf(AppFeature.FIRST_RUN) }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource("features_wizard_title"), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource("features_wizard_body"), style = MaterialTheme.typography.bodyLarge)
        FeatureSwitches(
            enabled = chosen,
            countOf = { 0 },
            onSet = { feature, on ->
                chosen = if (on) chosen + feature else chosen - feature
            },
        )
        Button(
            onClick = {
                controller.chooseFeatures(chosen)
                controller.markFeaturesChosen()
            },
            enabled = AppFeature.NOTE_FOLDERS in chosen || AppFeature.TASK_FOLDERS in chosen,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource("features_done"))
        }
    }
}
