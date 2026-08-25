package org.mega.entropy.security.backup

import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.generators.SCrypt

/**
 * scrypt cost parameters for a MEGA backup file. N=2^17 lands around half a
 * second on a modern phone and a few seconds on an old/slow one — high
 * enough to matter against offline brute-force of a stolen backup file
 * (unlike the PIN's PBKDF2, which only has to resist on-device guessing
 * attempts), while still tolerable as a one-time cost during export/import.
 */
const val BACKUP_SCRYPT_N = 1 shl 17
const val BACKUP_SCRYPT_R = 8
const val BACKUP_SCRYPT_P = 1
const val BACKUP_KEY_LENGTH_BYTES = 32
const val BACKUP_SALT_LENGTH_BYTES = 16
const val BACKUP_GCM_IV_LENGTH_BYTES = 12
const val BACKUP_GCM_TAG_LENGTH_BITS = 128

/**
 * Widest cost parameters this app will ever ATTEMPT to run, for validating
 * an incoming backup file's header before deriving a key with it (see
 * BackupSerializer.decodeBackupFile). A corrupted or hostile file claiming
 * an absurd N/r/p must be rejected before scrypt ever runs, not after
 * pinning the device for minutes — RFC 7914's own "very sensitive files"
 * tier tops out at N=2^20, so 2^20/16/8 leaves headroom above every value
 * this app itself ever writes without allowing an unbounded cost.
 */
const val BACKUP_SCRYPT_MAX_N = 1 shl 20
const val BACKUP_SCRYPT_MAX_R = 16
const val BACKUP_SCRYPT_MAX_P = 8

/**
 * Derives a 256-bit AES key from a user-supplied passphrase via scrypt
 * (RFC 7914). Unlike SessionCrypto's Android Keystore-backed keys — which
 * exist specifically so no software path can ever extract the raw key —
 * a backup's key must be reproducible from the passphrase ALONE, on a
 * second device that has never seen this one's Keystore, since that
 * portability is the entire point of an exportable backup file.
 */
fun deriveBackupKey(
    passphrase: String,
    salt: ByteArray,
    n: Int = BACKUP_SCRYPT_N,
    r: Int = BACKUP_SCRYPT_R,
    p: Int = BACKUP_SCRYPT_P,
): ByteArray {
    require(salt.size == BACKUP_SALT_LENGTH_BYTES) {
        "salt must be exactly $BACKUP_SALT_LENGTH_BYTES bytes, got ${salt.size}"
    }
    return SCrypt.generate(passphrase.toByteArray(StandardCharsets.UTF_8), salt, n, r, p, BACKUP_KEY_LENGTH_BYTES)
}

/**
 * Encrypts [plaintext] with AES-256-GCM under a scrypt-derived [key].
 * [aad] (the backup file's own plaintext header — format version and KDF
 * parameters) is bound into the authentication tag so a tampered header —
 * e.g. someone lowering N to make brute-forcing cheaper — is detected on
 * decrypt even though the header itself isn't secret.
 */
fun encryptBackupPayload(key: ByteArray, iv: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
    require(key.size == BACKUP_KEY_LENGTH_BYTES) { "key must be exactly $BACKUP_KEY_LENGTH_BYTES bytes, got ${key.size}" }
    require(iv.size == BACKUP_GCM_IV_LENGTH_BYTES) { "iv must be exactly $BACKUP_GCM_IV_LENGTH_BYTES bytes, got ${iv.size}" }
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(BACKUP_GCM_TAG_LENGTH_BITS, iv))
    cipher.updateAAD(aad)
    return cipher.doFinal(plaintext)
}

/**
 * Decrypts a payload produced by [encryptBackupPayload]. Lets
 * AEADBadTagException propagate uncaught — a wrong passphrase or a
 * tampered/corrupted file must fail closed, never be silently
 * misinterpreted as valid plaintext.
 */
fun decryptBackupPayload(key: ByteArray, iv: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray {
    require(key.size == BACKUP_KEY_LENGTH_BYTES) { "key must be exactly $BACKUP_KEY_LENGTH_BYTES bytes, got ${key.size}" }
    require(iv.size == BACKUP_GCM_IV_LENGTH_BYTES) { "iv must be exactly $BACKUP_GCM_IV_LENGTH_BYTES bytes, got ${iv.size}" }
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(BACKUP_GCM_TAG_LENGTH_BITS, iv))
    cipher.updateAAD(aad)
    return cipher.doFinal(ciphertext)
}
