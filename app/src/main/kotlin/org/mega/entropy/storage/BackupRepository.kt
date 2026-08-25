package org.mega.entropy.storage

import android.content.Context
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mega.entropy.security.backup.BACKUP_GCM_IV_LENGTH_BYTES
import org.mega.entropy.security.backup.BACKUP_SALT_LENGTH_BYTES
import org.mega.entropy.security.backup.BACKUP_SCRYPT_N
import org.mega.entropy.security.backup.BACKUP_SCRYPT_P
import org.mega.entropy.security.backup.BACKUP_SCRYPT_R
import org.mega.entropy.security.backup.decryptBackupPayload
import org.mega.entropy.security.backup.deriveBackupKey
import org.mega.entropy.security.backup.encryptBackupPayload

/** How many sessions and vaults a successful import actually wrote — shown
 * to the user as confirmation, since a silent "done" would leave someone
 * unsure whether their old device's data really made it across. */
data class BackupImportResult(val sessionCount: Int, val vaultCount: Int)

/**
 * Builds and restores MEGA's portable, passphrase-encrypted backup file:
 * every saved session's mnemonic/label/tags/child-seed-info/passphrase-check,
 * plus every saved multisig vault's public descriptor data, bundled into one
 * file a user can move to a second MEGA device and recover everything from
 * — the same "backup + passphrase" shape Samourai Wallet offers, unlike
 * SessionRepository's per-device Android Keystore encryption (which is
 * deliberately NOT exportable: see SessionCrypto's doc comment), this key
 * is derived from a passphrase alone specifically so it survives a move to
 * different hardware that has never seen this device's Keystore.
 *
 * The passphrase itself is never stored anywhere, on either device — only
 * used in memory, once, to derive the scrypt key for this one operation.
 */
class BackupRepository(context: Context) {
    private val sessionRepository = SessionRepository(context)
    private val multisigVaultRepository = MultisigVaultRepository(context)

    /**
     * Gathers every saved session and vault, encrypts them under a fresh
     * scrypt-derived key, and returns the finished backup file's bytes —
     * ready to write wherever the caller's Storage Access Framework picker
     * points. A fresh random salt and IV are generated for every export,
     * even backing up the exact same data twice in a row.
     */
    suspend fun exportBackup(passphrase: String): ByteArray {
        return withContext(Dispatchers.IO) {
            val sessions = sessionRepository.listSessions().map { metadata ->
                val record = sessionRepository.loadSession(metadata.id)
                val words = sessionRepository.resolveMnemonicWords(record.diceRolls, record.mnemonicWords)
                BackupSessionEntry(
                    createdAtEpochMillis = metadata.createdAtEpochMillis,
                    label = metadata.label,
                    tags = metadata.tags,
                    childSeedInfo = metadata.childSeedInfo,
                    mnemonicWords = words,
                    passphraseCheck = record.passphraseCheck,
                )
            }
            val vaults = multisigVaultRepository.listVaults().map { vault ->
                BackupVaultEntry(
                    createdAtEpochMillis = vault.createdAtEpochMillis,
                    label = vault.label,
                    threshold = vault.threshold,
                    network = vault.network,
                    scriptType = vault.scriptType,
                    tags = vault.tags,
                    cosigners = vault.cosigners.map { cosigner ->
                        BackupVaultCosignerEntry(
                            label = cosigner.label,
                            masterFingerprint = cosigner.masterFingerprint,
                            derivationPath = cosigner.derivationPath,
                            extendedPublicKey = cosigner.extendedPublicKey,
                            passphraseUsed = cosigner.passphraseUsed,
                        )
                    },
                )
            }

            val plaintext = encodeBackupPayload(BackupPayload(sessions, vaults))
            val salt = randomBytes(BACKUP_SALT_LENGTH_BYTES)
            val iv = randomBytes(BACKUP_GCM_IV_LENGTH_BYTES)
            val key = deriveBackupKey(passphrase, salt, BACKUP_SCRYPT_N, BACKUP_SCRYPT_R, BACKUP_SCRYPT_P)
            val header = BackupHeader(BACKUP_SCRYPT_N, BACKUP_SCRYPT_R, BACKUP_SCRYPT_P, salt, iv)
            // AAD must be exactly the header bytes encodeBackupFile will
            // actually write — computed the same way here rather than
            // reused from some intermediate value, so the two can never
            // silently drift apart.
            val aad = encodeBackupFile(header, ByteArray(0))
            val ciphertext = encryptBackupPayload(key, iv, aad, plaintext)
            encodeBackupFile(header, ciphertext)
        }
    }

    /**
     * Decrypts [fileBytes] with [passphrase] and writes every session and
     * vault it contains into this device's own storage as brand-new
     * records (fresh IDs, fresh per-session Keystore keys) — restoring
     * them rather than literally replaying the source device's files.
     * Original save dates are preserved via each repository's
     * createdAtEpochMillis override, so restored items don't all jump to
     * today's date ahead of anything already on this device.
     *
     * Lets a wrong passphrase or corrupted/tampered file fail closed: a
     * bad header throws IllegalArgumentException (see decodeBackupFile)
     * and a wrong key/tampered ciphertext throws AEADBadTagException,
     * either way before a single byte is written to storage — a failed
     * import must never partially import.
     */
    suspend fun importBackup(fileBytes: ByteArray, passphrase: String): BackupImportResult {
        return withContext(Dispatchers.IO) {
            val file = decodeBackupFile(fileBytes)
            val key = deriveBackupKey(passphrase, file.header.salt, file.header.n, file.header.r, file.header.p)
            val plaintext = decryptBackupPayload(key, file.header.iv, file.aad, file.ciphertext)
            val payload = decodeBackupPayload(plaintext)

            payload.sessions.forEach { session ->
                val saved = sessionRepository.saveSession(
                    mnemonicWords = session.mnemonicWords,
                    label = session.label,
                    passphraseCheck = session.passphraseCheck,
                    childSeedInfo = session.childSeedInfo,
                    createdAtEpochMillis = session.createdAtEpochMillis,
                )
                if (session.tags.isNotEmpty()) {
                    sessionRepository.updateSessionTags(saved.id, session.tags)
                }
            }
            payload.vaults.forEach { vault ->
                val saved = multisigVaultRepository.saveVault(
                    threshold = vault.threshold,
                    network = vault.network,
                    scriptType = vault.scriptType,
                    cosigners = vault.cosigners.map {
                        SavedMultisigCosigner(
                            label = it.label,
                            masterFingerprint = it.masterFingerprint,
                            derivationPath = it.derivationPath,
                            extendedPublicKey = it.extendedPublicKey,
                            passphraseUsed = it.passphraseUsed,
                        )
                    },
                    label = vault.label,
                    createdAtEpochMillis = vault.createdAtEpochMillis,
                )
                if (vault.tags.isNotEmpty()) {
                    multisigVaultRepository.updateVaultTags(saved.id, vault.tags)
                }
            }

            BackupImportResult(sessionCount = payload.sessions.size, vaultCount = payload.vaults.size)
        }
    }
}

private fun randomBytes(length: Int): ByteArray = ByteArray(length).also { SecureRandom().nextBytes(it) }
