package ai.openonion.oochat.ui.workroom

import ai.openonion.oochat.domain.model.ApprovalBoundary
import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.domain.model.ProviderActivity
import ai.openonion.oochat.domain.model.ProviderActivityStatus
import ai.openonion.oochat.domain.model.ProviderApprovalPresentation
import ai.openonion.oochat.domain.model.ProviderInvocationStatus
import ai.openonion.oochat.domain.model.WorkRoomApproval
import ai.openonion.oochat.ui.theme.ConnectOnionTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * W16AAPPLE-41: a phone turned on its side, a foldable unfolded or a window resized keeps the
 * session, the place in the transcript, the typed message and the request waiting. The app takes
 * these changes itself (see `configChanges` in the manifest), so the room is not rebuilt: the same
 * composition is given a new width. Where the room is rebuilt, as when it is left and opened again, the
 * state the chat holds for it brings it back the same way.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h900dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WorkRoomResizeComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val run = ChatItem.ProviderInvocation(
        id = "codex:call-1",
        parentToolCallId = "call-1",
        provider = "codex",
        taskTitle = "Add a login page",
        stateRevision = 4,
        status = ProviderInvocationStatus.RUNNING,
        activities = (1..40).map {
            ProviderActivity("step-$it", it, "command", ProviderActivityStatus.COMPLETED, "Step $it", "Ran step $it")
        }
    )

    private val waiting = ChatItem.ApprovalNeeded(
        id = "req-1",
        tool = "codex",
        arguments = emptyMap(),
        requestId = "req-1",
        workRoom = WorkRoomApproval(
            "codex",
            run.id,
            "call-1",
            presentation = ProviderApprovalPresentation(
                action = "Run a command",
                scope = "This Work Room only",
                reason = "Run the tests",
                boundary = ApprovalBoundary.WORKROOM,
                allowOnce = true,
                allowSession = false,
                files = emptyList()
            )
        )
    )

    // What the chat's ViewModel holds for the room, which outlives the screen.
    private var draft by mutableStateOf(WorkRoomDraft(text = "Use the existing"))
    private var savedPosition by mutableStateOf(WorkRoomScrollPosition())
    private var widthDp by mutableStateOf(360.dp)
    private var generation by mutableIntStateOf(0)

    private fun showRoom() {
        composeRule.setContent {
            ConnectOnionTheme {
                // A new generation is a room built again from what the chat holds.
                key(generation) {
                    // The window, then the part of it the room is given: a width the window itself would force on it is no change.
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.width(widthDp).fillMaxHeight()) {
                            WorkRoomScreen(
                                run = run,
                                onNavigateBack = {},
                                draft = draft,
                                onDraftChange = { draft = draft.copy(text = it) },
                                scrollPosition = savedPosition,
                                onScrollPositionChanged = { savedPosition = it },
                                approvals = listOf(waiting)
                            )
                        }
                    }
                }
            }
        }
    }

    private fun typeAndScroll(): String {
        composeRule.onNodeWithText("Use the existing").performTextReplacement("Use the existing SecondaryButton style")
        composeRule.onNodeWithTag(WORK_ROOM_TRANSCRIPT_TAG).performScrollToNode(hasText("Step 30"))
        composeRule.waitForIdle()
        val key = checkNotNull(savedPosition.firstVisibleItemKey) { "the place was not recorded" }
        assertEquals("the draft was recorded", "Use the existing SecondaryButton style", draft.text)
        return key
    }

    private fun assertKept(anchorKey: String, widthNow: Dp) {
        composeRule.onNodeWithText("Use the existing SecondaryButton style").assertIsDisplayed()
        composeRule.onNodeWithText("Allow once").assertIsDisplayed()
        // The step the room was at the top of is still on screen, whatever the new width.
        composeRule.onNodeWithText("Step ${anchorKey.substringAfterLast('-')}").assertIsDisplayed()
        assertEquals(widthNow, composeRule.onNodeWithTag(WORK_ROOM_APPROVAL_DOCK_TAG).getBoundsInRoot().width)
    }

    @Test
    fun `a phone opened out to a tablet keeps the message, the place and the request`() {
        showRoom()
        val anchor = typeAndScroll()
        assertEquals(360.dp, composeRule.onNodeWithTag(WORK_ROOM_APPROVAL_DOCK_TAG).getBoundsInRoot().width)

        composeRule.runOnIdle { widthDp = 1000.dp }
        composeRule.waitForIdle()

        assertKept(anchor, widthNow = 640.dp)
    }

    @Test
    fun `a tablet folded back to a phone keeps them too`() {
        widthDp = 1000.dp
        showRoom()
        val anchor = typeAndScroll()
        assertEquals(640.dp, composeRule.onNodeWithTag(WORK_ROOM_APPROVAL_DOCK_TAG).getBoundsInRoot().width)

        composeRule.runOnIdle { widthDp = 360.dp }
        composeRule.waitForIdle()

        assertKept(anchor, widthNow = 360.dp)
    }

    @Test
    fun `a room built again at another width comes back with them from what the chat holds`() {
        showRoom()
        val anchor = typeAndScroll()

        composeRule.runOnIdle {
            widthDp = 1000.dp
            generation++
        }
        composeRule.waitForIdle()

        assertNotNull(anchor)
        assertKept(anchor, widthNow = 640.dp)
    }
}
