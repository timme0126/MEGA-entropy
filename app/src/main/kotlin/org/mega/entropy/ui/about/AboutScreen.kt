package org.mega.entropy.ui.about

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import org.mega.entropy.BuildConfig
import org.mega.entropy.ui.components.MegaInfoScaffold
import org.mega.entropy.ui.components.MegaMonoText
import org.mega.entropy.ui.components.MegaPrimaryButton
import org.mega.entropy.ui.components.MegaSection

@Composable
fun AboutScreen(onBack: () -> Unit, onPrivacy: () -> Unit) {
    MegaInfoScaffold(title = "About", onBack = onBack) {
        Text("MEGA — Make Entropy Great Again", style = MaterialTheme.typography.titleLarge)
        MegaMonoText("Version ${BuildConfig.VERSION_NAME}")

        MegaSection(
            heading = "What this is",
            body = "An offline tool that converts physical die rolls into a " +
                "12- or 24-word BIP39 recovery phrase, with every " +
                "intermediate calculation shown so the result can be " +
                "independently verified. See How It Works and Security Model.",
        )
        MegaSection(
            heading = "Security review status",
            body = "MEGA's entropy-derivation logic has undergone a " +
                "thorough internal code review and audit (see " +
                "docs/SECURITY-AUDIT-ENTROPY-CORE.md), verifying its " +
                "mathematical correctness against the BIP-39 " +
                "specification. It has not yet undergone an independent " +
                "third-party security audit. Exercise caution before " +
                "using it to protect meaningful funds.",
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
        MegaSection(
            heading = "Source",
            body = "Source, documentation, and issue tracking: " +
                "github.com/timme0126/MEGA-entropy",
        )
        MegaPrimaryButton(text = "Privacy", onClick = onPrivacy)
    }
}
