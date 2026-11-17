package ai.openonion.oochat.ui.chat.components

import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.domain.model.IntentStatus
import ai.openonion.oochat.domain.model.ThinkingStatus
import ai.openonion.oochat.domain.model.ToolStatus
import ai.openonion.oochat.domain.model.TurnSteps
import ai.openonion.oochat.ui.theme.spacing
import ai.openonion.oochat.util.summarizeToolCall
import ai.openonion.oochat.util.truncateMiddle
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * A turn's thoughts and tool calls as one block (W16AAPPLE-112).
 *
 * While the turn runs, its newest steps are on screen under a live
 * "Working…" header, older ones folded behind a toggle. Once it finishes the
 * whole block collapses to one summary line — "Worked for 16s · 7 steps" —
 * above the answer, and a tap brings the steps back.
 *
 * [expanded] is the caller's: this row is rebuilt as the turn grows, and the
 * reader's choice has to outlive it. While running it opens the earlier
 * steps; once finished, the whole list.
 *
 * @param durationMs how long the finished turn took, when known.
 * @param startedAtMs when the turn started, for the running clock. Null counts
 *   from when this block first composed.
 */
@Composable
internal fun TurnStepsBlock(
    turn: TurnSteps,
    durationMs: Long?,
    startedAtMs: Long?,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (turn.running) {
            RunningSteps(turn, startedAtMs, showEarlier = expanded, onToggleEarlier = onToggle)
        } else {
            TurnSummaryRow(turn, durationMs, expanded, onToggle)
            AnimatedVisibility(visible = expanded) {
                StepList(turn.steps, modifier = Modifier.padding(top = MaterialTheme.spacing.xs))
            }
        }
    }
}

@Composable
private fun TurnSummaryRow(turn: TurnSteps, durationMs: Long?, expanded: Boolean, onToggle: () -> Unit) {
    val spoken = turnSummarySpoken(durationMs, turn.steps.size, turn.failedCount)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onToggle)
            .padding(vertical = MaterialTheme.spacing.xs)
            .testTag(TURN_SUMMARY_TAG)
            // One stop for TalkBack: what happened, whether it is open, and
            // what a double-tap does — rather than four fragments and a chevron.
            .clearAndSetSemantics {
                contentDescription = spoken
                role = Role.Button
                stateDescription = if (expanded) "Expanded" else "Collapsed"
                onClick(label = if (expanded) "Hide steps" else "Show steps") { onToggle(); true }
            }
    ) {
        Icon(
            imageVector = Icons.Outlined.AutoAwesome,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = turnSummaryHeadline(durationMs),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = " · ${stepCountLabel(turn.steps.size)}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (turn.failedCount > 0) {
            Text(
                text = "${turn.failedCount} failed",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = MaterialTheme.spacing.xs2, vertical = MaterialTheme.spacing.xxs)
            )
        }
        ExpandChevron(expanded, Modifier.size(18.dp))
    }
}

@Composable
private fun RunningSteps(
    turn: TurnSteps,
    startedAtMs: Long?,
    showEarlier: Boolean,
    onToggleEarlier: () -> Unit
) {
    WorkingHeader(startedAtMs, step = turn.steps.size)
    val earlier = (turn.steps.size - LIVE_STEP_COUNT).coerceAtLeast(0)
    val shown = if (showEarlier) turn.steps else turn.steps.takeLast(LIVE_STEP_COUNT)
    StepList(
        steps = shown,
        modifier = Modifier.padding(top = MaterialTheme.spacing.xs),
        header = if (earlier > 0) {
            { EarlierStepsToggle(earlier, showEarlier, onToggleEarlier) }
        } else null
    )
    Text(
        text = "Steps collapse into a summary when the turn finishes.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = MaterialTheme.spacing.xs, top = MaterialTheme.spacing.xs)
    )
}

/**
 * "Working… 12s · step 5": the agent is busy, how long for, and how far in.
 * Heads a running turn's steps, and stands alone for a turn that has not taken
 * a step yet, so waiting on the model looks the same as waiting on a tool.
 *
 * @param startedAtMs when the turn started. Null counts from when this first composed.
 * @param step how many steps the turn has taken, or null before the first.
 */
@Composable
internal fun WorkingHeader(startedAtMs: Long?, step: Int? = null, modifier: Modifier = Modifier) {
    val start = startedAtMs ?: remember { System.currentTimeMillis() }
    val now by produceState(System.currentTimeMillis(), start) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val elapsed = formatStepDuration((now - start).coerceAtLeast(0))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
        modifier = modifier
            .padding(vertical = MaterialTheme.spacing.xs)
            .testTag(WORKING_HEADER_TAG)
            // Read once as "Agent is working", politely, rather than re-announced
            // every second as the clock ticks.
            .clearAndSetSemantics {
                contentDescription = if (step != null) "Agent is working, step $step" else "Agent is working"
                liveRegion = LiveRegionMode.Polite
            }
    ) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        Text(
            text = "Working…",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = if (step != null) "$elapsed · step $step" else elapsed,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun EarlierStepsToggle(count: Int, showing: Boolean, onToggle: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                onClickLabel = if (showing) "Hide earlier steps" else "Show earlier steps",
                onClick = onToggle
            )
            .padding(horizontal = MaterialTheme.spacing.md, vertical = MaterialTheme.spacing.sm2)
    ) {
        ExpandChevron(showing, Modifier.size(18.dp))
        Text(
            text = if (showing) "Hide earlier steps" else "$count earlier ${if (count == 1) "step" else "steps"}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** The steps on a tinted panel, so they read as working notes set apart from the answer. */
@Composable
internal fun StepList(
    steps: List<ChatItem>,
    modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            // The faintest green wash, so the panel sits with the user's bubble.
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.035f))
            .padding(vertical = MaterialTheme.spacing.xs)
    ) {
        header?.invoke()
        steps.forEach { step -> key(step.id) { StepRow(step) } }
    }
}

/**
 * One step on a single line: an icon for the kind of thing it did, what it
 * did, and how long it took. Tapping it opens what it produced — the full
 * thought, or the command and its output.
 */
@Composable
private fun StepRow(step: ChatItem) {
    var open by rememberSaveable(step.id) { mutableStateOf(false) }
    val detail = stepDetail(step)
    val failed = step is ChatItem.ToolCall && step.status == ToolStatus.ERROR
    val running = when (step) {
        is ChatItem.ToolCall -> step.status == ToolStatus.RUNNING
        is ChatItem.Thinking -> step.status == ThinkingStatus.RUNNING
        is ChatItem.IntentItem -> step.status == IntentStatus.ANALYZING
        else -> false
    }
    val duration = stepDurationMs(step)?.let(::formatStepDuration)
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md),
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (detail != null) {
                        Modifier.clickable(onClickLabel = if (open) "Hide details" else "Show details") { open = !open }
                    } else {
                        Modifier
                    }
                )
                .padding(horizontal = MaterialTheme.spacing.md, vertical = MaterialTheme.spacing.sm2)
                .semantics(mergeDescendants = true) {
                    contentDescription = stepSpoken(step, failed, running)
                }
        ) {
            Box(modifier = Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                when {
                    running -> CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    failed -> Icon(
                        imageVector = Icons.Outlined.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                    else -> Icon(
                        imageVector = stepIcon(step),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Text(
                text = stepLabel(step),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = if (step.isCommand()) FontFamily.Monospace else null,
                fontStyle = if (step.isThought()) FontStyle.Italic else null,
                color = if (step.isThought()) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (failed) {
                Text(
                    text = "Failed",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (duration != null) {
                Text(
                    text = duration,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (detail != null) {
            AnimatedVisibility(visible = open) {
                val indent = MaterialTheme.spacing.md + 18.dp + MaterialTheme.spacing.md
                if (step.isThought()) {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = indent, end = MaterialTheme.spacing.md, bottom = MaterialTheme.spacing.sm)
                    )
                } else {
                    MonoText(
                        text = detail,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = indent, end = MaterialTheme.spacing.md, bottom = MaterialTheme.spacing.sm)
                    )
                }
            }
        }
    }
}

private fun ChatItem.baseTool(): String? = (this as? ChatItem.ToolCall)?.name?.substringBefore(':')?.lowercase()

private fun ChatItem.isCommand(): Boolean = baseTool() in SHELL_TOOLS

/** Something the agent thought rather than did: italic, under a sparkle. */
private fun ChatItem.isThought(): Boolean = this is ChatItem.Thinking || this is ChatItem.IntentItem

private fun stepIcon(step: ChatItem): ImageVector = when {
    step is ChatItem.ApprovalNeeded -> Icons.Outlined.VerifiedUser
    step.isThought() -> Icons.Outlined.AutoAwesome
    else -> when (step.baseTool()) {
        in SHELL_TOOLS -> Icons.Outlined.Terminal
        in SEARCH_TOOLS -> Icons.Outlined.Search
        "read", "read_file" -> Icons.Outlined.Description
        "write", "edit" -> Icons.Outlined.Edit
        // The design's plain square: any other tool, named in the row itself.
        else -> Icons.Outlined.CheckBoxOutlineBlank
    }
}

/** What a step did, in a line — the command itself for a shell call, a verb and its object otherwise. */
internal fun stepLabel(step: ChatItem): String {
    if (step is ChatItem.Thinking) return step.content.orEmpty().trim().lineSequence().firstOrNull().orEmpty()
    if (step is ChatItem.IntentItem) {
        return step.ack?.takeIf { it.isNotBlank() } ?: when (step.status) {
            IntentStatus.ANALYZING -> "Working out what you asked…"
            IntentStatus.UNDERSTOOD -> "Understood"
        }
    }
    if (step is ChatItem.ApprovalNeeded) return approvalStepLabel(step)
    val call = step as? ChatItem.ToolCall ?: return ""
    val args = call.args.orEmpty()
    val path = (args["file_path"] ?: args["path"] ?: args["filename"])?.truncateMiddle(prefix = 20, suffix = 16)
    return when (call.baseTool()) {
        in SHELL_TOOLS -> args["command"]?.takeIf { it.isNotBlank() } ?: call.name
        in SEARCH_TOOLS -> args["pattern"]?.let { "Searched $it" } ?: "Searched"
        "read", "read_file" -> path?.let { "Read $it" } ?: call.name
        "write" -> path?.let { "Wrote $it" } ?: call.name
        "edit" -> path?.let { "Edited $it" } ?: call.name
        else -> listOfNotNull(call.name, summarizeToolCall(call.name, call.args)).joinToString(" ")
    }
}

/** What opening a step shows, or null when there is nothing past its one line. */
private fun stepDetail(step: ChatItem): String? = when (step) {
    is ChatItem.Thinking -> step.content?.takeIf { it.isNotBlank() }
    is ChatItem.ApprovalNeeded -> summarizeToolCall(step.tool, step.arguments, maxLength = Int.MAX_VALUE)
    is ChatItem.ToolCall -> listOfNotNull(
        summarizeToolCall(step.name, step.args, maxLength = Int.MAX_VALUE),
        step.result?.takeIf { it.isNotBlank() }
    ).joinToString("\n\n").takeIf { it.isNotEmpty() }
    else -> null
}

private fun stepDurationMs(step: ChatItem): Long? = when (step) {
    is ChatItem.ToolCall -> step.timingMs
    is ChatItem.Thinking -> step.durationMs?.toLong()
    else -> null
}

private fun stepSpoken(step: ChatItem, failed: Boolean, running: Boolean): String = buildString {
    append(if (step.isThought()) "Thought: " else "")
    append(stepLabel(step))
    if (running) append(", running")
    if (failed) append(", failed")
    stepDurationMs(step)?.let { append(", ").append(spokenDuration(it)) }
}

/** "16s", "2m 5s", "<1s" — compact enough for the end of a step row. */
internal fun formatStepDuration(ms: Long): String {
    val seconds = ms / 1000
    return when {
        ms < 1000 -> "<1s"
        seconds < 60 -> "${seconds}s"
        seconds % 60 == 0L -> "${seconds / 60}m"
        else -> "${seconds / 60}m ${seconds % 60}s"
    }
}

private fun spokenDuration(ms: Long): String {
    val seconds = ms / 1000
    fun unit(n: Long, word: String) = "$n $word${if (n == 1L) "" else "s"}"
    return when {
        seconds < 1 -> "under a second"
        seconds < 60 -> unit(seconds, "second")
        seconds % 60 == 0L -> unit(seconds / 60, "minute")
        else -> "${unit(seconds / 60, "minute")} ${unit(seconds % 60, "second")}"
    }
}

internal fun stepCountLabel(count: Int): String = if (count == 1) "1 step" else "$count steps"

/** The summary's lead — "Worked for 16s", or just "Worked" when the turn's length is unknown. */
internal fun turnSummaryHeadline(durationMs: Long?): String =
    durationMs?.let { "Worked for ${formatStepDuration(it)}" } ?: "Worked"

/** What TalkBack reads for the summary row: the same facts, with units spelled out. */
internal fun turnSummarySpoken(durationMs: Long?, stepCount: Int, failedCount: Int): String = buildString {
    append(durationMs?.let { "Worked for ${spokenDuration(it)}" } ?: "Worked")
    append(", ").append(stepCountLabel(stepCount))
    if (failedCount > 0) append(", $failedCount failed")
}

private val SHELL_TOOLS = setOf("bash", "shell", "run", "run_background")
private val SEARCH_TOOLS = setOf("grep", "glob", "search", "find")

/** How many of a running turn's newest steps stay on screen; older ones fold behind a toggle. */
internal const val LIVE_STEP_COUNT = 3

internal const val TURN_SUMMARY_TAG = "turn_summary"

internal const val WORKING_HEADER_TAG = "working_header"
