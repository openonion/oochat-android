package ai.openonion.oochat.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * The logs console is a dark panel in either theme, so its colours are checked against that
 * panel alone: brand green and warm greys with the error red, every one legible on it.
 */
class LogColorsTest {

    private val console = DarkStatusColors.logSurface

    private val all = mapOf(
        "error" to LogColors.error, "warning" to LogColors.warning, "info" to LogColors.info,
        "debug" to LogColors.debug, "verbose" to LogColors.verbose, "tag" to LogColors.tag,
        "body" to LogColors.body, "timestamp" to LogColors.timestamp
    )

    private fun channel(value: Float): Double =
        if (value <= 0.03928f) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)

    private fun luminance(c: Color) = 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)

    private fun contrast(a: Color, b: Color) =
        (maxOf(luminance(a), luminance(b)) + 0.05) / (minOf(luminance(a), luminance(b)) + 0.05)

    private fun rgb(c: Color) = Triple((c.red * 255 + 0.5f).toInt(), (c.green * 255 + 0.5f).toInt(), (c.blue * 255 + 0.5f).toInt())

    @Test
    fun `every log colour is green, a warm grey or the error red`() {
        all.forEach { (role, color) ->
            val (r, g, b) = rgb(color)
            val green = g > r && g > b
            val warmGrey = r >= g && g >= b && r - b <= 16
            assertTrue("$role is neither green nor a warm grey", green || warmGrey || color == errorDark)
        }
    }

    @Test
    fun `every log colour is at least 4_5 to 1 on the console`() {
        all.forEach { (role, color) ->
            val ratio = contrast(color, console)
            assertTrue("$role is %.2f:1 on the console, under 4.5:1".format(ratio), ratio >= 4.5)
        }
    }

    @Test
    fun `each level has its own colour`() {
        assertEquals(LogColors.error, LogColors.forLevel('E'))
        assertEquals(LogColors.warning, LogColors.forLevel('W'))
        assertEquals(LogColors.info, LogColors.forLevel('I'))
        assertEquals(LogColors.debug, LogColors.forLevel('D'))
        assertEquals(LogColors.verbose, LogColors.forLevel('V'))
        assertEquals(5, listOf('E', 'W', 'I', 'D', 'V').map(LogColors::forLevel).toSet().size)
    }
}
