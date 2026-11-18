package com.notebudget.app.feature.features

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.notebudget.app.R
import com.notebudget.app.core.ui.appViewModel
import com.notebudget.app.core.ui.tip.Button
import com.notebudget.app.core.ui.tip.stringResource
import com.notebudget.app.domain.model.AppFeature
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.notebudget.app.core.common.AppContainer
import kotlinx.coroutines.launch

class FeaturesWizardViewModel(private val container: AppContainer) : ViewModel() {
    /** Writes the chosen set and ends the wizard; the app continues on its own (D366). */
    fun finish(chosen: Set<AppFeature>) {
        viewModelScope.launch {
            runCatching {
                container.settings.setFeatures(chosen)
                container.settings.markFeaturesChosen()
            }
        }
    }
}

/**
 * The first-run page (D366): every section of the app as a switch, starting with the defaults
 * (note folders and text notes on). Done writes the set; nothing is asked again.
 */
@Composable
fun FeaturesWizardScreen(
    viewModel: FeaturesWizardViewModel = appViewModel { FeaturesWizardViewModel(it) },
) {
    var chosen by remember { mutableStateOf(AppFeature.FIRST_RUN) }
    Scaffold(Modifier.fillMaxSize()) { innerPadding ->
        Column(
            Modifier.fillMaxSize().padding(innerPadding).imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.features_wizard_title), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.features_wizard_body), style = MaterialTheme.typography.bodyLarge)
            FeatureSwitches(
                enabled = chosen,
                countOf = { 0 },
                onSet = { feature, on ->
                    chosen = if (on) chosen + feature else chosen - feature
                },
            )
            Button(
                onClick = { viewModel.finish(chosen) },
                enabled = AppFeature.NOTE_FOLDERS in chosen || AppFeature.TASK_FOLDERS in chosen,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.features_done))
            }
        }
    }
}
