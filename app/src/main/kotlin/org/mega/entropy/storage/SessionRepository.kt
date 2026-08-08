package org.mega.entropy.storage

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

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
    suspend fun saveSession(diceRolls: List<Int>, mnemonicWords: List<String>? = null, label: String = ""): SavedSessionMetadata {
        return withContext(Dispatchers.IO) {
            val sessionId = UUID.randomUUID().toString()
            val alias = aliasForSession(sessionId)
            val payload = encodePayload(diceRolls, mnemonicWords)
            val encrypted = encrypt(alias, payload)

            val metadata = SavedSessionMetadata(
                id = sessionId,
                createdAtEpochMillis = System.currentTimeMillis(),
                rollsCount = diceRolls.size,
                hasMnemonic = mnemonicWords != null,
                keystoreAlias = alias,
                label = label,
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

            val encryptedData = fileStore.readEncFile(sessionId)
                ?: throw NoSuchElementException("Session encrypted data not found for ID: $sessionId")

            val decryptedPayload = decrypt(metadata.keystoreAlias, encryptedData)
            val (diceRolls, mnemonicWords) = decodePayload(decryptedPayload)

            SavedSessionRecord(metadata, diceRolls, mnemonicWords)
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
}
