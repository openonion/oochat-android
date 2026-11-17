package ai.openonion.oochat.ui.chat.components

import ai.openonion.oochat.ui.theme.ConnectOnionTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InputBarDraftComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `the chat draft survives leaving and reopening the composer`() {
        var showing by mutableStateOf(true)
        var draft by mutableStateOf(ChatComposerDraft())

        composeRule.setContent {
            ConnectOnionTheme {
                if (showing) {
                    InputBar(
                        isConnected = true,
                        draft = draft,
                        draftKey = "session-a",
                        onDraftChange = { draft = it },
                        onSend = { _, _, _ -> }
                    )
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNode(hasSetTextAction()).performTextInput("half a thought")
        composeRule.waitForIdle()
        assertEquals("half a thought", draft.text)

        composeRule.runOnIdle { showing = false }
        composeRule.waitForIdle()
        composeRule.runOnIdle { showing = true }
        composeRule.waitForIdle()

        composeRule.onNode(hasSetTextAction()).assertTextEquals("half a thought")
    }

    @Test
    fun `the composer restores the draft for the selected conversation`() {
        var session by mutableStateOf("session-a")
        var drafts by mutableStateOf(
            mapOf(
                "session-a" to ChatComposerDraft(text = "first draft"),
                "session-b" to ChatComposerDraft(text = "second draft")
            )
        )

        composeRule.setContent {
            ConnectOnionTheme {
                InputBar(
                    isConnected = true,
                    draft = drafts[session] ?: ChatComposerDraft(),
                    draftKey = session,
                    onDraftChange = { drafts = drafts + (session to it) },
                    onSend = { _, _, _ -> }
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNode(hasSetTextAction()).assertTextEquals("first draft")

        composeRule.runOnIdle { session = "session-b" }
        composeRule.waitForIdle()
        composeRule.onNode(hasSetTextAction()).assertTextEquals("second draft")
    }
}
