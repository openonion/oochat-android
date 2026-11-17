package ai.openonion.oochat.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The logs console's colours. The console is a dark panel in either theme
 * ([StatusColors.logSurface]), so these are tuned to it alone: the brand green
 * and the pale warm greys, with the error red. The level is also spelled out
 * on every line, so a hue is never the only cue. All of them clear 4.5:1 on
 * the console.
 */
internal object LogColors {
    val error = errorDark
    val warning = Stone100
    val info = Green500
    val debug = Stone300
    val verbose = Stone400

    /** The tag gets its own fixed colour, independent of level, to scan across lines of any level. */
    val tag = Green50
    val body = Stone200

    /** Dimmer than the body: once you know roughly when, the exact time is the least useful part of a line. */
    val timestamp = Stone400

    fun forLevel(level: Char): Color = when (level) {
        'E' -> error
        'W' -> warning
        'I' -> info
        'D' -> debug
        else -> verbose
    }
}
