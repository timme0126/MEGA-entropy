package org.mega.entropy.ui.navigation

/**
 * All MEGA screens in one place, per section 24 of the project spec
 * ("Main application flow"). Kept as plain route strings rather than a
 * DI-heavy navigation abstraction — this app is small enough that a
 * flat list is easier to audit than a framework.
 */
object MegaDestinations {
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
    fun savedSessionDetailRoute(sessionId: String) = "saved_session_detail/$sessionId"
    const val HOW_IT_WORKS = "how_it_works"
    const val SECURITY_MODEL = "security_model"
    const val PRIVACY = "privacy"
    const val ABOUT = "about"
    const val PIN_ENTRY = "pin_entry"
    const val PIN_SETUP = "pin_setup"
}
