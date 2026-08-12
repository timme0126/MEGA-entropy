package org.mega.entropy.ui.navigation

/**
 * All MEGA screens in one place, per section 24 of the project spec
 * ("Main application flow"). Kept as plain route strings rather than a
 * DI-heavy navigation abstraction — this app is small enough that a
 * flat list is easier to audit than a framework.
 */
object MegaDestinations {
    /** Shown once per cold launch, before WELCOME — see LoadingScreen. */
    const val LOADING = "loading"
    const val WELCOME = "welcome"
    const val CHOOSE_LENGTH = "choose_length"
    const val BEFORE_YOU_BEGIN = "before_you_begin"

    /** Route of the nested graph wrapping DICE_ENTRY..FINAL_MNEMONIC, so
     * those screens can share one DiceSessionViewModel scoped to this
     * graph's own back stack entry (see MegaNavGraph). */
    const val DICE_FLOW = "dice_flow"
    const val DICE_ENTRY = "dice_entry"
    const val BIAS_CHECK = "bias_check"
    const val ENTROPY_256 = "entropy_256"
    const val CHECKSUM = "checksum"
    const val SPLIT_GROUPS = "split_groups"
    const val WORD_DERIVATION = "word_derivation"
    const val FINAL_MNEMONIC = "final_mnemonic"

    const val SAVE_SESSION = "save_session"
    const val SAVED_SESSIONS = "saved_sessions"
    const val SAVED_SESSION_DETAIL_ARG = "sessionId"
    const val SAVED_SESSION_DETAIL = "saved_session_detail/{$SAVED_SESSION_DETAIL_ARG}"
    const val SAVED_SESSION_UNLOCK = "saved_session_unlock"
    fun savedSessionDetailRoute(sessionId: String) = "saved_session_detail/$sessionId"
    const val HOW_IT_WORKS = "how_it_works"
    const val SECURITY_MODEL = "security_model"
    const val PRIVACY = "privacy"
    const val ABOUT = "about"
    const val PIN_ENTRY = "pin_entry"
    const val PIN_SETUP = "pin_setup"
    const val PIN_DURESS_SETUP = "pin_duress_setup"

    /** Re-verifying the current PIN before "Change PIN" (Saved Sessions).
     * Separate from PIN_ENTRY so its onUnlocked routes to PIN_SETUP instead
     * of SAVED_SESSIONS — changing the PIN must always prove knowledge of
     * the current one first, even if the app was already unlocked. */
    const val PIN_CHANGE_VERIFY = "pin_change_verify"

    /** Advanced Mode: manual mnemonic entry + BIP85 / wallet key derivation
     * for an existing (not dice-generated) seed phrase. Reachable from
     * Welcome only when the Advanced Mode setting is on. */
    const val ADVANCED_MODE_ENTRY = "advanced_mode_entry"
    /** The manual-typing sub-screen, one of two ways in from
     * ADVANCED_MODE_ENTRY's landing page (the other: import from a
     * saved session). */
    const val ADVANCED_MODE_MANUAL_ENTRY = "advanced_mode_manual_entry"
    const val ADVANCED_MODE_HUB = "advanced_mode_hub"
    const val ADVANCED_MODE_BIP85 = "advanced_mode_bip85"
    const val ADVANCED_MODE_WALLET = "advanced_mode_wallet"
    /** PIN-gated picker (same gate as SAVED_SESSIONS) for importing an
     * existing saved session's words into Advanced Mode instead of typing
     * them by hand. */
    const val ADVANCED_MODE_IMPORT_PICKER = "advanced_mode_import_picker"

    /** "Setup Multi-Signature Vault" — reachable directly from
     * ADVANCED_MODE_ENTRY, not from the Hub: unlike every other Advanced
     * Mode destination, this one never needs a single seed loaded up
     * front, since each cosigner slot gets filled independently (saved
     * session, pasted fragment, or eventually a scan) from within the
     * flow itself. See MultisigVaultViewModel for the flow's state. */
    const val ADVANCED_MODE_MULTISIG_VAULT = "advanced_mode_multisig_vault"
    /** PIN-gated picker (same gate as SAVED_SESSIONS/ADVANCED_MODE_IMPORT_PICKER)
     * for choosing which saved session to derive one vault slot's
     * cosigner key from — reuses AdvancedModeImportPickerScreen itself
     * unchanged, just with a different onImported destination. */
    const val ADVANCED_MODE_MULTISIG_COSIGNER_PICKER = "advanced_mode_multisig_cosigner_picker"
    /** Passphrase + account index entry for deriving one slot's cosigner
     * key from the words ADVANCED_MODE_MULTISIG_COSIGNER_PICKER just
     * supplied. */
    const val ADVANCED_MODE_MULTISIG_DERIVE_COSIGNER = "advanced_mode_multisig_derive_cosigner"
    /** Camera QR scanner for adding a pasted-equivalent cosigner descriptor
     * fragment or full `wsh(sortedmulti(...))` descriptor to the multisig flow. */
    const val ADVANCED_MODE_MULTISIG_SCANNER = "advanced_mode_multisig_scanner"

    /** "Multi-Signature Vaults" button's actual landing page: the saved
     * vaults list (not ADVANCED_MODE_MULTISIG_VAULT directly) whenever at
     * least one vault is already saved. Not PIN-gated — see
     * SavedMultisigVaultsViewModel's doc comment for why. */
    const val ADVANCED_MODE_SAVED_MULTISIG_VAULTS = "advanced_mode_saved_multisig_vaults"
    const val SAVED_MULTISIG_VAULT_DETAIL_ARG = "vaultId"
    const val SAVED_MULTISIG_VAULT_DETAIL = "saved_multisig_vault_detail/{$SAVED_MULTISIG_VAULT_DETAIL_ARG}"
    fun savedMultisigVaultDetailRoute(vaultId: String) = "saved_multisig_vault_detail/$vaultId"
}
