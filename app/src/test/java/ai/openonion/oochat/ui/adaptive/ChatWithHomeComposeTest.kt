package ai.openonion.oochat.ui.adaptive

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The chat with the agent's Home beside it — see [ChatWithHome]. The panes here are stand-ins: what
 * matters is where they go, and that the chat survives Home coming and going the way it does when
 * a foldable is unfolded and folded.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatWithHomeComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var placement by mutableStateOf(HomePlacement.OWN_SCREEN)

    /** A chat with something to lose: a counter that lives in the pane's own state. */
    private val chat: @Composable (Modifier) -> Unit = { modifier ->
        var taps by remember { mutableIntStateOf(0) }
        Box(modifier.testTag("chat")) {
            Text("taps: $taps", Modifier.testTag("taps").clickable { taps++ })
        }
    }

    private val home: @Composable (Modifier) -> Unit = { modifier ->
        Box(modifier.testTag("home")) { Text("home") }
    }

    private fun show(initial: HomePlacement) {
        placement = initial
        composeRule.setContent { ChatWithHome(placement = placement, chat = chat, home = home) }
    }

    @Test
    fun `Home is drawn to the right of the chat when the placement says so`() {
        show(HomePlacement.BESIDE_CHAT)

        composeRule.onNodeWithTag("chat").assertIsDisplayed()
        composeRule.onNodeWithTag("home").assertIsDisplayed()
        val chatBounds = composeRule.onNodeWithTag("chat").getBoundsInRoot()
        val homeBounds = composeRule.onNodeWithTag("home").getBoundsInRoot()
        assertTrue("Home starts where the chat ends", homeBounds.left >= chatBounds.right)
        assertTrue("neither pane is squeezed out", chatBounds.width > 100.dp && homeBounds.width > 100.dp)
    }

    @Test
    fun `Home is not drawn at all when it has its own screen`() {
        show(HomePlacement.OWN_SCREEN)

        composeRule.onNodeWithTag("chat").assertIsDisplayed()
        composeRule.onNodeWithTag("home").assertDoesNotExist()
    }

    @Test
    fun `the chat keeps its state when Home appears and disappears`() {
        show(HomePlacement.OWN_SCREEN)
        composeRule.onNodeWithTag("taps").performClick()
        composeRule.onNodeWithTag("taps").performClick()
        composeRule.onNodeWithTag("taps").assertTextEquals("taps: 2")

        // Unfold, then fold again.
        composeRule.runOnIdle { placement = HomePlacement.BESIDE_CHAT }
        composeRule.onNodeWithTag("home").assertIsDisplayed()
        composeRule.onNodeWithTag("taps").assertTextEquals("taps: 2")
        composeRule.runOnIdle { placement = HomePlacement.OWN_SCREEN }
        composeRule.onNodeWithTag("home").assertDoesNotExist()
        composeRule.onNodeWithTag("taps").assertTextEquals("taps: 2")
    }
}
