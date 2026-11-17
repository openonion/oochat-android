package ai.openonion.oochat.ui.chat.components

import ai.openonion.oochat.domain.model.ApprovalDecision
import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.ui.theme.ConnectOnionTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * On-device bug this guards against: [ApprovalCard] showed only the tool
 * name ("edit") while awaiting a decision, and its post-decision
 * confirmation bar collapsed to "Allowed: edit — running…" — no file, no
 * command, no way to recall what was actually approved once the card
 * scrolled past. See [ai.openonion.oochat.util.ToolArgsSummaryTest]
 * for the underlying pure-function coverage.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ActionCardsComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `an answered question drawn afresh keeps its buttons off`() {
        // A scroll or rotation draws the card again with none of its local state.
        composeRule.setContent {
            ConnectOnionTheme {
                AskUserCard(
                    item = ChatItem.AskUser(id = "q-1", text = "Which branch?", options = listOf("main", "dev"), multiSelect = false, answer = "dev"),
                    onRespond = {}
                )
            }
        }

        composeRule.onNodeWithText("main").assertIsNotEnabled()
        composeRule.onNodeWithText("dev").assertIsNotEnabled()
        composeRule.onNodeWithText("No longer waiting for an answer").assertDoesNotExist()
    }

    @Test
    fun `a refused answer says the question is no longer waiting`() {
        composeRule.setContent {
            ConnectOnionTheme {
                AskUserCard(
                    item = ChatItem.AskUser(
                        id = "q-1", text = "Which branch?", options = listOf("main", "dev"), multiSelect = false,
                        answer = "dev", refused = true
                    ),
                    onRespond = {}
                )
            }
        }

        composeRule.onNodeWithText("No longer waiting for an answer").assertExists()
        composeRule.onNodeWithText("main").assertIsNotEnabled()
    }

    @Test
    fun `pending approval card shows the argument being approved`() {
        composeRule.setContent {
            ConnectOnionTheme {
                ApprovalCard(
                    item = ChatItem.ApprovalNeeded(
                        id = "ap-1",
                        tool = "edit",
                        arguments = mapOf("file_path" to "src/App.tsx")
                    ),
                    onApprove = { _, _, _, _ -> }
                )
            }
        }

        composeRule.onNodeWithText("src/App.tsx").assertExists()
    }

    @Test
    fun `pending approval card renders no extra summary line when arguments are empty`() {
        composeRule.setContent {
            ConnectOnionTheme {
                ApprovalCard(
                    item = ChatItem.ApprovalNeeded(
                        id = "ap-2",
                        tool = "load_guide",
                        arguments = emptyMap()
                    ),
                    onApprove = { _, _, _, _ -> }
                )
            }
        }

        // The tool-name pill (and its title fallback) still show, just
        // nothing extra underneath them for the empty argument map.
        composeRule.onNodeWithText("=", substring = true).assertDoesNotExist()
    }

    @Test
    fun `decided approval card's confirmation receipt still names what was approved`() {
        composeRule.setContent {
            ConnectOnionTheme {
                ApprovalCard(
                    item = ChatItem.ApprovalNeeded(
                        id = "ap-3",
                        tool = "edit",
                        arguments = mapOf("file_path" to "src/App.tsx"),
                        decision = ApprovalDecision(approved = true, scope = "once", mode = null)
                    ),
                    onApprove = { _, _, _, _ -> }
                )
            }
        }

        composeRule.onNodeWithText("Allowed: edit", substring = true).assertExists()
        composeRule.onNodeWithText("src/App.tsx", substring = true).assertExists()
    }
}
