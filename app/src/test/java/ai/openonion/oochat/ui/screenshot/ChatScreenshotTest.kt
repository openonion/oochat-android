package ai.openonion.oochat.ui.screenshot

import ai.openonion.oochat.domain.model.AgentSkill
import ai.openonion.oochat.domain.model.ApprovalDecision
import ai.openonion.oochat.domain.model.ApprovalMode
import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.domain.model.ConnectionState
import ai.openonion.oochat.domain.model.SessionActivity
import ai.openonion.oochat.domain.model.SessionModes
import ai.openonion.oochat.domain.model.SessionUsageTotals
import ai.openonion.oochat.domain.model.ThinkingStatus
import ai.openonion.oochat.domain.model.ToolStatus
import ai.openonion.oochat.domain.model.UserMessageState
import ai.openonion.oochat.ui.adaptive.HomeTab
import ai.openonion.oochat.ui.adaptive.HomeTabs
import ai.openonion.oochat.ui.chat.EmptyChat
import ai.openonion.oochat.ui.chat.MessageList
import ai.openonion.oochat.ui.chat.components.ApprovalCard
import ai.openonion.oochat.ui.chat.components.ApprovalModeChip
import ai.openonion.oochat.ui.chat.components.ApprovalModeSheet
import ai.openonion.oochat.ui.chat.components.ChatTopBar
import ai.openonion.oochat.ui.chat.components.CtxRing
import ai.openonion.oochat.ui.chat.components.DiffPreviewCard
import ai.openonion.oochat.ui.chat.components.InputBar
import ai.openonion.oochat.ui.chat.components.ModeChangedDivider
import ai.openonion.oochat.ui.chat.components.SessionUsageBar
import ai.openonion.oochat.ui.chat.components.TURN_SUMMARY_TAG
import ai.openonion.oochat.ui.chat.components.TurnThinkingFooter
import ai.openonion.oochat.ui.chat.components.VoiceInputPhase
import ai.openonion.oochat.ui.chat.components.VoiceInputState
import ai.openonion.oochat.ui.theme.spacing
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Chat surfaces: the hand-tuned [ChatTopBar], the message list (bubbles,
 * grouping, day divider, tool card), and the input bar.
 *
 * The full `ChatScreen` is not captured — it resolves a `ChatViewModel`
 * through the app container and opens a real relay connection as it
 * composes. These are the parts of it that carry the visual weight, and each
 * takes its state as a plain parameter.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = RobolectricDeviceQualifiers.Pixel5)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatScreenshotTest {

    @get:Rule(order = 0)
    val localeRule = DeterministicLocaleRule()

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    private fun captureTopBar(
        name: String,
        palette: Palette,
        interact: (ComposeContentTestRule.() -> Unit)? = null,
    ) = composeRule.captureThemed(name, palette, interact) {
        ChatTopBar(
            title = "research-assistant",
            connectionState = ConnectionState.Connected(address = AGENT_ADDRESS),
            isConnected = true,
            onClearChat = {},
            onNavigateToLogs = {},
            onMenuClick = {},
        )
    }

    @Test
    fun `top bar connected - light`() = captureTopBar("chat_top_bar", Palette.Light)

    @Test
    fun `top bar connected - dark`() = captureTopBar("chat_top_bar", Palette.Dark)

    private fun captureTopBarWithModel(
        name: String,
        palette: Palette,
    ) = composeRule.captureThemed(name, palette, null) {
        ChatTopBar(
            title = "research-assistant",
            connectionState = ConnectionState.Connected(address = AGENT_ADDRESS),
            isConnected = true,
            onClearChat = {},
            onNavigateToLogs = {},
            onMenuClick = {},
            modelName = "gemini-2.5-pro",
        )
    }

    /** Once an AGENT_PROFILE frame lands, a quiet second line appends the model — see ChatTopBar's modelName param. */
    @Test
    fun `top bar connected with model - light`() = captureTopBarWithModel("chat_top_bar_with_model", Palette.Light)

    @Test
    fun `top bar connected with model - dark`() = captureTopBarWithModel("chat_top_bar_with_model", Palette.Dark)

    /** A long title must truncate with an ellipsis rather than wrap the bar past 64dp. */
    private fun captureTopBarLongTitle(name: String, palette: Palette) =
        composeRule.captureThemed(name, palette, null) {
            ChatTopBar(
                title = "ML pipeline feature engineering and hyperparameter tuning strategy review",
                connectionState = ConnectionState.Connected(address = AGENT_ADDRESS),
                isConnected = true,
                onClearChat = {},
                onNavigateToLogs = {},
                onMenuClick = {},
                modelName = "gemini-2.5-flash",
            )
        }

    @Test
    fun `top bar long title truncates - light`() = captureTopBarLongTitle("chat_top_bar_long_title", Palette.Light)

    @Test
    fun `top bar long title truncates - dark`() = captureTopBarLongTitle("chat_top_bar_long_title", Palette.Dark)

    /** A CJK title exercises the same single-line-ellipsis path with no Latin fallback metrics to lean on. */
    private fun captureTopBarChineseTitle(name: String, palette: Palette) =
        composeRule.captureThemed(name, palette, null) {
            ChatTopBar(
                title = "量子纠缠与量子计算基础原理综述及其在信息安全领域的应用",
                connectionState = ConnectionState.Connected(address = AGENT_ADDRESS),
                isConnected = true,
                onClearChat = {},
                onNavigateToLogs = {},
                onMenuClick = {},
                modelName = "gemini-2.5-flash",
            )
        }

    @Test
    fun `top bar Chinese title - light`() = captureTopBarChineseTitle("chat_top_bar_chinese_title", Palette.Light)

    @Test
    fun `top bar Chinese title - dark`() = captureTopBarChineseTitle("chat_top_bar_chinese_title", Palette.Dark)

    private fun captureTopBarWithDashboard(
        name: String,
        palette: Palette,
    ) = composeRule.captureThemed(name, palette, null) {
        ChatTopBar(
            title = "research-assistant",
            connectionState = ConnectionState.Connected(address = AGENT_ADDRESS),
            isConnected = true,
            onClearChat = {},
            onNavigateToLogs = {},
            onMenuClick = {},
            hasDashboard = true,
        )
    }

    /** The Home entry point, which appears only once a DASHBOARD_SNAPSHOT has arrived. */
    @Test
    fun `top bar with dashboard - light`() = captureTopBarWithDashboard("chat_top_bar_with_dashboard", Palette.Light)

    @Test
    fun `top bar with dashboard - dark`() = captureTopBarWithDashboard("chat_top_bar_with_dashboard", Palette.Dark)

    /**
     * One of the app's two [androidx.compose.material3.DropdownMenu]s. Its
     * container color comes from the menu's own default rather than from
     * anything this app sets — exactly the kind of role a material3 bump
     * moves without touching a line of app code.
     */
    @Test
    fun `top bar overflow menu - light`() =
        captureTopBar("chat_top_bar_overflow_menu", Palette.Light) { openOverflowMenu() }

    @Test
    fun `top bar overflow menu - dark`() =
        captureTopBar("chat_top_bar_overflow_menu", Palette.Dark) { openOverflowMenu() }

    /** Clear-chat confirmation — one of the app's three `ModalBottomSheet` call sites. */
    @Test
    fun `clear chat sheet - light`() = captureTopBar("chat_clear_sheet", Palette.Light) {
        openOverflowMenu()
        onNodeWithText("Clear chat").performClick()
    }

    @Test
    fun `clear chat sheet - dark`() = captureTopBar("chat_clear_sheet", Palette.Dark) {
        openOverflowMenu()
        onNodeWithText("Clear chat").performClick()
    }

    private fun ComposeContentTestRule.openOverflowMenu() {
        onNodeWithContentDescription("More options").performClick()
        waitForIdle()
    }

    private fun captureMessageList(palette: Palette) =
        composeRule.captureThemed("chat_message_list", palette) {
            MessageList(
                items = CONVERSATION,
                timestamps = TIMESTAMPS,
                wasCleared = false,
                onRespond = { _, _ -> },
                onApprove = { _, _, _, _, _ -> },
                onOnboard = { _, _, _ -> },
                onPlanReviewResponse = { _, _ -> },
                onUlwResponse = { _, _, _ -> },
                modifier = Modifier.fillMaxSize(),
            )
        }

    /**
     * The two states a user's own message can be left in, side by side.
     *
     * Both are awkward to stage on a device — FAILED wants the socket to
     * refuse a write while the app still believes it is connected, and QUEUED
     * wants the send to land inside the window before the radio is noticed
     * gone. Here they are two items in a list.
     */
    private fun captureSendStates(palette: Palette) =
        composeRule.captureThemed("chat_send_states", palette) {
            MessageList(
                items = SEND_STATES,
                timestamps = SEND_STATE_TIMESTAMPS,
                wasCleared = false,
                onRespond = { _, _ -> },
                onApprove = { _, _, _, _, _ -> },
                onOnboard = { _, _, _ -> },
                onPlanReviewResponse = { _, _ -> },
                onUlwResponse = { _, _, _ -> },
                onResendMessage = {},
                modifier = Modifier.fillMaxSize(),
            )
        }

    @Test
    fun `send states - light`() = captureSendStates(Palette.Light)

    @Test
    fun `send states - dark`() = captureSendStates(Palette.Dark)

    /**
     * A turn's steps in the three states the design draws (W16AAPPLE-112):
     * running, finished and collapsed, and finished with the steps opened.
     */
    private fun captureTurnSteps(name: String, palette: Palette, items: List<ChatItem>, open: Boolean = false) =
        composeRule.captureThemed(
            name,
            palette,
            interact = if (open) {
                { onNodeWithTag(TURN_SUMMARY_TAG).performClick() }
            } else null,
        ) {
            MessageList(
                items = items,
                // The running turn's user message is left undated so its clock
                // counts from composition, not from a fixed instant years ago.
                timestamps = TURN_TIMESTAMPS.filterKeys { items.last().id == "r1" || it != "w-u1" },
                wasCleared = false,
                onRespond = { _, _ -> },
                onApprove = { _, _, _, _, _ -> },
                onOnboard = { _, _, _ -> },
                onPlanReviewResponse = { _, _ -> },
                onUlwResponse = { _, _, _ -> },
                modifier = Modifier.fillMaxSize(),
            )
        }

    /**
     * The whole chat screen as the design draws it — tabs, header, transcript
     * and composer together — running and finished. ChatScreen itself needs a
     * ViewModel and a live relay, so it is assembled from the same parts here.
     */
    private fun captureChatScreen(name: String, palette: Palette, items: List<ChatItem>, running: Boolean) =
        composeRule.captureThemed(name, palette) {
            Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                HomeTabs(tab = HomeTab.CHAT, homeLabel = "Dashboard", onTabChange = {})
                ChatTopBar(
                    title = "open up a work room",
                    connectionState = ConnectionState.Connected(address = AGENT_ADDRESS),
                    isConnected = true,
                    onClearChat = {},
                    onNavigateToLogs = {},
                    onMenuClick = {},
                    modelName = "gemini-3.8-flash",
                    sessionActivity = if (running) SessionActivity.RUNNING else SessionActivity.IDLE,
                )
                MessageList(
                    items = items,
                    timestamps = TURN_TIMESTAMPS.filterKeys { !running || it != "w-u1" },
                    wasCleared = false,
                    onRespond = { _, _ -> },
                    onApprove = { _, _, _, _, _ -> },
                    onOnboard = { _, _, _ -> },
                    onPlanReviewResponse = { _, _ -> },
                    onUlwResponse = { _, _, _ -> },
                    modifier = Modifier.weight(1f),
                )
                InputBar(
                    isConnected = true,
                    isAgentWorking = running,
                    approvalMode = ApprovalMode.AUTO,
                    sessionModes = SessionModes(available = ApprovalMode.entries.toList(), hostManaged = true),
                    usage = SessionUsageTotals(if (running) 337_300 else 342_300, if (running) 0.09 else 0.10, null),
                    onSend = { _, _, _ -> },
                )
            }
        }

    @Test
    fun `chat screen running - light`() = captureChatScreen("chat_screen_running", Palette.Light, RUNNING_TURN, running = true)

    @Test
    fun `chat screen running - dark`() = captureChatScreen("chat_screen_running", Palette.Dark, RUNNING_TURN, running = true)

    @Test
    fun `chat screen finished - light`() = captureChatScreen("chat_screen_finished", Palette.Light, FINISHED_TURN, running = false)

    @Test
    fun `chat screen finished - dark`() = captureChatScreen("chat_screen_finished", Palette.Dark, FINISHED_TURN, running = false)

    /**
     * A chat with no messages yet (W16AAPPLE-113): the composer as a card in
     * the middle, with the starters below it, or without them and on one line
     * while the keyboard is up.
     */
    private fun captureEmptyChat(name: String, palette: Palette, keyboardOpen: Boolean = false) =
        composeRule.captureThemed(
            name,
            palette,
            interact = if (keyboardOpen) ({ onNode(hasSetTextAction()).performClick() }) else null
        ) {
            Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                HomeTabs(tab = HomeTab.CHAT, homeLabel = "Dashboard", onTabChange = {})
                ChatTopBar(
                    title = "New chat",
                    connectionState = ConnectionState.Connected(address = AGENT_ADDRESS),
                    isConnected = true,
                    onClearChat = {},
                    onNavigateToLogs = {},
                    onMenuClick = {},
                    modelName = "gemini-3.8-flash",
                    sessionActivity = SessionActivity.IDLE,
                )
                EmptyChat(
                    agentName = "ConnectOnion",
                    keyboardOpen = keyboardOpen,
                    onStarter = {},
                    modifier = Modifier.weight(1f),
                ) {
                    InputBar(
                        isConnected = true,
                        approvalMode = ApprovalMode.AUTO,
                        onSend = { _, _, _ -> },
                        centred = true,
                    )
                }
            }
        }

    @Test
    fun `empty chat - light`() = captureEmptyChat("chat_empty", Palette.Light)

    @Test
    fun `empty chat - dark`() = captureEmptyChat("chat_empty", Palette.Dark)

    @Test
    fun `empty chat keyboard open - light`() = captureEmptyChat("chat_empty_keyboard", Palette.Light, keyboardOpen = true)

    @Test
    @Config(qualifiers = RobolectricDeviceQualifiers.MediumTablet)
    fun `empty chat tablet - light`() = captureEmptyChat("chat_empty_tablet", Palette.Light)

    @Test
    fun `turn steps collapsed - light`() = captureTurnSteps("chat_turn_collapsed", Palette.Light, FINISHED_TURN)

    @Test
    fun `turn steps collapsed - dark`() = captureTurnSteps("chat_turn_collapsed", Palette.Dark, FINISHED_TURN)

    @Test
    fun `turn steps expanded - light`() =
        captureTurnSteps("chat_turn_expanded", Palette.Light, FINISHED_TURN, open = true)

    @Test
    fun `turn steps expanded - dark`() =
        captureTurnSteps("chat_turn_expanded", Palette.Dark, FINISHED_TURN, open = true)

    @Test
    fun `turn steps running - light`() = captureTurnSteps("chat_turn_running", Palette.Light, RUNNING_TURN)

    @Test
    fun `turn steps running - dark`() = captureTurnSteps("chat_turn_running", Palette.Dark, RUNNING_TURN)

    @Test
    fun `message list - light`() = captureMessageList(Palette.Light)

    @Test
    fun `message list - dark`() = captureMessageList(Palette.Dark)

    /** All three states stacked, so one image covers enabled/offline/working chrome. */
    private fun captureInputBar(palette: Palette) =
        composeRule.captureThemed("chat_input_bar", palette) {
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.lg)) {
                InputBar(isConnected = true, onSend = { _, _, _ -> })
                InputBar(isConnected = false, onSend = { _, _, _ -> })
                InputBar(
                    isConnected = true,
                    isAgentWorking = true,
                    onSend = { _, _, _ -> },
                )
                // Mid-dictation: the timer/level row sits above a field that
                // stays visible, which is the whole point of streaming partials.
                InputBar(
                    isConnected = true,
                    voiceInput = VoiceInputState(phase = VoiceInputPhase.LISTENING),
                    onSend = { _, _, _ -> },
                )
            }
        }

    /**
     * Dictating into a run that is still going: the waveform offering the
     * finish, the mic slot inert beside a Stop that is not, and no second
     * square glyph anywhere. Its own capture so the whole composer is in frame.
     */
    private fun captureInputBarDictatingMidRun(palette: Palette) =
        composeRule.captureThemed("chat_input_bar_dictating_mid_run", palette) {
            InputBar(
                isConnected = true,
                isAgentWorking = true,
                voiceInput = VoiceInputState(phase = VoiceInputPhase.LISTENING),
                onSend = { _, _, _ -> },
            )
        }

    /**
     * The one dictation state with a notice on it. Its own capture because the
     * notice is the only thing it proves, and it has to be visibly captioning
     * the recording row rather than wedged between that row and the pill.
     */
    private fun captureInputBarFallbackNotice(palette: Palette) =
        composeRule.captureThemed("chat_input_bar_fallback_notice", palette) {
            InputBar(
                isConnected = true,
                voiceInput = VoiceInputState(
                    phase = VoiceInputPhase.LISTENING,
                    notice = "Using server transcription.",
                ),
                onSend = { _, _, _ -> },
            )
        }

    /**
     * The other closed-microphone state: tapped, but nothing is capturing yet.
     * It has to be visibly not-a-recording — same quiet ring as transcribing,
     * no timer and no meter — because the whole bug was a row that looked live
     * over a microphone that was not.
     */
    private fun captureInputBarPreparing(palette: Palette) =
        composeRule.captureThemed(
            "chat_input_bar_preparing",
            palette,
            // The row holds this state back until the wait is worth mentioning.
            interact = { mainClock.advanceTimeBy(1_000) },
        ) {
            InputBar(
                isConnected = true,
                voiceInput = VoiceInputState(phase = VoiceInputPhase.PREPARING),
                onSend = { _, _, _ -> },
            )
        }

    @Test
    fun `input bar preparing - light`() = captureInputBarPreparing(Palette.Light)

    @Test
    fun `input bar preparing - dark`() = captureInputBarPreparing(Palette.Dark)

    /**
     * The dictation row's resting state: mic shut, waiting on the transcript.
     * Its ring drops to the same quiet 1dp the unfocused pill wears, so this is
     * where the two boxes have to read as one family rather than two.
     */
    private fun captureInputBarTranscribing(palette: Palette) =
        composeRule.captureThemed("chat_input_bar_transcribing", palette) {
            InputBar(
                isConnected = true,
                voiceInput = VoiceInputState(phase = VoiceInputPhase.TRANSCRIBING),
                onSend = { _, _, _ -> },
            )
        }

    @Test
    fun `input bar transcribing - light`() = captureInputBarTranscribing(Palette.Light)

    @Test
    fun `input bar transcribing - dark`() = captureInputBarTranscribing(Palette.Dark)

    @Test
    fun `input bar fallback notice - light`() = captureInputBarFallbackNotice(Palette.Light)

    @Test
    fun `input bar fallback notice - dark`() = captureInputBarFallbackNotice(Palette.Dark)

    /**
     * The shelf at its widest: the longest mode chip, the labelled Stop chip and
     * a six-figure token count with a near-full context ring, all in one row.
     * This is the case that proves they do not collide. Its own capture because
     * the state gallery above already fills the screen, and a clipped case
     * proves nothing.
     */
    private fun captureInputBarWidestShelf(palette: Palette) =
        composeRule.captureThemed("chat_input_bar_widest_shelf", palette) {
            InputBar(
                isConnected = true,
                isAgentWorking = true,
                approvalMode = ApprovalMode.ULW,
                usage = SessionUsageTotals(
                    totalTokens = 128_000,
                    totalCostUsd = 1.42,
                    latestContextPercent = 92.0
                ),
                onSend = { _, _, _ -> },
            )
        }

    /**
     * The `/` palette open on three of the four published skills, each with
     * the description under its name — including one paragraph-long enough to
     * hit the two-line cap, and one with no description at all. Captured on a
     * focused field, which is the only state it ever appears in.
     */
    private fun captureSlashPalette(palette: Palette) =
        composeRule.captureThemed("chat_slash_palette", palette, interact = {
            onNode(hasSetTextAction()).performTextInput("/li")
        }) {
            InputBar(
                isConnected = true,
                skills = listOf(
                    AgentSkill(
                        "linkedin-engagement",
                        "Sequentially engage normal LinkedIn feed posts using verified browser " +
                            "workflows. Skips promoted or already-reacted posts, comments from " +
                            "context, then likes each post it engaged with."
                    ),
                    AgentSkill("linkedin-post", "Write ready-to-paste LinkedIn post text"),
                    AgentSkill("list-files"),
                    AgentSkill("pdf", "Read, split, and merge PDF files")
                ),
                onSend = { _, _, _ -> },
            )
        }

    @Test
    fun `slash palette - light`() = captureSlashPalette(Palette.Light)

    @Test
    fun `slash palette - dark`() = captureSlashPalette(Palette.Dark)

    @Test
    fun `input bar - light`() = captureInputBar(Palette.Light)

    @Test
    fun `input bar - dark`() = captureInputBar(Palette.Dark)

    @Test
    fun `input bar widest shelf - light`() = captureInputBarWidestShelf(Palette.Light)

    @Test
    fun `input bar widest shelf - dark`() = captureInputBarWidestShelf(Palette.Dark)

    @Test
    fun `input bar dictating mid run - light`() = captureInputBarDictatingMidRun(Palette.Light)

    @Test
    fun `input bar dictating mid run - dark`() = captureInputBarDictatingMidRun(Palette.Dark)

    /**
     * The diff_preview card, expanded so the baseline exercises the +/-
     * line coloring, not just the collapsed header row.
     */
    private fun captureDiffPreviewCard(palette: Palette) =
        composeRule.captureThemed("chat_diff_preview_card", palette, interact = {
            onNodeWithContentDescription("Expand").performClick()
            waitForIdle()
        }) {
            DiffPreviewCard(
                ChatItem.DiffPreviewItem(
                    id = "diff-1",
                    path = "src/main/java/ai/openonion/oochat/util/StringFormat.kt",
                    preview = "--- a/StringFormat.kt\n+++ b/StringFormat.kt\n@@ -1,3 +1,4 @@\n" +
                        " package ai.openonion.oochat.util\n" +
                        "-fun truncateMiddle(s: String) = s\n" +
                        "+fun truncateMiddle(s: String, prefix: Int, suffix: Int) = s",
                    truncated = true,
                    fileExists = true,
                ),
            )
        }

    @Test
    fun `diff preview card - light`() = captureDiffPreviewCard(Palette.Light)

    @Test
    fun `diff preview card - dark`() = captureDiffPreviewCard(Palette.Dark)

    /**
     * The bug this exists to catch: an [ApprovalCard] used to show only the
     * tool name ("edit") with no way to tell what was actually about to run,
     * and its post-decision receipt collapsed to "Allowed: edit — running…"
     * with no record of what was approved. Both states stacked in one
     * baseline — pending (with the buttons) and already-decided (the
     * read-only [ai.openonion.oochat.ui.chat.components.ApprovalConfirmationBar]
     * receipt) — so a regression that drops the args line from either one
     * fails here.
     */
    private fun captureApprovalCardWithArgs(palette: Palette) =
        composeRule.captureThemed("chat_approval_card_with_args", palette) {
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.lg)) {
                ApprovalCard(
                    item = ChatItem.ApprovalNeeded(
                        id = "ap-1",
                        tool = "edit",
                        arguments = mapOf(
                            "file_path" to "src/main/java/ai/openonion/oochat/util/StringFormat.kt",
                            "old_string" to "fun truncateMiddle(s: String) = s",
                            "new_string" to "fun truncateMiddle(s: String, prefix: Int, suffix: Int) = s",
                        ),
                    ),
                    onApprove = { _, _, _, _ -> },
                )
                ApprovalCard(
                    item = ChatItem.ApprovalNeeded(
                        id = "ap-2",
                        tool = "edit",
                        arguments = mapOf("file_path" to "src/main/java/ai/openonion/oochat/util/StringFormat.kt"),
                        decision = ApprovalDecision(approved = true, scope = "once", mode = null),
                    ),
                    onApprove = { _, _, _, _ -> },
                )
            }
        }

    @Test
    fun `approval card with args - light`() = captureApprovalCardWithArgs(Palette.Light)

    @Test
    fun `approval card with args - dark`() = captureApprovalCardWithArgs(Palette.Dark)

    /** The `mode_changed` inline status marker — both a with-source and a
     * no-source (plan_mode.py's exit path omits `triggered_by`) case. */
    private fun captureModeChanged(palette: Palette) =
        composeRule.captureThemed("chat_mode_changed_divider", palette) {
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md)) {
                ModeChangedDivider(ChatItem.ModeChangedItem(id = "mode-1", mode = "plan", triggeredBy = "agent"))
                ModeChangedDivider(ChatItem.ModeChangedItem(id = "mode-2", mode = "safe"))
            }
        }

    @Test
    fun `mode changed divider - light`() = captureModeChanged(Palette.Light)

    @Test
    fun `mode changed divider - dark`() = captureModeChanged(Palette.Dark)

    /**
     * The session-wide usage summary in isolation, so the ring's thresholds
     * are readable without the rest of the input bar around them. Two states
     * stacked: a mid-window reading (primary arc) and a near-full one (>=80%,
     * error). Its real home is the row below the field — see the last case in
     * `chat_input_bar`.
     */
    private fun captureSessionUsageBar(palette: Palette) =
        composeRule.captureThemed("chat_session_usage_bar", palette) {
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.lg)) {
                SessionUsageBar(
                    usage = SessionUsageTotals(totalTokens = 1234, totalCostUsd = 0.02, latestContextPercent = 45.0)
                )
                SessionUsageBar(
                    usage = SessionUsageTotals(totalTokens = 128_000, totalCostUsd = 1.42, latestContextPercent = 92.0)
                )
            }
        }

    /**
     * The ring at the three readings that matter, beside the label it has to
     * sit on the baseline of. 8% is the one this case exists for: with the
     * number gone, a nearly-empty gauge is where the design most risks reading
     * as "no data" rather than "barely used". 92% is the only warning left
     * before a compact, so the arc's `error` tint is load-bearing here.
     */
    private fun captureCtxRing(palette: Palette) =
        composeRule.captureThemed("chat_ctx_ring", palette) {
            Column(
                modifier = Modifier.padding(MaterialTheme.spacing.lg),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md),
            ) {
                listOf(8, 45, 92).forEach { percent ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
                    ) {
                        Text(
                            text = "128.0k tokens · $1.42",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.alignByBaseline(),
                        )
                        CtxRing(percent = percent, modifier = Modifier.alignByBaseline())
                    }
                }
            }
        }

    @Test
    fun `ctx ring - light`() = captureCtxRing(Palette.Light)

    @Test
    fun `ctx ring - dark`() = captureCtxRing(Palette.Dark)

    /**
     * The per-turn footer in all three states. The done line is the one under
     * design review: a stroked tick in the outline colour and the numbers,
     * with no "Done", no model name and no context ring. Failed is deliberately
     * the loud one — error colour, a word, and a model to name.
     */
    private fun captureTurnFooter(palette: Palette) =
        composeRule.captureThemed("chat_turn_footer", palette) {
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md)) {
                TurnThinkingFooter(THINKING_ORDINARY)
                TurnThinkingFooter(THINKING_RUNNING)
                TurnThinkingFooter(THINKING_FAILED)
            }
        }

    @Test
    fun `turn footer - light`() = captureTurnFooter(Palette.Light)

    @Test
    fun `turn footer - dark`() = captureTurnFooter(Palette.Dark)

    /**
     * The input bar's mode chip in all four states. The three base modes must
     * differ only by icon and label — same fill, same weight — while ULW is
     * the one state that takes color, so a diff that leaks hue into a base
     * mode (or drains it from ULW) fails here. The last row is the pending
     * (unconfirmed) rendering — see [ApprovalModeChip]'s own doc.
     */
    private fun captureApprovalModeChips(palette: Palette) =
        composeRule.captureThemed("chat_approval_mode_chips", palette) {
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)) {
                ApprovalMode.entries.forEach { mode ->
                    ApprovalModeChip(mode = mode, enabled = true, onCycle = {}, onOpenSheet = {})
                }
                ApprovalModeChip(
                    mode = ApprovalMode.SAFE,
                    enabled = false,
                    onCycle = {},
                    onOpenSheet = {},
                )
                ApprovalModeChip(
                    mode = ApprovalMode.SAFE,
                    enabled = true,
                    pending = true,
                    onCycle = {},
                    onOpenSheet = {},
                )
            }
        }

    @Test
    fun `session usage bar - light`() = captureSessionUsageBar(Palette.Light)

    @Test
    fun `session usage bar - dark`() = captureSessionUsageBar(Palette.Dark)

    @Test
    fun `approval mode chips - light`() = captureApprovalModeChips(Palette.Light)

    @Test
    fun `approval mode chips - dark`() = captureApprovalModeChips(Palette.Dark)

    /** The sheet behind the chip's tune button — base-mode rows plus the ULW danger block. */
    private fun captureApprovalModeSheet(palette: Palette) =
        composeRule.captureThemed("chat_approval_mode_sheet", palette) {
            ApprovalModeSheet(current = ApprovalMode.PLAN, onSelect = { _, _ -> }, onDismiss = {})
        }

    @Test
    fun `approval mode sheet - light`() = captureApprovalModeSheet(Palette.Light)

    @Test
    fun `approval mode sheet - dark`() = captureApprovalModeSheet(Palette.Dark)

    private companion object {
        const val AGENT_ADDRESS = "0x4f3a9c2b8e1d7a6f5c4b3a2918d7e6f5c4b3a291"

        /**
         * Fixed instants in a past year, so the day divider always formats as
         * an absolute date ("January 15, 2024") instead of drifting between
         * "Today", "Yesterday" and a date as the calendar moves underneath a
         * recorded baseline.
         */
        const val DAY_ONE = 1_705_312_800_000L // 2024-01-15 10:00 UTC
        const val DAY_TWO = 1_705_399_500_000L // 2024-01-16 10:05 UTC

        val CONVERSATION = listOf(
            ChatItem.User(id = "u1", content = "Summarise the release notes for 2.3."),
            ChatItem.Agent(
                id = "a1",
                content = "Sure — pulling them now. **Three** changes stand out:\n\n" +
                    "1. The relay reconnects on its own\n" +
                    "2. Voice notes transcribe on-device\n" +
                    "3. Attachments cap at four per message",
            ),
            ChatItem.ToolCall(
                id = "t1",
                name = "bash",
                args = mapOf("command" to "git log --oneline v2.2..v2.3"),
                status = ToolStatus.DONE,
                result = "a1b2c3d feat(relay): reconnect without a user tap\n" +
                    "d4e5f6a feat(voice): transcribe on device",
                timingMs = 412L,
            ),
            ChatItem.User(id = "u2", content = "Thanks — anything breaking?"),
        )

        private fun bash(id: String, command: String, timingMs: Long?, status: ToolStatus = ToolStatus.DONE) =
            ChatItem.ToolCall(id = id, name = "bash", args = mapOf("command" to command), status = status, timingMs = timingMs)

        /** The turn the design draws: a thought, a search, a guide lookup and four commands, one failed. */
        val TURN_STEPS = listOf(
            ChatItem.User(id = "w-u1", content = "open up a work room"),
            ChatItem.Thinking(
                id = "w-t1",
                status = ThinkingStatus.DONE,
                content = "understood, I'll open up a work room for you",
                kind = "intent",
                durationMs = 2000.0,
            ),
            ChatItem.ToolCall(id = "w-c1", name = "glob", args = mapOf("pattern" to "*"), timingMs = 2000L, status = ToolStatus.DONE),
            ChatItem.ToolCall(id = "w-c2", name = "load_guide", args = mapOf("path" to "index"), timingMs = 3000L, status = ToolStatus.DONE),
            bash("w-c3", "echo \$PWD; ls -la ~/.config; ls -la ~/.connectonion", 3000L, ToolStatus.ERROR),
            bash("w-c4", "pwd", 2000L),
            bash("w-c5", "ls -la .connectonion", 4000L),
            bash("w-c6", "which co; python3 -c \"import connectonion; print(connectonion.__file__)\"", null),
        )

        val FINISHED_TURN = TURN_STEPS + ChatItem.Turn(
            id = "r1",
            thinking = ChatItem.Thinking(id = "r1", status = ThinkingStatus.DONE, tokensTotal = 25_300, costUsd = 0.015),
            agent = ChatItem.Agent(
                id = "r1",
                content = "Your work room is open. It is running in ~/.connectonion and is ready for its first task.",
            ),
        )

        val RUNNING_TURN = TURN_STEPS.take(5) + bash("w-c4", "pwd", null, ToolStatus.RUNNING) + ChatItem.Turn(
            id = "w-l1",
            thinking = ChatItem.Thinking(id = "w-l1", status = ThinkingStatus.RUNNING),
        )

        val TURN_TIMESTAMPS = mapOf(
            "w-u1" to DAY_ONE,
            "r1" to DAY_ONE + 16_000L,
        )

        val THINKING_ORDINARY = ChatItem.Thinking(
            id = "th-1",
            status = ThinkingStatus.DONE,
            model = "gemini-2.5-flash",
            durationMs = 5000.0,
            tokensTotal = 46,
        )

        val THINKING_RUNNING = THINKING_ORDINARY.copy(
            id = "th-2",
            status = ThinkingStatus.RUNNING,
            durationMs = null,
            tokensTotal = null,
        )

        val THINKING_FAILED = THINKING_ORDINARY.copy(id = "th-3", status = ThinkingStatus.ERROR)

        val TIMESTAMPS = mapOf(
            "u1" to DAY_ONE,
            "a1" to DAY_ONE + 30_000L,
            "u2" to DAY_TWO,
        )

        /** One message that never left the device, one still waiting for the socket. */
        val SEND_STATES = listOf(
            ChatItem.User(id = "s1", content = "What is the weather in Sydney?"),
            ChatItem.Agent(id = "s2", content = "19 degrees and clear."),
            ChatItem.User(
                id = "s3",
                content = "And tomorrow?",
                state = UserMessageState.FAILED,
            ),
            ChatItem.User(
                id = "s4",
                content = "The weekend too, when you get a moment.",
                state = UserMessageState.QUEUED,
            ),
        )

        val SEND_STATE_TIMESTAMPS = mapOf(
            "s1" to DAY_ONE,
            "s2" to DAY_ONE + 20_000L,
            "s3" to DAY_ONE + 40_000L,
            "s4" to DAY_ONE + 60_000L,
        )
    }
}
