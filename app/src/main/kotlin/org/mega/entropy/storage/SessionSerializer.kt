package org.mega.entropy.storage

import java.nio.charset.StandardCharsets

/**
 * Encodes the sensitive session payload into the exact plaintext format required
 * before AES-GCM encryption.
 *
 * WHY: A simple, explicit, hand-rolled text format avoids external JSON dependencies
 * and allows a reviewer to verify the structure directly from the code.
 */
fun encodePayload(diceRolls: List<Int>, mnemonicWords: List<String>?): ByteArray {
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

    return lines.joinToString("\n").toByteArray(StandardCharsets.UTF_8)
}

/**
 * Decodes the plaintext payload back into dice rolls and optional mnemonic words.
 *
 * WHY: We fail closed on format mismatches. If the first line isn't exactly
 * "MEGA-SESSION-V1" or the ROLLS line is malformed, we throw IllegalStateException
 * rather than guessing at a corrupt/wrong-format payload.
 */
fun decodePayload(bytes: ByteArray): Pair<List<Int>, List<String>?> {
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
    if (lines.size > 2) {
        val mnemonicLine = lines[2]
        if (!mnemonicLine.startsWith("MNEMONIC:")) {
            throw IllegalStateException("Invalid payload format: third line must start with 'MNEMONIC:' if present")
        }
        val words = mnemonicLine.substringAfter("MNEMONIC:").split(" ")
        if (words.size != 12 && words.size != 24) {
            throw IllegalStateException("Invalid payload format: mnemonic must contain 12 or 24 words, got ${words.size}")
        }
        mnemonicWords = words
    }

    return diceRolls to mnemonicWords
}

/**
 * Encodes session metadata into the exact plaintext format for the unencrypted .meta file.
 */
fun encodeMetadata(metadata: SavedSessionMetadata): ByteArray {
    val lines = listOf(
        "MEGA-META-V1",
        "id:${metadata.id}",
        "createdAt:${metadata.createdAtEpochMillis}",
        "rollsCount:${metadata.rollsCount}",
        "hasMnemonic:${metadata.hasMnemonic}",
        "alias:${metadata.keystoreAlias}"
    )
    return lines.joinToString("\n").toByteArray(StandardCharsets.UTF_8)
}

/**
 * Decodes the unencrypted .meta file back into SavedSessionMetadata.
 *
 * WHY: We validate the header and parse strictly. A mismatched header indicates
 * a corrupted or incompatible file, so we fail closed.
 */
fun decodeMetadata(bytes: ByteArray): SavedSessionMetadata {
    val text = bytes.decodeToString()
    val lines = text.split("\n")
    require(lines.size == 6) { "Invalid metadata format: expected exactly 6 lines, got ${lines.size}" }
    require(lines[0] == "MEGA-META-V1") { "Invalid metadata format: first line must be exactly 'MEGA-META-V1'" }

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
        keystoreAlias = extractValue(5, "alias")
    )
}
