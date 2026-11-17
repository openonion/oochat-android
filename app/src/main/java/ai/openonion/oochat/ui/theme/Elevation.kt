package ai.openonion.oochat.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.unit.dp

/**
 * Surfaces sit flat on the page (W16AAPPLE-49). What lifts a menu or a popup is a hairline: a
 * 1dp shadow and a 1dp outline, not a drop shadow and not a tint. Nothing in the app casts a
 * larger shadow than [hairline], and [ColorScheme.surfaceTint] is clear, so elevation never
 * washes a surface with the brand green either.
 */
object Elevation {
    val hairline = 1.dp
}

/** The 1dp outline an elevated surface wears so it still reads as set apart on a white page. */
val MaterialTheme.hairline: BorderStroke
    @Composable
    @ReadOnlyComposable
    get() = BorderStroke(Elevation.hairline, colorScheme.outlineVariant)
