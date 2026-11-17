package ai.openonion.oochat.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * The status roles stay inside the palette (W16AAPPLE-49): success is the brand green, a warning
 * is a warm grey told apart by its icon and title, and there is no amber or other accent. Each
 * is still legible on the surface it is drawn on, in both themes.
 */
class StatusColorsPaletteTest {

    private val themes = listOf(
        Triple("light", LightStatusColors, LightColorScheme.surface),
        Triple("dark", DarkStatusColors, DarkColorScheme.surface)
    )

    private fun channel(value: Float): Double =
        if (value <= 0.03928f) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)

    private fun luminance(c: Color) = 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)

    private fun contrast(a: Color, b: Color): Double {
        val hi = maxOf(luminance(a), luminance(b))
        val lo = minOf(luminance(a), luminance(b))
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun rgb(c: Color) = Triple((c.red * 255 + 0.5f).toInt(), (c.green * 255 + 0.5f).toInt(), (c.blue * 255 + 0.5f).toInt())

    private fun isGreen(c: Color) = rgb(c).let { (r, g, b) -> g > r && g > b }

    private fun isWarmGrey(c: Color) = rgb(c).let { (r, g, b) -> r >= g && g >= b && r - b <= 16 }

    @Test
    fun `success is green and a warning is a warm grey, in both themes`() {
        for ((name, status, _) in themes) {
            assertTrue("$name success is not green", isGreen(status.success))
            assertTrue("$name successDot is not green", isGreen(status.successDot))
            assertTrue("$name successContainer is not green", isGreen(status.successContainer.copy(alpha = 1f)))
            assertTrue("$name warning is not a warm grey", isWarmGrey(status.warning))
            assertTrue("$name warningContainer is not a warm grey", isWarmGrey(status.warningContainer.copy(alpha = 1f)))
            assertTrue("$name logSurface is not a warm grey", isWarmGrey(status.logSurface))
        }
    }

    @Test
    fun `status text is at least 4_5 to 1 on the surface and on its own container`() {
        for ((name, status, surface) in themes) {
            listOf(
                "success on surface" to contrast(status.success, surface),
                "success on successContainer" to contrast(status.success, status.successContainer.compositeOver(surface)),
                "warning on surface" to contrast(status.warning, surface),
                "warning on warningContainer" to contrast(status.warning, status.warningContainer.compositeOver(surface))
            ).forEach { (pair, ratio) ->
                assertTrue("$name $pair is %.2f:1, under 4.5:1".format(ratio), ratio >= 4.5)
            }
        }
    }

    @Test
    fun `the status dot is at least 3 to 1 on the surface`() {
        for ((name, status, surface) in themes) {
            val ratio = contrast(status.successDot, surface)
            assertTrue("$name successDot is %.2f:1, under 3:1".format(ratio), ratio >= 3.0)
        }
    }
}
