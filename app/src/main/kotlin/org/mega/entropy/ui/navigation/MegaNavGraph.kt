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
import org.mega.entropy.security.passphrase.buildPassphraseCheck
import org.mega.entropy.security.settings.SavedSessionSecuritySettings
import org.mega.entropy.security.pin.PinManager
import org.mega.entropy.storage.SessionRepository
import org.mega.entropy.ui.about.AboutScreen
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
import org.mega.entropy.ui.passphrase.PassphraseScreen
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

@Composable
fun MegaNavGraph(navController: NavHostController = rememberNavController()) {
    // Both Activity-scoped (no back-stack-entry override), so they survive
    // navigation across the whole app. diceSessionViewModel needs this
    // because ChooseLengthScreen (which sets the chosen length) and
    // BeforeYouBeginScreen (which displays it) both sit outside the
    // "dice_flow" nested graph that the dice-entry screens share.
    val appLockViewModel: AppLockViewModel = viewModel()
    val diceSessionViewModel: DiceSessionViewModel = viewModel()
    var savedSessionBip85ParentWords by remember { mutableStateOf<List<String>?>(null) }
    val context = LocalContext.current
    val pinManager = remember { PinManager(context) }
    val savedSessionSecuritySettings = remember { SavedSessionSecuritySettings(context) }
    var savedSessionLockTimeoutMillis by remember {
        mutableStateOf(savedSessionSecuritySettings.lockTimeoutMillis())
    }
    var randomizePinKeypad by remember {
        mutableStateOf(savedSessionSecuritySettings.randomizePinKeypad())
    }
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
            val repository = remember { SessionRepository(context) }
            WelcomeScreen(
                onNewDiceSession = { navController.navigate(MegaDestinations.CHOOSE_LENGTH) },
                onSavedSessions = {
                    coroutineScope.launch {
                        val hasSavedSessions = repository.listSessions().isNotEmpty()
                        if (hasSavedSessions && pinManager.isPinEnabled()) {
                            // A PIN is required whenever saved data exists. The
                            // only bypass is the explicit saved-session timeout
                            // window armed when leaving Saved Sessions.
                            val hasActiveReturnWindow = stoppedAtElapsedRealtime != null
                            consumeSavedSessionLockElapsed()
                            if (!hasActiveReturnWindow) {
                                appLockViewModel.lock()
                            }
                            if (appLockViewModel.isLocked.value) {
                                navController.navigate(MegaDestinations.PIN_ENTRY)
                            } else {
                                navController.navigate(MegaDestinations.SAVED_SESSIONS)
                            }
                        } else {
                            appLockViewModel.unlock()
                            navController.navigate(MegaDestinations.SAVED_SESSIONS)
                        }
                    }
                },
                onHowItWorks = { navController.navigate(MegaDestinations.HOW_IT_WORKS) },
                onSecurityModel = { navController.navigate(MegaDestinations.SECURITY_MODEL) },
                onAbout = { navController.navigate(MegaDestinations.ABOUT) },
                onExitApp = {
                    context.findActivity()?.finishAndRemoveTask()
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
                    navController.navigate(MegaDestinations.SAVED_SESSIONS) {
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
                    if (pendingSaveWithMnemonic != null) {
                        // Reached via a forced "you must set a PIN before
                        // saving" redirect (see SAVE_SESSION below) — finish
                        // the save that was waiting on this, then leave the
                        // whole dice flow, exactly like a normal save does.
                        coroutineScope.launch {
                            val success = state.mnemonicResult as? MnemonicResult.Success
                            val mnemonicWords = if (pendingSaveWithMnemonic) success?.words else null
                            val passphrase = state.passphrase
                            val passphraseCheck = if (state.pendingSavePassphraseCheck && success != null && passphrase != null) {
                                buildPassphraseCheck(success.words, passphrase)
                            } else {
                                null
                            }
                            repository.saveSession(
                                diceRolls = state.allRolls,
                                mnemonicWords = mnemonicWords,
                                passphraseCheck = passphraseCheck,
                            )
                            diceSessionViewModel.clearPendingSave()
                            diceSessionViewModel.resetSession()
                            navController.popBackStack(MegaDestinations.WELCOME, inclusive = false)
                        }
                    } else {
                        // Reached via "Change PIN" from Saved Sessions.
                        navController.popBackStack()
                    }
                },
                onCancel = {
                    diceSessionViewModel.clearPendingSave()
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
            )
        }
        composable(MegaDestinations.SAVED_SESSION_UNLOCK) {
            PinVerifyScreen(
                onUnlocked = {
                    appLockViewModel.unlock()
                    navController.popBackStack()
                },
                onCancel = {
                    savedSessionBip85ParentWords = null
                    navController.popBackStack(MegaDestinations.WELCOME, inclusive = false)
                },
                randomizeKeypad = randomizePinKeypad,
                onDuressWipe = {
                    savedSessionBip85ParentWords = null
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
                onBack = { navController.popBackStack() },
                onBip85 = { parentWords ->
                    savedSessionBip85ParentWords = parentWords
                    navController.navigate(MegaDestinations.SAVED_SESSION_BIP85)
                },
            )
        }
        composable(MegaDestinations.SAVED_SESSION_BIP85) {
            LockGuard(appLockViewModel, navController)
            val parentWords = savedSessionBip85ParentWords
            fun leaveBip85() {
                savedSessionBip85ParentWords = null
                navController.popBackStack()
            }
            if (parentWords != null) {
                BackHandler { leaveBip85() }
                Bip85Screen(
                    parentWords = parentWords,
                    onBack = { leaveBip85() },
                )
            } else {
                LaunchedEffect(Unit) {
                    navController.popBackStack()
                }
            }
        }

        diceFlow(navController, diceSessionViewModel, pinManager)
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
                    onDone = { navController.navigate(MegaDestinations.SAVE_SESSION) },
                    onAddPassphrase = { navController.navigate(MegaDestinations.PASSPHRASE) },
                    onBip85 = { navController.navigate(MegaDestinations.BIP85) },
                )
            }
        }
        composable(MegaDestinations.BIP85) {
            val state by sharedViewModel.uiState.collectAsState()
            val success = state.mnemonicResult as? MnemonicResult.Success
            if (success != null) {
                Bip85Screen(
                    parentWords = success.words,
                    onBack = { navController.popBackStack() },
                )
            }
        }
        composable(MegaDestinations.PASSPHRASE) {
            val state by sharedViewModel.uiState.collectAsState()
            val success = state.mnemonicResult as? MnemonicResult.Success
            if (success != null) {
                PassphraseScreen(
                    words = success.words,
                    onContinue = { passphrase ->
                        sharedViewModel.setPassphrase(passphrase)
                        navController.navigate(MegaDestinations.SAVE_SESSION)
                    },
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
            fun saveOrRequirePin(withMnemonic: Boolean, withPassphraseCheck: Boolean) {
                coroutineScope.launch {
                    if (pinManager.isPinEnabled()) {
                        val mnemonicWords = if (withMnemonic) success?.words else null
                        val passphrase = state.passphrase
                        val passphraseCheck = if (withPassphraseCheck && success != null && passphrase != null) {
                            buildPassphraseCheck(success.words, passphrase)
                        } else {
                            null
                        }
                        repository.saveSession(
                            diceRolls = state.allRolls,
                            mnemonicWords = mnemonicWords,
                            passphraseCheck = passphraseCheck,
                        )
                        finishAndReturnToWelcome()
                    } else {
                        sharedViewModel.requestPendingSave(withMnemonic, withPassphraseCheck)
                        navController.navigate(MegaDestinations.PIN_SETUP)
                    }
                }
            }

            SaveSessionScreen(
                rollCount = state.mnemonicLength.rollCount,
                wordCount = state.mnemonicLength.wordCount,
                hasPendingPassphrase = state.passphrase != null,
                onDontSave = { finishAndReturnToWelcome() },
                onSaveDiceOnly = { savePassphraseCheck ->
                    saveOrRequirePin(withMnemonic = false, withPassphraseCheck = savePassphraseCheck)
                },
                onSaveDiceAndMnemonic = { savePassphraseCheck ->
                    saveOrRequirePin(withMnemonic = true, withPassphraseCheck = savePassphraseCheck)
                },
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
