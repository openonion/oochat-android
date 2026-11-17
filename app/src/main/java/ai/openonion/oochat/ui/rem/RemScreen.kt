package ai.openonion.oochat.ui.rem

import ai.openonion.oochat.domain.model.RemNotebook
import ai.openonion.oochat.domain.model.RemOutcome
import ai.openonion.oochat.domain.model.RemPage
import ai.openonion.oochat.ui.chat.markdown.LinkResolver
import ai.openonion.oochat.ui.chat.markdown.MarkdownRenderer
import ai.openonion.oochat.ui.common.appViewModel
import ai.openonion.oochat.ui.components.BackTopAppBar
import ai.openonion.oochat.ui.theme.spacing
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun RemScreenWrapper(onNavigateBack: () -> Unit, viewModel: RemViewModel = appViewModel()) {
    val state by viewModel.uiState.collectAsState()
    RemScreen(
        state = state,
        onNavigateBack = onNavigateBack,
        onRetry = viewModel::load,
        onOpenPage = viewModel::openPage,
        onClosePage = viewModel::closePage
    )
}

/**
 * The agent owner's Rem notebook (W16AAPPLE-69), O Chat's private Rem page
 * (oo-chat #246, renamed in #267) in the app. Pages are read-only Markdown.
 * A phone shows the contents or one page; from [TwoPaneMinWidth] the two sit
 * side by side. Every state other than the notebook says what happened and
 * what to do next, never a blank page or an endless spinner.
 */
@Composable
fun RemScreen(
    state: RemUiState,
    onNavigateBack: () -> Unit,
    onRetry: () -> Unit,
    onOpenPage: (String) -> Unit,
    onClosePage: () -> Unit,
    modifier: Modifier = Modifier
) {
    val notebook = (state.outcome as? RemOutcome.Loaded)?.notebook
    val openPage = notebook?.pages?.firstOrNull { it.id == state.openPageId }

    Scaffold(
        modifier = modifier,
        topBar = { BackTopAppBar(title = "Rem", onNavigateBack = onNavigateBack) }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val outcome = state.outcome) {
                null -> Loading()
                is RemOutcome.Loaded -> Notebook(outcome.notebook, openPage, onOpenPage, onClosePage)
                else -> outcome.message(state.ownAddress).let { message ->
                    StateCard(message, onRetry.takeIf { message.canRetry })
                }
            }
        }
    }
}

@Composable
private fun Loading() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator()
        Text(
            text = "Reading this agent's Rem…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = MaterialTheme.spacing.lg)
        )
    }
}

@Composable
private fun Notebook(
    notebook: RemNotebook,
    openPage: RemPage?,
    onOpenPage: (String) -> Unit,
    onClosePage: () -> Unit
) {
    if (notebook.pages.isEmpty()) {
        StateCard(RemMessage("No pages yet", "This Rem notebook is empty. Pages appear as the agent writes them."), onRetry = null)
        return
    }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        if (maxWidth >= TwoPaneMinWidth) {
            Row(modifier = Modifier.fillMaxSize()) {
                Contents(notebook, openPage?.id, onOpenPage, Modifier.width(ContentsPaneWidth).fillMaxHeight())
                VerticalDivider()
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    if (openPage != null) {
                        PageView(notebook, openPage, onOpenPage)
                    } else {
                        StateCard(RemMessage("Pick a page", "Choose a page on the left to read it."), onRetry = null)
                    }
                }
            }
        } else if (openPage != null) {
            // Back closes the page first, as a phone's own navigation would.
            BackHandler(onBack = onClosePage)
            PageView(notebook, openPage, onOpenPage)
        } else {
            Contents(notebook, null, onOpenPage, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun Contents(notebook: RemNotebook, selectedId: String?, onOpenPage: (String) -> Unit, modifier: Modifier) {
    LazyColumn(modifier = modifier, contentPadding = PaddingValues(vertical = MaterialTheme.spacing.sm)) {
        notebook.sections.forEach { (category, pages) ->
            item(key = "section:$category") {
                Text(
                    text = categoryLabel(category),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .semantics { heading() }
                        .padding(
                            start = MaterialTheme.spacing.lg,
                            end = MaterialTheme.spacing.lg,
                            top = MaterialTheme.spacing.lg,
                            bottom = MaterialTheme.spacing.xs
                        )
                )
            }
            items(pages, key = { it.id }) { page ->
                val selected = page.id == selectedId
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .padding(horizontal = MaterialTheme.spacing.sm)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
                        .clickable { onOpenPage(page.id) }
                        .padding(horizontal = MaterialTheme.spacing.sm, vertical = MaterialTheme.spacing.sm),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = page.title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    page.updatedDay()?.let { day ->
                        Text(
                            text = "Updated $day",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PageView(notebook: RemNotebook, page: RemPage, onOpenPage: (String) -> Unit) {
    // A link to another page of the notebook opens it; anything else (a web
    // address, a page this notebook does not have) stays as plain underlined text.
    val links = remember(notebook, page.id) {
        LinkResolver { target -> notebook.pageLinkedFrom(page.id, target)?.let { linked -> { onOpenPage(linked.id) } } }
    }
    Box(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            // Capped for reading: a full tablet width of prose is hard to follow.
            modifier = Modifier.widthIn(max = ReadingWidth).fillMaxWidth().padding(MaterialTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)
        ) {
            // Most pages open on their own `# Title`; a page that doesn't gets one.
            if (!page.markdown.trimStart().startsWith("#")) {
                Text(
                    text = page.title,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() }
                )
            }
            page.updatedDay()?.let { day ->
                Text(
                    text = "${categoryLabel(page.category)} · Updated $day",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            MarkdownRenderer.RenderBlocks(text = page.markdown, textColor = MaterialTheme.colorScheme.onSurface, links = links)
        }
    }
}

/**
 * A state other than the notebook: what happened, and the command or button
 * that gets past it. [canRetry] is false where reading again cannot change the
 * answer, so the screen offers no "Try again" that would only fail the same way.
 */
internal data class RemMessage(
    val title: String,
    val body: String,
    val command: String? = null,
    val canRetry: Boolean = true
)

internal fun RemOutcome.message(ownAddress: String?): RemMessage = when (this) {
    is RemOutcome.Loaded -> RemMessage("Rem", "")
    RemOutcome.NotConnected -> RemMessage(
        "Not connected",
        "Connect to this agent from the chat, then try again."
    )
    // The commands are co rem's own since connectonion 1.9.0, as O Chat says
    // them (oo-chat #267); `co wiki` now runs nothing.
    RemOutcome.NotOwner -> RemMessage(
        "Only the owner can read this Rem",
        "The agent's Host shows Rem to its owner only. If this is your agent, run this on the Host's machine, then try again.",
        command = ownAddress?.let { "co trust admin add $it" }
    )
    RemOutcome.NoNotebook -> RemMessage(
        "No Rem on this Host",
        "This agent's Host has no Rem notebook yet. Create one on the Host's machine, then try again.",
        command = "co rem init"
    )
    RemOutcome.TooLarge -> RemMessage(
        "Rem is too large to open here",
        "This notebook is over the Host's 16 MiB limit. Open it on the Host's machine instead.",
        command = "co rem open",
        // The notebook does not shrink by being asked again.
        canRetry = false
    )
    RemOutcome.SessionLost -> RemMessage(
        "Reconnect to read Rem",
        // The chat still looks connected here, so it offers no Reconnect: only
        // a fresh start sends the CONNECT the Host needs (W16AAPPLE-108 tracks
        // reconnecting by itself).
        "The agent's Host no longer recognises this connection, for example because it restarted. " +
            "Close the app fully and open it again to reconnect, then open Rem again.",
        // A read on this socket is refused until it reconnects.
        canRetry = false
    )
    RemOutcome.HostTooOld -> RemMessage(
        "This Host is too old for Rem",
        "Update ConnectOnion on the Host's machine and restart it, then open Rem again.",
        // The restart drops this connection anyway (see SessionLost).
        canRetry = false
    )
    RemOutcome.NoAnswer -> RemMessage(
        "The agent didn't answer",
        "Its Host didn't send the notebook within 25 seconds. Check the agent is running, then try again."
    )
    // The Host's own wording stays in the log: it is not written for this screen.
    is RemOutcome.Failed -> RemMessage(
        "Couldn't read Rem",
        "Something went wrong reading this agent's Rem. Try again in a moment."
    )
}

@Composable
private fun StateCard(message: RemMessage, onRetry: (() -> Unit)?) {
    Box(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.widthIn(max = ReadingWidth).padding(MaterialTheme.spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md)
        ) {
            Text(
                text = message.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() }
            )
            Text(
                text = message.body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            message.command?.let { command ->
                // Selectable, so it can be copied to the Host's terminal.
                SelectionContainer {
                    Text(
                        text = command,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(horizontal = MaterialTheme.spacing.md, vertical = MaterialTheme.spacing.sm)
                    )
                }
            }
            if (onRetry != null) {
                Button(onClick = onRetry) { Text("Try again") }
            }
        }
    }
}

/** `people` → `People`. */
internal fun categoryLabel(category: String): String = category.replaceFirstChar { it.uppercase() }

/** The day part of the page's ISO timestamp, as the Host wrote it. */
private fun RemPage.updatedDay(): String? = updated?.take(10)?.takeIf { it.length == 10 }

private val TwoPaneMinWidth = 720.dp
private val ContentsPaneWidth = 320.dp
private val ReadingWidth = 720.dp
