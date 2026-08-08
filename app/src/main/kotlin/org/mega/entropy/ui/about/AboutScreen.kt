package org.mega.entropy.ui.about

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import org.mega.entropy.BuildConfig
import org.mega.entropy.ui.components.MegaInfoScaffold
import org.mega.entropy.ui.components.MegaMonoText
import org.mega.entropy.ui.components.MegaSecondaryButton
import org.mega.entropy.ui.components.MegaSection

@Composable
fun AboutScreen(onBack: () -> Unit, onPrivacy: () -> Unit) {
    MegaInfoScaffold(title = "About", onBack = onBack) {
        Text("MEGA — Make Entropy Great Again", style = MaterialTheme.typography.titleLarge)
        MegaMonoText("Version ${BuildConfig.VERSION_NAME}")
        MegaMonoText("mega.it")

        MegaSection(
            heading = "What this is",
            body = "An offline tool that converts 100 physical die rolls into " +
                "a 24-word BIP39 recovery phrase, with every intermediate " +
                "calculation shown so the result can be independently " +
                "verified. See How It Works and Security Model.",
        )
        MegaSection(
            heading = "Experimental status",
            body = "MEGA has not undergone an independent security audit. " +
                "Do not use it to protect meaningful funds until one has " +
                "happened. See the project's SECURITY.md for what an " +
                "independent reviewer should check.",
        )
        MegaSection(
            heading = "License",
            body = "MEGA is free and open-source software, released under " +
                "the MIT License. The complete source, including this " +
                "screen's claims about what the app does and does not do, " +
                "is available for independent review.",
        )
        MegaSection(
            heading = "The BIP39 word list",
            body = "Vendored verbatim from the official BIP-0039 English " +
                "word list, verified against its recorded SHA-256 hash at " +
                "build time and again at runtime before any mnemonic is " +
                "derived. See docs/BIP39-DERIVATION.md in the repository.",
        )
        MegaSecondaryButton(text = "Privacy", onClick = onPrivacy)
    }
}
