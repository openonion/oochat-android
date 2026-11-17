package ai.openonion.oochat.ui.adaptive

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [HomeScreenRoute] reads the real window size, set here with Robolectric qualifiers.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeScreenRouteComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var left = 0

    private fun show(hasHome: Boolean = true) {
        left = 0
        composeRule.setContent { HomeScreenRoute(onLeave = { left++ }, hasHome = hasHome) { Text("home screen") } }
        composeRule.waitForIdle()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h800dp")
    fun `on a phone the screen is left once, for the chat with Home as a tab`() {
        show()

        composeRule.onNodeWithText("home screen").assertDoesNotExist()
        assertEquals("left exactly once", 1, left)
    }

    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h800dp")
    fun `on a phone a Home that has no tab keeps its screen`() {
        show(hasHome = false)

        composeRule.onNodeWithText("home screen").assertIsDisplayed()
        assertEquals("nothing to leave", 0, left)
    }

    @Test
    @Config(sdk = [34], qualifiers = "w915dp-h412dp")
    fun `on a phone on its side the screen is left once too`() {
        show()

        composeRule.onNodeWithText("home screen").assertDoesNotExist()
        assertEquals("left exactly once", 1, left)
    }

    @Test
    @Config(sdk = [34], qualifiers = "w900dp-h800dp")
    fun `on a tablet the screen is left once, for the chat with Home beside it`() {
        show()

        assertEquals("left exactly once", 1, left)
    }

    @Test
    @Config(sdk = [34], qualifiers = "w700dp-h840dp")
    fun `on an unfolded foldable the screen shows nothing while it is left`() {
        show()

        composeRule.onNodeWithText("home screen").assertDoesNotExist()
        assertEquals("left exactly once", 1, left)
    }

    @Test
    @Config(sdk = [34], qualifiers = "w900dp-h800dp")
    fun `on a tablet a Home that cannot sit beside the chat keeps its screen`() {
        composeRule.setContent {
            HomeScreenRoute(onLeave = { left++ }, hasHome = false) { Text("home screen") }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("home screen").assertIsDisplayed()
        assertEquals("nothing to leave", 0, left)
    }

    @Test
    @Config(sdk = [34])
    fun `a foldable unfolded while Home is open leaves it then, not only when it opens`() {
        var placement by mutableStateOf(HomePlacement.OWN_SCREEN)
        composeRule.setContent {
            HomeScreenRoute(onLeave = { left++ }, placement = placement) { Text("home screen") }
        }
        composeRule.onNodeWithText("home screen").assertIsDisplayed()
        assertEquals("folded: nothing to leave", 0, left)

        composeRule.runOnIdle { placement = HomePlacement.BESIDE_CHAT }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("home screen").assertDoesNotExist()
        assertEquals("unfolded: left once", 1, left)
    }
}
