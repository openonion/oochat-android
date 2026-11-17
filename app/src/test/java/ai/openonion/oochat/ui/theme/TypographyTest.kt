package ai.openonion.oochat.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The type is the system's own face, with monospace where the text is code or ticks
 * (W16AAPPLE-49). Nothing is bundled, so text matches the device and the app does not ship a
 * font of its own.
 */
class TypographyTest {

    private val module: File = listOf(".", "app").map(::File).first { File(it, "src/main").isDirectory }

    private val styles = AppTypography.let {
        mapOf(
            "displayLarge" to it.displayLarge, "displayMedium" to it.displayMedium, "displaySmall" to it.displaySmall,
            "headlineLarge" to it.headlineLarge, "headlineMedium" to it.headlineMedium, "headlineSmall" to it.headlineSmall,
            "titleLarge" to it.titleLarge, "titleMedium" to it.titleMedium, "titleSmall" to it.titleSmall,
            "bodyLarge" to it.bodyLarge, "bodyMedium" to it.bodyMedium, "bodySmall" to it.bodySmall,
            "labelLarge" to it.labelLarge, "labelMedium" to it.labelMedium, "labelSmall" to it.labelSmall
        )
    }

    // Default and SansSerif are both the platform's own sans face; a family built from font files is not.
    private val systemFaces = setOf<FontFamily?>(null, FontFamily.Default, FontFamily.SansSerif)

    @Test
    fun `every text style is the system font`() {
        styles.forEach { (name, style) ->
            assertTrue("$name is ${style.fontFamily}, not the system font", style.fontFamily in systemFaces)
        }
    }

    @Test
    fun `the app bundles no font files`() {
        assertFalse("res/font should not exist", File(module, "src/main/res/font").exists())
    }

    @Test
    fun `no screen builds a font family of its own`() {
        val custom = Regex("""\bFontFamily\(|\bFont\(""")
        val offenders = File(module, "src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && "/ui/theme/" !in it.invariantSeparatorsPath }
            .flatMap { file ->
                file.readLines().withIndex()
                    .filter { (_, line) -> custom.containsMatchIn(line) && !line.trimStart().startsWith("//") }
                    .map { (index, line) -> "${file.name}:${index + 1}: ${line.trim()}" }
            }
            .toList()

        assertTrue("fonts built outside ui/theme:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test
    fun `ticking figures are fixed width in monospace, whatever the system font`() {
        val style = TextStyle().tabularFigures()

        assertEquals(FontFamily.Monospace, style.fontFamily)
        assertEquals("tnum", style.fontFeatureSettings)
    }
}
