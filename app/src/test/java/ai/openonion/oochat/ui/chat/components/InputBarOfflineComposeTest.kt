package ai.openonion.oochat.ui.chat.components

import ai.openonion.oochat.domain.model.AgentSkill
import ai.openonion.oochat.ui.theme.ConnectOnionTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The composer while disconnected (W16AAPPLE-102). It used to lock outright,
 * so the outbox the user guide describes ("Waiting to send...", sent on
 * reconnect) was almost unreachable. Now only two things lock it: no agent to
 * send to, and a gate waiting for its invite code. The approval chip still
 * needs a live agent, since a mode change is a message to one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InputBarOfflineComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val sent = mutableListOf<String>()

    private fun bar(
        isConnected: Boolean = false,
        hasAgent: Boolean = true,
        awaitingOnboardCode: Boolean = false,
        skills: List<AgentSkill> = emptyList()
    ) {
        composeRule.setContent {
            ConnectOnionTheme {
                Column {
                    InputBar(
                        isConnected = isConnected,
                        hasAgent = hasAgent,
                        awaitingOnboardCode = awaitingOnboardCode,
                        skills = skills,
                        onSend = { text, _, _ -> sent += text }
                    )
                }
            }
        }
    }

    private fun field() = composeRule.onNode(hasSetTextAction())

    @Test
    fun `offline a typed message can be sent`() {
        bar()

        field().performTextInput("typed offline")
        composeRule.onNodeWithContentDescription("Send").assertIsEnabled().performClick()

        assertEquals(listOf("typed offline"), sent)
    }

    @Test
    fun `offline the attachment menu still opens`() {
        bar()

        composeRule.onNodeWithContentDescription("Open attachment menu").assertIsEnabled()
    }

    @Test
    fun `offline the slash palette still offers the agent's skills`() {
        bar(skills = listOf(AgentSkill("explain", "Explain the code")))

        field().performTextInput("/")

        composeRule.onNodeWithText("/explain").assertIsDisplayed()
    }

    @Test
    fun `with no agent to send to nothing can be typed or sent`() {
        bar(hasAgent = false)

        // A disabled field offers no text input at all.
        field().assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Send").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Open attachment menu").assertIsNotEnabled()
    }

    @Test
    fun `a gate waiting for its invite code keeps the field locked`() {
        // The code goes in the card above; a message typed here would only be queued behind it.
        bar(awaitingOnboardCode = true)

        field().assertDoesNotExist()
    }

    @Test
    fun `an unanswered gate card does not lock a connected composer`() {
        bar(isConnected = true, awaitingOnboardCode = true)

        field().assertIsEnabled().performTextInput("hello")
        composeRule.onNodeWithContentDescription("Send").assertIsEnabled()
    }

    @Test
    fun `offline the approval chip still waits for a live agent`() {
        bar()

        // "safe" is the default mode's chip label (ApprovalMode.DEFAULT).
        composeRule.onNodeWithText("safe").assertIsNotEnabled()
    }
}
