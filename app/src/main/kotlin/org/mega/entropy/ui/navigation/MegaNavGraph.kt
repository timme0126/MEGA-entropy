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
import org.mega.entropy.security.pin.hasSavedSessionGraceWindowExpired
import org.mega.entropy.security.pin.isSavedSessionUnlockStillValid
import org.mega.entropy.storage.MultisigVaultRepository
import org.mega.entropy.storage.SavedMultisigCosigner
import org.mega.entropy.storage.SavedMultisigVault
import org.mega.entropy.storage.SessionRepository
import org.mega.entropy.ui.about.AboutScreen
import org.mega.entropy.ui.advancedmode.AdvancedModeEntryScreen
import org.mega.entropy.ui.advancedmode.AdvancedModeHubScreen
import org.mega.entropy.ui.advancedmode.AdvancedModeImportPickerScreen
import org.mega.entropy.ui.advancedmode.AdvancedModeMnemonicEntryScreen
import org.mega.entropy.ui.advancedmode.AdvancedModeWalletScreen
import org.mega.entropy.ui.advancedmode.PsbtReviewScreen
import org.mega.entropy.ui.advancedmode.PsbtScanScreen
import org.mega.entropy.ui.advancedmode.PsbtSignResultScreen
import org.mega.entropy.ui.advancedmode.SeedQrScanScreen
import org.mega.entropy.ui.advancedmode.structuretx.StructureTransactionScreen
import org.mega.entropy.ui.advancedmode.structuretx.StructureTransactionViewModel
import org.mega.entropy.ui.advancedmode.multisig.AdvancedModeMultisigDeriveCosignerScreen
import org.mega.entropy.ui.advancedmode.multisig.AdvancedModeMultisigScannerScreen
import org.mega.entropy.ui.advancedmode.multisig.AdvancedModeMultisigVaultScreen
import org.mega.entropy.ui.advancedmode.multisig.MultisigSetupStep
import org.mega.entropy.ui.advancedmode.multisig.MultisigVaultViewModel
import org.mega.entropy.ui.advancedmode.multisig.SavedMultisigVaultDetailScreen
import org.mega.entropy.ui.advancedmode.multisig.SavedMultisigVaultsScreen
import org.mega.entropy.ui.advancedmode.multisig.SavedMultisigVaultsViewModel
import org.mega.entropy.ui.advancedmode.multisig.SavedVaultCosignerPickScreen
import org.mega.entropy.ui.advancedmode.multisig.SavedVaultCosignerVerifyScreen
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
import org.mega.entropy.ui.security.SecurityVerificationScreen
import org.mega.entropy.ui.splitgroups.SplitGroupsScreen
import org.mega.entropy.ui.welcome.WelcomeScreen
import org.mega.entropy.ui.words.WordDerivationScreen
import org.mega.entropycore.MnemonicResult
import org.mega.entropycore.buildMultisigWallet
import org.mega.entropycore.masterKeyFingerprint

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
    val structureTxViewModel: StructureTransactionViewModel = viewModel()
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
    // Carries the passphrase typed on the Hub over to ADVANCED_MODE_PSBT_SCAN
    // / ADVANCED_MODE_PSBT_SIGN_RESULT, same role as advancedModeWalletPassphrase
    // above. scannedPsbtBytes holds the PSBT the scanner screen just read,
    // on its way to the sign/result screen — cleared once that screen is
    // left via its own Done button.
    var advancedModePsbtPassphrase by remember { mutableStateOf("") }
    var scannedPsbtBytes by remember { mutableStateOf<ByteArray?>(null) }
    // Same role as advancedModePsbtPassphrase, for ADVANCED_MODE_STRUCTURE_TX
    // on its way to ADVANCED_MODE_PSBT_REVIEW / ADVANCED_MODE_PSBT_SIGN_RESULT
    // — a freshly-built PSBT is handed to those same two screens via
    // scannedPsbtBytes above, so no separate carrier is needed for the bytes
    // themselves, only for the passphrase used to build (and later sign) it.
    var advancedModeStructureTxPassphrase by remember { mutableStateOf("") }
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
    // "Sign PSBT" for an EXISTING SAVED multisig vault — separate, narrowly
    // scoped state from every other Advanced Mode flow (not advancedModeWords,
    // not multisigCosignerSourceWords): this flow's loaded seed exists only
    // to verify+sign ONE PSBT for ONE saved vault, so unlike advancedModeWords
    // (which lives for the whole Hub session) it must be cleared on ANY exit
    // from this flow, not just its natural end — see exitSavedVaultPsbtFlow.
    var savedVaultPsbtVault by remember { mutableStateOf<SavedMultisigVault?>(null) }
    var savedVaultPsbtSelectedCosigner by remember { mutableStateOf<SavedMultisigCosigner?>(null) }
    var savedVaultPsbtSourceWords by remember { mutableStateOf<List<String>?>(null) }
    var savedVaultPsbtSourceLabel by remember { mutableStateOf("") }
    var savedVaultPsbtPassphrase by remember { mutableStateOf("") }
    var savedVaultPsbtScannedBytes by remember { mutableStateOf<ByteArray?>(null) }
    // Called from Back at any step of the saved-vault PSBT-sign flow, and
    // from its terminal Done button — eagerly drops every sensitive value
    // this flow loaded (candidate seed words, passphrase, scanned PSBT
    // bytes) regardless of how the flow is left, then returns to the
    // originating vault's detail screen (or a plain pop if that route isn't
    // on the back stack for some reason).
    fun exitSavedVaultPsbtFlow() {
        val vaultId = savedVaultPsbtVault?.id
        savedVaultPsbtVault = null
        savedVaultPsbtSelectedCosigner = null
        savedVaultPsbtSourceWords = null
        savedVaultPsbtSourceLabel = ""
        savedVaultPsbtPassphrase = ""
        savedVaultPsbtScannedBytes = null
        val poppedToDetail = vaultId != null &&
            navController.popBackStack(MegaDestinations.savedMultisigVaultDetailRoute(vaultId), inclusive = false)
        if (!poppedToDetail) {
            navController.popBackStack()
        }
    }
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
        val millisSinceUnlock = savedSessionUnlockedAtElapsedRealtime?.let { SystemClock.elapsedRealtime() - it }
        val stillValid = isSavedSessionUnlockStillValid(
            isLocked = appLockViewModel.isLocked.value,
            pinEnabled = pinManager.isPinEnabled(),
            timeoutMillis = savedSessionLockTimeoutMillis,
            millisSinceUnlock = millisSinceUnlock,
        )
        // isSavedSessionUnlockStillValid is a pure decision — it never
        // mutates state itself, so every "not valid" outcome that isn't
        // already reflected by the lock bit needs the actual re-lock side
        // effect applied here (matches the original inline logic exactly).
        if (!stillValid && !appLockViewModel.isLocked.value) lockSavedSessionAccess()
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
        val millisSinceLeft = stoppedAt?.let { SystemClock.elapsedRealtime() - it }
        if (pinManager.isPinEnabled() && hasSavedSessionGraceWindowExpired(savedSessionLockTimeoutMillis, millisSinceLeft)) {
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
                    // unlockSavedSessionAccess(), not a direct
                    // appLockViewModel.unlock() — this route is how BOTH
                    // first-time PIN creation and "Change PIN" complete (the
                    // latter via PIN_CHANGE_VERIFY -> PIN_SETUP). A direct
                    // unlock() leaves savedSessionUnlockedAtElapsedRealtime
                    // null, so the very next enterSavedSessionsGate call
                    // finds no unlock timestamp and re-locks immediately —
                    // forcing a spurious re-prompt moments after the user
                    // just set or changed their PIN.
                    unlockSavedSessionAccess()
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
        composable(MegaDestinations.SECURITY_VERIFICATION) {
            SecurityVerificationScreen(
                onBack = { navController.popBackStack() },
                onContinue = { navController.popBackStack() },
            )
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
                onImportViaSeedQr = {
                    navController.navigate(MegaDestinations.ADVANCED_MODE_SEED_QR)
                },
                onMultisigVaults = {
                    coroutineScope.launch { enterMultisigVaultsEntry() }
                },
                onSecurityVerification = { navController.navigate(MegaDestinations.SECURITY_VERIFICATION) },
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
        composable(MegaDestinations.ADVANCED_MODE_SEED_QR) {
            SeedQrScanScreen(
                allowScreenshots = allowScreenshots,
                onBack = { navController.popBackStack() },
                onScanned = { words ->
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
                    onSignPsbt = { passphrase ->
                        advancedModePsbtPassphrase = passphrase
                        navController.navigate(MegaDestinations.ADVANCED_MODE_PSBT_SCAN)
                    },
                    onStructureTransaction = { passphrase ->
                        advancedModeStructureTxPassphrase = passphrase
                        structureTxViewModel.reset()
                        navController.navigate(MegaDestinations.ADVANCED_MODE_STRUCTURE_TX)
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
        composable(MegaDestinations.ADVANCED_MODE_PSBT_SCAN) {
            PsbtScanScreen(
                allowScreenshots = allowScreenshots,
                onBack = { navController.popBackStack() },
                onScanned = { bytes ->
                    // Scanning alone must never sign anything — this always
                    // lands on the review/confirmation step next, never
                    // directly on the screen that actually signs.
                    scannedPsbtBytes = bytes
                    navController.navigate(MegaDestinations.ADVANCED_MODE_PSBT_REVIEW)
                },
            )
        }
        composable(MegaDestinations.ADVANCED_MODE_PSBT_REVIEW) {
            val psbtBytes = scannedPsbtBytes
            if (psbtBytes != null) {
                // Cancel/Back here discards the scanned PSBT exactly like
                // PsbtSignResultScreen's own onDone does — nothing has been
                // signed yet at this point, so "cancel" and "done" both mean
                // "leave this attempt behind, empty-handed."
                fun onReviewCancelled() {
                    scannedPsbtBytes = null
                    advancedModePsbtPassphrase = ""
                    if (!navController.popBackStack(MegaDestinations.ADVANCED_MODE_HUB, inclusive = false)) {
                        navController.popBackStack()
                    }
                }
                PsbtReviewScreen(
                    psbtBytes = psbtBytes,
                    // The single-seed flow has no concept of a target network
                    // for an arbitrary scanned PSBT — Unknown is the honest
                    // answer, not a guess.
                    knownNetwork = null,
                    deviceMasterFingerprint = remember(advancedModeWords, advancedModePsbtPassphrase) {
                        advancedModeWords?.let { words ->
                            runCatching { masterKeyFingerprint(words, advancedModePsbtPassphrase) }.getOrNull()
                        }
                    },
                    allowScreenshots = allowScreenshots,
                    onCancel = { onReviewCancelled() },
                    onConfirm = { navController.navigate(MegaDestinations.ADVANCED_MODE_PSBT_SIGN_RESULT) },
                )
            } else {
                LaunchedEffect(Unit) {
                    navController.popBackStack()
                }
            }
        }
        composable(MegaDestinations.ADVANCED_MODE_PSBT_SIGN_RESULT) {
            val words = advancedModeWords
            val psbtBytes = scannedPsbtBytes
            if (words != null && psbtBytes != null) {
                fun onDone() {
                    scannedPsbtBytes = null
                    advancedModePsbtPassphrase = ""
                    if (!navController.popBackStack(MegaDestinations.ADVANCED_MODE_HUB, inclusive = false)) {
                        navController.popBackStack()
                    }
                }
                PsbtSignResultScreen(
                    psbtBytes = psbtBytes,
                    mnemonicWords = words,
                    passphrase = advancedModePsbtPassphrase,
                    allowScreenshots = allowScreenshots,
                    allowSeedCopy = allowSeedCopy,
                    onBack = { onDone() },
                )
            } else {
                LaunchedEffect(Unit) {
                    navController.popBackStack()
                }
            }
        }
        composable(MegaDestinations.ADVANCED_MODE_STRUCTURE_TX) {
            val words = advancedModeWords
            val state by structureTxViewModel.uiState.collectAsState()
            if (words != null) {
                // Same "cancel/back means leave nothing behind" reasoning as
                // onReviewCancelled above — Back here must not carry a
                // half-filled form back to the Hub.
                fun onStructureTxBack() {
                    advancedModeStructureTxPassphrase = ""
                    structureTxViewModel.reset()
                    navController.popBackStack()
                }
                val builtBytes = state.builtPsbtBytes
                if (builtBytes != null) {
                    // Built successfully — hand off to the EXACT SAME review/
                    // sign-result screens the scanned-PSBT flow uses, then
                    // consume the built bytes so recomposition (e.g. a
                    // configuration change) doesn't re-navigate.
                    LaunchedEffect(builtBytes) {
                        scannedPsbtBytes = builtBytes
                        advancedModePsbtPassphrase = advancedModeStructureTxPassphrase
                        structureTxViewModel.consumeBuiltPsbt()
                        navController.navigate(MegaDestinations.ADVANCED_MODE_PSBT_REVIEW)
                    }
                }
                StructureTransactionScreen(
                    viewModel = structureTxViewModel,
                    mnemonicWords = words,
                    passphrase = advancedModeStructureTxPassphrase,
                    allowScreenshots = allowScreenshots,
                    onBack = { onStructureTxBack() },
                    onScanDestinationXpub = { navController.navigate(MegaDestinations.ADVANCED_MODE_STRUCTURE_TX_SCAN) },
                )
            } else {
                LaunchedEffect(Unit) {
                    navController.popBackStack()
                }
            }
        }
        composable(MegaDestinations.ADVANCED_MODE_STRUCTURE_TX_SCAN) {
            AdvancedModeMultisigScannerScreen(
                allowScreenshots = allowScreenshots,
                onBack = { navController.popBackStack() },
                onScanned = { text ->
                    structureTxViewModel.onXpubScanned(text)
                    navController.popBackStack()
                },
            )
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
                onEditFingerprint = multisigVaultViewModel::editSlotFingerprint,
                onEditDerivationPath = multisigVaultViewModel::editSlotDerivationPath,
                onEditLabel = multisigVaultViewModel::editSlotLabel,
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
                onGoHome = {
                    multisigVaultViewModel.resetSession()
                    navController.popBackStack(MegaDestinations.ADVANCED_MODE_HUB, inclusive = false)
                },
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
                        onSignPsbt = {
                            savedVaultPsbtVault = currentVault
                            navController.navigate(MegaDestinations.SAVED_MULTISIG_VAULT_PSBT_COSIGNER_PICK)
                        },
                    )
                } else {
                    LaunchedEffect(Unit) { navController.popBackStack() }
                }
            } else if (loadFailed) {
                LaunchedEffect(Unit) { navController.popBackStack() }
            }
        }
        composable(MegaDestinations.SAVED_MULTISIG_VAULT_PSBT_COSIGNER_PICK) {
            val vault = savedVaultPsbtVault
            if (vault != null) {
                val coroutineScope = rememberCoroutineScope()
                SavedVaultCosignerPickScreen(
                    vaultLabel = vault.label,
                    cosigners = vault.cosigners,
                    allowScreenshots = allowScreenshots,
                    onBack = { exitSavedVaultPsbtFlow() },
                    onCosignerSelected = { cosigner ->
                        savedVaultPsbtSelectedCosigner = cosigner
                        coroutineScope.launch {
                            enterSavedSessionsGate(MegaDestinations.SAVED_MULTISIG_VAULT_PSBT_SESSION_PICKER)
                        }
                    },
                )
            } else {
                LaunchedEffect(Unit) { navController.popBackStack() }
            }
        }
        composable(MegaDestinations.SAVED_MULTISIG_VAULT_PSBT_SESSION_PICKER) {
            LockGuard(appLockViewModel, navController)
            AdvancedModeImportPickerScreen(
                allowScreenshots = allowScreenshots,
                onBack = { exitSavedVaultPsbtFlow() },
                onImported = { words, sourceLabel ->
                    savedVaultPsbtSourceWords = words
                    savedVaultPsbtSourceLabel = sourceLabel
                    navController.navigate(MegaDestinations.SAVED_MULTISIG_VAULT_PSBT_VERIFY)
                },
            )
        }
        composable(MegaDestinations.SAVED_MULTISIG_VAULT_PSBT_VERIFY) {
            val vault = savedVaultPsbtVault
            val cosigner = savedVaultPsbtSelectedCosigner
            val words = savedVaultPsbtSourceWords
            if (vault != null && cosigner != null && words != null) {
                SavedVaultCosignerVerifyScreen(
                    mnemonicWords = words,
                    sourceLabel = savedVaultPsbtSourceLabel,
                    selectedCosigner = cosigner,
                    allVaultCosigners = vault.cosigners,
                    allowScreenshots = allowScreenshots,
                    onBack = { exitSavedVaultPsbtFlow() },
                    onVerified = { passphrase ->
                        savedVaultPsbtPassphrase = passphrase
                        navController.navigate(MegaDestinations.SAVED_MULTISIG_VAULT_PSBT_SCAN)
                    },
                )
            } else {
                LaunchedEffect(Unit) { navController.popBackStack() }
            }
        }
        composable(MegaDestinations.SAVED_MULTISIG_VAULT_PSBT_SCAN) {
            PsbtScanScreen(
                allowScreenshots = allowScreenshots,
                onBack = { exitSavedVaultPsbtFlow() },
                onScanned = { bytes ->
                    // Scanning alone must never sign anything — always land
                    // on review/confirmation next, never directly on the
                    // screen that actually signs.
                    savedVaultPsbtScannedBytes = bytes
                    navController.navigate(MegaDestinations.SAVED_MULTISIG_VAULT_PSBT_REVIEW)
                },
            )
        }
        composable(MegaDestinations.SAVED_MULTISIG_VAULT_PSBT_REVIEW) {
            val vault = savedVaultPsbtVault
            val cosigner = savedVaultPsbtSelectedCosigner
            val bytes = savedVaultPsbtScannedBytes
            if (vault != null && cosigner != null && bytes != null) {
                PsbtReviewScreen(
                    psbtBytes = bytes,
                    knownNetwork = vault.network,
                    // Already verified at SAVED_MULTISIG_VAULT_PSBT_VERIFY —
                    // reused as-is rather than re-derived from the seed a
                    // second time.
                    deviceMasterFingerprint = cosigner.masterFingerprint,
                    // This vault's own keys — lets the review VERIFY which
                    // outputs are change back to the vault instead of
                    // trusting the PSBT's own metadata.
                    vaultThreshold = vault.threshold,
                    vaultCosigners = vault.cosigners.map { it.toOrigin() },
                    allowScreenshots = allowScreenshots,
                    onCancel = { exitSavedVaultPsbtFlow() },
                    onConfirm = { navController.navigate(MegaDestinations.SAVED_MULTISIG_VAULT_PSBT_SIGN_RESULT) },
                )
            } else {
                LaunchedEffect(Unit) { navController.popBackStack() }
            }
        }
        composable(MegaDestinations.SAVED_MULTISIG_VAULT_PSBT_SIGN_RESULT) {
            val cosigner = savedVaultPsbtSelectedCosigner
            val words = savedVaultPsbtSourceWords
            val bytes = savedVaultPsbtScannedBytes
            if (cosigner != null && words != null && bytes != null) {
                PsbtSignResultScreen(
                    psbtBytes = bytes,
                    mnemonicWords = words,
                    passphrase = savedVaultPsbtPassphrase,
                    allowScreenshots = allowScreenshots,
                    allowSeedCopy = allowSeedCopy,
                    expectedCosignerFingerprint = cosigner.masterFingerprint,
                    onBack = { exitSavedVaultPsbtFlow() },
                )
            } else {
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
