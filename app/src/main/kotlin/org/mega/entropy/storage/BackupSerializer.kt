package org.mega.entropy.storage

import java.nio.charset.StandardCharsets
import org.mega.entropy.security.backup.BACKUP_GCM_IV_LENGTH_BYTES
import org.mega.entropy.security.backup.BACKUP_SALT_LENGTH_BYTES
import org.mega.entropy.security.backup.BACKUP_SCRYPT_MAX_N
import org.mega.entropy.security.backup.BACKUP_SCRYPT_MAX_P
import org.mega.entropy.security.backup.BACKUP_SCRYPT_MAX_R
import org.mega.entropy.security.passphrase.PassphraseCheck
import org.mega.entropycore.MultisigScriptType
import org.mega.entropycore.WalletNetwork

private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

private fun String.hexToByteArray(): ByteArray {
    require(length % 2 == 0) { "Hex string must have an even length, got $length" }
    return ByteArray(length / 2) { i -> ((this[i * 2].digitToInt(16) shl 4) or this[i * 2 + 1].digitToInt(16)).toByte() }
}

// --- Outer file: plaintext header (also used verbatim as the GCM AAD) + ciphertext ---

data class BackupHeader(val n: Int, val r: Int, val p: Int, val salt: ByteArray, val iv: ByteArray)

data class BackupFile(val header: BackupHeader, val aad: ByteArray, val ciphertext: ByteArray)

private const val BACKUP_FILE_MAGIC = "MEGA-BACKUP-V1"
private const val BACKUP_FILE_HEADER_LINE_COUNT = 7

/**
 * Writes the backup file's plaintext header (magic, scrypt cost params,
 * salt, IV) immediately followed by the raw ciphertext+tag bytes. The
 * header is plaintext by necessity — the salt and IV must be readable
 * before a key can even be derived to decrypt anything — but it is bound
 * into the ciphertext's own authentication tag as AAD (see
 * encryptBackupPayload's caller), so tampering with it is still detected.
 */
fun encodeBackupFile(header: BackupHeader, ciphertext: ByteArray): ByteArray {
    val headerText = buildString {
        append("$BACKUP_FILE_MAGIC\n")
        append("N:${header.n}\n")
        append("R:${header.r}\n")
        append("P:${header.p}\n")
        append("SALT:${header.salt.toHexString()}\n")
        append("IV:${header.iv.toHexString()}\n")
        append("END-HEADER\n")
    }
    return headerText.toByteArray(StandardCharsets.US_ASCII) + ciphertext
}

/**
 * Parses a backup file back into its header and ciphertext, without
 * attempting any decryption. Scans for exactly
 * [BACKUP_FILE_HEADER_LINE_COUNT] newline-terminated lines byte-by-byte
 * (rather than decoding the whole file as text first) so a large ciphertext
 * full of arbitrary binary bytes can never be misinterpreted as more header
 * lines than there really are.
 *
 * Cost parameters are sanity-bounded here — before any scrypt derivation
 * is attempted — because this file may have come from "another device"
 * (or been corrupted/tampered) and a hostile N/r/p could otherwise pin the
 * importing device for an unbounded amount of time.
 */
fun decodeBackupFile(bytes: ByteArray): BackupFile {
    val lines = mutableListOf<String>()
    var lineStart = 0
    var cursor = 0
    while (lines.size < BACKUP_FILE_HEADER_LINE_COUNT) {
        if (cursor >= bytes.size) throw IllegalArgumentException("Not a MEGA backup file: header is truncated")
        if (bytes[cursor] == '\n'.code.toByte()) {
            lines += String(bytes, lineStart, cursor - lineStart, StandardCharsets.US_ASCII)
            lineStart = cursor + 1
        }
        cursor++
    }
    val ciphertextStart = lineStart

    if (lines[0] != BACKUP_FILE_MAGIC) throw IllegalArgumentException("Not a MEGA backup file: bad header")

    fun value(index: Int, key: String): String {
        val line = lines[index]
        if (!line.startsWith("$key:")) {
            throw IllegalArgumentException("Malformed backup file: expected '$key:' on header line ${index + 1}")
        }
        return line.substringAfter("$key:")
    }

    val n = value(1, "N").toIntOrNull() ?: throw IllegalArgumentException("Malformed backup file: bad N")
    val r = value(2, "R").toIntOrNull() ?: throw IllegalArgumentException("Malformed backup file: bad R")
    val p = value(3, "P").toIntOrNull() ?: throw IllegalArgumentException("Malformed backup file: bad P")
    if (n !in 1..BACKUP_SCRYPT_MAX_N || r !in 1..BACKUP_SCRYPT_MAX_R || p !in 1..BACKUP_SCRYPT_MAX_P) {
        throw IllegalArgumentException("Malformed backup file: scrypt cost parameters out of accepted range")
    }
    val salt = value(4, "SALT").hexToByteArray()
    val iv = value(5, "IV").hexToByteArray()
    if (salt.size != BACKUP_SALT_LENGTH_BYTES) throw IllegalArgumentException("Malformed backup file: bad SALT length")
    if (iv.size != BACKUP_GCM_IV_LENGTH_BYTES) throw IllegalArgumentException("Malformed backup file: bad IV length")
    if (lines[6] != "END-HEADER") throw IllegalArgumentException("Malformed backup file: missing END-HEADER")

    val aad = bytes.copyOfRange(0, ciphertextStart)
    val ciphertext = bytes.copyOfRange(ciphertextStart, bytes.size)
    return BackupFile(BackupHeader(n, r, p, salt, iv), aad, ciphertext)
}

// --- Inner payload: the plaintext actually protected by encryption ---

data class BackupSessionEntry(
    val createdAtEpochMillis: Long,
    val label: String,
    val tags: List<String>,
    val childSeedInfo: String,
    val mnemonicWords: List<String>,
    val passphraseCheck: PassphraseCheck?,
)

data class BackupVaultCosignerEntry(
    val label: String,
    val masterFingerprint: String,
    val derivationPath: String,
    val extendedPublicKey: String,
    val passphraseUsed: Boolean?,
)

data class BackupVaultEntry(
    val createdAtEpochMillis: Long,
    val label: String,
    val threshold: Int,
    val network: WalletNetwork,
    val scriptType: MultisigScriptType,
    val tags: List<String>,
    val cosigners: List<BackupVaultCosignerEntry>,
)

data class BackupPayload(
    val sessions: List<BackupSessionEntry>,
    val vaults: List<BackupVaultEntry>,
)

private const val BACKUP_PAYLOAD_MAGIC = "MEGA-BACKUP-PAYLOAD-V1"

private fun List<String>.encodeTags(): String = joinToString(",")

private fun String.decodeTags(): List<String> = if (isEmpty()) emptyList() else split(",")

/**
 * Encodes every saved session and multisig vault into the plaintext format
 * that gets AES-GCM encrypted for a backup file. A simple, explicit,
 * hand-rolled line format, same convention as SessionSerializer's
 * encodePayload — no external JSON dependency, and a reviewer can verify
 * the structure directly from the code here.
 */
fun encodeBackupPayload(payload: BackupPayload): ByteArray {
    val lines = mutableListOf<String>()
    lines += BACKUP_PAYLOAD_MAGIC

    lines += "SESSIONS:${payload.sessions.size}"
    payload.sessions.forEach { session ->
        lines += "SESSION-CREATED:${session.createdAtEpochMillis}"
        lines += "SESSION-LABEL:${session.label}"
        lines += "SESSION-TAGS:${session.tags.encodeTags()}"
        lines += "SESSION-CHILDSEEDINFO:${session.childSeedInfo}"
        lines += "SESSION-MNEMONIC:${session.mnemonicWords.joinToString(" ")}"
        val check = session.passphraseCheck
        lines += "SESSION-PASSCHECK:${if (check != null) "${check.salt.toHexString()}:${check.hash.toHexString()}" else ""}"
    }

    lines += "VAULTS:${payload.vaults.size}"
    payload.vaults.forEach { vault ->
        lines += "VAULT-CREATED:${vault.createdAtEpochMillis}"
        lines += "VAULT-LABEL:${vault.label}"
        lines += "VAULT-THRESHOLD:${vault.threshold}"
        lines += "VAULT-NETWORK:${vault.network.name}"
        lines += "VAULT-SCRIPTTYPE:${vault.scriptType.name}"
        lines += "VAULT-TAGS:${vault.tags.encodeTags()}"
        lines += "VAULT-COSIGNERS:${vault.cosigners.size}"
        vault.cosigners.forEach { cosigner ->
            lines += "COSIGNER-LABEL:${cosigner.label}"
            lines += "COSIGNER-FINGERPRINT:${cosigner.masterFingerprint}"
            lines += "COSIGNER-PATH:${cosigner.derivationPath}"
            lines += "COSIGNER-XPUB:${cosigner.extendedPublicKey}"
            lines += "COSIGNER-PASSPHRASEUSED:${cosigner.passphraseUsed?.toString() ?: "unknown"}"
        }
    }

    return lines.joinToString("\n").toByteArray(StandardCharsets.UTF_8)
}

/**
 * Decodes a backup payload written by [encodeBackupPayload]. Consumes
 * lines sequentially via a cursor (rather than fixed indices, since the
 * number of sessions/vaults/cosigners varies) and fails closed with
 * IllegalStateException on any mismatch — the same "never guess at a
 * malformed structure" stance as SessionSerializer.decodePayload.
 */
fun decodeBackupPayload(bytes: ByteArray): BackupPayload {
    val lines = bytes.decodeToString().split("\n")
    var cursor = 0

    fun nextLine(): String {
        if (cursor >= lines.size) throw IllegalStateException("Backup payload is truncated")
        return lines[cursor++]
    }

    fun expect(prefix: String): String {
        val line = nextLine()
        if (!line.startsWith(prefix)) {
            throw IllegalStateException("Invalid backup payload: expected a '$prefix' line, got '$line'")
        }
        return line.substringAfter(prefix)
    }

    if (nextLine() != BACKUP_PAYLOAD_MAGIC) {
        throw IllegalStateException("Invalid backup payload: bad header")
    }

    val sessionCount = expect("SESSIONS:").toIntOrNull()
        ?: throw IllegalStateException("Invalid backup payload: malformed SESSIONS count")
    val sessions = (0 until sessionCount).map {
        val created = expect("SESSION-CREATED:").toLongOrNull()
            ?: throw IllegalStateException("Invalid backup payload: malformed SESSION-CREATED")
        val label = expect("SESSION-LABEL:")
        val tags = expect("SESSION-TAGS:").decodeTags()
        val childSeedInfo = expect("SESSION-CHILDSEEDINFO:")
        val mnemonicWords = expect("SESSION-MNEMONIC:").split(" ")
        if (mnemonicWords.size != 12 && mnemonicWords.size != 24) {
            throw IllegalStateException("Invalid backup payload: mnemonic must contain 12 or 24 words, got ${mnemonicWords.size}")
        }
        val passCheckField = expect("SESSION-PASSCHECK:")
        val passphraseCheck = if (passCheckField.isEmpty()) {
            null
        } else {
            val parts = passCheckField.split(":")
            if (parts.size != 2) throw IllegalStateException("Invalid backup payload: malformed SESSION-PASSCHECK")
            try {
                PassphraseCheck(salt = parts[0].hexToByteArray(), hash = parts[1].hexToByteArray())
            } catch (e: IllegalArgumentException) {
                throw IllegalStateException("Invalid backup payload: malformed SESSION-PASSCHECK", e)
            }
        }
        BackupSessionEntry(created, label, tags, childSeedInfo, mnemonicWords, passphraseCheck)
    }

    val vaultCount = expect("VAULTS:").toIntOrNull()
        ?: throw IllegalStateException("Invalid backup payload: malformed VAULTS count")
    val vaults = (0 until vaultCount).map {
        val created = expect("VAULT-CREATED:").toLongOrNull()
            ?: throw IllegalStateException("Invalid backup payload: malformed VAULT-CREATED")
        val label = expect("VAULT-LABEL:")
        val threshold = expect("VAULT-THRESHOLD:").toIntOrNull()
            ?: throw IllegalStateException("Invalid backup payload: malformed VAULT-THRESHOLD")
        val networkName = expect("VAULT-NETWORK:")
        val network = WalletNetwork.entries.firstOrNull { it.name == networkName }
            ?: throw IllegalStateException("Invalid backup payload: unknown VAULT-NETWORK '$networkName'")
        val scriptTypeName = expect("VAULT-SCRIPTTYPE:")
        val scriptType = MultisigScriptType.entries.firstOrNull { it.name == scriptTypeName }
            ?: throw IllegalStateException("Invalid backup payload: unknown VAULT-SCRIPTTYPE '$scriptTypeName'")
        val tags = expect("VAULT-TAGS:").decodeTags()
        val cosignerCount = expect("VAULT-COSIGNERS:").toIntOrNull()
            ?: throw IllegalStateException("Invalid backup payload: malformed VAULT-COSIGNERS count")
        val cosigners = (0 until cosignerCount).map {
            val cosignerLabel = expect("COSIGNER-LABEL:")
            val fingerprint = expect("COSIGNER-FINGERPRINT:")
            val path = expect("COSIGNER-PATH:")
            val xpub = expect("COSIGNER-XPUB:")
            val passphraseUsedField = expect("COSIGNER-PASSPHRASEUSED:")
            val passphraseUsed = when (passphraseUsedField) {
                "true" -> true
                "false" -> false
                "unknown" -> null
                else -> throw IllegalStateException("Invalid backup payload: malformed COSIGNER-PASSPHRASEUSED")
            }
            BackupVaultCosignerEntry(cosignerLabel, fingerprint, path, xpub, passphraseUsed)
        }
        BackupVaultEntry(created, label, threshold, network, scriptType, tags, cosigners)
    }

    return BackupPayload(sessions, vaults)
}
