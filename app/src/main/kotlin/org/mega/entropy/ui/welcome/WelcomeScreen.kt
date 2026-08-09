package org.mega.entropy.ui.welcome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import java.time.Year
import org.mega.entropy.BuildConfig
import org.mega.entropy.ui.components.MegaLogo
import org.mega.entropy.ui.components.MegaPrimaryButton
import org.mega.entropy.ui.components.MegaScreenPadding
import org.mega.entropy.ui.components.MegaSecondaryButton

private const val FOUNDING_YEAR = 2026

private val PowerIcon: ImageVector = ImageVector.Builder(
    name = "Power",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(fill = SolidColor(androidx.compose.ui.graphics.Color.Black)) {
        moveTo(13f, 3f)
        horizontalLineTo(11f)
        verticalLineTo(13f)
        horizontalLineTo(13f)
        verticalLineTo(3f)
        close()
        moveTo(17.83f, 5.17f)
        lineTo(16.41f, 6.59f)
        curveTo(17.99f, 7.86f, 19f, 9.82f, 19f, 12f)
        curveTo(19f, 15.86f, 15.86f, 19f, 12f, 19f)
        curveTo(8.14f, 19f, 5f, 15.86f, 5f, 12f)
        curveTo(5f, 9.82f, 6.01f, 7.86f, 7.59f, 6.59f)
        lineTo(6.17f, 5.17f)
        curveTo(4.23f, 6.82f, 3f, 9.26f, 3f, 12f)
        curveTo(3f, 16.97f, 7.03f, 21f, 12f, 21f)
        curveTo(16.97f, 21f, 21f, 16.97f, 21f, 12f)
        curveTo(21f, 9.26f, 19.77f, 6.82f, 17.83f, 5.17f)
        close()
    }
}.build()

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
    onExitApp: () -> Unit,
) {
    var confirmingExit by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(MegaScreenPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Brand lockup uses the original black-background treatment in both
        // light and dark mode.
        MegaLogo()

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
        val currentYear = Year.now().value
        val copyrightYears = if (currentYear > FOUNDING_YEAR) {
            "$FOUNDING_YEAR–$currentYear"
        } else {
            "$FOUNDING_YEAR"
        }
        Text(
            text = "v${BuildConfig.VERSION_NAME} · © $copyrightYears MEGA · github.com/timme0126/MEGA-entropy",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

        IconButton(
            onClick = { confirmingExit = true },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(8.dp),
        ) {
            Icon(PowerIcon, contentDescription = "Close app")
        }

        if (confirmingExit) {
            AlertDialog(
                onDismissRequest = { confirmingExit = false },
                title = { Text("Close MEGA?") },
                text = { Text("This closes the app. Saved sessions remain encrypted and protected by your PIN.") },
                confirmButton = {
                    TextButton(onClick = onExitApp) { Text("Close App") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmingExit = false }) { Text("Cancel") }
                },
            )
        }
    }
}
