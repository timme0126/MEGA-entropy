package org.mega.entropy

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
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
                // the Welcome screen — composes. `remember` evaluates it
                // once per process, synchronously, before the `when`
                // below can pick a branch, and the vectors are a fixed
                // handful of small computations (well under a second),
                // so blocking the first composition frame is the honest
                // order of operations rather than a race against it.
                val outcome = remember { CryptoSelfTest.run() }
                when (outcome) {
                    CryptoSelfTestOutcome.Pass -> Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        MegaNavGraph()
                    }
                    is CryptoSelfTestOutcome.Failed -> SelfTestFailedDialog(outcome.failedCheckNames)
                }
            }
        }
    }
}
