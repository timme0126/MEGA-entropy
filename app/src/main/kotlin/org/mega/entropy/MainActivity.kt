package org.mega.entropy

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.mega.entropy.ui.navigation.MegaNavGraph
import org.mega.entropy.ui.selftest.SelfTestFailedDialog
import org.mega.entropy.ui.theme.MegaTheme
import org.mega.entropycore.CryptoSelfTest
import org.mega.entropycore.CryptoSelfTestOutcome

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MegaTheme {
                // The boot crypto self-test is the gate in front of the
                // ENTIRE app, not a warning banner: known-answer vectors
                // are recomputed right here, and until every one matches
                // a published value, no part of MegaNavGraph — not even
                // the Welcome screen — composes. `remember` re-evaluates
                // it once PER COMPOSITION — i.e. again on every Activity
                // recreation and every process start — deliberately: a
                // result cached across recompositions would be a
                // staleness surface the gate exists to close. If the
                // gate itself dies (construction fault), the exception
                // is caught HERE so the designed, readable failure
                // dialog — never a raw crash loop — is what a user sees.
                // (Wall-clock cost on the slowest supported devices is
                // an open, owner-measured question, tracked in the
                // 2026-10-30 audit notes; nothing here claims a bound.)
                var outcome by remember {
                    mutableStateOf(runCatching { CryptoSelfTest.run() }.getOrElse { CryptoSelfTest.startupFailure() })
                }
                when (val current = outcome) {
                    CryptoSelfTestOutcome.Pass -> Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        MegaNavGraph()
                    }
                    is CryptoSelfTestOutcome.Failed -> SelfTestFailedDialog(
                        failedCheckNames = current.failedCheckNames,
                        onRetry = {
                            outcome = runCatching { CryptoSelfTest.run() }
                                .getOrElse { CryptoSelfTest.startupFailure() }
                        },
                        onClose = { finishAffinity() },
                    )
                }
            }
        }
    }
}
