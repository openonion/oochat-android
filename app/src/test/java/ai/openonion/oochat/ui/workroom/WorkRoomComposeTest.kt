package ai.openonion.oochat.ui.workroom

import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.domain.model.ChatItemPrivacy
import ai.openonion.oochat.domain.model.ProviderActivity
import ai.openonion.oochat.domain.model.ProviderActivityStatus
import ai.openonion.oochat.domain.model.ProviderArtifact
import ai.openonion.oochat.domain.model.ProviderInvocationStatus
import ai.openonion.oochat.domain.model.ProviderMessage
import ai.openonion.oochat.domain.model.ProviderMessageRole
import ai.openonion.oochat.ui.chat.components.ChatItemBubble
import ai.openonion.oochat.ui.chat.components.WorkRoomCard
import ai.openonion.oochat.ui.navigation.Routes
import ai.openonion.oochat.ui.theme.ConnectOnionTheme
import ai.openonion.oochat.util.SensitiveText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** W16AAPPLE-33: a Claude Code or Codex run's card in the chat, and its Work Room. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WorkRoomComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val running = ChatItem.ProviderInvocation(
        id = "codex:call-1",
        parentToolCallId = "call-1",
        provider = "codex",
        taskTitle = "Add a login page",
        status = ProviderInvocationStatus.RUNNING,
        currentSummary = "Working in the selected workspace",
        activities = listOf(
            ProviderActivity("a", 1, "file_change", ProviderActivityStatus.COMPLETED, "Update workspace files", "Changed 2 files", listOf("Login.kt", "login.xml")),
            ProviderActivity("b", 2, "command", ProviderActivityStatus.RUNNING, "Run the requested tests", "Running the tests")
        )
    )

    @Test
    fun `the chat shows the run as its provider's Work Room and opens it by its id`() {
        var opened: String? = null
        composeRule.setContent {
            ConnectOnionTheme {
                ChatItemBubble(
                    item = running,
                    timestamp = null,
                    onRespond = {},
                    onApprove = { _, _, _, _ -> },
                    onOnboard = { _, _, _ -> },
                    onPlanReviewResponse = {},
                    onUlwResponse = { _, _, _ -> },
                    onOpenWorkRoom = { opened = it }
                )
            }
        }

        composeRule.onNodeWithText("Codex · Work Room").assertIsDisplayed()
        composeRule.onNodeWithText("Add a login page").assertIsDisplayed()
        composeRule.onNodeWithText("Working").assertIsDisplayed()
        // The newest step, not the Host's "Working in the selected workspace", which never changes.
        composeRule.onNodeWithText("Run the requested tests").assertIsDisplayed()
        composeRule.onNodeWithText("2 steps").assertIsDisplayed()

        composeRule.onNodeWithText("Open Work Room").performClick()
        assertEquals("codex:call-1", opened)
    }

    @Test
    fun `the Work Room lists each step with its files`() {
        composeRule.setContent { ConnectOnionTheme { WorkRoomScreen(run = running, onNavigateBack = {}) } }

        composeRule.onNodeWithText("Codex Work Room").assertIsDisplayed()
        composeRule.onNodeWithText("Update workspace files").assertIsDisplayed()
        composeRule.onNodeWithText("Changed 2 files").assertIsDisplayed()
        composeRule.onNodeWithText("Login.kt, login.xml").assertIsDisplayed()
        composeRule.onNodeWithText("Run the requested tests").assertIsDisplayed()
    }

    @Test
    fun `a finished run says how it ended`() {
        val failed = running.copy(
            status = ProviderInvocationStatus.FAILED,
            errorSummary = "The provider reported an error",
            elapsedMs = 65_000L
        )
        composeRule.setContent { ConnectOnionTheme { WorkRoomScreen(run = failed, onNavigateBack = {}) } }

        composeRule.onNodeWithText("Failed").assertIsDisplayed()
        composeRule.onNodeWithText("The provider reported an error").assertIsDisplayed()
        composeRule.onNodeWithText("Took 1 min 5 s").assertIsDisplayed()
    }

    @Test
    fun `a working run's time does not read as final`() {
        composeRule.setContent { ConnectOnionTheme { WorkRoomScreen(run = running.copy(elapsedMs = 42_000L), onNavigateBack = {}) } }

        composeRule.onNodeWithText("Running for 42 s").assertIsDisplayed()
        composeRule.onNodeWithText("Took 42 s").assertDoesNotExist()
    }

    @Test
    fun `a stopped run does not still say it is working`() {
        // A Host-made Stop frame carries the running summary over.
        val stopped = running.copy(status = ProviderInvocationStatus.CANCELLED)
        composeRule.setContent { ConnectOnionTheme { WorkRoomCard(stopped, onOpen = {}) } }

        composeRule.onNodeWithText("Stopped").assertIsDisplayed()
        composeRule.onNodeWithText("Working in the selected workspace").assertDoesNotExist()
        composeRule.onNodeWithText("Run the requested tests").assertDoesNotExist()
    }

    @Test
    fun `a run that ended unseen says so rather than guessing how`() {
        val ended = running.copy(status = ProviderInvocationStatus.ENDED)
        composeRule.setContent { ConnectOnionTheme { WorkRoomScreen(run = ended, onNavigateBack = {}) } }

        composeRule.onNodeWithText("Ended").assertIsDisplayed()
        composeRule.onNodeWithText(WorkRoomText.ENDED_UNSEEN).assertIsDisplayed()
    }

    @Test
    fun `a running step's spinner is read out`() {
        composeRule.setContent { ConnectOnionTheme { WorkRoomScreen(run = running, onNavigateBack = {}) } }

        composeRule.onNodeWithContentDescription("Running").assertExists()
    }

    @Test
    fun `the room's route carries an invocation id with its colon`() {
        assertEquals("work_room/codex%3Acall-1", Routes.workRoom("codex:call-1"))
    }

    @Test
    fun `the Work Room restores its viewport after leaving and reopening`() {
        val manySteps = running.copy(
            activities = (1..12).map { step ->
                ProviderActivity(
                    id = "step-$step",
                    sequence = step,
                    kind = "command",
                    status = ProviderActivityStatus.COMPLETED,
                    title = "Step $step",
                    summary = "Completed step $step"
                )
            }
        )
        var room by mutableStateOf(manySteps)
        var showing by mutableStateOf(true)
        var savedPosition by mutableStateOf(WorkRoomScrollPosition())

        composeRule.setContent {
            ConnectOnionTheme {
                if (showing) {
                    WorkRoomScreen(
                        run = room,
                        onNavigateBack = {},
                        scrollPosition = savedPosition,
                        onScrollPositionChanged = { savedPosition = it }
                    )
                }
            }
        }

        composeRule.onNodeWithTag(WORK_ROOM_TRANSCRIPT_TAG).performScrollToIndex(8)
        composeRule.waitForIdle()
        val beforeLeaving = savedPosition
        assertTrue("the list must have moved before leaving", beforeLeaving.firstVisibleItemIndex > 0)
        assertTrue("the viewport must record a stable item key", beforeLeaving.firstVisibleItemKey != null)

        composeRule.runOnIdle { showing = false }
        composeRule.waitForIdle()
        room = manySteps.copy(
            activities = listOf(
                ProviderActivity(
                    id = "inserted-before-reopen",
                    sequence = 0,
                    kind = "command",
                    status = ProviderActivityStatus.COMPLETED,
                    title = "Inserted step",
                    summary = "Arrived while chat was showing"
                )
            ) + manySteps.activities
        )
        composeRule.runOnIdle { showing = true }
        composeRule.waitForIdle()

        assertEquals(
            "reopening must keep the same item anchored after an insertion",
            beforeLeaving.firstVisibleItemKey,
            savedPosition.firstVisibleItemKey
        )
        composeRule.onNodeWithText("Step 8").assertIsDisplayed()
    }

    @Test
    fun `a run the chat no longer holds says so instead of a blank screen`() {
        composeRule.setContent { ConnectOnionTheme { WorkRoomScreen(run = null, onNavigateBack = {}) } }

        composeRule.onNodeWithText("This Work Room is no longer in the chat.").assertIsDisplayed()
    }

    // W16AAPPLE-35: steering the run from its room.

    private val steerable = running.copy(stateRevision = 4)

    @Test
    fun `typing into the room and sending it`() {
        var typed: String? = null
        var sends = 0
        composeRule.setContent {
            ConnectOnionTheme {
                WorkRoomScreen(
                    run = steerable,
                    onNavigateBack = {},
                    draft = WorkRoomDraft(text = "Use the existing auth helper"),
                    onDraftChange = { typed = it },
                    onSend = { sends++ }
                )
            }
        }

        composeRule.onNodeWithText("Use the existing auth helper").performTextReplacement("Use the existing auth helper, not a new one")
        composeRule.onNodeWithContentDescription("Send").assertIsEnabled().performClick()

        assertEquals("Use the existing auth helper, not a new one", typed)
        assertEquals(1, sends)
    }

    @Test
    fun `an empty field names the provider and cannot send`() {
        composeRule.setContent { ConnectOnionTheme { WorkRoomScreen(run = steerable, onNavigateBack = {}) } }

        composeRule.onNodeWithText("Message Codex").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Send").assertIsNotEnabled()
    }

    @Test
    fun `a send waiting on the provider shows it is sending and cannot be sent again`() {
        composeRule.setContent {
            ConnectOnionTheme {
                WorkRoomScreen(run = steerable, onNavigateBack = {}, draft = WorkRoomDraft(text = "hello", sending = true))
            }
        }

        composeRule.onNodeWithContentDescription("Sending").assertExists()
        composeRule.onNodeWithContentDescription("Send").assertDoesNotExist()
        composeRule.onNodeWithText("hello").assertIsNotEnabled()
    }

    @Test
    fun `a send that did not go through keeps the text and says why`() {
        val error = WorkRoomText.inputRefused("state_changed")
        composeRule.setContent {
            ConnectOnionTheme {
                WorkRoomScreen(run = steerable, onNavigateBack = {}, draft = WorkRoomDraft(text = "hello", error = error))
            }
        }

        composeRule.onNodeWithText(error).assertIsDisplayed()
        composeRule.onNodeWithText("hello").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Send").assertIsEnabled()
    }

    @Test
    fun `a Claude Code run still working says when it will take a message`() {
        val claude = steerable.copy(id = "claude_code:call-2", provider = "claude_code")
        composeRule.setContent {
            ConnectOnionTheme { WorkRoomScreen(run = claude, onNavigateBack = {}, draft = WorkRoomDraft(text = "hello")) }
        }

        composeRule.onNodeWithText(WorkRoomText.INPUT_CLAUDE_BUSY).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Send").assertIsNotEnabled()
    }

    // ── provider messages (W16AAPPLE-34) ──────────────────────────────

    private val conversation = running.copy(
        messages = listOf(
            ProviderMessage("m1", ProviderMessageRole.USER, "Make the login remember me"),
            ProviderMessage(
                "m2",
                ProviderMessageRole.ASSISTANT,
                "I added it. Run the tests with:\n```\n./gradlew testDebugUnitTest\n```\nand tell me what fails."
            )
        )
    )

    private fun fontOf(text: String): FontFamily? {
        val results = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.first().layoutInput.style.fontFamily
    }

    @Test
    fun `the Work Room shows what was said, by the user and by the provider`() {
        composeRule.setContent { ConnectOnionTheme { WorkRoomScreen(run = conversation, onNavigateBack = {}) } }

        composeRule.onNodeWithText("Conversation").assertIsDisplayed()
        composeRule.onNodeWithText("Make the login remember me", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("You", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Codex", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("I added it. Run the tests with:", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("and tell me what fails.", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `a run nobody has spoken in shows no conversation`() {
        composeRule.setContent { ConnectOnionTheme { WorkRoomScreen(run = running, onNavigateBack = {}) } }

        composeRule.onNodeWithText("Conversation").assertDoesNotExist()
    }

    @Test
    fun `code in a message is monospace and the prose around it is not`() {
        composeRule.setContent { ConnectOnionTheme { WorkRoomScreen(run = conversation, onNavigateBack = {}) } }

        assertEquals(FontFamily.Monospace, fontOf("./gradlew testDebugUnitTest"))
        assertNotEquals(FontFamily.Monospace, fontOf("I added it. Run the tests with:"))
    }

    @Test
    fun `each message is one item for TalkBack, the speaker then the words`() {
        composeRule.setContent { ConnectOnionTheme { WorkRoomScreen(run = conversation, onNavigateBack = {}) } }

        composeRule.onNode(hasText("You") and hasText("Make the login remember me")).assertIsDisplayed()
        composeRule.onNode(hasText("Codex") and hasText("and tell me what fails.")).assertIsDisplayed()
    }

    @Test
    fun `a message that is a raw provider payload is not shown, only that its details are in the logs`() {
        val payload = """{"type": "result", "subtype": "success", "session_id": "abc"}"""
        val run = conversation.copy(messages = listOf(ProviderMessage("m1", ProviderMessageRole.ASSISTANT, payload)))
        // The chat view model hands the screen the chat as ChatItemPrivacy redacts it.
        val shown = ChatItemPrivacy.redact(run) as ChatItem.ProviderInvocation
        composeRule.setContent { ConnectOnionTheme { WorkRoomScreen(run = shown, onNavigateBack = {}) } }

        composeRule.onNodeWithText(SensitiveText.UNREADABLE_SUMMARY, useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText(payload, substring = true, useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithText("session_id", substring = true, useUnmergedTree = true).assertDoesNotExist()
    }

    // ── provider artifacts (W16AAPPLE-34) ─────────────────────────────

    private val pixel = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="

    private fun withPicture(dataUrl: String, pictureRevision: Long = 2) = running.copy(
        stateRevision = 2,
        artifact = ProviderArtifact("shot-1", dataUrl, "Latest provider workspace view", pictureRevision)
    )

    @Test
    fun `the Work Room shows the latest picture of the run, described by the Host's own words`() {
        composeRule.setContent { ConnectOnionTheme { WorkRoomScreen(run = withPicture(pixel), onNavigateBack = {}) } }

        composeRule.onNodeWithText("Latest view").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Latest provider workspace view").assertIsDisplayed()
    }

    @Test
    fun `a picture of an earlier state of the run is not shown as the latest view`() {
        composeRule.setContent {
            ConnectOnionTheme { WorkRoomScreen(run = withPicture(pixel, pictureRevision = 1), onNavigateBack = {}) }
        }

        composeRule.onNodeWithText("Latest view").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Latest provider workspace view").assertDoesNotExist()
    }

    @Test
    fun `a run with no picture shows none`() {
        composeRule.setContent { ConnectOnionTheme { WorkRoomScreen(run = running, onNavigateBack = {}) } }

        composeRule.onNodeWithText("Latest view").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Latest provider workspace view").assertDoesNotExist()
    }

    @Test
    fun `a picture that cannot be read leaves the rest of the room as it was`() {
        composeRule.setContent {
            ConnectOnionTheme { WorkRoomScreen(run = withPicture("data:image/png;base64,AAAA"), onNavigateBack = {}) }
        }

        composeRule.onNodeWithText("Codex Work Room").assertIsDisplayed()
        composeRule.onNodeWithText("Update workspace files").assertIsDisplayed()
    }
}
