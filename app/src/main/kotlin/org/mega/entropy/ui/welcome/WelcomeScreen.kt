package org.mega.entropy.ui.welcome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.mega.entropy.R
import org.mega.entropy.ui.components.MegaBadgeRow
import org.mega.entropy.ui.components.MegaPrimaryButton
import org.mega.entropy.ui.components.MegaScreenPadding
import org.mega.entropy.ui.components.MegaSecondaryButton

/**
 * Spec section 24, "Welcome". First thing a user sees; sets the tone that
 * this is a serious, auditable tool rather than a novelty dice app.
 */
@Composable
fun WelcomeScreen(
    onNewDiceSession: () -> Unit,
    onSavedSessions: () -> Unit,
    onHowItWorks: () -> Unit,
    onSecurityModel: () -> Unit,
    onAbout: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(MegaScreenPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(R.string.app_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(20.dp))
        MegaBadgeRow(badges = listOf("OFFLINE", "REAL DICE ONLY", "ZERO DEVICE ENTROPY IN SEED"))
        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "Convert physical die rolls into a valid 12- or 24-word " +
                "BIP39 recovery phrase — with every calculation shown, " +
                "and nothing else contributing to the result.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(32.dp))

        MegaPrimaryButton(text = "New Dice Session", onClick = onNewDiceSession)
        MegaSecondaryButton(text = "Saved Sessions", onClick = onSavedSessions)
        MegaSecondaryButton(text = "How It Works", onClick = onHowItWorks)
        MegaSecondaryButton(text = "Security Model", onClick = onSecurityModel)
        MegaSecondaryButton(text = "About", onClick = onAbout)

        Spacer(modifier = Modifier.height(32.dp))
        Text(
            text = "© 2026 Mega.it. Code licensed under MIT.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
