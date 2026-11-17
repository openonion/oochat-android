package ai.openonion.oochat.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The type is Inter, the chat design's face (W16AAPPLE-112), bundled so every device draws the
 * same letters; monospace stays the system's, where the text is code or ticks (W16AAPPLE-49).
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

    @Test
    fun `every text style is Inter`() {
        styles.forEach { (name, style) ->
            assertEquals("$name is ${style.fontFamily}, not Inter", Inter, style.fontFamily)
        }
    }

    @Test
    fun `the only bundled fonts are Inter's two, with its licence`() {
        val fonts = File(module, "src/main/res/font").listFiles().orEmpty().map { it.name }.sorted()
        assertEquals(listOf("inter.ttf", "inter_italic.ttf"), fonts)
        // The OFL lets the font be shipped only alongside its licence.
        assertTrue(File(module, "src/main/assets/licenses/inter_OFL.txt").isFile)
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
