package ai.openonion.oochat.ui.adaptive

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The tab not showing takes no room, but its content is still laid out at full size, so where that
 * content ends up depends on which way the row runs. A touch must reach the tab that is showing
 * whichever way that is: in a right-to-left layout the hidden page lands over the chat.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatWithHomeDirectionComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var chatTaps = 0
    private var homeTaps = 0
    private var tab by mutableStateOf(HomeTab.CHAT)

    private fun show(direction: LayoutDirection) {
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                ChatWithHome(
                    placement = HomePlacement.TABBED,
                    tab = tab,
                    onTabChange = { tab = it },
                    homeLabel = "Home",
                    chat = { modifier -> Box(modifier.fillMaxSize().testTag("chat").clickable { chatTaps++ }) },
                    home = { modifier -> Box(modifier.fillMaxSize().testTag("home").clickable { homeTaps++ }) }
                )
            }
        }
    }

    private fun tapsGoToTheTabShowing(direction: LayoutDirection) {
        show(direction)
        composeRule.onNodeWithText("Home").performClick()
        composeRule.onNodeWithTag("home").performClick()
        assertEquals("Home's tab is showing, so the tap is Home's", 1, homeTaps)
        assertEquals("the hidden chat takes none", 0, chatTaps)

        composeRule.onNodeWithText("Chat").performClick()
        composeRule.onNodeWithTag("chat").performClick()
        assertEquals("the chat's tab is showing, so the tap is the chat's", 1, chatTaps)
        assertEquals("the hidden Home takes none", 1, homeTaps)
    }

    @Test
    fun `left to right, a tap goes to the tab that is showing`() = tapsGoToTheTabShowing(LayoutDirection.Ltr)

    @Test
    fun `right to left, a tap goes to the tab that is showing`() = tapsGoToTheTabShowing(LayoutDirection.Rtl)
}
