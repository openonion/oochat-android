package ai.openonion.oochat.ui.workroom

import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.domain.model.ProviderInvocationStatus
import ai.openonion.oochat.domain.model.WorkRoomStopState
import ai.openonion.oochat.ui.theme.ConnectOnionTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** W16AAPPLE-37: the Work Room's Stop, its confirmation and its countdown. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WorkRoomStopComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val run = ChatItem.ProviderInvocation(
        id = "codex:call-1",
        provider = "codex",
        taskTitle = "Add a login page",
        status = ProviderInvocationStatus.RUNNING,
        stateRevision = 4
    )

    private val calls = mutableListOf<String>()

    private fun show(shown: ChatItem.ProviderInvocation = run, ui: WorkRoomStopUi = WorkRoomStopUi()) {
        composeRule.setContent {
            ConnectOnionTheme {
                WorkRoomScreen(
                    run = shown,
                    onNavigateBack = {},
                    stopUi = ui,
                    onStop = { calls += "stop" },
                    onConfirmStop = { calls += "confirm" },
                    onCancelStop = { calls += "cancel" }
                )
            }
        }
    }

    @Test
    fun `a working run offers Stop, and the first tap only asks`() {
        show()

        composeRule.onNodeWithText(WorkRoomText.STOP).performClick()

        assertEquals(listOf("stop"), calls)
    }

    @Test
    fun `the confirmation says only this run stops, counts down from 15 seconds, and can be confirmed or backed out of`() {
        composeRule.mainClock.autoAdvance = false
        show(ui = WorkRoomStopUi(WorkRoomStopState.Confirming))

        composeRule.onNodeWithText("Stop this Codex run?").assertIsDisplayed()
        composeRule.onNodeWithText(WorkRoomText.stopBody(run)).assertIsDisplayed()
        composeRule.onNodeWithText("Closes in 15 seconds").assertIsDisplayed()
        composeRule.mainClock.advanceTimeBy(3_000)
        composeRule.onNodeWithText("Closes in 12 seconds").assertIsDisplayed()

        composeRule.onNodeWithText(WorkRoomText.STOP_KEEP).performClick()
        composeRule.onNodeWithText(WorkRoomText.STOP_CONFIRM).performClick()

        assertEquals(listOf("cancel", "confirm"), calls)
    }

    @Test
    fun `while a Stop is on its way, it cannot be sent again`() {
        show(ui = WorkRoomStopUi(WorkRoomStopState.Sending))

        composeRule.onNodeWithText(WorkRoomText.STOP_SENDING).assertIsNotEnabled()
    }

    @Test
    fun `a refusal is shown under Stop`() {
        show(ui = WorkRoomStopUi(error = WorkRoomText.stopRefused("not_active")))

        composeRule.onNodeWithText(WorkRoomText.stopRefused("not_active")).assertIsDisplayed()
    }

    @Test
    fun `a run that has ended has no Stop`() {
        show(shown = run.copy(status = ProviderInvocationStatus.CANCELLED))

        composeRule.onNodeWithTag(WORK_ROOM_STOP_TAG).assertDoesNotExist()
        composeRule.onNodeWithText(WorkRoomText.STOP).assertDoesNotExist()
    }
}
