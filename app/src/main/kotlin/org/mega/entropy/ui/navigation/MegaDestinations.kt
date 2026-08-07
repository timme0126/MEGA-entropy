package org.mega.entropy.ui.navigation

/**
 * All MEGA screens in one place, per section 24 of the project spec
 * ("Main application flow"). Kept as plain route strings rather than a
 * DI-heavy navigation abstraction — this app is small enough that a
 * flat list is easier to audit than a framework.
 */
object MegaDestinations {
    const val WELCOME = "welcome"
    const val BEFORE_YOU_BEGIN = "before_you_begin"
    const val DICE_ENTRY = "dice_entry"
    const val BIAS_CHECK = "bias_check"
    const val ENTROPY_256 = "entropy_256"
    const val CHECKSUM = "checksum"
    const val SPLIT_GROUPS = "split_groups"
    const val WORD_DERIVATION = "word_derivation"
    const val FINAL_MNEMONIC = "final_mnemonic"
    const val SAVE_SESSION = "save_session"
    const val SAVED_SESSIONS = "saved_sessions"
    const val HOW_IT_WORKS = "how_it_works"
    const val SECURITY_MODEL = "security_model"
    const val PRIVACY = "privacy"
    const val ABOUT = "about"
    const val PIN_ENTRY = "pin_entry"
    const val PIN_SETUP = "pin_setup"
}
