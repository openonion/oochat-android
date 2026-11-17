package ai.openonion.oochat.ui.workroom

import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.domain.model.ProviderInvocationStatus
import ai.openonion.oochat.ui.theme.ConnectOnionTheme
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * W16AAPPLE-41: how wide the room's column and its message field are in windows of different
 * widths. Each test sets the window with Robolectric qualifiers.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WorkRoomWidthComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val run = ChatItem.ProviderInvocation(
        id = "codex:call-1",
        parentToolCallId = "call-1",
        provider = "codex",
        taskTitle = "Add a login page",
        status = ProviderInvocationStatus.RUNNING
    )

    private fun widthOf(tag: String) = composeRule.onNodeWithTag(tag).getBoundsInRoot().width

    private fun leftOf(tag: String) = composeRule.onNodeWithTag(tag).getBoundsInRoot().left

    private fun showRoom() {
        composeRule.setContent { ConnectOnionTheme { WorkRoomScreen(run = run, onNavigateBack = {}) } }
    }

    @Test
    @Config(sdk = [34], qualifiers = "w800dp-h1000dp")
    fun `on a tablet in portrait the column and the message field are 640dp, in the middle`() {
        showRoom()

        listOf(WORK_ROOM_TRANSCRIPT_TAG, WORK_ROOM_COMPOSER_TAG).forEach { tag ->
            assertEquals(tag, 640.dp, widthOf(tag))
            assertEquals(tag, 80.dp, leftOf(tag))
        }
    }

    @Test
    @Config(sdk = [34], qualifiers = "w1280dp-h800dp")
    fun `on a tablet in landscape they are still 640dp, in the middle`() {
        showRoom()

        listOf(WORK_ROOM_TRANSCRIPT_TAG, WORK_ROOM_COMPOSER_TAG).forEach { tag ->
            assertEquals(tag, 640.dp, widthOf(tag))
            assertEquals(tag, 320.dp, leftOf(tag))
        }
    }

    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h800dp")
    fun `on a phone they take the whole width`() {
        showRoom()

        listOf(WORK_ROOM_TRANSCRIPT_TAG, WORK_ROOM_COMPOSER_TAG).forEach { tag ->
            assertEquals(tag, 360.dp, widthOf(tag))
            assertEquals(tag, 0.dp, leftOf(tag))
        }
    }
}
