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
import org.mega.entropy.storage.SessionRepository
import org.mega.entropy.ui.about.AboutScreen
import org.mega.entropy.ui.advancedmode.AdvancedModeHubScreen
import org.mega.entropy.ui.advancedmode.AdvancedModeImportPickerScreen
import org.mega.entropy.ui.advancedmode.AdvancedModeMnemonicEntryScreen
import org.mega.entropy.ui.advancedmode.AdvancedModeWalletScreen
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
    val context = LocalContext.current
    val pinManager = remember { PinManager(context) }
    val repository = remember { SessionRepository(context) }
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
    // Set when AdvancedModeHubScreen's or Bip85Screen's save icon is used
    // but no MEGA PIN exists yet — the same "must have a PIN before
    // saving" redirect the dice flow's SAVE_SESSION uses, just carrying
    // words/label/childSeedInfo instead of a DiceSessionViewModel result.
    // PIN_SETUP performs the deferred save itself once a PIN is set (see
    // below) and pops back to whichever screen it came from.
    var pendingAdvancedModeSave by remember { mutableStateOf<PendingAdvancedModeSave?>(null) }
    var advancedModeSavedConfirmation by remember { mutableStateOf<String?>(null) }
    var stoppedAtElapsedRealtime by remember { mutableStateOf<Long?>(null) }
    val lifecycleCoroutineScope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    // Shared by app backgrounding (ON_STOP/ON_START below) AND by simply
    // navigating back out of Saved Sessions to Welcome — both count as
    // "leaving saved sessions" for auto-lock purposes, so a quick trip to
    // Welcome and straight back in doesn't force a fresh PIN entry unless
    // the configured timeout actually elapsed (or is set to "Immediately").
    suspend fun armSavedSessionLock() {
        if (!pinManager.isPinEnabled()) return
        if (savedSessionLockTimeoutMillis == 0L) {
            appLockViewModel.lock()
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
            appLockViewModel.lock()
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
            // A PIN is required whenever saved data exists. The only
            // bypass is the explicit saved-session timeout window armed
            // when leaving Saved Sessions.
            val hasActiveReturnWindow = stoppedAtElapsedRealtime != null
            consumeSavedSessionLockElapsed()
            if (!hasActiveReturnWindow) {
                appLockViewModel.lock()
            }
            if (appLockViewModel.isLocked.value) {
                navController.navigate(MegaDestinations.PIN_ENTRY)
            } else {
                navController.navigate(target)
            }
        } else {
            appLockViewModel.unlock()
            navController.navigate(target)
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
                    appLockViewModel.unlock()
                    navController.navigate(savedSessionsEntryTarget) {
                        popUpTo(MegaDestinations.PIN_ENTRY) { inclusive = true }
                    }
                },
                onCancel = { navController.popBackStack() },
                randomizeKeypad = randomizePinKeypad,
                onDuressWipe = {
                    appLockViewModel.lock()
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
            val pinManagerForDuress = remember { PinManager(context) }
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
                    appLockViewModel.unlock()
                    navController.popBackStack()
                },
                onCancel = {
                    navController.popBackStack(MegaDestinations.WELCOME, inclusive = false)
                },
                randomizeKeypad = randomizePinKeypad,
                onDuressWipe = {
                    appLockViewModel.lock()
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
                    appLockViewModel.lock()
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
            AdvancedModeMnemonicEntryScreen(
                allowScreenshots = allowScreenshots,
                onBack = { leaveAdvancedMode() },
                onValidated = { words ->
                    advancedModeWords = words
                    advancedModePassphrase = ""
                    advancedModeSourceSessionLabel = null
                    navController.navigate(MegaDestinations.ADVANCED_MODE_HUB)
                },
                onImportFromSavedSession = {
                    coroutineScope.launch { enterSavedSessionsGate(MegaDestinations.ADVANCED_MODE_IMPORT_PICKER) }
                },
            )
        }
        composable(MegaDestinations.ADVANCED_MODE_HUB) {
            val words = advancedModeWords
            if (words != null) {
                val coroutineScope = rememberCoroutineScope()
                AdvancedModeHubScreen(
                    mnemonicWords = words,
                    allowScreenshots = allowScreenshots,
                    allowSeedCopy = allowSeedCopy,
                    isExistingSavedSession = advancedModeSourceSessionLabel != null,
                    onBack = { navController.popBackStack() },
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
                onBack = { navController.popBackStack(MegaDestinations.WELCOME, inclusive = false) },
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
