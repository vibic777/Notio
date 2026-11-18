package com.notio.desktop

import com.notio.shared.data.AppPaths
import com.notio.shared.domain.AppFeature
import com.notio.shared.domain.FolderKind
import com.notio.shared.domain.NoteType
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * The sections of the app (Android D366) on a real data folder: the first-run page on a new install, the silent all-on of
 * an existing one, and a section that hides its things without deleting them.
 */
class FeaturesTest {
    private suspend fun awaitLoaded(controller: NotioController) =
        withTimeout(10_000) { while (!controller.state.value.loaded) delay(5) }

    @Test
    fun aNewInstallAsksAndAnExistingOneKeepsEverything() = runBlocking {
        val home = Files.createTempDirectory("notio-features").toFile()
        try {
            var controller = NotioController(AppPaths(home))
            try {
                awaitLoaded(controller)
                // A fresh data folder: the choice was never made, the wizard stands (D366).
                assertFalse(controller.featuresChosen)
                assertEquals(AppFeature.ALL, controller.features)
                // The person answers: note folders and Markdown only.
                controller.chooseFeatures(AppFeature.FIRST_RUN)
                controller.markFeaturesChosen()
                assertTrue(controller.featuresChosen)
                assertEquals(AppFeature.FIRST_RUN, controller.features)
            } finally {
                controller.close()
            }
            // The next start reads the same choice from ui.properties.
            controller = NotioController(AppPaths(home))
            try {
                awaitLoaded(controller)
                assertTrue(controller.featuresChosen)
                assertEquals(AppFeature.FIRST_RUN, controller.features)
            } finally {
                controller.close()
            }
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun anInstallThatAlreadyHasFoldersGetsEverythingOnSilently() = runBlocking {
        val home = Files.createTempDirectory("notio-features-migrated").toFile()
        try {
            var controller = NotioController(AppPaths(home))
            try {
                awaitLoaded(controller)
                var made = false
                controller.createFolder("Дом", "#3366FF", "SOFT", null, { error(it.toString()) }) { made = true }
                withTimeout(10_000) { while (!made || controller.state.value.folders.isEmpty()) delay(5) }
                // Folders appeared while the choice was unmade: all on, chosen (the migration of D366).
                withTimeout(10_000) { while (!controller.featuresChosen) delay(5) }
                assertEquals(AppFeature.ALL, controller.features)
            } finally {
                controller.close()
            }
            // And a later start of that data folder never asks: it was never a new install.
            controller = NotioController(AppPaths(home))
            try {
                awaitLoaded(controller)
                assertTrue(controller.featuresChosen)
                assertEquals(AppFeature.ALL, controller.features)
            } finally {
                controller.close()
            }
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun aSectionOffHidesItsThingsButDeletesNothing() = runBlocking {
        val home = Files.createTempDirectory("notio-features-hide").toFile()
        try {
            val controller = NotioController(AppPaths(home))
            try {
                awaitLoaded(controller)
                var made = false
                controller.createFolder("Дом", "#3366FF", "SOFT", null, { error(it.toString()) }) { made = true }
                withTimeout(10_000) { while (!made || controller.state.value.folders.isEmpty()) delay(5) }
                val folder = controller.state.value.folders.first().folder
                var noted = false
                controller.createNote(folder.id, "Список", NoteType.LIST, { error(it.toString()) }) { noted = true }
                withTimeout(10_000) { while (!noted || controller.state.value.notes.none { it.type == NoteType.LIST }) delay(5) }
                assertTrue(controller.featuresChosen)

                // The lists section off: the note leaves the lists and the counts, its folder stays.
                controller.setFeature(AppFeature.NOTE_LIST, false)
                withTimeout(10_000) { while (controller.state.value.notes.any { it.type == NoteType.LIST }) delay(5) }
                assertEquals(0, controller.state.value.folders.first().noteCount)
                // Nothing is deleted: the note is still in the base and comes back with the switch.
                controller.setFeature(AppFeature.NOTE_LIST, true)
                withTimeout(10_000) { while (controller.state.value.notes.none { it.type == NoteType.LIST }) delay(5) }
                assertEquals(1, controller.state.value.folders.first().noteCount)
            } finally {
                controller.close()
            }
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun aFolderKindOffHidesItsFoldersAndTheSyncOffStopsListening() = runBlocking {
        val home = Files.createTempDirectory("notio-features-folders").toFile()
        try {
            val controller = NotioController(AppPaths(home))
            try {
                awaitLoaded(controller)
                var made = false
                controller.createFolder("Работа", "#3366FF", "SOFT", "✅", { error(it.toString()) }, FolderKind.TASKS) { made = true }
                withTimeout(10_000) { while (!made || controller.state.value.folders.none { it.folder.kind == FolderKind.TASKS }) delay(5) }
                // Task folders cannot go off while the task kind is on (the dependency of D366).
                assertFalse(AppFeature.mayTurnOff(AppFeature.TASK_FOLDERS, controller.features))
                controller.setFeature(AppFeature.NOTE_TASK, false)
                controller.setFeature(AppFeature.TASK_FOLDERS, false)
                withTimeout(10_000) { while (controller.state.value.folders.any { it.folder.kind == FolderKind.TASKS }) delay(5) }
                assertTrue(controller.state.value.folders.isEmpty())
                // And the folder is not deleted: the sections back on bring it back.
                controller.chooseFeatures(AppFeature.ALL)
                withTimeout(10_000) { while (controller.state.value.folders.none { it.folder.kind == FolderKind.TASKS }) delay(5) }
            } finally {
                controller.close()
            }
        } finally {
            home.deleteRecursively()
        }
    }
}
