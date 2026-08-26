package org.mega.entropy.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * MEGA's width-based breakpoints, matching Material Design 3's window size
 * classes (Compact/Medium/Expanded) -- chosen deliberately so the three
 * required test widths (360dp, 600dp, 840dp) land exactly on Compact,
 * Medium, and Expanded respectively, with no ambiguous edge case at any of
 * them. Based on *available width*, not device model or a hardcoded phone
 * vs. tablet check, so a large-screen phone in landscape and a small
 * tablet in portrait that happen to report the same width get the same
 * layout -- and a resizable/split-screen window crossing a threshold while
 * the app is already running reflows live, since it is just reading
 * LocalConfiguration.
 *
 * - Compact  (<600dp):  today's phone design, completely unchanged.
 * - Medium   (600-839dp): a large phone or a tablet held in portrait.
 * - Expanded (>=840dp): a tablet in landscape, or a similarly wide window.
 */
enum class MegaWindowSizeClass {
    Compact,
    Medium,
    Expanded,
}

/** Pure function, kept separate from the @Composable wrapper below purely
 * so the breakpoint boundaries themselves can be unit-tested on the JVM
 * without pulling in the Compose runtime or an instrumented device. */
internal fun megaWindowSizeClassForWidth(widthDp: Int): MegaWindowSizeClass = when {
    widthDp < 600 -> MegaWindowSizeClass.Compact
    widthDp < 840 -> MegaWindowSizeClass.Medium
    else -> MegaWindowSizeClass.Expanded
}

/** Reads the current available width from LocalConfiguration, which
 * updates live as the window resizes (rotation, split-screen, freeform
 * resize) -- this is deliberately NOT read once and cached, so the whole
 * app reflows automatically as a window is dragged across a breakpoint. */
@Composable
fun rememberMegaWindowSizeClass(): MegaWindowSizeClass {
    val widthDp = LocalConfiguration.current.screenWidthDp
    return remember(widthDp) { megaWindowSizeClassForWidth(widthDp) }
}

/** The narrow-phone content padding every screen already used before this
 * file existed -- kept as the Compact-width value so nothing about the
 * existing phone layout changes. */
private val MegaCompactScreenPadding = MegaScreenPadding

/** A little more breathing room once there's width to spare -- still modest
 * (this is padding around already-capped content, not what makes a wide
 * screen feel wide; MegaContentMaxWidth does that job). */
private val MegaWideScreenPadding = PaddingValues(horizontal = 32.dp, vertical = 16.dp)

/** Default cap for a single column of body content/forms (About, Security
 * Model, session detail, dialogs-as-screens, PIN entry, dice entry, ...).
 * Wide enough to comfortably read multi-paragraph text or show a QR code
 * at a sensible size, narrow enough that line lengths and button widths
 * don't sprawl across a whole tablet screen. */
val MegaContentMaxWidth = 640.dp

/** Narrower cap for anything centered around a small, focused control
 * cluster rather than reading text -- the PIN keypad chief among them.
 * Without this, a keypad row's Modifier.weight(1f) buttons would grow to
 * fill the full screen width, since each button is also aspectRatio(1f)
 * -- turning "large touch target" into "comically oversized button" on a
 * tablet. */
val MegaFocusedContentMaxWidth = 420.dp

/**
 * Wraps [content] in the padding/max-width treatment every MEGA screen
 * should use: at Compact width this is pixel-for-pixel what every screen
 * already did (fillMaxWidth, [MegaScreenPadding]) -- at Medium/Expanded,
 * content is centered under a max width instead of stretching edge to
 * edge, with slightly more padding around it. This is the single
 * building block [MegaInfoScaffold] and the screens that don't use that
 * scaffold are updated to use, rather than duplicating the same
 * width/padding logic per screen.
 */
@Composable
fun MegaResponsiveContent(
    modifier: Modifier = Modifier,
    maxWidth: Dp = MegaContentMaxWidth,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    windowSizeClass: MegaWindowSizeClass = rememberMegaWindowSizeClass(),
    content: @Composable ColumnScope.() -> Unit,
) {
    val padding = if (windowSizeClass == MegaWindowSizeClass.Compact) {
        MegaCompactScreenPadding
    } else {
        MegaWideScreenPadding
    }
    // fillMaxHeight (not just width) on the inner column, in both branches,
    // is what lets verticalArrangement = Arrangement.Center keep working
    // exactly as it did before this wrapper existed for screens like
    // Welcome and Loading -- without it, a wide-screen Column would only
    // be as tall as its content and this Box's TopCenter alignment would
    // pin that shorter column to the top instead of centering it.
    Box(modifier = modifier, contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = if (windowSizeClass == MegaWindowSizeClass.Compact) {
                Modifier
                    .fillMaxSize()
                    .padding(padding)
            } else {
                Modifier
                    .fillMaxHeight()
                    .widthIn(max = maxWidth)
                    .padding(padding)
            },
            verticalArrangement = verticalArrangement,
            horizontalAlignment = horizontalAlignment,
            content = content,
        )
    }
}

/**
 * A two-pane list/detail layout for Expanded width only -- callers decide
 * for themselves whether to use this or fall back to normal push
 * navigation at Compact/Medium (that decision depends on each screen's
 * own navigation wiring, so it isn't baked into this primitive). The
 * list pane keeps a sensible minimum width so it doesn't get squeezed
 * illegibly thin in a very wide-but-not-huge window.
 */
@Composable
fun MegaListDetailPane(
    listContent: @Composable RowScope.() -> Unit,
    detailContent: @Composable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
    listWeight: Float = 0.4f,
    listMinWidth: Dp = 320.dp,
) {
    Row(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(listWeight).widthIn(min = listMinWidth).fillMaxHeight()) {
            Row { listContent() }
        }
        VerticalDivider(color = MaterialTheme.colorScheme.outline)
        Box(modifier = Modifier.weight(1f - listWeight).fillMaxHeight()) {
            Row { detailContent() }
        }
    }
}

/** A slim, non-scrolling section index for a long settings-style screen at
 * Expanded width -- clicking an entry scrolls the (still single, still
 * unchanged) content column to that section, rather than splitting
 * settings into separate routes/screens. Kept deliberately lightweight:
 * this is a jump-list, not a second navigation graph. */
@Composable
fun MegaSectionNavRail(
    sections: List<String>,
    onSectionClicked: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.width(200.dp)) {
        sections.forEachIndexed { index, section ->
            TextButton(onClick = { onSectionClicked(index) }) {
                Text(
                    text = section,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
