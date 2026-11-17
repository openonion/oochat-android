# Design system

The app is green on white, with a proper dark mode (W16AAPPLE-49): the brand green ramp on warm
neutral greys, the system's own type with monospace for code, small radii, and surfaces lifted by a
hairline rather than a shadow. There is no other accent. Red is for failures and destructive
actions only.

Everything lives in `ui/theme/`, and the rules below are held by tests, so a screen that breaks one
fails the build instead of waiting to be noticed on a device. The one thing a test cannot do is look
at a screen, so [the checklist at the end](#verifying-a-screen-in-light-and-dark) is for a person.

## Palette

The brand ramp is `#F0FDF4` (50), `#22C55E` (500) and `#14532D` (900). The greys are Tailwind's
warm `stone` scale. Light mode is white; dark mode is stone 900.

`#22C55E` is only 2.3:1 on white, so it cannot carry text or a button label in light mode. Light
mode therefore puts the dark end of the ramp on the primary role and the pale end on its container,
and dark mode, where 500 is 7.7:1, puts the mid step on the primary role.

| Role | Light | Dark | Used for |
|---|---|---|---|
| `primary` | `#14532D` (9.1:1) | `#22C55E` (7.7:1) | Buttons, links, the active tab, connected dots |
| `onPrimary` | `#FFFFFF` | `#052E16` | Text on `primary` (9.1:1, 6.5:1) |
| `primaryContainer` | `#F0FDF4` | `#14532D` | Tints and selected states; text on it is 8.7:1 in both |
| `surface` | `#FFFFFF` | `#1C1917` | The page |
| `surfaceContainer` | `#F5F5F4` | `#292524` | Cards, fields, raised areas |
| `onSurface` | `#1C1917` (17.5:1) | `#F5F5F4` (16.0:1) | Body text |
| `onSurfaceVariant` | `#57534E` (7.6:1) | `#D6D3D1` (11.7:1) | Secondary text |
| `outline` | `#6B655F` (5.8:1) | `#A8A29E` (6.9:1) | Captions and labels |
| `outlineVariant` | `#8F8883` (3.5:1) | `#78716C` (3.6:1) | Dividers and borders |
| `error` | `#BA1A1A` (6.5:1) | `#FFB4AB` (10.3:1) | Failures and destructive actions only |

The ratios are against `surface`. Text roles are held to 4.5:1 on every surface they are read on, and
dividers to 3:1.

The greys get closer together on the raised containers, so the rule depends on where a role is drawn:
`outline` captions are legible up to `surfaceContainerHigh`, and on `surfaceContainerHighest` a caption is
`onSurfaceVariant`. An `outlineVariant` border is legible up to `surfaceContainer`, so a popup or card
with a border sits on `surfaceContainer` or lower.

Status colours (`MaterialTheme.statusColors`) follow the same rule: success is the brand green and a
warning is a warm grey, told apart by its icon and title rather than a hue. A connection that is still
coming up is the connected green pulsing, and the word "Connecting" says so.

## Type

Inter, the chat design's face (W16AAPPLE-112). This reverses W16AAPPLE-49's "system font, nothing
bundled": the design is drawn in Inter, and the system face (Roboto on most phones) read noticeably
looser and wider beside it. It is bundled as Inter's two variable fonts, `res/font/inter.ttf` and
`res/font/inter_italic.ttf` (about 1.7MB together in the APK), from the official Google Fonts
repository; the SIL Open Font License that lets it ship sits beside it in
`assets/licenses/inter_OFL.txt`, and must stay there while the font does. `Type.kt` builds one
`FontFamily`, `Inter`, with an entry per weight the app uses, each drawn at its true weight from the
variable font rather than synthesised. Body text drops Material 3's positive letter spacing, which was
tuned for Roboto.

Monospace is still the system's, for code, log lines, addresses and anything that ticks.
`tabularFigures()` is monospace as well as `tnum`, so a recording timer does not jitter.

Use the roles in `Type.kt` (the ladder is documented there) and do not pass a `fontSize` or build a
`FontFamily` of your own.

## Radii

One scale, in `Radii` and `AppShapes`: 2, 4, 6, 8 and 12dp. Nothing is rounder than 12dp apart from a
circle (dots, avatars, the Send and Stop buttons) and one named exception, `Radii.composer` (22dp):
the chat composer's corners, measured off the chat design (W16AAPPLE-112).

Use `MaterialTheme.shapes.<size>`, or `Radii.<size>` where a plain value is needed, as the dashed
attachment tile does. Chat bubbles and buttons are `large` (8dp), sheets, dialogs and
menus `extraLarge` (12dp). Material draws filled, tonal and outlined buttons fully round unless told
otherwise, and the scale cannot reach that default, so each one passes `shape = MaterialTheme.shapes.large`.

## Elevation

Surfaces sit flat. What lifts a menu or a popup is `Elevation.hairline` (a 1dp shadow) plus
`MaterialTheme.hairline` (a 1dp outline). `surfaceTint` is clear, so elevation never washes a surface
with the brand green.

Material's own `Snackbar` has a fixed 6dp shadow, so use `AppSnackbarHost` instead of `SnackbarHost`.
Every `DropdownMenu` passes its own `shadowElevation`, because Material's default is 3dp.

## Web pages

An agent's Dashboard and Control Center are web pages the agent wrote, so their colours are not ours.
The app does two things for them:

- It creates the WebView under a light or dark theme chosen by the app's own Light, Dark or System
  setting (`WebViewTheme.kt`, `themes.xml`). WebView takes `prefers-color-scheme` from the theme it is
  created under, and the activity's theme follows the phone's setting instead, so without this a page
  stayed light whenever the two disagreed.
- It lets WebView darken a page that has no dark styles of its own. A page that uses
  `prefers-color-scheme`, or opts out of darkening, is left as written.

A change of theme with the page open reloads it. For agent authors: write a dark style under
`@media (prefers-color-scheme: dark)` and the page follows the app. Algorithmic darkening is the
browser's best guess and will not match this palette exactly.

## What is enforced

| Test | Rule |
|---|---|
| `ColorSchemePaletteTest` | The ramp is on the primary roles, every neutral is a warm grey, text pairs are 4.5:1 and dividers 3:1 |
| `StatusColorsPaletteTest` | Success is green, a warning is a warm grey, both legible |
| `LogColorsTest` | The logs console uses green, warm greys and the error red, each 4.5:1 on the console |
| `ThemeColorsOnlyInThemeTest` | No colour literal or named colour outside `ui/theme` |
| `ContrastUsageTest` | No `outline` caption on the highest container, no `outlineVariant` border on a high one |
| `TypographyTest` | Every style is Inter, only Inter's two files are bundled and its licence ships with them, no custom family elsewhere, monospace timer |
| `ShapesTest` | The scale, the tokens (the composer on `Radii.composer`), the bubble's tail, no numeric radius, every button passes a shape |
| `ElevationTest` | No tint, no shadow above 1dp, every menu sets its shadow, no Material snackbar |
| `AgentStatusColorComposeTest` | Connecting is the brand green, apart from disabled grey and error red |
| `AppSnackbarHostComposeTest` | The app's snackbar shows its message and answers its action |
| `ControlCenterWebViewTest`, `DashboardWebViewTest`, `ControlCenterPageTest` | Both WebViews are created under the app's theme, in both directions, and follow a change |

## Adding a screen

- Take every colour from `MaterialTheme.colorScheme` or `MaterialTheme.statusColors`. The tests fail on a literal.
- Take every radius from `MaterialTheme.shapes`, and every button's shape from it too.
- Show state with a shape, an icon or a word as well as a colour, since there is only one accent.
- Lift with `Elevation.hairline` and `MaterialTheme.hairline`, never a bigger shadow.
- Show a web page through the same WebView setup, so it follows the theme.

## Verifying a screen in light and dark

The tests prove the palette and the rules. Whether a screen looks right needs a person. For each screen below,
open it in light and in dark and look for the things listed. Switch the theme in **Settings, Appearance**. For a
screen that shows web content (Dashboard, Control Center) also try the two cases where the app and the phone
disagree: app dark on a light phone, and app light on a dark phone. Do it once at the Large font size too.

| Screen | Look for |
|---|---|
| Setup and Loading, including the invite-code gate | The "Redeem code" button is the brand green, the gate card is a warm grey, no stray blue |
| Chat | Bubbles are 8dp with a small tail, the composer is a field and not a pill, the Stop and approval-mode chips read as buttons, captions are legible on every surface |
| Chat tabs (phone) | The Chat and Dashboard or Control Center tabs sit under the status bar with no gap, the selected one is clear, switching keeps the draft |
| Dashboard | The page is dark in dark mode, including one that has no dark styles of its own; cards are legible; flipping the theme reloads it |
| Control Center | As the Dashboard; an app with `prefers-color-scheme` follows it; the "cannot show this app" notice is legible |
| Tablet, side by side | The divider is a hairline, both panes follow the theme, no tab row or Home button |
| Approvals and status cards | "Allow once" and the other buttons, the plan panel's done, in-progress and to-do dots, the warning cards (grey with an icon), the turn-limit card |
| Attachment menu, `/` palette, menus | Lifted by a hairline and an outline, not a shadow, and still clearly separate from the page in light mode |
| Rem | Notebook and pages are legible, the Home and Rem buttons follow the shape |
| Agent list, edit and discovery | The status dots (a pulsing green dot is connecting), "Tap to select" is the brand green, the badge is grey |
| Settings and its sheets | Every row, the Appearance control, the confirmation sheets and their buttons |
| Logs | The console stays dark in light mode, levels are readable, a snackbar after "copy" is flat |
| Recovery | The card, the buttons and the text |
| Snackbars, dialogs and sheets | Flat, 8dp or 12dp corners, buttons on the theme's shape |

If something is unreadable, the fix is the role it uses, not a new colour. Tell the ticket which screen and which mode.
