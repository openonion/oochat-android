package ai.openonion.oochat.ui.adaptive

import ai.openonion.oochat.domain.model.ControlCenterApp
import ai.openonion.oochat.ui.controlcenter.ControlCenterScreenContent
import ai.openonion.oochat.ui.dashboard.DashboardScreenContent
import ai.openonion.oochat.ui.theme.ConnectOnionTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The agent's Home when it sits beside the chat rather than replacing it. The chat is on screen,
 * so the Home page has nowhere to go back to and nothing to say about the conversation waiting:
 * a back arrow would leave the pane dead, and the notice would point at what is already in view.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeBesideChatComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val waiting = "The conversation is waiting for you"

    private fun setDashboard(besideChat: Boolean, chatAwaitsUser: Boolean = false, onBack: () -> Unit = {}) {
        composeRule.setContent {
            ConnectOnionTheme {
                DashboardScreenContent(
                    document = "<h1>hi</h1>",
                    allowedSkills = emptyList(),
                    onRunSkill = {},
                    onNavigateBack = onBack,
                    agentName = "Aria",
                    chatAwaitsUser = chatAwaitsUser,
                    navigationBlocked = false,
                    onNavigationBlocked = {},
                    onDismissNavigationBlocked = {},
                    besideChat = besideChat
                )
            }
        }
    }

    private fun setControlCenter(besideChat: Boolean, chatAwaitsUser: Boolean = false, onBack: () -> Unit = {}) {
        composeRule.setContent {
            ConnectOnionTheme {
                ControlCenterScreenContent(
                    app = ControlCenterApp(url = "https://r-1.apps.example.net/index.html", revision = "sha256:" + "a".repeat(64)),
                    supported = true,
                    stopped = false,
                    onReload = {},
                    content = { Text("app") },
                    onNavigateBack = onBack,
                    agentName = "Aria",
                    chatAwaitsUser = chatAwaitsUser,
                    navigationBlocked = false,
                    onDismissNavigationBlocked = {},
                    besideChat = besideChat
                )
            }
        }
    }

    @Test
    fun `a Dashboard beside the chat is titled with the agent but has no way back`() {
        setDashboard(besideChat = true)

        composeRule.onNodeWithText("Aria").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Back").assertDoesNotExist()
    }

    @Test
    fun `a Dashboard beside the chat does not announce a conversation that is in view`() {
        setDashboard(besideChat = true, chatAwaitsUser = true)

        composeRule.onNodeWithText(waiting).assertDoesNotExist()
    }

    @Test
    fun `a Dashboard on its own screen still has its back arrow and waiting notice`() {
        var backCount = 0
        setDashboard(besideChat = false, chatAwaitsUser = true, onBack = { backCount++ })

        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.onNodeWithText(waiting).assertIsDisplayed()

        assertEquals(1, backCount)
    }

    @Test
    fun `a Control Center beside the chat is titled with the agent but has no way back`() {
        setControlCenter(besideChat = true)

        composeRule.onNodeWithText("Aria").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Back").assertDoesNotExist()
    }

    @Test
    fun `a Control Center beside the chat does not announce a conversation that is in view`() {
        setControlCenter(besideChat = true, chatAwaitsUser = true)

        composeRule.onNodeWithText(waiting).assertDoesNotExist()
    }

    @Test
    fun `a Control Center on its own screen still has its back arrow and waiting notice`() {
        var backCount = 0
        setControlCenter(besideChat = false, chatAwaitsUser = true, onBack = { backCount++ })

        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.onNodeWithText(waiting).assertIsDisplayed()

        assertEquals(1, backCount)
    }
}
