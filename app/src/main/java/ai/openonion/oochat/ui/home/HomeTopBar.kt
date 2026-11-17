package ai.openonion.oochat.ui.home

import ai.openonion.oochat.ui.components.BackTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable

/**
 * The title bar of an agent's Home page, shared by the Dashboard and the Control Center. On its
 * own screen it has the way back to the chat; beside the chat there is nothing to go back to, so
 * it is only the title.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeTopBar(title: String, besideChat: Boolean, onNavigateBack: () -> Unit) {
    val colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
    if (besideChat) {
        TopAppBar(title = { Text(title) }, colors = colors)
    } else {
        BackTopAppBar(title = title, onNavigateBack = onNavigateBack, colors = colors)
    }
}
