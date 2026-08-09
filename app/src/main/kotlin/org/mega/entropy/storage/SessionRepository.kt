package org.mega.entropy.storage

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import org.mega.entropy.security.passphrase.PassphraseCheck
import org.mega.entropy.security.passphrase.PassphraseVerification
import org.mega.entropy.security.passphrase.buildPassphraseCheck
import org.mega.entropy.security.passphrase.checkPassphrase
import org.mega.entropycore.MnemonicLength
import org.mega.entropycore.MnemonicResult
import org.mega.entropycore.deriveMnemonic

class SessionRepository(private val context: Context) {
    private val fileStore = SessionFileStore(context)

    /**
     * Saves a new session to disk with dedicated cryptographic key and encrypted payload.
     *
     * WHY: We use withContext(Dispatchers.IO) to ensure file I/O and Keystore
     * operations run off the main thread, preventing ANRs. The session ID is
     * a random UUID used purely as a storage identifier, completely independent
     * of wallet/mnemonic entropy handled by :entropy-core.
     */
    suspend fun saveSession(
        diceRolls: List<Int>,
        mnemonicWords: List<String>? = null,
        label: String = "",
        passphraseCheck: PassphraseCheck? = null,
    ): SavedSessionMetadata {
        return withContext(Dispatchers.IO) {
            val sessionId = UUID.randomUUID().toString()
            val alias = aliasForSession(sessionId)
            val payload = encodePayload(diceRolls, mnemonicWords, passphraseCheck)
            val encrypted = encrypt(alias, payload)

            val metadata = SavedSessionMetadata(
                id = sessionId,
                createdAtEpochMillis = System.currentTimeMillis(),
                rollsCount = diceRolls.size,
                hasMnemonic = mnemonicWords != null,
                keystoreAlias = alias,
                label = label,
                hasPassphraseCheck = passphraseCheck != null,
            )

            fileStore.writeMetaFile(metadata)
            fileStore.writeEncFile(sessionId, encrypted)
            metadata
        }
    }

    /**
     * Updates a saved session's label. Metadata is unencrypted (see
     * SavedSessionMetadata's doc comment), so this only touches the .meta
     * file — no decryption or re-encryption of the session's dice/mnemonic
     * payload is needed.
     */
    suspend fun renameSession(sessionId: String, label: String) {
        withContext(Dispatchers.IO) {
            val metadata = fileStore.readMetaFile(sessionId)
                ?: throw NoSuchElementException("Session metadata not found for ID: $sessionId")
            fileStore.writeMetaFile(metadata.copy(label = label))
        }
    }

    suspend fun listSessions(): List<SavedSessionMetadata> {
        return withContext(Dispatchers.IO) {
            fileStore.listAllMetadata().sortedByDescending { it.createdAtEpochMillis }
        }
    }

    /**
     * Loads a complete session record by ID.
     *
     * WHY: We throw NoSuchElementException if metadata or ciphertext files are missing.
     * We let AEADBadTagException propagate uncaught from decrypt() — per security
     * best practices, if encrypted data authentication fails, we must not attempt
     * to interpret partially-decrypted or tampered material.
     */
    suspend fun loadSession(sessionId: String): SavedSessionRecord {
        return withContext(Dispatchers.IO) {
            val metadata = fileStore.readMetaFile(sessionId)
                ?: throw NoSuchElementException("Session metadata not found for ID: $sessionId")

            val payload = decryptPayload(sessionId, metadata)
            SavedSessionRecord(metadata, payload.diceRolls, payload.mnemonicWords, payload.passphraseCheck)
        }
    }



    /**
     * Stores the mnemonic words derived from this session's saved dice rolls.
     * Used when a session was originally saved as dice-only and the user later
     * chooses to keep the words in encrypted saved-session storage as well.
     */
    suspend fun saveMnemonicWords(sessionId: String): List<String> {
        return withContext(Dispatchers.IO) {
            val metadata = fileStore.readMetaFile(sessionId)
                ?: throw NoSuchElementException("Session metadata not found for ID: $sessionId")
            val payload = decryptPayload(sessionId, metadata)
            val words = resolveMnemonicWords(payload.diceRolls, payload.mnemonicWords)
            val newPayload = encodePayload(payload.diceRolls, words, payload.passphraseCheck)
            fileStore.writeEncFile(sessionId, encrypt(metadata.keystoreAlias, newPayload))
            fileStore.writeMetaFile(metadata.copy(hasMnemonic = true))
            words
        }
    }

    /**
     * Replaces the dice rolls for an existing saved session and re-encrypts
     * the payload under the same per-session Keystore key. If the session
     * stored mnemonic words, they are recomputed from the edited rolls so the
     * saved data stays internally consistent. Any passphrase check is cleared:
     * it was derived from the old mnemonic and cannot be safely migrated
     * without knowing the user's passphrase.
     */
    suspend fun updateDiceRolls(sessionId: String, diceRolls: List<Int>) {
        withContext(Dispatchers.IO) {
            val metadata = fileStore.readMetaFile(sessionId)
                ?: throw NoSuchElementException("Session metadata not found for ID: $sessionId")
            val length = MnemonicLength.entries.firstOrNull { it.rollCount == diceRolls.size }
                ?: throw IllegalArgumentException("Saved sessions can only contain 50 or 100 rolls")
            val result = deriveMnemonic(diceRolls, length)
            val success = result as? MnemonicResult.Success
                ?: throw IllegalArgumentException("Edited dice rolls do not produce an accepted mnemonic")

            val newMnemonicWords = if (metadata.hasMnemonic) success.words else null
            val newPayload = encodePayload(diceRolls, newMnemonicWords, passphraseCheck = null)
            fileStore.writeEncFile(sessionId, encrypt(metadata.keystoreAlias, newPayload))
            fileStore.writeMetaFile(metadata.copy(hasPassphraseCheck = false))
        }
    }

    /**
     * Attaches a PassphraseCheck to an already-saved session, so a
     * passphrase can be verified later without ever being displayed again —
     * usable on sessions saved before this feature existed, or ones saved
     * without a passphrase at the time. Overwrites any existing check.
     *
     * WHY: The mnemonic words are recomputed from the saved dice rolls when
     * the session wasn't saved with them (see resolveMnemonicWords) — the
     * same recomputation SavedSessionDetailScreen already relies on for
     * dice-only sessions, since a saved sequence always already produced an
     * accepted mnemonic the first time.
     */
    suspend fun setPassphraseCheck(sessionId: String, passphrase: String) {
        withContext(Dispatchers.IO) {
            val metadata = fileStore.readMetaFile(sessionId)
                ?: throw NoSuchElementException("Session metadata not found for ID: $sessionId")

            val payload = decryptPayload(sessionId, metadata)
            val words = resolveMnemonicWords(payload.diceRolls, payload.mnemonicWords)
            val check = buildPassphraseCheck(words, passphrase)

            val newPayload = encodePayload(payload.diceRolls, payload.mnemonicWords, check)
            fileStore.writeEncFile(sessionId, encrypt(metadata.keystoreAlias, newPayload))
            fileStore.writeMetaFile(metadata.copy(hasPassphraseCheck = true))
        }
    }

    /**
     * Checks whether a candidate passphrase matches a session's stored
     * PassphraseCheck, without ever exposing the original passphrase.
     * Also returns the seed the candidate derived, so a caller that gets a
     * match back can offer to reveal it — the same "reveal the resulting
     * seed" affordance PassphraseScreen already offers right after deriving
     * one, now available again from a saved session once you've proven you
     * know its passphrase. Throws IllegalStateException if the session has
     * no check stored — callers should gate this on
     * SavedSessionMetadata.hasPassphraseCheck.
     */
    suspend fun verifyPassphrase(sessionId: String, candidate: String): PassphraseVerification {
        return withContext(Dispatchers.IO) {
            val metadata = fileStore.readMetaFile(sessionId)
                ?: throw NoSuchElementException("Session metadata not found for ID: $sessionId")

            val payload = decryptPayload(sessionId, metadata)
            val check = payload.passphraseCheck
                ?: throw IllegalStateException("Session $sessionId has no passphrase check stored")
            val words = resolveMnemonicWords(payload.diceRolls, payload.mnemonicWords)

            checkPassphrase(words, candidate, check)
        }
    }

    /**
     * Removes a session's stored PassphraseCheck, if any.
     */
    suspend fun clearPassphraseCheck(sessionId: String) {
        withContext(Dispatchers.IO) {
            val metadata = fileStore.readMetaFile(sessionId)
                ?: throw NoSuchElementException("Session metadata not found for ID: $sessionId")

            val payload = decryptPayload(sessionId, metadata)
            val newPayload = encodePayload(payload.diceRolls, payload.mnemonicWords, passphraseCheck = null)
            fileStore.writeEncFile(sessionId, encrypt(metadata.keystoreAlias, newPayload))
            fileStore.writeMetaFile(metadata.copy(hasPassphraseCheck = false))
        }
    }

    /**
     * Deletes a session's files and its dedicated Keystore key.
     *
     * WHY: We delete both the Keystore key AND the files. Sandboxed storage
     * protects against other apps, but cryptographic key destruction ensures
     * that even if storage is compromised or recovered, the data remains
     * cryptographically inaccessible. We attempt to read metadata first to
     * get the exact alias, falling back to the conventional naming scheme
     * if metadata is missing, ensuring best-effort cleanup.
     */
    suspend fun deleteSession(sessionId: String) {
        withContext(Dispatchers.IO) {
            val metadata = fileStore.readMetaFile(sessionId)
            val alias = metadata?.keystoreAlias ?: aliasForSession(sessionId)

            deleteKey(alias)
            fileStore.deleteSessionFiles(sessionId)
        }
    }

    suspend fun deleteAllSessions() {
        withContext(Dispatchers.IO) {
            listSessions().forEach { deleteSession(it.id) }
        }
    }

    private fun decryptPayload(sessionId: String, metadata: SavedSessionMetadata): SessionPayload {
        val encryptedData = fileStore.readEncFile(sessionId)
            ?: throw NoSuchElementException("Session encrypted data not found for ID: $sessionId")
        return decodePayload(decrypt(metadata.keystoreAlias, encryptedData))
    }

    /**
     * Returns the session's mnemonic words, recomputing them from its dice
     * rolls via :entropy-core when the session wasn't saved with the
     * mnemonic itself. Safe to assume MnemonicResult.Success: a session's
     * rolls only ever reach storage after they already produced an accepted
     * mnemonic (see SaveSessionScreen / MegaNavGraph).
     */
    private fun resolveMnemonicWords(diceRolls: List<Int>, savedMnemonicWords: List<String>?): List<String> {
        if (savedMnemonicWords != null) return savedMnemonicWords

        val length = MnemonicLength.entries.first { it.rollCount == diceRolls.size }
        val result = deriveMnemonic(diceRolls, length)
        return (result as? MnemonicResult.Success)?.words
            ?: throw IllegalStateException("Saved dice rolls for session no longer produce an accepted mnemonic")
    }
}
