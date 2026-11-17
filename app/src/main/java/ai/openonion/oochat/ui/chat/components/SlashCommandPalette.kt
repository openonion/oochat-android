package ai.openonion.oochat.ui.chat.components

import ai.openonion.oochat.domain.model.AgentSkill
import ai.openonion.oochat.ui.theme.spacing
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The `/skill` palette, sitting directly above the composer's field.
 *
 * Tap-driven rather than the web client's ArrowUp/Down + Tab + Enter: a phone
 * has no arrow keys and the soft keyboard's Enter is the send action, so there
 * is no selected index to move. Dismissal is the same one character it always
 * was — deleting the `/`, or typing a space to commit the command.
 *
 * Never rendered empty: [candidates] must be non-empty, which for an agent
 * that publishes no skills it never is.
 */
@Composable
fun SlashCommandPalette(
    candidates: List<AgentSkill>,
    onSelect: (AgentSkill) -> Unit,
    modifier: Modifier = Modifier
) {
    if (candidates.isEmpty()) return

    // One row per name: the rows are keyed on it, and LazyColumn throws on a
    // repeated key, which took the whole composer down. Two rows with one name
    // would insert the same `/name` anyway. First wins, as in
    // ProtocolParser.toAgentSkills; this guards every source, cleaned or not.
    val rows = remember(candidates) { candidates.distinctBy { it.name } }

    Column(
        modifier = modifier
            .fillMaxWidth()
            // Same inset and radius as the pill below, so the two read as one
            // control rather than a popup that happens to be nearby.
            .padding(horizontal = MaterialTheme.spacing.xs)
            .padding(bottom = MaterialTheme.spacing.xs)
            .clip(MaterialTheme.shapes.extraLarge)
            // surfaceContainer, not a higher one: the outlineVariant border below is 3.2:1 on it and 2.7:1 above.
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant,
                MaterialTheme.shapes.extraLarge
            )
    ) {
        LazyColumn(
            // Capped so a long skill list can't push the field under the
            // keyboard — the only state this is ever shown in.
            modifier = Modifier.heightIn(max = MaxPaletteHeight)
        ) {
            items(rows, key = { it.name }) { skill ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(skill) }
                        // md2, not md: at md a name-only row measures 44dp,
                        // under the 48dp minimum touch target.
                        .padding(
                            horizontal = MaterialTheme.spacing.lg,
                            vertical = MaterialTheme.spacing.md2
                        )
                ) {
                    Text(
                        text = "/${skill.name}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    skill.description?.let { description ->
                        // Two lines, hard: real descriptions run to whole
                        // paragraphs, and one verbose skill must not push the
                        // rest of the list out of the palette.
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = MaxDescriptionLines,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = MaterialTheme.spacing.xxs)
                        )
                    }
                }
            }
        }
    }
}


/** Roughly three described rows — enough to show there is a list, short enough to stay clear of the field. */
private val MaxPaletteHeight = 232.dp

private const val MaxDescriptionLines = 2
