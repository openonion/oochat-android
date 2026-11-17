package ai.openonion.oochat.ui.workroom

import ai.openonion.oochat.domain.model.ApprovalBoundary
import ai.openonion.oochat.domain.model.ApprovalDecision
import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.domain.model.ProviderActivity
import ai.openonion.oochat.domain.model.ProviderActivityStatus
import ai.openonion.oochat.domain.model.ProviderApprovalPresentation
import ai.openonion.oochat.domain.model.ProviderInvocationStatus
import ai.openonion.oochat.domain.model.WorkRoomApproval
import ai.openonion.oochat.ui.theme.ConnectOnionTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** W16AAPPLE-41: a request waiting on an answer is docked above the message field. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WorkRoomApprovalDockComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val run = ChatItem.ProviderInvocation(
        id = "claude_code:call-1",
        parentToolCallId = "call-1",
        provider = "claude_code",
        taskTitle = "Add a login page",
        status = ProviderInvocationStatus.AWAITING_APPROVAL,
        // Enough steps that the transcript is well longer than the screen.
        activities = (1..40).map {
            ProviderActivity("step-$it", it, "command", ProviderActivityStatus.COMPLETED, "Step $it", "Ran step $it")
        }
    )

    private fun approval(id: String = "req-1", decision: ApprovalDecision? = null) = ChatItem.ApprovalNeeded(
        id = id,
        tool = "claude_code",
        arguments = emptyMap(),
        requestId = id,
        decision = decision,
        workRoom = WorkRoomApproval(
            "claude_code",
            run.id,
            "call-1",
            presentation = ProviderApprovalPresentation(
                action = "Run a command ($id)",
                scope = "This Work Room only",
                reason = "Run the tests",
                boundary = ApprovalBoundary.WORKROOM,
                allowOnce = true,
                allowSession = false,
                files = emptyList()
            )
        )
    )

    private val decisions = mutableListOf<Triple<String, Boolean, String>>()

    private fun showRoom(approvals: () -> List<ChatItem.ApprovalNeeded>) {
        composeRule.setContent {
            ConnectOnionTheme {
                WorkRoomScreen(
                    run = run,
                    onNavigateBack = {},
                    approvals = approvals(),
                    onApprovalDecision = { id, approved, scope -> decisions += Triple(id, approved, scope) }
                )
            }
        }
    }

    private fun inDock(text: String) = hasText(text, substring = true) and hasAnyAncestor(hasTestTag(WORK_ROOM_APPROVAL_DOCK_TAG))

    private fun inTranscript(text: String) = hasText(text, substring = true) and hasAnyAncestor(hasTestTag(WORK_ROOM_TRANSCRIPT_TAG))

    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h800dp")
    fun `a request waiting is in the dock, not in the transcript, and the dock is above the message field`() {
        showRoom { listOf(approval()) }

        composeRule.onNode(inDock("Run a command (req-1)")).assertIsDisplayed()
        composeRule.onNode(inTranscript("Run a command (req-1)")).assertDoesNotExist()
        val dock = composeRule.onNodeWithTag(WORK_ROOM_APPROVAL_DOCK_TAG).getBoundsInRoot()
        val field = composeRule.onNodeWithTag(WORK_ROOM_COMPOSER_TAG).getBoundsInRoot()
        assertTrue("dock ${dock.bottom} is above the field ${field.top}", dock.bottom <= field.top)
    }

    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h800dp")
    fun `the dock stays in reach however far down the transcript is read`() {
        showRoom { listOf(approval()) }

        composeRule.onNodeWithTag(WORK_ROOM_TRANSCRIPT_TAG).performScrollToIndex(30)

        composeRule.onNode(inDock("Run a command (req-1)")).assertIsDisplayed()
        composeRule.onNodeWithText("Allow once").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h800dp")
    fun `Allow once in the dock answers the request, and the answer then stays in the transcript`() {
        var approvals by mutableStateOf(listOf(approval()))
        showRoom { approvals }

        composeRule.onNodeWithText("Allow once").performClick()
        assertEquals(listOf(Triple("req-1", true, "once")), decisions)

        approvals = listOf(approval(decision = ApprovalDecision(approved = true, scope = "once", mode = null)))
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(WORK_ROOM_APPROVAL_DOCK_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(WORK_ROOM_TRANSCRIPT_TAG).performScrollToNode(hasText("Allowed once", substring = true))
        composeRule.onNode(inTranscript("Allowed once")).assertIsDisplayed()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h800dp")
    fun `with nothing waiting there is no dock`() {
        showRoom { emptyList() }

        composeRule.onNodeWithTag(WORK_ROOM_APPROVAL_DOCK_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(WORK_ROOM_COMPOSER_TAG).assertIsDisplayed()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h800dp")
    fun `many requests at once take no more than 40 percent of the screen, and the field is still there`() {
        showRoom { (1..8).map { approval("req-$it") } }

        val dock = composeRule.onNodeWithTag(WORK_ROOM_APPROVAL_DOCK_TAG).getBoundsInRoot()

        assertTrue("dock is ${dock.height} tall", dock.height <= 320.dp)
        assertTrue("dock is ${dock.height} tall, so it is full", dock.height >= 300.dp)
        composeRule.onNodeWithTag(WORK_ROOM_COMPOSER_TAG).assertIsDisplayed()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h800dp")
    fun `on a phone the dock takes the whole width`() {
        showRoom { listOf(approval()) }

        val dock = composeRule.onNodeWithTag(WORK_ROOM_APPROVAL_DOCK_TAG).getBoundsInRoot()

        assertEquals(360.dp, dock.width)
        assertEquals(0.dp, dock.left)
    }

    @Test
    @Config(sdk = [34], qualifiers = "w1280dp-h800dp")
    fun `on a tablet the dock is the same 640dp column as the field, in the middle`() {
        showRoom { listOf(approval()) }

        val dock = composeRule.onNodeWithTag(WORK_ROOM_APPROVAL_DOCK_TAG).getBoundsInRoot()
        val field = composeRule.onNodeWithTag(WORK_ROOM_COMPOSER_TAG).getBoundsInRoot()

        assertEquals(640.dp, dock.width)
        assertEquals(320.dp, dock.left)
        assertEquals(field.left, dock.left)
        assertEquals(field.width, dock.width)
    }

    /** The dock alone, at the foot of an 800dp window with [keyboard] of it under the keyboard. */
    private fun showDock(count: Int, keyboard: Dp) {
        composeRule.setContent {
            ConnectOnionTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    WorkRoomApprovalDock(
                        approvals = (1..count).map { approval("req-$it") },
                        onDecision = { _, _, _ -> },
                        keyboard = WindowInsets(bottom = keyboard)
                    )
                }
            }
        }
    }

    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h800dp")
    fun `with the keyboard open the dock takes 40 percent of what the keyboard leaves, not of the screen`() {
        showDock(count = 8, keyboard = 400.dp)

        val dock = composeRule.onNodeWithTag(WORK_ROOM_APPROVAL_DOCK_TAG).getBoundsInRoot()

        assertTrue("dock is ${dock.height} tall, over 40% of the 400dp above the keyboard", dock.height <= 160.dp)
        assertTrue("dock is ${dock.height} tall, so it is full", dock.height >= 150.dp)
    }

    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h800dp")
    fun `with no keyboard the dock still takes 40 percent of the screen`() {
        showDock(count = 8, keyboard = 0.dp)

        val dock = composeRule.onNodeWithTag(WORK_ROOM_APPROVAL_DOCK_TAG).getBoundsInRoot()

        assertTrue("dock is ${dock.height} tall", dock.height <= 320.dp)
        assertTrue("dock is ${dock.height} tall, so it is full", dock.height >= 300.dp)
    }

    @Test
    @Config(sdk = [34])
    fun `the dock's height is 40 percent of the height the keyboard leaves, never negative`() {
        assertEquals(800, workRoomDockMaxHeight(availableHeight = 2000, keyboardHeight = 0))
        assertEquals(480, workRoomDockMaxHeight(availableHeight = 2000, keyboardHeight = 800))
        assertEquals(0, workRoomDockMaxHeight(availableHeight = 2000, keyboardHeight = 2400))
    }
}
