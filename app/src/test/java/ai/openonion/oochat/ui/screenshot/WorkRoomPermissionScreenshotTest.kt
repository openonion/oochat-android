package ai.openonion.oochat.ui.screenshot

import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.domain.model.ProviderActivity
import ai.openonion.oochat.domain.model.ProviderActivityStatus
import ai.openonion.oochat.domain.model.ProviderInvocationStatus
import ai.openonion.oochat.domain.model.ProviderPermissionOption
import ai.openonion.oochat.domain.model.ProviderPermissionState
import ai.openonion.oochat.ui.workroom.WorkRoomScreen
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A Codex Work Room with its provider permission control (W16AAPPLE-43), under
 * the run's header, as the Host reports it under an Auto approval mode.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = RobolectricDeviceQualifiers.Pixel5)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WorkRoomPermissionScreenshotTest {

    @get:Rule(order = 0)
    val localeRule = DeterministicLocaleRule()

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    private val run = ChatItem.ProviderInvocation(
        id = "codex:call-1",
        provider = "codex",
        taskTitle = "Add a login page",
        status = ProviderInvocationStatus.RUNNING,
        stateRevision = 4,
        elapsedMs = 42_000,
        activities = listOf(
            ProviderActivity("a", 1, "file_change", ProviderActivityStatus.COMPLETED, "Update workspace files", "Changed 2 files", listOf("Login.kt")),
            ProviderActivity("b", 2, "command", ProviderActivityStatus.RUNNING, "Run the requested tests", "")
        ),
        providerPermission = ProviderPermissionState(
            provider = "codex",
            activeOptionId = "codex:workspace-ask",
            options = listOf(
                ProviderPermissionOption("codex:read-only", "Read Only", selectable = true),
                ProviderPermissionOption(
                    "codex:workspace-ask",
                    "Ask for approval",
                    "Codex can work in the workspace and asks before protected actions.",
                    selectable = true
                ),
                ProviderPermissionOption("codex:full-access", "Full Access", elevated = true)
            )
        )
    )

    private fun capture(palette: Palette) =
        composeRule.captureThemed("work_room_permission", palette) {
            WorkRoomScreen(run = run, onNavigateBack = {})
        }

    @Test
    fun `work room permission - light`() = capture(Palette.Light)

    @Test
    fun `work room permission - dark`() = capture(Palette.Dark)
}
