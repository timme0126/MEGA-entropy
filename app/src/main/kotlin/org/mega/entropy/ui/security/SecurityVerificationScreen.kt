package org.mega.entropy.ui.security

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import android.content.Intent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mega.entropy.security.verification.SecurityCheck
import org.mega.entropy.security.verification.SecurityCheckStatus
import org.mega.entropy.security.verification.SecurityEnvironmentProfile
import org.mega.entropy.security.verification.SecurityOverallStatus
import org.mega.entropy.security.verification.SecurityState
import org.mega.entropy.security.verification.SecurityVerifier
import org.mega.entropy.ui.components.MegaCard
import org.mega.entropy.ui.components.MegaInfoScaffold
import org.mega.entropy.ui.components.MegaPrimaryButton
import org.mega.entropy.ui.components.MegaSecondaryButton
import org.mega.entropy.ui.components.SecureScreen
import org.mega.entropy.ui.theme.MegaError
import org.mega.entropy.ui.theme.MegaSuccess

@Composable
fun SecurityVerificationScreen(onBack: () -> Unit, onContinue: () -> Unit) {
    SecureScreen()
    val context = LocalContext.current
    var state by remember { mutableStateOf(SecurityVerifier.verify(context)) }
    var environment by remember { mutableStateOf(SecurityEnvironmentProfile.ORDINARY_ANDROID) }
    var recheckCount by remember { mutableStateOf(0) }
    var isRechecking by remember { mutableStateOf(false) }
    var completedRechecks by remember { mutableStateOf(0) }
    LaunchedEffect(recheckCount) {
        if (recheckCount > 0) {
            isRechecking = true
            state = withContext(Dispatchers.Default) { SecurityVerifier.verify(context) }
            completedRechecks = recheckCount
            isRechecking = false
        }
    }
    MegaInfoScaffold(title = "Security Verification", onBack = onBack, scrollToTopRequest = recheckCount) {
        Text(if (completedRechecks == 0) "Not checked again since this screen opened." else "Last checked after recheck " + completedRechecks + ".", style = MaterialTheme.typography.labelMedium)
        Text("MEGA has no Android Internet permission. This screen reports what the app can verify; it does not certify that a phone is air-gapped.", style = MaterialTheme.typography.bodyMedium)
        OverallCard(state)
        CheckSection("MEGA NETWORK CAPABILITY", state.appChecks)
        CheckSection("DEVICE CONNECTIVITY", state.deviceChecks)
        MegaCard(title = "ENVIRONMENT / ISOLATION") {
            Text("Choose the environment that best describes this device. This is user-supplied information, not automatic verification.", style = MaterialTheme.typography.bodySmall)
            SecurityEnvironmentProfile.values().forEach { profile ->
                MegaSecondaryButton(profile.label, enabled = environment != profile, onClick = { environment = profile })
            }
            Text(environment.guidance, style = MaterialTheme.typography.bodyMedium)
        }
        MegaPrimaryButton(if (isRechecking) "RECHECKING…" else "RECHECK", enabled = !isRechecking, onClick = { recheckCount++ })
        MegaPrimaryButton(if (state.overallStatus == SecurityOverallStatus.VERIFIED) "CONTINUE" else "CONTINUE ANYWAY", onClick = onContinue)
    }
}

@Composable
private fun OverallCard(state: SecurityState) {
    val (title, color) = when (state.overallStatus) {
        SecurityOverallStatus.VERIFIED -> "OFFLINE CONDITIONS VERIFIED" to MegaSuccess
        SecurityOverallStatus.ACTION_REQUIRED -> "ACTION REQUIRED" to MegaError
        SecurityOverallStatus.SOME_UNVERIFIED -> "SOME CONDITIONS COULD NOT BE VERIFIED" to Color(0xFFFFB74D)
    }
    MegaCard(title = title) { Text("Green checks describe app guarantees or observed states. Warnings and unavailable checks require your judgment.", color = color, style = MaterialTheme.typography.bodyMedium) }
}

@Composable
private fun CheckSection(title: String, checks: List<SecurityCheck>) {
    val context = LocalContext.current
    MegaCard(title = title) {
        checks.forEach { check ->
            val (symbol, color) = when (check.status) {
                SecurityCheckStatus.PASS -> "✓" to MegaSuccess
                SecurityCheckStatus.WARNING, SecurityCheckStatus.UNAVAILABLE -> "⚠" to Color(0xFFFFB74D)
                SecurityCheckStatus.FAIL -> "!" to MegaError
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(symbol, color = color, style = MaterialTheme.typography.titleMedium)
                Column(modifier = Modifier.weight(1f)) {
                    Text(check.title, style = MaterialTheme.typography.bodyLarge)
                    Text(check.detail, style = MaterialTheme.typography.bodySmall)
                    if (check.settingsAction != null && check.status != SecurityCheckStatus.PASS) {
                        TextButton(onClick = { runCatching { context.startActivity(Intent(check.settingsAction)) } }) {
                            Text("OPEN SETTINGS")
                        }
                    }
                }
            }
        }
    }
}
