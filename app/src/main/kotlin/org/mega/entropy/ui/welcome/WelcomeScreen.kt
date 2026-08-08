package org.mega.entropy.ui.welcome

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
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
        // Brand lockup (wordmark + tagline) replaces the old plain-text
        // title/subtitle. Its own canvas is solid black, so it's shown on a
        // matching solid-black backdrop that extends slightly past the
        // image on every side — this way there's no visible seam against
        // this screen's normal theme background regardless of light/dark
        // mode, since the black backdrop is drawn explicitly rather than
        // relying on the image's edge to land exactly on a themed color.
        Image(
            painter = painterResource(R.drawable.mega_wordmark),
            contentDescription = stringResource(R.string.app_name),
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black)
                .aspectRatio(1672f / 941f)
                .padding(12.dp),
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
