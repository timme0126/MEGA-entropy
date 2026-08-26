package org.mega.entropy.ui.savedsessions

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.roundToInt
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch
import org.mega.entropy.security.settings.SavedSessionLockTimeoutOption
import org.mega.entropy.storage.SavedSessionMetadata
import org.mega.entropy.ui.backup.BackupCard
import org.mega.entropy.ui.components.MegaCard
import org.mega.entropy.ui.components.MegaInfoScaffold
import org.mega.entropy.ui.components.MegaLabelSessionDialog
import org.mega.entropy.ui.components.MegaListDetailPane
import org.mega.entropy.ui.components.MegaNeutralButton
import org.mega.entropy.ui.components.MegaPrimaryButton
import org.mega.entropy.ui.components.MegaWindowSizeClass
import org.mega.entropy.ui.components.MegaSecondaryButton
import org.mega.entropy.ui.components.MegaSectionNavRail
import org.mega.entropy.ui.components.rememberMegaWindowSizeClass
import org.mega.entropy.ui.components.SecureScreen

/** Spec section 24: "Saved Sessions" entry point from Welcome. */
@Composable
fun SavedSessionsScreen(
    onBack: () -> Unit,
    onChangePin: () -> Unit,
    onViewSession: (String) -> Unit,
    onNewDiceSession: () -> Unit,
    onChangeDuressPin: () -> Unit,
    selectedLockTimeoutMillis: Long,
    lockTimeoutOptions: List<SavedSessionLockTimeoutOption>,
    onLockTimeoutSelected: (Long) -> Unit,
    randomizePinKeypad: Boolean,
    onRandomizePinKeypadChanged: (Boolean) -> Unit,
    allowScreenshots: Boolean,
    onAllowScreenshotsChanged: (Boolean) -> Unit,
    allowSeedCopy: Boolean,
    onAllowSeedCopyChanged: (Boolean) -> Unit,
    allowPrivateKeyExport: Boolean,
    onAllowPrivateKeyExportChanged: (Boolean) -> Unit,
    advancedModeEnabled: Boolean,
    onAdvancedModeChanged: (Boolean) -> Unit,
    // Only used by the Expanded-width two-pane layout, to embed
    // SavedSessionDetailScreen directly instead of navigating to it.
    diceRollsLockedDefault: Boolean = false,
    onDiceRollsLockedDefaultChanged: (Boolean) -> Unit = {},
    viewModel: SavedSessionsViewModel = viewModel(),
) {
    SecureScreen(enabled = !allowScreenshots)
    val state by viewModel.uiState.collectAsState()
    var confirmingDeleteAll by remember { mutableStateOf(false) }
    var showingSettings by remember { mutableStateOf(false) }

    // This destination stays on the back stack (and its ViewModel with it)
    // while PIN setup/change is pushed on top, so the cached isPinEnabled
    // here goes stale the moment that flow completes. Re-running this
    // LaunchedEffect(Unit) every time this composable re-enters composition
    // (i.e. every time we're navigated back to) re-syncs it, rather than
    // requiring a full app relaunch to pick up the change.
    LaunchedEffect(Unit) { viewModel.refresh() }

    if (showingSettings) {
        SavedSessionSettingsScreen(
            pinButtonText = if (state.isPinEnabled) "Change PIN" else "Set Up PIN",
            duressPinEnabled = state.isDuressPinEnabled,
            selectedLockTimeoutMillis = selectedLockTimeoutMillis,
            lockTimeoutOptions = lockTimeoutOptions,
            onLockTimeoutSelected = onLockTimeoutSelected,
            randomizePinKeypad = randomizePinKeypad,
            onRandomizePinKeypadChanged = onRandomizePinKeypadChanged,
            allowScreenshots = allowScreenshots,
            onAllowScreenshotsChanged = onAllowScreenshotsChanged,
            allowSeedCopy = allowSeedCopy,
            onAllowSeedCopyChanged = onAllowSeedCopyChanged,
            allowPrivateKeyExport = allowPrivateKeyExport,
            onAllowPrivateKeyExportChanged = onAllowPrivateKeyExportChanged,
            advancedModeEnabled = advancedModeEnabled,
            onAdvancedModeChanged = onAdvancedModeChanged,
            onChangePin = {
                showingSettings = false
                onChangePin()
            },
            onChangeDuressPin = {
                showingSettings = false
                onChangeDuressPin()
            },
            onClearDuressPin = { viewModel.clearDuressPin() },
            onDeleteAll = {
                showingSettings = false
                confirmingDeleteAll = true
            },
            onBack = { showingSettings = false },
        )
    } else {
        val windowSizeClass = rememberMegaWindowSizeClass()
        // Only meaningful at Expanded width -- Compact/Medium always
        // navigate away via onViewSession instead, exactly as before this
        // file gained two-pane support.
        var selectedSessionId by remember { mutableStateOf<String?>(null) }

        MegaInfoScaffold(
            title = "Saved Sessions",
            onBack = onBack,
            actions = {
                IconButton(onClick = { showingSettings = true }) {
                    Icon(Icons.Filled.Settings, contentDescription = "Saved session settings")
                }
            },
            scrollable = windowSizeClass != MegaWindowSizeClass.Expanded,
        ) {
            if (windowSizeClass == MegaWindowSizeClass.Expanded) {
                MegaListDetailPane(
                    listContent = {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(end = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            SavedSessionsListBody(
                                state = state,
                                viewModel = viewModel,
                                onNewDiceSession = onNewDiceSession,
                                onView = { sessionId -> selectedSessionId = sessionId },
                            )
                        }
                    },
                    detailContent = {
                        val currentSessionId = selectedSessionId
                        if (currentSessionId == null) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    "Select a session to view its details.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            org.mega.entropy.ui.savedsessiondetail.SavedSessionDetailScreen(
                                sessionId = currentSessionId,
                                allowScreenshots = allowScreenshots,
                                allowSeedCopy = allowSeedCopy,
                                diceRollsLockedDefault = diceRollsLockedDefault,
                                onDiceRollsLockedDefaultChanged = onDiceRollsLockedDefaultChanged,
                                onBack = { selectedSessionId = null },
                            )
                        }
                    },
                )
            } else {
                SavedSessionsListBody(
                    state = state,
                    viewModel = viewModel,
                    onNewDiceSession = onNewDiceSession,
                    onView = onViewSession,
                )
            }
        }
    }

    if (confirmingDeleteAll) {
        ConfirmDeleteDialog(
            text = "This permanently deletes every saved session and its encryption key. This cannot be undone.",
            confirmText = "Secure Delete All",
            onConfirm = {
                viewModel.deleteAllSessions()
                confirmingDeleteAll = false
            },
            onDismiss = { confirmingDeleteAll = false },
        )
    }
}

/** Full-screen settings for saved sessions, reached via the cog on
 * [SavedSessionsScreen]. Grouped into auto-lock timing, PIN management, and
 * a visually separated destructive section, instead of one flat dialog. */
@Composable
fun SavedSessionSettingsScreen(
    pinButtonText: String,
    duressPinEnabled: Boolean,
    selectedLockTimeoutMillis: Long,
    lockTimeoutOptions: List<SavedSessionLockTimeoutOption>,
    onLockTimeoutSelected: (Long) -> Unit,
    randomizePinKeypad: Boolean,
    onRandomizePinKeypadChanged: (Boolean) -> Unit,
    allowScreenshots: Boolean,
    onAllowScreenshotsChanged: (Boolean) -> Unit,
    allowSeedCopy: Boolean,
    onAllowSeedCopyChanged: (Boolean) -> Unit,
    allowPrivateKeyExport: Boolean,
    onAllowPrivateKeyExportChanged: (Boolean) -> Unit,
    advancedModeEnabled: Boolean,
    onAdvancedModeChanged: (Boolean) -> Unit,
    onChangePin: () -> Unit,
    onChangeDuressPin: () -> Unit,
    onClearDuressPin: () -> Unit,
    onDeleteAll: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var confirmingAdvancedMode by remember { mutableStateOf(false) }
    val windowSizeClass = rememberMegaWindowSizeClass()

    MegaInfoScaffold(
        title = "Saved Session Settings",
        onBack = onBack,
        scrollable = windowSizeClass != MegaWindowSizeClass.Expanded,
    ) {
        if (windowSizeClass == MegaWindowSizeClass.Expanded) {
            val railScrollState = rememberScrollState()
            val railCoroutineScope = rememberCoroutineScope()
            val sectionOffsets = remember { mutableStateMapOf<Int, Int>() }
            Row(modifier = Modifier.fillMaxSize()) {
                MegaSectionNavRail(
                    sections = SETTINGS_SECTION_TITLES,
                    onSectionClicked = { index ->
                        val offset = sectionOffsets[index] ?: return@MegaSectionNavRail
                        railCoroutineScope.launch { railScrollState.animateScrollTo(offset) }
                    },
                    modifier = Modifier.padding(top = 16.dp, end = 8.dp),
                )
                VerticalDivider(color = MaterialTheme.colorScheme.outline)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(railScrollState)
                        .padding(start = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    fun sectionModifier(index: Int): Modifier = Modifier.onGloballyPositioned { coords ->
                        sectionOffsets[index] = coords.positionInParent().y.roundToInt()
                    }
                    SavedSessionSettingsBody(
                        pinButtonText = pinButtonText,
                        duressPinEnabled = duressPinEnabled,
                        selectedLockTimeoutMillis = selectedLockTimeoutMillis,
                        lockTimeoutOptions = lockTimeoutOptions,
                        onLockTimeoutSelected = onLockTimeoutSelected,
                        randomizePinKeypad = randomizePinKeypad,
                        onRandomizePinKeypadChanged = onRandomizePinKeypadChanged,
                        allowScreenshots = allowScreenshots,
                        onAllowScreenshotsChanged = onAllowScreenshotsChanged,
                        allowSeedCopy = allowSeedCopy,
                        onAllowSeedCopyChanged = onAllowSeedCopyChanged,
                        allowPrivateKeyExport = allowPrivateKeyExport,
                        onAllowPrivateKeyExportChanged = onAllowPrivateKeyExportChanged,
                        advancedModeEnabled = advancedModeEnabled,
                        onConfirmingAdvancedModeChanged = { confirmingAdvancedMode = it },
                        onAdvancedModeChanged = onAdvancedModeChanged,
                        onChangePin = onChangePin,
                        onChangeDuressPin = onChangeDuressPin,
                        onClearDuressPin = onClearDuressPin,
                        onDeleteAll = onDeleteAll,
                        sectionModifier = ::sectionModifier,
                    )
                }
            }
        } else {
            SavedSessionSettingsBody(
                pinButtonText = pinButtonText,
                duressPinEnabled = duressPinEnabled,
                selectedLockTimeoutMillis = selectedLockTimeoutMillis,
                lockTimeoutOptions = lockTimeoutOptions,
                onLockTimeoutSelected = onLockTimeoutSelected,
                randomizePinKeypad = randomizePinKeypad,
                onRandomizePinKeypadChanged = onRandomizePinKeypadChanged,
                allowScreenshots = allowScreenshots,
                onAllowScreenshotsChanged = onAllowScreenshotsChanged,
                allowSeedCopy = allowSeedCopy,
                onAllowSeedCopyChanged = onAllowSeedCopyChanged,
                allowPrivateKeyExport = allowPrivateKeyExport,
                onAllowPrivateKeyExportChanged = onAllowPrivateKeyExportChanged,
                advancedModeEnabled = advancedModeEnabled,
                onConfirmingAdvancedModeChanged = { confirmingAdvancedMode = it },
                onAdvancedModeChanged = onAdvancedModeChanged,
                onChangePin = onChangePin,
                onChangeDuressPin = onChangeDuressPin,
                onClearDuressPin = onClearDuressPin,
                onDeleteAll = onDeleteAll,
            )
        }
    }

    if (confirmingAdvancedMode) {
        AlertDialog(
            onDismissRequest = { confirmingAdvancedMode = false },
            title = { Text("Enable Advanced Mode?") },
            text = {
                Text(
                    "Advanced Mode is for advanced users. Entering an existing seed phrase " +
                        "or passphrase on any connected Android device can expose the funds it " +
                        "controls if the device is compromised — MEGA cannot guarantee safety on " +
                        "an internet-connected device. Prefer an offline GrapheneOS phone for " +
                        "sensitive seed workflows.\n\n" +
                        "A mistake in the seed words, passphrase, derivation path, script type, " +
                        "or account index produces a completely different wallet.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onAdvancedModeChanged(true)
                    confirmingAdvancedMode = false
                }) {
                    Text("I Understand, Enable", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingAdvancedMode = false }) { Text("Cancel") }
            },
        )
    }
}

/** Order matches the section index each MegaCard below is tagged with via
 * [SavedSessionSettingsBody]'s sectionModifier -- used both to label
 * MegaSectionNavRail's jump links and to record each section's scroll
 * offset for them to jump to. */
private val SETTINGS_SECTION_TITLES = listOf(
    "Auto-lock",
    "PIN",
    "Sensitive Display",
    "Duress PIN",
    "Advanced Mode",
    "Private Key Export",
    "Backup",
    "Danger Zone",
)

/** The actual settings content, shared between the single-pane
 * (Compact/Medium) and two-pane nav-rail (Expanded) layouts of
 * [SavedSessionSettingsScreen]. [sectionModifier] lets the Expanded-width
 * caller tag each MegaCard with a position-tracking modifier so
 * MegaSectionNavRail's jump links know where to scroll to; the default
 * (Modifier, unchanged) is what Compact/Medium uses. */
@Composable
private fun SavedSessionSettingsBody(
    pinButtonText: String,
    duressPinEnabled: Boolean,
    selectedLockTimeoutMillis: Long,
    lockTimeoutOptions: List<SavedSessionLockTimeoutOption>,
    onLockTimeoutSelected: (Long) -> Unit,
    randomizePinKeypad: Boolean,
    onRandomizePinKeypadChanged: (Boolean) -> Unit,
    allowScreenshots: Boolean,
    onAllowScreenshotsChanged: (Boolean) -> Unit,
    allowSeedCopy: Boolean,
    onAllowSeedCopyChanged: (Boolean) -> Unit,
    allowPrivateKeyExport: Boolean,
    onAllowPrivateKeyExportChanged: (Boolean) -> Unit,
    advancedModeEnabled: Boolean,
    onConfirmingAdvancedModeChanged: (Boolean) -> Unit,
    onAdvancedModeChanged: (Boolean) -> Unit,
    onChangePin: () -> Unit,
    onChangeDuressPin: () -> Unit,
    onClearDuressPin: () -> Unit,
    onDeleteAll: () -> Unit,
    sectionModifier: (Int) -> Modifier = { Modifier },
) {
    MegaCard(modifier = sectionModifier(0), title = "Auto-lock") {
        Text(
            "Require PIN again after MEGA leaves saved sessions.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        lockTimeoutOptions.forEach { option ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onLockTimeoutSelected(option.millis) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RadioButton(
                    selected = option.millis == selectedLockTimeoutMillis,
                    onClick = { onLockTimeoutSelected(option.millis) },
                )
                Text(option.label, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    MegaCard(modifier = sectionModifier(1), title = "PIN") {
        Text(
            "Required to view or delete saved sessions.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        MegaPrimaryButton(text = pinButtonText, onClick = onChangePin)

        Text("PIN pad layout", style = MaterialTheme.typography.titleSmall)
        KeypadLayoutOption(
            label = "Standard",
            description = "Digits stay in the familiar phone keypad order.",
            selected = !randomizePinKeypad,
            onClick = { onRandomizePinKeypadChanged(false) },
        )
        KeypadLayoutOption(
            label = "Randomized",
            description = "Digits shuffle for PIN entry to reduce shoulder-surfing risk.",
            selected = randomizePinKeypad,
            onClick = { onRandomizePinKeypadChanged(true) },
        )
    }

    MegaCard(modifier = sectionModifier(2), title = "Sensitive Display") {
        SettingSwitchRow(
            label = "Allow screenshots",
            description = "Permit Android screenshots and recent-app previews on MEGA sensitive screens.",
            checked = allowScreenshots,
            onCheckedChange = onAllowScreenshotsChanged,
        )
        SettingSwitchRow(
            label = "Allow seed word copy",
            description = "Show copy buttons for seed words and BIP85 child words after reveal.",
            checked = allowSeedCopy,
            onCheckedChange = onAllowSeedCopyChanged,
        )
    }

    MegaCard(modifier = sectionModifier(3), title = "Duress PIN") {
        Text(
            "Entering the duress PIN at a MEGA PIN prompt securely deletes all saved MEGA session data instead of unlocking.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        MegaPrimaryButton(
            text = if (duressPinEnabled) "Change Duress PIN" else "Set Duress PIN",
            onClick = onChangeDuressPin,
        )
        if (duressPinEnabled) {
            TextButton(onClick = onClearDuressPin, modifier = Modifier.fillMaxWidth()) {
                Text("Clear Duress PIN", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    MegaCard(modifier = sectionModifier(4), title = "Advanced Mode") {
        Text(
            "Manually enter an existing seed phrase to derive BIP85 children or wallet account keys. Off by default — MEGA's core purpose is generating a phrase from dice, not typing one in.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SettingSwitchRow(
            label = "Advanced Mode",
            description = "Adds a manual seed entry and wallet key derivation flow, reachable from the main screen.",
            checked = advancedModeEnabled,
            onCheckedChange = { enable ->
                if (enable) {
                    onConfirmingAdvancedModeChanged(true)
                } else {
                    onAdvancedModeChanged(false)
                }
            },
        )
    }

    MegaCard(modifier = sectionModifier(5), title = "Private Key Export") {
        Text(
            "Adds a button in Advanced Mode to generate a WIF private key for a " +
                "derived address. Unlike an xpub, a private key can spend whatever " +
                "funds are sent there — anyone who sees it can take them. Off by " +
                "default; each generation still requires its own confirmation.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        SettingSwitchRow(
            label = "Allow private key export",
            description = "Show the Generate Private Key (WIF) button in Advanced Mode.",
            checked = allowPrivateKeyExport,
            onCheckedChange = onAllowPrivateKeyExportChanged,
        )
    }

    Box(modifier = sectionModifier(6)) {
        BackupCard()
    }

    MegaCard(modifier = sectionModifier(7)) {
        Text(
            "Danger Zone",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.error,
        )
        Text(
            "Permanently erases every saved session and its encryption key. This cannot be undone.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        MegaSecondaryButton(text = "Secure Delete All MEGA Data", onClick = onDeleteAll)
    }
}

@Composable
private fun SettingSwitchRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        androidx.compose.foundation.layout.Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.weight(1f),
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun KeypadLayoutOption(
    label: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Shared between the single-pane (Compact/Medium) and two-pane
 * (Expanded) layouts of [SavedSessionsScreen] -- the PIN banner,
 * loading/empty states, tag filter, and session cards are identical
 * either way; only what "viewing" a session does (navigate away vs.
 * select it for the detail pane) differs, via [onView]. */
@Composable
private fun SavedSessionsListBody(
    state: SavedSessionsUiState,
    viewModel: SavedSessionsViewModel,
    onNewDiceSession: () -> Unit,
    onView: (String) -> Unit,
) {
    if (!state.isPinEnabled) {
        MegaCard(title = "MEGA PIN") {
            Text(
                "Not set yet",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Set a PIN in Settings before saving or retrieving MEGA data.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    when {
        state.isLoading -> {
            CircularProgressIndicator()
        }
        state.sessions.isEmpty() -> {
            Text(
                "No saved sessions. Sessions are only saved when you " +
                    "explicitly choose to save on the Save screen at the end " +
                    "of a dice-rolling flow.",
                style = MaterialTheme.typography.bodyMedium,
            )
            MegaPrimaryButton(text = "New Dice Session", onClick = onNewDiceSession)
        }
        else -> {
            if (state.allTags.isNotEmpty()) {
                TagFilterRow(
                    allTags = state.allTags,
                    selectedTags = state.selectedTagFilters,
                    onTagToggled = { viewModel.toggleTagFilter(it) },
                )
            }
            state.visibleSessions.forEach { session ->
                SavedSessionCard(
                    session = session,
                    onView = { onView(session.id) },
                    onRename = { newLabel -> viewModel.renameSession(session.id, newLabel) },
                    onTagsChanged = { tags -> viewModel.updateTags(session.id, tags) },
                    onDelete = { viewModel.deleteSession(session.id) },
                )
            }
        }
    }
}

@Composable
private fun SavedSessionCard(
    session: SavedSessionMetadata,
    onView: () -> Unit,
    onRename: (String) -> Unit,
    onTagsChanged: (List<String>) -> Unit,
    onDelete: () -> Unit,
) {
    var confirmingDelete by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var editingTags by remember { mutableStateOf(false) }
    val dateText = remember(session.createdAtEpochMillis) {
        DateFormat.getDateTimeInstance().format(Date(session.createdAtEpochMillis))
    }

    MegaCard {
        if (session.label.isNotBlank()) {
            Text(session.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(dateText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(dateText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        Text(
            (if (session.rollsCount > 0) "${session.rollsCount} rolls" else "Manually entered seed") +
                (if (session.hasMnemonic) " · mnemonic saved" else "") +
                (if (session.hasPassphraseCheck) " · passphrase check saved" else ""),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (session.childSeedInfo.isNotBlank()) {
            Text(
                session.childSeedInfo,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (session.tags.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                session.tags.forEach { tag ->
                    AssistChip(onClick = { editingTags = true }, label = { Text(tag) })
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier) {
            MegaNeutralButton(text = "View", modifier = Modifier.weight(1f), onClick = onView)
            MegaNeutralButton(text = "Label", modifier = Modifier.weight(1f), onClick = { renaming = true })
            MegaNeutralButton(text = "Tags", modifier = Modifier.weight(1f), onClick = { editingTags = true })
        }
        MegaPrimaryButton(text = "Secure Delete", onClick = { confirmingDelete = true })
    }

    if (renaming) {
        MegaLabelSessionDialog(
            initialLabel = session.label,
            onConfirm = { newLabel ->
                onRename(newLabel)
                renaming = false
            },
            onDismiss = { renaming = false },
        )
    }

    if (editingTags) {
        MegaLabelSessionDialog(
            title = "Edit Tags",
            helperText = "Comma-separated, e.g. cold storage, inheritance.",
            initialLabel = session.tags.joinToString(", "),
            onConfirm = { text ->
                onTagsChanged(parseTagsInput(text))
                editingTags = false
            },
            onDismiss = { editingTags = false },
        )
    }

    if (confirmingDelete) {
        ConfirmDeleteDialog(
            text = "This permanently deletes this session and its encryption key. This cannot be undone.",
            confirmText = "Secure Delete",
            onConfirm = {
                onDelete()
                confirmingDelete = false
            },
            onDismiss = { confirmingDelete = false },
        )
    }
}

@Composable
fun ConfirmDeleteDialog(
    text: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Are you sure?") },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmText, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/** Parses the comma-separated text MegaLabelSessionDialog's tag-editing
 * mode collects into a clean tag list: trimmed, blanks dropped, duplicates
 * collapsed — matches [SavedSessionMetadata]'s own tag validation (no
 * blank, no comma, no newline) so this never produces a value the
 * metadata's init block would reject. */
internal fun parseTagsInput(text: String): List<String> =
    text.split(",").map { it.trim() }.filter { it.isNotEmpty() }.distinct()

/** Tag filter row shown above the session list once at least one session
 * has a tag — "any of" selection, same semantics as
 * SavedSessionsUiState.visibleSessions. */
@Composable
private fun TagFilterRow(
    allTags: List<String>,
    selectedTags: Set<String>,
    onTagToggled: (String) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState()),
    ) {
        allTags.forEach { tag ->
            FilterChip(
                selected = tag in selectedTags,
                onClick = { onTagToggled(tag) },
                label = { Text(tag) },
            )
        }
    }
}
