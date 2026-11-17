package ai.openonion.oochat.ui.theme

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a role is drawn is part of whether it is legible (W16AAPPLE-49). [ColorSchemePaletteTest]
 * holds the pairs of roles; this holds the places the app puts them, because the palette
 * deliberately stops short of the raised containers: `outline` captions clear 4.5:1 up to
 * `surfaceContainerHigh`, and `outlineVariant` borders clear 3:1 up to `surfaceContainer`.
 */
class ContrastUsageTest {

    private val containers = Regex("""(?<![A-Za-z])(?:Surface|Card|Box|Row|Column)\(""")

    @Test
    fun `a caption on the highest container is onSurfaceVariant, not outline`() {
        val outlineText = Regex("""colorScheme\.outline\b(?!Variant)""")
        val offenders = SourceScan.calls(containers)
            .filter { "surfaceContainerHighest" in it.text }
            .filter { outlineText.containsMatchIn(it.text + it.block) }
            .map { it.where }

        assertTrue("outline captions on surfaceContainerHighest (3.9:1 light, 4.1:1 dark): $offenders", offenders.isEmpty())
    }

    @Test
    fun `an outlineVariant border is not drawn on a high container`() {
        val highContainer = Regex("""surfaceContainerHigh(?:est)?""")
        // The other way a border is put on a container: a modifier chain, .background(...).border(...).
        val chained = Regex(
            """background\(MaterialTheme\.colorScheme\.surfaceContainerHigh(?:est)?\)\s*\.border\([^)]*?outlineVariant"""
        )

        val byArguments = SourceScan.calls(containers)
            .filter { highContainer.containsMatchIn(it.text) && "outlineVariant" in it.text }
            .map { it.where }
        val byModifier = mutableListOf<String>()
        SourceScan.kotlinFiles().filter { "/ui/theme/" !in it.invariantSeparatorsPath }.forEach { file ->
            val text = file.readText()
            chained.findAll(text).forEach { match ->
                byModifier += "${file.name}:${text.take(match.range.first).count { it == '\n' } + 1}"
            }
        }

        val offenders = byArguments + byModifier
        assertTrue("outlineVariant borders on a high container (2.7:1, under 3:1): $offenders", offenders.isEmpty())
    }
}
