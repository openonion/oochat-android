package ai.openonion.oochat.ui.theme

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every colour comes from the theme (W16AAPPLE-49). A screen that writes its own hex value or a
 * named colour is outside the palette, and in dark mode usually outside legibility too, so this
 * reads the sources and fails on any colour literal outside `ui/theme`.
 */
class ThemeColorsOnlyInThemeTest {

    private val sources: File = listOf("src/main/java", "app/src/main/java").map(::File).first { it.isDirectory }

    private val literal = Regex("""\bColor\(\s*0x|\bColor\.(Red|Green|Blue|Yellow|Cyan|Magenta|Gray|DarkGray|LightGray)\b""")

    @Test
    fun `no screen or component writes a colour of its own`() {
        val offenders = sources.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && "/ui/theme/" !in it.invariantSeparatorsPath }
            .flatMap { file ->
                file.readLines().withIndex()
                    .filter { (_, line) -> literal.containsMatchIn(line) && !line.trimStart().startsWith("//") }
                    .map { (index, line) -> "${file.name}:${index + 1}: ${line.trim()}" }
            }
            .toList()

        assertTrue("colour literals outside ui/theme:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }
}
