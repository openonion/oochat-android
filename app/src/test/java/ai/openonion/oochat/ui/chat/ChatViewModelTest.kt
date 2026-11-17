package ai.openonion.oochat.ui.chat

import ai.openonion.oochat.data.local.AppSettings
import ai.openonion.oochat.data.local.ConnectionConfig
import ai.openonion.oochat.data.local.IgnoredIdsManager
import ai.openonion.oochat.data.local.IgnoredIdsStorage
import ai.openonion.oochat.data.local.ImageAttachmentStore
import ai.openonion.oochat.data.local.RecordedVoice
import ai.openonion.oochat.data.local.RecordingResult
import ai.openonion.oochat.data.local.StoredImage
import ai.openonion.oochat.data.local.VoiceRecorderStore
import ai.openonion.oochat.data.local.mapper.TranscriptItemCodec
import ai.openonion.oochat.data.local.toConnectTarget
import ai.openonion.oochat.data.repository.AccountClockException
import ai.openonion.oochat.data.repository.AgentRepository
import ai.openonion.oochat.data.repository.ConnectionConfigRepository
import ai.openonion.oochat.data.repository.MessageRepository
import ai.openonion.oochat.data.repository.SessionRepository
import ai.openonion.oochat.domain.model.AgentProfile
import ai.openonion.oochat.domain.model.AgentStatus
import ai.openonion.oochat.domain.model.ApprovalBoundary
import ai.openonion.oochat.domain.model.ApprovalMode
import ai.openonion.oochat.domain.model.ChatEvent
import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.domain.model.ChatMessage
import ai.openonion.oochat.domain.model.ChatSession
import ai.openonion.oochat.domain.model.ConnectionState
import ai.openonion.oochat.domain.model.ExecOutcome
import ai.openonion.oochat.domain.model.ExecRequest
import ai.openonion.oochat.domain.model.PendingApproval
import ai.openonion.oochat.domain.model.PlanEntry
import ai.openonion.oochat.domain.model.PlanPriority
import ai.openonion.oochat.domain.model.PlanReviewReply
import ai.openonion.oochat.domain.model.PlanStatus
import ai.openonion.oochat.domain.model.ProviderApprovalPresentation
import ai.openonion.oochat.domain.model.ProviderInputAck
import ai.openonion.oochat.domain.model.ProviderInvocationStatus
import ai.openonion.oochat.domain.model.Role
import ai.openonion.oochat.domain.model.ServerSessionStatus
import ai.openonion.oochat.domain.model.ServerTranscriptEntry
import ai.openonion.oochat.domain.model.SessionActivity
import ai.openonion.oochat.domain.model.SessionModes
import ai.openonion.oochat.domain.model.ThinkingStatus
import ai.openonion.oochat.domain.model.ToolStatus
import ai.openonion.oochat.domain.model.UserMessageState
import ai.openonion.oochat.domain.model.WorkRoomApproval
import ai.openonion.oochat.domain.model.isInProgress
import ai.openonion.oochat.domain.model.stableAssistantId
import ai.openonion.oochat.domain.usecase.ConnectToAgentUseCaseContract
import ai.openonion.oochat.domain.usecase.ConversationHistoryUseCase
import ai.openonion.oochat.domain.usecase.RecentChatsSync
import ai.openonion.oochat.domain.usecase.RecentChatsSyncResult
import ai.openonion.oochat.domain.usecase.ServerErrorText
import ai.openonion.oochat.domain.usecase.StaleSessionDetector
import ai.openonion.oochat.network.AgentConnection
import ai.openonion.oochat.network.AgentDiscoveryService
import ai.openonion.oochat.network.OFFLINE_GRACE_MS
import ai.openonion.oochat.network.RecognizerReadiness
import ai.openonion.oochat.network.SpeechRecognitionEvent
import ai.openonion.oochat.network.SpeechRecognitionService
import ai.openonion.oochat.network.VoiceTranscriptionService
import ai.openonion.oochat.ui.chat.components.VoiceInputPhase
import ai.openonion.oochat.ui.chat.components.failedUserMessages
import ai.openonion.oochat.ui.controlcenter.ControlCenterActionRejected
import ai.openonion.oochat.ui.controlcenter.ControlCenterAgentStatus
import ai.openonion.oochat.ui.controlcenter.ControlCenterCancelled
import ai.openonion.oochat.ui.controlcenter.ControlCenterConnection
import ai.openonion.oochat.ui.controlcenter.ControlCenterConversation
import ai.openonion.oochat.ui.controlcenter.ControlCenterSkill
import ai.openonion.oochat.ui.controlcenter.controlCenterSkillMessage
import ai.openonion.oochat.ui.workroom.WorkRoomDraft
import ai.openonion.oochat.ui.workroom.WorkRoomText
import ai.openonion.oochat.util.SensitiveText
import ai.openonion.oochat.util.stringField
import android.app.Application
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Unit tests for [ChatViewModel].
 *
 * ChatViewModel extends [androidx.lifecycle.AndroidViewModel], which
 * requires an [Application]. We satisfy that with the no-arg constructor
 * (`Application()`) — Android stub methods are never called because every
 * dependency is swapped for an in-memory fake, so the stub-throws-default
 * behaviour cannot fire on this code path.
 *
 * Tests focus on the ViewModel's documented contract:
 *  - `sendMessage` / `clearChat` / `respond*` forward correctly to the use case
 *  - per-agent `ignoredIds` semantics survive a clear
 *  - `ChatItemReceived` / `ChatItemUpdated` / `OutputReceived` shape the UI list
 *    and respect the ignore set
 *  - errors surface through `uiState.error` and are cleared on demand
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    // ── Test fakes ─────────────────────────────────────────────────────

    private class FakeConnectToAgentUseCase : ConnectToAgentUseCaseContract {
        private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
        override val connectionState: StateFlow<ConnectionState> = _connectionState

        val _agentProfile = MutableStateFlow<ai.openonion.oochat.domain.model.AgentLiveProfile?>(null)
        override val agentProfile: StateFlow<ai.openonion.oochat.domain.model.AgentLiveProfile?> = _agentProfile

        // Mirrors ConnectionRepositoryImpl: setMode writes optimistically, an
        // inbound mode_changed overwrites. Tests drive the second half with
        // emitModeChanged().
        val _approvalMode = MutableStateFlow(ai.openonion.oochat.domain.model.ApprovalMode.DEFAULT)
        override val approvalMode: StateFlow<ai.openonion.oochat.domain.model.ApprovalMode> = _approvalMode

        val _modePending = MutableStateFlow(false)
        override val modePending: StateFlow<Boolean> = _modePending

        val _sessionModes = MutableStateFlow(SessionModes.LEGACY)
        override val sessionModes: StateFlow<SessionModes> = _sessionModes

        val _hostSessionStatus = MutableStateFlow(ServerSessionStatus.UNKNOWN)
        override val hostSessionStatus: StateFlow<ServerSessionStatus> = _hostSessionStatus

        val setModeCalls = mutableListOf<Pair<ai.openonion.oochat.domain.model.ApprovalMode, Int?>>()
        override suspend fun setMode(mode: ai.openonion.oochat.domain.model.ApprovalMode, turns: Int?) {
            setModeCalls += mode to turns
            _approvalMode.value = mode
            _modePending.value = true
        }

        val _dashboardHtml = MutableStateFlow<String?>(null)
        override val dashboardHtml: StateFlow<String?> = _dashboardHtml

        val _plan = MutableStateFlow<List<PlanEntry>>(emptyList())
        override val plan: StateFlow<List<PlanEntry>> = _plan

        private val _events = MutableSharedFlow<ChatEvent>(
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )

        var isConnectedResult: Boolean = false
        override fun isConnected(): Boolean = isConnectedResult

        var connectResult: Boolean = true
        var connectInvocations = 0
        var lastConnectAddress: String? = null
        var lastConnectDirectUrl: String? = null
        var lastConnectRelayUrl: String? = null
        var lastConnectConversationId: String? = null
        val switchedConversations = mutableListOf<String?>()
        /**
         * The conversation the socket is actually CONNECTed as — the thing
         * the old fakes had no notion of, so "we sent an INPUT on someone
         * else's session" was unrepresentable and therefore untestable.
         */
        var connectedConversationId: String? = null

        /** Ordered record of what was asked of the connection, for ordering assertions. */
        val interactions = mutableListOf<String>()

        override suspend fun connect(agentAddress: String, conversationId: String?, directUrl: String?, relayUrl: String?): Boolean {
            connectInvocations++
            lastConnectAddress = agentAddress
            lastConnectConversationId = conversationId
            lastConnectDirectUrl = directUrl
            lastConnectRelayUrl = relayUrl
            if (connectResult) connectedConversationId = conversationId
            interactions += "connect:$conversationId"
            return connectResult
        }
        /** Mirrors the repository: the session in play is the conversation we connected on. */
        override fun liveSessionIdFor(agentAddress: String): String? = connectedConversationId

        /** What the ViewModel considered active at each switchConversation call. */
        val activeAtSwitch = mutableListOf<String?>()
        var activeSessionIdAtSwitch: (() -> String?)? = null
        override suspend fun switchConversation(conversationId: String?) {
            // Mirrors ConnectionRepositoryImpl: switching to the conversation
            // the connection is already on is a no-op, so a recorded switch
            // always means a real socket round trip.
            if (conversationId == connectedConversationId) return
            switchedConversations += conversationId
            activeAtSwitch += activeSessionIdAtSwitch?.invoke()
            connectedConversationId = conversationId
            interactions += "switch:$conversationId"
        }

        data class SentMessage(
            val content: String,
            val agentAddress: String,
            val images: List<String>?,
            val files: List<ai.openonion.oochat.domain.model.OutgoingFileAttachment>? = null
        )
        var sentMessages = mutableListOf<SentMessage>()
        /** Set to make the next send throw, the way a refused socket write does. */
        var failNextSend = false
        /** Set to hold sends open until it completes, the way a slow socket write would. */
        var sendGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        override suspend fun sendMessage(content: String, agentAddress: String, images: List<String>?, files: List<ai.openonion.oochat.domain.model.OutgoingFileAttachment>?) {
            sendGate?.await()
            interactions += "send:$content"
            if (failNextSend) {
                failNextSend = false
                throw java.io.IOException("socket refused the write")
            }
            sentMessages += SentMessage(content, agentAddress, images, files)
            onSend?.invoke()
        }

        val executeToolCalls = mutableListOf<ExecRequest>()
        var executeToolHandler: suspend (ExecRequest) -> ExecOutcome = {
            ExecOutcome.NotSent("No result configured")
        }

        override suspend fun executeTool(request: ExecRequest): ExecOutcome {
            executeToolCalls += request
            return executeToolHandler(request)
        }

        /** Runs while a send is still in flight — lets a test have the host answer before it returns. */
        var onSend: (() -> Unit)? = null

        var lastRespond: String? = null
        var lastRespondRequestId: String? = null
        var respondCount = 0
        override suspend fun respond(answer: String, requestId: String?) {
            lastRespond = answer
            lastRespondRequestId = requestId
            respondCount++
        }

        var interruptInvocations = 0
        override suspend fun interrupt() { interruptInvocations++ }

        val providerPermissionChanges = mutableListOf<ai.openonion.oochat.domain.model.ProviderPermissionChange>()
        override suspend fun sendProviderPermissionChange(
            change: ai.openonion.oochat.domain.model.ProviderPermissionChange
        ): Boolean {
            providerPermissionChanges += change
            return true
        }

        val providerStops = mutableListOf<ai.openonion.oochat.domain.model.ProviderStop>()
        override suspend fun sendProviderStop(stop: ai.openonion.oochat.domain.model.ProviderStop): Boolean {
            providerStops += stop
            return true
        }

        val providerInputs = mutableListOf<ai.openonion.oochat.domain.model.ProviderInput>()
        override suspend fun sendProviderInput(input: ai.openonion.oochat.domain.model.ProviderInput): Boolean {
            providerInputs += input
            return true
        }

        var lastApproval: Boolean? = null
        var lastApprovalScope: String? = null
        var lastApprovalMode: String? = null
        var lastApprovalFeedback: String? = null
        override suspend fun respondToApproval(approved: Boolean, scope: String, mode: String?, feedback: String?, requestId: String?) {
            lastApproval = approved
            lastApprovalScope = scope
            lastApprovalMode = mode
            lastApprovalFeedback = feedback
        }

        var lastOnboardMethod: String? = null
        var lastOnboardInviteCode: String? = null
        var lastOnboardPayment: Double? = null
        override suspend fun respondToOnboard(method: String, inviteCode: String?, payment: Double?) {
            lastOnboardMethod = method
            lastOnboardInviteCode = inviteCode
            lastOnboardPayment = payment
        }

        var lastPlanReviewMessage: String? = null
        override suspend fun respondToPlanReview(message: String) { lastPlanReviewMessage = message }

        var lastUlwAction: String? = null
        var lastUlwTurns: Int? = null
        var lastUlwMode: String? = null
        override suspend fun respondToUlwTurnsReached(action: String, turns: Int?, mode: String?) {
            lastUlwAction = action
            lastUlwTurns = turns
            lastUlwMode = mode
        }

        var disconnectCount = 0
    var retryNowCalls = 0
    override fun retryNow() { retryNowCalls++ }

        var querySessionStatusCalls = 0
        override suspend fun querySessionStatus() { querySessionStatusCalls++ }

        /** Mirrors the repository: what the Host said is gone once a screen has acted on it. */
        var releasedStatuses = 0
        override fun releaseSessionStatus() {
            releasedStatuses++
            val state = _connectionState.value
            if (state is ConnectionState.Connected) {
                _connectionState.value = state.copy(status = ServerSessionStatus.UNKNOWN, serverNewer = false)
            }
        }

        val disconnectKeptOutbox = mutableListOf<Boolean>()
        override suspend fun disconnect(keepOutbox: Boolean) {
            disconnectCount++
            disconnectKeptOutbox += keepOutbox
        }

        var resetCount = 0
        /** When set, reset() waits on it: lets a test act while Clear chat is still under way. */
        var resetGate: CompletableDeferred<Unit>? = null
        /** The conversation each Clear chat named. */
        val resetConversations = mutableListOf<String?>()
        override suspend fun reset(conversationId: String?) {
            resetCount++
            resetConversations += conversationId
            resetGate?.await()
        }

        /** What the outbox still holds per conversation; absent means empty. */
        val queuedCounts = mutableMapOf<String, Int>()
        /** When set, the outbox cannot be asked. */
        var queuedCountUnknown = false
        /** The agent each count was asked for. */
        val queuedCountAgents = mutableListOf<String>()
        override suspend fun queuedMessageCount(agentAddress: String, conversationId: String): Int? {
            queuedCountAgents += agentAddress
            return if (queuedCountUnknown) null else queuedCounts[conversationId] ?: 0
        }


        // When true, the NEXT collected event throws CancellationException
        // instead of being delivered — simulates the ViewModel's scope
        // being cancelled (e.g. cleared) while the collector is suspended
        // mid-collect, for the "cancellation must propagate, not become a
        // uiState.error" regression test below.
        var throwCancellationOnNextEvent = false
        override fun observeEvents(): Flow<ChatEvent> = _events.map { event ->
            if (throwCancellationOnNextEvent) throw kotlinx.coroutines.CancellationException("simulated scope cancellation")
            event
        }

        // ── Test helpers ───────────────────────────────────────────────

        fun emit(event: ChatEvent) {
            // tryEmit is sufficient here: extraBufferCapacity is large and
            // the test dispatcher runs the collector immediately.
            _events.tryEmit(event)
        }

        fun markConnected(address: String = "0xAGENT", status: ServerSessionStatus = ServerSessionStatus.UNKNOWN) {
            _connectionState.value = ConnectionState.Connected(address = address, status = status)
            isConnectedResult = true
        }

        fun forceState(state: ConnectionState) {
            _connectionState.value = state
        }
    }

    private class FakeIgnoredIdsStorage : IgnoredIdsStorage {
        private var backing: MutableMap<String, MutableSet<String>> = mutableMapOf()
        var saveCount = 0

        override suspend fun loadAll(): Map<String, Set<String>> = backing
        override suspend fun saveForAgent(agentAddress: String, ids: Set<String>) {
            backing[agentAddress] = ids.toMutableSet()
            saveCount++
        }
    }

    /** Stands in for `ConnectivityManager`, so these tests can pull the radio. */
    private class FakeNetworkMonitor : ai.openonion.oochat.network.NetworkMonitor {
        val online = MutableStateFlow(true)
        override val isOnline: Flow<Boolean> = online
    }

    private class FakeRecentChatsSync : RecentChatsSync {
        data class Call(val agentId: String, val agentAddress: String, val directUrl: String?, val relayUrl: String? = null)
        val calls = mutableListOf<Call>()
        var identityActivations = 0
        var activationFailure: Throwable? = null
        var onActivate: () -> Unit = {}
        var result: RecentChatsSyncResult = RecentChatsSyncResult.Synced

        override suspend fun activateIdentity() {
            identityActivations++
            onActivate()
            activationFailure?.let { throw it }
        }

        override suspend fun sync(
            agentId: String,
            agentAddress: String,
            directUrl: String?,
            relayUrl: String?
        ): RecentChatsSyncResult {
            calls += Call(agentId, agentAddress, directUrl, relayUrl)
            return result
        }
    }

    private class FakeAppSettings : AppSettings {
        private val renderMarkdownFlow = MutableStateFlow(true)
        private val fontSizeIndexFlow = MutableStateFlow(1)
        private val hapticFeedbackFlow = MutableStateFlow(true)
        private val soundEffectsFlow = MutableStateFlow(false)
        private val streamingResponsesFlow = MutableStateFlow(true)
        private val memoryEnabledFlow = MutableStateFlow(true)
        private val customInstructionsFlow = MutableStateFlow("")
        private val pushNotificationsEnabledFlow = MutableStateFlow(false)
        private val notificationSoundFlow = MutableStateFlow("Chime")
        private val analyticsEnabledFlow = MutableStateFlow(false)
        private val safeModeEnabledFlow = MutableStateFlow(true)

        override val renderMarkdown: Flow<Boolean> = renderMarkdownFlow
        override suspend fun setRenderMarkdown(value: Boolean) { renderMarkdownFlow.value = value }
        override val fontSizeIndex: Flow<Int> = fontSizeIndexFlow
        override suspend fun setFontSizeIndex(value: Int) { fontSizeIndexFlow.value = value }
        override val hapticFeedback: Flow<Boolean> = hapticFeedbackFlow
        override suspend fun setHapticFeedback(value: Boolean) { hapticFeedbackFlow.value = value }
        override val soundEffects: Flow<Boolean> = soundEffectsFlow
        override suspend fun setSoundEffects(value: Boolean) { soundEffectsFlow.value = value }
        override val streamingResponses: Flow<Boolean> = streamingResponsesFlow
        override suspend fun setStreamingResponses(value: Boolean) { streamingResponsesFlow.value = value }
        override val memoryEnabled: Flow<Boolean> = memoryEnabledFlow
        override suspend fun setMemoryEnabled(value: Boolean) { memoryEnabledFlow.value = value }
        override val customInstructions: Flow<String> = customInstructionsFlow
        override suspend fun setCustomInstructions(value: String) { customInstructionsFlow.value = value }
        override val pushNotificationsEnabled: Flow<Boolean> = pushNotificationsEnabledFlow
        override suspend fun setPushNotificationsEnabled(value: Boolean) { pushNotificationsEnabledFlow.value = value }
        // True (already offered) unless a test of the offer says otherwise, as the interface's default.
        val approvalOfferShownFlow = MutableStateFlow(true)
        override val approvalNotificationOfferShown: Flow<Boolean> = approvalOfferShownFlow
        override suspend fun setApprovalNotificationOfferShown(value: Boolean) { approvalOfferShownFlow.value = value }
        override val notificationSound: Flow<String> = notificationSoundFlow
        override suspend fun setNotificationSound(value: String) { notificationSoundFlow.value = value }
        override val analyticsEnabled: Flow<Boolean> = analyticsEnabledFlow
        override suspend fun setAnalyticsEnabled(value: Boolean) { analyticsEnabledFlow.value = value }
        override val safeModeEnabled: Flow<Boolean> = safeModeEnabledFlow
        override suspend fun setSafeModeEnabled(value: Boolean) { safeModeEnabledFlow.value = value }

        private val developerToolsEnabledFlow = MutableStateFlow(false)
        override val developerToolsEnabled: Flow<Boolean> = developerToolsEnabledFlow
        override suspend fun setDeveloperToolsEnabled(value: Boolean) { developerToolsEnabledFlow.value = value }
    }

    /** Records every call instead of touching NotificationManager — see the "maybeNotifyAgentReply gating" tests below. */
    private class FakeAgentReplyNotifier : ai.openonion.oochat.data.local.AgentReplyNotifier {
        val posted = mutableListOf<Pair<String?, String>>()
        var clearCalls = 0
        override fun notifyAgentReply(agentName: String?, preview: String) { posted += agentName to preview }
        override fun clearReplyNotifications() { clearCalls++ }

        val approvals = mutableListOf<PendingApproval>()
        val clearedApprovals = mutableListOf<String>()
        override fun notifyApprovalNeeded(approval: PendingApproval) { approvals += approval }
        override fun clearApprovalNotification(approvalId: String) { clearedApprovals += approvalId }
    }

    private class FakeImageAttachmentStore : ImageAttachmentStore {
        var storedUris = mutableListOf<String>()
        var shouldFail = false
        var failingUris = emptySet<String>()
        /** When set, store() suspends here first — lets a test interleave work while a "storage" call is still in flight. */
        var blockUntil: CompletableDeferred<Unit>? = null
        override suspend fun store(uriString: String): StoredImage? {
            blockUntil?.await()
            storedUris += uriString
            if (shouldFail || uriString in failingUris) return null
            return StoredImage(localPath = "file:///fake/$uriString.jpg", dataUrl = "data:image/jpeg;base64,FAKE-$uriString")
        }
    }

    private class FakeFileAttachmentStore : ai.openonion.oochat.data.local.FileAttachmentStore {
        var storedUris = mutableListOf<String>()
        /** URIs in this set resolve to [ai.openonion.oochat.data.local.FileAttachResult.TooLarge]. */
        var tooLargeUris = emptySet<String>()
        /** URIs in this set resolve to [ai.openonion.oochat.data.local.FileAttachResult.Failed]. */
        var failingUris = emptySet<String>()
        override suspend fun store(uriString: String): ai.openonion.oochat.data.local.FileAttachResult {
            storedUris += uriString
            return when (uriString) {
                in tooLargeUris -> ai.openonion.oochat.data.local.FileAttachResult.TooLarge("fake-$uriString", 20_000_000L)
                in failingUris -> ai.openonion.oochat.data.local.FileAttachResult.Failed("fake-$uriString")
                else -> ai.openonion.oochat.data.local.FileAttachResult.Success(
                    attachment = ai.openonion.oochat.domain.model.OutgoingFileAttachment(
                        name = "fake-$uriString",
                        data = "data:application/octet-stream;base64,FAKE-$uriString"
                    ),
                    localPath = "file:///fake/$uriString"
                )
            }
        }
    }

    private class FakeVoiceRecorderStore : VoiceRecorderStore {
        var startCalls = 0
        var stopCalls = 0
        var cancelCalls = 0
        /** Runs inside startRecording(), so a test can see the state the mic opened in. */
        var onStartRecording: () -> Unit = {}
        /** Set by a test to control what the next stopRecording() returns. */
        var nextRecording: RecordingResult = RecordingResult.Recorded(
            RecordedVoice(
                localPath = "file:///fake/voice.m4a",
                durationSeconds = 3f,
                file = File.createTempFile("fake-voice", ".m4a")
            )
        )
        /** Set by a test to make the recorder fail to open, as a busy or denied mic does. */
        var startSucceeds = true
        override suspend fun startRecording(): Boolean {
            startCalls++
            onStartRecording()
            return startSucceeds
        }
        override suspend fun stopRecording(): RecordingResult {
            stopCalls++
            return nextRecording
        }
        override suspend fun cancelRecording() {
            cancelCalls++
        }
    }

    /**
     * Drives dictation by hand: [reportedReadiness] picks the recognizer vs the
     * server-transcription path, and [emit] plays events at the collector.
     * [recordProbeResult] caches like the real one, so a test can watch a
     * second dictation skip the probe.
     */
    private class FakeSpeechRecognitionService : SpeechRecognitionService {
        var reportedReadiness = RecognizerReadiness.READY
        var stopCalls = 0
        var listenCalls = 0
        var recordedVerdicts = mutableListOf<Boolean>()
        /** Set to hold readiness() open — the real one takes up to 1.5s on API 33+. */
        var readinessGate: CompletableDeferred<Unit>? = null
        private val events = MutableSharedFlow<SpeechRecognitionEvent>(extraBufferCapacity = 16)
        override suspend fun readiness(): RecognizerReadiness {
            readinessGate?.await()
            return reportedReadiness
        }
        override fun recordProbeResult(usable: Boolean) {
            recordedVerdicts += usable
            reportedReadiness = if (usable) RecognizerReadiness.READY else RecognizerReadiness.UNAVAILABLE
        }
        override fun listen(): Flow<SpeechRecognitionEvent> {
            listenCalls++
            return events
        }
        override suspend fun stop() { stopCalls++ }
        fun emit(event: SpeechRecognitionEvent) { check(events.tryEmit(event)) }
    }

    private class FakeVoiceTranscriptionService : VoiceTranscriptionService {
        /** Set by a test: null simulates a failed/empty transcription. */
        var nextTranscript: String? = "hello from voice"
        /** What a failed transcription fails with, when [nextTranscript] is null. */
        var nextFailure: Exception = Exception("transcription failed")
        var transcribeCalls = 0
        override suspend fun transcribe(audioFile: File): Result<String> {
            transcribeCalls++
            return nextTranscript?.let { Result.success(it) } ?: Result.failure(nextFailure)
        }
    }

    private class FakeAgentRepository : AgentRepository {
        // Backed by a real StateFlow (not just the createdAgents list) so
        // getAllAgents()/getAgentByAddress() actually reflect what's been
        // created — needed for ChatViewModel.drawerAgents' reactive combine.
        private val agentsFlow = MutableStateFlow<List<AgentProfile>>(emptyList())
        var createdAgents = mutableListOf<AgentProfile>()
        override fun getAllAgents(): Flow<List<AgentProfile>> = agentsFlow
        override fun getActiveAgents(): Flow<List<AgentProfile>> = agentsFlow.map { it.filter { a -> a.isActive } }
        override suspend fun getAgentById(id: String) = agentsFlow.value.find { it.id == id }
        override suspend fun getAgentByAddress(address: String) = agentsFlow.value.find { it.address == address }
        override suspend fun createAgent(agent: AgentProfile): AgentProfile {
            createdAgents += agent
            agentsFlow.value = agentsFlow.value + agent
            return agent
        }
        override suspend fun updateAgent(agent: AgentProfile) {
            agentsFlow.value = agentsFlow.value.map { if (it.id == agent.id) agent else it }
        }
        override suspend fun deleteAgent(agentId: String) {
            agentsFlow.value = agentsFlow.value.filterNot { it.id == agentId }
        }
        override suspend fun updateLastConnected(agentId: String) {}
        override suspend fun getDefaultAgent() = agentsFlow.value.firstOrNull { it.isActive }
        override suspend fun reorderAgents(orderedIds: List<String>) {
            val byId = agentsFlow.value.associateBy { it.id }
            agentsFlow.value = orderedIds.mapIndexedNotNull { index, id -> byId[id]?.copy(position = index) }
        }

        /** Pre-existing agent, as if saved by an earlier run of the app. */
        fun seed(agent: AgentProfile) { agentsFlow.value = agentsFlow.value + agent }
    }

    private class FakeConfigRepository : ConnectionConfigRepository {
        var configValue: ConnectionConfig? = null
        override suspend fun getConfig() = configValue
        override fun observeConfig() = MutableStateFlow(configValue)
        override suspend fun saveConfig(config: ConnectionConfig) { configValue = config }
        override suspend fun deleteConfig() { configValue = null }
        override suspend fun hasConfig() = configValue != null
        override suspend fun updateLastConnected(timestamp: Long) {}
        override suspend fun updateAgentAddress(address: String?) {}
    }

    /** In-memory [SessionRepository], reached once a Connected state has run
     *  saveAgentIfNeeded() and ConversationHistoryUseCase.ensureActiveSession()
     *  resolves the agent via [FakeAgentRepository.getAgentByAddress]. */
    private class FakeSessionRepository : SessionRepository {
        private val all = MutableStateFlow<List<ChatSession>>(emptyList())
        private var counter = 0

        override fun getAllSessions(): Flow<List<ChatSession>> = all
        override fun getSessionsByAgent(agentId: String): Flow<List<ChatSession>> =
            all.map { list -> list.filter { it.agentId == agentId }.sortedByDescending { it.updatedAt } }
        override suspend fun getSessionById(id: String) = all.value.find { it.id == id }
        override suspend fun createSession(agentId: String, title: String, id: String): ChatSession {
            val session = ChatSession(
                id = id, agentId = agentId, title = title,
                createdAt = 0L, updatedAt = 0L
            )
            all.value = all.value + session
            return session
        }
        override suspend fun deleteSession(sessionId: String) {
            all.value = all.value.filterNot { it.id == sessionId }
        }
        override suspend fun renameSession(sessionId: String, newTitle: String) {
            all.value = all.value.map { if (it.id == sessionId) it.copy(title = newTitle) else it }
        }
        override suspend fun updateMessageInfo(sessionId: String, count: Int, preview: String?) {
            all.value = all.value.map { if (it.id == sessionId) it.copy(messageCount = count, lastMessagePreview = preview) else it }
        }

        /** Pre-existing conversation, as if saved by an earlier run of the app. */
        fun seed(agentId: String, title: String, messageCount: Int = 0): ChatSession {
            val session = ChatSession(
                id = "session-${++counter}", agentId = agentId, title = title,
                createdAt = 0L, updatedAt = 0L, messageCount = messageCount
            )
            all.value = all.value + session
            return session
        }
    }

    /** In-memory [MessageRepository], mirroring Room's insert-or-replace-by-id semantics. */
    private class FakeMessageRepository : MessageRepository {
        private val all = MutableStateFlow<List<ChatMessage>>(emptyList())

        override suspend fun getMessagesListBySession(sessionId: String) =
            all.value.filter { it.sessionId == sessionId }.sortedBy { it.timestamp }
        override suspend fun createMessage(message: ChatMessage) {
            all.value = all.value.filterNot { it.id == message.id } + message
        }
        override suspend fun deleteMessagesBySession(sessionId: String) {
            all.value = all.value.filterNot { it.sessionId == sessionId }
        }
        override suspend fun getMessageCount(sessionId: String) =
            all.value.count { it.sessionId == sessionId }
        override suspend fun existsById(id: String) =
            all.value.any { it.id == id }
        override suspend fun updateTimestamp(id: String, timestamp: Long) {
            all.value = all.value.map { if (it.id == id) it.copy(timestamp = timestamp) else it }
        }
        override suspend fun updateFailedToSend(id: String, failedToSend: Boolean, inOutbox: Boolean) {
            all.value = all.value.map { if (it.id == id) it.copy(failedToSend = failedToSend, inOutbox = inOutbox) else it }
        }
        override suspend fun getOwningSessionId(id: String) =
            all.value.firstOrNull { it.id == id }?.sessionId
        override suspend fun getSessionIdByUserContent(content: String) =
            all.value.lastOrNull { it.role == Role.USER && it.content == content }?.sessionId

        /** Pre-existing rows, as if written by an earlier run of the app. */
        fun seed(vararg messages: ChatMessage) { all.value = all.value + messages }

        fun snapshot(): List<ChatMessage> = all.value
    }

    // ── Setup ──────────────────────────────────────────────────────────

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var vm: ChatViewModel
    private lateinit var connectUseCase: FakeConnectToAgentUseCase
    private lateinit var storage: FakeIgnoredIdsStorage
    private lateinit var agentRepo: FakeAgentRepository
    private lateinit var configRepo: FakeConfigRepository
    private lateinit var sessionRepo: FakeSessionRepository
    private lateinit var messageRepo: FakeMessageRepository
    private lateinit var imageStore: FakeImageAttachmentStore
    private lateinit var fileStore: FakeFileAttachmentStore
    private lateinit var voiceRecorderStore: FakeVoiceRecorderStore
    private lateinit var voiceTranscriptionService: FakeVoiceTranscriptionService
    private lateinit var speechRecognition: FakeSpeechRecognitionService
    private lateinit var appSettings: FakeAppSettings
    private lateinit var networkMonitor: FakeNetworkMonitor
    private lateinit var recentChatsSync: FakeRecentChatsSync
    private lateinit var agentReplyNotifier: FakeAgentReplyNotifier

    // Backing vars for the two gates ChatViewModel reads through lambdas
    // rather than fakeable objects — see maybeNotifyAgentReply's doc. Tests
    // flip these before emitting the event they want gated.
    private var appInForeground = false
    private var notificationPermissionGranted = true

    @Before
    fun setUp() {
        // viewModelScope.launch {} uses Dispatchers.Main; pin it to our
        // test dispatcher so init's collectors run deterministically.
        kotlinx.coroutines.Dispatchers.setMain(testDispatcher)

        connectUseCase = FakeConnectToAgentUseCase()
        storage = FakeIgnoredIdsStorage()
        agentRepo = FakeAgentRepository()
        configRepo = FakeConfigRepository()
        sessionRepo = FakeSessionRepository()
        messageRepo = FakeMessageRepository()
        imageStore = FakeImageAttachmentStore()
        fileStore = FakeFileAttachmentStore()
        voiceRecorderStore = FakeVoiceRecorderStore()
        voiceTranscriptionService = FakeVoiceTranscriptionService()
        speechRecognition = FakeSpeechRecognitionService()
        appSettings = FakeAppSettings()
        networkMonitor = FakeNetworkMonitor()
        recentChatsSync = FakeRecentChatsSync()
        agentReplyNotifier = FakeAgentReplyNotifier()
        appInForeground = false
        notificationPermissionGranted = true

        vm = ChatViewModel(
            application = Application(),
            agentDiscovery = AgentDiscoveryService(),
            connectUseCase = connectUseCase,
            agentRepository = agentRepo,
            configRepository = configRepo,
            ignoredIdsManager = IgnoredIdsManager(storage),
            imageAttachmentStore = imageStore,
            fileAttachmentStore = fileStore,
            voiceRecorderStore = voiceRecorderStore,
            voiceTranscriptionService = voiceTranscriptionService,
            speechRecognitionService = speechRecognition,
            appSettings = appSettings,
            messageRepository = messageRepo,
            conversationHistory = ConversationHistoryUseCase(
                agentRepo, sessionRepo, messageRepo,
                FakePersistenceTransaction(messageRepo, sessionRepo)
            ),
            recentChatsSync = recentChatsSync,
            initialMyAddress = "0xTEST",
            networkMonitor = networkMonitor,
            agentReplyNotifier = agentReplyNotifier,
            requiresNotificationPermission = true,
            hasNotificationPermission = { notificationPermissionGranted },
            isAppInForeground = { appInForeground },
            controlCenterDispatcher = testDispatcher,
        )
    }

    @After
    fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    // ── Construction smoke test ────────────────────────────────────────

    @Test
    fun `constructs without throwing and seeds myAddress from constructor param`() {
        assertEquals(
            "0xTEST",
            vm.uiState.value.myAddress
        )
    }

    @Test
    fun `construction never auto-connects on its own`() {
        // Connecting is always caller-driven now (ChatScreen's
        // connectToAgentId-keyed LaunchedEffect calls connectToAgent or
        // connectToAgentByConfig explicitly) — see connectToAgentByConfig's
        // doc for the real race this replaced (an init-time auto-connect
        // that fought an explicit connectToAgent call from a fresh route).
        assertEquals(0, connectUseCase.connectInvocations)
    }

    @Test
    fun `initial uiState has empty chatItems and no error`() {
        val state = vm.uiState.value
        assertTrue(state.chatItems.isEmpty())
        assertNull(state.error)
    }

    @Test
    fun `executeTool shows pending item and then success result`() = runTest(testDispatcher) {
        vm.connectToAgent(validHexAddress())

        val result = CompletableDeferred<ExecOutcome>()
        connectUseCase.executeToolHandler = { result.await() }

        vm.executeTool(
            tool = "read_file",
            argsJson = """{"path":"README.md"}"""
        )

        val pending = vm.execItems.value.single()
        assertTrue(pending.isRunning)
        assertEquals("read_file", pending.tool)
        assertEquals(1, connectUseCase.executeToolCalls.size)
        assertEquals("read_file", connectUseCase.executeToolCalls.single().tool)

        result.complete(ExecOutcome.Success("file contents", 42.0))
        testScheduler.advanceUntilIdle()

        val completed = vm.execItems.value.single()
        assertFalse(completed.isRunning)
        assertEquals(ExecOutcome.Success("file contents", 42.0), completed.outcome)
    }

    @Test
    fun `executeTool displays agent error result`() = runTest(testDispatcher) {
        vm.connectToAgent(validHexAddress())
        connectUseCase.executeToolHandler = {
            ExecOutcome.Error("Permission denied", 18.0)
        }

        vm.executeTool(
            tool = "delete_file",
            argsJson = """{"path":"protected.txt"}"""
        )
        testScheduler.advanceUntilIdle()

        assertEquals(
            ExecOutcome.Error("Permission denied", 18.0),
            vm.execItems.value.single().outcome
        )
    }

    @Test
    fun `dismissing an EXEC card removes only that card`() = runTest(testDispatcher) {
        vm.connectToAgent(validHexAddress())
        vm.executeTool("read_file", "{}")
        vm.executeTool("list_files", "{}")

        val dismissedId = vm.execItems.value.first().execId
        vm.dismissExec(dismissedId)

        assertEquals(1, vm.execItems.value.size)
        assertEquals("list_files", vm.execItems.value.single().tool)
    }

    @Test
    fun `starting a new conversation clears EXEC cards`() = runTest(testDispatcher) {
        vm.connectToAgent(validHexAddress())
        vm.executeTool("read_file", "{}")
        assertEquals(1, vm.execItems.value.size)

        vm.startNewSession()
        testScheduler.runCurrent()

        assertTrue(vm.execItems.value.isEmpty())
    }

    // ── sendMessage ────────────────────────────────────────────────────

    @Test
    fun `retrying a failed turn re-sends without a second copy of the user message`() {
        vm.connectToAgent(validHexAddress())
        testSchedulerKeepRunning()
        connectUseCase.markConnected()

        vm.sendMessage("hello")
        testSchedulerKeepRunning()
        val userBubbles = vm.uiState.value.chatItems.count { it is ChatItem.User }
        val sent = connectUseCase.sentMessages.size

        vm.retryFailedTurn(vm.uiState.value.chatItems.filterIsInstance<ChatItem.User>().last().id)
        testSchedulerKeepRunning()

        assertEquals(
            "Retry must re-issue the request",
            sent + 1,
            connectUseCase.sentMessages.size
        )
        assertEquals(
            "The user's message is already in the transcript; Retry must not echo it again",
            userBubbles,
            vm.uiState.value.chatItems.count { it is ChatItem.User }
        )
    }

    /**
     * The device losing its network is knowable in milliseconds; the socket
     * only finds out when a ping goes unanswered, 15-30s later. These cover
     * the ViewModel's half of that — the grace period itself belongs to
     * [ai.openonion.oochat.network.NetworkMonitorTest].
     */
    @Test
    fun `an outage shorter than the grace period never reaches the banner`() = runTest(testDispatcher) {
        val collected = backgroundScope.launch { vm.isOffline.collect { } }
        testScheduler.runCurrent()

        networkMonitor.online.value = false
        testScheduler.advanceTimeBy(OFFLINE_GRACE_MS)

        assertFalse(vm.isOffline.value)
        collected.cancel()
    }

    @Test
    fun `an outage that outlasts the grace period surfaces, and clears on recovery`() = runTest(testDispatcher) {
        val collected = backgroundScope.launch { vm.isOffline.collect { } }
        testScheduler.runCurrent()

        networkMonitor.online.value = false
        testScheduler.advanceTimeBy(OFFLINE_GRACE_MS + 1)
        assertTrue(vm.isOffline.value)

        networkMonitor.online.value = true
        testScheduler.runCurrent()
        assertFalse(vm.isOffline.value)

        collected.cancel()
    }

    @Test
    fun `the network coming back retries a pending reconnect instead of waiting out the backoff`() = runTest(testDispatcher) {
        // Mirrors onReturnedToForeground below, and the reference web client's
        // `online` listener: the reason the socket failed has just gone away.
        assertEquals(0, connectUseCase.retryNowCalls)

        networkMonitor.online.value = false
        testScheduler.runCurrent()
        assertEquals(0, connectUseCase.retryNowCalls)

        networkMonitor.online.value = true
        testScheduler.runCurrent()

        assertEquals(1, connectUseCase.retryNowCalls)
    }

    @Test
    fun `returning to the foreground retries a pending reconnect immediately`() {
        // Android aborts a backgrounded app's sockets, so the drop after a trip
        // to the camera is routine — and its cause is gone the moment we are
        // back. Sitting out a backoff computed for a flaky network wastes the
        // one moment the user is watching.
        assertEquals(0, connectUseCase.retryNowCalls)

        vm.onReturnedToForeground()

        assertEquals(1, connectUseCase.retryNowCalls)
    }

    @Test
    fun `sendMessage with blank content is a no-op`() {
        vm.connectToAgent(validHexAddress())
        testSchedulerKeepRunning()
        connectUseCase.markConnected()

        vm.sendMessage("")
        vm.sendMessage("   ")

        val state = vm.uiState.value
        assertTrue("Blank sendMessage must not append a User bubble", state.chatItems.isEmpty())
        assertTrue("Blank sendMessage must not call the use case", connectUseCase.sentMessages.isEmpty())
    }

    @Test
    fun `sendMessage with no target agent is a no-op and does not call use case`() {
        // No connectToAgent() → _targetAgentAddress stays blank.
        vm.sendMessage("hello")
        assertTrue(vm.uiState.value.chatItems.isEmpty())
        assertTrue(connectUseCase.sentMessages.isEmpty())
    }

    @Test
    fun `sendMessage while not connected keeps the message and marks it queued`() {
        vm.connectToAgent(validHexAddress())
        testSchedulerKeepRunning()
        // isConnected is still false (we never called markConnected).

        vm.sendMessage("hello")

        val state = vm.uiState.value
        // Refusing here is what made the persisted outbox almost unreachable:
        // the connection layer is what enqueues, and the old guard returned
        // before the message ever reached it. The user was told "not
        // connected" and the message was dropped.
        val bubble = state.chatItems.single() as ChatItem.User
        assertEquals("hello", bubble.content)
        assertEquals(
            "an undelivered message has to say so, not vanish",
            UserMessageState.QUEUED,
            bubble.state
        )
        assertNull("a queued message is not an error the user has to dismiss", state.error)
        assertEquals(
            "it still goes through the connection layer, which is what queues it",
            listOf("hello"),
            connectUseCase.sentMessages.map { it.content }
        )
    }

    @Test
    fun `a send that fails leaves the message on screen, marked, and resendable`() = runTest(testDispatcher) {
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)
        connectUseCase.failNextSend = true

        vm.sendMessage("did this go?")
        testScheduler.runCurrent()

        // Nothing on this protocol reports a failed delivery, so if the bubble
        // does not say it, nothing does.
        val failed = vm.uiState.value.chatItems.single() as ChatItem.User
        assertEquals(UserMessageState.FAILED, failed.state)
        assertTrue("a failed send reaches no one", connectUseCase.sentMessages.isEmpty())

        vm.resendMessage(failed.id)
        testScheduler.advanceUntilIdle()

        assertEquals(
            listOf("did this go?"),
            connectUseCase.sentMessages.map { it.content }
        )
    }

    @Test
    fun `resending revives the same bubble instead of adding a second one`() = runTest(testDispatcher) {
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)
        connectUseCase.failNextSend = true
        vm.sendMessage("only once")
        testScheduler.runCurrent()
        val id = (vm.uiState.value.chatItems.single() as ChatItem.User).id

        vm.resendMessage(id)
        testScheduler.advanceUntilIdle()

        // Retry was wired straight to sendMessage once, and every tap appended
        // a second copy of the same message. Reusing the id is what prevents
        // that, and is also the only stable handle on an outgoing message —
        // the wire's input_id is regenerated per attempt.
        val bubbles = vm.uiState.value.chatItems.filterIsInstance<ChatItem.User>()
        assertEquals("one message, one bubble, however many attempts", 1, bubbles.size)
        assertEquals(id, bubbles.single().id)
        assertEquals(UserMessageState.SENT, bubbles.single().state)
    }

    @Test
    fun `resending moves the message to the bottom, on screen and in what a reload reads`() = runTest(testDispatcher) {
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)
        connectUseCase.failNextSend = true
        vm.sendMessage("lost one")
        testScheduler.runCurrent()
        val lostId = (vm.uiState.value.chatItems.single() as ChatItem.User).id
        vm.sendMessage("later one")
        testScheduler.advanceUntilIdle()
        val laterId = vm.uiState.value.chatItems.filterIsInstance<ChatItem.User>().single { it.content == "later one" }.id

        vm.resendMessage(lostId)

        // Moved at once, while still waiting to go: the reply lands at the
        // bottom, so the question it answers has to be there too.
        val users = vm.uiState.value.chatItems.filterIsInstance<ChatItem.User>()
        assertEquals(listOf(laterId, lostId), users.map { it.id })
        assertEquals("moved, not copied", 1, users.count { it.id == lostId })
        assertEquals(lostId, vm.uiState.value.chatItems.last().id)

        testScheduler.advanceUntilIdle()

        val saved = messageRepo.snapshot()
        assertTrue(
            "the saved row follows, or a reload puts the question back above the old reply",
            saved.single { it.id == lostId }.timestamp > saved.single { it.id == laterId }.timestamp
        )
        assertEquals(UserMessageState.SENT, (vm.uiState.value.chatItems.last() as ChatItem.User).state)
    }

    @Test
    fun `a failed send is saved as failed, and a resend that goes through clears it`() = runTest(testDispatcher) {
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)
        connectUseCase.failNextSend = true
        vm.sendMessage("did this go?")
        testScheduler.advanceUntilIdle()
        val id = (vm.uiState.value.chatItems.single() as ChatItem.User).id

        // On screen alone, a restart would bring it back looking sent.
        assertTrue(messageRepo.snapshot().single { it.id == id }.failedToSend)

        vm.resendMessage(id)
        testScheduler.advanceUntilIdle()

        assertFalse(messageRepo.snapshot().single { it.id == id }.failedToSend)
    }

    @Test
    fun `resending a message that did not fail is a no-op`() = runTest(testDispatcher) {
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)
        vm.sendMessage("fine")
        testScheduler.runCurrent()
        val id = (vm.uiState.value.chatItems.single() as ChatItem.User).id

        vm.resendMessage(id)
        testScheduler.advanceUntilIdle()

        assertEquals(
            "a delivered message must not be sendable twice by a stray tap",
            1,
            connectUseCase.sentMessages.size
        )
    }

    @Test
    fun `sendMessage when connected appends a User bubble and forwards to use case`() = runTest(testDispatcher) {
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)

        vm.sendMessage("hello world")

        val items = vm.uiState.value.chatItems
        assertEquals(1, items.size)
        val user = items[0] as ChatItem.User
        assertEquals("hello world", user.content)
        assertEquals(
            "sendMessage must forward to the use case",
            listOf(FakeConnectToAgentUseCase.SentMessage("hello world", address, null)),
            connectUseCase.sentMessages
        )
    }

    @Test
    fun `sendMessage prepends custom instructions to the wire payload only, never to the bubble or persisted content`() =
        runTest(testDispatcher) {
            val address = validHexAddress()
            appSettings.setCustomInstructions("Always answer in pirate speak.")
            vm.connectToAgent(address)
            testScheduler.runCurrent()
            connectUseCase.markConnected(address)

            vm.sendMessage("hello world")

            val user = vm.uiState.value.chatItems.single() as ChatItem.User
            assertEquals("the visible bubble must show only what the user typed", "hello world", user.content)
            assertEquals(
                "the instructions must be prepended only on the way to the agent",
                "Always answer in pirate speak.\n\nhello world",
                connectUseCase.sentMessages.single().content
            )
        }

    @Test
    fun `sendMessage with images-only content stores, shows a local bubble, and forwards base64 to the use case`() =
        runTest(testDispatcher) {
            val address = validHexAddress()
            vm.connectToAgent(address)
            testScheduler.runCurrent()
            connectUseCase.markConnected(address)

            vm.sendMessage("", images = listOf("content://picker/1", "content://picker/2"))

            val items = vm.uiState.value.chatItems
            assertEquals(1, items.size)
            val user = items[0] as ChatItem.User
            assertEquals("", user.content)
            assertEquals(
                "the bubble must show the store's durable local path, not the raw picker URI",
                listOf("file:///fake/content://picker/1.jpg", "file:///fake/content://picker/2.jpg"),
                user.images
            )
            assertEquals(listOf("content://picker/1", "content://picker/2"), imageStore.storedUris)
            assertEquals(
                "an images-only (blank content) message must still reach the use case",
                listOf(
                    FakeConnectToAgentUseCase.SentMessage(
                        "",
                        address,
                        listOf("data:image/jpeg;base64,FAKE-content://picker/1", "data:image/jpeg;base64,FAKE-content://picker/2")
                    )
                ),
                connectUseCase.sentMessages
            )
        }

    @Test
    fun `sendMessage drops the bubble and surfaces an error when every attached image fails to store`() =
        runTest(testDispatcher) {
            val address = validHexAddress()
            vm.connectToAgent(address)
            testScheduler.runCurrent()
            connectUseCase.markConnected(address)
            imageStore.shouldFail = true

            vm.sendMessage("", images = listOf("content://picker/1"))

            assertTrue(
                "no blank ghost bubble must survive when every image failed to store",
                vm.uiState.value.chatItems.isEmpty()
            )
            assertNotNull("the user must be told the attachment failed", vm.uiState.value.error)
            assertTrue(
                "nothing meaningful to send when content is blank and every image failed",
                connectUseCase.sentMessages.isEmpty()
            )
        }

    @Test
    fun `sendMessage surfaces a non-blocking error when only some attached images fail to store`() =
        runTest(testDispatcher) {
            val address = validHexAddress()
            vm.connectToAgent(address)
            testScheduler.runCurrent()
            connectUseCase.markConnected(address)
            imageStore.failingUris = setOf("content://picker/bad")

            vm.sendMessage("caption", images = listOf("content://picker/good", "content://picker/bad"))

            val items = vm.uiState.value.chatItems
            assertEquals(1, items.size)
            val user = items[0] as ChatItem.User
            assertEquals(
                "the surviving image must still be shown/sent, just not the failed one",
                listOf("file:///fake/content://picker/good.jpg"),
                user.images
            )
            assertNotNull("a partial failure must still surface something to the user", vm.uiState.value.error)
            assertEquals(
                "the message must still send with whatever images survived",
                1,
                connectUseCase.sentMessages.single().images?.size
            )
        }

    @Test
    fun `sendMessage pins the target session so a mid-flight session switch doesn't misfile the persisted message`() =
        runTest(testDispatcher) {
            val address = validHexAddress()
            vm.connectToAgent(address)
            testScheduler.runCurrent()
            connectUseCase.markConnected(address)
            testScheduler.runCurrent()
            // Lazy session creation: nothing has been sent yet, so there's no
            // real session at this point — activeSessionId is still null.
            assertNull(vm.activeSessionId.value)

            // Block image storage mid-flight so a session switch can land
            // while sendMessage's coroutine is still suspended on it.
            val gate = CompletableDeferred<Unit>()
            imageStore.blockUntil = gate

            vm.sendMessage("", images = listOf("content://picker/1"))
            testScheduler.runCurrent()
            // sendMessage's coroutine has started and resolved (materialized)
            // its target session by now, before the image-store gate, even
            // though it's still suspended waiting on that gate.
            val firstSessionId = vm.activeSessionId.value!!

            vm.startNewSession()
            testScheduler.runCurrent()
            // Nothing has been sent in the new session, so — same lazy
            // behavior — it stays un-materialized; activeSessionId going
            // back to null is itself proof it no longer points at
            // firstSessionId.
            assertNull(vm.activeSessionId.value)

            gate.complete(Unit)
            testScheduler.runCurrent()

            assertEquals(
                "the message must land in the session it was actually sent from",
                1,
                messageRepo.getMessagesListBySession(firstSessionId).size
            )
        }

    // ── Voice input ────────────────────────────────────────────────────
    //
    // Voice fills the composer and never sends: there is no ChatItem, no
    // audio in the transcript, and nothing reaches the agent until the user
    // presses Send on text they have read.

    @Test
    fun `dictation takes the recognizer when one is available, not the recorder`() =
        runTest(testDispatcher) {
            speechRecognition.reportedReadiness = RecognizerReadiness.READY

            vm.startVoiceRecording()
            testScheduler.runCurrent()
            speechRecognition.emit(SpeechRecognitionEvent.Ready)
            testScheduler.runCurrent()

            assertEquals(0, voiceRecorderStore.startCalls)
            assertEquals(VoiceInputPhase.LISTENING, vm.voiceInput.value.phase)
        }

    @Test
    fun `dictation falls back to recording plus server transcription with no recognizer`() =
        runTest(testDispatcher) {
            speechRecognition.reportedReadiness = RecognizerReadiness.UNAVAILABLE

            vm.startVoiceRecording()
            testScheduler.runCurrent()

            assertEquals(1, voiceRecorderStore.startCalls)
        }

    // Tapping the mic used to declare LISTENING on the spot, with the timer
    // running and the meter drawing over a microphone nothing had opened yet.
    // Everything said in that window was lost — up to 2.5s of it on a device
    // whose recognizer binds and never answers.

    @Test
    fun `the mic tap does not claim to be listening until the recognizer answers`() =
        runTest(testDispatcher) {
            speechRecognition.reportedReadiness = RecognizerReadiness.READY

            vm.startVoiceRecording()
            testScheduler.runCurrent()

            assertEquals(
                "no callback has arrived, so no microphone is open",
                VoiceInputPhase.PREPARING,
                vm.voiceInput.value.phase
            )

            speechRecognition.emit(SpeechRecognitionEvent.Ready)
            testScheduler.runCurrent()

            assertEquals(VoiceInputPhase.LISTENING, vm.voiceInput.value.phase)
        }

    @Test
    fun `the fallback path is preparing right up until the recorder is open`() =
        runTest(testDispatcher) {
            speechRecognition.reportedReadiness = RecognizerReadiness.UNAVAILABLE
            var phaseWhenMicOpened: VoiceInputPhase? = null
            voiceRecorderStore.onStartRecording = { phaseWhenMicOpened = vm.voiceInput.value.phase }

            vm.startVoiceRecording()
            testScheduler.runCurrent()

            assertEquals(
                "the recorder itself is what ends the wait, so it cannot already be over",
                VoiceInputPhase.PREPARING,
                phaseWhenMicOpened
            )
            assertEquals(VoiceInputPhase.LISTENING, vm.voiceInput.value.phase)
        }

    @Test
    fun `the whole probe window is preparing, and the fallback still announces itself`() =
        runTest(testDispatcher) {
            speechRecognition.reportedReadiness = RecognizerReadiness.UNPROVEN

            vm.startVoiceRecording()
            testScheduler.runCurrent()
            assertEquals(VoiceInputPhase.PREPARING, vm.voiceInput.value.phase)

            testScheduler.advanceTimeBy(RECOGNIZER_PROBE_TIMEOUT_MS - 1)
            testScheduler.runCurrent()
            assertEquals(
                "still nothing capturing audio a moment before the watchdog fires",
                VoiceInputPhase.PREPARING,
                vm.voiceInput.value.phase
            )
            assertNull("nothing to announce until a path is actually taken", vm.voiceInput.value.notice)

            testScheduler.advanceTimeBy(2)
            testScheduler.runCurrent()

            assertEquals(VoiceInputPhase.LISTENING, vm.voiceInput.value.phase)
            assertEquals(1, voiceRecorderStore.startCalls)
            assertEquals("Using server transcription.", vm.voiceInput.value.notice)
        }

    @Test
    fun `cancelling while preparing leaves no watchdog and no recorder behind`() =
        runTest(testDispatcher) {
            speechRecognition.reportedReadiness = RecognizerReadiness.UNPROVEN
            vm.startVoiceRecording()
            testScheduler.runCurrent()
            assertEquals(VoiceInputPhase.PREPARING, vm.voiceInput.value.phase)

            vm.cancelVoiceRecording()
            testScheduler.runCurrent()
            assertEquals(VoiceInputPhase.IDLE, vm.voiceInput.value.phase)

            testScheduler.advanceTimeBy(RECOGNIZER_PROBE_TIMEOUT_MS * 2)
            testScheduler.runCurrent()

            assertEquals(
                "the watchdog died with the job; nothing may open a mic behind the user",
                0,
                voiceRecorderStore.startCalls
            )
            assertEquals(VoiceInputPhase.IDLE, vm.voiceInput.value.phase)
        }

    @Test
    fun `cancelling before a path is even chosen releases whatever the last one left`() =
        runTest(testDispatcher) {
            // The readiness query itself is part of the preparing window, and on
            // API 33+ it can hold the tap for over a second on its own.
            speechRecognition.reportedReadiness = RecognizerReadiness.UNAVAILABLE
            val gate = CompletableDeferred<Unit>()
            speechRecognition.readinessGate = gate

            vm.startVoiceRecording()
            testScheduler.runCurrent()
            assertEquals(VoiceInputPhase.PREPARING, vm.voiceInput.value.phase)

            vm.cancelVoiceRecording()
            gate.complete(Unit)
            testScheduler.runCurrent()

            assertEquals("the recorder is released even though no path had been picked", 1, voiceRecorderStore.cancelCalls)
            assertEquals("and the abandoned query never opens one", 0, voiceRecorderStore.startCalls)
            assertEquals(VoiceInputPhase.IDLE, vm.voiceInput.value.phase)
        }

    // ── the recorder is disk I/O, and the UI must not wait on it ──
    //
    // VoiceRecorderStoreImpl writes through RandomAccessFile, deletes files and
    // joins its capture thread with a one-second bound; every call came straight
    // off a UI callback. Main is a *queuing* dispatcher for the two tests below
    // — with the class's usual Unconfined one, a call that had left the caller's
    // stack and one that had not would look identical.

    @Test
    fun `finishing a dictation does not stop the recorder on the caller's stack`() =
        runTest(testDispatcher) {
            kotlinx.coroutines.Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            speechRecognition.reportedReadiness = RecognizerReadiness.UNAVAILABLE
            vm.startVoiceRecording()
            testScheduler.advanceUntilIdle()
            assertEquals("test setup: the fallback recorder must be open", 1, voiceRecorderStore.startCalls)

            vm.finishVoiceInput()

            assertEquals(
                "stopRecording joins the capture thread and rewrites the WAV header — " +
                    "the ANR candidate, and it ran before finishVoiceInput even returned",
                0,
                voiceRecorderStore.stopCalls
            )
            testScheduler.advanceUntilIdle()
            assertEquals("but it must still happen", 1, voiceRecorderStore.stopCalls)
        }

    @Test
    fun `cancelling a dictation does not delete the recording on the caller's stack`() =
        runTest(testDispatcher) {
            kotlinx.coroutines.Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            speechRecognition.reportedReadiness = RecognizerReadiness.UNAVAILABLE
            vm.startVoiceRecording()
            testScheduler.advanceUntilIdle()

            vm.cancelVoiceRecording()

            assertEquals(
                "cancelRecording joins the capture thread and deletes the file",
                0,
                voiceRecorderStore.cancelCalls
            )
            assertEquals(
                "the row still has to go the instant the user taps cancel",
                VoiceInputPhase.IDLE,
                vm.voiceInput.value.phase
            )
            testScheduler.advanceUntilIdle()
            assertEquals("and the recorder is still released", 1, voiceRecorderStore.cancelCalls)
        }

    // A registered recognizer is not a working one: some devices ship a stub
    // that binds, takes the microphone and never calls back at all — not even
    // onError — so the dictation used to hang until the user gave up.

    @Test
    fun `a recognizer that never calls back hands the dictation to server transcription`() =
        runTest(testDispatcher) {
            speechRecognition.reportedReadiness = RecognizerReadiness.UNPROVEN

            vm.startVoiceRecording()
            testScheduler.runCurrent()
            assertEquals("the probe is the dictation, so nothing starts recording yet", 0, voiceRecorderStore.startCalls)

            testScheduler.advanceTimeBy(RECOGNIZER_PROBE_TIMEOUT_MS + 1)
            testScheduler.runCurrent()

            assertEquals(1, voiceRecorderStore.startCalls)
            assertEquals(
                "the user is still holding the mic down; don't make them start over",
                VoiceInputPhase.LISTENING,
                vm.voiceInput.value.phase
            )
            assertNull(vm.voiceInput.value.error)
        }

    @Test
    fun `a recognizer that answers keeps the dictation, however long the user pauses`() =
        runTest(testDispatcher) {
            speechRecognition.reportedReadiness = RecognizerReadiness.UNPROVEN

            vm.startVoiceRecording()
            testScheduler.runCurrent()
            speechRecognition.emit(SpeechRecognitionEvent.Ready)
            testScheduler.runCurrent()

            testScheduler.advanceTimeBy(RECOGNIZER_PROBE_TIMEOUT_MS * 4)
            testScheduler.runCurrent()

            assertEquals("a silent speaker is not a silent recognizer", 0, voiceRecorderStore.startCalls)
            assertEquals(VoiceInputPhase.LISTENING, vm.voiceInput.value.phase)
            assertNull("nothing changed, so there is nothing to tell the user", vm.voiceInput.value.notice)
            assertEquals(listOf(true), speechRecognition.recordedVerdicts)
        }

    // READY is the recognizer's own account of itself — checkRecognitionSupport
    // said so — and an API 34 emulator has answered READY and then emitted
    // nothing at all. Only a callback is evidence, so this path is watched too.

    @Test
    fun `a READY recognizer that never calls back falls back and announces it`() =
        runTest(testDispatcher) {
            speechRecognition.reportedReadiness = RecognizerReadiness.READY

            vm.startVoiceRecording()
            testScheduler.runCurrent()
            assertEquals("the recognizer is being given its chance", 0, voiceRecorderStore.startCalls)

            testScheduler.advanceTimeBy(RECOGNIZER_READY_TIMEOUT_MS + 1)
            testScheduler.runCurrent()

            assertEquals(1, voiceRecorderStore.startCalls)
            assertEquals(
                "the user is still holding the mic down; don't make them start over",
                VoiceInputPhase.LISTENING,
                vm.voiceInput.value.phase
            )
            assertEquals("Using server transcription.", vm.voiceInput.value.notice)
            assertNull("a working fallback is not a failure", vm.voiceInput.value.error)
        }

    @Test
    fun `a READY recognizer gets the whole window before it is given up on`() =
        runTest(testDispatcher) {
            speechRecognition.reportedReadiness = RecognizerReadiness.READY

            vm.startVoiceRecording()
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(RECOGNIZER_READY_TIMEOUT_MS - 1)
            testScheduler.runCurrent()
            assertEquals(
                "a cold recognition service takes ~1.7s; that is slow, not dead",
                VoiceInputPhase.PREPARING,
                vm.voiceInput.value.phase
            )

            speechRecognition.emit(SpeechRecognitionEvent.Ready)
            testScheduler.advanceTimeBy(RECOGNIZER_PROBE_TIMEOUT_MS * 4)
            testScheduler.runCurrent()

            assertEquals("it answered, so it keeps the dictation", 0, voiceRecorderStore.startCalls)
            assertEquals(VoiceInputPhase.LISTENING, vm.voiceInput.value.phase)
            assertNull("nothing changed, so there is nothing to tell the user", vm.voiceInput.value.notice)
        }

    @Test
    fun `a READY recognizer proved silent is not asked again this session`() =
        runTest(testDispatcher) {
            speechRecognition.reportedReadiness = RecognizerReadiness.READY
            vm.startVoiceRecording()
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(RECOGNIZER_READY_TIMEOUT_MS + 1)
            testScheduler.runCurrent()
            vm.cancelVoiceRecording()

            vm.startVoiceRecording()
            testScheduler.runCurrent()

            assertEquals("READY was disproved by silence, and that verdict sticks", 1, speechRecognition.listenCalls)
            assertEquals("and the recorder starts without waiting again", 2, voiceRecorderStore.startCalls)
        }

    @Test
    fun `cancelling during the READY wait leaves no watchdog and no recorder behind`() =
        runTest(testDispatcher) {
            speechRecognition.reportedReadiness = RecognizerReadiness.READY
            vm.startVoiceRecording()
            testScheduler.runCurrent()
            assertEquals(VoiceInputPhase.PREPARING, vm.voiceInput.value.phase)

            vm.cancelVoiceRecording()
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(RECOGNIZER_READY_TIMEOUT_MS * 2)
            testScheduler.runCurrent()

            assertEquals(
                "the watchdog died with the job; nothing may open a mic behind the user",
                0,
                voiceRecorderStore.startCalls
            )
            assertEquals(VoiceInputPhase.IDLE, vm.voiceInput.value.phase)
        }

    @Test
    fun `an unproven recognizer still gets the longer wait than a READY one`() =
        runTest(testDispatcher) {
            speechRecognition.reportedReadiness = RecognizerReadiness.UNPROVEN

            vm.startVoiceRecording()
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(RECOGNIZER_READY_TIMEOUT_MS + 1)
            testScheduler.runCurrent()

            assertTrue(
                "a recognizer that never claimed readiness is given more rope, not less",
                RECOGNIZER_READY_TIMEOUT_MS < RECOGNIZER_PROBE_TIMEOUT_MS
            )
            assertEquals("the unproven window is not over yet", 0, voiceRecorderStore.startCalls)
            assertEquals(VoiceInputPhase.PREPARING, vm.voiceInput.value.phase)
        }

    @Test
    fun `the silent verdict is remembered, so the next dictation costs no wait`() =
        runTest(testDispatcher) {
            speechRecognition.reportedReadiness = RecognizerReadiness.UNPROVEN
            vm.startVoiceRecording()
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(RECOGNIZER_PROBE_TIMEOUT_MS + 1)
            testScheduler.runCurrent()
            vm.cancelVoiceRecording()

            vm.startVoiceRecording()
            testScheduler.runCurrent()

            assertEquals("the recognizer must not be probed a second time", 1, speechRecognition.listenCalls)
            assertEquals("and the recorder starts without waiting out the probe", 2, voiceRecorderStore.startCalls)
        }

    @Test
    fun `stopping before the recognizer ever answers does not open the recorder afterwards`() =
        runTest(testDispatcher) {
            speechRecognition.reportedReadiness = RecognizerReadiness.UNPROVEN
            vm.startVoiceRecording()
            testScheduler.runCurrent()

            vm.finishVoiceInput()
            testScheduler.advanceTimeBy(RECOGNIZER_PROBE_TIMEOUT_MS + 1)
            testScheduler.runCurrent()

            assertEquals("the user already stopped; don't reopen the mic behind them", 0, voiceRecorderStore.startCalls)
            assertEquals(VoiceInputPhase.IDLE, vm.voiceInput.value.phase)
        }

    @Test
    fun `the switch to server transcription is announced once and then dropped`() =
        runTest(testDispatcher) {
            speechRecognition.reportedReadiness = RecognizerReadiness.UNPROVEN
            vm.startVoiceRecording()
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(RECOGNIZER_PROBE_TIMEOUT_MS + 1)
            testScheduler.runCurrent()

            assertEquals("Using server transcription.", vm.voiceInput.value.notice)
            assertNull("a working fallback is not a failure", vm.voiceInput.value.error)

            vm.cancelVoiceRecording()
            vm.startVoiceRecording()
            testScheduler.runCurrent()

            assertNull("saying it every dictation is nagging", vm.voiceInput.value.notice)
        }

    @Test
    fun `partial results reach the composer while the user is still speaking`() =
        runTest(testDispatcher) {
            vm.startVoiceRecording()
            testScheduler.runCurrent()

            speechRecognition.emit(SpeechRecognitionEvent.Partial("book a"))
            testScheduler.runCurrent()
            assertEquals("book a", vm.voiceInput.value.transcript)
            assertEquals(VoiceInputPhase.LISTENING, vm.voiceInput.value.phase)

            // Cumulative, not a delta — the composer recomputes from its anchor.
            speechRecognition.emit(SpeechRecognitionEvent.Partial("book a table"))
            testScheduler.runCurrent()
            assertEquals("book a table", vm.voiceInput.value.transcript)
        }

    @Test
    fun `a finished dictation lands in the composer and sends nothing`() =
        runTest(testDispatcher) {
            val address = validHexAddress()
            vm.connectToAgent(address)
            testScheduler.runCurrent()
            connectUseCase.markConnected(address)

            vm.startVoiceRecording()
            testScheduler.runCurrent()
            speechRecognition.emit(SpeechRecognitionEvent.Final("book a table for two"))
            testScheduler.runCurrent()

            assertEquals("book a table for two", vm.voiceInput.value.transcript)
            assertEquals(VoiceInputPhase.IDLE, vm.voiceInput.value.phase)
            assertTrue(
                "voice must not post a chat item of its own",
                vm.uiState.value.chatItems.isEmpty()
            )
            assertTrue(
                "nothing reaches the agent until the user presses Send",
                connectUseCase.sentMessages.isEmpty()
            )
        }

    @Test
    fun `finishVoiceInput asks the recognizer to settle rather than stopping the recorder`() =
        runTest(testDispatcher) {
            vm.startVoiceRecording()
            testScheduler.runCurrent()
            // There is nothing to settle before the recognizer answers, so the
            // dictation has to be genuinely running for this to be the case.
            speechRecognition.emit(SpeechRecognitionEvent.Ready)
            testScheduler.runCurrent()

            vm.finishVoiceInput()
            testScheduler.runCurrent()

            assertEquals(1, speechRecognition.stopCalls)
        }

    @Test
    fun `finishing while preparing discards instead of settling a recognizer that never started`() =
        runTest(testDispatcher) {
            speechRecognition.reportedReadiness = RecognizerReadiness.READY
            vm.startVoiceRecording()
            testScheduler.runCurrent()

            vm.finishVoiceInput()
            testScheduler.runCurrent()

            assertEquals("nothing was captured, so there is nothing to settle", 0, speechRecognition.stopCalls)
            assertEquals(VoiceInputPhase.IDLE, vm.voiceInput.value.phase)
            assertEquals("", vm.voiceInput.value.transcript)
        }

    @Test
    fun `a failed dictation keeps the partials it already delivered`() =
        runTest(testDispatcher) {
            vm.startVoiceRecording()
            testScheduler.runCurrent()
            speechRecognition.emit(SpeechRecognitionEvent.Partial("half a sentence"))
            testScheduler.runCurrent()

            speechRecognition.emit(SpeechRecognitionEvent.Failed("Didn't catch that. Try again."))
            testScheduler.runCurrent()

            assertEquals("half a sentence", vm.voiceInput.value.transcript)
            assertNull("a timeout after real words is not an error worth showing", vm.voiceInput.value.error)
        }

    @Test
    fun `a dictation that heard nothing at all reports why`() =
        runTest(testDispatcher) {
            vm.startVoiceRecording()
            testScheduler.runCurrent()

            speechRecognition.emit(SpeechRecognitionEvent.Failed("Didn't catch that. Try again."))
            testScheduler.runCurrent()

            assertEquals("Didn't catch that. Try again.", vm.voiceInput.value.error)
        }

    @Test
    fun `the composer taking the transcript does not wipe the error line with it`() =
        runTest(testDispatcher) {
            vm.startVoiceRecording()
            testScheduler.runCurrent()
            speechRecognition.emit(SpeechRecognitionEvent.Failed("Didn't catch that. Try again."))
            testScheduler.runCurrent()

            // The composer merges and reports back in the same frame; an error
            // cleared there would never be on screen long enough to read.
            vm.consumeVoiceTranscript()

            assertEquals("Didn't catch that. Try again.", vm.voiceInput.value.error)
        }

    @Test
    fun `starting the next dictation clears the last one's error`() =
        runTest(testDispatcher) {
            vm.startVoiceRecording()
            testScheduler.runCurrent()
            speechRecognition.emit(SpeechRecognitionEvent.Failed("Didn't catch that. Try again."))
            testScheduler.runCurrent()
            vm.consumeVoiceTranscript()

            vm.startVoiceRecording()
            testScheduler.runCurrent()

            assertNull(vm.voiceInput.value.error)
        }

    @Test
    fun `cancelling a dictation throws the transcript away`() =
        runTest(testDispatcher) {
            vm.startVoiceRecording()
            testScheduler.runCurrent()
            speechRecognition.emit(SpeechRecognitionEvent.Partial("never mind"))
            testScheduler.runCurrent()

            vm.cancelVoiceRecording()
            testScheduler.runCurrent()

            assertEquals("", vm.voiceInput.value.transcript)
            assertEquals(VoiceInputPhase.IDLE, vm.voiceInput.value.phase)
        }

    @Test
    fun `the fallback path transcribes the recording and puts the text in the composer`() =
        runTest(testDispatcher) {
            speechRecognition.reportedReadiness = RecognizerReadiness.UNAVAILABLE
            voiceTranscriptionService.nextTranscript = "hello from voice"
            vm.startVoiceRecording()
            testScheduler.runCurrent()

            vm.finishVoiceInput()
            testScheduler.runCurrent()

            assertEquals("hello from voice", vm.voiceInput.value.transcript)
            assertEquals(VoiceInputPhase.IDLE, vm.voiceInput.value.phase)
            assertTrue(connectUseCase.sentMessages.isEmpty())
        }

    // What the fallback path does when it cannot finish. Each ends with an
    // idle composer and nothing sent - dictation never sends on its own, and a
    // failed one must not either.

    /** One whole dictation on the recorder + server-transcription path. */
    private fun TestScope.dictateOnFallbackPath() {
        speechRecognition.reportedReadiness = RecognizerReadiness.UNAVAILABLE
        vm.startVoiceRecording()
        testScheduler.runCurrent()
        vm.finishVoiceInput()
        testScheduler.runCurrent()
    }

    @Test
    fun `a recorder that cannot open says so and sends nothing`() =
        runTest(testDispatcher) {
            speechRecognition.reportedReadiness = RecognizerReadiness.UNAVAILABLE
            voiceRecorderStore.startSucceeds = false

            vm.startVoiceRecording()
            testScheduler.runCurrent()

            assertEquals("Couldn't start recording.", vm.voiceInput.value.error)
            assertEquals(VoiceInputPhase.IDLE, vm.voiceInput.value.phase)
            assertEquals(0, voiceTranscriptionService.transcribeCalls)
            assertTrue(connectUseCase.sentMessages.isEmpty())
        }

    @Test
    fun `a failed server transcription says so, leaves the composer alone, and sends nothing`() =
        runTest(testDispatcher) {
            voiceTranscriptionService.nextTranscript = null

            dictateOnFallbackPath()

            assertEquals("Couldn't transcribe that. Try again.", vm.voiceInput.value.error)
            assertEquals("", vm.voiceInput.value.transcript)
            assertEquals(VoiceInputPhase.IDLE, vm.voiceInput.value.phase)
            assertTrue(connectUseCase.sentMessages.isEmpty())
        }

    @Test
    fun `a transcription refused over the device clock says to fix the clock, not to try again`() =
        runTest(testDispatcher) {
            // W16AAPPLE-91: trying again cannot help while the account server refuses the clock.
            voiceTranscriptionService.nextTranscript = null
            voiceTranscriptionService.nextFailure = AccountClockException()

            dictateOnFallbackPath()

            assertEquals(ChatViewModel.TRANSCRIPTION_CLOCK_WRONG, vm.voiceInput.value.error)
            assertTrue(connectUseCase.sentMessages.isEmpty())
        }

    @Test
    fun `a blank server transcription is reported as a failure, not as an empty success`() =
        runTest(testDispatcher) {
            voiceTranscriptionService.nextTranscript = "   "

            dictateOnFallbackPath()

            assertEquals("Couldn't transcribe that. Try again.", vm.voiceInput.value.error)
            assertTrue(connectUseCase.sentMessages.isEmpty())
        }

    @Test
    fun `a recording too short to keep says so, and is not uploaded or sent`() =
        runTest(testDispatcher) {
            // W16AAPPLE-103: an accidental tap used to vanish with no word.
            voiceRecorderStore.nextRecording = RecordingResult.TooShort

            dictateOnFallbackPath()

            assertEquals(ChatViewModel.RECORDING_TOO_SHORT, vm.voiceInput.value.error)
            assertEquals(VoiceInputPhase.IDLE, vm.voiceInput.value.phase)
            assertEquals("", vm.voiceInput.value.transcript)
            assertEquals(0, voiceTranscriptionService.transcribeCalls)
            assertTrue(connectUseCase.sentMessages.isEmpty())
        }

    @Test
    fun `a recording that produced nothing usable is dropped quietly`() =
        runTest(testDispatcher) {
            // Not the user's to fix, unlike a too-short one, so no message.
            voiceRecorderStore.nextRecording = RecordingResult.Unusable

            dictateOnFallbackPath()

            assertNull(vm.voiceInput.value.error)
            assertEquals(VoiceInputPhase.IDLE, vm.voiceInput.value.phase)
            assertEquals(0, voiceTranscriptionService.transcribeCalls)
            assertTrue(connectUseCase.sentMessages.isEmpty())
        }

    @Test
    fun `starting a new dictation clears the too-short message`() =
        runTest(testDispatcher) {
            voiceRecorderStore.nextRecording = RecordingResult.TooShort
            dictateOnFallbackPath()
            assertEquals("test setup", ChatViewModel.RECORDING_TOO_SHORT, vm.voiceInput.value.error)

            vm.startVoiceRecording()
            testScheduler.runCurrent()

            assertNull(vm.voiceInput.value.error)
        }

    @Test
    fun `cancelVoiceRecording releases the recorder on the fallback path`() =
        runTest(testDispatcher) {
            speechRecognition.reportedReadiness = RecognizerReadiness.UNAVAILABLE
            vm.startVoiceRecording()
            testScheduler.runCurrent()

            vm.cancelVoiceRecording()

            assertEquals(1, voiceRecorderStore.cancelCalls)
        }

    // ── Interjection ───────────────────────────────────────────────────

    /**
     * A message sent into a running turn is acknowledged by the relay with
     * `RUNTIME_INPUT_ACK` and then only read if that turn starts another
     * iteration. Measured both ways on device against one agent with the
     * `runtime_input` plugin enabled: a turn that called a tool picked the
     * message up and answered it; a plain question ran a single iteration and
     * never did. So it waits for the turn instead.
     */
    private fun startTurn(id: String) = ChatEvent.ChatItemReceived(
        ChatItem.Turn(id = id, thinking = ChatItem.Thinking(id = id, status = ThinkingStatus.RUNNING))
    )

    /** Ends a turn the way the wire does — the OUTPUT, not the thinking status. */
    private suspend fun FakeConnectToAgentUseCase.endTurn() =
        emit(ChatEvent.OutputReceived("done", null))

    @Test
    fun `a message typed mid-run is held until the turn ends, not sent into it`() =
        runTest(testDispatcher) {
            val address = validHexAddress()
            vm.connectToAgent(address)
            testScheduler.runCurrent()
            connectUseCase.markConnected(address)
            vm.sendMessage("first question")
            testScheduler.runCurrent()
            connectUseCase.emit(startTurn("turn-1"))
            testScheduler.runCurrent()
            assertTrue("premise: the agent is mid-run", vm.isAgentWorking.value)

            vm.sendMessage("actually, use the staging URL")
            testScheduler.runCurrent()

            assertEquals(
                "only the first has reached the wire",
                listOf("first question"),
                connectUseCase.sentMessages.map { it.content }
            )
            val queued = vm.uiState.value.chatItems.last() as ChatItem.User
            assertEquals("actually, use the staging URL", queued.content)
            assertTrue("the bubble is up, and says it is waiting", queued.queued)

            connectUseCase.endTurn()
            testScheduler.runCurrent()

            assertEquals(
                "once the turn is over it goes out as an ordinary INPUT",
                listOf("first question", "actually, use the staging URL"),
                connectUseCase.sentMessages.map { it.content }
            )
            assertTrue(
                "and stops claiming to be waiting",
                vm.uiState.value.chatItems.filterIsInstance<ChatItem.User>().none { it.queued }
            )
        }

    /**
     * Reproduces a hang seen on a device that kills the socket seconds after
     * the app is backgrounded: the turn's OUTPUT never came back, and the send
     * gate — cleared only where OUTPUT arrives — held every later message
     * behind a turn that had already ended. An auto-reconnect goes through
     * Reconnecting, never Disconnected, so none of the disconnect recovery ran.
     */
    @Test
    fun `a turn cut off by a reconnect stops holding later messages`() =
        runTest(testDispatcher) {
            val address = validHexAddress()
            vm.connectToAgent(address)
            testScheduler.runCurrent()
            connectUseCase.markConnected(address)
            vm.sendMessage("first question")
            testScheduler.runCurrent()
            connectUseCase.emit(startTurn("turn-1"))
            testScheduler.runCurrent()
            assertTrue("premise: the agent is mid-run", vm.isAgentWorking.value)

            // The socket dies and the ladder reconnects. No OUTPUT ever lands.
            connectUseCase.forceState(ConnectionState.Reconnecting)
            testScheduler.runCurrent()
            connectUseCase.markConnected(address)
            testScheduler.runCurrent()

            vm.sendMessage("are you still there")
            testScheduler.runCurrent()

            assertEquals(
                "the second message goes out instead of waiting on a dead turn",
                listOf("first question", "are you still there"),
                connectUseCase.sentMessages.map { it.content }
            )
            assertTrue(
                "and no bubble is left claiming to be waiting",
                vm.uiState.value.chatItems.filterIsInstance<ChatItem.User>().none { it.queued }
            )
        }

    /**
     * W16AAPPLE-108: the Host lost this connection's session, so it refused
     * the message unread. AgentConnection says so, then reconnects, and sends
     * nothing again by itself: the message is shown failed with Retry, and
     * only Retry sends it, once.
     */
    @Test
    fun `a message lost with the Host's session is shown failed with Retry, and sent again only by Retry`() =
        runTest(testDispatcher) {
            val address = validHexAddress()
            vm.connectToAgent(address)
            testScheduler.runCurrent()
            connectUseCase.markConnected(address)
            vm.sendMessage("what's on today?")
            testScheduler.runCurrent()

            connectUseCase.emit(ChatEvent.ConnectionErrorOccurred(AgentConnection.LOST_SESSION_MESSAGE))
            testScheduler.runCurrent()
            connectUseCase.forceState(ConnectionState.Reconnecting)
            testScheduler.runCurrent()
            connectUseCase.markConnected(address)
            testScheduler.runCurrent()

            val items = vm.uiState.value.chatItems
            val bubble = items.indexOfFirst { it is ChatItem.User && it.content == "what's on today?" }
            val failed = items.drop(bubble + 1).filterIsInstance<ChatItem.Thinking>().single()
            assertEquals("a failed turn, which offers Retry", ThinkingStatus.ERROR, failed.status)
            assertEquals(AgentConnection.LOST_SESSION_MESSAGE, failed.content)
            assertEquals("not sent again by itself", listOf("what's on today?"), connectUseCase.sentMessages.map { it.content })

            vm.retryFailedTurn(items[bubble].id)
            testScheduler.runCurrent()

            assertEquals(listOf("what's on today?", "what's on today?"), connectUseCase.sentMessages.map { it.content })
            assertEquals(
                "Retry adds no second bubble",
                1,
                vm.uiState.value.chatItems.count { it is ChatItem.User && it.content == "what's on today?" }
            )
        }

    @Test
    fun `the closing message after Stop lands in the turn that was stopped`() =
        runTest(testDispatcher) {
            val address = validHexAddress()
            vm.connectToAgent(address)
            testScheduler.runCurrent()
            connectUseCase.markConnected(address)
            vm.sendMessage("open a website")
            testScheduler.runCurrent()
            connectUseCase.emit(startTurn("turn-1"))
            testScheduler.runCurrent()

            vm.interrupt()
            testScheduler.runCurrent()
            // Interrupt is a request: the agent finishes its step and answers.
            connectUseCase.emit(ChatEvent.OutputReceived("Stopped. What next?", null))
            testScheduler.runCurrent()

            val turns = vm.uiState.value.chatItems.filterIsInstance<ChatItem.Turn>()
            assertEquals("the reply belongs in the stopped turn, not beside it", 1, turns.size)
            assertEquals("Stopped. What next?", turns.single().agent?.content)
            assertTrue(
                "no stray reply bubble left carrying the answer on its own",
                vm.uiState.value.chatItems.filterIsInstance<ChatItem.Agent>().isEmpty()
            )
        }

    @Test
    fun `Stop reopens the send gate, not just the spinners`() =
        runTest(testDispatcher) {
            val address = validHexAddress()
            vm.connectToAgent(address)
            testScheduler.runCurrent()
            connectUseCase.markConnected(address)
            vm.sendMessage("first question")
            testScheduler.runCurrent()
            connectUseCase.emit(startTurn("turn-1"))
            testScheduler.runCurrent()

            vm.interrupt()
            testScheduler.runCurrent()
            vm.sendMessage("never mind, different question")
            testScheduler.runCurrent()

            assertEquals(
                "a message after Stop is not held behind the turn Stop ended",
                listOf("first question", "never mind, different question"),
                connectUseCase.sentMessages.map { it.content }
            )
        }

    @Test
    fun `what the running turn still emits lands above the message waiting on it`() =
        runTest(testDispatcher) {
            val address = validHexAddress()
            vm.connectToAgent(address)
            testScheduler.runCurrent()
            connectUseCase.markConnected(address)
            vm.sendMessage("first question")
            testScheduler.runCurrent()
            connectUseCase.emit(startTurn("turn-1"))
            testScheduler.runCurrent()

            vm.sendMessage("and another thing")
            testScheduler.runCurrent()
            // turn-1's answer arrives after the second message was typed. It
            // answers the *first* one, so appending it would put it under the
            // wrong question — which is what it did on device.
            connectUseCase.emit(
                ChatEvent.ChatItemReceived(ChatItem.Agent(id = "answer-1", content = "the answer to the first"))
            )
            testScheduler.runCurrent()

            val contents = vm.uiState.value.chatItems.map {
                when (it) {
                    is ChatItem.User -> it.content
                    is ChatItem.Agent -> it.content
                    else -> it.id
                }
            }
            assertEquals(
                listOf("first question", "turn-1", "the answer to the first", "and another thing"),
                contents
            )
        }

    /** Connected, one question asked, and its turn still running. */
    private fun kotlinx.coroutines.test.TestScope.givenAQuestionWhoseTurnIsRunning(address: String) {
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)
        vm.sendMessage("first question")
        testScheduler.runCurrent()
        connectUseCase.emit(startTurn("turn-1"))
        testScheduler.runCurrent()
    }

    @Test
    fun `a held message is saved at the tap as failed, and moves below the reply once it is sent`() =
        runTest(testDispatcher) {
            givenAQuestionWhoseTurnIsRunning(validHexAddress())

            vm.sendMessage("and another thing")
            testScheduler.runCurrent()

            // Saved now, so dying during the hold cannot lose it; failed,
            // because it has not gone anywhere yet.
            assertTrue(savedAsFailed("and another thing"))

            connectUseCase.endTurn()
            testScheduler.runCurrent()

            // Read as a reload reads it, ordered by timestamp. Dated at the tap,
            // the held message sat above the reply it waited behind — both
            // questions, then both answers. Sending moves it to the end.
            assertEquals(
                "the reply lands between the question it answers and the one that waited",
                listOf("first question", "done", "and another thing"),
                messageRepo.getMessagesListBySession(vm.activeSessionId.value!!).map { it.content }
            )
            assertFalse("delivered, so no longer failed", savedAsFailed("and another thing"))
        }

    @Test
    fun `a held message survives a restart during the hold, failed and offered for resending`() =
        runTest(testDispatcher) {
            val address = validHexAddress()
            givenAQuestionWhoseTurnIsRunning(address)
            vm.sendMessage("and another thing")
            testScheduler.runCurrent()

            // The process dies here, before the turn ends. A new screen on the
            // same storage is what the restart sees.
            val restarted = newChatViewModel()
            restarted.connectToAgent(address)
            testScheduler.runCurrent()

            val held = restarted.uiState.value.chatItems.filterIsInstance<ChatItem.User>()
                .single { it.content == "and another thing" }
            assertEquals(UserMessageState.FAILED, held.state)
            assertEquals(listOf(held.id), restarted.uiState.value.chatItems.failedUserMessages().map { it.id })
        }

    @Test
    fun `two messages typed mid-run go out one turn at a time`() =
        runTest(testDispatcher) {
            val address = validHexAddress()
            vm.connectToAgent(address)
            testScheduler.runCurrent()
            connectUseCase.markConnected(address)
            vm.sendMessage("first question")
            testScheduler.runCurrent()
            connectUseCase.emit(startTurn("turn-1"))
            testScheduler.runCurrent()

            vm.sendMessage("second")
            vm.sendMessage("third")
            testScheduler.runCurrent()
            assertEquals(1, connectUseCase.sentMessages.size)

            connectUseCase.endTurn()
            testScheduler.runCurrent()

            // Releasing both at once would put the third straight into the turn
            // the second just started — the bug this exists to prevent.
            assertEquals(
                listOf("first question", "second"),
                connectUseCase.sentMessages.map { it.content }
            )

            connectUseCase.endTurn()
            testScheduler.runCurrent()

            assertEquals(
                listOf("first question", "second", "third"),
                connectUseCase.sentMessages.map { it.content }
            )
        }

    // ── Runtime input: RUNTIME_INPUT_ACK / RUNTIME_INPUT_REJECTED ──────
    //
    // The idle hold above keeps ordinary messages out of a running turn. What
    // reaches the host mid-turn anyway is a message whose hold timed out, and
    // for that one the host answers with an ACK (queued) or a refusal (not).
    // The ACK carries only the host's own id, so these tests also pin that it
    // is linked to its message by order.

    /** A connected agent that is mid-run on "first question". */
    private fun kotlinx.coroutines.test.TestScope.givenARunningTurn() {
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)
        vm.sendMessage("first question")
        testScheduler.runCurrent()
        connectUseCase.emit(startTurn("turn-1"))
        testScheduler.runCurrent()
        assertTrue("premise: the agent is mid-run", vm.isAgentWorking.value)
    }

    /** Sends [text] and lets the idle hold time out, so it goes out into the running turn. */
    private fun kotlinx.coroutines.test.TestScope.sendIntoRunningTurn(text: String) {
        vm.sendMessage(text)
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(ChatViewModel.TURN_WAIT_TIMEOUT_MS + 1)
        testScheduler.runCurrent()
    }

    private fun userMessage(text: String): ChatItem.User =
        vm.uiState.value.chatItems.filterIsInstance<ChatItem.User>().first { it.content == text }

    @Test
    fun `a message sent into a running turn shows as pending until the ACK arrives`() =
        runTest(testDispatcher) {
            givenARunningTurn()

            sendIntoRunningTurn("actually, use the staging URL")

            assertEquals(
                "premise: it did reach the wire, mid-turn",
                listOf("first question", "actually, use the staging URL"),
                connectUseCase.sentMessages.map { it.content }
            )
            assertEquals(
                "on the wire but unconfirmed",
                UserMessageState.AWAITING_ACK,
                userMessage("actually, use the staging URL").state
            )

            connectUseCase.emit(ChatEvent.RuntimeInputAcknowledged(sessionId = "sess-1"))
            testScheduler.runCurrent()

            assertEquals(UserMessageState.SENT, userMessage("actually, use the staging URL").state)
        }

    @Test
    fun `a message sent to an idle agent never waits for an ACK`() =
        runTest(testDispatcher) {
            val address = validHexAddress()
            vm.connectToAgent(address)
            testScheduler.runCurrent()
            connectUseCase.markConnected(address)

            vm.sendMessage("hello")
            testScheduler.runCurrent()

            // It starts a turn of its own, and the host sends no ACK for that.
            assertEquals(UserMessageState.SENT, userMessage("hello").state)
        }

    @Test
    fun `a second message waits behind an unanswered one, so a reply can only be for the first`() =
        runTest(testDispatcher) {
            givenARunningTurn()
            sendIntoRunningTurn("second")
            assertEquals(UserMessageState.AWAITING_ACK, userMessage("second").state)

            vm.sendMessage("third")
            testScheduler.runCurrent()

            assertEquals(
                "nothing else goes out while an answer is owed",
                listOf("first question", "second"),
                connectUseCase.sentMessages.map { it.content }
            )

            connectUseCase.emit(ChatEvent.RuntimeInputAcknowledged(sessionId = "sess-1"))
            testScheduler.runCurrent()
            assertEquals(UserMessageState.SENT, userMessage("second").state)

            testScheduler.advanceTimeBy(ChatViewModel.TURN_WAIT_TIMEOUT_MS + 1)
            testScheduler.runCurrent()
            assertEquals(
                listOf("first question", "second", "third"),
                connectUseCase.sentMessages.map { it.content }
            )
            assertEquals(UserMessageState.AWAITING_ACK, userMessage("third").state)

            connectUseCase.emit(ChatEvent.RuntimeInputAcknowledged(sessionId = "sess-1"))
            testScheduler.runCurrent()
            assertEquals(UserMessageState.SENT, userMessage("third").state)
        }

    @Test
    fun `an ACK that arrives while the send is still in flight is not lost`() =
        runTest(testDispatcher) {
            givenARunningTurn()
            connectUseCase.onSend = {
                connectUseCase.emit(ChatEvent.RuntimeInputAcknowledged(sessionId = "sess-1"))
            }

            sendIntoRunningTurn("second")

            // Settled by the ACK itself, not by the reply timeout running out.
            assertEquals(UserMessageState.SENT, userMessage("second").state)
        }

    @Test
    fun `a wrong guess that a turn was running does not let a later ACK settle the wrong message`() =
        runTest(testDispatcher) {
            givenARunningTurn()
            sendIntoRunningTurn("second")
            testScheduler.advanceTimeBy(ChatViewModel.RUNTIME_INPUT_REPLY_TIMEOUT_MS + 1)
            testScheduler.runCurrent()
            assertEquals(UserMessageState.SENT, userMessage("second").state)

            sendIntoRunningTurn("third")
            assertEquals(UserMessageState.AWAITING_ACK, userMessage("third").state)

            connectUseCase.emit(ChatEvent.RuntimeInputAcknowledged(sessionId = "sess-1"))
            testScheduler.runCurrent()
            assertEquals(UserMessageState.SENT, userMessage("second").state)
            assertEquals(UserMessageState.SENT, userMessage("third").state)
        }

    @Test
    fun `an unrelated error frame does not fail a message still waiting for its answer`() =
        runTest(testDispatcher) {
            givenARunningTurn()
            sendIntoRunningTurn("second")

            connectUseCase.emit(ChatEvent.ConnectionErrorOccurred("provider hiccup"))
            testScheduler.runCurrent()
            assertEquals(UserMessageState.AWAITING_ACK, userMessage("second").state)

            connectUseCase.emit(ChatEvent.RuntimeInputAcknowledged(sessionId = "sess-1"))
            testScheduler.runCurrent()
            assertEquals(UserMessageState.SENT, userMessage("second").state)
        }

    @Test
    fun `a message the host keeps refusing is resent once, not forever`() =
        runTest(testDispatcher) {
            givenARunningTurn()
            sendIntoRunningTurn("use staging")

            connectUseCase.emit(ChatEvent.RuntimeInputRejected(sessionId = "sess-1", retryable = true))
            testScheduler.runCurrent()
            assertEquals(UserMessageState.QUEUED, userMessage("use staging").state)

            testScheduler.advanceTimeBy(ChatViewModel.TURN_WAIT_TIMEOUT_MS + 1)
            testScheduler.runCurrent()
            assertEquals(
                listOf("first question", "use staging", "use staging"),
                connectUseCase.sentMessages.map { it.content }
            )

            connectUseCase.emit(ChatEvent.RuntimeInputRejected(sessionId = "sess-1", retryable = true))
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(ChatViewModel.TURN_WAIT_TIMEOUT_MS * 3)
            testScheduler.runCurrent()

            assertEquals(UserMessageState.FAILED, userMessage("use staging").state)
            assertEquals("no third attempt", 3, connectUseCase.sentMessages.size)
        }

    // ── What a restart brings a message back as ──────────────────────────

    /** Whether [text]'s saved row says it should come back failed after a restart. */
    private fun kotlinx.coroutines.test.TestScope.savedAsFailed(text: String): Boolean {
        testScheduler.runCurrent()
        return messageRepo.snapshot().single { it.role == Role.USER && it.content == text }.failedToSend
    }

    /** Whether [text]'s saved row says it is still in the outbox, to come back "Waiting to send..." after a restart. */
    private fun kotlinx.coroutines.test.TestScope.savedInOutbox(text: String): Boolean {
        testScheduler.runCurrent()
        return messageRepo.snapshot().single { it.role == Role.USER && it.content == text }.inOutbox
    }

    @Test
    fun `a message waiting for the Host's answer is saved as failed until the answer says it got through`() =
        runTest(testDispatcher) {
            givenARunningTurn()
            sendIntoRunningTurn("second")
            assertEquals("test setup", UserMessageState.AWAITING_ACK, userMessage("second").state)

            // If the app dies now, nobody knows whether the Host took it.
            assertTrue(savedAsFailed("second"))

            connectUseCase.emit(ChatEvent.RuntimeInputAcknowledged(sessionId = "sess-1"))
            testScheduler.runCurrent()

            assertFalse(savedAsFailed("second"))
        }

    @Test
    fun `a message in the outbox is saved as not failed, since the outbox still sends it after a restart`() =
        runTest(testDispatcher) {
            val address = validHexAddress()
            vm.connectToAgent(address)
            testScheduler.runCurrent()
            vm.sendMessage("typed offline")
            testScheduler.runCurrent()
            assertEquals("test setup", UserMessageState.QUEUED, userMessage("typed offline").state)

            assertFalse(savedAsFailed("typed offline"))
        }

    @Test
    fun `a message held in memory for a retry is saved as failed, since a restart loses the hold`() =
        runTest(testDispatcher) {
            givenARunningTurn()
            sendIntoRunningTurn("use staging")

            connectUseCase.emit(ChatEvent.RuntimeInputRejected(sessionId = "sess-1", retryable = true))
            testScheduler.runCurrent()
            assertEquals("test setup", UserMessageState.QUEUED, userMessage("use staging").state)

            // QUEUED like an outbox message, but only this process holds it.
            assertTrue(savedAsFailed("use staging"))
        }

    @Test
    fun `a message with an image is held until the turn ends, never sent into it`() =
        runTest(testDispatcher) {
            givenARunningTurn()

            vm.sendMessage("see this", images = listOf("content://picker/1"))
            testScheduler.runCurrent()
            // Well inside the 2 minute cap: still held, not sent, not failed.
            testScheduler.advanceTimeBy(ChatViewModel.TURN_WAIT_TIMEOUT_MS / 2)
            testScheduler.runCurrent()

            assertEquals(
                "the host's mid-turn queue keeps only the text, so the image must not go yet",
                listOf("first question"),
                connectUseCase.sentMessages.map { it.content }
            )
            assertEquals(UserMessageState.QUEUED, userMessage("see this").state)

            connectUseCase.endTurn()
            testScheduler.runCurrent()

            val sent = connectUseCase.sentMessages.single { it.content == "see this" }
            assertEquals(
                listOf("data:image/jpeg;base64,FAKE-content://picker/1"),
                sent.images
            )
            assertEquals(UserMessageState.SENT, userMessage("see this").state)
        }

    @Test
    fun `an image message behind a stuck card fails after the cap and does not block later messages`() =
        runTest(testDispatcher) {
            givenARunningTurn()
            // The turn ends, but a card is left RUNNING (a tool the server died in
            // the middle of). Nothing clears it, so the agent still reads as busy.
            connectUseCase.endTurn()
            testScheduler.runCurrent()
            connectUseCase.emit(startTurn("stuck-tool"))
            testScheduler.runCurrent()
            assertTrue("premise: busy on a stuck card alone", vm.isAgentWorking.value)

            vm.sendMessage("see this", images = listOf("content://picker/1"))
            testScheduler.runCurrent()
            vm.sendMessage("and a follow-up")
            testScheduler.runCurrent()

            testScheduler.advanceTimeBy(ChatViewModel.TURN_WAIT_TIMEOUT_MS + 1)
            testScheduler.runCurrent()
            assertEquals(
                "the attachment is not sent into the running turn",
                listOf("first question"),
                connectUseCase.sentMessages.map { it.content }
            )
            assertEquals(UserMessageState.FAILED, userMessage("see this").state)

            val saved = messageRepo.snapshot()
                .filter { it.role == Role.USER && it.content == "see this" }
            assertEquals(
                "the failed message is saved, so it is still there after a restart",
                1,
                saved.size
            )
            testScheduler.advanceTimeBy(ChatViewModel.TURN_WAIT_TIMEOUT_MS + 1)
            testScheduler.runCurrent()
            assertEquals(
                "and the lock was released, so the text message behind it still goes",
                listOf("first question", "and a follow-up"),
                connectUseCase.sentMessages.map { it.content }
            )
        }

    @Test
    fun `a reply that lands while the host's answer is awaited leaves the agent idle, not busy`() =
        runTest(testDispatcher) {
            givenARunningTurn()
            sendIntoRunningTurn("second")
            assertEquals(UserMessageState.AWAITING_ACK, userMessage("second").state)

            // The guess was wrong: "second" started its own turn, and it answers
            // well inside the 10 s window, with no ACK.
            connectUseCase.endTurn()
            testScheduler.runCurrent()
            assertEquals(UserMessageState.SENT, userMessage("second").state)

            vm.sendMessage("third")
            testScheduler.runCurrent()

            assertEquals(
                "goes out at once, without waiting out the 2 minute hold",
                listOf("first question", "second", "third"),
                connectUseCase.sentMessages.map { it.content }
            )
        }

    @Test
    fun `a retryable refusal holds the message and resends it as a normal INPUT after the turn`() =
        runTest(testDispatcher) {
            givenARunningTurn()
            sendIntoRunningTurn("use staging")

            connectUseCase.emit(ChatEvent.RuntimeInputRejected(sessionId = "sess-1", retryable = true))
            testScheduler.runCurrent()

            assertEquals(
                "nothing was queued on the host, so it is waiting again",
                UserMessageState.QUEUED,
                userMessage("use staging").state
            )
            assertEquals(
                "and it has not been sent a second time yet",
                listOf("first question", "use staging"),
                connectUseCase.sentMessages.map { it.content }
            )

            connectUseCase.endTurn()
            testScheduler.runCurrent()

            assertEquals(
                "the turn is over, so it goes out once more, as an ordinary INPUT",
                listOf("first question", "use staging", "use staging"),
                connectUseCase.sentMessages.map { it.content }
            )
            assertEquals(UserMessageState.SENT, userMessage("use staging").state)
            assertEquals(
                "one bubble throughout, not a second copy",
                1,
                vm.uiState.value.chatItems.filterIsInstance<ChatItem.User>().count { it.content == "use staging" }
            )
        }

    @Test
    fun `a refusal that is not retryable marks the message failed and does not resend it`() =
        runTest(testDispatcher) {
            givenARunningTurn()
            sendIntoRunningTurn("use staging")

            connectUseCase.emit(ChatEvent.RuntimeInputRejected(sessionId = "sess-1", retryable = false))
            testScheduler.runCurrent()
            connectUseCase.endTurn()
            testScheduler.runCurrent()

            assertEquals(UserMessageState.FAILED, userMessage("use staging").state)
            assertEquals(
                listOf("first question", "use staging"),
                connectUseCase.sentMessages.map { it.content }
            )
        }

    @Test
    fun `a turn that ends with no reply settles the pending message as sent`() =
        runTest(testDispatcher) {
            givenARunningTurn()
            sendIntoRunningTurn("use staging")

            // The host answers a runtime INPUT the moment it reads it. Silence
            // until OUTPUT means this one reached an agent that had just gone
            // idle and started a turn of its own.
            connectUseCase.endTurn()
            testScheduler.runCurrent()

            assertEquals(UserMessageState.SENT, userMessage("use staging").state)
        }

    @Test
    fun `losing the connection while a message is pending marks it failed`() =
        runTest(testDispatcher) {
            givenARunningTurn()
            sendIntoRunningTurn("use staging")

            connectUseCase.forceState(ConnectionState.Reconnecting)
            testScheduler.runCurrent()

            // No reply can arrive on a dead socket, and whether the host queued
            // it is unknown — so it must not keep saying "Sending…".
            assertEquals(UserMessageState.FAILED, userMessage("use staging").state)

            // And a late ACK does not revive it: nothing is awaiting one now.
            connectUseCase.emit(ChatEvent.RuntimeInputAcknowledged(sessionId = "sess-1"))
            testScheduler.runCurrent()
            assertEquals(UserMessageState.FAILED, userMessage("use staging").state)
        }

    @Test
    fun `an ACK with no message awaiting it is ignored`() =
        runTest(testDispatcher) {
            val address = validHexAddress()
            vm.connectToAgent(address)
            testScheduler.runCurrent()
            connectUseCase.markConnected(address)
            vm.sendMessage("hello")
            testScheduler.runCurrent()

            connectUseCase.emit(ChatEvent.RuntimeInputAcknowledged(sessionId = "sess-1"))
            connectUseCase.emit(ChatEvent.RuntimeInputRejected(sessionId = "sess-1", retryable = true))
            testScheduler.runCurrent()

            assertEquals(UserMessageState.SENT, userMessage("hello").state)
            assertEquals(
                "and nothing was sent again",
                listOf("hello"),
                connectUseCase.sentMessages.map { it.content }
            )
        }

    // ── clearChat ──────────────────────────────────────────────────────

    @Test
    fun `clearChat empties chatItems and new messages still mint distinct ids`() = runTest(testDispatcher) {
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)

        vm.sendMessage("one")
        vm.sendMessage("two")
        vm.sendMessage("three")
        assertEquals(3, vm.uiState.value.chatItems.size)
        val idsBeforeClear = vm.uiState.value.chatItems.map { it.id }.toSet()

        vm.clearChat()
        // clearChat awaits the storage save before wiping the UI; let
        // the suspending body finish.
        testScheduler.runCurrent()

        assertTrue("chatItems must be empty after clearChat", vm.uiState.value.chatItems.isEmpty())

        // Message ids are UUIDs (see ChatViewModel.sendMessage) — no
        // per-instance counter to "reset" anymore, so just confirm the
        // next message mints a fresh id, not one reused from before.
        vm.sendMessage("again")
        val newItems = vm.uiState.value.chatItems
        assertEquals(1, newItems.size)
        assertTrue(newItems[0].id !in idsBeforeClear)
    }

    @Test
    fun `clearChat persists the snapshot of current ids to storage`() = runTest(testDispatcher) {
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)

        vm.sendMessage("hi")
        val firstId = vm.uiState.value.chatItems[0].id
        vm.clearChat()
        testScheduler.runCurrent()

        val saved = storage.loadAll()[address]
        assertNotNull("Storage must have an entry for the current agent", saved)
        assertTrue(
            "Persisted ids must contain the cleared user bubble id",
            firstId in saved!!
        )
    }

    @Test
    fun `clearChat calls reset on the use case before wiping the UI`() = runTest(testDispatcher) {
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()

        vm.clearChat()
        testScheduler.runCurrent()

        assertTrue(
            "clearChat must reset the use case (suppresses session_sync replay)",
            connectUseCase.resetCount >= 1
        )
    }

    @Test
    fun `opening another conversation while Clear chat is still running leaves that conversation alone`() =
        runTest(testDispatcher) {
            // Clear chat waits on storage between its steps. A conversation
            // opened in the meantime used to be the one deleted, hidden and
            // blanked, because each step read the active conversation afresh.
            val address = validHexAddress()
            val kept = seedPreviousConversation(address)
            vm.connectToAgent(address)
            testScheduler.runCurrent()
            vm.startNewSession()
            testScheduler.runCurrent()
            // Its first message is what saves a new conversation.
            vm.sendMessage("to be cleared")
            testScheduler.runCurrent()
            val cleared = vm.activeSessionId.value
            assertTrue("test setup: a second conversation is open", cleared != null && cleared != kept.id)
            assertTrue("test setup: it has a saved message", messageRepo.snapshot().any { it.sessionId == cleared })
            val gate = CompletableDeferred<Unit>()
            connectUseCase.resetGate = gate

            vm.clearChat()
            testScheduler.runCurrent()
            vm.switchToSession(kept.id)
            testScheduler.runCurrent()
            gate.complete(Unit)
            testScheduler.runCurrent()

            assertEquals(kept.id, vm.activeSessionId.value)
            assertEquals(
                "the conversation opened is still on screen",
                listOf("hi", "hello"),
                vm.uiState.value.chatItems.map {
                    when (it) {
                        is ChatItem.User -> it.content
                        is ChatItem.Turn -> it.agent?.content.orEmpty()
                        else -> ""
                    }
                }
            )
            assertEquals(
                "and still saved",
                listOf("m1", "m2"),
                messageRepo.snapshot().filter { it.sessionId == kept.id }.map { it.id }
            )
            assertTrue(
                "the cleared one is what was deleted",
                messageRepo.snapshot().none { it.sessionId == cleared }
            )
            assertFalse(
                "and only its ids are ignored, not the opened conversation's",
                storage.loadAll()[address].orEmpty().contains("m1")
            )
        }

    // ── ignoredIds semantics ───────────────────────────────────────────

    @Test
    fun `ChatItemReceived after clearChat with same id is filtered out`() = runTest(testDispatcher) {
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)

        // Round 1: receive an item, then clear.
        connectUseCase.emit(
            ChatEvent.ChatItemReceived(ChatItem.Agent(id = "stable-id-1", content = "first reply"))
        )
        testScheduler.runCurrent()
        assertEquals(1, vm.uiState.value.chatItems.size)
        vm.clearChat()
        testScheduler.runCurrent()
        assertTrue(vm.uiState.value.chatItems.isEmpty())

        // Round 2: server replays the SAME item (e.g. session_sync on
        // reconnect). Must be suppressed by the ignored-ids set.
        connectUseCase.emit(
            ChatEvent.ChatItemReceived(ChatItem.Agent(id = "stable-id-1", content = "first reply"))
        )
        testScheduler.runCurrent()

        assertTrue(
            "Replay of a cleared id must NOT re-appear in chatItems",
            vm.uiState.value.chatItems.isEmpty()
        )
    }

    @Test
    fun `a Work Room run still working keeps Stop showing until it ends`() = runTest(testDispatcher) {
        // W16AAPPLE-33 review: the run replaces the codex tool card, the only
        // other item in flight, so Send came back for the whole run.
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)
        appSettings.setStreamingResponses(false)
        connectUseCase.emit(ChatEvent.ChatItemReceived(ChatItem.ToolCall(id = "call-1", name = "codex")))
        testScheduler.runCurrent()
        assertTrue("premise: the codex tool card is in flight", vm.isAgentWorking.value)
        val run = ChatItem.ProviderInvocation(id = "codex:call-1", parentToolCallId = "call-1", provider = "codex", stateRevision = 1)

        connectUseCase.emit(ChatEvent.ChatItemUpdated(run))
        testScheduler.runCurrent()
        assertTrue("a working run is work in flight", vm.isAgentWorking.value)
        assertEquals("but its card is not hidden with streaming off", listOf("codex:call-1"), vm.uiState.value.chatItems.map { it.id })

        connectUseCase.emit(ChatEvent.ChatItemUpdated(run.copy(status = ProviderInvocationStatus.COMPLETED, stateRevision = 2)))
        testScheduler.runCurrent()
        assertFalse(vm.isAgentWorking.value)
    }

    @Test
    fun `a Work Room message goes out at the run's revision and its draft clears once the Host accepts it`() = runTest(testDispatcher) {
        // W16AAPPLE-35.
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)
        val run = ChatItem.ProviderInvocation(id = "codex:call-1", parentToolCallId = "call-1", provider = "codex", stateRevision = 3)
        connectUseCase.emit(ChatEvent.ChatItemUpdated(run))
        connectUseCase.emit(ChatEvent.ChatItemUpdated(run.copy(stateRevision = 4)))
        testScheduler.runCurrent()

        vm.editWorkRoomDraft("codex:call-1", "Use the existing auth helper")
        vm.sendWorkRoomInput("codex:call-1")
        testScheduler.runCurrent()

        val sent = connectUseCase.providerInputs.single()
        assertEquals("codex:call-1", sent.invocationId)
        assertEquals("the newest revision the screen holds", 4L, sent.stateRevision)
        assertEquals("Use the existing auth helper", sent.text)
        assertEquals(true, vm.workRoomDraft("codex:call-1").sending)

        connectUseCase.emit(ChatEvent.ProviderInputAcked(ProviderInputAck(sent.requestId, "codex:call-1", accepted = true, stateRevision = 4)))
        testScheduler.runCurrent()

        assertEquals(WorkRoomDraft(), vm.workRoomDraft("codex:call-1"))
    }

    @Test
    fun `a Work Room permission change goes out at the run's revision, and the Host's answer and new mode land`() = runTest(testDispatcher) {
        // W16AAPPLE-43.
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)
        val permission = ai.openonion.oochat.domain.model.ProviderPermissionState(
            provider = "codex",
            activeOptionId = "codex:workspace-ask",
            options = listOf(
                ai.openonion.oochat.domain.model.ProviderPermissionOption("codex:read-only", "Read Only", selectable = true),
                ai.openonion.oochat.domain.model.ProviderPermissionOption("codex:workspace-ask", "Ask for approval", selectable = true)
            )
        )
        val run = ChatItem.ProviderInvocation(id = "codex:call-1", provider = "codex", stateRevision = 4, providerPermission = permission)
        connectUseCase.emit(ChatEvent.ChatItemUpdated(run))
        testScheduler.runCurrent()

        vm.chooseWorkRoomPermission("codex:call-1", "codex:read-only")
        testScheduler.runCurrent()

        val sent = connectUseCase.providerPermissionChanges.single()
        assertEquals("codex:call-1", sent.invocationId)
        assertEquals(4L, sent.stateRevision)
        assertEquals("codex:read-only", sent.optionId)
        assertEquals(false, sent.confirmRisk)

        connectUseCase.emit(
            ChatEvent.ProviderPermissionAcked(
                ai.openonion.oochat.domain.model.ProviderPermissionAck(sent.requestId, "codex:call-1", accepted = true, stateRevision = 5)
            )
        )
        // The Host then sends the run at its new revision, which is what moves the mode shown.
        connectUseCase.emit(
            ChatEvent.ChatItemUpdated(run.copy(stateRevision = 5, providerPermission = permission.copy(activeOptionId = "codex:read-only")))
        )
        testScheduler.runCurrent()

        assertEquals(ai.openonion.oochat.ui.workroom.WorkRoomPermissionUi(), vm.workRoomPermissionControl("codex:call-1"))
        val shown = vm.uiState.value.chatItems.single() as ChatItem.ProviderInvocation
        assertEquals("codex:read-only", shown.providerPermission?.activeOptionId)
    }

    @Test
    fun `a Work Room Stop goes out only once confirmed, for that run alone, and settles on the Host's answer`() = runTest(testDispatcher) {
        // W16AAPPLE-37.
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)
        val run = ChatItem.ProviderInvocation(id = "codex:call-1", provider = "codex", stateRevision = 4)
        connectUseCase.emit(ChatEvent.ChatItemUpdated(run))
        testScheduler.runCurrent()

        vm.requestWorkRoomStop("codex:call-1")
        testScheduler.runCurrent()
        assertTrue("the first tap only opens the confirmation", connectUseCase.providerStops.isEmpty())
        vm.confirmWorkRoomStop("codex:call-1")
        testScheduler.runCurrent()

        val stop = connectUseCase.providerStops.single()
        assertEquals("codex:call-1", stop.invocationId)
        assertEquals(4L, stop.stateRevision)
        assertEquals("the agent's own turn is not interrupted", 0, connectUseCase.interruptInvocations)

        connectUseCase.emit(
            ChatEvent.ProviderStopAcked(ai.openonion.oochat.domain.model.ProviderStopAck(stop.requestId, "codex:call-1", accepted = true))
        )
        testScheduler.runCurrent()
        assertEquals(
            ai.openonion.oochat.domain.model.WorkRoomStopState.Stopping,
            vm.workRoomStop("codex:call-1").state
        )
    }

    @Test
    fun `a refused Work Room message stays in its draft with the reason`() = runTest(testDispatcher) {
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)
        connectUseCase.emit(
            ChatEvent.ChatItemUpdated(ChatItem.ProviderInvocation(id = "codex:call-1", provider = "codex", stateRevision = 4))
        )
        testScheduler.runCurrent()
        vm.editWorkRoomDraft("codex:call-1", "Use the existing auth helper")
        vm.sendWorkRoomInput("codex:call-1")
        testScheduler.runCurrent()
        val requestId = connectUseCase.providerInputs.single().requestId

        connectUseCase.emit(
            ChatEvent.ProviderInputAcked(ProviderInputAck(requestId, "codex:call-1", accepted = false, reason = "not_active"))
        )
        testScheduler.runCurrent()

        val draft = vm.workRoomDraft("codex:call-1")
        assertEquals("Use the existing auth helper", draft.text)
        assertEquals(false, draft.sending)
        assertEquals(WorkRoomText.inputRefused("not_active"), draft.error)
        assertEquals("the chat is not touched", listOf("codex:call-1"), vm.uiState.value.chatItems.map { it.id })
    }

    @Test
    fun `Work Room drafts with the same invocation id stay with their conversation`() = runTest(testDispatcher) {
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)
        testScheduler.runCurrent()

        vm.sendMessage("conversation A")
        testScheduler.runCurrent()
        val sessionA = vm.activeSessionId.value!!
        vm.editWorkRoomDraft("codex:call-1", "draft for A")

        vm.startNewSession()
        testScheduler.runCurrent()
        vm.sendMessage("conversation B")
        testScheduler.runCurrent()
        assertNotEquals(sessionA, vm.activeSessionId.value)
        assertEquals(WorkRoomDraft(), vm.workRoomDraft("codex:call-1"))

        vm.editWorkRoomDraft("codex:call-1", "draft for B")
        vm.switchToSession(sessionA)
        testScheduler.runCurrent()

        assertEquals("draft for A", vm.workRoomDraft("codex:call-1").text)
    }

    @Test
    fun `clearing a chat during a Work Room run keeps the run's late codex tool card away`() = runTest(testDispatcher) {
        // W16AAPPLE-33: the run replaced its parent ToolCall, so the parent's id
        // was never on screen; the clear must still ignore it, or the outer
        // tool_result that lands when the run ends brings a lone card back.
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)
        connectUseCase.emit(
            ChatEvent.ChatItemUpdated(
                ChatItem.ProviderInvocation(id = "codex:call-1", parentToolCallId = "call-1", provider = "codex", stateRevision = 1)
            )
        )
        testScheduler.runCurrent()
        vm.clearChat()
        testScheduler.runCurrent()

        connectUseCase.emit(
            ChatEvent.ChatItemUpdated(ChatItem.ToolCall(id = "call-1", name = "codex", status = ToolStatus.DONE, result = "done"))
        )
        testScheduler.runCurrent()

        assertTrue(vm.uiState.value.chatItems.isEmpty())
    }

    @Test
    fun `clearChat in agent A does not filter replays in agent B`() = runTest(testDispatcher) {
        val addressA = validHexAddress(0xA)
        val addressB = validHexAddress(0xB)

        // Connect to A, receive an item, clear.
        vm.connectToAgent(addressA)
        testScheduler.runCurrent()
        connectUseCase.markConnected(addressA)
        connectUseCase.emit(
            ChatEvent.ChatItemReceived(ChatItem.Agent(id = "shared-id", content = "from-A"))
        )
        testScheduler.runCurrent()
        vm.clearChat()
        testScheduler.runCurrent()

        // Switch to agent B and replay the same id. Must NOT be filtered
        // (the clear was scoped to agent A).
        vm.connectToAgent(addressB)
        testScheduler.runCurrent()
        connectUseCase.markConnected(addressB)
        connectUseCase.emit(
            ChatEvent.ChatItemReceived(ChatItem.Agent(id = "shared-id", content = "from-B"))
        )
        testScheduler.runCurrent()

        val items = vm.uiState.value.chatItems
        assertEquals(
            "Cross-agent isolation: B's reply with the same id must render",
            1,
            items.size
        )
        assertEquals("from-B", (items[0] as ChatItem.Agent).content)
    }

    // ── ChatItemReceived / ChatItemUpdated / OutputReceived ────────────

    @Test
    fun `ChatItemReceived appends the item to chatItems`() = runTest(testDispatcher) {
        vm.connectToAgent(validHexAddress())
        testScheduler.runCurrent()

        connectUseCase.emit(
            ChatEvent.ChatItemReceived(ChatItem.Agent(id = "a1", content = "hi"))
        )
        testScheduler.runCurrent()

        val items = vm.uiState.value.chatItems
        assertEquals(1, items.size)
        assertEquals("hi", (items[0] as ChatItem.Agent).content)
    }

    @Test
    fun `agentReplyArrived fires exactly once for a genuinely new agent item, not at all for one already persisted`() =
        runTest(testDispatcher) {
            val address = validHexAddress()
            vm.connectToAgent(address)
            testScheduler.runCurrent()
            connectUseCase.markConnected(address)

            val received = mutableListOf<Unit>()
            val job = launch { vm.agentReplyArrived.collect { received.add(Unit) } }
            testScheduler.runCurrent()

            // Genuinely new id — not yet in the fake Room repository.
            connectUseCase.emit(ChatEvent.ChatItemReceived(ChatItem.Agent(id = "new-1", content = "hello")))
            testScheduler.runCurrent()
            assertEquals(1, received.size)

            // Same id re-delivered (session_sync replay) — already persisted
            // by the first emission above, so this must NOT fire again.
            connectUseCase.emit(ChatEvent.ChatItemReceived(ChatItem.Agent(id = "new-1", content = "hello")))
            testScheduler.runCurrent()
            assertEquals(1, received.size)

            job.cancel()
        }

    @Test
    fun `agentReplyArrived does not fire for a user item or a blank-content agent marker`() = runTest(testDispatcher) {
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)

        val received = mutableListOf<Unit>()
        val job = launch { vm.agentReplyArrived.collect { received.add(Unit) } }
        testScheduler.runCurrent()

        connectUseCase.emit(ChatEvent.ChatItemReceived(ChatItem.User(id = "u1", content = "hi from me")))
        testScheduler.runCurrent()
        connectUseCase.emit(ChatEvent.ChatItemReceived(ChatItem.Agent(id = "blank-1", content = "")))
        testScheduler.runCurrent()

        assertEquals(0, received.size)
        job.cancel()
    }

    // ── maybeNotifyAgentReply gating ────────────────────────────────────
    // All three must hold before a system notification is posted: the app
    // is backgrounded, the pref is on, and the OS permission is granted.
    // agentReplyArrived (haptics/sound) above fires regardless — these tests
    // only cover the separate agentReplyNotifier call.

    @Test
    fun `does not post while the app is foregrounded, even with the setting on and permission granted`() =
        runTest(testDispatcher) {
            val address = validHexAddress()
            vm.connectToAgent(address)
            testScheduler.runCurrent()
            connectUseCase.markConnected(address)

            appInForeground = true
            appSettings.setPushNotificationsEnabled(true)
            notificationPermissionGranted = true

            connectUseCase.emit(ChatEvent.ChatItemReceived(ChatItem.Agent(id = "new-1", content = "hello")))
            testScheduler.runCurrent()

            assertEquals(emptyList<Pair<String?, String>>(), agentReplyNotifier.posted)
        }

    @Test
    fun `does not post when the push notifications setting is off, even backgrounded with permission granted`() =
        runTest(testDispatcher) {
            val address = validHexAddress()
            vm.connectToAgent(address)
            testScheduler.runCurrent()
            connectUseCase.markConnected(address)

            appInForeground = false
            appSettings.setPushNotificationsEnabled(false)
            notificationPermissionGranted = true

            connectUseCase.emit(ChatEvent.ChatItemReceived(ChatItem.Agent(id = "new-1", content = "hello")))
            testScheduler.runCurrent()

            assertEquals(emptyList<Pair<String?, String>>(), agentReplyNotifier.posted)
        }

    @Test
    fun `does not post when backgrounded and the setting is on but the OS permission is denied`() =
        runTest(testDispatcher) {
            val address = validHexAddress()
            vm.connectToAgent(address)
            testScheduler.runCurrent()
            connectUseCase.markConnected(address)

            appInForeground = false
            appSettings.setPushNotificationsEnabled(true)
            notificationPermissionGranted = false

            connectUseCase.emit(ChatEvent.ChatItemReceived(ChatItem.Agent(id = "new-1", content = "hello")))
            testScheduler.runCurrent()

            assertEquals(emptyList<Pair<String?, String>>(), agentReplyNotifier.posted)
        }

    @Test
    fun `posts exactly once when backgrounded, setting on, and permission granted`() = runTest(testDispatcher) {
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)

        appInForeground = false
        appSettings.setPushNotificationsEnabled(true)
        notificationPermissionGranted = true

        connectUseCase.emit(ChatEvent.ChatItemReceived(ChatItem.Agent(id = "new-1", content = "hello")))
        testScheduler.runCurrent()

        assertEquals(1, agentReplyNotifier.posted.size)
        assertEquals("hello", agentReplyNotifier.posted[0].second)
    }

    @Test
    fun `onReturnedToForeground clears any posted reply notification`() = runTest(testDispatcher) {
        vm.onReturnedToForeground()
        assertEquals(1, agentReplyNotifier.clearCalls)
    }

    // ── Approval notifications (W16AAPPLE-44) ───────────────────────────

    private fun approval(id: String = "ap-1") = ChatItem.ApprovalNeeded(
        id = id,
        tool = "bash",
        arguments = mapOf("command" to "rm -rf build"),
        requestId = "req-$id"
    )

    private fun TestScope.connectedInBackground(): String {
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)
        testScheduler.runCurrent()
        appInForeground = false
        notificationPermissionGranted = true
        return address
    }

    @Test
    fun `an approval that arrives in the background posts a notification that opens its conversation`() =
        runTest(testDispatcher) {
            val address = connectedInBackground()
            appSettings.setPushNotificationsEnabled(true)

            connectUseCase.emit(ChatEvent.ChatItemReceived(approval()))
            testScheduler.runCurrent()

            val posted = agentReplyNotifier.approvals.single()
            assertEquals("ap-1", posted.approvalId)
            assertEquals("bash: rm -rf build", posted.summary)
            assertEquals(address, posted.target.agentAddress)
            assertEquals(vm.activeSessionId.value, posted.target.sessionId)
            assertNull("not a Work Room approval", posted.target.invocationId)
        }

    @Test
    fun `a Work Room approval opens the Work Room and says what the run wants`() = runTest(testDispatcher) {
        connectedInBackground()
        appSettings.setPushNotificationsEnabled(true)
        val workRoom = WorkRoomApproval(
            provider = "codex",
            invocationId = "inv:1",
            parentToolCallId = "call-1",
            presentation = ProviderApprovalPresentation(
                action = "Edit app/main.kt",
                scope = "workroom",
                reason = "",
                boundary = ApprovalBoundary.WORKROOM,
                allowOnce = true,
                allowSession = false
            )
        )

        connectUseCase.emit(ChatEvent.ChatItemReceived(approval().copy(workRoom = workRoom)))
        testScheduler.runCurrent()

        val posted = agentReplyNotifier.approvals.single()
        assertEquals("inv:1", posted.target.invocationId)
        assertEquals("Codex wants to: Edit app/main.kt", posted.summary)
    }

    @Test
    fun `an approval posts nothing in the foreground, with the setting off, or without the permission`() =
        runTest(testDispatcher) {
            connectedInBackground()

            appInForeground = true
            appSettings.setPushNotificationsEnabled(true)
            connectUseCase.emit(ChatEvent.ChatItemReceived(approval("ap-fg")))
            testScheduler.runCurrent()

            appInForeground = false
            appSettings.setPushNotificationsEnabled(false)
            connectUseCase.emit(ChatEvent.ChatItemReceived(approval("ap-off")))
            testScheduler.runCurrent()

            appSettings.setPushNotificationsEnabled(true)
            notificationPermissionGranted = false
            connectUseCase.emit(ChatEvent.ChatItemReceived(approval("ap-denied")))
            testScheduler.runCurrent()

            assertEquals(emptyList<PendingApproval>(), agentReplyNotifier.approvals)
        }

    @Test
    fun `an approval replayed after a reconnect, or rebuilt from a snapshot, posts no second notification`() =
        runTest(testDispatcher) {
            connectedInBackground()
            appSettings.setPushNotificationsEnabled(true)

            connectUseCase.emit(ChatEvent.ChatItemReceived(approval()))
            testScheduler.runCurrent()
            connectUseCase.emit(ChatEvent.ChatItemReceived(approval()))
            testScheduler.runCurrent()
            // A snapshot carries no decision: it could be one answered long ago.
            connectUseCase.emit(ChatEvent.ChatItemReceived(approval("ap-old"), fromSessionSnapshot = true))
            testScheduler.runCurrent()

            assertEquals(listOf("ap-1"), agentReplyNotifier.approvals.map { it.approvalId })
        }

    @Test
    fun `answering an approval clears its notification`() = runTest(testDispatcher) {
        connectedInBackground()
        appSettings.setPushNotificationsEnabled(true)
        connectUseCase.emit(ChatEvent.ChatItemReceived(approval()))
        testScheduler.runCurrent()

        vm.respondToApproval("ap-1", approved = true)
        testScheduler.runCurrent()

        assertEquals(listOf("ap-1"), agentReplyNotifier.clearedApprovals)
    }

    @Test
    fun `the turn ending clears its approval notifications, as another device may have answered`() =
        runTest(testDispatcher) {
            connectedInBackground()
            appSettings.setPushNotificationsEnabled(true)
            connectUseCase.emit(ChatEvent.ChatItemReceived(approval()))
            testScheduler.runCurrent()

            connectUseCase.emit(ChatEvent.OutputReceived(result = "done", session = null))
            testScheduler.runCurrent()

            assertEquals(listOf("ap-1"), agentReplyNotifier.clearedApprovals)
        }

    @Test
    fun `coming back to the chat clears the notifications for the approvals it shows`() = runTest(testDispatcher) {
        connectedInBackground()
        appSettings.setPushNotificationsEnabled(true)
        connectUseCase.emit(ChatEvent.ChatItemReceived(approval()))
        testScheduler.runCurrent()

        vm.onReturnedToForeground()

        assertEquals(listOf("ap-1"), agentReplyNotifier.clearedApprovals)
    }

    @Test
    fun `the chat offers approval notifications once, while an approval waits and they are off`() =
        runTest(testDispatcher) {
            connectedInBackground()
            appSettings.approvalOfferShownFlow.value = false
            appSettings.setPushNotificationsEnabled(false)
            testScheduler.runCurrent()
            assertFalse("nothing to approve yet", vm.approvalNotificationOffer.value)

            connectUseCase.emit(ChatEvent.ChatItemReceived(approval()))
            testScheduler.runCurrent()
            assertTrue(vm.approvalNotificationOffer.value)

            vm.acceptApprovalNotificationOffer()
            testScheduler.runCurrent()

            assertTrue(appSettings.pushNotificationsEnabled.first())
            assertTrue(appSettings.approvalNotificationOfferShown.first())
            assertFalse(vm.approvalNotificationOffer.value)
        }

    @Test
    fun `not now ends the offer without turning notifications on`() = runTest(testDispatcher) {
        connectedInBackground()
        appSettings.approvalOfferShownFlow.value = false
        appSettings.setPushNotificationsEnabled(false)
        connectUseCase.emit(ChatEvent.ChatItemReceived(approval()))
        testScheduler.runCurrent()
        assertTrue(vm.approvalNotificationOffer.value)

        vm.dismissApprovalNotificationOffer()
        testScheduler.runCurrent()

        assertFalse(vm.approvalNotificationOffer.value)
        assertFalse(appSettings.pushNotificationsEnabled.first())
    }

    @Test
    fun `nothing is offered when notifications are already on and allowed`() = runTest(testDispatcher) {
        connectedInBackground()
        appSettings.approvalOfferShownFlow.value = false
        appSettings.setPushNotificationsEnabled(true)
        connectUseCase.emit(ChatEvent.ChatItemReceived(approval()))
        testScheduler.runCurrent()

        assertFalse(vm.approvalNotificationOffer.value)
    }

    @Test
    fun `ChatItemUpdated replaces existing item by id`() = runTest(testDispatcher) {
        vm.connectToAgent(validHexAddress())
        testScheduler.runCurrent()

        connectUseCase.emit(
            ChatEvent.ChatItemReceived(
                ChatItem.Thinking(id = "t1", status = ThinkingStatus.RUNNING)
            )
        )
        testScheduler.runCurrent()
        connectUseCase.emit(
            ChatEvent.ChatItemUpdated(
                ChatItem.Thinking(id = "t1", status = ThinkingStatus.DONE, durationMs = 123.0)
            )
        )
        testScheduler.runCurrent()

        val items = vm.uiState.value.chatItems
        assertEquals(1, items.size)
        val thinking = items[0] as ChatItem.Thinking
        assertEquals(ThinkingStatus.DONE, thinking.status)
        assertEquals(123.0, thinking.durationMs)
    }

    @Test
    fun `ChatItemUpdated appends when no item with the same id exists yet`() = runTest(testDispatcher) {
        // Fallback path: server emits an update frame without a
        // preceding "received" (e.g. llm_result for a Thinking bubble
        // we never observed locally).
        vm.connectToAgent(validHexAddress())
        testScheduler.runCurrent()

        connectUseCase.emit(
            ChatEvent.ChatItemUpdated(
                ChatItem.Thinking(id = "ghost", status = ThinkingStatus.DONE)
            )
        )
        testScheduler.runCurrent()

        val items = vm.uiState.value.chatItems
        assertEquals(
            "Update-without-prior-receive should still land as a single bubble",
            1,
            items.size
        )
        assertEquals("ghost", items[0].id)
    }

    // ── Streaming responses toggle ──────────────────────────────────────

    @Test
    fun `disabling streaming hides in-progress items and they reappear when re-enabled`() = runTest(testDispatcher) {
        vm.connectToAgent(validHexAddress())
        testScheduler.runCurrent()

        connectUseCase.emit(ChatEvent.ChatItemReceived(ChatItem.Thinking(id = "t1", status = ThinkingStatus.RUNNING)))
        connectUseCase.emit(ChatEvent.ChatItemReceived(ChatItem.ToolCall(id = "tc1", name = "search", status = ToolStatus.RUNNING)))
        connectUseCase.emit(ChatEvent.ChatItemReceived(ChatItem.Agent(id = "a1", content = "done reply")))
        testScheduler.runCurrent()
        assertEquals(3, vm.uiState.value.chatItems.size)

        appSettings.setStreamingResponses(false)
        testScheduler.runCurrent()

        val visible = vm.uiState.value.chatItems
        assertEquals(
            "only the finished Agent item should remain once streaming is off",
            listOf("a1"),
            visible.map { it.id }
        )

        appSettings.setStreamingResponses(true)
        testScheduler.runCurrent()

        assertEquals(
            "all items reappear once streaming is re-enabled",
            setOf("t1", "tc1", "a1"),
            vm.uiState.value.chatItems.map { it.id }.toSet()
        )
    }

    @Test
    fun `disabling streaming does not hide a finished ToolCall or a Turn with a completed agent reply`() =
        runTest(testDispatcher) {
            vm.connectToAgent(validHexAddress())
            testScheduler.runCurrent()

            connectUseCase.emit(ChatEvent.ChatItemReceived(ChatItem.ToolCall(id = "tc1", name = "search", status = ToolStatus.DONE)))
            connectUseCase.emit(
                ChatEvent.ChatItemReceived(
                    ChatItem.Turn(id = "turn1", agent = ChatItem.Agent(id = "a1", content = "finished"))
                )
            )
            testScheduler.runCurrent()

            appSettings.setStreamingResponses(false)
            testScheduler.runCurrent()

            assertEquals(
                "finished work must stay visible even with streaming off",
                setOf("tc1", "turn1"),
                vm.uiState.value.chatItems.map { it.id }.toSet()
            )
        }

    @Test
    fun `OutputReceived creates a Turn item when no matching bubble exists`() = runTest(testDispatcher) {
        vm.connectToAgent(validHexAddress())
        testScheduler.runCurrent()

        connectUseCase.emit(ChatEvent.OutputReceived(result = "  hello  ", session = null))
        testScheduler.runCurrent()

        val items = vm.uiState.value.chatItems
        assertEquals(1, items.size)
        val turn = items[0] as ChatItem.Turn
        assertNotNull(turn.agent)
        assertEquals("hello", turn.agent!!.content)
    }

    @Test
    fun `OutputReceived with already-rendered content does not duplicate the bubble`() = runTest(testDispatcher) {
        vm.connectToAgent(validHexAddress())
        testScheduler.runCurrent()

        // Seed an existing Agent bubble with the same content.
        connectUseCase.emit(
            ChatEvent.ChatItemReceived(ChatItem.Agent(id = "x", content = "hello"))
        )
        testScheduler.runCurrent()

        // Output for the same content must NOT add another bubble.
        connectUseCase.emit(ChatEvent.OutputReceived(result = "hello", session = null))
        testScheduler.runCurrent()

        val agentItems = vm.uiState.value.chatItems.filterIsInstance<ChatItem.Agent>()
        val turnItems = vm.uiState.value.chatItems.filterIsInstance<ChatItem.Turn>()
        assertEquals("Only the original Agent bubble should remain", 1, agentItems.size)
        assertTrue("No duplicate Turn should be appended", turnItems.isEmpty())
    }

    @Test
    fun `OutputReceived after clearChat with same content is suppressed`() = runTest(testDispatcher) {
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)

        // Round 1: receive the output, then clear.
        connectUseCase.emit(ChatEvent.OutputReceived(result = "hello", session = null))
        testScheduler.runCurrent()
        assertEquals(1, vm.uiState.value.chatItems.size)
        vm.clearChat()
        testScheduler.runCurrent()
        assertTrue(vm.uiState.value.chatItems.isEmpty())

        // Round 2: server replays the same OUTPUT. Suppressed.
        connectUseCase.emit(ChatEvent.OutputReceived(result = "hello", session = null))
        testScheduler.runCurrent()
        assertTrue(
            "Replayed OUTPUT for cleared content must NOT re-appear",
            vm.uiState.value.chatItems.isEmpty()
        )
    }

    // ── Connection-state reactions ─────────────────────────────────────

    @Test
    fun `ConnectionState Error (a genuine fatal connection failure) with nothing in flight appends a standalone failed item`() = runTest(testDispatcher) {
        // ConnectionState.Error now only reaches ChatViewModel for a real
        // transport/connect failure (e.g. ConnectionState.Error.fromException) —
        // a server-sent "ERROR" business-logic frame (credits, etc.) goes
        // through ChatEvent.ConnectionErrorOccurred instead (see below) since
        // it doesn't mean the connection actually died. Either way, no prior
        // RUNNING Thinking/Turn reads as part of the conversation (a
        // synthesized failed Thinking item) rather than a uiState.error
        // banner/snackbar floating outside the message list.
        connectUseCase.forceState(ConnectionState.Error(message = "boom"))
        testScheduler.runCurrent()

        assertNull("uiState.error is no longer used for this path", vm.uiState.value.error)
        val failed = vm.uiState.value.chatItems.filterIsInstance<ChatItem.Thinking>()
            .find { it.status == ThinkingStatus.ERROR }
        assertNotNull("Expected a synthesized failed Thinking item", failed)
        assertEquals("boom", failed?.content)
    }

    @Test
    fun `ConnectionState Error with a RUNNING Thinking bubble flips it to ERROR with the error message`() = runTest(testDispatcher) {
        val thinkingId = "thinking-1"
        connectUseCase.emit(
            ChatEvent.ChatItemReceived(ChatItem.Thinking(id = thinkingId, status = ThinkingStatus.RUNNING, model = "gemini-3-flash-preview"))
        )
        testScheduler.runCurrent()

        connectUseCase.forceState(ConnectionState.Error(message = "TLS handshake failed"))
        testScheduler.runCurrent()

        assertNull(vm.uiState.value.error)
        val thinking = vm.uiState.value.chatItems.filterIsInstance<ChatItem.Thinking>().single { it.id == thinkingId }
        assertEquals(ThinkingStatus.ERROR, thinking.status)
        assertEquals("TLS handshake failed", thinking.content)
    }

    // ── W16AAPPLE-74: an error already current when the screen is created ──

    /** A second chat screen on the same shared connection, as recreating the screen makes. */
    private fun newChatViewModel() = ChatViewModel(
        application = Application(),
        agentDiscovery = AgentDiscoveryService(),
        connectUseCase = connectUseCase,
        agentRepository = agentRepo,
        configRepository = configRepo,
        ignoredIdsManager = IgnoredIdsManager(storage),
        imageAttachmentStore = imageStore,
        fileAttachmentStore = fileStore,
        voiceRecorderStore = voiceRecorderStore,
        voiceTranscriptionService = voiceTranscriptionService,
        speechRecognitionService = speechRecognition,
        appSettings = appSettings,
        messageRepository = messageRepo,
        conversationHistory = ConversationHistoryUseCase(
            agentRepo, sessionRepo, messageRepo,
            FakePersistenceTransaction(messageRepo, sessionRepo)
        ),
        recentChatsSync = recentChatsSync,
        initialMyAddress = "0xTEST",
        networkMonitor = networkMonitor,
        agentReplyNotifier = agentReplyNotifier,
        requiresNotificationPermission = true,
        hasNotificationPermission = { notificationPermissionGranted },
        isAppInForeground = { appInForeground },
        controlCenterDispatcher = testDispatcher,
    )

    private fun ChatViewModel.errorItems() =
        uiState.value.chatItems.filterIsInstance<ChatItem.Thinking>().filter { it.status == ThinkingStatus.ERROR }

    @Test
    fun `an error the shared connection already had is not added again by a newly created chat screen`() = runTest(testDispatcher) {
        connectUseCase.forceState(ConnectionState.Error(message = "boom"))
        testScheduler.runCurrent()

        val recreated = newChatViewModel()
        testScheduler.runCurrent()

        assertEquals("the old error is the banner's to show, not a new chat item", emptyList<ChatItem>(), recreated.errorItems())
    }

    @Test
    fun `an error that happens after the screen is created is still added, once`() = runTest(testDispatcher) {
        connectUseCase.forceState(ConnectionState.Error(message = "boom"))
        testScheduler.runCurrent()
        val recreated = newChatViewModel()
        testScheduler.runCurrent()

        connectUseCase.forceState(ConnectionState.Reconnecting)
        testScheduler.runCurrent()
        connectUseCase.forceState(ConnectionState.Error(message = "again"))
        testScheduler.runCurrent()

        assertEquals(listOf("again"), recreated.errorItems().map { it.content })
    }

    @Test
    fun `starting a new session fails a turn still running in the one being left`() = runTest(testDispatcher) {
        val thinkingId = "thinking-switch-1"
        connectUseCase.emit(
            ChatEvent.ChatItemReceived(ChatItem.Thinking(id = thinkingId, status = ThinkingStatus.RUNNING, model = "gemini-3-flash-preview"))
        )
        testScheduler.runCurrent()

        vm.startNewSession()
        testScheduler.runCurrent()

        // The item left with the previous conversation, so it is gone from the
        // cleared list — what matters is that it was resolved on the way out
        // and not persisted as RUNNING.
        assertTrue(
            "the conversation being left must not keep a RUNNING Thinking",
            vm.uiState.value.chatItems.none { it is ChatItem.Thinking && it.status == ThinkingStatus.RUNNING }
        )
    }

    @Test
    fun `ConnectionState Error for a stale-session rejection is not surfaced as a chat item`() = runTest(testDispatcher) {
        // ConnectToAgentUseCase.connect()'s first attempt deliberately lets
        // this specific error reach connectionState.value transiently (so its
        // own fast-fail-and-retry logic can detect it without waiting the
        // full timeout — see its own doc), then silently drops the stale
        // sessionId and reconnects fresh. That auto-recovery is meant to be
        // invisible to the user; before this check it left a permanent
        // "Session is already attached…" error card in the chat even though
        // the very next state transition is a normal Connected. Caught
        // on-device.
        connectUseCase.forceState(ConnectionState.Error(message = "Session is already attached to another connection"))
        testScheduler.runCurrent()

        assertNull(vm.uiState.value.error)
        assertTrue(
            "Stale-session errors must not become a visible chat item",
            vm.uiState.value.chatItems.none { it is ChatItem.Thinking && it.status == ThinkingStatus.ERROR }
        )
    }

    @Test
    fun `a refused protocol version reaches the chat once even when the Host's words look like a stale session`() = runTest(testDispatcher) {
        // W16AAPPLE-12: the message embeds the Host's own version text. A Host
        // that words it "session-already" must not trip the stale-session
        // suppression above and leave the user with no error at all.
        val message = ServerErrorText.incompatibleProtocol(hostProtocol = "oip/session-already", clientProtocol = "oip/0.1")
        assertTrue("test setup: the detector must be fooled by this text", StaleSessionDetector.isStaleSessionError(message))

        connectUseCase.forceState(ConnectionState.Error(message = message, retryable = false))
        testScheduler.runCurrent()

        val shown = vm.uiState.value.chatItems.filterIsInstance<ChatItem.Thinking>().filter { it.status == ThinkingStatus.ERROR }
        assertEquals(listOf(message), shown.map { it.content })
    }

    @Test
    fun `ChatEvent ConnectionErrorOccurred (eg Insufficient Credits) attaches to a RUNNING Turn without touching uiState error`() = runTest(testDispatcher) {
        // The actual path a server "ERROR" frame takes today (see
        // ConnectionRepositoryImpl) — deliberately NOT a ConnectionState
        // transition, since this class of error doesn't mean the socket
        // died (verified against production: PING/PONG continues after it).
        val turnId = "turn-1"
        connectUseCase.emit(
            ChatEvent.ChatItemReceived(ChatItem.Turn(id = turnId, thinking = ChatItem.Thinking(id = turnId, status = ThinkingStatus.RUNNING)))
        )
        testScheduler.runCurrent()

        connectUseCase.emit(ChatEvent.ConnectionErrorOccurred("Insufficient ConnectOnion Credits"))
        testScheduler.runCurrent()

        assertNull("A business-logic ERROR frame must not surface via uiState.error", vm.uiState.value.error)
        val turn = vm.uiState.value.chatItems.filterIsInstance<ChatItem.Turn>().single { it.id == turnId }
        assertEquals(ThinkingStatus.ERROR, turn.thinking?.status)
        assertEquals("Insufficient ConnectOnion Credits", turn.thinking?.content)
    }

    @Test
    fun `real event-collector cancellation does not surface as a uiState error`() = runTest(testDispatcher) {
        // Regression test for Phase 5: the event collector used to be a bare
        // try/catch(Exception), which caught CancellationException (e.g. from
        // the ViewModel's scope being cancelled mid-collect) and wrote a
        // bogus "Connection lost" error — right as the screen observing it
        // was being torn down. Now it's wrapped in runCatchingCancellable,
        // which rethrows CancellationException instead of reporting it.
        connectUseCase.emit(ChatEvent.Waiting) // prove the collector is alive first
        testScheduler.runCurrent()
        assertNull(vm.uiState.value.error)

        connectUseCase.throwCancellationOnNextEvent = true
        connectUseCase.emit(ChatEvent.Waiting)
        testScheduler.runCurrent()

        assertNull(
            "A real CancellationException from the collector must not populate uiState.error",
            vm.uiState.value.error
        )
    }

    @Test
    fun `clearError wipes uiState error`() {
        // Through a real error path rather than by touching _uiState: an
        // attachment that fails to store is the shortest one that does not
        // depend on connection state.
        vm.connectToAgent(validHexAddress())
        testSchedulerKeepRunning()
        connectUseCase.markConnected()
        imageStore.shouldFail = true
        vm.sendMessage("", images = listOf("content://broken"))
        testSchedulerKeepRunning()
        assertNotNull(vm.uiState.value.error)

        vm.clearError()
        assertNull("clearError must wipe the error", vm.uiState.value.error)
    }

    // ── Forwarders ─────────────────────────────────────────────────────

    @Test
    fun `disconnect forwards to the use case`() = runTest(testDispatcher) {
        vm.disconnect()
        testScheduler.runCurrent()
        assertEquals(1, connectUseCase.disconnectCount)
    }

    @Test
    fun `respond forwards answer to the use case`() = runTest(testDispatcher) {
        askUser("q-1", requestId = "ask-1")
        testScheduler.runCurrent()
        vm.respond("q-1", "my answer")
        testScheduler.runCurrent()
        assertEquals("my answer", connectUseCase.lastRespond)
        assertEquals("ask-1", connectUseCase.lastRespondRequestId)
    }

    @Test
    fun `interrupt forwards to the use case`() = runTest(testDispatcher) {
        vm.interrupt()
        testScheduler.runCurrent()
        assertEquals(1, connectUseCase.interruptInvocations)
    }

    @Test
    fun `interrupt optimistically flips a RUNNING Thinking bubble to DONE`() = runTest(testDispatcher) {
        // Regression coverage for the "no way to stop a running agent turn"
        // gap — see ChatViewModel.interrupt/markRunningItemsDone's own doc.
        // This flip is optimistic only (not persisted): the agent's real
        // closing event, once it arrives, overwrites the same item id
        // through the normal reducer path.
        val thinkingId = "thinking-1"
        connectUseCase.emit(
            ChatEvent.ChatItemReceived(ChatItem.Thinking(id = thinkingId, status = ThinkingStatus.RUNNING))
        )
        testScheduler.runCurrent()

        vm.interrupt()
        testScheduler.runCurrent()

        val thinking = vm.uiState.value.chatItems.filterIsInstance<ChatItem.Thinking>().single { it.id == thinkingId }
        assertEquals(ThinkingStatus.DONE, thinking.status)
        assertEquals(1, connectUseCase.interruptInvocations)
    }

    @Test
    fun `interrupt optimistically flips a RUNNING Turn's nested thinking to DONE`() = runTest(testDispatcher) {
        val turnId = "turn-1"
        connectUseCase.emit(
            ChatEvent.ChatItemReceived(ChatItem.Turn(id = turnId, thinking = ChatItem.Thinking(id = turnId, status = ThinkingStatus.RUNNING)))
        )
        testScheduler.runCurrent()

        vm.interrupt()
        testScheduler.runCurrent()

        val turn = vm.uiState.value.chatItems.filterIsInstance<ChatItem.Turn>().single { it.id == turnId }
        assertEquals(ThinkingStatus.DONE, turn.thinking?.status)
        assertTrue("must no longer read as in-progress", !turn.isInProgress())
    }

    @Test
    fun `interrupt settles the turn without sealing it against its own closing message`() = runTest(testDispatcher) {
        // This used to also fill in an empty reply, which settled the turn but
        // hid it from mergeIntoPendingTurn — so the closing message the agent
        // still owed arrived as a bubble of its own, stranding the footer with
        // this turn's timing and token count above a reply it no longer held.
        // Settling now rests on the footer alone: a turn whose thinking reads
        // DONE is not in progress even with no reply (see ChatItem.isInProgress),
        // which is what keeps the Stop button dark here.
        val turnId = "turn-2"
        connectUseCase.emit(
            ChatEvent.ChatItemReceived(ChatItem.Turn(id = turnId, thinking = ChatItem.Thinking(id = turnId, status = ThinkingStatus.RUNNING), agent = null))
        )
        testScheduler.runCurrent()
        assertEquals(true, vm.isAgentWorking.value)

        vm.interrupt()
        testScheduler.runCurrent()

        val turn = vm.uiState.value.chatItems.filterIsInstance<ChatItem.Turn>().single { it.id == turnId }
        assertTrue("must no longer read as in-progress", !turn.isInProgress())
        assertEquals("settled by its footer, not by a fabricated reply", ThinkingStatus.DONE, turn.thinking?.status)
        assertNull("still able to receive the closing message it is owed", turn.agent)
        assertEquals(false, vm.isAgentWorking.value)
    }

    @Test
    fun `a stopped turn that never answered does not adopt the next turn's reply`() =
        runTest(testDispatcher) {
            // The reason interrupt used to seal the turn. Left open, a stopped
            // turn is still a pending footer, and the merge picks the last one
            // — so the next turn has to be the one that wins it.
            val address = validHexAddress()
            vm.connectToAgent(address)
            testScheduler.runCurrent()
            connectUseCase.markConnected(address)
            vm.sendMessage("first")
            testScheduler.runCurrent()
            connectUseCase.emit(startTurn("turn-stopped"))
            testScheduler.runCurrent()
            vm.interrupt()
            testScheduler.runCurrent()

            // The agent says nothing. The user moves on, and that turn answers.
            vm.sendMessage("second")
            testScheduler.runCurrent()
            connectUseCase.emit(startTurn("turn-next"))
            testScheduler.runCurrent()
            connectUseCase.emit(ChatEvent.OutputReceived("the answer to second", null))
            testScheduler.runCurrent()

            // The merged turn takes the OUTPUT's own id, not the llm_call's,
            // so it is identified by what it holds rather than by name.
            val turns = vm.uiState.value.chatItems.filterIsInstance<ChatItem.Turn>()
            assertTrue(
                "the reply must land in the turn that produced it",
                turns.any { it.id != "turn-stopped" && it.agent?.content == "the answer to second" }
            )
            assertTrue(
                "the stopped turn must not have claimed it",
                turns.single { it.id == "turn-stopped" }.agent?.content.isNullOrBlank()
            )
        }

    @Test
    fun `a settled turn that produced no reply does not keep the Stop button lit`() = runTest(testDispatcher) {
        // The reload shape: a turn_thinking row decodes to exactly this, and
        // it used to disable the composer on an untouched, freshly opened app.
        connectUseCase.emit(
            ChatEvent.ChatItemReceived(
                ChatItem.Turn(id = "turn-3", thinking = ChatItem.Thinking(id = "turn-3", status = ThinkingStatus.DONE), agent = null)
            )
        )
        testScheduler.runCurrent()

        assertEquals(false, vm.isAgentWorking.value)
    }

    private suspend fun askUser(id: String, requestId: String?) {
        connectUseCase.emit(
            ChatEvent.ChatItemReceived(
                ChatItem.AskUser(id = id, text = "Which branch?", options = listOf("main", "dev"), multiSelect = false, requestId = requestId)
            )
        )
    }

    private fun question(id: String) =
        vm.uiState.value.chatItems.filterIsInstance<ChatItem.AskUser>().single { it.id == id }

    // ── which card an answer, and a refusal, belongs to (W16AAPPLE-107) ──

    @Test
    fun `answering an older question sends that question's id, not the newest one's`() = runTest(testDispatcher) {
        // Another device answered question 1 and the Host asked question 2;
        // question 1 is still on this screen with its buttons.
        askUser("q-1", requestId = "ask-1")
        askUser("q-2", requestId = "ask-2")
        testScheduler.runCurrent()

        vm.respond("q-1", "dev")
        testScheduler.runCurrent()

        assertEquals("ask-1", connectUseCase.lastRespondRequestId)
        assertEquals("dev", question("q-1").answer)
        assertNull("the newer question is still open", question("q-2").answer)
    }

    @Test
    fun `a question is answered once`() = runTest(testDispatcher) {
        askUser("q-1", requestId = "ask-1")
        testScheduler.runCurrent()

        vm.respond("q-1", "main")
        vm.respond("q-1", "dev")
        testScheduler.runCurrent()

        assertEquals(1, connectUseCase.respondCount)
        assertEquals("main", question("q-1").answer)
    }

    @Test
    fun `a question with no id from the Host is answered without one`() = runTest(testDispatcher) {
        askUser("local-7", requestId = null)
        testScheduler.runCurrent()

        vm.respond("local-7", "dev")
        testScheduler.runCurrent()

        assertEquals("dev", connectUseCase.lastRespond)
        assertNull("neither an app-made id nor the card's own id may be sent", connectUseCase.lastRespondRequestId)
    }

    @Test
    fun `a refused ask_user answer marks its question as no longer waiting`() = runTest(testDispatcher) {
        askUser("q-1", requestId = "ask-1")
        testScheduler.runCurrent()
        vm.respond("q-1", "dev")
        testScheduler.runCurrent()

        connectUseCase.emit(ChatEvent.AnswerRefused("ask-1"))
        testScheduler.runCurrent()

        assertTrue(question("q-1").refused)
        assertEquals("only one answer was ever sent", 1, connectUseCase.respondCount)
    }

    @Test
    fun `a refusal touches only the approval it names`() = runTest(testDispatcher) {
        connectUseCase.emit(ChatEvent.ChatItemReceived(ChatItem.ApprovalNeeded(id = "a", tool = "bash", arguments = emptyMap(), requestId = "req-a")))
        connectUseCase.emit(ChatEvent.ChatItemReceived(ChatItem.ApprovalNeeded(id = "b", tool = "edit", arguments = emptyMap(), requestId = "req-b")))
        testScheduler.runCurrent()
        vm.respondToApproval("a", true)
        vm.respondToApproval("b", true)
        testScheduler.runCurrent()

        connectUseCase.emit(ChatEvent.AnswerRefused("req-a"))
        testScheduler.runCurrent()

        assertEquals(true, gate("a")?.decision?.refused)
        assertEquals(false, gate("b")?.decision?.refused)
    }

    @Test
    fun `a refusal with no id marks nothing when it could mean either of two cards`() = runTest(testDispatcher) {
        raiseGate("gate-1")
        askUser("q-1", requestId = null)
        testScheduler.runCurrent()
        vm.respondToApproval("gate-1", true)
        vm.respond("q-1", "dev")
        testScheduler.runCurrent()

        connectUseCase.emit(ChatEvent.AnswerRefused(null))
        testScheduler.runCurrent()

        assertEquals(false, gate("gate-1")?.decision?.refused)
        assertFalse(question("q-1").refused)
    }

    @Test
    fun `a refusal with no id marks the one card answered without an id`() = runTest(testDispatcher) {
        connectUseCase.emit(ChatEvent.ChatItemReceived(ChatItem.ApprovalNeeded(id = "named", tool = "bash", arguments = emptyMap(), requestId = "req-1")))
        askUser("q-1", requestId = null)
        testScheduler.runCurrent()
        vm.respondToApproval("named", true)
        vm.respond("q-1", "dev")
        testScheduler.runCurrent()

        connectUseCase.emit(ChatEvent.AnswerRefused(null))
        testScheduler.runCurrent()

        assertTrue("the only answer that went out without an id", question("q-1").refused)
        assertEquals("an answer that named its request would have been named back", false, gate("named")?.decision?.refused)
    }

    /** respondToApproval now needs the gate present in chatItems to answer it. */
    private suspend fun raiseGate(id: String = "gate-1") {
        connectUseCase.emit(
            ChatEvent.ChatItemReceived(
                ChatItem.ApprovalNeeded(id = id, tool = "bash", arguments = emptyMap())
            )
        )
    }

    private fun gate(id: String = "gate-1") =
        vm.uiState.value.chatItems.filterIsInstance<ChatItem.ApprovalNeeded>().firstOrNull { it.id == id }

    @Test
    fun `respondToApproval forwards the boolean and default scope to the use case`() = runTest(testDispatcher) {
        raiseGate()
        testScheduler.runCurrent()
        vm.respondToApproval("gate-1", true)
        testScheduler.runCurrent()
        assertEquals(true, connectUseCase.lastApproval)
        assertEquals("once", connectUseCase.lastApprovalScope)
        assertNull(connectUseCase.lastApprovalMode)
    }

    @Test
    fun `respondToApproval forwards session scope and reject mode to the use case`() = runTest(testDispatcher) {
        raiseGate()
        testScheduler.runCurrent()
        vm.respondToApproval("gate-1", false, scope = "once", mode = "reject_hard")
        testScheduler.runCurrent()
        assertEquals(false, connectUseCase.lastApproval)
        assertEquals("once", connectUseCase.lastApprovalScope)
        assertEquals("reject_hard", connectUseCase.lastApprovalMode)
    }

    @Test
    fun `respondToApproval writes the decision onto the gate item`() = runTest(testDispatcher) {
        raiseGate()
        testScheduler.runCurrent()
        vm.respondToApproval("gate-1", false, scope = "once", mode = "reject_hard")
        testScheduler.runCurrent()

        // On the item, not in a side map: this is what lets the card render the
        // outcome after a recomposition *and* after a reload from the database.
        val decision = gate()?.decision
        assertEquals(false, decision?.approved)
        assertEquals("once", decision?.scope)
        assertEquals("reject_hard", decision?.mode)
    }

    @Test
    fun `respondToApproval ignores a second answer for the same gate`() = runTest(testDispatcher) {
        raiseGate()
        testScheduler.runCurrent()
        vm.respondToApproval("gate-1", false, scope = "once", mode = "reject_hard")
        testScheduler.runCurrent()
        vm.respondToApproval("gate-1", true, scope = "session")
        testScheduler.runCurrent()

        // The server already ran (or skipped) the tool on the first response;
        // a second APPROVAL_RESPONSE would be acted on again.
        assertEquals(false, connectUseCase.lastApproval)
        assertEquals("reject_hard", connectUseCase.lastApprovalMode)
        assertEquals(false, gate()?.decision?.approved)
    }

    @Test
    fun `respondToApproval forwards rejection feedback to the use case and the gate's decision`() = runTest(testDispatcher) {
        raiseGate()
        testScheduler.runCurrent()
        vm.respondToApproval("gate-1", false, scope = "once", mode = "reject_soft", feedback = "Use yarn instead")
        testScheduler.runCurrent()

        assertEquals("Use yarn instead", connectUseCase.lastApprovalFeedback)
        assertEquals("Use yarn instead", gate()?.decision?.feedback)
    }

    @Test
    fun `respondToApproval defaults feedback to null when none is given`() = runTest(testDispatcher) {
        raiseGate()
        testScheduler.runCurrent()
        vm.respondToApproval("gate-1", true)
        testScheduler.runCurrent()

        assertNull(connectUseCase.lastApprovalFeedback)
        assertNull(gate()?.decision?.feedback)
    }

    @Test
    fun `respondToOnboard forwards invite code path`() = runTest(testDispatcher) {
        connectUseCase.markConnected()
        vm.respondToOnboard(method = "invite_code", inviteCode = "ABC-123")
        testScheduler.runCurrent()
        assertEquals("invite_code", connectUseCase.lastOnboardMethod)
        assertEquals("ABC-123", connectUseCase.lastOnboardInviteCode)
        assertNull(connectUseCase.lastOnboardPayment)
    }

    @Test
    fun `respondToOnboard forwards payment path`() = runTest(testDispatcher) {
        connectUseCase.markConnected()
        vm.respondToOnboard(method = "payment", payment = 12.5)
        testScheduler.runCurrent()
        assertEquals("payment", connectUseCase.lastOnboardMethod)
        assertEquals(12.5, connectUseCase.lastOnboardPayment)
    }

    @Test
    fun `respondToOnboard is a no-op with a clear error when not connected`() = runTest(testDispatcher) {
        // Regression test: submitting an invite code into a dead connection
        // got no response and no error back, leaving OnboardGateCard's
        // "Verifying…" holding state stuck forever (see ChatViewModel.
        // respondToOnboard's own doc — caught via reconnect-timing testing
        // on-device).
        vm.respondToOnboard(method = "invite_code", inviteCode = "ABC-123")
        testScheduler.runCurrent()

        assertNull(connectUseCase.lastOnboardMethod)
        assertEquals("Not connected. Please reconnect first.", vm.uiState.value.error?.message)
    }

    @Test
    fun `respondToOnboard succeeds while connectionState is Connecting (onboarding pending)`() = runTest(testDispatcher) {
        // Regression test for the fix above's own regression: while an
        // ONBOARD_REQUIRED gate is showing, the server never sends the
        // CONNECTED frame, so connectionState legitimately stays Connecting
        // for the entire time the invite-code form is up — even though the
        // socket is live and AgentConnection.respondToOnboard only needs a
        // non-null socket. An earlier cut of the guard used isConnected()
        // (which requires that CONNECTED frame) and blocked every real
        // invite-code submission, not just genuinely dead connections —
        // caught immediately by re-testing on-device.
        connectUseCase.forceState(ConnectionState.Connecting)

        vm.respondToOnboard(method = "invite_code", inviteCode = "ABC-123")
        testScheduler.runCurrent()

        assertEquals("invite_code", connectUseCase.lastOnboardMethod)
        assertEquals("ABC-123", connectUseCase.lastOnboardInviteCode)
        assertNull("must not surface a spurious not-connected error", vm.uiState.value.error)
    }

    @Test
    fun `respondToPlanReview sends web's approval wording with the plan`() = runTest(testDispatcher) {
        connectUseCase.emit(ChatEvent.ChatItemReceived(ChatItem.PlanReviewItem(id = "plan-1", planContent = "1. Do it")))
        testScheduler.runCurrent()

        vm.respondToPlanReview("plan-1", PlanReviewReply.Approve)
        testScheduler.runCurrent()

        assertEquals(
            "Plan approved. Implement now. Do NOT re-enter plan mode.\n\n---\n\n1. Do it",
            connectUseCase.lastPlanReviewMessage
        )
    }

    @Test
    fun `respondToPlanReview sends the rejection with the user's feedback`() = runTest(testDispatcher) {
        connectUseCase.emit(ChatEvent.ChatItemReceived(ChatItem.PlanReviewItem(id = "plan-1", planContent = "1. Do it")))
        testScheduler.runCurrent()

        vm.respondToPlanReview("plan-1", PlanReviewReply.Reject("smaller steps"))
        testScheduler.runCurrent()

        assertEquals("Plan rejected. Revise with write_plan(). Feedback: smaller steps", connectUseCase.lastPlanReviewMessage)
    }

    // ── what the screen may show (W16AAPPLE-50) ─────────────────────────

    @Test
    fun `the screen gets agent text redacted while the reply keeps it`() = runTest(testDispatcher) {
        connectUseCase.emit(
            ChatEvent.ChatItemReceived(
                ChatItem.Agent(id = "a-1", content = "Saved OPENAI_API_KEY=abc123secret to /Users/priya/app/.env")
            )
        )
        testScheduler.runCurrent()

        val shown = vm.uiState.value.chatItems.filterIsInstance<ChatItem.Agent>().single().content
        assertEquals("Saved OPENAI_API_KEY=[redacted] to ~/app/.env", shown)
    }

    @Test
    fun `an approved plan quotes the plan as the agent wrote it, not as shown`() = runTest(testDispatcher) {
        val plan = "1. Edit /Users/priya/app/main.kt"
        connectUseCase.emit(ChatEvent.ChatItemReceived(ChatItem.PlanReviewItem(id = "plan-1", planContent = plan)))
        testScheduler.runCurrent()
        assertEquals(
            "1. Edit ~/app/main.kt",
            vm.uiState.value.chatItems.filterIsInstance<ChatItem.PlanReviewItem>().single().planContent
        )

        vm.respondToPlanReview("plan-1", PlanReviewReply.Approve)
        testScheduler.runCurrent()

        assertTrue(connectUseCase.lastPlanReviewMessage!!.endsWith(plan))
    }

    @Test
    fun `a tapped option is answered as the agent wrote it`() = runTest(testDispatcher) {
        connectUseCase.emit(
            ChatEvent.ChatItemReceived(
                ChatItem.AskUser(
                    id = "q-1",
                    text = "Where?",
                    options = listOf("/home/priya/work", "Somewhere else"),
                    multiSelect = false,
                    requestId = "ask-1"
                )
            )
        )
        testScheduler.runCurrent()
        assertEquals("~/work", question("q-1").options.first())

        vm.respondWithOption("q-1", 0)
        testScheduler.runCurrent()

        assertEquals("/home/priya/work", connectUseCase.lastRespond)
        assertEquals("~/work", question("q-1").answer)
    }

    @Test
    fun `of two options that look alike once redacted, the tapped one is answered`() = runTest(testDispatcher) {
        connectUseCase.emit(
            ChatEvent.ChatItemReceived(
                ChatItem.AskUser(
                    id = "q-1",
                    text = "Which folder?",
                    options = listOf("/home/priya/work", "/Users/priya/work"),
                    multiSelect = false,
                    requestId = "ask-1"
                )
            )
        )
        testScheduler.runCurrent()
        assertEquals(listOf("~/work", "~/work"), question("q-1").options)

        vm.respondWithOption("q-1", 1)
        testScheduler.runCurrent()

        assertEquals("/Users/priya/work", connectUseCase.lastRespond)
    }

    @Test
    fun `Retry sends the message the user typed, not the one on screen`() {
        vm.connectToAgent(validHexAddress())
        testSchedulerKeepRunning()
        connectUseCase.markConnected()
        val typed = "Set OPENAI_API_KEY=sk-proj-abcdefghijklmnopqrstuv in /home/priya/app/.env"

        vm.sendMessage(typed)
        testSchedulerKeepRunning()
        val shown = vm.uiState.value.chatItems.filterIsInstance<ChatItem.User>().last()
        assertEquals("Set OPENAI_API_KEY=[redacted] in ~/app/.env", shown.content)

        vm.retryFailedTurn(shown.id)
        testSchedulerKeepRunning()

        assertEquals(listOf(typed, typed), connectUseCase.sentMessages.map { it.content })
    }

    @Test
    fun `the live Todo List is shown redacted`() = runTest(testDispatcher) {
        connectUseCase._plan.value = listOf(
            PlanEntry("Rotate OPENAI_API_KEY=sk-proj-abcdefghijklmnopqrstuv", PlanPriority.HIGH, PlanStatus.IN_PROGRESS),
            PlanEntry("Edit /home/priya/app/main.kt", PlanPriority.MEDIUM, PlanStatus.PENDING),
        )
        testScheduler.runCurrent()

        assertEquals(
            listOf("Rotate OPENAI_API_KEY=[redacted]", "Edit ~/app/main.kt"),
            vm.plan.value.map { it.content }
        )
    }

    @Test
    fun `a raw provider payload in a failed turn is never shown`() = runTest(testDispatcher) {
        connectUseCase.emit(
            ChatEvent.ChatItemReceived(
                ChatItem.Thinking(
                    id = "t-1",
                    status = ThinkingStatus.ERROR,
                    content = "Error code: 400 - {'type': 'error', 'error': {'message': 'prompt too long'}}"
                )
            )
        )
        testScheduler.runCurrent()

        val shown = vm.uiState.value.chatItems.filterIsInstance<ChatItem.Thinking>().single().content
        assertEquals(SensitiveText.UNREADABLE_ERROR, shown)
    }

    @Test
    fun `respondToUlwTurnsReached forwards the continue action with turns`() = runTest(testDispatcher) {
        vm.respondToUlwTurnsReached(action = "continue", turns = 100)
        testScheduler.runCurrent()
        assertEquals("continue", connectUseCase.lastUlwAction)
        assertEquals(100, connectUseCase.lastUlwTurns)
        assertNull(connectUseCase.lastUlwMode)
    }

    @Test
    fun `respondToUlwTurnsReached forwards the switch_mode action with mode`() = runTest(testDispatcher) {
        vm.respondToUlwTurnsReached(action = "switch_mode", mode = "safe")
        testScheduler.runCurrent()
        assertEquals("switch_mode", connectUseCase.lastUlwAction)
        assertNull(connectUseCase.lastUlwTurns)
        assertEquals("safe", connectUseCase.lastUlwMode)
    }

    // ── Approval mode ──────────────────────────────────────────────────

    @Test
    fun `cycleApprovalMode advances the chip through the base modes`() = runTest(testDispatcher) {
        assertEquals(ApprovalMode.SAFE, vm.approvalMode.value)

        vm.cycleApprovalMode()
        testScheduler.runCurrent()
        assertEquals(ApprovalMode.PLAN, vm.approvalMode.value)

        vm.cycleApprovalMode()
        testScheduler.runCurrent()
        assertEquals(ApprovalMode.ACCEPT_EDITS, vm.approvalMode.value)

        vm.cycleApprovalMode()
        testScheduler.runCurrent()
        assertEquals(ApprovalMode.SAFE, vm.approvalMode.value)

        assertEquals(
            listOf(ApprovalMode.PLAN, ApprovalMode.ACCEPT_EDITS, ApprovalMode.SAFE),
            connectUseCase.setModeCalls.map { it.first }
        )
    }

    /**
     * Safety property, asserted at the layer the chip actually calls: tapping
     * the chip can never hand the agent ULW, from any starting mode and for
     * any number of taps. See [ApprovalMode.CYCLE]'s doc.
     */
    @Test
    fun `cycling the chip never sends a ulw mode_change`() = runTest(testDispatcher) {
        ApprovalMode.entries.forEach { start ->
            connectUseCase._approvalMode.value = start
            repeat(ApprovalMode.entries.size + 2) {
                vm.cycleApprovalMode()
                testScheduler.runCurrent()
            }
        }

        assertTrue(connectUseCase.setModeCalls.isNotEmpty())
        assertTrue(
            "cycling produced a ULW switch: ${connectUseCase.setModeCalls}",
            connectUseCase.setModeCalls.none { it.first == ApprovalMode.ULW }
        )
    }

    @Test
    fun `setApprovalMode forwards ULW with its turn budget`() = runTest(testDispatcher) {
        vm.setApprovalMode(ApprovalMode.ULW, turns = 25)
        testScheduler.runCurrent()

        assertEquals(ApprovalMode.ULW to 25, connectUseCase.setModeCalls.single())
        assertEquals(ApprovalMode.ULW, vm.approvalMode.value)
    }

    @Test
    fun `setApprovalMode sends no turns for a base mode`() = runTest(testDispatcher) {
        vm.setApprovalMode(ApprovalMode.PLAN)
        testScheduler.runCurrent()

        assertEquals(ApprovalMode.PLAN to null, connectUseCase.setModeCalls.single())
    }

    @Test
    fun `an agent-initiated mode_changed moves the chip without any local action`() = runTest(testDispatcher) {
        connectUseCase._approvalMode.value = ApprovalMode.ULW
        testScheduler.runCurrent()

        assertEquals(ApprovalMode.ULW, vm.approvalMode.value)
        assertTrue(connectUseCase.setModeCalls.isEmpty())
    }

    // ── Host-managed modes and session activity (W16AAPPLE-18) ─────────

    private val hostModes = SessionModes(
        available = listOf(ApprovalMode.READ_ONLY, ApprovalMode.AUTO, ApprovalMode.FULL_ACCESS),
        hostManaged = true
    )

    @Test
    fun `on a Host-managed connection the chip cycles the advertised base modes only`() = runTest(testDispatcher) {
        connectUseCase._sessionModes.value = hostModes
        connectUseCase._approvalMode.value = ApprovalMode.AUTO

        repeat(4) {
            vm.cycleApprovalMode()
            testScheduler.runCurrent()
        }

        assertEquals(
            listOf(ApprovalMode.READ_ONLY, ApprovalMode.AUTO, ApprovalMode.READ_ONLY, ApprovalMode.AUTO),
            connectUseCase.setModeCalls.map { it.first }
        )
    }

    @Test
    fun `the chip exits full access to the Host's default, auto`() = runTest(testDispatcher) {
        connectUseCase._sessionModes.value = hostModes.copy(turnsLeft = 4)
        connectUseCase._approvalMode.value = ApprovalMode.FULL_ACCESS

        vm.cycleApprovalMode()
        testScheduler.runCurrent()

        assertEquals(ApprovalMode.AUTO to null, connectUseCase.setModeCalls.single())
    }

    @Test
    fun `sessionActivity is null while not connected`() = runTest(testDispatcher) {
        connectUseCase._hostSessionStatus.value = ServerSessionStatus.RUNNING
        testScheduler.runCurrent()

        assertNull(vm.sessionActivity.value)
    }

    @Test
    fun `sessionActivity is idle once connected and nothing is running`() = runTest(testDispatcher) {
        connectUseCase.markConnected()
        connectUseCase._hostSessionStatus.value = ServerSessionStatus.CONNECTED
        testScheduler.runCurrent()

        assertEquals(SessionActivity.IDLE, vm.sessionActivity.value)
    }

    @Test
    fun `sessionActivity reflects a SESSION_STATUS running or not_found answer`() = runTest(testDispatcher) {
        connectUseCase.markConnected()
        connectUseCase._hostSessionStatus.value = ServerSessionStatus.RUNNING
        testScheduler.runCurrent()
        assertEquals(SessionActivity.RUNNING, vm.sessionActivity.value)

        connectUseCase._hostSessionStatus.value = ServerSessionStatus.NOT_FOUND
        testScheduler.runCurrent()
        assertEquals(SessionActivity.NOT_FOUND, vm.sessionActivity.value)
    }

    @Test
    fun `sessionActivity is running while a turn is in progress on screen`() = runTest(testDispatcher) {
        connectUseCase.markConnected()
        connectUseCase.emit(ChatEvent.ChatItemReceived(ChatItem.Thinking(id = "t1", status = ThinkingStatus.RUNNING)))
        testScheduler.runCurrent()

        assertEquals(SessionActivity.RUNNING, vm.sessionActivity.value)
    }

    @Test
    fun `joined while running, a turn that fails stops saying Running`() = runTest(testDispatcher) {
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        // Joined a turn the Host is still running: this process never sent its INPUT.
        connectUseCase._hostSessionStatus.value = ServerSessionStatus.RUNNING
        connectUseCase.markConnected(address, status = ServerSessionStatus.RUNNING)
        testScheduler.runCurrent()
        assertEquals(SessionActivity.RUNNING, vm.sessionActivity.value)

        // The turn fails: an ERROR in place of the OUTPUT, which the repository answers by
        // settling its status (ConnectionRepositoryImplTest covers that half).
        connectUseCase.emit(ChatEvent.ConnectionErrorOccurred("Unable to run agent"))
        connectUseCase._hostSessionStatus.value = ServerSessionStatus.CONNECTED
        testScheduler.runCurrent()

        assertEquals(SessionActivity.IDLE, vm.sessionActivity.value)
    }

    @Test
    fun `sessionActivity is waiting for you while a gate is open`() = runTest(testDispatcher) {
        connectUseCase.markConnected()
        raiseGate()
        testScheduler.runCurrent()

        assertEquals(SessionActivity.AWAITING_USER, vm.sessionActivity.value)
    }

    @Test
    fun `connectToAgent with blank address is a no-op`() = runTest(testDispatcher) {
        vm.connectToAgent("")
        testScheduler.runCurrent()
        assertEquals(
            "Blank address must not trigger a connect",
            0,
            connectUseCase.connectInvocations
        )
    }

    @Test
    fun `connectToAgent with valid hex address resolves and forwards to use case`() = runTest(testDispatcher) {
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()

        assertEquals(1, connectUseCase.connectInvocations)
        assertEquals(address, connectUseCase.lastConnectAddress)
    }

    @Test
    fun `connectToAgent forwards a saved non-default serverUrl as directUrl`() = runTest(testDispatcher) {
        // Regression test: the drawer's "+" on a different agent (and every
        // other connectToAgent(address) caller that doesn't already know a
        // directUrl) silently ignored that agent's saved custom server and
        // fell back to the default relay — see connectToAgent's own doc.
        val address = validHexAddress()
        agentRepo.createAgent(
            AgentProfile(
                id = "agent-1",
                address = address,
                name = "Custom Server Agent",
                serverUrl = "https://custom.example.com",
                createdAt = 0L,
                connectionMode = "direct"
            )
        )

        vm.connectToAgent(address)
        testScheduler.runCurrent()

        assertEquals(address, connectUseCase.lastConnectAddress)
        assertEquals("https://custom.example.com", connectUseCase.lastConnectDirectUrl)
    }

    @Test
    fun `an agent saved as relay-mode on a self-hosted relay is dialled through that relay`() =
        runTest(testDispatcher) {
            // What discovery saves for every agent found on a relay the user
            // typed in: connectionMode "relay", serverUrl = that relay.
            val address = validHexAddress()
            agentRepo.createAgent(
                AgentProfile(
                    id = "agent-1",
                    address = address,
                    name = "Found On My Relay",
                    serverUrl = "https://relay.example.com",
                    createdAt = 0L,
                    connectionMode = "relay"
                )
            )

            vm.connectToAgent(address)
            testScheduler.runCurrent()

            // W16AAPPLE-56: through the relay's /ws/input, not direct at <relay>/ws.
            assertNull(connectUseCase.lastConnectDirectUrl)
            assertEquals("https://relay.example.com", connectUseCase.lastConnectRelayUrl)
        }

    @Test
    fun `connectToAgent does not forward a default-relay serverUrl as directUrl`() = runTest(testDispatcher) {
        val address = validHexAddress()
        agentRepo.createAgent(
            AgentProfile(
                id = "agent-1",
                address = address,
                name = "Default Relay Agent",
                serverUrl = "https://oo.openonion.ai",
                createdAt = 0L
            )
        )

        vm.connectToAgent(address)
        testScheduler.runCurrent()

        assertNull(connectUseCase.lastConnectDirectUrl)
    }

    @Test
    fun `connectToAgent honors an explicitly passed directUrl over the saved agent serverUrl`() = runTest(testDispatcher) {
        val address = validHexAddress()
        agentRepo.createAgent(
            AgentProfile(
                id = "agent-1",
                address = address,
                name = "Custom Server Agent",
                serverUrl = "https://custom.example.com",
                createdAt = 0L
            )
        )

        vm.connectToAgent(address, directUrl = "https://explicit.example.com")
        testScheduler.runCurrent()

        assertEquals("https://explicit.example.com", connectUseCase.lastConnectDirectUrl)
    }

    // ── which agent a cold start reconnects to (W16AAPPLE-77) ──────────

    /** The agent onboarding or the agent list saved before the user went elsewhere. */
    private fun savedFromOnboarding(address: String) {
        configRepo.configValue = ConnectionConfig(serverUrl = "https://oo.openonion.ai", agentAddress = address, apiKey = "key-1")
    }

    @Test
    fun `an agent opened from a link or the drawer becomes the one a cold start reconnects to`() = runTest(testDispatcher) {
        savedFromOnboarding(validHexAddress(0xA))
        val opened = validHexAddress(0xB)

        connectUseCase.markConnected(opened)
        vm.connectToAgent(opened)
        testScheduler.runCurrent()

        val target = configRepo.configValue!!.toConnectTarget()
        assertEquals(opened, target.agentAddress)
        assertNull("on the default relay, as the chat dialled it", target.directUrl)
    }

    @Test
    fun `a cold start reaches the agent on the same server the chat used`() = runTest(testDispatcher) {
        savedFromOnboarding(validHexAddress(0xA))
        val opened = validHexAddress(0xB)
        ownServerAgent(opened)

        connectUseCase.markConnected(opened)
        vm.connectToAgent(opened)
        testScheduler.runCurrent()

        val target = configRepo.configValue!!.toConnectTarget()
        assertEquals(opened, target.agentAddress)
        assertEquals(connectUseCase.lastConnectDirectUrl, target.directUrl)
        assertEquals("https://custom.example.com", target.directUrl)
    }

    private suspend fun ownServerAgent(address: String) {
        agentRepo.createAgent(
            // An agent on its own server is saved "direct" (the form, a link, and since v17 every older row).
            AgentProfile(
                id = "agent-b",
                address = address,
                name = "Own server",
                serverUrl = "https://custom.example.com",
                createdAt = 0L,
                connectionMode = "direct"
            )
        )
    }

    @Test
    fun `a reconnect after a drop keeps the agent on its own server`() = runTest(testDispatcher) {
        savedFromOnboarding(validHexAddress(0xA))
        val opened = validHexAddress(0xB)
        ownServerAgent(opened)
        connectUseCase.markConnected(opened)
        vm.connectToAgent(opened)
        testScheduler.runCurrent()

        // The state listener's Connected carries no URL.
        connectUseCase.forceState(ConnectionState.Disconnected)
        connectUseCase.markConnected(opened)
        testScheduler.runCurrent()

        assertEquals("https://custom.example.com", configRepo.configValue!!.toConnectTarget().directUrl)
    }

    @Test
    fun `an agent let in after the onboarding gate is saved on its own server`() = runTest(testDispatcher) {
        savedFromOnboarding(validHexAddress(0xA))
        val opened = validHexAddress(0xB)
        ownServerAgent(opened)
        // connect() returns true on ONBOARD_REQUIRED, before the user is let in.
        connectUseCase.forceState(ConnectionState.Disconnected)
        vm.connectToAgent(opened)
        testScheduler.runCurrent()

        connectUseCase.markConnected(opened)
        testScheduler.runCurrent()

        val target = configRepo.configValue!!.toConnectTarget()
        assertEquals(opened, target.agentAddress)
        assertEquals("https://custom.example.com", target.directUrl)
    }

    @Test
    fun `a reconnect keeps the server the agent was opened at, not its saved row's`() = runTest(testDispatcher) {
        val opened = validHexAddress(0xB)
        ownServerAgent(opened)
        connectUseCase.markConnected(opened)
        vm.connectToAgent(opened, directUrl = "https://other.example.com")
        testScheduler.runCurrent()

        connectUseCase.forceState(ConnectionState.Disconnected)
        connectUseCase.markConnected(opened)
        testScheduler.runCurrent()

        assertEquals("https://other.example.com", configRepo.configValue!!.toConnectTarget().directUrl)
    }

    @Test
    fun `a saved relay URL written another way is not rewritten`() = runTest(testDispatcher) {
        val onboarded = validHexAddress(0xA)
        val saved = ConnectionConfig(serverUrl = "wss://oo.openonion.ai/", agentAddress = onboarded, apiKey = "key-1")
        configRepo.configValue = saved

        connectUseCase.markConnected(onboarded)
        vm.connectToAgent(onboarded)
        testScheduler.runCurrent()

        assertEquals(saved, configRepo.configValue)
    }

    @Test
    fun `an agent that could not be reached does not replace the saved one`() = runTest(testDispatcher) {
        val onboarded = validHexAddress(0xA)
        savedFromOnboarding(onboarded)
        connectUseCase.connectResult = false

        vm.connectToAgent(validHexAddress(0xB))
        testScheduler.runCurrent()

        assertEquals(onboarded, configRepo.configValue?.agentAddress)
    }

    @Test
    fun `reconnecting to the saved agent keeps its config as it was`() = runTest(testDispatcher) {
        val onboarded = validHexAddress(0xA)
        savedFromOnboarding(onboarded)

        connectUseCase.markConnected(onboarded)
        vm.connectToAgent(onboarded)
        testScheduler.runCurrent()

        assertEquals(
            ConnectionConfig(serverUrl = "https://oo.openonion.ai", agentAddress = onboarded, apiKey = "key-1"),
            configRepo.configValue
        )
    }

    @Test
    fun `targetAgentAddress reflects the resolved address after connectToAgent`() = runTest(testDispatcher) {
        assertNull("no target address before any connect attempt", vm.targetAgentAddress.value)

        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()

        assertEquals(address, vm.targetAgentAddress.value)
    }

    @Test
    fun `connectToAgentByConfig reads config and forwards to connectToAgent`() = runTest(testDispatcher) {
        configRepo.configValue = ConnectionConfig(
            serverUrl = "https://oo.openonion.ai",
            agentAddress = validHexAddress()
        )
        vm.connectToAgentByConfig()
        testScheduler.runCurrent()

        assertEquals(
            "Config-driven connect must call the use case",
            1,
            connectUseCase.connectInvocations
        )
    }

    @Test
    fun `connectToAgentByConfig forwards a self-hosted relay saved in relay mode as the relay`() = runTest(testDispatcher) {
        configRepo.configValue = ConnectionConfig(
            serverUrl = "https://relay.example.com",
            agentAddress = validHexAddress(),
            connectionMode = "relay"
        )
        vm.connectToAgentByConfig()
        testScheduler.runCurrent()

        assertNull(connectUseCase.lastConnectDirectUrl)
        assertEquals("https://relay.example.com", connectUseCase.lastConnectRelayUrl)
    }

    private suspend fun selfHostedRelayAgent(address: String) {
        // What discovery saves for an agent found on a relay the user typed in.
        agentRepo.createAgent(
            AgentProfile(
                id = "agent-r",
                address = address,
                name = "On my relay",
                serverUrl = "https://relay.example.com",
                createdAt = 0L,
                connectionMode = "relay"
            )
        )
    }

    @Test
    fun `a cold start goes back through the self-hosted relay the chat used`() = runTest(testDispatcher) {
        savedFromOnboarding(validHexAddress(0xA))
        val opened = validHexAddress(0xB)
        selfHostedRelayAgent(opened)

        connectUseCase.markConnected(opened)
        vm.connectToAgent(opened)
        testScheduler.runCurrent()

        // W16AAPPLE-56: remembered in relay mode, or Loading would dial the relay direct.
        val target = configRepo.configValue!!.toConnectTarget()
        assertEquals(opened, target.agentAddress)
        assertNull(target.directUrl)
        assertEquals("https://relay.example.com", target.relayUrl)
    }

    @Test
    fun `Recent Chat for an agent on a self-hosted relay is fetched through that relay`() = runTest(testDispatcher) {
        val address = validHexAddress()
        selfHostedRelayAgent(address)

        connectUseCase.markConnected(address)
        vm.connectToAgent(address)
        testScheduler.runCurrent()

        val call = recentChatsSync.calls.single()
        assertNull(call.directUrl)
        assertEquals("https://relay.example.com", call.relayUrl)
    }

    @Test
    fun `an agent first reached through a self-hosted relay is saved on that relay`() = runTest(testDispatcher) {
        val address = validHexAddress()

        connectUseCase.markConnected(address)
        vm.connectToAgent(address, relayUrl = "https://relay.example.com")
        testScheduler.runCurrent()

        val saved = agentRepo.getAgentByAddress(address)!!
        assertEquals("https://relay.example.com", saved.serverUrl)
        assertEquals("relay", saved.connectionMode)

        vm.connectToAgent(address)
        testScheduler.runCurrent()
        assertNull(connectUseCase.lastConnectDirectUrl)
        assertEquals("https://relay.example.com", connectUseCase.lastConnectRelayUrl)
    }

    @Test
    fun `an agent first reached direct is saved as direct so it is dialled direct again`() = runTest(testDispatcher) {
        val address = validHexAddress()
        vm.connectToAgent(address, directUrl = "https://agent.example.com")
        testScheduler.runCurrent()

        val saved = agentRepo.getAgentByAddress(address)
        assertEquals("direct", saved?.connectionMode)

        vm.connectToAgent(address)
        testScheduler.runCurrent()

        assertEquals("https://agent.example.com", connectUseCase.lastConnectDirectUrl)
        assertNull(connectUseCase.lastConnectRelayUrl)
    }

    @Test
    fun `connectToAgentByConfig is a no-op when no config is saved`() = runTest(testDispatcher) {
        configRepo.configValue = null
        vm.connectToAgentByConfig()
        testScheduler.runCurrent()
        assertEquals(0, connectUseCase.connectInvocations)
        // Regression: Reconnect used to fail silently forever here — see
        // connectToAgentByConfig's else branch.
        assertNotNull("no config must surface an error, not silence", vm.uiState.value.error)
    }

    @Test
    fun `connectToAgentByConfig is a no-op when config has no agent address`() = runTest(testDispatcher) {
        configRepo.configValue = ConnectionConfig(serverUrl = "https://x", agentAddress = null)
        vm.connectToAgentByConfig()
        testScheduler.runCurrent()
        assertEquals(0, connectUseCase.connectInvocations)
        assertNotNull("no agent address must surface an error, not silence", vm.uiState.value.error)
    }

    // ── Connected-state side effect: saveAgentIfNeeded ─────────────────

    @Test
    fun `transitioning to Connected calls saveAgentIfNeeded`() = runTest(testDispatcher) {
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()

        connectUseCase.markConnected(address)
        testScheduler.runCurrent()

        assertTrue(
            "Connected must trigger saveAgentIfNeeded via agentRepository",
            agentRepo.createdAgents.isNotEmpty()
        )
        val saved = agentRepo.createdAgents.first()
        assertEquals(address, saved.address)
    }

    @Test
    fun `joining an already-live shared connection still runs the connected work`() = runTest(testDispatcher) {
        // LoadingScreen's probe left the shared connection Connected, so
        // ChatViewModel's own connect() joins it and no Connected *transition*
        // ever fires. The agent row and its conversation must still be resolved.
        val address = validHexAddress()
        connectUseCase.markConnected(address)
        testScheduler.runCurrent()

        vm.connectToAgent(address)
        testScheduler.runCurrent()

        assertEquals(address, agentRepo.createdAgents.single().address)
    }

    @Test
    fun `connected agent refreshes Recent Chat exactly once for that connection`() = runTest(testDispatcher) {
        val address = validHexAddress()
        recentChatsSync.onActivate = { connectUseCase.interactions += "activate-identity" }
        vm.connectToAgent(address)
        testScheduler.runCurrent()

        // Consuming this non-UNKNOWN status publishes another Connected value;
        // it still represents the same socket and must not start a second sync.
        connectUseCase.markConnected(address, status = ServerSessionStatus.CONNECTED)
        testScheduler.runCurrent()

        val call = recentChatsSync.calls.single()
        assertTrue(recentChatsSync.identityActivations >= 1)
        assertTrue(
            connectUseCase.interactions.indexOf("activate-identity") <
                connectUseCase.interactions.indexOfFirst { it.startsWith("connect:") }
        )
        assertEquals(address, call.agentAddress)
        assertEquals(agentRepo.createdAgents.single().id, call.agentId)
    }

    @Test
    fun `a later reconnect refreshes Recent Chat again`() = runTest(testDispatcher) {
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)
        testScheduler.runCurrent()

        connectUseCase.forceState(ConnectionState.Reconnecting)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)
        testScheduler.runCurrent()

        assertEquals(2, recentChatsSync.calls.size)
    }

    @Test
    fun `selecting an already-connected agent refreshes Recent Chat again`() = runTest(testDispatcher) {
        val address = validHexAddress()
        connectUseCase.markConnected(address)
        testScheduler.runCurrent()

        vm.connectToAgent(address)
        testScheduler.runCurrent()
        vm.connectToAgent(address)
        testScheduler.runCurrent()

        assertEquals(2, recentChatsSync.calls.size)
    }

    @Test
    fun `re-selecting the same agent refreshes even when Connected carries the device identity`() = runTest(testDispatcher) {
        // Production reality: AgentConnection stamps ConnectionState.Connected.address
        // with the device's own identity (keys?.address), NOT the agent we dialled
        // (see AgentConnection's CONNECTED handler). The "is this a live
        // re-selection?" decision must therefore come from the ViewModel's own
        // previous target, not from Connected.address -- otherwise navigating back
        // to the agent already open silently skips the refresh, which is exactly
        // the cross-device staleness this fixes.
        val address = validHexAddress()
        val deviceIdentity = validHexAddress(nibble = 0xB)
        connectUseCase.markConnected(deviceIdentity)
        assertNotEquals(address, deviceIdentity)
        testScheduler.runCurrent()

        vm.connectToAgent(address)
        testScheduler.runCurrent()
        vm.connectToAgent(address)
        testScheduler.runCurrent()

        assertEquals(2, recentChatsSync.calls.size)
    }

    @Test
    fun `returning to foreground refreshes Recent Chat on a live connection`() = runTest(testDispatcher) {
        val address = validHexAddress()
        connectUseCase.markConnected(address)
        testScheduler.runCurrent()
        vm.connectToAgent(address)
        testScheduler.runCurrent()

        vm.onReturnedToForeground()
        testScheduler.runCurrent()

        assertEquals(2, recentChatsSync.calls.size)
    }

    @Test
    fun `Recent Chat sync failure does not fail the live chat`() = runTest(testDispatcher) {
        recentChatsSync.result = RecentChatsSyncResult.Failed("index unavailable", retryable = true)
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)
        testScheduler.runCurrent()

        assertEquals(1, recentChatsSync.calls.size)
        assertNull(vm.uiState.value.error)
        assertTrue(connectUseCase.connectionState.value is ConnectionState.Connected)
    }

    @Test
    fun `identity filter failure does not fail the live chat`() = runTest(testDispatcher) {
        recentChatsSync.activationFailure = IllegalStateException("database unavailable")
        val address = validHexAddress()

        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)
        testScheduler.runCurrent()

        assertTrue(connectUseCase.connectionState.value is ConnectionState.Connected)
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun `an already-Connected shared connection at construction saves no blank agent`() = runTest(testDispatcher) {
        // The state collector sees Connected the instant it subscribes, which
        // is before ChatScreen has said which agent this ViewModel is for —
        // acting on it then wrote an agent row with an empty address.
        connectUseCase.markConnected(validHexAddress())

        val fresh = ChatViewModel(
            application = Application(),
            agentDiscovery = AgentDiscoveryService(),
            connectUseCase = connectUseCase,
            agentRepository = agentRepo,
            configRepository = configRepo,
            ignoredIdsManager = IgnoredIdsManager(storage),
            imageAttachmentStore = imageStore,
            fileAttachmentStore = fileStore,
            voiceRecorderStore = voiceRecorderStore,
            voiceTranscriptionService = voiceTranscriptionService,
            speechRecognitionService = speechRecognition,
            appSettings = appSettings,
            messageRepository = messageRepo,
            conversationHistory = ConversationHistoryUseCase(
                agentRepo, sessionRepo, messageRepo,
                FakePersistenceTransaction(messageRepo, sessionRepo)
            ),
            recentChatsSync = recentChatsSync,
            initialMyAddress = "0xTEST",
            networkMonitor = networkMonitor,
            agentReplyNotifier = agentReplyNotifier,
            requiresNotificationPermission = true,
            hasNotificationPermission = { notificationPermissionGranted },
            isAppInForeground = { appInForeground },
            controlCenterDispatcher = testDispatcher,
        )
        testScheduler.runCurrent()

        assertTrue(agentRepo.createdAgents.isEmpty())
        assertNull(fresh.targetAgentAddress.value)
    }

    // ── drawerAgents ─────────────────────────────────────────────────────

    private fun agentProfile(id: String, address: String, isActive: Boolean = true) = AgentProfile(
        id = id, address = address, name = "Agent-$id", serverUrl = "https://example.com",
        createdAt = 0L, isActive = isActive
    )

    @Test
    fun `drawerAgents lists every saved agent, not just the connected one`() = runTest(testDispatcher) {
        agentRepo.createAgent(agentProfile("a1", "0x1"))
        agentRepo.createAgent(agentProfile("a2", "0x2"))
        testScheduler.runCurrent()

        val sections = vm.drawerAgents.value

        assertEquals(setOf("0x1", "0x2"), sections.map { it.agentAddress }.toSet())
    }

    @Test
    fun `drawerAgents shows live Connecting status only for the currently-targeted agent`() = runTest(testDispatcher) {
        agentRepo.createAgent(agentProfile("a1", "0x1"))
        agentRepo.createAgent(agentProfile("a2", "0x2"))
        vm.connectToAgent("0x1")
        testScheduler.runCurrent()

        connectUseCase.forceState(ConnectionState.Connecting)
        testScheduler.runCurrent()

        val sections = vm.drawerAgents.value.associateBy { it.agentAddress }
        assertEquals(AgentStatus.Connecting, sections.getValue("0x1").status)
        // The other agent has no live signal — falls back to isActive, same
        // as AgentListScreen's own StatusBadge does.
        assertEquals(AgentStatus.Active, sections.getValue("0x2").status)
    }

    @Test
    fun `drawerAgents falls back to Disabled for an inactive, non-targeted agent`() = runTest(testDispatcher) {
        agentRepo.createAgent(agentProfile("a1", "0x1", isActive = false))
        testScheduler.runCurrent()

        val section = vm.drawerAgents.value.single()

        assertEquals(AgentStatus.Disabled, section.status)
    }

    @Test
    fun `drawerAgents reflects Connected and Error for the targeted agent`() = runTest(testDispatcher) {
        agentRepo.createAgent(agentProfile("a1", "0x1"))
        vm.connectToAgent("0x1")
        testScheduler.runCurrent()

        connectUseCase.markConnected("0x1")
        testScheduler.runCurrent()
        assertEquals(AgentStatus.Connected, vm.drawerAgents.value.single().status)

        connectUseCase.forceState(ConnectionState.Error(message = "boom"))
        testScheduler.runCurrent()
        assertEquals(AgentStatus.Error("boom"), vm.drawerAgents.value.single().status)
    }

    // ── awaitingOnboardCode ──────────────────────────────────────────────

    @Test
    fun `markOnboardPending sets awaitingOnboardCode`() {
        assertFalse(vm.awaitingOnboardCode.value)

        vm.markOnboardPending()

        assertTrue(vm.awaitingOnboardCode.value)
    }

    @Test
    fun `awaitingOnboardCode clears when connectionState becomes Connected`() = runTest(testDispatcher) {
        vm.markOnboardPending()
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()

        connectUseCase.markConnected(address)
        testScheduler.runCurrent()

        assertFalse(vm.awaitingOnboardCode.value)
    }

    @Test
    fun `awaitingOnboardCode clears when connectionState becomes Error`() = runTest(testDispatcher) {
        vm.markOnboardPending()

        connectUseCase.forceState(ConnectionState.Error(message = "boom"))
        testScheduler.runCurrent()

        assertFalse(vm.awaitingOnboardCode.value)
    }

    @Test
    fun `awaitingOnboardCode stays true while an unanswered OnboardRequired card is showing`() {
        // Some agents never send a CONNECTED frame once onboarding is
        // required, so connectionState alone would stay "Connecting"
        // forever — the chat list having the card is what must keep the
        // banner in the "waiting for code" state, independent of markOnboardPending().
        connectUseCase.emit(
            ChatEvent.ChatItemReceived(ChatItem.OnboardRequired(id = "ob1", methods = listOf("invite_code")))
        )

        assertTrue(vm.awaitingOnboardCode.value)
    }

    // ── W16AAPPLE-80: first connect to an invite-gated agent ──────────
    //
    // The agent is not in Room yet (Setup saves only ConnectionConfig), so the
    // conversation is resolved after connect() returns, which it does with
    // true once ONBOARD_REQUIRED wins. Starting that conversation used to empty
    // the list, card included, and clear the flag: "Connecting to agent…"
    // forever, with no way to enter the code.

    @Test
    fun `a new agent's invite-code card survives the first connect, and the banner still asks for the code`() = runTest(testDispatcher) {
        val address = "0x" + "ab".repeat(32)
        vm.markOnboardPending() // as LoadingScreen hands over
        connectUseCase.emit(
            ChatEvent.ChatItemReceived(ChatItem.OnboardRequired(id = "ob1", methods = listOf("invite_code")))
        )
        testScheduler.advanceUntilIdle()

        vm.connectToAgent(address)
        testScheduler.advanceUntilIdle()

        assertNotNull("the agent is saved on this connect", agentRepo.getAgentByAddress(address))
        assertEquals(listOf("ob1"), vm.uiState.value.chatItems.map { it.id })
        assertTrue(vm.awaitingOnboardCode.value)
    }

    @Test
    fun `an agent first saved by the chat screen is saved with a lower-case address`() = runTest(testDispatcher) {
        // The same form AgentViewModel and Setup save in (W16AAPPLE-57).
        vm.connectToAgent("0x" + "AB".repeat(32))
        testScheduler.advanceUntilIdle()

        // This fake matches addresses exactly, so only the saved form is found.
        assertNotNull(agentRepo.getAgentByAddress("0x" + "ab".repeat(32)))
        assertNull(agentRepo.getAgentByAddress("0x" + "AB".repeat(32)))
    }

    @Test
    fun `a new conversation keeps an open onboarding gate and nothing else`() = runTest(testDispatcher) {
        val address = "0x" + "cd".repeat(32)
        connectUseCase.emit(ChatEvent.ChatItemReceived(ChatItem.User(id = "u1", content = "earlier")))
        connectUseCase.emit(
            ChatEvent.ChatItemReceived(ChatItem.OnboardRequired(id = "ob1", methods = listOf("invite_code")))
        )
        testScheduler.advanceUntilIdle()

        vm.connectToAgent(address)
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("ob1"), vm.uiState.value.chatItems.map { it.id })
    }

    @Test
    fun `a new conversation drops an onboarding card that has already been answered`() = runTest(testDispatcher) {
        val address = "0x" + "ef".repeat(32)
        connectUseCase.emit(
            ChatEvent.ChatItemReceived(ChatItem.OnboardRequired(id = "ob1", methods = listOf("invite_code")))
        )
        connectUseCase.emit(
            ChatEvent.ChatItemReceived(ChatItem.OnboardSuccess(id = "os1", level = "basic", message = "Welcome"))
        )
        testScheduler.advanceUntilIdle()

        vm.connectToAgent(address)
        testScheduler.advanceUntilIdle()

        assertEquals(emptyList<String>(), vm.uiState.value.chatItems.map { it.id })
        assertFalse(vm.awaitingOnboardCode.value)
    }

    @Test
    fun `awaitingOnboardCode clears once OnboardSuccess arrives`() {
        connectUseCase.emit(
            ChatEvent.ChatItemReceived(ChatItem.OnboardRequired(id = "ob1", methods = listOf("invite_code")))
        )
        assertTrue(vm.awaitingOnboardCode.value)

        connectUseCase.emit(
            ChatEvent.ChatItemReceived(ChatItem.OnboardSuccess(id = "os1", level = "basic", message = "Welcome"))
        )

        assertFalse(vm.awaitingOnboardCode.value)
    }

    @Test
    fun `invite-code rejection flow comes in as a ChatItemReceived OnboardingFailed (not a ConnectionState Error)`() = runTest(testDispatcher) {
        // Invite-code rejections now arrive as ChatItemReceived(OnboardingFailed) replacing the OnboardRequired card in place, not as a ConnectionState.Error — see AgentConnectionRobolectricTest for the emit-side coverage.
        connectUseCase.emit(
            ChatEvent.ChatItemReceived(
                ChatItem.OnboardRequired(id = "ob1", methods = listOf("invite_code"))
            )
        )
        testScheduler.runCurrent()

        connectUseCase.emit(
            ChatEvent.ChatItemReceived(
                ChatItem.OnboardingFailed(id = "ob1", reason = "Invalid invite code")
            )
        )
        testScheduler.runCurrent()

        val items = vm.uiState.value.chatItems
        assertEquals(
            "OnboardRequired must be replaced in place by OnboardingFailed",
            1, items.size
        )
        val failed = items.single() as ChatItem.OnboardingFailed
        assertEquals("ob1", failed.id)
        assertEquals("Invalid invite code", failed.reason)
        assertFalse(
            "OnboardingFailed must keep awaitingOnboardCode=true — banner should stay 'Waiting for invite code…'",
            vm.awaitingOnboardCode.value.not()
        )
    }

    @Test
    fun `ConnectionState Error without an OnboardRequired card does NOT add an OnboardingFailed`() = runTest(testDispatcher) {
        // Negative path: ConnectionState.Error outside onboarding (e.g. a
        // server-side crash mid-conversation) must NOT inject a stray
        // OnboardingFailed chat item into the chat list. The card is a
        // gate-state-machine item, not a generic error indicator.
        connectUseCase.forceState(ConnectionState.Error(message = "Internal server error"))
        testScheduler.runCurrent()

        assertTrue(
            "no OnboardingFailed should be emitted when no OnboardRequired gate was on screen",
            vm.uiState.value.chatItems.none { it is ChatItem.OnboardingFailed }
        )
    }

    // ── startNewSession / switchToSession / onCleared ──────────────────

    @Test
    fun `startNewSession clears chatItems and starts a new persisted session once an agent is known`() = runTest(testDispatcher) {
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)
        testScheduler.runCurrent()
        vm.sendMessage("hello")
        assertTrue(vm.uiState.value.chatItems.isNotEmpty())

        vm.startNewSession()
        testScheduler.runCurrent()

        assertTrue("chatItems must be cleared for the new session", vm.uiState.value.chatItems.isEmpty())
    }

    @Test
    fun `startNewSession is a no-op before any agent has connected`() = runTest(testDispatcher) {
        // No connectToAgent()/markConnected() — ensureActiveSession() never
        // ran, so ConversationHistoryUseCase has no agent id to work with.
        vm.startNewSession()
        testScheduler.runCurrent()

        assertTrue(vm.uiState.value.chatItems.isEmpty())
    }

    @Test
    fun `switchToSession loads that session's persisted messages into chatItems`() = runTest(testDispatcher) {
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)
        testScheduler.runCurrent()
        vm.sendMessage("first session message")
        testScheduler.runCurrent()
        val firstSessionId = vm.activeSessionId.value!!

        // Switch away (clearing the visible list, same as startNewSession's
        // own test above), send a message on the new session too, then
        // switch back — this is what exercises switchToSession's *restore*
        // path specifically, and that the two sessions' messages don't
        // collide (message ids are UUIDs, not a per-instance counter that
        // used to reset to 0 on startNewSession() and could mint the same
        // id "1" for both sessions' first message).
        vm.startNewSession()
        testScheduler.runCurrent()
        assertTrue(vm.uiState.value.chatItems.isEmpty())
        vm.sendMessage("second session message")
        testScheduler.runCurrent()

        vm.switchToSession(firstSessionId)
        testScheduler.runCurrent()

        val restored = vm.uiState.value.chatItems.single() as ChatItem.User
        assertEquals("first session message", restored.content)
        assertEquals(firstSessionId, vm.activeSessionId.value)
    }

    @Test
    fun `deleteSession clears the visible chat list when the deleted session was the active one`() = runTest(testDispatcher) {
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)
        testScheduler.runCurrent()
        vm.sendMessage("hello")
        testScheduler.runCurrent()
        val activeId = vm.activeSessionId.value!!
        assertTrue(vm.uiState.value.chatItems.isNotEmpty())

        vm.deleteSession(activeId)
        testScheduler.runCurrent()

        assertTrue("chatItems must be cleared once the open session is gone", vm.uiState.value.chatItems.isEmpty())
        assertNull(vm.activeSessionId.value)
    }

    @Test
    fun `deleteSession leaves the visible chat list untouched for a session that isn't the active one`() = runTest(testDispatcher) {
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)
        testScheduler.runCurrent()
        vm.sendMessage("hello")
        testScheduler.runCurrent()
        val activeId = vm.activeSessionId.value!!

        vm.deleteSession("some-other-session-id-not-open-here")
        testScheduler.runCurrent()

        assertTrue("current conversation must be untouched", vm.uiState.value.chatItems.isNotEmpty())
        assertEquals(activeId, vm.activeSessionId.value)
    }

    // ── renameSession ──────────────────────────────────────────────────

    @Test
    fun `renameSession persists the new title through the repository`() = runTest(testDispatcher) {
        val session = sessionRepo.seed("agent-1", "Old title")

        vm.renameSession(session.id, "New title")
        testScheduler.runCurrent()

        assertEquals("New title", sessionRepo.getSessionById(session.id)?.title)
    }

    // ── session_sync replay must not leak across conversations ───────────

    @Test
    fun `a replayed reply owned by another conversation never reaches the new chat`() = runTest(testDispatcher) {
        // Observed on device: tap "New chat", and seconds later the server's
        // session_sync (one per agent, not per local conversation) refilled the
        // empty chat with the *previous* conversation's replies. Room stayed
        // correct -- PersistenceTransaction refuses to re-file an owned row --
        // so it was the list on screen that lied.
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)
        testScheduler.runCurrent()

        vm.sendMessage("first session message")
        testScheduler.runCurrent()
        val reply = "the first conversation's reply"
        val replyId = stableAssistantId(reply)
        connectUseCase.emit(ChatEvent.OutputReceived(reply, null))
        testScheduler.runCurrent()
        val firstSessionId = vm.activeSessionId.value!!
        assertEquals(firstSessionId, messageRepo.getOwningSessionId(replyId))

        vm.startNewSession()
        testScheduler.runCurrent()
        vm.sendMessage("hi")
        testScheduler.runCurrent()

        // The server replays the whole agent transcript as ChatItemReceived,
        // exactly as ProtocolParser.parseSessionSync fans it out.
        connectUseCase.emit(
            ChatEvent.ChatItemReceived(
                ChatItem.Turn(id = replyId, agent = ChatItem.Agent(id = replyId, content = reply))
            )
        )
        testScheduler.runCurrent()

        assertTrue(
            "the previous conversation's reply was replayed into the new chat: " +
                vm.uiState.value.chatItems.map { it.id },
            vm.uiState.value.chatItems.none { it.id == replyId }
        )
        // And it stayed where it belongs rather than being re-filed.
        assertEquals(firstSessionId, messageRepo.getOwningSessionId(replyId))
    }

    @Test
    fun `a reply that belongs to no conversation yet still renders`() = runTest(testDispatcher) {
        // The guard keys on "Room already files this id elsewhere", so an id
        // that has never been written -- every genuinely new reply -- must pass
        // straight through. Without this the fix would silence live traffic.
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)
        testScheduler.runCurrent()
        vm.sendMessage("hello")
        testScheduler.runCurrent()

        connectUseCase.emit(ChatEvent.OutputReceived("a brand new reply", null))
        testScheduler.runCurrent()

        val replyId = stableAssistantId("a brand new reply")
        assertTrue(
            "a new reply was dropped: " + vm.uiState.value.chatItems.map { it.id },
            vm.uiState.value.chatItems.any { it.id == replyId }
        )
    }

    @Test
    fun `a resumed conversation still accepts session_sync history`() = runTest(testDispatcher) {
        // The mirror image: a conversation being resumed is exactly the one
        // the server's history may belong to, so the guard must not block it.
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)
        testScheduler.runCurrent()
        vm.sendMessage("first session message")
        testScheduler.runCurrent()
        val sessionA = vm.activeSessionId.value!!
        vm.startNewSession()
        testScheduler.runCurrent()
        vm.switchToSession(sessionA)
        testScheduler.runCurrent()

        val restored = "an entry only the server still has"
        val restoredId = stableAssistantId(restored)
        connectUseCase.emit(
            ChatEvent.ChatItemReceived(
                ChatItem.Turn(id = restoredId, agent = ChatItem.Agent(id = restoredId, content = restored)),
                fromSessionSnapshot = true,
                answeredQuestion = "first session message"
            )
        )
        testScheduler.runCurrent()

        assertTrue(
            "a resumed conversation was denied its own history: " + vm.uiState.value.chatItems.map { it.id },
            vm.uiState.value.chatItems.any { it.id == restoredId }
        )
    }

    @Test
    fun `a reply that lands after switching away is filed in the chat that asked`() = runTest(testDispatcher) {
        // Reported from the device: ask in A, switch to B before the answer
        // arrives, and the answer appeared in B. It must go to A instead --
        // invisible in B, and there when the user switches back.
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)
        testScheduler.runCurrent()
        vm.sendMessage("a question asked in A")
        testScheduler.runCurrent()
        val sessionA = vm.activeSessionId.value!!

        // Switch away with the turn still unanswered. Deliberately without
        // sending anything here: asking a second question would overwrite the
        // connection's own `currentInputId` too, and ProtocolParser then drops
        // the older turn's OUTPUT as "not for us" long before the ViewModel
        // sees it — a different behaviour, out of this test's scope.
        vm.startNewSession()
        testScheduler.runCurrent()
        assertTrue("test setup: must have left A", vm.activeSessionId.value != sessionA)

        val late = "the answer A was waiting for"
        connectUseCase.emit(ChatEvent.OutputReceived(late, null))
        testScheduler.runCurrent()

        val lateId = stableAssistantId(late)
        assertTrue(
            "A's answer showed up in B: " + vm.uiState.value.chatItems.map { it.id },
            vm.uiState.value.chatItems.none { it.id == lateId }
        )
        assertEquals("the answer was not filed under the chat that asked", sessionA, messageRepo.getOwningSessionId(lateId))

        // And switching back surfaces it.
        vm.switchToSession(sessionA)
        testScheduler.runCurrent()
        assertTrue(
            "switching back to A did not load its answer: " + vm.uiState.value.chatItems.map { it.id },
            vm.uiState.value.chatItems.any { it.id == lateId }
        )
    }

    @Test
    fun `a full turn's progress frames do not hand the reply to the chat switched to`() = runTest(testDispatcher) {
        // The test above emits OutputReceived alone, which is not what a turn
        // looks like on the wire. A real one (device log, 01:52) is a burst:
        //
        //   session_sync -> ChatItemReceived(Turn)
        //   llm_call     -> ChatItemReceived(Turn)
        //   [user switches away here]
        //   llm_result   -> ChatItemUpdated(Turn)   <- no agent text yet
        //   OUTPUT       -> the actual reply
        //
        // Every one of those middle frames reduces to a non-null
        // itemToPersist, so releasing ownership on the first of them handed
        // the real reply to whatever conversation was on screen by then.
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)
        testScheduler.runCurrent()
        vm.sendMessage("a question asked in A")
        testScheduler.runCurrent()
        val sessionA = vm.activeSessionId.value!!

        // Progress frames that arrive while A is still on screen.
        connectUseCase.emit(
            ChatEvent.ChatItemReceived(
                ChatItem.Turn(
                    id = "turn-in-flight",
                    thinking = ChatItem.Thinking(id = "turn-in-flight", status = ThinkingStatus.RUNNING)
                )
            )
        )
        testScheduler.runCurrent()

        vm.startNewSession()
        testScheduler.runCurrent()
        assertTrue("test setup: must have left A", vm.activeSessionId.value != sessionA)

        // ...and the one that arrives after the switch. A Turn whose `agent`
        // is null is dropped by PersistenceTransaction, so this writes nothing
        // -- it must not be mistaken for the reply having been filed either.
        connectUseCase.emit(
            ChatEvent.ChatItemUpdated(
                ChatItem.Turn(
                    id = "turn-in-flight",
                    thinking = ChatItem.Thinking(id = "turn-in-flight", status = ThinkingStatus.DONE)
                )
            )
        )
        testScheduler.runCurrent()

        val late = "the answer A was waiting for"
        connectUseCase.emit(ChatEvent.OutputReceived(late, null))
        testScheduler.runCurrent()

        val lateId = stableAssistantId(late)
        assertTrue(
            "A's answer showed up in B: " + vm.uiState.value.chatItems.map { it.id },
            vm.uiState.value.chatItems.none { it.id == lateId }
        )
        assertEquals("the answer was not filed under the chat that asked", sessionA, messageRepo.getOwningSessionId(lateId))

        vm.switchToSession(sessionA)
        testScheduler.runCurrent()
        assertTrue(
            "switching back to A did not load its answer: " + vm.uiState.value.chatItems.map { it.id },
            vm.uiState.value.chatItems.any { it.id == lateId }
        )
    }

    @Test
    fun `a turn that ends in a server error releases ownership`() = runTest(testDispatcher) {
        // The mirror of the case above: no reply is coming, so the owner must
        // not stay pinned. Otherwise the next persistable item to arrive while
        // the user reads B would be filed into A instead.
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)
        testScheduler.runCurrent()
        vm.sendMessage("a question asked in A")
        testScheduler.runCurrent()
        val sessionA = vm.activeSessionId.value!!

        vm.startNewSession()
        testScheduler.runCurrent()
        val sessionB = vm.activeSessionId.value

        connectUseCase.emit(ChatEvent.ConnectionErrorOccurred("the turn failed"))
        testScheduler.runCurrent()

        // With the turn closed out, a later reply belongs to whatever is on
        // screen now -- B -- not to A.
        val later = "an answer asked for in B"
        connectUseCase.emit(ChatEvent.OutputReceived(later, null))
        testScheduler.runCurrent()

        val laterId = stableAssistantId(later)
        assertTrue(
            "a reply after the failed turn was still pinned to A",
            messageRepo.getOwningSessionId(laterId) != sessionA
        )
        assertTrue(
            "the reply is not visible in the conversation on screen: " + vm.uiState.value.chatItems.map { it.id },
            vm.uiState.value.chatItems.any { it.id == laterId }
        )
        assertTrue("test setup: B must be a distinct conversation", sessionB != sessionA)
    }

    @Test
    fun `leaving a conversation mid-turn does not persist a failure marker`() = runTest(testDispatcher) {
        // The answer is still coming and will be filed into this conversation,
        // so a persisted "Failed" row would sit right next to it claiming it
        // never arrived. A real disconnect still records one.
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)
        testScheduler.runCurrent()
        vm.sendMessage("a question asked in A")
        testScheduler.runCurrent()
        val sessionA = vm.activeSessionId.value!!
        // sendMessage alone leaves nothing RUNNING, so without this the
        // assertion below could never have failed -- failRunningThinking
        // returns early and writes nothing either way.
        connectUseCase.emit(
            ChatEvent.ChatItemReceived(ChatItem.Thinking(id = "think-1", status = ThinkingStatus.RUNNING))
        )
        testScheduler.runCurrent()
        assertTrue(
            "test setup: a RUNNING item must exist for this to mean anything",
            vm.uiState.value.chatItems.any { it.isInProgress() }
        )

        vm.startNewSession()
        testScheduler.runCurrent()

        // The RUNNING row itself is persisted as it arrives -- that is normal.
        // What must NOT appear is that same item flipped to ERROR on the way
        // out, which is what a persisted failure marker looks like.
        val failed = messageRepo.snapshot()
            .filter { it.sessionId == sessionA }
            .mapNotNull { it.payload?.let(TranscriptItemCodec::decode) }
            .filterIsInstance<ChatItem.Thinking>()
            .filter { it.status == ThinkingStatus.ERROR }
        assertTrue("a failure marker was written into the conversation being left: $failed", failed.isEmpty())
    }

    // ── SESSION_STATUS-based revert of a disconnect false alarm ─────────

    @Test
    fun `a disconnect-failed turn is un-failed when SESSION_STATUS says the server is still running it`() = runTest(testDispatcher) {
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)
        testScheduler.runCurrent()
        vm.sendMessage("a question asked in A")
        testScheduler.runCurrent()

        connectUseCase.emit(
            ChatEvent.ChatItemReceived(ChatItem.Thinking(id = "think-1", status = ThinkingStatus.RUNNING))
        )
        testScheduler.runCurrent()

        // The reconnect ladder exhausts itself (AgentConnection gives up) --
        // the ViewModel force-fails the still-in-flight Thinking bubble.
        connectUseCase.forceState(ConnectionState.Disconnected)
        testScheduler.runCurrent()
        assertEquals(
            "test setup: the turn must be marked failed before a revert means anything",
            ThinkingStatus.ERROR,
            (vm.uiState.value.chatItems.first { it.id == "think-1" } as ChatItem.Thinking).status
        )

        // A later manual reconnect (e.g. the app comes back to the
        // foreground) succeeds -- this must trigger exactly one
        // SESSION_STATUS query to find out whether that failure was real.
        connectUseCase.markConnected(address)
        testScheduler.runCurrent()
        assertEquals(1, connectUseCase.querySessionStatusCalls)

        // The server says the agent thread never actually stopped.
        connectUseCase.emit(ChatEvent.SessionStatusReceived(sessionId = "sess-1", status = "running"))
        testScheduler.runCurrent()

        assertEquals(
            "SESSION_STATUS confirmed the turn is still running -- the ERROR mark must be reverted",
            ThinkingStatus.RUNNING,
            (vm.uiState.value.chatItems.first { it.id == "think-1" } as ChatItem.Thinking).status
        )
    }

    @Test
    fun `a disconnect-failed turn stops saying Failed once the replay carries its answer`() =
        runTest(testDispatcher) {
            val address = validHexAddress()
            vm.connectToAgent(address)
            testScheduler.runCurrent()
            connectUseCase.markConnected(address)
            testScheduler.runCurrent()
            vm.sendMessage("你好")
            testScheduler.runCurrent()
            connectUseCase.emit(
                ChatEvent.ChatItemReceived(ChatItem.Thinking(id = "think-1", status = ThinkingStatus.RUNNING))
            )
            testScheduler.runCurrent()

            connectUseCase.forceState(ConnectionState.Reconnecting)
            testScheduler.runCurrent()
            connectUseCase.markConnected(address)
            testScheduler.runCurrent()

            // The server's replay brings the answer the lost OUTPUT owed.
            connectUseCase.emit(
                ChatEvent.ChatItemReceived(ChatItem.Agent(id = "asst-1", content = "你好！我是一个网络自动化助手。"))
            )
            testScheduler.runCurrent()
            connectUseCase.emit(ChatEvent.SessionStatusReceived(sessionId = "sess-1", status = "connected"))
            testScheduler.runCurrent()

            assertEquals(
                "Failed must not sit directly above the reply it claims never arrived",
                ThinkingStatus.DONE,
                (vm.uiState.value.chatItems.first { it.id == "think-1" } as ChatItem.Thinking).status
            )
        }

    @Test
    fun `a disconnect-failed turn keeps saying Failed when the replay brings nothing`() =
        runTest(testDispatcher) {
            val address = validHexAddress()
            vm.connectToAgent(address)
            testScheduler.runCurrent()
            connectUseCase.markConnected(address)
            testScheduler.runCurrent()
            vm.sendMessage("你好")
            testScheduler.runCurrent()
            connectUseCase.emit(
                ChatEvent.ChatItemReceived(ChatItem.Thinking(id = "think-1", status = ThinkingStatus.RUNNING))
            )
            testScheduler.runCurrent()

            connectUseCase.forceState(ConnectionState.Reconnecting)
            testScheduler.runCurrent()
            connectUseCase.markConnected(address)
            testScheduler.runCurrent()
            connectUseCase.emit(ChatEvent.SessionStatusReceived(sessionId = "sess-1", status = "connected"))
            testScheduler.runCurrent()

            assertEquals(
                "the run ended with nothing to show, so Retry stays the obvious move",
                ThinkingStatus.ERROR,
                (vm.uiState.value.chatItems.first { it.id == "think-1" } as ChatItem.Thinking).status
            )
        }

    @Test
    fun `a disconnect-failed turn stays failed when SESSION_STATUS says the session is gone`() = runTest(testDispatcher) {
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)
        testScheduler.runCurrent()
        vm.sendMessage("a question asked in A")
        testScheduler.runCurrent()

        connectUseCase.emit(
            ChatEvent.ChatItemReceived(ChatItem.Thinking(id = "think-1", status = ThinkingStatus.RUNNING))
        )
        testScheduler.runCurrent()

        connectUseCase.forceState(ConnectionState.Disconnected)
        testScheduler.runCurrent()

        connectUseCase.markConnected(address)
        testScheduler.runCurrent()

        // The server genuinely lost the session -- the original ERROR mark
        // was correct and must NOT be reverted.
        connectUseCase.emit(ChatEvent.SessionStatusReceived(sessionId = "sess-1", status = "not_found"))
        testScheduler.runCurrent()

        assertEquals(
            ThinkingStatus.ERROR,
            (vm.uiState.value.chatItems.first { it.id == "think-1" } as ChatItem.Thinking).status
        )
    }

    @Test
    fun `connecting with nothing disconnect-failed does not query SESSION_STATUS`() = runTest(testDispatcher) {
        // A fresh/ordinary connect has nothing to reconcile -- querying on
        // every single Connected transition would be pure waste.
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)
        testScheduler.runCurrent()

        assertEquals(0, connectUseCase.querySessionStatusCalls)
    }

    // ── CONNECTED status: running ───────────────────────────────────────

    @Test
    fun `a message typed after joining a running turn is held for it, then rides it and waits for the ACK`() =
        runTest(testDispatcher) {
            val address = validHexAddress()
            vm.connectToAgent(address)
            testScheduler.runCurrent()
            // Relaunched into a turn the Host is still running: this process never sent its INPUT.
            connectUseCase.markConnected(address, status = ServerSessionStatus.RUNNING)
            testScheduler.runCurrent()

            vm.sendMessage("and check the logs too")
            testScheduler.runCurrent()

            assertEquals(
                "held behind the running turn rather than going out as a plain INPUT",
                emptyList<String>(),
                connectUseCase.sentMessages.map { it.content }
            )
            assertEquals(UserMessageState.QUEUED, userMessage("and check the logs too").state)

            testScheduler.advanceTimeBy(ChatViewModel.TURN_WAIT_TIMEOUT_MS + 1)
            testScheduler.runCurrent()
            assertEquals(
                "it rides the turn, and the app now waits for the Host's answer",
                UserMessageState.AWAITING_ACK,
                userMessage("and check the logs too").state
            )

            connectUseCase.emit(ChatEvent.RuntimeInputAcknowledged(sessionId = "sess-1"))
            testScheduler.runCurrent()
            assertEquals(UserMessageState.SENT, userMessage("and check the logs too").state)
        }

    @Test
    fun `a disconnect-failed turn is un-failed by the CONNECTED status, without asking SESSION_STATUS`() =
        runTest(testDispatcher) {
            val address = validHexAddress()
            vm.connectToAgent(address)
            testScheduler.runCurrent()
            connectUseCase.markConnected(address)
            testScheduler.runCurrent()
            vm.sendMessage("a question asked in A")
            testScheduler.runCurrent()
            connectUseCase.emit(
                ChatEvent.ChatItemReceived(ChatItem.Thinking(id = "think-1", status = ThinkingStatus.RUNNING))
            )
            testScheduler.runCurrent()
            connectUseCase.forceState(ConnectionState.Disconnected)
            testScheduler.runCurrent()
            assertEquals(
                "test setup: the drop must have failed the turn before reviving it means anything",
                ThinkingStatus.ERROR,
                (vm.uiState.value.chatItems.first { it.id == "think-1" } as ChatItem.Thinking).status
            )

            connectUseCase.markConnected(address, status = ServerSessionStatus.RUNNING)
            testScheduler.runCurrent()

            assertEquals(
                "CONNECTED said the turn is still running, so the Failed mark goes",
                ThinkingStatus.RUNNING,
                (vm.uiState.value.chatItems.first { it.id == "think-1" } as ChatItem.Thinking).status
            )
            assertEquals("the status answered it, so there is nothing to ask", 0, connectUseCase.querySessionStatusCalls)
        }

    @Test
    fun `joining a live connection to a running turn holds the next message, and the answer is acted on once`() =
        runTest(testDispatcher) {
            val address = validHexAddress()
            // The Loading probe's CONNECT, before this screen has asked for anything.
            connectUseCase.markConnected(address, status = ServerSessionStatus.RUNNING)
            testScheduler.runCurrent()
            assertEquals("not before the screen knows which conversation it is on", 0, connectUseCase.releasedStatuses)

            vm.connectToAgent(address)
            testScheduler.runCurrent()
            assertEquals("acted on, then let go, exactly once", 1, connectUseCase.releasedStatuses)

            vm.sendMessage("and check the logs too")
            testScheduler.runCurrent()
            assertEquals(emptyList<String>(), connectUseCase.sentMessages.map { it.content })
        }

    @Test
    fun `a message typed after joining an idle session goes straight out`() = runTest(testDispatcher) {
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address, status = ServerSessionStatus.CONNECTED)
        testScheduler.runCurrent()

        vm.sendMessage("hello")
        testScheduler.runCurrent()

        assertEquals(listOf("hello"), connectUseCase.sentMessages.map { it.content })
        assertEquals(UserMessageState.SENT, userMessage("hello").state)
    }

    // ── CONNECTED status: connected (idle) ──────────────────────────────

    @Test
    fun `a Work Room left working says it ended once the Host says the session is idle`() = runTest(testDispatcher) {
        // W16AAPPLE-33: the run finished while the socket was down, and an idle Host replays nothing.
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)
        testScheduler.runCurrent()
        connectUseCase.emit(
            ChatEvent.ChatItemUpdated(
                ChatItem.ProviderInvocation(id = "codex:call-1", provider = "codex", stateRevision = 1)
            )
        )
        testScheduler.runCurrent()
        connectUseCase.forceState(ConnectionState.Disconnected)
        testScheduler.runCurrent()

        connectUseCase.markConnected(address, status = ServerSessionStatus.CONNECTED)
        testScheduler.runCurrent()

        val run = vm.uiState.value.chatItems.first { it.id == "codex:call-1" } as ChatItem.ProviderInvocation
        assertEquals(ProviderInvocationStatus.ENDED, run.status)
    }

    @Test
    fun `a card left spinning is settled once the Host says the session is idle`() = runTest(testDispatcher) {
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)
        testScheduler.runCurrent()
        connectUseCase.emit(
            ChatEvent.ChatItemReceived(ChatItem.ToolCall(id = "tool-1", name = "bash", status = ToolStatus.RUNNING))
        )
        testScheduler.runCurrent()
        // The socket drops mid-tool and the turn ends while nobody is listening, so no tool_result ever comes.
        connectUseCase.forceState(ConnectionState.Disconnected)
        testScheduler.runCurrent()
        assertTrue(
            "test setup: a drop does not settle a tool call, so it is still spinning",
            vm.uiState.value.chatItems.any { it.id == "tool-1" && it.isInProgress() }
        )

        connectUseCase.markConnected(address, status = ServerSessionStatus.CONNECTED)
        testScheduler.runCurrent()

        assertEquals(
            ToolStatus.DONE,
            (vm.uiState.value.chatItems.first { it.id == "tool-1" } as ChatItem.ToolCall).status
        )
        assertEquals("acted on, then let go, exactly once", 1, connectUseCase.releasedStatuses)

        vm.sendMessage("hello")
        testScheduler.runCurrent()
        assertEquals(
            "nothing is left running, so the message is not held",
            listOf("hello"),
            connectUseCase.sentMessages.map { it.content }
        )
    }

    @Test
    fun `a turn the drop failed is left for SESSION_STATUS to settle`() = runTest(testDispatcher) {
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)
        testScheduler.runCurrent()
        vm.sendMessage("a question asked in A")
        testScheduler.runCurrent()
        connectUseCase.emit(
            ChatEvent.ChatItemReceived(ChatItem.Thinking(id = "think-1", status = ThinkingStatus.RUNNING))
        )
        testScheduler.runCurrent()
        connectUseCase.forceState(ConnectionState.Disconnected)
        testScheduler.runCurrent()

        connectUseCase.markConnected(address, status = ServerSessionStatus.CONNECTED)
        testScheduler.runCurrent()

        assertEquals(
            "whether the reply arrived is for the replay to say, so Failed stays until it does",
            ThinkingStatus.ERROR,
            (vm.uiState.value.chatItems.first { it.id == "think-1" } as ChatItem.Thinking).status
        )
        // At least once: releasing the status comes back as one more Connected, which asks again.
        assertTrue("and the Host is still asked", connectUseCase.querySessionStatusCalls >= 1)
    }

    @Test
    fun `cards of a turn the Host says is still running keep spinning`() = runTest(testDispatcher) {
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)
        testScheduler.runCurrent()
        connectUseCase.emit(
            ChatEvent.ChatItemReceived(ChatItem.ToolCall(id = "tool-1", name = "bash", status = ToolStatus.RUNNING))
        )
        testScheduler.runCurrent()

        connectUseCase.markConnected(address, status = ServerSessionStatus.RUNNING)
        testScheduler.runCurrent()

        assertEquals(
            ToolStatus.RUNNING,
            (vm.uiState.value.chatItems.first { it.id == "tool-1" } as ChatItem.ToolCall).status
        )
    }

    /** What an llm_call frame becomes: a footer with no reply yet, still running. */
    private fun runningFooter(id: String) =
        ChatItem.Turn(id = id, thinking = ChatItem.Thinking(id = id, status = ThinkingStatus.RUNNING))

    private fun footerStatus(id: String) =
        (vm.uiState.value.chatItems.first { it.id == id } as ChatItem.Turn).thinking?.status

    @Test
    fun `a thinking footer left running is settled once the Host says the session is idle`() =
        runTest(testDispatcher) {
            val address = validHexAddress()
            vm.connectToAgent(address)
            testScheduler.runCurrent()
            connectUseCase.markConnected(address)
            testScheduler.runCurrent()
            connectUseCase.emit(ChatEvent.ChatItemReceived(runningFooter("turn-1")))
            testScheduler.runCurrent()

            connectUseCase.markConnected(address, status = ServerSessionStatus.CONNECTED)
            testScheduler.runCurrent()

            assertEquals(ThinkingStatus.DONE, footerStatus("turn-1"))
        }

    @Test
    fun `a turn the drop failed stays failed when the Host says the session is idle`() = runTest(testDispatcher) {
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)
        testScheduler.runCurrent()
        vm.sendMessage("a question asked in A")
        testScheduler.runCurrent()
        connectUseCase.emit(ChatEvent.ChatItemReceived(runningFooter("turn-1")))
        testScheduler.runCurrent()
        connectUseCase.forceState(ConnectionState.Disconnected)
        testScheduler.runCurrent()
        assertEquals("test setup: the drop failed the turn", ThinkingStatus.ERROR, footerStatus("turn-1"))

        connectUseCase.markConnected(address, status = ServerSessionStatus.CONNECTED)
        testScheduler.runCurrent()

        assertEquals(
            "no reply has arrived, so Retry stays the obvious move",
            ThinkingStatus.ERROR,
            footerStatus("turn-1")
        )
    }

    // ── CONNECTED status: new ───────────────────────────────────────────

    @Test
    fun `a turn the Host has no record of is marked failed and its other cards stop spinning`() =
        runTest(testDispatcher) {
            val address = validHexAddress()
            vm.connectToAgent(address)
            testScheduler.runCurrent()
            connectUseCase.markConnected(address)
            testScheduler.runCurrent()
            connectUseCase.emit(ChatEvent.ChatItemReceived(runningFooter("turn-1")))
            connectUseCase.emit(
                ChatEvent.ChatItemReceived(ChatItem.ToolCall(id = "tool-1", name = "bash", status = ToolStatus.RUNNING))
            )
            testScheduler.runCurrent()

            // The Host restarted, or the session expired, while the app was away: what it was running is gone.
            connectUseCase.markConnected(address, status = ServerSessionStatus.NEW)
            testScheduler.runCurrent()

            assertEquals(ThinkingStatus.ERROR, footerStatus("turn-1"))
            assertEquals(
                ToolStatus.DONE,
                (vm.uiState.value.chatItems.first { it.id == "tool-1" } as ChatItem.ToolCall).status
            )
            assertEquals("acted on, then let go, exactly once", 1, connectUseCase.releasedStatuses)
        }

    @Test
    fun `a brand-new conversation joined with status new is left alone`() = runTest(testDispatcher) {
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        val before = vm.uiState.value.chatItems

        connectUseCase.markConnected(address, status = ServerSessionStatus.NEW)
        testScheduler.runCurrent()

        assertEquals("nothing was in flight, so nothing changes", before, vm.uiState.value.chatItems)
        vm.sendMessage("hello")
        testScheduler.runCurrent()
        assertEquals(listOf("hello"), connectUseCase.sentMessages.map { it.content })
    }

    @Test
    fun `a turn the drop failed stays failed, without asking, when the Host has no record`() =
        runTest(testDispatcher) {
            val address = validHexAddress()
            vm.connectToAgent(address)
            testScheduler.runCurrent()
            connectUseCase.markConnected(address)
            testScheduler.runCurrent()
            vm.sendMessage("a question asked in A")
            testScheduler.runCurrent()
            connectUseCase.emit(ChatEvent.ChatItemReceived(runningFooter("turn-1")))
            testScheduler.runCurrent()
            connectUseCase.forceState(ConnectionState.Disconnected)
            testScheduler.runCurrent()

            connectUseCase.markConnected(address, status = ServerSessionStatus.NEW)
            testScheduler.runCurrent()

            assertEquals(ThinkingStatus.ERROR, footerStatus("turn-1"))
            assertEquals(
                "not found is already the answer, so there is nothing to ask",
                0,
                connectUseCase.querySessionStatusCalls
            )
        }

    // ── Messages parked while offline, and the server's newer transcript ─

    private fun contentsOnScreen() = vm.uiState.value.chatItems.map {
        when (it) {
            is ChatItem.User -> it.content
            is ChatItem.Agent -> it.content
            is ChatItem.Turn -> it.agent?.content.orEmpty()
            else -> ""
        }
    }

    @Test
    fun `a message typed offline stops saying it is waiting once the connection is back`() =
        runTest(testDispatcher) {
            val address = validHexAddress()
            vm.connectToAgent(address)
            testScheduler.runCurrent()
            vm.sendMessage("typed offline")
            testScheduler.runCurrent()
            assertEquals("test setup", UserMessageState.QUEUED, userMessage("typed offline").state)

            connectUseCase.markConnected(address)
            testScheduler.runCurrent()

            assertEquals(UserMessageState.SENT, userMessage("typed offline").state)
        }

    @Test
    fun `stopping the connection keeps the messages it was holding waiting to send`() =
        runTest(testDispatcher) {
            // W16AAPPLE-106: Stop keeps the outbox, as Reconnect and the
            // connect timeout do, so "Waiting to send" stays true: the message
            // goes out on the next connect.
            val address = validHexAddress()
            vm.connectToAgent(address)
            testScheduler.runCurrent()
            vm.sendMessage("typed offline")
            testScheduler.runCurrent()
            assertEquals("test setup", UserMessageState.QUEUED, userMessage("typed offline").state)

            vm.disconnect()
            testScheduler.runCurrent()

            assertEquals(1, connectUseCase.disconnectCount)
            assertEquals("Stop must keep the outbox", listOf(true), connectUseCase.disconnectKeptOutbox)
            assertEquals(UserMessageState.QUEUED, userMessage("typed offline").state)
            assertFalse("not failed, so no Retry for a message that will still go", savedAsFailed("typed offline"))
            assertTrue("saved as waiting, so a restart agrees", savedInOutbox("typed offline"))

            connectUseCase.markConnected(address)
            testScheduler.runCurrent()

            assertEquals("the next connect flushes it", UserMessageState.SENT, userMessage("typed offline").state)
            assertFalse("and it is saved as gone", savedInOutbox("typed offline"))
        }

    @Test
    fun `a message saved as waiting comes back waiting, and stops once the connection is back`() =
        runTest(testDispatcher) {
            // W16AAPPLE-106: still in the outbox after a restart, so not sent yet.
            val address = validHexAddress()
            val session = seedPreviousConversation(address)
            messageRepo.seed(
                ChatMessage(
                    id = "m3", sessionId = session.id, role = Role.USER, content = "typed before the restart",
                    timestamp = 300L, inOutbox = true
                )
            )

            vm.connectToAgent(address)
            testScheduler.runCurrent()

            assertEquals(UserMessageState.QUEUED, userMessage("typed before the restart").state)
            assertFalse("not failed: the outbox will still send it", savedAsFailed("typed before the restart"))

            connectUseCase.markConnected(address)
            testScheduler.runCurrent()

            assertEquals(UserMessageState.SENT, userMessage("typed before the restart").state)
            assertFalse("and saved as gone", savedInOutbox("typed before the restart"))
        }

    @Test
    fun `a waiting message the connection already sent before the chat opened stops waiting`() =
        runTest(testDispatcher) {
            // W16AAPPLE-106: on relaunch the Loading screen connects first and the
            // outbox goes out then, before this screen loads the conversation. It
            // sees Connected once, too early, so it has to ask the outbox instead.
            val address = validHexAddress()
            val session = seedPreviousConversation(address)
            messageRepo.seed(
                ChatMessage(
                    id = "m3", sessionId = session.id, role = Role.USER, content = "sent by the Loading screen",
                    timestamp = 300L, inOutbox = true
                )
            )
            connectUseCase.markConnected(address)
            connectUseCase.queuedCounts[session.id] = 0
            testScheduler.runCurrent()

            vm.connectToAgent(address)
            testScheduler.runCurrent()

            assertEquals(UserMessageState.SENT, userMessage("sent by the Loading screen").state)
            assertFalse("and saved as gone, so the next restart agrees", savedInOutbox("sent by the Loading screen"))
        }

    @Test
    fun `of the waiting messages restored, only as many as the outbox still holds keep waiting`() =
        runTest(testDispatcher) {
            // The outbox sends oldest first, so the ones still in it are the newest.
            val address = validHexAddress()
            val session = seedPreviousConversation(address)
            messageRepo.seed(
                ChatMessage(id = "m3", sessionId = session.id, role = Role.USER, content = "older", timestamp = 300L, inOutbox = true),
                ChatMessage(id = "m4", sessionId = session.id, role = Role.USER, content = "newer", timestamp = 400L, inOutbox = true)
            )
            connectUseCase.markConnected(address)
            connectUseCase.queuedCounts[session.id] = 1
            testScheduler.runCurrent()

            vm.connectToAgent(address)
            testScheduler.runCurrent()

            assertEquals(UserMessageState.SENT, userMessage("older").state)
            assertEquals(UserMessageState.QUEUED, userMessage("newer").state)
            assertTrue("the newer one is still saved as waiting", savedInOutbox("newer"))
        }

    @Test
    fun `a restored waiting message stays waiting when the outbox cannot be asked`() =
        runTest(testDispatcher) {
            val address = validHexAddress()
            val session = seedPreviousConversation(address)
            messageRepo.seed(
                ChatMessage(id = "m3", sessionId = session.id, role = Role.USER, content = "unknown", timestamp = 300L, inOutbox = true)
            )
            connectUseCase.markConnected(address)
            connectUseCase.queuedCountUnknown = true
            testScheduler.runCurrent()

            vm.connectToAgent(address)
            testScheduler.runCurrent()

            assertEquals(UserMessageState.QUEUED, userMessage("unknown").state)
        }

    @Test
    fun `a waiting message settles when the flush has finished, not when Connected arrives`() =
        runTest(testDispatcher) {
            // The connection reports Connected before it flushes the outbox.
            val address = validHexAddress()
            vm.connectToAgent(address)
            testScheduler.runCurrent()
            vm.sendMessage("typed offline")
            testScheduler.runCurrent()
            val conversation = vm.activeSessionId.value ?: error("test setup: no conversation")
            connectUseCase.queuedCounts[conversation] = 1

            connectUseCase.markConnected(address)
            testScheduler.runCurrent()
            assertEquals("still in the outbox", UserMessageState.QUEUED, userMessage("typed offline").state)

            connectUseCase.queuedCounts[conversation] = 0
            connectUseCase.emit(ChatEvent.OutboxFlushed(conversation))
            testScheduler.runCurrent()

            assertEquals(UserMessageState.SENT, userMessage("typed offline").state)
            assertFalse("and saved as gone", savedInOutbox("typed offline"))
        }

    @Test
    fun `a send that bounced during the flush keeps waiting, and is saved as waiting`() =
        runTest(testDispatcher) {
            // The flush stops at a send the socket refused, and that message
            // and those behind it stay queued: they have not gone (W16AAPPLE-106).
            val address = validHexAddress()
            val session = seedPreviousConversation(address)
            messageRepo.seed(
                ChatMessage(id = "m3", sessionId = session.id, role = Role.USER, content = "went out", timestamp = 300L, inOutbox = true),
                ChatMessage(id = "m4", sessionId = session.id, role = Role.USER, content = "bounced", timestamp = 400L, inOutbox = true)
            )
            vm.connectToAgent(address)
            testScheduler.runCurrent()
            assertEquals("test setup", UserMessageState.QUEUED, userMessage("went out").state)
            connectUseCase.queuedCounts[session.id] = 1

            connectUseCase.markConnected(address)
            connectUseCase.emit(ChatEvent.OutboxFlushed(session.id))
            testScheduler.runCurrent()

            assertEquals(UserMessageState.SENT, userMessage("went out").state)
            assertEquals(UserMessageState.QUEUED, userMessage("bounced").state)
            assertTrue("saved as waiting, so a restart agrees", savedInOutbox("bounced"))
            assertFalse(savedInOutbox("went out"))
        }

    @Test
    fun `the outbox is asked about the chat's own agent`() = runTest(testDispatcher) {
        // Not whichever agent the connection is on: the chat can load another
        // agent's conversation before the connection moves to it.
        val address = validHexAddress()
        val session = seedPreviousConversation(address)
        messageRepo.seed(
            ChatMessage(id = "m3", sessionId = session.id, role = Role.USER, content = "waiting", timestamp = 300L, inOutbox = true)
        )
        connectUseCase.markConnected("0x" + "ee".repeat(32))
        testScheduler.runCurrent()

        vm.connectToAgent(address)
        testScheduler.runCurrent()

        assertTrue("asked about ${connectUseCase.queuedCountAgents}", connectUseCase.queuedCountAgents.isNotEmpty())
        assertTrue(connectUseCase.queuedCountAgents.all { it == address })
    }

    @Test
    fun `Clear chat names the conversation it clears`() = runTest(testDispatcher) {
        // So the connection side clears that one, even if another is open by
        // the time it runs (W16AAPPLE-106).
        val address = validHexAddress()
        val session = seedPreviousConversation(address)
        vm.connectToAgent(address)
        testScheduler.runCurrent()

        vm.clearChat()
        testScheduler.runCurrent()

        assertEquals(listOf<String?>(session.id), connectUseCase.resetConversations)
    }

    @Test
    fun `stopping the connection leaves a message that already went out alone`() =
        runTest(testDispatcher) {
            // Only the outbox is dropped; a message already on the wire is not in it.
            val address = validHexAddress()
            vm.connectToAgent(address)
            connectUseCase.markConnected(address)
            testScheduler.runCurrent()
            vm.sendMessage("sent live")
            testScheduler.runCurrent()
            val before = userMessage("sent live").state
            assertTrue("test setup: $before", before != UserMessageState.QUEUED && before != UserMessageState.FAILED)

            vm.disconnect()
            testScheduler.runCurrent()

            assertEquals(before, userMessage("sent live").state)
        }

    @Test
    fun `the reply to a message typed offline lands below it`() = runTest(testDispatcher) {
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        vm.sendMessage("typed offline")
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)
        testScheduler.runCurrent()

        connectUseCase.emit(ChatEvent.OutputReceived(result = "the answer", session = null))
        testScheduler.runCurrent()

        assertEquals(listOf("typed offline", "the answer"), contentsOnScreen())
    }

    @Test
    fun `a message held behind a running turn stays queued when the connection comes back`() =
        runTest(testDispatcher) {
            val address = validHexAddress()
            vm.connectToAgent(address)
            testScheduler.runCurrent()
            connectUseCase.markConnected(address)
            testScheduler.runCurrent()
            vm.sendMessage("first")
            testScheduler.runCurrent()
            vm.sendMessage("second")
            testScheduler.runCurrent()
            assertEquals("test setup", UserMessageState.QUEUED, userMessage("second").state)

            connectUseCase.markConnected(address, status = ServerSessionStatus.RUNNING)
            testScheduler.runCurrent()

            assertEquals(
                "it is waiting for the turn, not for the socket",
                UserMessageState.QUEUED,
                userMessage("second").state
            )
        }

    @Test
    fun `a message typed offline survives the server's newer transcript, and nothing is doubled`() =
        runTest(testDispatcher) {
            val address = validHexAddress()
            seedPreviousConversation(address)
            vm.connectToAgent(address)
            testScheduler.runCurrent()
            vm.sendMessage("typed offline")
            testScheduler.runCurrent()

            connectUseCase.markConnected(address, status = ServerSessionStatus.CONNECTED)
            connectUseCase.emit(
                ChatEvent.ServerTranscriptReceived(
                    entries = listOf(
                        ServerTranscriptEntry("hello"),
                        ServerTranscriptEntry("answered while you were away")
                    ),
                    turn = 3
                )
            )
            testScheduler.runCurrent()

            val contents = contentsOnScreen()
            assertEquals("the unsent message is kept", 1, contents.count { it == "typed offline" })
            assertEquals("the reply it never saw arrives", 1, contents.count { it == "answered while you were away" })
            assertEquals("what was already there is not doubled", 1, contents.count { it == "hello" })
        }

    @Test
    fun `the same server transcript applied twice adds nothing the second time`() = runTest(testDispatcher) {
        val address = validHexAddress()
        seedPreviousConversation(address)
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)
        testScheduler.runCurrent()
        val transcript = ChatEvent.ServerTranscriptReceived(
            entries = listOf(ServerTranscriptEntry("hello"), ServerTranscriptEntry("answered while you were away")),
            turn = 3
        )

        connectUseCase.emit(transcript)
        testScheduler.runCurrent()
        connectUseCase.emit(transcript)
        testScheduler.runCurrent()

        assertEquals(1, contentsOnScreen().count { it == "answered while you were away" })
        assertEquals(1, messageRepo.snapshot().count { it.content == "answered while you were away" })
    }

    @Test
    fun `a reply the live stream already delivered is not doubled by the transcript`() = runTest(testDispatcher) {
        val address = validHexAddress()
        seedPreviousConversation(address)
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)
        testScheduler.runCurrent()
        connectUseCase.emit(ChatEvent.OutputReceived(result = "answered while you were away", session = null))
        testScheduler.runCurrent()

        connectUseCase.emit(
            ChatEvent.ServerTranscriptReceived(
                entries = listOf(ServerTranscriptEntry("hello"), ServerTranscriptEntry("answered while you were away")),
                turn = 3
            )
        )
        testScheduler.runCurrent()

        assertEquals(1, contentsOnScreen().count { it == "answered while you were away" })
    }

    @Test
    fun `onCleared leaves the shared connection alive for the next screen`() {
        // connectUseCase is app-scoped (see AppContainer): close() is terminal
        // — it cancels the repository's scope for good — so a ViewModel calling
        // it on teardown would brick the connection every other screen shares.
        vm.onClearedForTest()

        assertEquals(0, connectUseCase.disconnectCount)
    }

    // ── startAccountProfileAutoRefresh ───────────────────────────────────

    // ── Resume on open ─────────────────────────────────────────────────

    /** Seeds an agent plus one saved conversation with two persisted rows. */
    private fun seedPreviousConversation(address: String): ChatSession {
        agentRepo.seed(
            AgentProfile(
                id = "agent-1", address = address, name = "Test", serverUrl = "https://x.com",
                createdAt = 0L, isActive = true
            )
        )
        val session = sessionRepo.seed("agent-1", "Yesterday's chat", messageCount = 2)
        messageRepo.seed(
            ChatMessage(id = "m1", sessionId = session.id, role = Role.USER, content = "hi", timestamp = 100L),
            ChatMessage(id = "m2", sessionId = session.id, role = Role.ASSISTANT, content = "hello", timestamp = 200L)
        )
        return session
    }

    @Test
    fun `connecting renders the previous conversation before the server has answered`() = runTest(testDispatcher) {
        // The reported bug: after a force-stop, Room still held the messages
        // but the screen showed "No messages yet." Local history has to be
        // on screen from the restore, not from a server replay.
        val address = validHexAddress()
        val session = seedPreviousConversation(address)

        vm.connectToAgent(address)
        testScheduler.runCurrent()

        assertEquals(session.id, vm.activeSessionId.value)
        assertEquals(listOf("hi", "hello"), vm.uiState.value.chatItems.map {
            when (it) {
                is ChatItem.User -> it.content
                is ChatItem.Turn -> it.agent?.content.orEmpty()
                else -> ""
            }
        })
    }

    @Test
    fun `a message saved as failed is still failed after a restart, and offered for resending`() = runTest(testDispatcher) {
        val address = validHexAddress()
        val session = seedPreviousConversation(address)
        messageRepo.seed(
            ChatMessage(
                id = "m3", sessionId = session.id, role = Role.USER, content = "never arrived",
                timestamp = 300L, failedToSend = true
            )
        )

        vm.connectToAgent(address)
        testScheduler.runCurrent()

        val users = vm.uiState.value.chatItems.filterIsInstance<ChatItem.User>()
        assertEquals(UserMessageState.SENT, users.single { it.id == "m1" }.state)
        assertEquals(UserMessageState.FAILED, users.single { it.id == "m3" }.state)
        // What the bar above the composer lists.
        assertEquals(listOf("m3"), vm.uiState.value.chatItems.failedUserMessages().map { it.id })
    }

    @Test
    fun `an unreachable server leaves the local records on screen, not an empty list`() = runTest(testDispatcher) {
        val address = validHexAddress()
        seedPreviousConversation(address)
        connectUseCase.connectResult = false

        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.forceState(ConnectionState.Error(message = "Connection refused"))
        testScheduler.runCurrent()

        val contents = vm.uiState.value.chatItems.mapNotNull {
            when (it) {
                is ChatItem.User -> it.content
                is ChatItem.Turn -> it.agent?.content
                else -> null
            }
        }
        assertTrue("local rows must survive the connection failure", contents.containsAll(listOf("hi", "hello")))
    }

    @Test
    fun `markStartNewSessionOnConnect still overrides the resume default`() = runTest(testDispatcher) {
        val address = validHexAddress()
        seedPreviousConversation(address)

        vm.markStartNewSessionOnConnect()
        vm.connectToAgent(address)
        testScheduler.runCurrent()

        assertTrue(vm.uiState.value.chatItems.isEmpty())
        assertNull("lazy creation: the fresh session isn't a Room row yet", vm.activeSessionId.value)
    }

    @Test
    fun `a reconnect does not re-push the resumed rows over what arrived live`() = runTest(testDispatcher) {
        val address = validHexAddress()
        seedPreviousConversation(address)
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)
        connectUseCase.emit(ChatEvent.ChatItemReceived(ChatItem.Agent(id = "live-1", content = "live reply")))
        testScheduler.runCurrent()

        connectUseCase.forceState(ConnectionState.Reconnecting)
        connectUseCase.markConnected(address)
        testScheduler.runCurrent()

        assertEquals(
            listOf("hi", "hello", "live reply"),
            vm.uiState.value.chatItems.map {
                when (it) {
                    is ChatItem.User -> it.content
                    // Reloaded rows come back as Turn; "live reply" arrives
                    // as a standalone Agent, so both shapes appear here.
                    is ChatItem.Turn -> it.agent?.content.orEmpty()
                    is ChatItem.Agent -> it.content
                    else -> ""
                }
            }
        )
    }


    // ── one server session per conversation ──────────────────────────

    @Test
    fun `the first message of a pending conversation moves the connection before it sends`() = runTest(testDispatcher) {
        // Device bug: the app joined a live connection, the conversation went
        // pending, and materialising its Room row on send told nobody. The
        // INPUT left on whatever session the socket already held, so the turn
        // was filed under another conversation server-side and the new one
        // was empty — nothing downstream can recover a reply that was never
        // stored against it.
        val address = validHexAddress()
        agentRepo.seed(
            AgentProfile(
                id = "agent-1", address = address, name = "Test", serverUrl = "https://x.com",
                createdAt = 0L, isActive = true
            )
        )
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)
        testScheduler.runCurrent()
        // The socket is on somebody else's session, exactly as it is after
        // joining a connection the loading probe opened.
        connectUseCase.connectedConversationId = "some-other-session"
        connectUseCase.interactions.clear()

        vm.sendMessage("Hi")
        testScheduler.runCurrent()

        val conversation = vm.activeSessionId.value
        assertNotNull("the send must have materialised a conversation", conversation)
        assertEquals(
            "the INPUT went out on a session this conversation does not own",
            conversation,
            connectUseCase.connectedConversationId
        )
        val switchIndex = connectUseCase.interactions.indexOf("switch:$conversation")
        val sendIndex = connectUseCase.interactions.indexOfFirst { it.startsWith("send:") }
        assertTrue("the connection was never moved: ${connectUseCase.interactions}", switchIndex >= 0)
        assertTrue(
            "the INPUT was sent before the CONNECT for its session: ${connectUseCase.interactions}",
            switchIndex < sendIndex
        )
    }

    @Test
    fun `sending again in the same conversation does not churn the connection`() = runTest(testDispatcher) {
        // The switch costs a socket round trip, so it must happen once per
        // conversation, not once per message.
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)
        testScheduler.runCurrent()
        vm.sendMessage("first")
        testScheduler.runCurrent()
        val conversation = vm.activeSessionId.value!!
        connectUseCase.interactions.clear()

        vm.sendMessage("second")
        testScheduler.runCurrent()

        assertEquals(
            "a second message in the same conversation must not re-CONNECT: " +
                connectUseCase.interactions,
            emptyList<String>(),
            connectUseCase.interactions.filter { it.startsWith("switch:") && it != "switch:$conversation" }
        )
        assertEquals(conversation, connectUseCase.connectedConversationId)
    }


    @Test
    fun `the conversation is resolved locally before the connection is moved`() = runTest(testDispatcher) {
        // Load-bearing ordering. The transcript the server replays after a
        // switch is merged into whatever conversation is active when it
        // lands, so resolving must finish first — the other order files the
        // new session's history into the conversation just left.
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)
        testScheduler.runCurrent()
        vm.sendMessage("in the first chat")
        testScheduler.runCurrent()
        val first = vm.activeSessionId.value!!
        vm.startNewSession()
        testScheduler.runCurrent()
        connectUseCase.switchedConversations.clear()
        connectUseCase.activeAtSwitch.clear()
        connectUseCase.activeSessionIdAtSwitch = { vm.activeSessionId.value }

        vm.switchToSession(first)
        testScheduler.runCurrent()

        assertEquals(listOf(first), connectUseCase.switchedConversations)
        assertEquals(
            "the connection was moved before the conversation was resolved",
            listOf<String?>(first),
            connectUseCase.activeAtSwitch
        )
    }

    @Test
    fun `a brand-new conversation connects on an id of its own, before it has a row`() = runTest(testDispatcher) {
        // The agent is known but has no conversations, so this goes Pending:
        // no Room row, yet the CONNECT must already name this chat's session.
        val address = validHexAddress()
        agentRepo.seed(
            AgentProfile(
                id = "agent-1", address = address, name = "Test", serverUrl = "https://x.com",
                createdAt = 0L, isActive = true
            )
        )

        vm.connectToAgent(address)
        testScheduler.runCurrent()

        assertNull("nothing is persisted yet", vm.activeSessionId.value)
        val connectedOn = connectUseCase.lastConnectConversationId
        assertNotNull("a brand-new conversation must still connect on its own session", connectedOn)

        // And the row, once written, is that same conversation.
        connectUseCase.markConnected(address)
        testScheduler.runCurrent()
        vm.sendMessage("first")
        testScheduler.runCurrent()

        assertEquals(
            "the row must be the conversation the connection was already on",
            connectedOn,
            vm.activeSessionId.value
        )
    }

    @Test
    fun `resuming a stored conversation connects on that conversation's own session`() = runTest(testDispatcher) {
        val address = validHexAddress()
        val session = seedPreviousConversation(address)

        vm.connectToAgent(address)
        testScheduler.runCurrent()

        assertEquals(session.id, connectUseCase.lastConnectConversationId)
    }

    @Test
    fun `two conversations started back-to-back each get their own server session`() = runTest(testDispatcher) {
        // The old positional scheme minted both offsets from the same stream
        // length when no traffic separated them, so both claimed the same
        // replies. Now each conversation carries its own session id and the
        // second one starts from none at all.
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)
        testScheduler.runCurrent()
        vm.sendMessage("first")
        testScheduler.runCurrent()
        val first = vm.activeSessionId.value!!

        vm.startNewSession()
        testScheduler.runCurrent()
        vm.sendMessage("second")
        testScheduler.runCurrent()
        val second = vm.activeSessionId.value!!

        assertNotEquals("the two conversations must be distinct", first, second)
        // One switch per conversation, and the id switched to is the id each
        // row was then created under — i.e. each was already talking on its
        // own session before it had anywhere to be stored.
        assertEquals(
            "each conversation must get its own session, exactly once",
            listOf(first, second),
            connectUseCase.switchedConversations
        )
        assertEquals(
            "the connection must end up on the conversation on screen",
            second,
            connectUseCase.connectedConversationId
        )
    }

    @Test
    fun `switching conversations moves the connection onto that conversation's session`() = runTest(testDispatcher) {
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)
        testScheduler.runCurrent()
        vm.sendMessage("in the first chat")
        testScheduler.runCurrent()
        val first = vm.activeSessionId.value!!
        vm.startNewSession()
        testScheduler.runCurrent()
        connectUseCase.switchedConversations.clear()

        vm.switchToSession(first)
        testScheduler.runCurrent()

        assertEquals(listOf(first), connectUseCase.switchedConversations)
        assertTrue(
            "the transcript must be on screen from Room, not wait for the re-CONNECT",
            vm.uiState.value.chatItems.any { it.id == "in the first chat" || it is ChatItem.User }
        )
    }

    @Test
    fun `deleting the open conversation moves the connection off its session`() = runTest(testDispatcher) {
        // Deleting a conversation used to silently extend the previous one's
        // slice over the deleted one's replies. There is no slice now, but the
        // connection must still stop talking on a session nothing owns.
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)
        testScheduler.runCurrent()
        vm.sendMessage("doomed")
        testScheduler.runCurrent()
        val doomed = vm.activeSessionId.value!!
        connectUseCase.switchedConversations.clear()

        vm.deleteSession(doomed)
        testScheduler.runCurrent()

        assertEquals(1, connectUseCase.switchedConversations.size)
        val replacement = connectUseCase.switchedConversations.single()
        assertNotNull("deleting the open chat must leave a replacement to talk on", replacement)
        assertNotEquals(
            "the connection must not stay on the deleted conversation's session",
            doomed,
            replacement
        )
    }

    @Test
    fun `deleting a middle conversation leaves the others' sessions untouched`() = runTest(testDispatcher) {
        val address = validHexAddress()
        agentRepo.seed(
            AgentProfile(
                id = "agent-1", address = address, name = "Test", serverUrl = "https://x.com",
                createdAt = 0L, isActive = true
            )
        )
        val older = sessionRepo.seed("agent-1", "Older", messageCount = 1)
        val middle = sessionRepo.seed("agent-1", "Middle", messageCount = 1)
        val newer = sessionRepo.seed("agent-1", "Newer", messageCount = 1)
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.switchedConversations.clear()

        vm.deleteSession(middle.id)
        testScheduler.runCurrent()

        // The survivors keep their own ids, and nothing re-points them at the
        // deleted conversation's session.
        val remaining = vm.sessions.value.map { it.id }
        assertTrue("the older conversation must survive", remaining.contains(older.id))
        assertTrue("the newer conversation must survive", remaining.contains(newer.id))
        assertTrue(
            "deleting a conversation the user was not in must not move the connection",
            connectUseCase.switchedConversations.isEmpty()
        )
    }

    @Test
    fun `a transcript that arrives before the conversation resolves is applied once it does`() = runTest(testDispatcher) {
        // The sticky transcript is replayed to whoever subscribes, so a
        // ViewModel joining a live connection can receive it before it knows
        // which conversation it is on. A StateFlow will not emit the same
        // value twice, so a merge that no-ops here is the last chance that
        // replay ever gets — on master this is where cold-start history went.
        val address = validHexAddress()
        val session = seedPreviousConversation(address)
        assertNull("test setup: nothing resolved yet", vm.activeSessionId.value)

        connectUseCase.emit(
            ChatEvent.ServerTranscriptReceived(
                entries = listOf(
                    ServerTranscriptEntry("answered while you were away")
                ),
                turn = 3
            )
        )
        testScheduler.runCurrent()

        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)
        testScheduler.runCurrent()

        assertEquals(session.id, vm.activeSessionId.value)
        assertTrue(
            "the replay was dropped instead of held: " + vm.uiState.value.chatItems.map { it.id },
            vm.uiState.value.chatItems.any {
                it.id == stableAssistantId("answered while you were away")
            }
        )
    }

    @Test
    fun `a reply that finished while away appears when the conversation is reopened`() = runTest(testDispatcher) {
        // Device repro: ask in A, start B before A's answer lands, send in B,
        // come back to A. A's turn finished server-side, so the CONNECT
        // transcript is the only way its answer can reach the chat list.
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)
        testScheduler.runCurrent()
        vm.sendMessage("Hi")
        testScheduler.runCurrent()
        val a = vm.activeSessionId.value!!

        vm.startNewSession()
        testScheduler.runCurrent()
        vm.sendMessage("Buhao")
        testScheduler.runCurrent()
        val b = vm.activeSessionId.value!!
        assertNotEquals(a, b)

        vm.switchToSession(a)
        testScheduler.runCurrent()

        connectUseCase.emit(
            ChatEvent.ServerTranscriptReceived(
                entries = listOf(
                    ServerTranscriptEntry("Hello! What's your name?")
                ),
                turn = 1
            )
        )
        testScheduler.runCurrent()

        assertEquals("test setup: A must be the conversation on screen", a, vm.activeSessionId.value)
        assertTrue(
            "the reply is in the database but never reached the chat list: " +
                vm.uiState.value.chatItems.map { it.id },
            vm.uiState.value.chatItems.any {
                it.id == stableAssistantId("Hello! What's your name?")
            }
        )
    }

    // ── ServerTranscriptReceived ───────────────────────────────────────

    @Test
    fun `a server transcript the local copy already holds changes nothing`() = runTest(testDispatcher) {
        val address = validHexAddress()
        seedPreviousConversation(address)
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)
        testScheduler.runCurrent()

        connectUseCase.emit(
            ChatEvent.ServerTranscriptReceived(entries = listOf(ServerTranscriptEntry("hello")), turn = 2)
        )
        testScheduler.runCurrent()

        assertEquals(2, vm.uiState.value.chatItems.size)
    }

    @Test
    fun `a newer server transcript is fetched into the resumed conversation`() = runTest(testDispatcher) {
        val address = validHexAddress()
        val session = seedPreviousConversation(address)
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)
        testScheduler.runCurrent()

        connectUseCase.emit(
            ChatEvent.ServerTranscriptReceived(
                entries = listOf(
                    ServerTranscriptEntry("hello"),
                    // Claimed by the conversation's own persisted question
                    // ("hi", row m1) — the asker confirmation an in-slice,
                    // locally-missing entry now needs to merge.
                    ServerTranscriptEntry("answered while you were away")
                ),
                turn = 3
            )
        )
        testScheduler.runCurrent()

        val contents = vm.uiState.value.chatItems.map {
            when (it) {
                is ChatItem.User -> it.content
                is ChatItem.Agent -> it.content
                is ChatItem.Turn -> it.agent?.content.orEmpty()
                else -> ""
            }
        }
        assertEquals(listOf("hi", "hello", "answered while you were away"), contents)
        assertTrue(
            "the fetched entry must be persisted into the resumed session",
            messageRepo.snapshot().any {
                it.sessionId == session.id && it.content == "answered while you were away"
            }
        )
    }

    // --- Control Center actions (W16AAPPLE-29) ---

    /** Reads the snapshot as the Control Center screen does; it only updates while read. */
    private fun TestScope.watchControlCenter() {
        backgroundScope.launch { vm.controlCenterSnapshot.collect {} }
        testScheduler.runCurrent()
    }

    private fun TestScope.connectedForControlCenter(): String {
        watchControlCenter()
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)
        testScheduler.runCurrent()
        return address
    }

    private fun TestScope.controlCenterSend(
        message: String,
        conversation: ControlCenterConversation = ControlCenterConversation.CURRENT
    ) = backgroundScope.async { runCatching { vm.controlCenterActions.sendMessage(message, conversation) } }

    private fun Result<String>.refusal(): String? = (exceptionOrNull() as? ControlCenterActionRejected)?.message

    @Test
    fun `a Control Center message is an ordinary visible turn, answered once that turn ends`() = runTest(testDispatcher) {
        connectedForControlCenter()

        val action = controlCenterSend("summarise today")
        testScheduler.runCurrent()

        assertEquals("summarise today", (vm.uiState.value.chatItems.single() as ChatItem.User).content)
        assertEquals(listOf("summarise today"), connectUseCase.sentMessages.map { it.content })
        assertFalse("answered before its turn ended", action.isCompleted)

        connectUseCase.emit(ChatEvent.OutputReceived("Here is today.", null))
        testScheduler.runCurrent()

        val session = messageRepo.snapshot().single { it.content == "summarise today" }.sessionId
        assertEquals(session, action.await().getOrThrow())
    }

    @Test
    fun `a Control Center skill runs as the slash command the user could have typed`() = runTest(testDispatcher) {
        connectedForControlCenter()

        backgroundScope.async { vm.controlCenterActions.runSkill("meeting-prep", " 2pm\n  sync ", ControlCenterConversation.CURRENT) }
        testScheduler.runCurrent()

        assertEquals(listOf("/meeting-prep 2pm sync"), connectUseCase.sentMessages.map { it.content })
        assertEquals("/daily-brief", controlCenterSkillMessage("daily-brief", null))
        assertEquals("/daily-brief", controlCenterSkillMessage("daily-brief", "  "))
    }

    @Test
    fun `nothing is sent for the Control Center while the agent is busy`() = runTest(testDispatcher) {
        connectedForControlCenter()
        connectUseCase.emit(ChatEvent.ChatItemReceived(ChatItem.Thinking(id = "t1", status = ThinkingStatus.RUNNING)))
        testScheduler.runCurrent()

        val action = controlCenterSend("hi")
        testScheduler.runCurrent()

        assertEquals("The agent is busy. Try again when its current turn has finished", action.await().refusal())
        assertTrue(connectUseCase.sentMessages.isEmpty())
    }

    @Test
    fun `nothing is sent for the Control Center before the connection is ready`() = runTest(testDispatcher) {
        val action = controlCenterSend("hi")
        testScheduler.runCurrent()

        assertEquals("Agent connection is not ready", action.await().refusal())
        assertTrue(connectUseCase.sentMessages.isEmpty())
    }

    @Test
    fun `a Control Center action for a new chat is refused for now`() = runTest(testDispatcher) {
        connectedForControlCenter()

        val action = controlCenterSend("hi", ControlCenterConversation.NEW)
        testScheduler.runCurrent()

        assertEquals("Starting a new chat from the Control Center is not supported yet", action.await().refusal())
        assertTrue(connectUseCase.sentMessages.isEmpty())
    }

    @Test
    fun `a Control Center message that fails to send is reported as not delivered`() = runTest(testDispatcher) {
        connectedForControlCenter()
        connectUseCase.failNextSend = true

        val action = controlCenterSend("hi")
        testScheduler.runCurrent()

        assertEquals("The message did not reach the agent", action.await().refusal())
    }

    @Test
    fun `a connection lost mid-turn is not reported to the Control Center as success`() = runTest(testDispatcher) {
        connectedForControlCenter()
        val action = controlCenterSend("hi")
        testScheduler.runCurrent()

        connectUseCase.forceState(ConnectionState.Reconnecting)
        testScheduler.runCurrent()

        assertEquals(
            "The connection dropped before the turn finished. Check the chat before retrying",
            action.await().refusal()
        )
    }

    @Test
    fun `an agent error ends the Control Center action with a refusal`() = runTest(testDispatcher) {
        connectedForControlCenter()
        val action = controlCenterSend("hi")
        testScheduler.runCurrent()

        connectUseCase.emit(ChatEvent.ConnectionErrorOccurred("Insufficient ConnectOnion Credits"))
        testScheduler.runCurrent()

        assertEquals("The agent stopped with an error. See the chat", action.await().refusal())
    }

    @Test
    fun `the app cancelling a Control Center action interrupts the turn it started`() = runTest(testDispatcher) {
        // As in the React SDK: an app owns the idle turn it starts.
        connectedForControlCenter()
        val action = controlCenterSend("hi")
        testScheduler.runCurrent()

        action.cancel(ControlCenterCancelled())
        testScheduler.runCurrent()

        assertEquals(listOf("hi"), connectUseCase.sentMessages.map { it.content })
        assertEquals(1, connectUseCase.interruptInvocations)
    }

    @Test
    fun `the app cancelling while its message is still being sent interrupts the turn once it starts`() = runTest(testDispatcher) {
        // The app's own timeout cancels too, and can land before the slow send returns.
        connectedForControlCenter()
        val gate = CompletableDeferred<Unit>()
        connectUseCase.sendGate = gate
        val action = controlCenterSend("hi")
        testScheduler.runCurrent()

        action.cancel(ControlCenterCancelled())
        testScheduler.runCurrent()
        assertEquals("interrupted before anything was sent", 0, connectUseCase.interruptInvocations)

        gate.complete(Unit)
        testScheduler.runCurrent()

        assertEquals(listOf("hi"), connectUseCase.sentMessages.map { it.content })
        assertEquals(1, connectUseCase.interruptInvocations)
    }

    @Test
    fun `a cancelled action's message still being sent keeps the agent busy until it resolves`() = runTest(testDispatcher) {
        // Otherwise a second action could be answered by the first turn's closing reply.
        connectedForControlCenter()
        val gate = CompletableDeferred<Unit>()
        connectUseCase.sendGate = gate
        val first = controlCenterSend("first")
        testScheduler.runCurrent()
        first.cancel(ControlCenterCancelled())
        testScheduler.runCurrent()

        val second = controlCenterSend("second")
        testScheduler.runCurrent()
        assertEquals("The agent is busy. Try again when its current turn has finished", second.await().refusal())

        connectUseCase.failNextSend = true
        gate.complete(Unit)
        testScheduler.runCurrent()
        connectUseCase.sendGate = null

        controlCenterSend("third")
        testScheduler.runCurrent()
        assertEquals(listOf("third"), connectUseCase.sentMessages.map { it.content })
    }

    @Test
    fun `the Control Center snapshot names the conversation on screen after a switch`() = runTest(testDispatcher) {
        connectedForControlCenter()
        vm.sendMessage("in A")
        testScheduler.runCurrent()
        connectUseCase.emit(ChatEvent.OutputReceived("answer A", null))
        testScheduler.runCurrent()
        val a = vm.activeSessionId.value!!
        vm.startNewSession()
        testScheduler.runCurrent()
        vm.sendMessage("in B")
        testScheduler.runCurrent()
        connectUseCase.emit(ChatEvent.OutputReceived("answer B", null))
        testScheduler.runCurrent()
        assertNotEquals(a, vm.controlCenterSnapshot.value.sessionId)

        vm.switchToSession(a)
        testScheduler.runCurrent()

        assertEquals(a, vm.controlCenterSnapshot.value.sessionId)
    }

    @Test
    fun `leaving the Control Center screen does not stop the turn`() = runTest(testDispatcher) {
        // The user going back to the chat to watch the turn closes the page; that must not end it.
        connectedForControlCenter()
        val action = controlCenterSend("hi")
        testScheduler.runCurrent()
        assertEquals(listOf("hi"), connectUseCase.sentMessages.map { it.content })
        assertEquals(SessionActivity.RUNNING, vm.sessionActivity.value)

        action.cancel()
        testScheduler.runCurrent()

        assertEquals(0, connectUseCase.interruptInvocations)
        assertEquals(SessionActivity.RUNNING, vm.sessionActivity.value)
    }

    @Test
    fun `leaving the Control Center screen while its message is being sent does not stop the turn`() = runTest(testDispatcher) {
        connectedForControlCenter()
        val gate = CompletableDeferred<Unit>()
        connectUseCase.sendGate = gate
        val action = controlCenterSend("hi")
        testScheduler.runCurrent()

        action.cancel()
        gate.complete(Unit)
        testScheduler.runCurrent()

        assertEquals(listOf("hi"), connectUseCase.sentMessages.map { it.content })
        assertEquals(0, connectUseCase.interruptInvocations)
    }

    @Test
    fun `only one Control Center action runs at a time`() = runTest(testDispatcher) {
        // Held before its turn starts, so the agent still reads as idle.
        connectedForControlCenter()
        connectUseCase.sendGate = kotlinx.coroutines.CompletableDeferred()
        controlCenterSend("first")
        testScheduler.runCurrent()

        val second = controlCenterSend("second")
        testScheduler.runCurrent()

        assertEquals(SessionActivity.IDLE, vm.sessionActivity.value)
        assertEquals("The agent is busy. Try again when its current turn has finished", second.await().refusal())
    }

    @Test
    fun `Stop ends a waiting Control Center action, and the next waits for the stopped turn to close`() = runTest(testDispatcher) {
        connectedForControlCenter()
        val first = controlCenterSend("first")
        testScheduler.runCurrent()

        vm.interrupt()
        testScheduler.runCurrent()
        val tooSoon = controlCenterSend("second")
        testScheduler.runCurrent()

        assertEquals("The turn was stopped in the chat", first.await().refusal())
        // The stopped turn's closing OUTPUT has not arrived; it would answer this one.
        assertEquals("The agent is busy. Try again when its current turn has finished", tooSoon.await().refusal())

        connectUseCase.emit(ChatEvent.OutputReceived("Stopped.", null))
        testScheduler.runCurrent()
        controlCenterSend("third")
        testScheduler.runCurrent()

        assertEquals(listOf("first", "third"), connectUseCase.sentMessages.map { it.content })
    }

    @Test
    fun `a Control Center action is not answered by a turn it did not start`() = runTest(testDispatcher) {
        // A message typed in the chat is still on its way out when the app's request arrives,
        // so the agent still reads as idle; the first OUTPUT is the typed message's.
        connectedForControlCenter()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        connectUseCase.sendGate = gate
        vm.sendMessage("typed")
        testScheduler.runCurrent()
        val action = controlCenterSend("from app")
        testScheduler.runCurrent()
        gate.complete(Unit)
        testScheduler.runCurrent()

        connectUseCase.emit(ChatEvent.OutputReceived("answer to typed", null))
        testScheduler.runCurrent()

        assertFalse("answered by the typed message's turn", action.isCompleted)
        assertEquals(listOf("typed", "from app"), connectUseCase.sentMessages.map { it.content })

        connectUseCase.emit(ChatEvent.OutputReceived("answer to the app", null))
        testScheduler.runCurrent()

        assertTrue(action.await().isSuccess)
    }

    @Test
    fun `a reply that lands after a conversation switch still answers the Control Center action`() = runTest(testDispatcher) {
        connectedForControlCenter()
        val action = controlCenterSend("asked in A")
        testScheduler.runCurrent()
        val sessionA = vm.activeSessionId.value!!

        vm.startNewSession()
        testScheduler.runCurrent()
        connectUseCase.emit(ChatEvent.OutputReceived("answer for A", null))
        testScheduler.runCurrent()

        assertEquals("the session the turn ran in", sessionA, action.await().getOrThrow())
    }

    @Test
    fun `a Control Center action needs an agent to send to`() = runTest(testDispatcher) {
        connectUseCase.markConnected()
        testScheduler.runCurrent()

        val action = controlCenterSend("hi")
        testScheduler.runCurrent()

        assertEquals("Agent connection is not ready", action.await().refusal())
        assertTrue(connectUseCase.sentMessages.isEmpty())
    }

    @Test
    fun `the Control Center snapshot is busy from the moment an action is accepted`() = runTest(testDispatcher) {
        connectedForControlCenter()
        connectUseCase.sendGate = kotlinx.coroutines.CompletableDeferred()

        controlCenterSend("hi")
        testScheduler.runCurrent()

        assertEquals(SessionActivity.IDLE, vm.sessionActivity.value)
        assertEquals(ControlCenterAgentStatus.WORKING, vm.controlCenterSnapshot.value.status)
    }

    @Test
    fun `the Control Center snapshot follows the chat`() = runTest(testDispatcher) {
        val address = connectedForControlCenter()
        connectUseCase._agentProfile.value = ai.openonion.oochat.domain.model.AgentLiveProfile(
            sessionId = null, name = "agent", address = address, model = "m",
            skills = listOf(ai.openonion.oochat.domain.model.AgentSkill("daily-brief", "Today's brief"))
        )
        testScheduler.runCurrent()

        val idle = vm.controlCenterSnapshot.value
        assertEquals(address, idle.agentAddress)
        assertEquals(ControlCenterConnection.CONNECTED, idle.connectionState)
        assertEquals(ControlCenterAgentStatus.IDLE, idle.status)
        assertEquals(listOf(ControlCenterSkill("daily-brief", "Today's brief")), idle.skills)

        controlCenterSend("hi")
        testScheduler.runCurrent()
        assertEquals(ControlCenterAgentStatus.WORKING, vm.controlCenterSnapshot.value.status)

        connectUseCase.emit(ChatEvent.OutputReceived("done", null))
        testScheduler.runCurrent()
        val session = messageRepo.snapshot().single { it.content == "hi" }.sessionId
        assertEquals(ControlCenterAgentStatus.IDLE, vm.controlCenterSnapshot.value.status)
        assertEquals(session, vm.controlCenterSnapshot.value.sessionId)
    }

    // --- Control Center chat sync (W16AAPPLE-30) ---

    /**
     * The snapshot's chat items as (type, content-or-status) pairs, enough to tell them apart,
     * once a change made since the last rebuild has had time to come through.
     */
    private fun TestScope.controlCenterChat(): List<Pair<String?, String?>> {
        testScheduler.advanceTimeBy(ChatViewModel.CONTROL_CENTER_CHAT_INTERVAL_MS)
        testScheduler.runCurrent()
        return vm.controlCenterSnapshot.value.chatItems.map { it.stringField("type") to (it.stringField("content") ?: it.stringField("status")) }
    }

    @Test
    fun `the Control Center chat is only built while the Control Center is open`() = runTest(testDispatcher) {
        val address = validHexAddress()
        vm.connectToAgent(address)
        testScheduler.runCurrent()
        connectUseCase.markConnected(address)
        vm.sendMessage("hi")
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(ChatViewModel.CONTROL_CENTER_CHAT_INTERVAL_MS)
        testScheduler.runCurrent()
        assertEquals("nothing reads it, so nothing is built", emptyList<JsonObject>(), vm.controlCenterSnapshot.value.chatItems)

        watchControlCenter()

        assertEquals(listOf("user" to "hi"), controlCenterChat())
    }

    @Test
    fun `a streaming reply rebuilds the Control Center chat at most once per interval, ending on the latest`() = runTest(testDispatcher) {
        connectedForControlCenter()
        val seen = mutableListOf<List<JsonObject>>()
        backgroundScope.launch { vm.controlCenterSnapshot.collect { seen += it.chatItems } }
        testScheduler.runCurrent()
        seen.clear()

        vm.sendMessage("hi")
        testScheduler.runCurrent()
        assertEquals("the first change goes at once", 1, seen.size)
        testScheduler.advanceTimeBy(ChatViewModel.CONTROL_CENTER_CHAT_INTERVAL_MS)
        connectUseCase.emit(ChatEvent.ChatItemReceived(ChatItem.ToolCall(id = "t1", name = "search")))
        testScheduler.runCurrent()
        seen.clear()
        repeat(20) { n ->
            connectUseCase.emit(ChatEvent.ChatItemUpdated(ChatItem.ToolCall(id = "t1", name = "search", result = "part $n")))
            testScheduler.advanceTimeBy(10)
            testScheduler.runCurrent()
        }
        testScheduler.advanceTimeBy(ChatViewModel.CONTROL_CENTER_CHAT_INTERVAL_MS)
        testScheduler.runCurrent()

        assertTrue("20 changes in 200 ms gave ${seen.size} rebuilds", seen.size in 1..2)
        assertEquals("part 19", seen.last().last().stringField("result"))
    }

    @Test
    fun `each change in the chat reaches the Control Center snapshot`() = runTest(testDispatcher) {
        connectedForControlCenter()
        assertEquals(emptyList<Pair<String?, String?>>(), controlCenterChat())

        vm.sendMessage("hi")
        testScheduler.runCurrent()
        assertEquals(listOf("user" to "hi"), controlCenterChat())

        connectUseCase.emit(ChatEvent.ChatItemReceived(ChatItem.ToolCall(id = "t1", name = "search")))
        testScheduler.runCurrent()
        assertEquals(listOf("user" to "hi", "tool_call" to "running"), controlCenterChat())

        connectUseCase.emit(ChatEvent.ChatItemUpdated(ChatItem.ToolCall(id = "t1", name = "search", status = ToolStatus.DONE, result = "ok")))
        testScheduler.runCurrent()
        assertEquals(listOf("user" to "hi", "tool_call" to "done"), controlCenterChat())

        connectUseCase.emit(ChatEvent.OutputReceived("hello", null))
        testScheduler.runCurrent()
        assertEquals("agent" to "hello", controlCenterChat().last())
        assertFalse(vm.controlCenterSnapshot.value.truncated)
    }

    @Test
    fun `after a conversation switch the Control Center snapshot holds that conversation's chat`() = runTest(testDispatcher) {
        connectedForControlCenter()
        vm.sendMessage("in A")
        testScheduler.runCurrent()
        connectUseCase.emit(ChatEvent.OutputReceived("answer A", null))
        testScheduler.runCurrent()
        val a = vm.activeSessionId.value!!
        vm.startNewSession()
        testScheduler.runCurrent()
        assertEquals("a new conversation starts empty", emptyList<Pair<String?, String?>>(), controlCenterChat())

        vm.sendMessage("in B")
        testScheduler.runCurrent()
        assertEquals(listOf("user" to "in B"), controlCenterChat())

        vm.switchToSession(a)
        testScheduler.runCurrent()

        assertEquals(a, vm.controlCenterSnapshot.value.sessionId)
        assertEquals(listOf("user" to "in A", "agent" to "answer A"), controlCenterChat().filter { it.first != "thinking" })
    }

    @Test
    fun `through a reconnect the Control Center snapshot keeps the chat and then takes what was replayed`() = runTest(testDispatcher) {
        val address = connectedForControlCenter()
        vm.sendMessage("first")
        testScheduler.runCurrent()
        connectUseCase.emit(ChatEvent.ChatItemReceived(ChatItem.ToolCall(id = "t1", name = "search")))
        testScheduler.runCurrent()

        connectUseCase.forceState(ConnectionState.Reconnecting)
        testScheduler.runCurrent()
        assertEquals(ControlCenterConnection.RECONNECTING, vm.controlCenterSnapshot.value.connectionState)
        assertEquals(listOf("user" to "first", "tool_call" to "running"), controlCenterChat())

        connectUseCase.markConnected(address)
        testScheduler.runCurrent()
        // The host replays what was missed.
        connectUseCase.emit(ChatEvent.ChatItemUpdated(ChatItem.ToolCall(id = "t1", name = "search", status = ToolStatus.DONE)))
        connectUseCase.emit(ChatEvent.OutputReceived("after", null))
        testScheduler.runCurrent()

        assertEquals(ControlCenterConnection.CONNECTED, vm.controlCenterSnapshot.value.connectionState)
        assertEquals(
            listOf("user" to "first", "tool_call" to "done", "agent" to "after"),
            controlCenterChat().filter { it.first != "thinking" }
        )
    }

    // ── Helpers ────────────────────────────────────────────────────────

    /**
     * 40 hex chars after the `0x` prefix. AgentDiscoveryService treats
     * this as a valid address and returns synchronously without any
     * HTTP call, so tests don't need to stub network IO.
     */
    private fun validHexAddress(nibble: Int = 0xA): String =
        "0x" + nibble.toString(16).repeat(40)

    private fun testSchedulerKeepRunning() {
        // No-op marker: scheduler advancement happens at the call site via runCurrent(); named only for readability.
    }
}

/**
 * Exposes the protected onCleared() for direct testing without a real
 * ViewModelStore teardown. Resolved on the base class, not on ChatViewModel:
 * the override went away once the connection became app-scoped, and virtual
 * dispatch still reaches any future override.
 */
private fun ChatViewModel.onClearedForTest() {
    val method = androidx.lifecycle.ViewModel::class.java.getDeclaredMethod("onCleared")
    method.isAccessible = true
    method.invoke(this)
}

/**
 * Mirrors the real [ai.openonion.oochat.domain.usecase.PersistenceTransaction.persistMessageAtomically]
 * routing logic (item shape -> role/content/images, default-title rename
 * trigger) but against the fake repositories this test file already asserts
 * against, since the real implementation writes through raw Room DAOs that
 * bypass [MessageRepository]/[SessionRepository] entirely.
 */
class FakePersistenceTransaction(
    private val messageRepo: MessageRepository,
    private val sessionRepo: SessionRepository
) : ai.openonion.oochat.domain.usecase.PersistenceTransaction(FakeDatabaseForChatTest()) {
    override suspend fun persistMessageAtomically(
        sessionId: String,
        item: ChatItem,
        onTitleUpdate: suspend (String, String) -> Unit,
        onPreviewUpdate: suspend (String, Int, String?) -> Unit
    ) {
        val (role, content, images) = when (item) {
            is ChatItem.User -> Triple(Role.USER, item.content, item.images)
            is ChatItem.Agent -> Triple(Role.ASSISTANT, item.content, item.images)
            is ChatItem.Turn -> {
                val agent = item.agent ?: return
                Triple(Role.ASSISTANT, agent.content, agent.images)
            }
            else -> return
        }
        if (content.isBlank() && images.isNullOrEmpty()) return

        val now = 0L
        // As the real one: an item already failed is saved failed; otherwise a
        // re-save keeps what was recorded.
        val failedToSend = (item as? ChatItem.User)?.state == UserMessageState.FAILED ||
            messageRepo.getMessagesListBySession(sessionId).any { it.id == item.id && it.failedToSend }
        messageRepo.createMessage(
            ChatMessage(
                id = item.id,
                sessionId = sessionId,
                role = role,
                content = content,
                timestamp = now,
                images = images,
                failedToSend = failedToSend
            )
        )

        val session = sessionRepo.getSessionById(sessionId)
        if (session != null && role == Role.USER && session.title == "New conversation") {
            val newTitle = content.trim().take(40).ifBlank { "New conversation" }
            sessionRepo.renameSession(sessionId, newTitle)
            onTitleUpdate(sessionId, newTitle)
        }

        val count = messageRepo.getMessageCount(sessionId)
        val preview = content.take(80)
        sessionRepo.updateMessageInfo(sessionId, count, preview)
        onPreviewUpdate(sessionId, count, preview)
    }
}

class FakeDatabaseForChatTest : ai.openonion.oochat.data.local.db.AppDatabase() {
    override fun agentDao() = throw NotImplementedError()
    // persistMessageAtomically is overridden to a no-op in FakePersistenceTransaction,
    // but PersistenceTransaction's own constructor eagerly calls messageDao()/sessionDao()
    // to populate its private vals, so these must return an object rather than throw.
    override fun sessionDao() = ai.openonion.oochat.domain.usecase.NoOpSessionDao
    override fun messageDao() = ai.openonion.oochat.domain.usecase.NoOpMessageDao
    override fun sessionStateDao() = throw NotImplementedError()
    override fun sessionSyncDao() = throw NotImplementedError()
    override fun pendingMessageDao() = throw NotImplementedError()
    override fun clearAllTables() = throw NotImplementedError()
    // RoomDatabase's own constructor eagerly assigns `invalidationTracker = createInvalidationTracker()`
    // (see androidx.room.RoomDatabase), so this can't throw — unlike createOpenHelper, which is only
    // invoked from init(configuration), never reached when a fake is built via a bare constructor call.
    override fun createInvalidationTracker(): androidx.room.InvalidationTracker = androidx.room.InvalidationTracker(this)
    override fun createOpenHelper(config: androidx.room.DatabaseConfiguration): androidx.sqlite.db.SupportSQLiteOpenHelper = throw NotImplementedError()
}
