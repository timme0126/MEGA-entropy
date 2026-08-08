package org.mega.entropy.storage

import java.nio.charset.StandardCharsets
import org.mega.entropy.security.passphrase.PassphraseCheck

/**
 * The decoded contents of a session's encrypted payload.
 */
data class SessionPayload(
    val diceRolls: List<Int>,
    val mnemonicWords: List<String>?,
    val passphraseCheck: PassphraseCheck?,
)

private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

private fun String.hexToByteArray(): ByteArray {
    require(length % 2 == 0) { "Hex string must have an even length, got $length" }
    return ByteArray(length / 2) { i -> ((this[i * 2].digitToInt(16) shl 4) or this[i * 2 + 1].digitToInt(16)).toByte() }
}

/**
 * Encodes the sensitive session payload into the exact plaintext format required
 * before AES-GCM encryption.
 *
 * WHY: A simple, explicit, hand-rolled text format avoids external JSON dependencies
 * and allows a reviewer to verify the structure directly from the code.
 */
fun encodePayload(
    diceRolls: List<Int>,
    mnemonicWords: List<String>?,
    passphraseCheck: PassphraseCheck? = null,
): ByteArray {
    require(diceRolls.size in 1..100) { "diceRolls must contain between 1 and 100 entries, got ${diceRolls.size}" }
    require(diceRolls.all { it in 1..6 }) { "All dice rolls must be between 1 and 6" }
    if (mnemonicWords != null) {
        require(mnemonicWords.size == 12 || mnemonicWords.size == 24) {
            "mnemonicWords must contain 12 or 24 entries when present, got ${mnemonicWords.size}"
        }
    }

    val lines = mutableListOf<String>()
    lines.add("MEGA-SESSION-V1")
    lines.add("ROLLS:${diceRolls.joinToString(",")}")
    mnemonicWords?.let { lines.add("MNEMONIC:${it.joinToString(" ")}") }
    passphraseCheck?.let { lines.add("PASSCHECK:${it.salt.toHexString()}:${it.hash.toHexString()}") }

    return lines.joinToString("\n").toByteArray(StandardCharsets.UTF_8)
}

/**
 * Decodes the plaintext payload back into dice rolls, optional mnemonic
 * words, and an optional passphrase check.
 *
 * WHY: We fail closed on format mismatches. If the first line isn't exactly
 * "MEGA-SESSION-V1" or the ROLLS line is malformed, we throw IllegalStateException
 * rather than guessing at a corrupt/wrong-format payload. The MNEMONIC and
 * PASSCHECK lines are each independently optional and identified by their
 * prefix (not a fixed line number), so either can be present without the
 * other.
 */
fun decodePayload(bytes: ByteArray): SessionPayload {
    val text = bytes.decodeToString()
    val lines = text.split("\n")

    if (lines.isEmpty() || lines[0] != "MEGA-SESSION-V1") {
        throw IllegalStateException("Invalid payload format: first line must be exactly 'MEGA-SESSION-V1'")
    }

    val rollsLine = lines.getOrNull(1)
        ?: throw IllegalStateException("Invalid payload format: missing ROLLS line")
    if (!rollsLine.startsWith("ROLLS:")) {
        throw IllegalStateException("Invalid payload format: ROLLS line must start with 'ROLLS:'")
    }
    val rollsStr = rollsLine.substringAfter("ROLLS:")
    val diceRolls = rollsStr.split(",").map { rollStr ->
        rollStr.toIntOrNull()
            ?: throw IllegalStateException("Invalid payload format: malformed dice roll value '$rollStr'")
    }
    // Defense in depth: GCM authentication already guarantees these bytes are
    // exactly what encodePayload wrote, but re-validating the decoded values
    // against the same invariants encodePayload enforced costs nothing and
    // keeps this function safe to call on its own (e.g. future callers, or
    // a future format migration) without relying on that guarantee.
    if (diceRolls.isEmpty() || diceRolls.size > 100 || diceRolls.any { it !in 1..6 }) {
        throw IllegalStateException("Invalid payload format: dice rolls must be 1..100 entries each in 1..6")
    }

    var mnemonicWords: List<String>? = null
    var passphraseCheck: PassphraseCheck? = null
    for (line in lines.drop(2)) {
        when {
            line.startsWith("MNEMONIC:") -> {
                val words = line.substringAfter("MNEMONIC:").split(" ")
                if (words.size != 12 && words.size != 24) {
                    throw IllegalStateException("Invalid payload format: mnemonic must contain 12 or 24 words, got ${words.size}")
                }
                mnemonicWords = words
            }
            line.startsWith("PASSCHECK:") -> {
                val parts = line.substringAfter("PASSCHECK:").split(":")
                if (parts.size != 2) {
                    throw IllegalStateException("Invalid payload format: PASSCHECK line must contain exactly one ':' separator")
                }
                passphraseCheck = try {
                    PassphraseCheck(salt = parts[0].hexToByteArray(), hash = parts[1].hexToByteArray())
                } catch (e: IllegalArgumentException) {
                    throw IllegalStateException("Invalid payload format: malformed PASSCHECK line", e)
                }
            }
            else -> throw IllegalStateException("Invalid payload format: unrecognized line '$line'")
        }
    }

    return SessionPayload(diceRolls, mnemonicWords, passphraseCheck)
}

/**
 * Encodes session metadata into the exact plaintext format for the unencrypted
 * .meta file. V3 adds the hasPassphraseCheck line; there is no migration
 * from V2 (see decodeMetadata) since this predates any real saved data.
 */
fun encodeMetadata(metadata: SavedSessionMetadata): ByteArray {
    val lines = listOf(
        "MEGA-META-V3",
        "id:${metadata.id}",
        "createdAt:${metadata.createdAtEpochMillis}",
        "rollsCount:${metadata.rollsCount}",
        "hasMnemonic:${metadata.hasMnemonic}",
        "alias:${metadata.keystoreAlias}",
        "label:${metadata.label}",
        "hasPassphraseCheck:${metadata.hasPassphraseCheck}"
    )
    return lines.joinToString("\n").toByteArray(StandardCharsets.UTF_8)
}

/**
 * Decodes the unencrypted .meta file back into SavedSessionMetadata.
 *
 * WHY: We validate the header and parse strictly. A mismatched header
 * indicates a corrupted or incompatible file, so we fail closed — this
 * includes the older MEGA-META-V1 and MEGA-META-V2 formats (no
 * hasPassphraseCheck field), which are treated as unreadable rather than
 * silently guessing a default. SessionFileStore.listAllMetadata() already
 * skips (not crashes on) individual files that fail to parse, so pre-V3
 * test sessions simply stop being listed rather than breaking the app.
 */
fun decodeMetadata(bytes: ByteArray): SavedSessionMetadata {
    val text = bytes.decodeToString()
    val lines = text.split("\n")
    require(lines.size == 8) { "Invalid metadata format: expected exactly 8 lines, got ${lines.size}" }
    require(lines[0] == "MEGA-META-V3") { "Invalid metadata format: first line must be exactly 'MEGA-META-V3'" }

    fun extractValue(index: Int, key: String): String {
        val line = lines[index]
        require(line.startsWith("$key:")) { "Invalid metadata format: line $index must start with '$key:'" }
        return line.substringAfter("$key:")
    }

    return SavedSessionMetadata(
        id = extractValue(1, "id"),
        createdAtEpochMillis = extractValue(2, "createdAt").toLong(),
        rollsCount = extractValue(3, "rollsCount").toInt(),
        hasMnemonic = extractValue(4, "hasMnemonic").toBoolean(),
        keystoreAlias = extractValue(5, "alias"),
        label = extractValue(6, "label"),
        hasPassphraseCheck = extractValue(7, "hasPassphraseCheck").toBoolean(),
    )
}
