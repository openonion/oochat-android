package ai.openonion.oochat.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Shared warning/success tint scale for status-style chat cards (tool
 * blocked, eval pass/fail, compaction, etc). Mirrors [SpacingTokens] /
 * [LocalSpacing]'s composition-local pattern.
 *
 * The palette is green on warm greys with no other accent (W16AAPPLE-49), so a
 * success is the brand green and a warning is a warm grey: what makes a
 * warning card read as one is its icon and title, not a hue. Errors keep the
 * theme's red. The defaults are the dark-theme set; [DarkStatusColors] /
 * [LightStatusColors] name the two instances the theme actually provides.
 *
 * Access from composables via `MaterialTheme.statusColors.warning` etc.
 */
@Immutable
data class StatusColors(
    val warning: Color = Stone300,
    val warningContainer: Color = Stone500.copy(alpha = 0.16f),
    val success: Color = Green500,
    val successContainer: Color = Green500.copy(alpha = 0.12f),
    // The tiny connection-status indicator, where the text-weight success
    // colour reads too dark at 8dp. A connection still on its way is this same
    // dot pulsing, since the palette has no second accent to spare for it.
    val successDot: Color = Green500,
    // The logs console background. Same value in both instances below — it's
    // a monospace panel meant to stay dark regardless of app theme, tuned to
    // surfaceContainerLowestDark rather than a neutral black so it reads as
    // this app's console, not a foreign slab.
    val logSurface: Color = surfaceContainerLowestDark
)

/** Green 500 and the pale warm greys, 7.7:1 and up against the dark surfaces. */
val DarkStatusColors = StatusColors()

/**
 * The same roles re-toned for the light surfaces, where Green 500 is only
 * 2.3:1: the text roles take the dark end of each scale and clear 4.5:1 both
 * on `surface` and on their own container, and the dot takes Green 600, which
 * clears WCAG 1.4.11's 3.0:1. The containers are tints over the surface, so
 * they are stronger than dark's for the same reason as before: a pale tint
 * over a near-white surface barely registers.
 */
val LightStatusColors = StatusColors(
    warning = Stone700,
    warningContainer = Stone500.copy(alpha = 0.16f),
    success = Green900,
    successContainer = Green500.copy(alpha = 0.16f),
    successDot = Green600
)

val LocalStatusColors = staticCompositionLocalOf { StatusColors() }

/** Convenience accessor: `MaterialTheme.statusColors.warning`. */
val MaterialTheme.statusColors: StatusColors
    @Composable
    @ReadOnlyComposable
    get() = LocalStatusColors.current
