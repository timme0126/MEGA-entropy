package org.mega.entropy.ui.advancedmode

import android.util.Base64
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import org.mega.entropy.ui.components.MegaAnimatedQrCode
import org.mega.entropy.ui.components.MegaCard
import org.mega.entropy.ui.components.MegaCopyIconButton
import org.mega.entropy.ui.components.MegaInfoScaffold
import org.mega.entropy.ui.components.MegaMonoText
import org.mega.entropy.ui.components.MegaPrimaryButton
import org.mega.entropy.ui.components.SecureScreen
import org.mega.entropy.ui.theme.MegaError
import org.mega.entropycore.encodeBbqr
import org.mega.entropycore.extractFinalTransactionHex
import org.mega.entropycore.isPsbtFullyFinalized
import org.mega.entropycore.signAndFinalizePsbt

private fun hexStringToByteArray(hex: String): ByteArray {
    return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

@Composable
fun PsbtSignResultScreen(
    psbtBytes: ByteArray,
    mnemonicWords: List<String>,
    passphrase: String,
    allowScreenshots: Boolean,
    allowSeedCopy: Boolean,
    onBack: () -> Unit,
) {
    SecureScreen(enabled = !allowScreenshots)
    val signResult = remember(psbtBytes, mnemonicWords, passphrase) {
        runCatching { signAndFinalizePsbt(psbtBytes, mnemonicWords, passphrase) }
    }

    MegaInfoScaffold(title = "Sign PSBT", onBack = onBack) {
            if (signResult.isFailure) {
                MegaCard(title = "Could Not Sign PSBT") {
                    Text(
                        text = signResult.exceptionOrNull()?.message ?: "This PSBT could not be signed by this device.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MegaError
                    )
                }
            } else {
                val signedBytes = signResult.getOrThrow()
                val fullyFinalized = remember(signedBytes) { isPsbtFullyFinalized(signedBytes) }

                if (fullyFinalized) {
                    val txHex = remember(signedBytes) { extractFinalTransactionHex(signedBytes) }

                    if (txHex == null) {
                        MegaCard {
                            Text(
                                text = "Could not extract the final transaction from this PSBT.",
                                color = MegaError,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    } else {
                        MegaCard(title = "Transaction Fully Signed") {
                            Text(
                                text = "This is the final, signed transaction. Broadcasting it will move funds — only proceed if you're sure this is what you intend to send.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MegaError
                            )
                        }

                        MegaCard(
                            title = "Transaction (hex)",
                            trailingAction = if (allowSeedCopy) {
                                {
                                    MegaCopyIconButton(
                                        contentDescription = "Copy transaction hex",
                                        getTextToCopy = { txHex }
                                    )
                                }
                            } else null
                        ) {
                            Column {
                                txHex.chunked(32).forEach { line ->
                                    MegaMonoText(line)
                                }
                            }
                        }

                        MegaCard(title = "Broadcast QR") {
                            MegaAnimatedQrCode(
                                frames = remember(txHex) { encodeBbqr('T', hexStringToByteArray(txHex)) },
                                contentDescription = "Animated QR code of the signed transaction, to scan into a broadcasting wallet"
                            )
                        }

                        MegaPrimaryButton(text = "Done", onClick = onBack)
                    }
                } else {
                    MegaCard(title = "Signed — More Cosigners Needed") {
                        Text(
                            text = "This device's signature has been added, but the transaction still needs more signatures before it can be broadcast. Export the updated PSBT below and hand it to the next cosigner.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    MegaCard(
                        title = "Updated PSBT",
                        trailingAction = if (allowSeedCopy) {
                            {
                                MegaCopyIconButton(
                                    contentDescription = "Copy PSBT as base64",
                                    getTextToCopy = { Base64.encodeToString(signedBytes, Base64.NO_WRAP) }
                                )
                            }
                        } else null
                    ) {
                        MegaAnimatedQrCode(
                            frames = remember(signedBytes) { encodeBbqr('P', signedBytes) },
                            contentDescription = "Animated QR code of the partially-signed PSBT, to scan into the next cosigner's wallet"
                        )
                    }

                    MegaPrimaryButton(text = "Done", onClick = onBack)
                }
            }
        }
}
