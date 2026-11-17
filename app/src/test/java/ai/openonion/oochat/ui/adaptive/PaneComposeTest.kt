package ai.openonion.oochat.ui.adaptive
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [Pane] around screens that fill all the space they are given and take no modifier of their own,
 * which is how the chat is built: its modifier goes on a layout inside its navigation drawer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PaneComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `each pane gets half the row though what it holds fills all it is given`() {
        composeRule.setContent {
            ChatWithHome(
                placement = HomePlacement.BESIDE_CHAT,
                chat = { modifier -> Pane(modifier) { Box(Modifier.fillMaxSize().testTag("chat")) } },
                home = { modifier -> Pane(modifier) { Box(Modifier.fillMaxSize().testTag("home")) } }
            )
        }

        val chat = composeRule.onNodeWithTag("chat").getBoundsInRoot()
        val home = composeRule.onNodeWithTag("home").getBoundsInRoot()
        assertTrue("Home is to the right of the chat", home.left >= chat.right)
        assertTrue("Home is not squeezed out", home.width > 100.dp)
        assertEquals("the two share the row", chat.width.value, home.width.value, 2f)
    }
}
