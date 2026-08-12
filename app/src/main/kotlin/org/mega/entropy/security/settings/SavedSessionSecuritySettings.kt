package org.mega.entropy.security.settings

import android.content.Context

data class SavedSessionLockTimeoutOption(
    val label: String,
    val millis: Long,
)

class SavedSessionSecuritySettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun lockTimeoutMillis(): Long {
        val stored = prefs.getLong(KEY_LOCK_TIMEOUT_MILLIS, DEFAULT_LOCK_TIMEOUT_MILLIS)
        return LOCK_TIMEOUT_OPTIONS.firstOrNull { it.millis == stored }?.millis ?: DEFAULT_LOCK_TIMEOUT_MILLIS
    }

    fun setLockTimeoutMillis(millis: Long) {
        val allowed = LOCK_TIMEOUT_OPTIONS.firstOrNull { it.millis == millis }?.millis ?: DEFAULT_LOCK_TIMEOUT_MILLIS
        prefs.edit().putLong(KEY_LOCK_TIMEOUT_MILLIS, allowed).apply()
    }

    fun randomizePinKeypad(): Boolean {
        return prefs.getBoolean(KEY_RANDOMIZE_PIN_KEYPAD, DEFAULT_RANDOMIZE_PIN_KEYPAD)
    }

    fun setRandomizePinKeypad(randomize: Boolean) {
        prefs.edit().putBoolean(KEY_RANDOMIZE_PIN_KEYPAD, randomize).apply()
    }

    fun allowScreenshots(): Boolean {
        return prefs.getBoolean(KEY_ALLOW_SCREENSHOTS, DEFAULT_ALLOW_SCREENSHOTS)
    }

    fun setAllowScreenshots(allow: Boolean) {
        prefs.edit().putBoolean(KEY_ALLOW_SCREENSHOTS, allow).apply()
    }

    fun allowSeedCopy(): Boolean {
        return prefs.getBoolean(KEY_ALLOW_SEED_COPY, DEFAULT_ALLOW_SEED_COPY)
    }

    fun setAllowSeedCopy(allow: Boolean) {
        prefs.edit().putBoolean(KEY_ALLOW_SEED_COPY, allow).apply()
    }

    /** Gates the whole manual-entry / wallet-derivation flow (spec section
     * "Advanced Mode"). Off by default: MEGA's core purpose is generating a
     * mnemonic from dice on an offline device, never typing an existing one
     * into a networked phone. */
    fun advancedModeEnabled(): Boolean {
        return prefs.getBoolean(KEY_ADVANCED_MODE, DEFAULT_ADVANCED_MODE)
    }

    fun setAdvancedModeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ADVANCED_MODE, enabled).apply()
    }

    /** Whether the dice-roll edit lock (see SavedSessionDetailScreen)
     * starts locked when a saved session is opened. Per-screen UI state
     * still governs the current session while it's open; this only
     * carries the last choice forward as the default for the next one —
     * "remembered for future saved sessions", not persisted per-session.
     * Off by default, preserving the original always-editable behavior. */
    fun diceRollsLockedByDefault(): Boolean {
        return prefs.getBoolean(KEY_DICE_ROLLS_LOCKED, DEFAULT_DICE_ROLLS_LOCKED)
    }

    fun setDiceRollsLockedByDefault(locked: Boolean) {
        prefs.edit().putBoolean(KEY_DICE_ROLLS_LOCKED, locked).apply()
    }

    /** Gates the Advanced Mode "Generate Private Key (WIF)" affordance —
     * categorically riskier than anything else this app shows: an xpub
     * leak only exposes address history, but a private key can spend
     * whatever funds are sent to that one address, with nothing else
     * required. Off by default; even with this on, generating one still
     * requires its own explicit confirmation each time (see
     * AdvancedModeWalletScreen) — this setting only controls whether the
     * button exists at all. */
    fun allowPrivateKeyExport(): Boolean {
        return prefs.getBoolean(KEY_ALLOW_PRIVATE_KEY_EXPORT, DEFAULT_ALLOW_PRIVATE_KEY_EXPORT)
    }

    fun setAllowPrivateKeyExport(allow: Boolean) {
        prefs.edit().putBoolean(KEY_ALLOW_PRIVATE_KEY_EXPORT, allow).apply()
    }

    /** Clears every stored preference here back to its default, so a
     * duress wipe (see PinVerifyScreen) leaves no trace of prior settings
     * choices alongside the deleted sessions — reading back any getter
     * after this returns the same DEFAULT_* value a fresh install would. */
    fun resetToDefaults() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "mega_saved_session_security"
        private const val KEY_LOCK_TIMEOUT_MILLIS = "lock_timeout_millis"
        private const val KEY_RANDOMIZE_PIN_KEYPAD = "randomize_pin_keypad"
        private const val KEY_ALLOW_SCREENSHOTS = "allow_screenshots"
        private const val KEY_ALLOW_SEED_COPY = "allow_seed_copy"
        private const val KEY_ADVANCED_MODE = "advanced_mode_enabled"
        private const val KEY_DICE_ROLLS_LOCKED = "dice_rolls_locked_default"
        private const val KEY_ALLOW_PRIVATE_KEY_EXPORT = "allow_private_key_export"

        const val DEFAULT_LOCK_TIMEOUT_MILLIS = 0L
        const val DEFAULT_RANDOMIZE_PIN_KEYPAD = true
        const val DEFAULT_ALLOW_SCREENSHOTS = false
        const val DEFAULT_ALLOW_SEED_COPY = false
        const val DEFAULT_ADVANCED_MODE = false
        const val DEFAULT_DICE_ROLLS_LOCKED = false
        const val DEFAULT_ALLOW_PRIVATE_KEY_EXPORT = false

        val LOCK_TIMEOUT_OPTIONS = listOf(
            SavedSessionLockTimeoutOption("Immediately", 0L),
            SavedSessionLockTimeoutOption("30 seconds", 30_000L),
            SavedSessionLockTimeoutOption("1 minute", 60_000L),
            SavedSessionLockTimeoutOption("5 minutes", 5 * 60_000L),
            SavedSessionLockTimeoutOption("15 minutes", 15 * 60_000L),
        )
    }
}
