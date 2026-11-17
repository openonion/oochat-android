package ai.openonion.oochat.ui.theme

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Small radii, from one scale (W16AAPPLE-49): nothing in the app is rounder than 12dp apart from
 * a circle, and every radius comes from the theme rather than a number written where it is used.
 */
class ShapesTest {

    private val module: File = listOf(".", "app").map(::File).first { File(it, "src/main").isDirectory }

    // At density 1 a dp corner measures its own size in pixels.
    private fun CornerSize.dp(): Float = toPx(Size(100f, 100f), Density(1f))

    private fun CornerBasedShape.allCorners() = listOf(topStart, topEnd, bottomStart, bottomEnd).map { it.dp() }

    @Test
    fun `the scale is 2, 4, 6, 8 and 12dp`() {
        val scale = listOf(AppShapes.extraSmall, AppShapes.small, AppShapes.medium, AppShapes.large, AppShapes.extraLarge)

        assertEquals(listOf(2f, 4f, 6f, 8f, 12f), scale.map { it.allCorners().distinct().single() })
    }

    @Test
    fun `chat bubbles and image tiles use the scale`() {
        assertEquals(8f, MessageBubbleToken.BorderRadius.value)
        assertEquals(4f, ImageGridToken.BorderRadius.value)
    }

    @Test
    fun `the composer takes the design's own radius, named in the theme`() {
        // Off the scale on purpose (W16AAPPLE-112), so it lives in Radii rather
        // than as a number in the composer.
        assertEquals(22f, InputBarToken.BorderRadius.value)
        assertEquals(Radii.composer, InputBarToken.BorderRadius)
    }

    @Test
    fun `a bubble is round except a small tail on the sender's side`() {
        val user = bubbleShape(isUser = true)
        val agent = bubbleShape(isUser = false)

        assertEquals(listOf(8f, 8f, 8f, 2f), listOf(user.topStart, user.topEnd, user.bottomStart, user.bottomEnd).map { it.dp() })
        assertEquals(listOf(2f, 8f, 8f, 8f), listOf(agent.topStart, agent.topEnd, agent.bottomStart, agent.bottomEnd).map { it.dp() })
    }

    @Test
    fun `no screen writes a radius of its own`() {
        // A number inside RoundedCornerShape( is a radius no one tunes: 10.dp, 50, 28.dp.
        val literal = Regex("""\bRoundedCornerShape\(\s*\d""")
        val offenders = File(module, "src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && "/ui/theme/" !in it.invariantSeparatorsPath }
            .flatMap { file ->
                file.readLines().withIndex()
                    .filter { (_, line) -> literal.containsMatchIn(line) && !line.trimStart().startsWith("//") }
                    .map { (index, line) -> "${file.name}:${index + 1}: ${line.trim()}" }
            }
            .toList()

        assertTrue("radii written outside ui/theme:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test
    fun `every filled, tonal and outlined button takes the theme's shape, not Material's pill`() {
        // Material draws these fully round unless told otherwise, and the theme's scale cannot reach
        // that default, so each call has to pass its own. (Icon and text buttons have no fill to round.)
        val buttons = SourceScan.calls(Regex("""(?<![A-Za-z])(?:Outlined|FilledTonal|Elevated)?Button\("""))
        assertTrue("found no buttons to check", buttons.isNotEmpty())

        val pills = buttons.filter { Regex("""\bshape\s*=""") !in it.text }.map { it.where }

        assertTrue("buttons on Material's default pill shape: $pills", pills.isEmpty())
    }
}
