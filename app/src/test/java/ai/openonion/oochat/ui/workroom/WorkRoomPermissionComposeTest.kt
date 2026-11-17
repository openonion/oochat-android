package ai.openonion.oochat.ui.workroom

import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.domain.model.ProviderInvocationStatus
import ai.openonion.oochat.domain.model.ProviderPermissionControlState
import ai.openonion.oochat.domain.model.ProviderPermissionOption
import ai.openonion.oochat.domain.model.ProviderPermissionState
import ai.openonion.oochat.ui.theme.ConnectOnionTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * W16AAPPLE-43: a Work Room's provider permission control, which must read as
 * the provider's own mode and not as the chat's approval mode (the Host ceiling).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WorkRoomPermissionComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val codexUnderAuto = ProviderPermissionState(
        provider = "codex",
        activeOptionId = "codex:workspace-ask",
        options = listOf(
            ProviderPermissionOption("codex:read-only", "Read Only", "Codex can inspect the workspace but cannot change it.", selectable = true),
            ProviderPermissionOption("codex:workspace-ask", "Ask for approval", "Codex can work in the workspace and asks before protected actions.", selectable = true),
            ProviderPermissionOption(
                "codex:full-access",
                "Full Access",
                "Codex can act outside the workspace boundary for the next provider turn.",
                elevated = true,
                disabledReason = "Host permission ceiling is Auto."
            )
        )
    )

    private val run = ChatItem.ProviderInvocation(
        id = "codex:call-1",
        provider = "codex",
        taskTitle = "Add a login page",
        status = ProviderInvocationStatus.RUNNING,
        stateRevision = 4,
        providerPermission = codexUnderAuto
    )

    private fun show(
        shown: ChatItem.ProviderInvocation = run,
        ui: WorkRoomPermissionUi = WorkRoomPermissionUi(),
        onChoose: (String) -> Unit = {},
        onConfirm: () -> Unit = {},
        onCancel: () -> Unit = {}
    ) {
        composeRule.setContent {
            ConnectOnionTheme {
                WorkRoomScreen(
                    run = shown,
                    onNavigateBack = {},
                    permissionUi = ui,
                    onChoosePermission = onChoose,
                    onConfirmPermission = onConfirm,
                    onCancelPermission = onCancel
                )
            }
        }
    }

    @Test
    fun `the room names the provider's own mode, apart from the agent's approval mode`() {
        show()

        composeRule.onNodeWithTag(WORK_ROOM_PERMISSION_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Codex permissions").assertIsDisplayed()
        composeRule.onNodeWithText("Ask for approval").assertIsDisplayed()
        composeRule.onNodeWithText(WorkRoomText.permissionExplainer(run)).assertIsDisplayed()
    }

    @Test
    fun `a run the Host offers no permission mode for shows no control`() {
        show(shown = run.copy(providerPermission = null))

        composeRule.onNodeWithTag(WORK_ROOM_PERMISSION_TAG).assertDoesNotExist()
    }

    @Test
    fun `the sheet offers every choice, with one above the ceiling greyed out and why`() {
        val picked = mutableListOf<String>()
        show(onChoose = { picked += it })

        composeRule.onNodeWithText("Change").performClick()
        composeRule.onNodeWithText("Full Access · higher risk").assertIsNotEnabled()
        composeRule.onNodeWithText("Host permission ceiling is Auto.").assertIsDisplayed()
        composeRule.onNodeWithText("Read Only").performClick()

        assertEquals(listOf("codex:read-only"), picked)
    }

    @Test
    fun `an elevated choice asks to be confirmed, and Cancel backs out`() {
        var ui by mutableStateOf(WorkRoomPermissionUi(ProviderPermissionControlState.ConfirmingElevated("codex:full-access")))
        var confirmed = false
        var cancelled = false
        composeRule.setContent {
            ConnectOnionTheme {
                WorkRoomScreen(
                    run = run,
                    onNavigateBack = {},
                    permissionUi = ui,
                    onConfirmPermission = { confirmed = true },
                    onCancelPermission = {
                        cancelled = true
                        ui = WorkRoomPermissionUi()
                    }
                )
            }
        }

        composeRule.onNodeWithText("Switch Codex to Full Access?").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").performClick()

        assertTrue(cancelled)
        assertEquals(false, confirmed)
        composeRule.onNodeWithText("Switch Codex to Full Access?").assertDoesNotExist()
    }

    @Test
    fun `confirming sends it`() {
        var confirmed = false
        show(
            ui = WorkRoomPermissionUi(ProviderPermissionControlState.ConfirmingElevated("codex:full-access")),
            onConfirm = { confirmed = true }
        )

        composeRule.onNodeWithText("Switch").performClick()

        assertTrue(confirmed)
    }

    @Test
    fun `while a change waits the control says so and cannot be used`() {
        show(ui = WorkRoomPermissionUi(ProviderPermissionControlState.Pending("codex:read-only")))
        composeRule.onNodeWithText(WorkRoomText.PERMISSION_CHANGING).assertIsNotEnabled()
    }

    @Test
    fun `a refusal is shown under the control`() {
        show(ui = WorkRoomPermissionUi(error = WorkRoomText.permissionRefused("ceiling_denied")))

        composeRule.onNodeWithText(WorkRoomText.permissionRefused("ceiling_denied")).assertIsDisplayed()
    }
}
