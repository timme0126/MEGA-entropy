package org.mega.entropy.ui.navigation

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.launch
import org.mega.entropy.security.settings.SavedSessionSecuritySettings
import org.mega.entropy.security.pin.PinManager
import org.mega.entropy.storage.MultisigVaultRepository
import org.mega.entropy.storage.SavedMultisigVault
import org.mega.entropy.storage.SessionRepository
import org.mega.entropy.ui.about.AboutScreen
import org.mega.entropy.ui.advancedmode.AdvancedModeEntryScreen
import org.mega.entropy.ui.advancedmode.AdvancedModeHubScreen
import org.mega.entropy.ui.advancedmode.AdvancedModeImportPickerScreen
import org.mega.entropy.ui.advancedmode.AdvancedModeMnemonicEntryScreen
import org.mega.entropy.ui.advancedmode.AdvancedModeWalletScreen
import org.mega.entropy.ui.advancedmode.multisig.AdvancedModeMultisigDeriveCosignerScreen
import org.mega.entropy.ui.advancedmode.multisig.AdvancedModeMultisigScannerScreen
import org.mega.entropy.ui.advancedmode.multisig.AdvancedModeMultisigVaultScreen
import org.mega.entropy.ui.advancedmode.multisig.MultisigSetupStep
import org.mega.entropy.ui.advancedmode.multisig.MultisigVaultViewModel
import org.mega.entropy.ui.advancedmode.multisig.SavedMultisigVaultDetailScreen
import org.mega.entropy.ui.advancedmode.multisig.SavedMultisigVaultsScreen
import org.mega.entropy.ui.advancedmode.multisig.SavedMultisigVaultsViewModel
import org.mega.entropy.ui.advancedmode.multisig.toCosignerDisplayInfo
import org.mega.entropy.ui.advancedmode.multisig.toSavedCosigners
import org.mega.entropy.ui.biascheck.BiasCheckScreen
import org.mega.entropy.ui.bip85.Bip85Screen
import org.mega.entropy.ui.checksum.ChecksumScreen
import org.mega.entropy.ui.components.findActivity
import org.mega.entropy.ui.chooselength.ChooseLengthScreen
import org.mega.entropy.ui.diceentry.DiceEntryScreen
import org.mega.entropy.ui.diceentry.DiceSessionViewModel
import org.mega.entropy.ui.entropy.EntropyScreen
import org.mega.entropy.ui.howitworks.HowItWorksScreen
import org.mega.entropy.ui.loading.LoadingScreen
import org.mega.entropy.ui.mnemonic.FinalMnemonicScreen
import org.mega.entropy.ui.onboarding.BeforeYouBeginScreen
import org.mega.entropy.ui.pin.AppLockViewModel
import org.mega.entropy.ui.pin.PinSetupScreen
import org.mega.entropy.ui.pin.PinVerifyScreen
import org.mega.entropy.ui.privacy.PrivacyScreen
import org.mega.entropy.ui.savedsessiondetail.SavedSessionDetailScreen
import org.mega.entropy.ui.savedsessions.SavedSessionsScreen
import org.mega.entropy.ui.savesession.SaveSessionScreen
import org.mega.entropy.ui.security.SecurityModelScreen
import org.mega.entropy.ui.splitgroups.SplitGroupsScreen
import org.mega.entropy.ui.welcome.WelcomeScreen
import org.mega.entropy.ui.words.WordDerivationScreen
import org.mega.entropycore.MnemonicResult
import org.mega.entropycore.buildMultisigWallet

private data class PendingAdvancedModeSave(
    val words: List<String>,
    val label: String,
    val childSeedInfo: String = "",
)

@Composable
fun MegaNavGraph(navController: NavHostController = rememberNavController()) {
    // Both Activity-scoped (no back-stack-entry override), so they survive
    // navigation across the whole app. diceSessionViewModel needs this
    // because ChooseLengthScreen (which sets the chosen length) and
    // BeforeYouBeginScreen (which displays it) both sit outside the
    // "dice_flow" nested graph that the dice-entry screens share.
    val appLockViewModel: AppLockViewModel = viewModel()
    val diceSessionViewModel: DiceSessionViewModel = viewModel()
    val multisigVaultViewModel: MultisigVaultViewModel = viewModel()
    val context = LocalContext.current
    val pinManager = remember { PinManager(context.filesDir) }
    val repository = remember { SessionRepository(context) }
    val multisigVaultRepository = remember { MultisigVaultRepository(context) }
    val savedSessionSecuritySettings = remember { SavedSessionSecuritySettings(context) }
    var savedSessionLockTimeoutMillis by remember {
        mutableStateOf(savedSessionSecuritySettings.lockTimeoutMillis())
    }
    var randomizePinKeypad by remember {
        mutableStateOf(savedSessionSecuritySettings.randomizePinKeypad())
    }
    var allowScreenshots by remember {
        mutableStateOf(savedSessionSecuritySettings.allowScreenshots())
    }
    var allowSeedCopy by remember {
        mutableStateOf(savedSessionSecuritySettings.allowSeedCopy())
    }
    var allowPrivateKeyExport by remember {
        mutableStateOf(savedSessionSecuritySettings.allowPrivateKeyExport())
    }
    var advancedModeEnabled by remember {
        mutableStateOf(savedSessionSecuritySettings.advancedModeEnabled())
    }
    var diceRollsLockedDefault by remember {
        mutableStateOf(savedSessionSecuritySettings.diceRollsLockedByDefault())
    }
    var advancedModeWords by remember { mutableStateOf<List<String>?>(null) }
    var advancedModePassphrase by remember { mutableStateOf("") }
    // Null when advancedModeWords came from typing them in by hand
    // (AdvancedModeMnemonicEntryScreen.onValidated); non-null — possibly
    // "" — when they came from "Import from Saved Session", holding that
    // source session's own label. Used to hide AdvancedModeHubScreen's
    // save icon (they're already a saved session — saving again would
    // just duplicate it) and to describe a BIP85 child as "Child Seed of
    // <this label>" when saving one from Bip85Screen.
    var advancedModeSourceSessionLabel by remember { mutableStateOf<String?>(null) }
    // Only the passphrase needs its own slot here — ADVANCED_MODE_WALLET
    // reads advancedModeWords directly for the words themselves (the hub
    // is the only screen that navigates there, always with the same
    // words), and just needs a place to carry the passphrase typed on the
    // hub as that screen's own editable field's initial value.
    var advancedModeWalletPassphrase by remember { mutableStateOf("") }
    // Words (plus the source session's label) on their way from
    // ADVANCED_MODE_MULTISIG_COSIGNER_PICKER to
    // ADVANCED_MODE_MULTISIG_DERIVE_COSIGNER — a separate, narrowly-scoped
    // holder from advancedModeWords, which belongs to the OLDER "one seed
    // loaded into the Hub" flow this multisig vault setup doesn't use.
    var multisigCosignerSourceWords by remember { mutableStateOf<List<String>?>(null) }
    var multisigCosignerSourceLabel by remember { mutableStateOf("") }
    // Set right before navigating to ADVANCED_MODE_MULTISIG_SCANNER from the
    // Cosigners step's own top-bar camera icon (as opposed to a per-slot
    // camera icon) — read once by that route's own onScanned callback to
    // decide whether the scanned text should only ever be treated as a full
    // descriptor (see MultisigVaultViewModel.fillManySlotsFromScannedText)
    // or fall back to filling a single slot. Two separate composable()
    // blocks on the same NavHost can't share a locally remembered var, so
    // this lives here instead.
    var scanningFullDescriptor by remember { mutableStateOf(false) }
    // Set when AdvancedModeHubScreen's or Bip85Screen's save icon is used
    // but no MEGA PIN exists yet — the same "must have a PIN before
    // saving" redirect the dice flow's SAVE_SESSION uses, just carrying
    // words/label/childSeedInfo instead of a DiceSessionViewModel result.
    // PIN_SETUP performs the deferred save itself once a PIN is set (see
    // below) and pops back to whichever screen it came from.
    var pendingAdvancedModeSave by remember { mutableStateOf<PendingAdvancedModeSave?>(null) }
    var advancedModeSavedConfirmation by remember { mutableStateOf<String?>(null) }
    var stoppedAtElapsedRealtime by remember { mutableStateOf<Long?>(null) }
    // Timestamp of the last successful PIN entry for saved-session access.
    // The lock bit alone is not enough: it can stay false while the app is
    // foregrounded long past the configured timeout unless every gate checks
    // elapsed time before reusing it.
    var savedSessionUnlockedAtElapsedRealtime by remember { mutableStateOf<Long?>(null) }
    val lifecycleCoroutineScope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    // Shared by app backgrounding (ON_STOP/ON_START below) AND by simply
    // navigating back out of Saved Sessions to Welcome — both count as
    // "leaving saved sessions" for auto-lock purposes, so a quick trip to
    // Welcome and straight back in doesn't force a fresh PIN entry unless
    // the configured timeout actually elapsed (or is set to "Immediately").
    fun lockSavedSessionAccess() {
        appLockViewModel.lock()
        savedSessionUnlockedAtElapsedRealtime = null
    }

    fun unlockSavedSessionAccess() {
        appLockViewModel.unlock()
        savedSessionUnlockedAtElapsedRealtime = SystemClock.elapsedRealtime()
    }

    suspend fun savedSessionUnlockStillValid(): Boolean {
        if (appLockViewModel.isLocked.value) return false
        if (!pinManager.isPinEnabled()) return true
        if (savedSessionLockTimeoutMillis == 0L) {
            lockSavedSessionAccess()
            return false
        }
        val unlockedAt = savedSessionUnlockedAtElapsedRealtime ?: run {
            lockSavedSessionAccess()
            return false
        }
        val stillValid = SystemClock.elapsedRealtime() - unlockedAt < savedSessionLockTimeoutMillis
        if (!stillValid) lockSavedSessionAccess()
        return stillValid
    }

    suspend fun armSavedSessionLock() {
        if (!pinManager.isPinEnabled()) return
        if (savedSessionLockTimeoutMillis == 0L) {
            lockSavedSessionAccess()
        } else {
            stoppedAtElapsedRealtime = SystemClock.elapsedRealtime()
        }
    }
    suspend fun consumeSavedSessionLockElapsed() {
        val stoppedAt = stoppedAtElapsedRealtime
        if (
            stoppedAt != null &&
            pinManager.isPinEnabled() &&
            SystemClock.elapsedRealtime() - stoppedAt >= savedSessionLockTimeoutMillis
        ) {
            lockSavedSessionAccess()
        }
        stoppedAtElapsedRealtime = null
    }

    DisposableEffect(lifecycleOwner, savedSessionLockTimeoutMillis) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> lifecycleCoroutineScope.launch { armSavedSessionLock() }
                Lifecycle.Event.ON_START -> lifecycleCoroutineScope.launch { consumeSavedSessionLockElapsed() }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Where PIN_ENTRY lands once unlocked — normally the Saved Sessions
    // screen itself, but Advanced Mode's "Import from Saved Session"
    // reuses the exact same PIN gate (it reads the same encrypted vault)
    // and just needs to land somewhere else afterward.
    var savedSessionsEntryTarget by remember { mutableStateOf(MegaDestinations.SAVED_SESSIONS) }

    suspend fun enterSavedSessionsGate(target: String) {
        savedSessionsEntryTarget = target
        val hasSavedSessions = repository.listSessions().isNotEmpty()
        if (hasSavedSessions && pinManager.isPinEnabled()) {
            // A PIN is required whenever saved data exists. Reuse a prior
            // unlock only if it is still inside the configured timeout. This
            // must be checked before trusting the app-wide lock bit; otherwise
            // a foregrounded app can remain unlocked past the timeout and a
            // later cross-flow import (Advanced Mode, multisig cosigner picker)
            // would skip the PIN indefinitely.
            consumeSavedSessionLockElapsed()
            if (savedSessionUnlockStillValid()) {
                navController.navigate(target)
            } else {
                navController.navigate(MegaDestinations.PIN_ENTRY)
            }
        } else {
            appLockViewModel.unlock()
            navController.navigate(target)
        }
    }

    // "Multi-Signature Vaults" from Advanced Mode's entry page — a saved
    // vault contains only public key material (see SavedMultisigVault's
    // doc comment), so unlike enterSavedSessionsGate above, this never
    // touches the PIN gate at all.
    suspend fun enterMultisigVaultsEntry() {
        val hasSavedVaults = multisigVaultRepository.listVaults().isNotEmpty()
        if (hasSavedVaults) {
            navController.navigate(MegaDestinations.ADVANCED_MODE_SAVED_MULTISIG_VAULTS)
        } else {
            multisigVaultViewModel.resetSession()
            navController.navigate(MegaDestinations.ADVANCED_MODE_MULTISIG_VAULT)
        }
    }

    // Saving any data requires a MEGA PIN to already exist, same rule the
    // dice flow's Save screen enforces — see saveOrRequirePin in diceFlow.
    suspend fun saveAdvancedModeSession(words: List<String>, label: String, childSeedInfo: String = "") {
        if (pinManager.isPinEnabled()) {
            repository.saveSession(mnemonicWords = words, label = label, childSeedInfo = childSeedInfo)
            advancedModeSavedConfirmation = label
        } else {
            pendingAdvancedModeSave = PendingAdvancedModeSave(words, label, childSeedInfo)
            navController.navigate(MegaDestinations.PIN_SETUP)
        }
    }

    NavHost(navController = navController, startDestination = MegaDestinations.LOADING) {
        composable(MegaDestinations.LOADING) {
            LoadingScreen(
                onEnter = {
                    navController.navigate(MegaDestinations.WELCOME) {
                        popUpTo(MegaDestinations.LOADING) { inclusive = true }
                    }
                },
            )
        }
        composable(MegaDestinations.WELCOME) {
            val coroutineScope = rememberCoroutineScope()
            WelcomeScreen(
                onNewDiceSession = { navController.navigate(MegaDestinations.CHOOSE_LENGTH) },
                onSavedSessions = {
                    coroutineScope.launch { enterSavedSessionsGate(MegaDestinations.SAVED_SESSIONS) }
                },
                onHowItWorks = { navController.navigate(MegaDestinations.HOW_IT_WORKS) },
                onSecurityModel = { navController.navigate(MegaDestinations.SECURITY_MODEL) },
                onAbout = { navController.navigate(MegaDestinations.ABOUT) },
                onExitApp = {
                    context.findActivity()?.finishAndRemoveTask()
                },
                advancedModeEnabled = advancedModeEnabled,
                onAdvancedMode = {
                    advancedModeWords = null
                    advancedModePassphrase = ""
                    navController.navigate(MegaDestinations.ADVANCED_MODE_ENTRY)
                },
            )
        }
        composable(MegaDestinations.CHOOSE_LENGTH) {
            ChooseLengthScreen(
                onBack = { navController.popBackStack() },
                onLengthChosen = { length ->
                    diceSessionViewModel.selectLength(length)
                    navController.navigate(MegaDestinations.BEFORE_YOU_BEGIN)
                },
            )
        }
        composable(MegaDestinations.PIN_ENTRY) {
            PinVerifyScreen(
                onUnlocked = {
                    unlockSavedSessionAccess()
                    navController.navigate(savedSessionsEntryTarget) {
                        popUpTo(MegaDestinations.PIN_ENTRY) { inclusive = true }
                    }
                },
                onCancel = { navController.popBackStack() },
                randomizeKeypad = randomizePinKeypad,
                onDuressWipe = {
                    lockSavedSessionAccess()
                    navController.popBackStack(MegaDestinations.WELCOME, inclusive = false)
                },
            )
        }
        composable(MegaDestinations.PIN_SETUP) {
            val state by diceSessionViewModel.uiState.collectAsState()
            val coroutineScope = rememberCoroutineScope()
            val context = LocalContext.current
            val repository = remember { SessionRepository(context) }
            val pendingSaveWithMnemonic = state.pendingSaveWithMnemonic

            PinSetupScreen(
                onPinSet = {
                    appLockViewModel.unlock()
                    when {
                        pendingSaveWithMnemonic != null -> {
                            // Reached via a forced "you must set a PIN before
                            // saving" redirect (see SAVE_SESSION below) — finish
                            // the save that was waiting on this, then leave the
                            // whole dice flow, exactly like a normal save does.
                            coroutineScope.launch {
                                val success = state.mnemonicResult as? MnemonicResult.Success
                                val mnemonicWords = if (pendingSaveWithMnemonic) success?.words else null
                                repository.saveSession(
                                    diceRolls = state.allRolls,
                                    mnemonicWords = mnemonicWords,
                                    label = state.pendingSaveLabel,
                                )
                                diceSessionViewModel.clearPendingSave()
                                diceSessionViewModel.resetSession()
                                navController.popBackStack(MegaDestinations.WELCOME, inclusive = false)
                            }
                        }
                        pendingAdvancedModeSave != null -> {
                            // Reached via AdvancedModeHubScreen's or
                            // Bip85Screen's save icon — finish the deferred
                            // save, then return to that same screen (still
                            // underneath on the back stack) instead of
                            // leaving Advanced Mode.
                            val pending = pendingAdvancedModeSave!!
                            coroutineScope.launch {
                                repository.saveSession(
                                    mnemonicWords = pending.words,
                                    label = pending.label,
                                    childSeedInfo = pending.childSeedInfo,
                                )
                                pendingAdvancedModeSave = null
                                advancedModeSavedConfirmation = pending.label
                                navController.popBackStack()
                            }
                        }
                        else -> {
                            // Reached via "Change PIN" from Saved Sessions.
                            navController.popBackStack()
                        }
                    }
                },
                onCancel = {
                    diceSessionViewModel.clearPendingSave()
                    pendingAdvancedModeSave = null
                    navController.popBackStack()
                },
            )
        }
        composable(MegaDestinations.PIN_DURESS_SETUP) {
            val context = LocalContext.current
            val pinManagerForDuress = remember { PinManager(context.filesDir) }
            PinSetupScreen(
                title = "Choose a Duress PIN",
                confirmTitle = "Confirm Duress PIN",
                subtitle = "5 to 8 digits. Must differ from your normal MEGA PIN.",
                onSavePin = { pin -> pinManagerForDuress.setDuressPin(pin) },
                onPinSet = { navController.popBackStack() },
                onCancel = { navController.popBackStack() },
            )
        }
        composable(MegaDestinations.BEFORE_YOU_BEGIN) {
            val state by diceSessionViewModel.uiState.collectAsState()
            BeforeYouBeginScreen(
                mnemonicLength = state.mnemonicLength,
                onBack = { navController.popBackStack() },
                onStartRolling = { navController.navigate(MegaDestinations.DICE_FLOW) },
            )
        }
        composable(MegaDestinations.HOW_IT_WORKS) {
            HowItWorksScreen(onBack = { navController.popBackStack() })
        }
        composable(MegaDestinations.SECURITY_MODEL) {
            SecurityModelScreen(onBack = { navController.popBackStack() })
        }
        composable(MegaDestinations.PRIVACY) {
            PrivacyScreen(onBack = { navController.popBackStack() })
        }
        composable(MegaDestinations.ABOUT) {
            AboutScreen(
                onBack = { navController.popBackStack() },
                onPrivacy = { navController.navigate(MegaDestinations.PRIVACY) },
            )
        }
        composable(MegaDestinations.SAVED_SESSIONS) {
            LockGuard(appLockViewModel, navController)
            val coroutineScope = rememberCoroutineScope()
            fun leaveSavedSessions() {
                coroutineScope.launch { armSavedSessionLock() }
                navController.popBackStack()
            }
            BackHandler { leaveSavedSessions() }
            SavedSessionsScreen(
                onBack = { leaveSavedSessions() },
                onChangePin = {
                    coroutineScope.launch {
                        // Changing the PIN must always re-prove knowledge of the
                        // current one first — being inside Saved Sessions only
                        // means the PIN was verified at some earlier point, not
                        // right now. Skip straight to setup only when there's no
                        // existing PIN to verify against yet.
                        if (pinManager.isPinEnabled()) {
                            navController.navigate(MegaDestinations.PIN_CHANGE_VERIFY)
                        } else {
                            navController.navigate(MegaDestinations.PIN_SETUP)
                        }
                    }
                },
                onViewSession = { sessionId ->
                    navController.navigate(MegaDestinations.savedSessionDetailRoute(sessionId))
                },
                onNewDiceSession = {
                    coroutineScope.launch { armSavedSessionLock() }
                    navController.navigate(MegaDestinations.CHOOSE_LENGTH)
                },
                onChangeDuressPin = { navController.navigate(MegaDestinations.PIN_DURESS_SETUP) },
                selectedLockTimeoutMillis = savedSessionLockTimeoutMillis,
                lockTimeoutOptions = SavedSessionSecuritySettings.LOCK_TIMEOUT_OPTIONS,
                onLockTimeoutSelected = { millis ->
                    savedSessionSecuritySettings.setLockTimeoutMillis(millis)
                    savedSessionLockTimeoutMillis = savedSessionSecuritySettings.lockTimeoutMillis()
                },
                randomizePinKeypad = randomizePinKeypad,
                onRandomizePinKeypadChanged = { randomize ->
                    savedSessionSecuritySettings.setRandomizePinKeypad(randomize)
                    randomizePinKeypad = savedSessionSecuritySettings.randomizePinKeypad()
                },
                allowScreenshots = allowScreenshots,
                onAllowScreenshotsChanged = { allow ->
                    savedSessionSecuritySettings.setAllowScreenshots(allow)
                    allowScreenshots = savedSessionSecuritySettings.allowScreenshots()
                },
                allowSeedCopy = allowSeedCopy,
                onAllowSeedCopyChanged = { allow ->
                    savedSessionSecuritySettings.setAllowSeedCopy(allow)
                    allowSeedCopy = savedSessionSecuritySettings.allowSeedCopy()
                },
                allowPrivateKeyExport = allowPrivateKeyExport,
                onAllowPrivateKeyExportChanged = { allow ->
                    savedSessionSecuritySettings.setAllowPrivateKeyExport(allow)
                    allowPrivateKeyExport = savedSessionSecuritySettings.allowPrivateKeyExport()
                },
                advancedModeEnabled = advancedModeEnabled,
                onAdvancedModeChanged = { enabled ->
                    savedSessionSecuritySettings.setAdvancedModeEnabled(enabled)
                    advancedModeEnabled = savedSessionSecuritySettings.advancedModeEnabled()
                },
            )
        }
        composable(MegaDestinations.SAVED_SESSION_UNLOCK) {
            PinVerifyScreen(
                onUnlocked = {
                    unlockSavedSessionAccess()
                    navController.popBackStack()
                },
                onCancel = {
                    navController.popBackStack(MegaDestinations.WELCOME, inclusive = false)
                },
                randomizeKeypad = randomizePinKeypad,
                onDuressWipe = {
                    lockSavedSessionAccess()
                    navController.popBackStack(MegaDestinations.WELCOME, inclusive = false)
                },
            )
        }
        composable(MegaDestinations.PIN_CHANGE_VERIFY) {
            PinVerifyScreen(
                onUnlocked = {
                    navController.navigate(MegaDestinations.PIN_SETUP) {
                        popUpTo(MegaDestinations.PIN_CHANGE_VERIFY) { inclusive = true }
                    }
                },
                onCancel = { navController.popBackStack() },
                randomizeKeypad = randomizePinKeypad,
                onDuressWipe = {
                    lockSavedSessionAccess()
                    navController.popBackStack(MegaDestinations.WELCOME, inclusive = false)
                },
            )
        }
        composable(
            route = MegaDestinations.SAVED_SESSION_DETAIL,
            arguments = listOf(navArgument(MegaDestinations.SAVED_SESSION_DETAIL_ARG) { type = NavType.StringType }),
        ) { entry ->
            LockGuard(appLockViewModel, navController)
            val sessionId = entry.arguments?.getString(MegaDestinations.SAVED_SESSION_DETAIL_ARG).orEmpty()
            SavedSessionDetailScreen(
                sessionId = sessionId,
                allowScreenshots = allowScreenshots,
                allowSeedCopy = allowSeedCopy,
                diceRollsLockedDefault = diceRollsLockedDefault,
                onDiceRollsLockedDefaultChanged = { locked ->
                    savedSessionSecuritySettings.setDiceRollsLockedByDefault(locked)
                    diceRollsLockedDefault = savedSessionSecuritySettings.diceRollsLockedByDefault()
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable(MegaDestinations.ADVANCED_MODE_ENTRY) {
            val coroutineScope = rememberCoroutineScope()
            fun leaveAdvancedMode() {
                advancedModeWords = null
                advancedModePassphrase = ""
                advancedModeSourceSessionLabel = null
                advancedModeSavedConfirmation = null
                navController.popBackStack()
            }
            BackHandler { leaveAdvancedMode() }
            AdvancedModeEntryScreen(
                allowScreenshots = allowScreenshots,
                onBack = { leaveAdvancedMode() },
                onManualEntry = {
                    navController.navigate(MegaDestinations.ADVANCED_MODE_MANUAL_ENTRY)
                },
                onImportFromSavedSession = {
                    coroutineScope.launch { enterSavedSessionsGate(MegaDestinations.ADVANCED_MODE_IMPORT_PICKER) }
                },
                onMultisigVaults = {
                    coroutineScope.launch { enterMultisigVaultsEntry() }
                },
            )
        }
        composable(MegaDestinations.ADVANCED_MODE_MANUAL_ENTRY) {
            AdvancedModeMnemonicEntryScreen(
                allowScreenshots = allowScreenshots,
                onBack = { navController.popBackStack() },
                onValidated = { words ->
                    advancedModeWords = words
                    advancedModePassphrase = ""
                    advancedModeSourceSessionLabel = null
                    navController.navigate(MegaDestinations.ADVANCED_MODE_HUB)
                },
            )
        }
        composable(MegaDestinations.ADVANCED_MODE_HUB) {
            val words = advancedModeWords
            if (words != null) {
                val coroutineScope = rememberCoroutineScope()
                // Back always lands on the Advanced Mode landing page, not
                // back into whichever sub-form loaded these words (Manual
                // Entry or Import Picker) — landing there would just force
                // re-typing or re-picking the same words. These words are
                // done being used at this point, so clear them the same as
                // leaving Advanced Mode entirely, rather than leaving them
                // sitting in memory once Hub is left behind.
                fun onHubBack() {
                    advancedModeWords = null
                    advancedModePassphrase = ""
                    advancedModeSourceSessionLabel = null
                    advancedModeSavedConfirmation = null
                    if (!navController.popBackStack(MegaDestinations.ADVANCED_MODE_ENTRY, inclusive = false)) {
                        navController.popBackStack()
                    }
                }
                AdvancedModeHubScreen(
                    mnemonicWords = words,
                    allowScreenshots = allowScreenshots,
                    allowSeedCopy = allowSeedCopy,
                    isExistingSavedSession = advancedModeSourceSessionLabel != null,
                    onBack = { onHubBack() },
                    onBip85 = { passphrase ->
                        advancedModePassphrase = passphrase
                        navController.navigate(MegaDestinations.ADVANCED_MODE_BIP85)
                    },
                    onWalletKeys = { passphrase ->
                        advancedModeWalletPassphrase = passphrase
                        navController.navigate(MegaDestinations.ADVANCED_MODE_WALLET)
                    },
                    onSaveAsSession = { label ->
                        coroutineScope.launch { saveAdvancedModeSession(words, label) }
                    },
                    savedConfirmationLabel = advancedModeSavedConfirmation,
                    onSavedConfirmationDismissed = { advancedModeSavedConfirmation = null },
                )
            } else {
                LaunchedEffect(Unit) {
                    navController.popBackStack()
                }
            }
        }
        composable(MegaDestinations.ADVANCED_MODE_BIP85) {
            val words = advancedModeWords
            if (words != null) {
                val coroutineScope = rememberCoroutineScope()
                Bip85Screen(
                    parentWords = words,
                    parentPassphrase = advancedModePassphrase,
                    parentLabel = advancedModeSourceSessionLabel,
                    allowScreenshots = allowScreenshots,
                    allowSeedCopy = allowSeedCopy,
                    onBack = { navController.popBackStack() },
                    onSaveChildAsSession = { childWords, label, childSeedInfo ->
                        coroutineScope.launch { saveAdvancedModeSession(childWords, label, childSeedInfo) }
                    },
                    savedConfirmationLabel = advancedModeSavedConfirmation,
                    onSavedConfirmationDismissed = { advancedModeSavedConfirmation = null },
                )
            } else {
                LaunchedEffect(Unit) {
                    navController.popBackStack()
                }
            }
        }
        composable(MegaDestinations.ADVANCED_MODE_WALLET) {
            val words = advancedModeWords
            DisposableEffect(Unit) {
                onDispose {
                    advancedModeWalletPassphrase = ""
                }
            }
            if (words != null) {
                BackHandler { navController.popBackStack() }
                AdvancedModeWalletScreen(
                    mnemonicWords = words,
                    passphrase = advancedModeWalletPassphrase,
                    allowScreenshots = allowScreenshots,
                    allowSeedCopy = allowSeedCopy,
                    allowPrivateKeyExport = allowPrivateKeyExport,
                    onBack = { navController.popBackStack() },
                )
            } else {
                LaunchedEffect(Unit) {
                    navController.popBackStack()
                }
            }
        }
        composable(MegaDestinations.ADVANCED_MODE_MULTISIG_VAULT) {
            val uiState by multisigVaultViewModel.uiState.collectAsState()
            val coroutineScope = rememberCoroutineScope()
            // Each step backs out to the PREVIOUS step of the same flow
            // rather than leaving it outright, except from Policy (the
            // first step), which leaves the whole flow and resets it —
            // same "back steps within a flow before it steps out of it"
            // shape as the dice flow's own screens.
            fun onVaultBack() {
                when (uiState.step) {
                    MultisigSetupStep.POLICY -> {
                        multisigVaultViewModel.resetSession()
                        navController.popBackStack()
                    }
                    MultisigSetupStep.SLOTS -> multisigVaultViewModel.backToPolicy()
                    MultisigSetupStep.RESULT -> multisigVaultViewModel.backToSlots()
                }
            }
            BackHandler { onVaultBack() }
            AdvancedModeMultisigVaultScreen(
                uiState = uiState,
                allowScreenshots = allowScreenshots,
                allowSeedCopy = allowSeedCopy,
                onBack = { onVaultBack() },
                onSetN = multisigVaultViewModel::setN,
                onSetM = multisigVaultViewModel::setM,
                onSetNetwork = multisigVaultViewModel::setNetwork,
                onSetScriptType = multisigVaultViewModel::setScriptType,
                onConfirmPolicy = multisigVaultViewModel::confirmPolicy,
                onBackToPolicy = multisigVaultViewModel::backToPolicy,
                onBeginFillSlot = { index ->
                    multisigVaultViewModel.beginFillSlot(index)
                    coroutineScope.launch {
                        enterSavedSessionsGate(MegaDestinations.ADVANCED_MODE_MULTISIG_COSIGNER_PICKER)
                    }
                },
                onScanSlot = { index ->
                    multisigVaultViewModel.beginFillSlot(index)
                    scanningFullDescriptor = false
                    navController.navigate(MegaDestinations.ADVANCED_MODE_MULTISIG_SCANNER)
                },
                onScanFullDescriptor = {
                    scanningFullDescriptor = true
                    navController.navigate(MegaDestinations.ADVANCED_MODE_MULTISIG_SCANNER)
                },
                onPasteIntoSlot = multisigVaultViewModel::fillSlotFromPastedText,
                onPasteFullDescriptor = multisigVaultViewModel::fillManySlotsFromDescriptor,
                onClearSlot = multisigVaultViewModel::clearSlot,
                onCompleteBareXpubCosigner = multisigVaultViewModel::completeBareXpubCosigner,
                onCancelBareXpubHelper = multisigVaultViewModel::cancelBareXpubHelper,
                onBuildVault = multisigVaultViewModel::buildVault,
                onBackToSlots = multisigVaultViewModel::backToSlots,
                onBeginSaveVault = multisigVaultViewModel::beginSaveVault,
                onCancelSaveVault = multisigVaultViewModel::cancelSaveVault,
                onConfirmSaveVault = { label ->
                    val wallet = uiState.walletResult
                    if (wallet != null) {
                        coroutineScope.launch {
                            multisigVaultRepository.saveVault(
                                threshold = wallet.threshold,
                                network = wallet.network,
                                scriptType = uiState.scriptType,
                                cosigners = uiState.toSavedCosigners(),
                                label = label,
                            )
                            multisigVaultViewModel.onVaultSaved(label)
                        }
                    }
                },
                onDismissSavedVaultConfirmation = multisigVaultViewModel::dismissSavedVaultConfirmation,
                onConfirmDescriptorImport = multisigVaultViewModel::confirmDescriptorImport,
                onCancelDescriptorImport = multisigVaultViewModel::cancelDescriptorImport,
            )
        }
        composable(MegaDestinations.ADVANCED_MODE_MULTISIG_COSIGNER_PICKER) {
            LockGuard(appLockViewModel, navController)
            AdvancedModeImportPickerScreen(
                allowScreenshots = allowScreenshots,
                onBack = { navController.popBackStack() },
                onImported = { words, sourceLabel ->
                    multisigCosignerSourceWords = words
                    multisigCosignerSourceLabel = sourceLabel
                    navController.navigate(MegaDestinations.ADVANCED_MODE_MULTISIG_DERIVE_COSIGNER)
                },
            )
        }
        composable(MegaDestinations.ADVANCED_MODE_MULTISIG_DERIVE_COSIGNER) {
            val words = multisigCosignerSourceWords
            val uiState by multisigVaultViewModel.uiState.collectAsState()
            // Words (and the label alongside them) only exist to get from
            // the picker to a derived, PUBLIC cosigner key — cleared the
            // moment this screen is left, success or not, the same
            // "don't outlive the screen that needed it" lifetime every
            // other passphrase/word holder in Advanced Mode already
            // follows (see multisigCosignerSourceWords above).
            DisposableEffect(Unit) {
                onDispose {
                    multisigCosignerSourceWords = null
                    multisigCosignerSourceLabel = ""
                }
            }
            if (words != null) {
                BackHandler { navController.popBackStack() }
                AdvancedModeMultisigDeriveCosignerScreen(
                    mnemonicWords = words,
                    sourceLabel = multisigCosignerSourceLabel,
                    network = uiState.network,
                    scriptType = uiState.scriptType,
                    allowScreenshots = allowScreenshots,
                    onBack = { navController.popBackStack() },
                    onDerived = { origin, label, passphraseUsed ->
                        multisigVaultViewModel.fillPendingSlot(origin, label, passphraseUsed)
                        navController.popBackStack(MegaDestinations.ADVANCED_MODE_MULTISIG_VAULT, inclusive = false)
                    },
                )
            } else {
                LaunchedEffect(Unit) {
                    navController.popBackStack()
                }
            }
        }
        composable(MegaDestinations.ADVANCED_MODE_MULTISIG_SCANNER) {
            AdvancedModeMultisigScannerScreen(
                allowScreenshots = allowScreenshots,
                onBack = {
                    multisigVaultViewModel.cancelFillSlot()
                    scanningFullDescriptor = false
                    navController.popBackStack()
                },
                onScanned = { scannedText ->
                    if (scanningFullDescriptor) {
                        multisigVaultViewModel.fillManySlotsFromScannedText(scannedText)
                    } else {
                        multisigVaultViewModel.fillPendingSlotFromScannedText(scannedText)
                    }
                    scanningFullDescriptor = false
                    navController.popBackStack(MegaDestinations.ADVANCED_MODE_MULTISIG_VAULT, inclusive = false)
                },
            )
        }
        composable(MegaDestinations.ADVANCED_MODE_SAVED_MULTISIG_VAULTS) {
            val viewModel: SavedMultisigVaultsViewModel = viewModel()
            // Re-fetches every time this destination is (re)composed, including
            // returning here via back-navigation after saving a new vault —
            // viewModel()'s own init{} only runs once across the ViewModel's
            // whole lifetime, which would otherwise leave the list stale.
            LaunchedEffect(Unit) { viewModel.refresh() }
            val state by viewModel.uiState.collectAsState()
            SavedMultisigVaultsScreen(
                vaults = state.vaults,
                isLoading = state.isLoading,
                allowScreenshots = allowScreenshots,
                onBack = { navController.popBackStack() },
                onViewVault = { id -> navController.navigate(MegaDestinations.savedMultisigVaultDetailRoute(id)) },
                onRenameVault = viewModel::renameVault,
                onDeleteVault = viewModel::deleteVault,
                onCreateNewVault = {
                    multisigVaultViewModel.resetSession()
                    navController.navigate(MegaDestinations.ADVANCED_MODE_MULTISIG_VAULT)
                },
            )
        }
        composable(
            route = MegaDestinations.SAVED_MULTISIG_VAULT_DETAIL,
            arguments = listOf(navArgument(MegaDestinations.SAVED_MULTISIG_VAULT_DETAIL_ARG) { type = NavType.StringType }),
        ) { entry ->
            val vaultId = entry.arguments?.getString(MegaDestinations.SAVED_MULTISIG_VAULT_DETAIL_ARG).orEmpty()
            val coroutineScope = rememberCoroutineScope()
            var vault by remember { mutableStateOf<SavedMultisigVault?>(null) }
            var loadFailed by remember { mutableStateOf(false) }
            LaunchedEffect(vaultId) {
                vault = try {
                    multisigVaultRepository.loadVault(vaultId)
                } catch (e: Exception) {
                    loadFailed = true
                    null
                }
            }
            val currentVault = vault
            if (currentVault != null) {
                val wallet = remember(currentVault) {
                    runCatching {
                        buildMultisigWallet(
                            currentVault.threshold,
                            currentVault.cosigners.map { it.toOrigin() },
                            currentVault.network,
                        )
                    }.getOrNull()
                }
                if (wallet != null) {
                    SavedMultisigVaultDetailScreen(
                        label = currentVault.label,
                        wallet = wallet,
                        cosigners = currentVault.cosigners.map { it.toCosignerDisplayInfo() },
                        allowScreenshots = allowScreenshots,
                        allowSeedCopy = allowSeedCopy,
                        onBack = { navController.popBackStack() },
                        onRename = { newLabel ->
                            coroutineScope.launch {
                                multisigVaultRepository.renameVault(vaultId, newLabel)
                                vault = currentVault.copy(label = newLabel)
                            }
                        },
                        onDelete = {
                            coroutineScope.launch {
                                multisigVaultRepository.deleteVault(vaultId)
                                navController.popBackStack()
                            }
                        },
                    )
                } else {
                    LaunchedEffect(Unit) { navController.popBackStack() }
                }
            } else if (loadFailed) {
                LaunchedEffect(Unit) { navController.popBackStack() }
            }
        }
        composable(MegaDestinations.ADVANCED_MODE_IMPORT_PICKER) {
            LockGuard(appLockViewModel, navController)
            AdvancedModeImportPickerScreen(
                allowScreenshots = allowScreenshots,
                onBack = { navController.popBackStack() },
                onImported = { words, sourceLabel ->
                    advancedModeWords = words
                    advancedModePassphrase = ""
                    advancedModeSourceSessionLabel = sourceLabel
                    navController.navigate(MegaDestinations.ADVANCED_MODE_HUB)
                },
            )
        }
        diceFlow(
            navController = navController,
            sharedViewModel = diceSessionViewModel,
            pinManager = pinManager,
            allowScreenshots = allowScreenshots,
            allowSeedCopy = allowSeedCopy,
        )
    }
}

/**
 * The dice-entry-through-final-mnemonic screens all read from the same
 * Activity-scoped DiceSessionViewModel (passed in explicitly, since this is
 * a separate function from MegaNavGraph's body and can't see its local
 * vals). Popping the whole "dice_flow" graph off the back stack (e.g.
 * returning to Welcome) does NOT clear it automatically the way scoping it
 * to the graph's own back stack entry used to — screens that leave the
 * flow (BiasCheck's "start new sequence", SaveSession's "done") call
 * resetSession() explicitly instead. This trade-off is what lets
 * ChooseLengthScreen and BeforeYouBeginScreen, which sit outside this
 * nested graph, read and set the chosen MnemonicLength on the same
 * instance.
 */
private fun androidx.navigation.NavGraphBuilder.diceFlow(
    navController: NavHostController,
    sharedViewModel: DiceSessionViewModel,
    pinManager: PinManager,
    allowScreenshots: Boolean,
    allowSeedCopy: Boolean,
) {
    navigation(startDestination = MegaDestinations.DICE_ENTRY, route = MegaDestinations.DICE_FLOW) {
        composable(MegaDestinations.DICE_ENTRY) {
            DiceEntryScreen(
                viewModel = sharedViewModel,
                onSessionComplete = { navController.navigate(MegaDestinations.BIAS_CHECK) },
                // Leaving the flow from its very first screen must reset the
                // same as every other deliberate exit back to Welcome (see
                // SAVE_SESSION below, and BiasCheckScreen's
                // onStartNewSequence) — otherwise whatever rolls were
                // entered before backing out would sit in the shared,
                // Activity-scoped DiceSessionViewModel until the next
                // selectLength() call happens to overwrite them.
                onBack = {
                    sharedViewModel.resetSession()
                    navController.popBackStack(MegaDestinations.WELCOME, inclusive = false)
                },
            )
        }
        composable(MegaDestinations.BIAS_CHECK) {
            val state by sharedViewModel.uiState.collectAsState()
            BiasCheckScreen(
                mnemonicLength = state.mnemonicLength,
                rejectionResult = state.rejectionResult,
                onContinueToEntropy = { navController.navigate(MegaDestinations.ENTROPY_256) },
                onStartNewSequence = {
                    sharedViewModel.resetSession()
                    navController.popBackStack(MegaDestinations.DICE_ENTRY, inclusive = false)
                },
            )
        }
        composable(MegaDestinations.ENTROPY_256) {
            val state by sharedViewModel.uiState.collectAsState()
            val success = state.mnemonicResult as? MnemonicResult.Success
            if (success != null) {
                EntropyScreen(
                    entropy = success.entropy,
                    onContinue = { navController.navigate(MegaDestinations.CHECKSUM) },
                )
            }
        }
        composable(MegaDestinations.CHECKSUM) {
            val state by sharedViewModel.uiState.collectAsState()
            val success = state.mnemonicResult as? MnemonicResult.Success
            if (success != null) {
                ChecksumScreen(
                    checksum = success.checksum,
                    onContinue = { navController.navigate(MegaDestinations.SPLIT_GROUPS) },
                )
            }
        }
        composable(MegaDestinations.SPLIT_GROUPS) {
            val state by sharedViewModel.uiState.collectAsState()
            val success = state.mnemonicResult as? MnemonicResult.Success
            if (success != null) {
                SplitGroupsScreen(
                    derivations = success.derivations,
                    onContinue = { navController.navigate(MegaDestinations.WORD_DERIVATION) },
                )
            }
        }
        composable(MegaDestinations.WORD_DERIVATION) {
            val state by sharedViewModel.uiState.collectAsState()
            val success = state.mnemonicResult as? MnemonicResult.Success
            if (success != null) {
                WordDerivationScreen(
                    derivations = success.derivations,
                    onContinue = { navController.navigate(MegaDestinations.FINAL_MNEMONIC) },
                )
            }
        }
        composable(MegaDestinations.FINAL_MNEMONIC) {
            val state by sharedViewModel.uiState.collectAsState()
            val success = state.mnemonicResult as? MnemonicResult.Success
            if (success != null) {
                FinalMnemonicScreen(
                    words = success.words,
                    allowScreenshots = allowScreenshots,
                    allowSeedCopy = allowSeedCopy,
                    onDone = { navController.navigate(MegaDestinations.SAVE_SESSION) },
                )
            }
        }
        composable(MegaDestinations.SAVE_SESSION) {
            val state by sharedViewModel.uiState.collectAsState()
            val success = state.mnemonicResult as? MnemonicResult.Success
            val context = LocalContext.current
            val coroutineScope = rememberCoroutineScope()
            val repository = remember { SessionRepository(context) }

            fun finishAndReturnToWelcome() {
                sharedViewModel.resetSession()
                navController.popBackStack(MegaDestinations.WELCOME, inclusive = false)
            }

            // Without this, a system/gesture back press here would fall through to
            // NavController's default pop (back to FINAL_MNEMONIC) WITHOUT calling
            // resetSession() — leaving the completed mnemonic and every physical
            // roll still live in the shared, Activity-scoped DiceSessionViewModel
            // even though the user just backed out of saving them. Routing every
            // way off this screen through the same reset-then-leave path closes
            // that gap; there is no "leave without saving" exit that skips it.
            BackHandler { finishAndReturnToWelcome() }

            // Saving any data requires a MEGA PIN to already exist. If one
            // doesn't, defer the save (via sharedViewModel.requestPendingSave)
            // and force the user through PIN_SETUP first; PIN_SETUP performs
            // the deferred save itself once a PIN is set (see above).
            fun saveOrRequirePin(withMnemonic: Boolean, label: String) {
                coroutineScope.launch {
                    if (pinManager.isPinEnabled()) {
                        val mnemonicWords = if (withMnemonic) success?.words else null
                        repository.saveSession(
                            diceRolls = state.allRolls,
                            mnemonicWords = mnemonicWords,
                            label = label,
                        )
                        finishAndReturnToWelcome()
                    } else {
                        sharedViewModel.requestPendingSave(withMnemonic, label)
                        navController.navigate(MegaDestinations.PIN_SETUP)
                    }
                }
            }

            SaveSessionScreen(
                rollCount = state.mnemonicLength.rollCount,
                wordCount = state.mnemonicLength.wordCount,
                onDontSave = { finishAndReturnToWelcome() },
                onSaveDiceOnly = { label -> saveOrRequirePin(withMnemonic = false, label = label) },
                onSaveDiceAndMnemonic = { label -> saveOrRequirePin(withMnemonic = true, label = label) },
            )
        }
    }
}

/**
 * Covers saved-session screens with a PIN prompt after the app lock flips
 * back on. The unlock screen is pushed over the current saved-session route,
 * so a correct PIN returns the user to the same saved-session context instead
 * of dumping them back at Welcome.
 */
@Composable
private fun LockGuard(appLockViewModel: AppLockViewModel, navController: NavHostController) {
    val isLocked by appLockViewModel.isLocked.collectAsState()
    LaunchedEffect(isLocked) {
        if (isLocked) {
            navController.navigate(MegaDestinations.SAVED_SESSION_UNLOCK) {
                launchSingleTop = true
            }
        }
    }
}
