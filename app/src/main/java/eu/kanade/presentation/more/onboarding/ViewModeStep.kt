package eu.kanade.presentation.more.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.more.settings.screen.SettingsDataScreen
import mihon.app.di.appGraph
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

internal class ViewModeStep : OnboardingStep {

    override val isComplete: Boolean = true

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val directoryViewPref = remember { context.appGraph.storagePreferences.directoryView }
        val directoryView by directoryViewPref.collectAsState()
        val updateLibrary = SettingsDataScreen.libraryUpdater()

        Column(modifier = Modifier.selectableGroup()) {
            Text(
                text = stringResource(MR.strings.onboarding_view_mode_info),
                modifier = Modifier.padding(16.dp),
            )
            ViewModeItem(
                title = stringResource(MR.strings.label_library),
                subtitle = stringResource(MR.strings.onboarding_view_mode_library_summary),
                selected = !directoryView,
                onClick = {
                    // Back in library view, the library catches up with the folders as the settings switch makes it;
                    // the update does nothing in directory view, so that is left first
                    if (directoryViewPref.get()) {
                        directoryViewPref.set(false)
                        updateLibrary()
                    }
                },
            )
            ViewModeItem(
                title = stringResource(MR.strings.pref_directory_view),
                subtitle = stringResource(MR.strings.pref_directory_view_summary),
                selected = directoryView,
                onClick = { directoryViewPref.set(true) },
            )
        }
    }

    @Composable
    private fun ViewModeItem(
        title: String,
        subtitle: String,
        selected: Boolean,
        onClick: () -> Unit,
    ) {
        ListItem(
            modifier = Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
            leadingContent = { RadioButton(selected = selected, onClick = null) },
            supportingContent = { Text(text = subtitle) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            content = { Text(text = title) },
        )
    }
}
