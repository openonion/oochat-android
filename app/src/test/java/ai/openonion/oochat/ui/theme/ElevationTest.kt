package ai.openonion.oochat.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Flat surfaces, lifted by a hairline and nothing heavier (W16AAPPLE-49). Material's own
 * components bring a 3dp or 6dp shadow and a primary tint of their own, so the rule is held over
 * every call in the app rather than over the ones a test happens to render.
 */
class ElevationTest {

    @Test
    fun `elevation does not tint a surface`() {
        assertEquals(0f, LightColorScheme.surfaceTint.alpha)
        assertEquals(0f, DarkColorScheme.surfaceTint.alpha)
    }

    @Test
    fun `the hairline is 1dp`() {
        assertEquals(1f, Elevation.hairline.value)
    }

    @Test
    fun `no shadow or tonal elevation is written above the hairline`() {
        val elevation = Regex("""(?:shadow\(\s*elevation|shadowElevation|tonalElevation)\s*=\s*(\d+(?:\.\d+)?)\.dp""")
        val offenders = SourceScan.kotlinFiles()
            .filter { "/ui/theme/" !in it.invariantSeparatorsPath }
            .flatMap { file ->
                file.readLines().withIndex()
                    .filter { (_, line) -> !line.trimStart().startsWith("//") }
                    .filter { (_, line) -> elevation.findAll(line).any { it.groupValues[1].toDouble() > 1.0 } }
                    .map { (index, line) -> "${file.name}:${index + 1}: ${line.trim()}" }
            }
            .toList()

        assertTrue("elevation above 1dp:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test
    fun `every menu sets its own shadow instead of taking Material's 3dp`() {
        val menus = SourceScan.calls(Regex("""(?<![A-Za-z])(?:Exposed)?DropdownMenu\("""))
        assertTrue("found no menus to check", menus.isNotEmpty())

        val offenders = menus.filter { "shadowElevation" !in it.text }.map { it.where }

        assertTrue("menus on Material's default shadow: $offenders", offenders.isEmpty())
    }

    @Test
    fun `snackbars come only from the app's own host`() {
        val offenders = SourceScan.calls(Regex("""(?<![A-Za-z])SnackbarHost\("""))
            .filter { !it.where.startsWith("AppSnackbarHost.kt") }
            .map { it.where }

        assertTrue("Material's default snackbar (6dp shadow) is used at: $offenders", offenders.isEmpty())
    }
}
