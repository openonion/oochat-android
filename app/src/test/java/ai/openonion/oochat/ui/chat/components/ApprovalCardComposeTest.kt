package ai.openonion.oochat.ui.chat.components

import ai.openonion.oochat.domain.model.ApprovalDecision
import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.ui.theme.ConnectOnionTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What each approval card and Ultra checkpoint button hands over, using the
 * values connectonion accepts (`_normalized_legacy_permission`). The wire is
 * [ai.openonion.oochat.ui.chat.ApprovalResponseTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ApprovalCardComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private data class Answer(val approved: Boolean, val scope: String, val mode: String?, val feedback: String?)

    private val gate = ChatItem.ApprovalNeeded(
        id = "ap-1",
        tool = "bash",
        arguments = mapOf("command" to "npm install"),
        description = "Install npm packages"
    )

    private fun setCard(item: ChatItem.ApprovalNeeded = gate): MutableList<Answer> {
        val answers = mutableListOf<Answer>()
        composeRule.setContent {
            ConnectOnionTheme {
                ApprovalCard(item = item, onApprove = { approved, scope, mode, feedback -> answers += Answer(approved, scope, mode, feedback) })
            }
        }
        return answers
    }

    @Test
    fun `the card offers every answer the Host accepts`() {
        setCard()
        listOf("Allow once", "Trust bash for this session", "Reject", "Stop", "Explain").forEach {
            composeRule.onNodeWithText(it).assertExists()
        }
    }

    @Test
    fun `Allow once approves this call only`() {
        val answers = setCard()
        composeRule.onNodeWithText("Allow once").performClick()
        assertEquals(listOf(Answer(true, "once", null, null)), answers)
    }

    @Test
    fun `Trust for this session approves with session scope`() {
        val answers = setCard()
        composeRule.onNodeWithText("Trust bash for this session").performClick()
        assertEquals(listOf(Answer(true, "session", null, null)), answers)
    }

    @Test
    fun `Reject asks for a reason first and sends nothing yet`() {
        val answers = setCard()
        composeRule.onNodeWithText("Reject").performClick()

        composeRule.onNodeWithText("Reason (optional)").assertExists()
        assertTrue(answers.isEmpty())
    }

    @Test
    fun `Reject with a reason is a soft reject carrying it, trimmed`() {
        val answers = setCard()
        composeRule.onNodeWithText("Reject").performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput("  use yarn instead ")
        // The reason field's own Reject button.
        composeRule.onNodeWithText("Reject").performClick()

        assertEquals(listOf(Answer(false, "once", "reject_soft", "use yarn instead")), answers)
    }

    @Test
    fun `Reject with no reason sends no feedback at all`() {
        val answers = setCard()
        composeRule.onNodeWithText("Reject").performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput("   ")
        composeRule.onNodeWithText("Reject").performClick()

        assertEquals(listOf(Answer(false, "once", "reject_soft", null)), answers)
    }

    @Test
    fun `cancelling the reason goes back to the buttons and sends nothing`() {
        val answers = setCard()
        composeRule.onNodeWithText("Reject").performClick()
        composeRule.onNodeWithText("Cancel").performClick()

        assertTrue(answers.isEmpty())
        composeRule.onNodeWithText("Allow once").assertExists()
    }

    @Test
    fun `Stop is a hard reject`() {
        val answers = setCard()
        composeRule.onNodeWithText("Stop").performClick()
        assertEquals(listOf(Answer(false, "once", "reject_hard", null)), answers)
    }

    @Test
    fun `Explain asks the agent to explain, with no reason field`() {
        val answers = setCard()
        composeRule.onNodeWithText("Explain").performClick()
        assertEquals(listOf(Answer(false, "once", "reject_explain", null)), answers)
        composeRule.onNodeWithText("Reason (optional)").assertDoesNotExist()
    }

    @Test
    fun `an answered card keeps a record of the answer and offers no buttons`() {
        val decisions = listOf(
            ApprovalDecision(true, "once", null, null) to "Allowed: bash",
            ApprovalDecision(true, "session", null, null) to "Session authorized: bash",
            ApprovalDecision(false, "once", "reject_soft", "use yarn") to "Rejected: bash",
            ApprovalDecision(false, "once", "reject_hard", null) to "Execution stopped",
            ApprovalDecision(false, "once", "reject_explain", null) to "Rejected: bash",
            ApprovalDecision(true, "once", null, null, refused = true) to "No longer waiting for an answer: bash"
        )
        var current by mutableStateOf(gate.copy(decision = decisions.first().first))
        composeRule.setContent {
            ConnectOnionTheme { ApprovalCard(item = current, onApprove = { _, _, _, _ -> error("an answered card must not answer again") }) }
        }

        for ((decision, receipt) in decisions) {
            current = gate.copy(decision = decision)
            composeRule.waitForIdle()
            assertTrue(
                "the receipt for $decision says what was decided",
                composeRule.onAllNodesWithText(receipt, substring = true).fetchSemanticsNodes().isNotEmpty()
            )
            assertTrue(
                "and still names what it was about",
                composeRule.onAllNodesWithText("npm install", substring = true).fetchSemanticsNodes().isNotEmpty()
            )
            composeRule.onNodeWithText("Allow once").assertDoesNotExist()
            composeRule.onNodeWithText("Explain").assertDoesNotExist()
        }
    }

    // ── Ultra work's checkpoint ─────────────────────────────────────────

    private data class UlwAnswer(val action: String, val turns: Int?, val mode: String?)

    private fun setCheckpoint(): MutableList<UlwAnswer> {
        val answers = mutableListOf<UlwAnswer>()
        composeRule.setContent {
            ConnectOnionTheme {
                UlwTurnsReachedCard(
                    item = ChatItem.UlwTurnsReachedItem(id = "ulw-1", turnsUsed = 20, maxTurns = 20),
                    onUlwResponse = { action, turns, mode -> answers += UlwAnswer(action, turns, mode) }
                )
            }
        }
        return answers
    }

    @Test
    fun `the checkpoint says the budget is spent`() {
        setCheckpoint()
        composeRule.onNodeWithText("Turn limit reached").assertExists()
        composeRule.onNodeWithText("20 of 20 turns used in this run.").assertExists()
    }

    @Test
    fun `Switch to Safe Mode leaves Ultra work for safe`() {
        val answers = setCheckpoint()
        composeRule.onNodeWithText("Switch to Safe Mode").performClick()
        assertEquals(listOf(UlwAnswer("switch_mode", null, "safe")), answers)
    }

    @Test
    fun `Switch to Accept Edits leaves Ultra work for accept_edits`() {
        val answers = setCheckpoint()
        composeRule.onNodeWithText("Switch to Accept Edits").performClick()
        assertEquals(listOf(UlwAnswer("switch_mode", null, "accept_edits")), answers)
    }

    @Test
    fun `Continue extends the budget by a hundred turns`() {
        val answers = setCheckpoint()
        composeRule.onNodeWithText("Continue (+100 turns)").performClick()
        assertEquals(listOf(UlwAnswer("continue", 100, null)), answers)
    }

    @Test
    fun `the checkpoint answers once`() {
        val answers = setCheckpoint()
        composeRule.onNodeWithText("Switch to Safe Mode").performClick()

        composeRule.onNodeWithText("Choice sent").assertExists()
        composeRule.onNodeWithText("Continue (+100 turns)").assertDoesNotExist()
        assertEquals(1, answers.size)
    }
}
