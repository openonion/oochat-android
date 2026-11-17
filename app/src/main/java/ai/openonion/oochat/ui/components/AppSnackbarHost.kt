package ai.openonion.oochat.ui.components

import ai.openonion.oochat.ui.theme.Elevation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The app's snackbars. Material's own casts a 6dp shadow that can't be set from outside, which
 * the flat, hairline look doesn't allow (W16AAPPLE-49), so this draws the same thing — the
 * message, and the action when there is one — on a surface that lifts by [Elevation.hairline].
 * [SnackbarHost] still does the queueing, the timeout and the screen-reader announcement.
 */
@Composable
fun AppSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(hostState, modifier) { data -> AppSnackbar(data) }
}

@Composable
private fun AppSnackbar(data: SnackbarData) {
    Surface(
        modifier = Modifier.padding(12.dp),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shadowElevation = Elevation.hairline
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.padding(start = 16.dp, end = 8.dp)
        ) {
            Text(
                text = data.visuals.message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 14.dp)
            )
            data.visuals.actionLabel?.let { label ->
                TextButton(
                    onClick = { data.performAction() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.inversePrimary)
                ) {
                    Text(label)
                }
            }
        }
    }
}
