package ai.openonion.oochat.ui.chat

import ai.openonion.oochat.ui.components.AgentInitialsAvatar
import ai.openonion.oochat.ui.theme.emptyChatHeadline
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Something to start from, for someone who has not used an agent before. */
internal data class ChatStarter(val label: String, val icon: ImageVector, val prefill: String)

// Each one fills the box rather than sending: a starter is a sentence to
// finish, and sending half of one would spend a turn on a question the agent
// can only answer by asking for the rest.
internal val ChatStarters = listOf(
    ChatStarter("Summarise a document", StarterIcons.Document, "Summarise this document: "),
    ChatStarter("Draft an email", StarterIcons.Mail, "Draft an email to "),
    ChatStarter("Compare options", StarterIcons.Columns, "Compare these options: "),
    ChatStarter("Plan a project", StarterIcons.Calendar, "Help me plan a project: ")
)

/**
 * A chat with no messages yet (W16AAPPLE-113): who you are talking to, a
 * question, the composer as a card in the middle of the screen, and a few
 * starters. The first message moves the screen to the normal transcript with
 * the composer along the bottom — see ChatScreen.
 *
 * With the keyboard up the starters go and the question drops to one line, so
 * the card stays in view above the keyboard rather than under it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EmptyChat(
    agentName: String?,
    keyboardOpen: Boolean,
    onStarter: (ChatStarter) -> Unit,
    modifier: Modifier = Modifier,
    composer: @Composable () -> Unit
) {
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    BoxWithConstraints(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        // Centred in whatever is left above the keyboard, and scrollable when
        // that is less than the content (a phone on its side).
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .padding(horizontal = 16.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Column(
                modifier = Modifier.widthIn(max = EmptyChatMaxWidth),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (agentName != null) {
                    AgentPill(agentName)
                    Spacer(Modifier.height(20.dp))
                }
                Text(
                    text = "What would you like to get done?",
                    style = MaterialTheme.typography.emptyChatHeadline(compact = keyboardOpen, wide = wide),
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(22.dp))
                composer()
                if (!keyboardOpen) {
                    Spacer(Modifier.height(24.dp))
                    Text(
                        text = "Or start with",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        ChatStarters.forEach { starter ->
                            StarterChip(starter, onClick = { onStarter(starter) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentPill(name: String) {
    Row(
        modifier = Modifier
            .height(32.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
            .padding(start = 4.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AgentInitialsAvatar(
            name = name,
            size = 24.dp,
            maxInitials = 1,
            textStyle = MaterialTheme.typography.labelMedium,
            backgroundColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

@Composable
private fun StarterChip(starter: ChatStarter, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier
                .height(44.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = starter.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = starter.label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
        }
    }
}

/** The card's width on a tablet: wide enough to write in, narrow enough to read as one block. */
private val EmptyChatMaxWidth = 680.dp

/**
 * Thin line icons, as drawn. Material's outlined set is filled shapes at a
 * heavier weight, which sat louder than the label beside them.
 */
private object StarterIcons {
    val Document = strokeIcon(
        "Document",
        "M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z",
        "M14 2v4a2 2 0 0 0 2 2h4",
        "M10 9H8",
        "M16 13H8",
        "M16 17H8"
    )
    val Mail = strokeIcon(
        "Mail",
        "M4 4h16a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z",
        "M22 7l-8.97 5.7a1.94 1.94 0 0 1-2.06 0L2 7"
    )
    val Columns = strokeIcon(
        "Columns",
        "M4 5h6a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1z",
        "M14 5h6a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1h-6a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1z"
    )
    val Calendar = strokeIcon(
        "Calendar",
        "M5 4h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z",
        "M16 2v4",
        "M8 2v4",
        "M3 10h18"
    )

    private fun strokeIcon(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .apply {
                paths.forEach {
                    addPath(
                        pathData = addPathNodes(it),
                        stroke = SolidColor(Color.Black),
                        strokeLineWidth = 2f,
                        strokeLineCap = StrokeCap.Round,
                        strokeLineJoin = StrokeJoin.Round
                    )
                }
            }
            .build()
}
