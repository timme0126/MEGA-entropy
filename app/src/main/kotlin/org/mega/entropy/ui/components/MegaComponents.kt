package org.mega.entropy.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mega.entropy.R
import org.mega.entropy.ui.theme.MegaError
import org.mega.entropy.ui.theme.MegaNeutralGray


/**
 * Brand wordmark locked to its original black-background treatment. The
 * PNG itself has transparent edges, so light mode must supply the black
 * backing explicitly instead of letting it float on the app background.
 */
@Composable
fun MegaLogo(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1672f / 941f)
            .background(Color.Black)
            .padding(12.dp),
    ) {
        Image(
            painter = painterResource(R.drawable.mega_wordmark),
            contentDescription = stringResource(R.string.app_name),
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Primary CTA button — large touch target, full width, per spec section 44. */
@Composable
fun MegaPrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun MegaSecondaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

/** Deliberately neutral filled button (fixed gray, not theme-adaptive) —
 * used for lower-emphasis actions like "View" / "Label" that shouldn't
 * compete visually with the orange primary actions on the same card. */
@Composable
fun MegaNeutralButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MegaNeutralGray,
            contentColor = androidx.compose.ui.graphics.Color.White,
        ),
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

/** Destructive filled button (fixed red, not theme-adaptive) — reserved for
 * irreversible bulk actions like "Secure Delete All", distinct from the
 * orange used for a single, more contained delete. */
@Composable
fun MegaDestructiveButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MegaError,
            contentColor = androidx.compose.ui.graphics.Color.White,
        ),
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

/** A card section with an optional title, used throughout the explanation
 * and calculation screens so every "show your work" block looks the same. */
@Composable
fun MegaCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            content()
        }
    }
}

/** Monospace block for anything numeric/calculated — dice digits, X, hex,
 * bit groups — so a reviewer's eye can line up digits column by column.
 * See spec sections 6 and 12 ("Show the math", hex representation). */
@Composable
fun MegaMonoText(
    text: String,
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    fontSize: androidx.compose.ui.unit.TextUnit = 15.sp,
) {
    Text(
        text = text,
        modifier = modifier,
        fontFamily = FontFamily.Monospace,
        fontSize = fontSize,
        color = color,
    )
}

@Composable
fun MegaSectionSpacer() = Box(modifier = Modifier.padding(top = 8.dp))

val MegaScreenPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)
