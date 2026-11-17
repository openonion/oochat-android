package ai.openonion.oochat.ui.screenshot

import ai.openonion.oochat.domain.model.AgentSkill
import ai.openonion.oochat.domain.model.ApprovalBoundary
import ai.openonion.oochat.domain.model.ApprovalMode
import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.domain.model.ConnectionState
import ai.openonion.oochat.domain.model.IntentStatus
import ai.openonion.oochat.domain.model.ProviderApprovalPresentation
import ai.openonion.oochat.domain.model.ProviderInvocationStatus
import ai.openonion.oochat.domain.model.SessionActivity
import ai.openonion.oochat.domain.model.SessionModes
import ai.openonion.oochat.domain.model.SessionUsageTotals
import ai.openonion.oochat.domain.model.ThemeMode
import ai.openonion.oochat.domain.model.ThinkingStatus
import ai.openonion.oochat.domain.model.ToolStatus
import ai.openonion.oochat.domain.model.UserMessageState
import ai.openonion.oochat.domain.model.WorkRoomApproval
import ai.openonion.oochat.ui.chat.MessageList
import ai.openonion.oochat.ui.chat.components.ChatComposerDraft
import ai.openonion.oochat.ui.chat.components.ChatTopBar
import ai.openonion.oochat.ui.chat.components.InputBar
import ai.openonion.oochat.ui.chat.components.TURN_SUMMARY_TAG
import ai.openonion.oochat.ui.theme.ConnectOnionTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The chat screenshots in the repository's own `screenshots/` folder, which
 * USER_GUIDE.md and README.md show (W16AAPPLE-112 restyled the chat they
 * picture). Rendered here rather than captured off a phone, so they follow the
 * design as it changes: re-record with `./gradlew :app:recordRoborazziDebug`.
 *
 * Light only, at a phone's 1080px width, the chat as a user sees it: top bar,
 * transcript and composer. Like [ChatScreenshotTest], ChatScreen itself needs a
 * ViewModel and a live relay, so the screen is assembled from the same parts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = RobolectricDeviceQualifiers.Pixel5)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UserGuideScreenshotTest {

    @get:Rule(order = 0)
    val localeRule = DeterministicLocaleRule()

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    /** The repository's `screenshots/`, found from the module directory tests run in. */
    private val guideDir: File = listOf(File("../screenshots"), File("screenshots")).first { it.isDirectory }

    private fun capture(name: String, interact: () -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
        composeRule.setContent {
            ConnectOnionTheme(themeMode = ThemeMode.LIGHT, fontScale = 1f) {
                Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) { content() }
            }
        }
        composeRule.waitForIdle()
        interact()
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage(File(guideDir, "$name.png").path)
    }

    @Composable
    private fun ColumnScope.ChatScreenParts(
        title: String,
        items: List<ChatItem>,
        running: Boolean = false,
        draft: String = "",
        skills: List<AgentSkill> = emptyList(),
        usage: SessionUsageTotals = SessionUsageTotals(25_300, 0.015, null)
    ) {
        ChatTopBar(
            title = title,
            connectionState = ConnectionState.Connected(address = AGENT_ADDRESS),
            isConnected = true,
            onClearChat = {},
            onNavigateToLogs = {},
            onMenuClick = {},
            modelName = "gemini-3.8-flash",
            sessionActivity = if (running) SessionActivity.RUNNING else SessionActivity.IDLE,
        )
        // The transcript takes the height the top bar and composer leave.
        MessageList(
            items = items,
            timestamps = TIMESTAMPS.filterKeys { key -> items.any { it.id == key } },
            wasCleared = false,
            onRespond = { _, _ -> },
            onApprove = { _, _, _, _, _ -> },
            onOnboard = { _, _, _ -> },
            onPlanReviewResponse = { _, _ -> },
            onUlwResponse = { _, _, _ -> },
            onResendMessage = {},
            modifier = Modifier.weight(1f),
        )
        InputBar(
            isConnected = true,
            isAgentWorking = running,
            approvalMode = ApprovalMode.AUTO,
            sessionModes = SessionModes(available = ApprovalMode.entries.toList(), hostManaged = true),
            usage = usage,
            skills = skills,
            onSend = { _, _, _ -> },
            draft = ChatComposerDraft(text = draft),
            draftKey = "guide",
        )
    }

    @Test
    fun `turn steps summary`() = capture(
        "turn-steps-summary",
        interact = { composeRule.onNodeWithTag(TURN_SUMMARY_TAG).performClick() }
    ) {
        ChatScreenParts(title = "open up a work room", items = FINISHED_TURN)
    }

    @Test
    fun `keep typing while working`() = capture("keep-typing-while-working") {
        ChatScreenParts(
            title = "What is a WebSocket",
            items = RUNNING_TURN,
            running = true,
            draft = "and what about SSE?",
            usage = SessionUsageTotals(12_400, 0.006, null),
        )
    }

    @Test
    fun `send states`() = capture("send-states") {
        ChatScreenParts(title = "Weather in Sydney", items = SEND_STATES, usage = SessionUsageTotals(1_900, 0.001, null))
    }

    @Test
    fun `slash command palette`() = capture("slash-command-palette") {
        ChatScreenParts(title = "New chat", items = emptyList(), draft = "/", skills = SKILLS, usage = SessionUsageTotals(0, 0.0, null))
    }

    @Test
    fun `dictation fills composer`() = capture("dictation-fills-composer") {
        ChatScreenParts(title = "Hi", items = GREETING, draft = DICTATED, usage = SessionUsageTotals(3_400, 0.0046, null))
    }

    @Test
    fun `dictation editable`() = capture(
        "dictation-editable",
        interact = {
            composeRule.onNode(hasSetTextAction()).performClick()
            composeRule.onNode(hasSetTextAction()).performTextInputSelection(TextRange(DICTATED.indexOf("Saturday")))
        }
    ) {
        ChatScreenParts(title = "Hi", items = GREETING, draft = DICTATED, usage = SessionUsageTotals(3_400, 0.0046, null))
    }

    @Test
    fun `work room approval in the chat`() = capture("work-room-approval-chat") {
        ChatScreenParts(
            title = "Use Claude Code to create a file hello.txt",
            items = WORK_ROOM,
            running = true,
            usage = SessionUsageTotals(12_600, 0.0054, null),
        )
    }

    private companion object {
        const val AGENT_ADDRESS = "0x4f3a9c2b8e1d7a6f5c4b3a2918d7e6f5c4b3a291"
        const val DAY = 1_705_312_800_000L // 2024-01-15 10:00 UTC

        val TIMESTAMPS = mapOf(
            "u1" to DAY, "r1" to DAY + 16_000L,
            "s1" to DAY, "s2" to DAY + 20_000L, "s3" to DAY + 40_000L, "s4" to DAY + 60_000L,
            "g1" to DAY, "g2" to DAY + 3_000L,
            // The running turns' questions are left undated, so their
            // "Working…" clock counts from the capture, not from years ago.
        )

        private fun bash(id: String, command: String, timingMs: Long?, status: ToolStatus = ToolStatus.DONE) =
            ChatItem.ToolCall(id = id, name = "bash", args = mapOf("command" to command), status = status, timingMs = timingMs)

        val FINISHED_TURN = listOf(
            ChatItem.User(id = "u1", content = "open up a work room"),
            ChatItem.Thinking(
                id = "t1",
                status = ThinkingStatus.DONE,
                content = "Understood, I'll open up a work room for you",
                kind = "intent",
                durationMs = 2000.0,
            ),
            ChatItem.ToolCall(id = "c1", name = "glob", args = mapOf("pattern" to "*"), timingMs = 2000L, status = ToolStatus.DONE),
            ChatItem.ToolCall(id = "c2", name = "load_guide", args = mapOf("path" to "index"), timingMs = 3000L, status = ToolStatus.DONE),
            bash("c3", "echo \$PWD; ls -la ~/.config; ls -la ~/.connectonion", 3000L, ToolStatus.ERROR),
            bash("c4", "pwd", 2000L),
            bash("c5", "ls -la .connectonion", 4000L),
            bash("c6", "which co; python3 -c \"import connectonion; print(connectonion.__file__)\"", null),
            ChatItem.Turn(
                id = "r1",
                thinking = ChatItem.Thinking(id = "r1", status = ThinkingStatus.DONE, tokensTotal = 25_300, costUsd = 0.015),
                agent = ChatItem.Agent(
                    id = "r1",
                    content = "Your work room is open. It is running in ~/.connectonion and is ready for its first task.",
                ),
            ),
        )

        val RUNNING_TURN = listOf(
            ChatItem.User(id = "k1", content = "What is a WebSocket"),
            ChatItem.Thinking(
                id = "k2",
                status = ThinkingStatus.DONE,
                content = "I'll look up how WebSockets work and compare them with HTTP",
                kind = "intent",
            ),
            ChatItem.ToolCall(id = "k3", name = "search", args = mapOf("pattern" to "WebSocket handshake"), timingMs = 1000L, status = ToolStatus.DONE),
            ChatItem.ToolCall(id = "k4", name = "read", args = mapOf("path" to "docs/rfc6455.txt"), timingMs = 2000L, status = ToolStatus.DONE),
            ChatItem.ToolCall(id = "k5", name = "search", args = mapOf("pattern" to "WebSocket vs HTTP polling"), status = ToolStatus.RUNNING),
            ChatItem.Turn(id = "k6", thinking = ChatItem.Thinking(id = "k6", status = ThinkingStatus.RUNNING)),
        )

        /** One message that never left the device, one still waiting for the socket. */
        val SEND_STATES = listOf(
            ChatItem.User(id = "s1", content = "What is the weather in Sydney?"),
            ChatItem.Turn(
                id = "s2",
                thinking = ChatItem.Thinking(id = "s2", status = ThinkingStatus.DONE, tokensTotal = 1_900, costUsd = 0.001),
                agent = ChatItem.Agent(id = "s2", content = "19 degrees and clear."),
            ),
            ChatItem.User(id = "s3", content = "And tomorrow?", state = UserMessageState.FAILED),
            ChatItem.User(id = "s4", content = "The weekend too, when you get a moment.", state = UserMessageState.QUEUED),
        )

        val SKILLS = listOf(
            AgentSkill("explain", "Explain a snippet of code in plain language"),
            AgentSkill("translate", "Translate the message into English, or into Chinese if it is already English"),
            AgentSkill("summarise", "Condense a piece of text into three bullet points"),
        )

        val GREETING = listOf(
            ChatItem.User(id = "g1", content = "Hi"),
            ChatItem.Turn(
                id = "g2",
                thinking = ChatItem.Thinking(id = "g2", status = ThinkingStatus.DONE, tokensTotal = 3_400, costUsd = 0.0046),
                agent = ChatItem.Agent(
                    id = "g2",
                    content = "Hello! I can help with tasks on your computer, like browsing websites, working with files, or answering questions.",
                ),
            ),
        )

        const val DICTATED = "Can you book a table for two on Saturday evening"

        val WORK_ROOM = listOf(
            ChatItem.User(id = "w-u", content = "Use Claude Code to create a file hello.txt in this folder containing \"hi\"."),
            ChatItem.IntentItem(
                id = "w-i",
                status = IntentStatus.UNDERSTOOD,
                ack = "Understood, I'll create the hello.txt file for you right away.",
            ),
            ChatItem.ProviderInvocation(
                id = "w-run",
                parentToolCallId = "call-1",
                provider = "claude_code",
                providerDisplayName = "Claude Code",
                taskTitle = "Implement and verify the requested change",
                status = ProviderInvocationStatus.AWAITING_APPROVAL,
                currentSummary = "Update workspace files",
            ),
            ChatItem.ApprovalNeeded(
                id = "w-a",
                tool = "claude_code",
                arguments = emptyMap(),
                requestId = "w-a",
                workRoom = WorkRoomApproval(
                    "claude_code",
                    "w-run",
                    "call-1",
                    presentation = ProviderApprovalPresentation(
                        action = "Make workspace file changes",
                        scope = "This Work Room only",
                        reason = "Apply the requested workspace file changes",
                        boundary = ApprovalBoundary.WORKROOM,
                        allowOnce = true,
                        allowSession = false,
                        files = listOf("hello.txt"),
                    ),
                ),
            ),
        )
    }
}
