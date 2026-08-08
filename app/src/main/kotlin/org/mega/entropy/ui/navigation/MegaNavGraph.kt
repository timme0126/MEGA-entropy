package org.mega.entropy.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch
import org.mega.entropy.security.pin.PinManager
import org.mega.entropy.storage.SessionRepository
import org.mega.entropy.ui.about.AboutScreen
import org.mega.entropy.ui.biascheck.BiasCheckScreen
import org.mega.entropy.ui.checksum.ChecksumScreen
import org.mega.entropy.ui.diceentry.DiceEntryScreen
import org.mega.entropy.ui.diceentry.DiceSessionViewModel
import org.mega.entropy.ui.entropy.Entropy256Screen
import org.mega.entropy.ui.howitworks.HowItWorksScreen
import org.mega.entropy.ui.mnemonic.FinalMnemonicScreen
import org.mega.entropy.ui.onboarding.BeforeYouBeginScreen
import org.mega.entropy.ui.pin.AppLockViewModel
import org.mega.entropy.ui.pin.PinSetupScreen
import org.mega.entropy.ui.pin.PinVerifyScreen
import org.mega.entropy.ui.privacy.PrivacyScreen
import org.mega.entropy.ui.savedsessions.SavedSessionsScreen
import org.mega.entropy.ui.savesession.SaveSessionScreen
import org.mega.entropy.ui.security.SecurityModelScreen
import org.mega.entropy.ui.splitgroups.SplitGroupsScreen
import org.mega.entropy.ui.welcome.WelcomeScreen
import org.mega.entropy.ui.words.WordDerivationScreen
import org.mega.entropycore.MnemonicResult

@Composable
fun MegaNavGraph(navController: NavHostController = rememberNavController()) {
    // Activity-scoped (no back-stack-entry override), so it survives
    // navigation across the whole app — see AppLockViewModel's doc comment
    // for why backgrounding from any screen must re-lock saved-session access.
    val appLockViewModel: AppLockViewModel = viewModel()
    val context = LocalContext.current
    val pinManager = remember { PinManager(context) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                appLockViewModel.lock()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    NavHost(navController = navController, startDestination = MegaDestinations.WELCOME) {
        composable(MegaDestinations.WELCOME) {
            val coroutineScope = rememberCoroutineScope()
            WelcomeScreen(
                onNewDiceSession = { navController.navigate(MegaDestinations.BEFORE_YOU_BEGIN) },
                onSavedSessions = {
                    coroutineScope.launch {
                        val needsPin = pinManager.isPinEnabled() && appLockViewModel.isLocked.value
                        if (needsPin) {
                            navController.navigate(MegaDestinations.PIN_ENTRY)
                        } else {
                            navController.navigate(MegaDestinations.SAVED_SESSIONS)
                        }
                    }
                },
                onHowItWorks = { navController.navigate(MegaDestinations.HOW_IT_WORKS) },
                onSecurityModel = { navController.navigate(MegaDestinations.SECURITY_MODEL) },
                onAbout = { navController.navigate(MegaDestinations.ABOUT) },
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
            )
        }
        composable(MegaDestinations.PIN_SETUP) {
            PinSetupScreen(
                onPinSet = {
                    appLockViewModel.unlock()
                    navController.popBackStack()
                },
                onCancel = { navController.popBackStack() },
            )
        }
        composable(MegaDestinations.BEFORE_YOU_BEGIN) {
            BeforeYouBeginScreen(
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
            SavedSessionsScreen(
                onBack = { navController.popBackStack() },
                onEnablePin = { navController.navigate(MegaDestinations.PIN_SETUP) },
            )
        }

        diceFlow(navController)
    }
}

/**
 * The dice-entry-through-final-mnemonic screens all read from ONE
 * DiceSessionViewModel, scoped to this nested graph's own back stack
 * entry rather than to each individual screen. Compose Navigation gives
 * every composable() destination its own ViewModelStore by default, so
 * without this the pipeline's intermediate results wouldn't survive
 * navigating from one calculation screen to the next. Popping the whole
 * "dice_flow" graph off the back stack (e.g. returning to Welcome) clears
 * that shared ViewModel and its in-memory session state — which is
 * correct: per spec section 16, a session is ephemeral unless the user
 * explicitly saved it.
 */
private fun androidx.navigation.NavGraphBuilder.diceFlow(navController: NavHostController) {
    navigation(startDestination = MegaDestinations.DICE_ENTRY, route = MegaDestinations.DICE_FLOW) {
        composable(MegaDestinations.DICE_ENTRY) { entry ->
            val sharedViewModel = diceSessionViewModel(navController, entry)
            DiceEntryScreen(
                viewModel = sharedViewModel,
                onSessionComplete = { navController.navigate(MegaDestinations.BIAS_CHECK) },
                onBack = { navController.popBackStack(MegaDestinations.WELCOME, inclusive = false) },
            )
        }
        composable(MegaDestinations.BIAS_CHECK) { entry ->
            val sharedViewModel = diceSessionViewModel(navController, entry)
            val state by sharedViewModel.uiState.collectAsState()
            BiasCheckScreen(
                rejectionResult = state.rejectionResult,
                onContinueToEntropy = { navController.navigate(MegaDestinations.ENTROPY_256) },
                onStartNewSequence = {
                    sharedViewModel.resetSession()
                    navController.popBackStack(MegaDestinations.DICE_ENTRY, inclusive = false)
                },
            )
        }
        composable(MegaDestinations.ENTROPY_256) { entry ->
            val sharedViewModel = diceSessionViewModel(navController, entry)
            val state by sharedViewModel.uiState.collectAsState()
            val success = state.mnemonicResult as? MnemonicResult.Success
            if (success != null) {
                Entropy256Screen(
                    entropy = success.entropy,
                    onContinue = { navController.navigate(MegaDestinations.CHECKSUM) },
                )
            }
        }
        composable(MegaDestinations.CHECKSUM) { entry ->
            val sharedViewModel = diceSessionViewModel(navController, entry)
            val state by sharedViewModel.uiState.collectAsState()
            val success = state.mnemonicResult as? MnemonicResult.Success
            if (success != null) {
                ChecksumScreen(
                    checksum = success.checksum,
                    onContinue = { navController.navigate(MegaDestinations.SPLIT_GROUPS) },
                )
            }
        }
        composable(MegaDestinations.SPLIT_GROUPS) { entry ->
            val sharedViewModel = diceSessionViewModel(navController, entry)
            val state by sharedViewModel.uiState.collectAsState()
            val success = state.mnemonicResult as? MnemonicResult.Success
            if (success != null) {
                SplitGroupsScreen(
                    derivations = success.derivations,
                    onContinue = { navController.navigate(MegaDestinations.WORD_DERIVATION) },
                )
            }
        }
        composable(MegaDestinations.WORD_DERIVATION) { entry ->
            val sharedViewModel = diceSessionViewModel(navController, entry)
            val state by sharedViewModel.uiState.collectAsState()
            val success = state.mnemonicResult as? MnemonicResult.Success
            if (success != null) {
                WordDerivationScreen(
                    derivations = success.derivations,
                    onContinue = { navController.navigate(MegaDestinations.FINAL_MNEMONIC) },
                )
            }
        }
        composable(MegaDestinations.FINAL_MNEMONIC) { entry ->
            val sharedViewModel = diceSessionViewModel(navController, entry)
            val state by sharedViewModel.uiState.collectAsState()
            val success = state.mnemonicResult as? MnemonicResult.Success
            if (success != null) {
                FinalMnemonicScreen(
                    words = success.words,
                    onDone = { navController.navigate(MegaDestinations.SAVE_SESSION) },
                )
            }
        }
        composable(MegaDestinations.SAVE_SESSION) { entry ->
            val sharedViewModel = diceSessionViewModel(navController, entry)
            val state by sharedViewModel.uiState.collectAsState()
            val success = state.mnemonicResult as? MnemonicResult.Success
            val context = LocalContext.current
            val coroutineScope = rememberCoroutineScope()
            val repository = remember { SessionRepository(context) }

            fun finishAndReturnToWelcome() {
                sharedViewModel.resetSession()
                navController.popBackStack(MegaDestinations.WELCOME, inclusive = false)
            }

            SaveSessionScreen(
                onDontSave = { finishAndReturnToWelcome() },
                onSaveDiceOnly = {
                    coroutineScope.launch {
                        repository.saveSession(diceRolls = state.allRolls, mnemonicWords = null)
                        finishAndReturnToWelcome()
                    }
                },
                onSaveDiceAndMnemonic = {
                    coroutineScope.launch {
                        repository.saveSession(diceRolls = state.allRolls, mnemonicWords = success?.words)
                        finishAndReturnToWelcome()
                    }
                },
            )
        }
    }
}

@Composable
private fun diceSessionViewModel(navController: NavHostController, entry: NavBackStackEntry): DiceSessionViewModel {
    val parentEntry = remember(entry) { navController.getBackStackEntry(MegaDestinations.DICE_FLOW) }
    return viewModel(parentEntry)
}

