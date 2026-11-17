package ai.openonion.oochat.ui.theme

import ai.openonion.oochat.R
import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Inter, the chat design's face (W16AAPPLE-112), bundled as its two variable
 * fonts (res/font; licence in assets/licenses/inter_OFL.txt) with one entry per
 * weight the app uses, so each is drawn at its true weight rather than
 * synthesised. Monospace stays the system's, where the text is code or ticks.
 */
@OptIn(ExperimentalTextApi::class)
val Inter: FontFamily = FontFamily(
    listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold, FontWeight.ExtraBold)
        .flatMap { weight ->
            listOf(
                Font(
                    R.font.inter,
                    weight = weight,
                    style = FontStyle.Normal,
                    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight))
                ),
                Font(
                    R.font.inter_italic,
                    weight = weight,
                    style = FontStyle.Italic,
                    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight))
                )
            )
        }
)

// Material 3's tuned sizes and line-heights, set in Inter. Body text drops
// M3's positive tracking, which was tuned for Roboto and spaces Inter out
// further than the design draws it; the tweaks below are kept as they were.
private val m3 = Typography()
private fun TextStyle.inter(tracking: Boolean = true) =
    copy(fontFamily = Inter).let { if (tracking) it else it.copy(letterSpacing = 0.sp) }

private val baseline = Typography(
    displayLarge = m3.displayLarge.inter(),
    displayMedium = m3.displayMedium.inter(),
    displaySmall = m3.displaySmall.inter(),
    headlineLarge = m3.headlineLarge.inter(),
    headlineMedium = m3.headlineMedium.inter(),
    headlineSmall = m3.headlineSmall.inter(),
    titleLarge = m3.titleLarge.inter(),
    titleMedium = m3.titleMedium.inter(tracking = false),
    titleSmall = m3.titleSmall.inter(tracking = false),
    bodyLarge = m3.bodyLarge.inter(tracking = false),
    bodyMedium = m3.bodyMedium.inter(tracking = false),
    bodySmall = m3.bodySmall.inter(tracking = false),
    labelLarge = m3.labelLarge.inter(),
    labelMedium = m3.labelMedium.inter(tracking = false),
    labelSmall = m3.labelSmall.inter()
)

val AppTypography = baseline.copy(
    headlineLarge = baseline.headlineLarge.copy(letterSpacing = (-0.4).sp), // Tighter for large headings
    headlineMedium = baseline.headlineMedium.copy(letterSpacing = (-0.2).sp), // Matches Figma spec
    titleLarge = baseline.titleLarge.copy(letterSpacing = (-0.2).sp), // Tighter for prominent titles
    labelLarge = baseline.labelLarge.copy(letterSpacing = 0.03.sp), // Slightly expanded for chips/labels (matches Figma 0.03em)
    labelSmall = baseline.labelSmall.copy(letterSpacing = 0.04.sp) // Expanded for small caps/uppercase (matches Figma 0.04em)
)

/**
 * The de facto chat body style — message bubbles, markdown paragraphs, and
 * the input field all render at 15sp/22, a size that sits between M3's
 * bodyMedium (14) and bodyLarge (16) and previously existed only as
 * repeated fontSize/lineHeight literals at 8+ call sites. Tune the chat
 * font here, in one place.
 */
val Typography.chatBody: TextStyle
    get() = bodyMedium.copy(fontSize = 15.sp, lineHeight = 22.sp)

/**
 * ALL-CAPS *badges* that sit inline with content and need to be noticed:
 * "SOON" and the discovery screen's "RELAY"/"DIRECT" connection-mode pill.
 *
 * Same 11sp as [Typography.labelSmall] — the extra tracking is what uppercase
 * needs to stay legible, nothing more. Group *headings* are a different job,
 * see [Typography.sectionLabel] — badges stay Bold because they *are* the
 * content at their position, headings are Medium because they aren't.
 */
val Typography.sectionOverline: TextStyle
    get() = labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)

/**
 * ALL-CAPS group headings: "ACCOUNT", "BALANCE", "AGENTS", "UP NEXT", and
 * every SettingsSection title.
 *
 * Same 11sp and tracking as [Typography.sectionOverline] but Medium, not
 * Bold — see that doc for why the two are split.
 */
val Typography.sectionLabel: TextStyle
    get() = labelSmall.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp)

/**
 * The question over an empty chat's composer — the one line on that screen
 * meant to be read before anything else. 26sp on a phone; one step down to
 * 22sp with the keyboard up, where it has to fit on a line above the card;
 * 34sp on a tablet, where the card beneath it is twice as wide.
 */
fun Typography.emptyChatHeadline(compact: Boolean, wide: Boolean): TextStyle = headlineMedium.copy(
    fontSize = when {
        compact -> 22.sp
        wide -> 34.sp
        else -> 26.sp
    },
    lineHeight = when {
        compact -> 28.sp
        wide -> 42.sp
        else -> 32.sp
    },
    fontWeight = FontWeight.Bold,
    letterSpacing = (-0.4).sp
)

/*
 * ── The app's type ladder ────────────────────────────────────────────────
 *
 * One size per hierarchy level. When adding a Text, pick the level below and
 * use its role verbatim — don't pass a `fontSize` override, and don't reach
 * for a neighbouring role because a particular screen "looks better" a step
 * up or down. If a level genuinely doesn't exist here yet, add it here first.
 *
 *   headlineLarge  32  Unused. Reserved as the top of the ladder — nothing on
 *                      any screen currently outranks headlineMedium.
 *   headlineMedium 28  The splash wordmark and the account balance figure.
 *   titleLarge     22  Bottom-sheet titles; BackTopAppBar screen titles (M3
 *                      TopAppBar default). ChatTopBar is the one exception —
 *                      its title stacks over a status line, see its comment.
 *   titleMedium    16  Card titles in a list (agent cards), drawer wordmark
 *   titleSmall     14  Centred state titles (empty / error / success) and
 *                      warning-card headings
 *   bodyLarge      16  Settings/choice row labels; screen lead paragraph
 *   bodyMedium     14  Default body copy; sheet body; nav rows
 *   bodySmall      12  Secondary/supporting text under a row, field, or
 *                      button; monospace tool + log output
 *   labelLarge     14  Every button and pill-toggle label; chat card titles
 *   labelMedium    12  Field labels above inputs; status-pill text
 *   labelSmall     11  Captions: timestamps, mixed-case badges, status chips,
 *                      sender labels, slider ticks
 *   sectionOverline 11 ALL-CAPS badges — SOON, RELAY/DIRECT (see above)
 *   sectionLabel   11  ALL-CAPS group headings (see above)
 *   chatBody       15  Chat message text (see above)
 *   emptyChatHeadline 26  The empty chat's question (22 / 34, see above)
 */

/**
 * Fixed-width digits. A value that ticks — a recording timer, a playback
 * position — changes width as it counts if its figures are proportional, and
 * nudges everything beside it. Monospace makes them fixed whatever face the
 * device's system font is, which `tnum` alone cannot promise: not every system
 * font has the feature. It is kept for the faces that do.
 */
fun TextStyle.tabularFigures(): TextStyle = copy(fontFamily = FontFamily.Monospace, fontFeatureSettings = "tnum")
