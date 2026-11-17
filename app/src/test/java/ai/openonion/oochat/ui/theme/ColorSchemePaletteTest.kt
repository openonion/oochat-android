package ai.openonion.oochat.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * The palette is the brand ramp on warm neutral greys, and every pair of roles that is read as
 * text or as a boundary is legible (W16AAPPLE-49). This is the whole of the app's colour, so a
 * role that drifts from the ramp or loses contrast fails here rather than on one screen.
 */
class ColorSchemePaletteTest {

    private val schemes = listOf("light" to LightColorScheme, "dark" to DarkColorScheme)

    private fun channel(value: Float): Double =
        if (value <= 0.03928f) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)

    private fun luminance(color: Color): Double =
        0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)

    private fun contrast(a: Color, b: Color): Double {
        val (light, dark) = luminance(a).let { la -> luminance(b).let { lb -> maxOf(la, lb) to minOf(la, lb) } }
        return (light + 0.05) / (dark + 0.05)
    }

    private fun argb(color: Color) = (color.red * 255 + 0.5f).toInt().let { r ->
        Triple(r, (color.green * 255 + 0.5f).toInt(), (color.blue * 255 + 0.5f).toInt())
    }

    private fun hex(color: Color) = argb(color).let { (r, g, b) -> "#%02X%02X%02X".format(r, g, b) }

    @Test
    fun `the brand ramp is the primary colour, 900 on white and 500 on dark`() {
        assertEquals("#14532D", hex(LightColorScheme.primary))
        assertEquals("#F0FDF4", hex(LightColorScheme.primaryContainer))
        assertEquals("#22C55E", hex(DarkColorScheme.primary))
        assertEquals("#14532D", hex(DarkColorScheme.primaryContainer))
        assertEquals("#F0FDF4", hex(DarkColorScheme.onPrimaryContainer))
    }

    @Test
    fun `the brand roles are green`() {
        for ((name, scheme) in schemes) {
            listOf(
                "primary" to scheme.primary,
                "primaryContainer" to scheme.primaryContainer,
                "inversePrimary" to scheme.inversePrimary
            ).forEach { (role, color) ->
                val (r, g, b) = argb(color)
                assertTrue("$name $role ${hex(color)} is not green", g > r && g > b)
            }
        }
    }

    private fun neutralRoles(s: ColorScheme) = mapOf(
        "background" to s.background, "onBackground" to s.onBackground,
        "surface" to s.surface, "onSurface" to s.onSurface,
        "surfaceVariant" to s.surfaceVariant, "onSurfaceVariant" to s.onSurfaceVariant,
        "outline" to s.outline, "outlineVariant" to s.outlineVariant,
        "secondary" to s.secondary, "onSecondary" to s.onSecondary,
        "secondaryContainer" to s.secondaryContainer, "onSecondaryContainer" to s.onSecondaryContainer,
        "tertiary" to s.tertiary, "onTertiary" to s.onTertiary,
        "tertiaryContainer" to s.tertiaryContainer, "onTertiaryContainer" to s.onTertiaryContainer,
        "inverseSurface" to s.inverseSurface, "inverseOnSurface" to s.inverseOnSurface,
        "surfaceDim" to s.surfaceDim, "surfaceBright" to s.surfaceBright,
        "surfaceContainerLowest" to s.surfaceContainerLowest, "surfaceContainerLow" to s.surfaceContainerLow,
        "surfaceContainer" to s.surfaceContainer, "surfaceContainerHigh" to s.surfaceContainerHigh,
        "surfaceContainerHighest" to s.surfaceContainerHighest
    )

    @Test
    fun `every neutral is a warm grey, red over green over blue and barely tinted`() {
        for ((name, scheme) in schemes) {
            neutralRoles(scheme).forEach { (role, color) ->
                val (r, g, b) = argb(color)
                assertTrue("$name $role ${hex(color)} is not warm (red >= green >= blue)", r >= g && g >= b)
                assertTrue("$name $role ${hex(color)} is tinted, not a grey", r - b <= 16)
            }
        }
    }

    private fun textPairs(s: ColorScheme) = listOf(
        Triple("onSurface on surface", s.onSurface, s.surface),
        Triple("onSurface on surfaceContainerLow", s.onSurface, s.surfaceContainerLow),
        Triple("onSurface on surfaceContainer", s.onSurface, s.surfaceContainer),
        Triple("onSurface on surfaceContainerHigh", s.onSurface, s.surfaceContainerHigh),
        Triple("onSurface on surfaceContainerHighest", s.onSurface, s.surfaceContainerHighest),
        Triple("onSurfaceVariant on surface", s.onSurfaceVariant, s.surface),
        Triple("onSurfaceVariant on surfaceContainer", s.onSurfaceVariant, s.surfaceContainer),
        Triple("onSurfaceVariant on surfaceContainerHigh", s.onSurfaceVariant, s.surfaceContainerHigh),
        // outline stops at surfaceContainerHigh, so a caption on the highest container is onSurfaceVariant.
        Triple("onSurfaceVariant on surfaceContainerHighest", s.onSurfaceVariant, s.surfaceContainerHighest),
        // outline is the caption colour at 50+ call sites, so it is text.
        Triple("outline on surface", s.outline, s.surface),
        Triple("outline on surfaceContainerLow", s.outline, s.surfaceContainerLow),
        Triple("outline on surfaceContainer", s.outline, s.surfaceContainer),
        Triple("outline on surfaceContainerHigh", s.outline, s.surfaceContainerHigh),
        Triple("primary on surface", s.primary, s.surface),
        Triple("tertiary on surface", s.tertiary, s.surface),
        Triple("onPrimary on primary", s.onPrimary, s.primary),
        Triple("onPrimaryContainer on primaryContainer", s.onPrimaryContainer, s.primaryContainer),
        Triple("onSecondary on secondary", s.onSecondary, s.secondary),
        Triple("onSecondaryContainer on secondaryContainer", s.onSecondaryContainer, s.secondaryContainer),
        Triple("onTertiary on tertiary", s.onTertiary, s.tertiary),
        Triple("onTertiaryContainer on tertiaryContainer", s.onTertiaryContainer, s.tertiaryContainer),
        Triple("onError on error", s.onError, s.error),
        Triple("onErrorContainer on errorContainer", s.onErrorContainer, s.errorContainer),
        Triple("error on surface", s.error, s.surface),
        Triple("inverseOnSurface on inverseSurface", s.inverseOnSurface, s.inverseSurface),
        // A snackbar's action is drawn in inversePrimary on inverseSurface.
        Triple("inversePrimary on inverseSurface", s.inversePrimary, s.inverseSurface)
    )

    @Test
    fun `text roles are at least 4_5 to 1 on what they are read against`() {
        for ((name, scheme) in schemes) {
            textPairs(scheme).forEach { (pair, fg, bg) ->
                val ratio = contrast(fg, bg)
                assertTrue("$name $pair is %.2f:1, under 4.5:1".format(ratio), ratio >= 4.5)
            }
        }
    }

    @Test
    fun `dividers and the brand colour are at least 3 to 1 on the surfaces they sit on`() {
        for ((name, s) in schemes.map { it.first to it.second }) {
            listOf(
                "outlineVariant on surface" to contrast(s.outlineVariant, s.surface),
                "outlineVariant on surfaceContainerLow" to contrast(s.outlineVariant, s.surfaceContainerLow),
                "outlineVariant on surfaceContainer" to contrast(s.outlineVariant, s.surfaceContainer),
                "primary on surface" to contrast(s.primary, s.surface)
            ).forEach { (pair, ratio) ->
                assertTrue("$name $pair is %.2f:1, under 3:1".format(ratio), ratio >= 3.0)
            }
        }
    }
}
