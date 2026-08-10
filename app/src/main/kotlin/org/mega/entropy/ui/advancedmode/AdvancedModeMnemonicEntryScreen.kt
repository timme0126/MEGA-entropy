package org.mega.entropy.ui.advancedmode

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import org.mega.entropy.ui.components.MegaCard
import org.mega.entropy.ui.components.MegaInfoScaffold
import org.mega.entropy.ui.components.MegaPrimaryButton
import org.mega.entropy.ui.components.SecureScreen
import org.mega.entropy.ui.theme.MegaError
import org.mega.entropycore.ManualMnemonicValidation
import org.mega.entropycore.bip39WordsStartingWith
import org.mega.entropycore.validateManualMnemonic

private const val MAX_SUGGESTIONS = 6

/**
 * Entry point for Advanced Mode (spec "Advanced Mode workflow"): manually
 * typing in an existing BIP39 mnemonic to derive BIP85 children or wallet
 * account keys from it, rather than one MEGA generated from dice. Only
 * reachable when Advanced Mode is on, itself gated behind the confirmation
 * dialog in Saved Session Settings — the warning here is a quieter, always-
 * visible reminder of the same risk, not the first (or only) time the user
 * sees it.
 *
 * Word entry has a Samourai-Wallet-style autocomplete preview (see
 * MnemonicSeedEditText in samourai-wallet-android, which pops a dropdown
 * once its focused token is 2-4 characters): every official BIP39 word is
 * uniquely identified by its first four letters (some fewer), so once the
 * focused field's text narrows to one or a few matches, they appear as
 * tappable chips directly under the row containing that field — not fixed
 * at the bottom of the whole 12/24-field grid, where they'd be scrolled out
 * of view for every row but the last. Tapping a chip fills the word and
 * advances focus to the next field. validateManualMnemonic also accepts an
 * unambiguous prefix directly, so submitting works even without tapping a
 * suggestion.
 *
 * The passphrase is asked for on the next screen (AdvancedModeHubScreen),
 * not here — that's the one place both this manual-entry path and
 * "Import from Saved Session" converge, so there's exactly one spot to
 * answer "do you want a passphrase" no matter how the words got loaded.
 */
@Composable
fun AdvancedModeMnemonicEntryScreen(
    allowScreenshots: Boolean,
    onBack: () -> Unit,
    onValidated: (words: List<String>) -> Unit,
    onImportFromSavedSession: () -> Unit,
) {
    SecureScreen(enabled = !allowScreenshots)
    val focusManager = LocalFocusManager.current

    var wordCount by remember { mutableStateOf(12) }
    var wordFields by remember(wordCount) { mutableStateOf(List(wordCount) { "" }) }
    val focusRequesters = remember(wordCount) { List(wordCount) { FocusRequester() } }
    // One per row (pair of fields): wraps that row's fields together with
    // its suggestion chips (when shown) so bringIntoView() scrolls the
    // whole block — not just the text field itself — above the keyboard.
    val rowBringIntoViewRequesters = remember(wordCount) {
        List((wordCount + 1) / 2) { BringIntoViewRequester() }
    }
    var focusedIndex by remember { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val suggestions = remember(focusedIndex, wordFields) {
        val index = focusedIndex ?: return@remember emptyList()
        val typed = wordFields.getOrNull(index)?.trim()?.lowercase().orEmpty()
        if (typed.length < 2) {
            emptyList()
        } else {
            val matches = bip39WordsStartingWith(typed)
            if (matches.size == 1 && matches[0] == typed) emptyList() else matches.take(MAX_SUGGESTIONS)
        }
    }

    // Re-scrolls whenever focus moves to a new row, AND again once
    // suggestion chips appear for the already-focused row (the row's
    // height grows at that point, so the earlier scroll position may no
    // longer show the chips) — keeps the field being typed into, plus its
    // suggestions, above the on-screen keyboard automatically instead of
    // relying on the user to scroll manually.
    LaunchedEffect(focusedIndex, suggestions) {
        val index = focusedIndex ?: return@LaunchedEffect
        rowBringIntoViewRequesters.getOrNull(index / 2)?.bringIntoView()
    }

    fun fillSuggestion(word: String) {
        val index = focusedIndex ?: return
        wordFields = wordFields.toMutableList().also { it[index] = word }
        error = null
        val nextIndex = index + 1
        if (nextIndex < focusRequesters.size) {
            focusRequesters[nextIndex].requestFocus()
        } else {
            focusManager.clearFocus()
        }
    }

    MegaInfoScaffold(title = "Advanced Mode", onBack = onBack) {
        MegaPrimaryButton(text = "Import from Saved Session", onClick = onImportFromSavedSession)

        MegaCard {
            Text(
                "Entering an existing seed phrase on any connected Android device can " +
                    "expose the funds it controls if the device is compromised. Prefer an " +
                    "offline GrapheneOS phone for sensitive seed workflows.",
                style = MaterialTheme.typography.bodyMedium,
                color = MegaError,
                fontWeight = FontWeight.SemiBold,
            )
        }

        MegaCard(title = "Word count") {
            WordCountOption(
                label = "12 words",
                selected = wordCount == 12,
                onClick = { wordCount = 12; error = null },
            )
            WordCountOption(
                label = "24 words",
                selected = wordCount == 24,
                onClick = { wordCount = 24; error = null },
            )
        }

        MegaCard(title = "Seed words") {
            Text(
                "Type a few letters — once they match only one BIP39 word, tap it below.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            wordFields.chunked(2).forEachIndexed { rowIndex, pair ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .bringIntoViewRequester(rowBringIntoViewRequesters[rowIndex]),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        pair.forEachIndexed { colIndex, word ->
                            val index = rowIndex * 2 + colIndex
                            OutlinedTextField(
                                value = word,
                                onValueChange = { value ->
                                    wordFields = wordFields.toMutableList().also { it[index] = value }
                                    error = null
                                },
                                label = { Text((index + 1).toString()) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                                modifier = Modifier
                                    .weight(1f)
                                    .focusRequester(focusRequesters[index])
                                    .onFocusChanged { if (it.isFocused) focusedIndex = index },
                            )
                        }
                    }
                    // Anchored directly under the row containing the focused
                    // field — like a dropdown pinned under the field being
                    // typed into — rather than in one fixed spot at the bottom
                    // of the whole 12/24-field grid, where it'd be scrolled
                    // out of view for every row except the last.
                    if (focusedIndex?.let { it / 2 } == rowIndex && suggestions.isNotEmpty()) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                        ) {
                            suggestions.forEach { word ->
                                AssistChip(onClick = { fillSuggestion(word) }, label = { Text(word) })
                            }
                        }
                    }
                }
            }
        }

        val currentError = error
        if (currentError != null) {
            Text(currentError, style = MaterialTheme.typography.bodyMedium, color = MegaError)
        }

        MegaPrimaryButton(
            text = "Validate & Continue",
            onClick = {
                when (val validation = validateManualMnemonic(wordFields)) {
                    is ManualMnemonicValidation.Valid -> {
                        error = null
                        onValidated(validation.words)
                    }
                    is ManualMnemonicValidation.Invalid -> {
                        error = validation.reason
                    }
                }
            },
        )
    }
}

@Composable
private fun WordCountOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
