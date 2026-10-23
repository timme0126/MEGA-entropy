package org.mega.entropy.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selectable
import androidx.compose.foundation.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isHeader
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.mega.entropy.ui.theme.MegaError

/**
 * Shared TalkBack semantics helpers (F1 accessibility pass).
 *
 * Everything here is additive semantics: sighted users see exactly what
 * they saw before. The row components ([MegaRadioRow], [MegaCheckboxRow],
 * [MegaSwitchRow]) reproduce the existing visual composition — only the
 * accessibility tree and the click-ownership change, so a screen reader
 * hears one labeled control per row instead of a nested pair.
 */

// ---------------------------------------------------------------- headings

/**
 * Marks a Text as a heading so TalkBack announces it with the
 * "Heading" role and offers heading-based navigation. Applied to the
 * shared title surfaces (MegaInfoScaffold's TopAppBar title,
 * MegaSection headings, MegaCard titles) so every screen built on them
 * gets correct headings for free.
 */
fun Modifier.megaHeading(): Modifier = semantics { isHeader = true }

// ---------------------------------------------------------- live regions

/**
 * Makes the modified node a polite live region: when its announced
 * content changes while focus sits elsewhere, TalkBack speaks the
 * change. Attach to the persistent, mutating nodes — progress lines,
 * error lines that swap text in place.
 */
fun Modifier.megaLiveRegion(): Modifier = semantics { liveRegion = LiveRegionMode.Polite }

/**
 * Imperative one-shot announcement (e.g. next to a Toast, or after a
 * copy succeeds). Uses the root view's own accessibility announcement
 * channel, so it adds no node to the composition at all — nothing new
 * to focus, nothing to reflow. Prefer [megaLiveRegion]/[MegaErrorText]
 * for visible content that persists and mutates; prefer this for
 * transient events that have no node of their own.
 */
@Composable
fun rememberMegaAnnounce(): (String) -> Unit {
    val view = LocalView.current
    return remember(view) { { message -> view.announceForAccessibility(message) } }
}

/**
 * Host for a transient message: announces [message] whenever it changes
 * to a new non-null value. Renders no node of its own (zero layout
 * impact), so it can be placed next to the state it speaks about
 * without disturbing any arrangement/spacing of visible content.
 */
@Composable
fun MegaAnnouncementHost(message: String?) {
    val announce = rememberMegaAnnounce()
    LaunchedEffect(message) {
        if (!message.isNullOrEmpty()) announce(message)
    }
}

/**
 * Transient error message as visible text plus a polite live region, so
 * an error that appears on an async failure is also spoken. Doubles as
 * the visible MegaError text: render it in place of a plain
 * `Text(msg, color = MegaError)` and the visible result is identical.
 */
@Composable
fun MegaErrorText(
    message: String?,
    modifier: Modifier = Modifier,
    color: Color = MegaError,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    if (message.isNullOrEmpty()) return
    Text(
        text = message,
        color = color,
        style = style,
        modifier = modifier.megaLiveRegion(),
    )
}

// ------------------------------------------------------- selectable rows

/**
 * Radio-button + label row with a single accessibility/click owner.
 *
 * The previous per-screen pattern (`Row(clickable) { RadioButton; Text }`)
 * exposed two nested clickable nodes: the inner RadioButton won the
 * semantics merge, so the row read as an unlabeled radio plus a loose,
 * non-actionable label. Here the row is the one `selectable` node —
 * role RadioButton, "selected/not selected" state, and the label merged
 * into it — while the inner RadioButton stays purely visual.
 */
@Composable
fun MegaRadioRow(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    labelStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    spacing: Dp = 8.dp,
) {
    Row(
        modifier = modifier
            .selectable(
                selected = selected,
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                enabled = true,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing),
    ) {
        // Keeps its own onClick so a sighted user tapping the circle
        // behaves exactly as before; clearAndSetSemantics with no
        // content makes it invisible to TalkBack so the row is the
        // single focusable node.
        RadioButton(
            selected = selected,
            onClick = onClick,
            modifier = Modifier.clearAndSetSemantics {},
        )
        if (description == null) {
            Text(label, style = labelStyle)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, style = labelStyle)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Checkbox + label row as one toggleable node with Role.Checkbox, so the
 * checkbox is announced WITH its label (previously the sibling Text left
 * it unlabeled: "Checkbox, double tap to select"). The inner Checkbox
 * renders the visual state and stays purely for sighted eyes.
 */
@Composable
fun MegaCheckboxRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    labelStyle: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    Row(
        modifier = modifier
            .toggleable(
                value = checked,
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                role = Role.Checkbox,
                onValueChange = { onCheckedChange(!checked) },
            )
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Null onCheckedChange renders the same, just stops the inner
        // Checkbox from owning a second click action (double-toggle).
        Checkbox(
            checked = checked,
            onCheckedChange = null,
            modifier = Modifier.clearAndSetSemantics {},
        )
        Text(label, style = labelStyle)
    }
}

/**
 * Label/description column + Switch row as one toggleable node with
 * Role.Switch (mirrors SettingSwitchRow's previous clickable-row layout).
 * The row is the single click owner and announces
 * "label, description, On/Off, double tap to toggle"; the inner Switch
 * stays purely visual.
 */
@Composable
fun MegaSwitchRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    description: String?,
    modifier: Modifier = Modifier,
    spacing: Dp = 12.dp,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                role = Role.Switch,
                onValueChange = { onCheckedChange(!checked) },
            )
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (description != null) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // Null onCheckedChange renders identically, just stops the
        // Switch owning a second action (double-toggle).
        Switch(
            checked = checked,
            onCheckedChange = null,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

// ------------------------------------------------------------ status

/**
 * A status glyph ("✓", "⚠", "!", …) whose color stays as reinforcement
 * only: TalkBack speaks [spoken] ("Pass", "Warning", "Failed", "Done")
 * instead of trying to read the raw glyph. Renders a plain Text with the
 * same glyph/style/color as before — identical for sighted users.
 */
@Composable
fun MegaStatusBadge(
    glyph: String,
    spoken: String,
    color: Color,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.titleMedium,
    fontWeight: FontWeight? = null,
) {
    Text(
        text = glyph,
        color = color,
        style = style,
        fontWeight = fontWeight,
        modifier = modifier.clearAndSetSemantics { contentDescription = spoken },
    )
}

/**
 * Count-encoded status shown only as filled/empty shapes (PIN dots):
 * speak "[noun]: n of max" and keep it a live region so each tap
 * announces. Semantics only — the dots themselves are unchanged.
 */
fun Modifier.megaCountStatus(entered: Int, max: Int, noun: String): Modifier =
    semantics {
        liveRegion = LiveRegionMode.Polite
        contentDescription = noun
        stateDescription = "$entered of $max"
    }
