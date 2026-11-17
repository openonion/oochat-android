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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel

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
    val run = state.chatItems.firstOrNull { it is ChatItem.ProviderInvocation && it.id == invocationId }
        as ChatItem.ProviderInvocation?
    WorkRoomScreen(
        run = run,
        onNavigateBack = onNavigateBack,
        draft = drafts[invocationId] ?: WorkRoomDraft(),
        onDraftChange = { viewModel.editWorkRoomDraft(invocationId, it) },
        onSend = { viewModel.sendWorkRoomInput(invocationId) }
    )
}

/**
 * A Claude Code or Codex run's Work Room (W16AAPPLE-33), O Chat's Work Room on
 * a phone: the task and where it stands, then each step the provider took, as
 * the Host describes it, and a field to steer it (W16AAPPLE-35). Approving and
 * Stop come with W16AAPPLE-36 and -37. [run] is null once the chat no longer
 * holds it.
 */
@Composable
fun WorkRoomScreen(
    run: ChatItem.ProviderInvocation?,
    onNavigateBack: () -> Unit,
    draft: WorkRoomDraft = WorkRoomDraft(),
    onDraftChange: (String) -> Unit = {},
    onSend: () -> Unit = {}
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
            LazyColumn(
                // Capped for reading on a tablet, as Rem's pages are.
                modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
                contentPadding = PaddingValues(MaterialTheme.spacing.lg),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md)
            ) {
                item(key = "header") { RunHeader(run) }
                item(key = "steps-heading") {
                    Text(
                        text = if (run.activities.isEmpty()) "No steps yet" else "Steps",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.semantics { heading() }
                    )
                }
                items(run.activities, key = { "step:${it.id}" }) { step -> Step(step) }
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
    }
}

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
