package org.mega.entropy.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.mega.entropy.ui.about.AboutScreen
import org.mega.entropy.ui.howitworks.HowItWorksScreen
import org.mega.entropy.ui.onboarding.BeforeYouBeginScreen
import org.mega.entropy.ui.privacy.PrivacyScreen
import org.mega.entropy.ui.security.SecurityModelScreen
import org.mega.entropy.ui.welcome.WelcomeScreen

/**
 * Single flat NavHost covering the flow in spec section 24. Screens that
 * depend on :entropy-core's dice/entropy pipeline or on the encrypted
 * session store (dice entry through saved sessions) are wired in as they
 * land; until then they show a short "under construction" placeholder so
 * the rest of the app is always navigable and buildable.
 */
@Composable
fun MegaNavGraph(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = MegaDestinations.WELCOME) {
        composable(MegaDestinations.WELCOME) {
            WelcomeScreen(
                onNewDiceSession = { navController.navigate(MegaDestinations.BEFORE_YOU_BEGIN) },
                onSavedSessions = { navController.navigate(MegaDestinations.SAVED_SESSIONS) },
                onHowItWorks = { navController.navigate(MegaDestinations.HOW_IT_WORKS) },
                onSecurityModel = { navController.navigate(MegaDestinations.SECURITY_MODEL) },
                onAbout = { navController.navigate(MegaDestinations.ABOUT) },
            )
        }
        composable(MegaDestinations.BEFORE_YOU_BEGIN) {
            BeforeYouBeginScreen(
                onBack = { navController.popBackStack() },
                onStartRolling = { navController.navigate(MegaDestinations.DICE_ENTRY) },
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
            AboutScreen(onBack = { navController.popBackStack() })
        }
        composable(MegaDestinations.DICE_ENTRY) { UnderConstruction("Dice Entry") }
        composable(MegaDestinations.SAVED_SESSIONS) { UnderConstruction("Saved Sessions") }
    }
}

@Composable
private fun UnderConstruction(screenName: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("$screenName — coming soon", style = MaterialTheme.typography.bodyLarge)
    }
}
