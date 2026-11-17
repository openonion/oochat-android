package ai.openonion.oochat.ui.workroom

import ai.openonion.oochat.domain.model.ApprovalBoundary
import ai.openonion.oochat.domain.model.ApprovalDecision
import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.domain.model.ProviderApprovalPresentation
import ai.openonion.oochat.domain.model.ProviderInvocationStatus
import ai.openonion.oochat.domain.model.WorkRoomApproval
import ai.openonion.oochat.ui.chat.components.ChatItemBubble
import ai.openonion.oochat.ui.theme.ConnectOnionTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** W16AAPPLE-36: the Work Room approval card. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WorkRoomApprovalComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val run = ChatItem.ProviderInvocation(
        id = "claude_code:call-1",
        parentToolCallId = "call-1",
        provider = "claude_code",
        taskTitle = "Add a login page",
        status = ProviderInvocationStatus.AWAITING_APPROVAL
    )

    private fun copy(boundary: ApprovalBoundary, allowSession: Boolean = false) = ProviderApprovalPresentation(
        action = "Make workspace file changes",
        scope = when (boundary) {
            ApprovalBoundary.WORKROOM -> "This Work Room only"
            ApprovalBoundary.ELEVATED -> "Outside this Work Room"
            ApprovalBoundary.UNKNOWN -> "Boundary could not be verified"
        },
        reason = "Apply the requested workspace file changes",
        boundary = boundary,
        allowOnce = boundary == ApprovalBoundary.WORKROOM,
        allowSession = allowSession,
        files = listOf("Login.kt")
    )

    private fun approval(
        id: String = "req-1",
        presentation: ProviderApprovalPresentation? = copy(ApprovalBoundary.WORKROOM),
        decision: ApprovalDecision? = null,
        invocationId: String = run.id
    ) = ChatItem.ApprovalNeeded(
        id = id,
        tool = "claude_code",
        arguments = emptyMap(),
        requestId = id,
        decision = decision,
        workRoom = WorkRoomApproval("claude_code", invocationId, "call-1", presentation = presentation)
    )

    private val decisions = mutableListOf<Triple<String, Boolean, String>>()

    private fun showRoom(vararg approvals: ChatItem.ApprovalNeeded) {
        composeRule.setContent {
            ConnectOnionTheme {
                WorkRoomScreen(
                    run = run,
                    onNavigateBack = {},
                    approvals = approvals.toList(),
                    onApprovalDecision = { id, approved, scope -> decisions += Triple(id, approved, scope) }
                )
            }
        }
    }

    @Test
    fun `the room shows what the provider asks, and Allow once answers it once`() {
        showRoom(approval())

        composeRule.onNodeWithText("Make workspace file changes").assertIsDisplayed()
        composeRule.onNodeWithText("This Work Room only").assertIsDisplayed()
        composeRule.onNodeWithText("Login.kt").assertIsDisplayed()
        composeRule.onNodeWithText("Allow once").performClick()

        assertEquals(listOf(Triple("req-1", true, "once")), decisions)
    }

    @Test
    fun `Deny declines it`() {
        showRoom(approval())

        composeRule.onNodeWithText("Deny").performScrollTo().performClick()

        assertEquals(listOf(Triple("req-1", false, "once")), decisions)
    }

    @Test
    fun `allowing for the session is offered only when the Host offers it`() {
        showRoom(approval(presentation = copy(ApprovalBoundary.WORKROOM, allowSession = true)))

        composeRule.onNodeWithText("Allow for this session").performScrollTo().performClick()

        assertEquals(listOf(Triple("req-1", true, "session")), decisions)
    }

    @Test
    fun `a request reaching outside the room can only be denied`() {
        showRoom(approval(presentation = copy(ApprovalBoundary.ELEVATED)))

        composeRule.onNodeWithText("This reaches outside this Work Room, so it can only be denied from here.").assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithTextCount("Allow once"))
        assertEquals(0, composeRule.onAllNodesWithTextCount("Allow for this session"))
        composeRule.onNodeWithText("Deny").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a request without the Host's copy can only be denied`() {
        showRoom(approval(presentation = null))

        composeRule.onNodeWithText("Claude Code asks for approval").assertIsDisplayed()
        composeRule.onNodeWithText("Where this reaches could not be checked, so it can only be denied from here.").assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithTextCount("Allow once"))
    }

    @Test
    fun `an answered request stays as a record without buttons`() {
        showRoom(approval(decision = ApprovalDecision(approved = true, scope = "once", mode = null)))

        composeRule.onNodeWithText("Allowed once").performScrollTo().assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithTextCount("Deny"))
    }

    @Test
    fun `an answer the Host refused says it was not applied`() {
        showRoom(approval(decision = ApprovalDecision(approved = true, scope = "once", mode = null, refused = true)))

        composeRule.onNodeWithText("Not applied: the run was no longer waiting for this answer.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the chat shows the same request and answers it with the Work Room's choices`() {
        val answers = mutableListOf<List<Any?>>()
        composeRule.setContent {
            ConnectOnionTheme {
                ChatItemBubble(
                    item = approval(),
                    timestamp = null,
                    onRespond = {},
                    onApprove = { approved, scope, mode, feedback -> answers += listOf(approved, scope, mode, feedback) },
                    onOnboard = { _, _, _ -> },
                    onPlanReviewResponse = {},
                    onUlwResponse = { _, _, _ -> }
                )
            }
        }

        assertEquals(0, composeRule.onAllNodesWithTextCount("Trust claude_code for this session"))
        composeRule.onNodeWithText("Deny").performClick()

        assertEquals(listOf(listOf<Any?>(false, "once", WORK_ROOM_DENY_MODE, null)), answers)
    }

    @Test
    fun `the room lists only its own run's approvals`() {
        val items = listOf(run, approval(id = "mine"), approval(id = "other", invocationId = "codex:call-9"))
        assertEquals(listOf("mine"), items.workRoomApprovals(run.id).map { it.id })
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String): Int =
        onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size
}
