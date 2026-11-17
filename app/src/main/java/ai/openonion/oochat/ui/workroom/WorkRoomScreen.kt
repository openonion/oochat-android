package ai.openonion.oochat.ui.workroom

import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.domain.model.ProviderActivity
import ai.openonion.oochat.domain.model.ProviderActivityStatus
import ai.openonion.oochat.domain.model.ProviderInvocationStatus
import ai.openonion.oochat.ui.chat.ChatViewModel
import ai.openonion.oochat.ui.chat.components.WorkRoomStatus
import ai.openonion.oochat.ui.components.BackTopAppBar
import ai.openonion.oochat.ui.theme.spacing
import ai.openonion.oochat.ui.theme.statusColors
import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Binds [WorkRoomScreen] to the chat it belongs to. [chatViewModelStoreOwner]
 * is the chat destination's back-stack entry, as for the Control Center, so
 * the room follows the same live list the chat is folding frames into.
 */
@Composable
fun WorkRoomScreenWrapper(
    invocationId: String,
    chatViewModelStoreOwner: ViewModelStoreOwner?,
    onNavigateBack: () -> Unit
) {
    val application = LocalContext.current.applicationContext as Application
    val viewModel: ChatViewModel = viewModel(
        viewModelStoreOwner = chatViewModelStoreOwner
            ?: checkNotNull(LocalViewModelStoreOwner.current) { "No ViewModelStoreOwner" },
        factory = ViewModelProvider.AndroidViewModelFactory.getInstance(application)
    )
    val state by viewModel.uiState.collectAsState()
    val drafts by viewModel.workRoomDrafts.collectAsState()
    val permissionControls by viewModel.workRoomPermissionControls.collectAsState()
    val stops by viewModel.workRoomStopStates.collectAsState()
    val run = state.chatItems.firstOrNull { it is ChatItem.ProviderInvocation && it.id == invocationId }
        as ChatItem.ProviderInvocation?
    WorkRoomScreen(
        run = run,
        onNavigateBack = onNavigateBack,
        draft = viewModel.workRoomDraft(invocationId, drafts),
        onDraftChange = { viewModel.editWorkRoomDraft(invocationId, it) },
        onSend = { viewModel.sendWorkRoomInput(invocationId) },
        scrollPosition = viewModel.workRoomScrollPosition(invocationId),
        onScrollPositionChanged = { viewModel.updateWorkRoomScrollPosition(invocationId, it) },
        approvals = state.chatItems.workRoomApprovals(invocationId),
        onApprovalDecision = { itemId, approved, scope ->
            viewModel.markGateResolved(itemId)
            viewModel.respondToApproval(itemId, approved, scope, if (approved) null else WORK_ROOM_DENY_MODE)
        },
        permissionUi = viewModel.workRoomPermissionControl(invocationId, permissionControls),
        onChoosePermission = { viewModel.chooseWorkRoomPermission(invocationId, it) },
        onConfirmPermission = { viewModel.confirmWorkRoomPermission(invocationId) },
        onCancelPermission = { viewModel.cancelWorkRoomPermission(invocationId) },
        stopUi = viewModel.workRoomStop(invocationId, stops),
        onStop = { viewModel.requestWorkRoomStop(invocationId) },
        onConfirmStop = { viewModel.confirmWorkRoomStop(invocationId) },
        onCancelStop = { viewModel.cancelWorkRoomStop(invocationId) }
    )
}

internal fun List<ChatItem>.workRoomApprovals(invocationId: String): List<ChatItem.ApprovalNeeded> =
    filterIsInstance<ChatItem.ApprovalNeeded>().filter { it.workRoom?.invocationId == invocationId }

/**
 * A Claude Code or Codex run's Work Room (W16AAPPLE-33), O Chat's Work Room on
 * a phone: the task and where it stands, the provider's own permission mode when
 * the Host offers one (W16AAPPLE-43), the latest picture of its workspace, what
 * was said to and by the provider (W16AAPPLE-34), then each step it took, as the Host
 * describes it, and a field to steer it (W16AAPPLE-35), and a Stop for this run only,
 * behind a 15-second confirmation (W16AAPPLE-37).
 * [run] is null once the chat no longer holds it.
 */
@Composable
fun WorkRoomScreen(
    run: ChatItem.ProviderInvocation?,
    onNavigateBack: () -> Unit,
    draft: WorkRoomDraft = WorkRoomDraft(),
    onDraftChange: (String) -> Unit = {},
    onSend: () -> Unit = {},
    scrollPosition: WorkRoomScrollPosition = WorkRoomScrollPosition(),
    onScrollPositionChanged: (WorkRoomScrollPosition) -> Unit = {},
    approvals: List<ChatItem.ApprovalNeeded> = emptyList(),
    onApprovalDecision: (itemId: String, approved: Boolean, scope: String) -> Unit = { _, _, _ -> },
    permissionUi: WorkRoomPermissionUi = WorkRoomPermissionUi(),
    onChoosePermission: (optionId: String) -> Unit = {},
    onConfirmPermission: () -> Unit = {},
    onCancelPermission: () -> Unit = {},
    stopUi: WorkRoomStopUi = WorkRoomStopUi(),
    onStop: () -> Unit = {},
    onConfirmStop: () -> Unit = {},
    onCancelStop: () -> Unit = {}
) {
    Scaffold(
        topBar = {
            BackTopAppBar(
                title = run?.let { "${WorkRoomText.providerName(it)} Work Room" } ?: "Work Room",
                onNavigateBack = onNavigateBack
            )
        },
        bottomBar = {
            if (run != null) WorkRoomComposer(run, draft, onDraftChange, onSend)
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            if (run == null) {
                Text(
                    text = "This Work Room is no longer in the chat.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(MaterialTheme.spacing.lg)
                )
                return@Box
            }
            WorkRoomTranscript(
                run,
                scrollPosition,
                onScrollPositionChanged,
                approvals,
                onApprovalDecision,
                stop = { WorkRoomStopControl(run, stopUi, onStop, onConfirmStop, onCancelStop) },
                permission = {
                    run.providerPermission?.let { permission ->
                        WorkRoomPermissionControl(
                            run = run,
                            permission = permission,
                            ui = permissionUi,
                            onChoose = onChoosePermission,
                            onConfirm = onConfirmPermission,
                            onCancel = onCancelPermission
                        )
                    }
                }
            )
        }
    }
}

/** Displays the room transcript and reports its scroll position. */
@Composable
private fun WorkRoomTranscript(
    run: ChatItem.ProviderInvocation,
    scrollPosition: WorkRoomScrollPosition,
    onScrollPositionChanged: (WorkRoomScrollPosition) -> Unit,
    approvals: List<ChatItem.ApprovalNeeded>,
    onApprovalDecision: (itemId: String, approved: Boolean, scope: String) -> Unit,
    stop: @Composable () -> Unit = {},
    permission: @Composable () -> Unit = {}
) {
    val pendingApprovals = approvals.filter { it.decision == null }
    val resolvedApprovals = approvals.filter { it.decision != null }
    // Only a picture of the run as it stands, as the Host's replay keeps: an older one would look current.
    val currentArtifact = run.artifact?.takeIf { it.stateRevision == run.stateRevision }
    val itemKeys = buildList {
        add("header")
        if (!run.status.isTerminal) add("stop")
        if (run.providerPermission != null) add("permission")
        currentArtifact?.let { add("artifact") }
        pendingApprovals.forEach { add("approval:${it.id}") }
        if (run.messages.isNotEmpty()) {
            add("conversation-heading")
            run.messages.forEach { add("message:${it.id}") }
        }
        add("steps-heading")
        run.activities.forEach { add("step:${it.id}") }
        resolvedApprovals.forEach { add("approval:${it.id}") }
        if (run.status.isTerminal) add("outcome")
    }
    val itemCount = itemKeys.size
    val keyIndex = scrollPosition.firstVisibleItemKey?.let(itemKeys::indexOf)?.takeIf { it >= 0 }
    val restoredIndex = (keyIndex ?: scrollPosition.firstVisibleItemIndex)
        .coerceIn(0, (itemCount - 1).coerceAtLeast(0))
    val restoredOffset = if (keyIndex != null || restoredIndex == scrollPosition.firstVisibleItemIndex) {
        scrollPosition.firstVisibleItemScrollOffset
    } else {
        0
    }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = restoredIndex,
        initialFirstVisibleItemScrollOffset = restoredOffset
    )

    LaunchedEffect(listState) {
        snapshotFlow {
            // The key of the item the index and offset describe, not just the first one
            // laid out: one peeking into the top padding would anchor the room an item off.
            val firstVisible = listState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.index == listState.firstVisibleItemIndex }
            WorkRoomScrollPosition(
                firstVisibleItemKey = firstVisible?.key as? String,
                firstVisibleItemIndex = listState.firstVisibleItemIndex,
                firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset
            )
        }
            .distinctUntilChanged()
            .collect(onScrollPositionChanged)
    }

    LazyColumn(
        state = listState,
        // Capped for reading on a tablet, as Rem's pages are.
        modifier = Modifier
            .widthIn(max = 720.dp)
            .fillMaxWidth()
            .testTag(WORK_ROOM_TRANSCRIPT_TAG),
        contentPadding = PaddingValues(MaterialTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md)
    ) {
        item(key = "header") { RunHeader(run) }
        // Stop for this run only (W16AAPPLE-37), while there is something to stop.
        if (!run.status.isTerminal) item(key = "stop") { stop() }
        if (run.providerPermission != null) item(key = "permission") { permission() }
        currentArtifact?.let { artifact -> item(key = "artifact") { LatestView(artifact) } }
        items(pendingApprovals, key = { "approval:${it.id}" }) { approval ->
            WorkRoomApprovalCard(approval, approval.workRoom ?: return@items) { approved, scope ->
                onApprovalDecision(approval.id, approved, scope)
            }
        }
        if (run.messages.isNotEmpty()) {
            item(key = "conversation-heading") {
                Text(
                    text = "Conversation",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.semantics { heading() }
                )
            }
            items(run.messages, key = { "message:${it.id}" }) { message ->
                ConversationMessage(message, WorkRoomText.providerName(run))
            }
        }
        item(key = "steps-heading") {
            Text(
                text = if (run.activities.isEmpty()) "No steps yet" else "Steps",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { heading() }
            )
        }
        items(run.activities, key = { "step:${it.id}" }) { step -> Step(step) }
        items(resolvedApprovals, key = { "approval:${it.id}" }) { approval ->
            WorkRoomApprovalCard(approval, approval.workRoom ?: return@items) { _, _ -> }
        }
        run.outcome()?.let { outcome ->
            item(key = "outcome") {
                HorizontalDivider()
                Text(
                    text = outcome,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (run.status == ProviderInvocationStatus.FAILED) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier.padding(top = MaterialTheme.spacing.sm)
                )
            }
        }
    }
}

internal const val WORK_ROOM_TRANSCRIPT_TAG = "work-room-transcript"

@Composable
private fun RunHeader(run: ChatItem.ProviderInvocation) {
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)) {
        Text(
            text = WorkRoomText.title(run),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() }
        )
        WorkRoomStatus(run.status)
        if (!run.status.isTerminal) {
            // The Host's own line on a working run; the steps below say what it is doing.
            run.currentSummary?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        WorkRoomText.elapsed(run.elapsedMs)?.let {
            // Only a finished run's time is final; a working one's moves on with each frame.
            Text(
                text = if (run.status.isTerminal) "Took $it" else "Running for $it",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun Step(step: ProviderActivity) {
    Row(verticalAlignment = Alignment.Top) {
        Box(modifier = Modifier.padding(top = MaterialTheme.spacing.xxs, end = MaterialTheme.spacing.sm)) {
            when (step.status) {
                ProviderActivityStatus.RUNNING -> CircularProgressIndicator(
                    modifier = Modifier
                        .size(16.dp)
                        .semantics { contentDescription = "Running" },
                    strokeWidth = 2.dp
                )
                ProviderActivityStatus.COMPLETED -> Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "Done",
                    tint = MaterialTheme.statusColors.success,
                    modifier = Modifier.size(16.dp)
                )
                ProviderActivityStatus.FAILED -> Icon(
                    Icons.Default.Error,
                    contentDescription = "Failed",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xxs)) {
            Text(step.title, style = MaterialTheme.typography.bodyMedium)
            step.summary.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (step.files.isNotEmpty()) {
                Text(
                    text = step.files.joinToString(", "),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** How the run ended, once it has; never the summary it had while working. */
private fun ChatItem.ProviderInvocation.outcome(): String? =
    if (status.isTerminal) WorkRoomText.summary(this) else null
