package ai.openonion.oochat.ui.chat

import ai.openonion.oochat.domain.model.ApprovalMode
import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.domain.model.CompactStatus
import ai.openonion.oochat.domain.model.EvalStatus
import ai.openonion.oochat.domain.model.IntentStatus
import ai.openonion.oochat.domain.model.ReceivedFile
import ai.openonion.oochat.domain.model.ThinkingStatus
import ai.openonion.oochat.domain.model.ToolStatus
import ai.openonion.oochat.domain.model.UserMessageState
import ai.openonion.oochat.ui.chat.components.ApprovalModeChip
import ai.openonion.oochat.ui.chat.components.ApprovalModeSheet
import ai.openonion.oochat.ui.chat.components.InputBar
import ai.openonion.oochat.ui.theme.ConnectOnionTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every control in the chat and its approvals has a name TalkBack can read (W16AAPPLE-48). This
 * renders the chat's parts in the states that put controls on screen and fails on any node that
 * can be clicked, toggled or typed into but has no text and no content description once its
 * children are merged in, which is what TalkBack announces for it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatControlsLabelledComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val interactive = hasClickAction() or hasSetTextAction() or
        SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState)

    /** What is on screen that can be operated but cannot be named. */
    private fun unnamedControls(): List<String> =
        composeRule.onAllNodes(interactive).fetchSemanticsNodes().filter { node ->
            val config = node.config
            val description = config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString("")
            val text = config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString("") { it.text }
            val typed = config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()
            val hint = config.getOrNull(SemanticsActions.OnClick)?.label.orEmpty()
            listOf(description, text, typed, hint).all { it.isBlank() }
        }.map { "id=${it.id} bounds=${it.boundsInRoot} role=${it.config.getOrNull(SemanticsProperties.Role)}" }

    private fun assertAllNamed(where: String) {
        composeRule.waitForIdle()
        val inspected = composeRule.onAllNodes(interactive).fetchSemanticsNodes().size
        assertTrue("$where: expected controls to inspect, found none", inspected > 0)
        val unnamed = unnamedControls()
        assertTrue("$where: ${unnamed.size} of $inspected control(s) with no name: $unnamed", unnamed.isEmpty())
    }

    private fun show(content: @Composable () -> Unit) {
        composeRule.setContent { ConnectOnionTheme { content() } }
    }

    private val everyKindOfItem: List<ChatItem> = listOf(
        ChatItem.User("u1", "hello"),
        ChatItem.User("u2", "never left the device", state = UserMessageState.FAILED),
        ChatItem.User("u3", "waiting", state = UserMessageState.QUEUED),
        ChatItem.Turn(
            "t1",
            thinking = ChatItem.Thinking("t1", ThinkingStatus.DONE, content = "pondering", kind = "intent"),
            agent = ChatItem.Agent("t1", "an answer")
        ),
        ChatItem.Thinking("th1", ThinkingStatus.ERROR, content = "it failed"),
        ChatItem.ToolCall("tc1", "bash", mapOf("command" to "ls"), ToolStatus.DONE, result = "ok"),
        ChatItem.ToolCall("tc2", "bash", mapOf("command" to "ls"), ToolStatus.ERROR, result = "no"),
        ChatItem.AskUser("a1", "Which one?", listOf("First", "Second"), multiSelect = false),
        ChatItem.ApprovalNeeded("ap1", "bash", mapOf("command" to "rm x"), description = "remove a file"),
        ChatItem.OnboardRequired("o1", listOf("invite_code", "payment"), 1.0, "0xabc"),
        ChatItem.OnboardingFailed("o2", "bad code"),
        ChatItem.OnboardSuccess("o3", "open", "welcome"),
        ChatItem.IntentItem("i1", IntentStatus.UNDERSTOOD, ack = "ok", isBuild = true),
        ChatItem.EvalItem("e1", EvalStatus.DONE, passed = true, summary = "fine"),
        ChatItem.CompactItem("c1", CompactStatus.DONE, 80.0, 20.0, 20.0, message = "compacted"),
        ChatItem.ToolBlockedItem("b1", "bash", "policy", "blocked", command = "rm -rf /"),
        ChatItem.PlanReviewItem("p1", "1. do it"),
        ChatItem.FilesReceivedItem("f1", listOf(ReceivedFile("a.txt", "/a.txt"))),
        ChatItem.UlwTurnsReachedItem("ul1", 10, 10),
        ChatItem.DiffPreviewItem("d1", "/a.txt", "+new\n-old"),
        ChatItem.ModeChangedItem("m1", "plan", "agent"),
        ChatItem.ProviderInvocation("w1", provider = "codex", taskTitle = "Add a login page")
    )

    @Test
    fun `every kind of chat item has named controls`() {
        show {
            MessageList(
                items = everyKindOfItem,
                timestamps = everyKindOfItem.associate { it.id to 1_700_000_000_000L },
                wasCleared = false,
                onRespond = { _, _ -> },
                onApprove = { _, _, _, _, _ -> },
                onOnboard = { _, _, _ -> },
                onPlanReviewResponse = {},
                onUlwResponse = { _, _, _ -> },
                onResendMessage = {},
                modifier = Modifier.requiredHeight(6000.dp)
            )
        }
        assertAllNamed("the transcript")
    }

    @Test
    fun `the composer has named controls idle and while the agent works`() {
        show {
            Column {
                InputBar(isConnected = true, onSend = { _, _, _ -> })
                InputBar(isConnected = true, isAgentWorking = true, onSend = { _, _, _ -> })
            }
        }
        assertAllNamed("the composer")
    }

    @Test
    fun `the approval mode controls have named controls in every mode`() {
        show {
            Column {
                ApprovalMode.entries.forEach { mode ->
                    ApprovalModeChip(mode = mode, enabled = true, onCycle = {}, onOpenSheet = {})
                }
                ApprovalModeSheet(current = ApprovalMode.ULW, onSelect = { _, _ -> }, onDismiss = {})
            }
        }
        assertAllNamed("the approval mode controls")
    }
}
