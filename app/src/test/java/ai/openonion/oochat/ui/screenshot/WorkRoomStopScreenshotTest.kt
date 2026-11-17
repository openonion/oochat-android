package ai.openonion.oochat.ui.screenshot

import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.domain.model.ProviderActivity
import ai.openonion.oochat.domain.model.ProviderActivityStatus
import ai.openonion.oochat.domain.model.ProviderInvocationStatus
import ai.openonion.oochat.domain.model.WorkRoomStopState
import ai.openonion.oochat.ui.workroom.WorkRoomScreen
import ai.openonion.oochat.ui.workroom.WorkRoomStopUi
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A Codex Work Room's scoped Stop (W16AAPPLE-37): the button under the run's
 * header, and the confirmation it opens, at the start of its 15 seconds.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = RobolectricDeviceQualifiers.Pixel5)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WorkRoomStopScreenshotTest {

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
        )
    )

    private fun capture(name: String, palette: Palette, ui: WorkRoomStopUi) =
        composeRule.captureThemed(name, palette) {
            WorkRoomScreen(run = run, onNavigateBack = {}, stopUi = ui)
        }

    @Test
    fun `stop button - light`() = capture("work_room_stop", Palette.Light, WorkRoomStopUi())

    @Test
    fun `stop button - dark`() = capture("work_room_stop", Palette.Dark, WorkRoomStopUi())

    @Test
    fun `stop confirmation - light`() =
        capture("work_room_stop_confirm", Palette.Light, WorkRoomStopUi(WorkRoomStopState.Confirming))

    @Test
    fun `stop confirmation - dark`() =
        capture("work_room_stop_confirm", Palette.Dark, WorkRoomStopUi(WorkRoomStopState.Confirming))
}
