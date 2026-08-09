package org.mega.entropy.ui.mnemonic

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mega.entropy.ui.components.MegaCard
import org.mega.entropy.ui.components.MegaCopyIconButton
import org.mega.entropy.ui.components.MegaMonoText
import org.mega.entropy.ui.components.MegaPrimaryButton
import org.mega.entropy.ui.components.MegaScreenPadding
import org.mega.entropy.ui.components.MegaSecondaryButton
import org.mega.entropy.ui.components.SecureScreen
import org.mega.entropy.ui.theme.MegaError

@Composable
fun FinalMnemonicScreen(
    words: List<String>,
    allowScreenshots: Boolean,
    allowSeedCopy: Boolean,
    onDone: () -> Unit,
    onAddPassphrase: () -> Unit,
    onBip85: () -> Unit,
) {
    SecureScreen(enabled = !allowScreenshots)
    var revealed by remember { mutableStateOf(false) }
    val exposureGuidance = remember(allowScreenshots, allowSeedCopy) {
        val screenshotGuidance = if (allowScreenshots) {
            "Screenshots are enabled in Settings."
        } else {
            "Screenshots are off by default."
        }
        val copyGuidance = if (allowSeedCopy) {
            "Copy is enabled in Settings for this session."
        } else {
            "Copy is off by default; write the words down by hand if you need a durable record."
        }
        "Make sure no one else can see this screen. $screenshotGuidance $copyGuidance"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(MegaScreenPadding),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Your ${words.size}-Word Mnemonic", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

        if (!revealed) {
            MegaCard {
                Text(
                    "Your ${words.size} words control any wallet initialized with " +
                        "this mnemonic. Anyone who obtains them may be able to " +
                        "take the funds.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MegaError,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    exposureGuidance,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            MegaPrimaryButton(text = "Reveal ${words.size} Words", onClick = { revealed = true })
        } else {
            MegaCard(
                leadingAction = if (allowSeedCopy) {
                    {
                        MegaCopyIconButton(
                            contentDescription = "Copy seed words",
                            getTextToCopy = { words.joinToString(" ") },
                        )
                    }
                } else {
                    null
                },
            ) {
                WordGrid(words)
            }
            Text(
                "Written it down? Double-check every word against this " +
                    "screen before you leave it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            MegaSecondaryButton(text = "Add a Passphrase (Optional)", onClick = onAddPassphrase)
            MegaSecondaryButton(text = "Calculate BIP85 Child", onClick = onBip85)
            MegaPrimaryButton(text = "Done", onClick = onDone)
        }
    }
}

@Composable
private fun WordGrid(words: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        words.chunked(2).forEachIndexed { rowIndex, pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
                pair.forEachIndexed { colIndex, word ->
                    val number = rowIndex * 2 + colIndex + 1
                    Row(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${number.toString().padStart(2, '0')}.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 6.dp),
                        )
                        MegaMonoText(word, fontSize = 16.sp)
                    }
                }
            }
        }
    }
}
