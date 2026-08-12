package org.mega.entropy.storage

import java.nio.charset.StandardCharsets
import java.util.Base64
import org.mega.entropycore.MultisigScriptType
import org.mega.entropycore.WalletNetwork

/**
 * Encodes/decodes a SavedMultisigVault into the same kind of simple,
 * explicit, hand-rolled text format SessionSerializer uses for session
 * metadata — no external JSON dependency, structure verifiable directly
 * from the code. Unlike SessionSerializer's payload half, there is no
 * encryption step anywhere in this file: every field a SavedMultisigVault
 * carries is public key material by construction (see its doc comment), so
 * the plaintext written here is the entire, final on-disk representation.
 *
 * One COSIGNER line per cosigner, pipe-delimited. Free-text labels are
 * base64url-encoded before they touch the line format so labels can contain
 * punctuation, pipes, or newlines without corrupting the vault file.
 */
private fun encodeText(value: String): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.UTF_8))

private fun decodeText(value: String): String =
    String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)

fun encodeMultisigVault(vault: SavedMultisigVault): ByteArray {
    val lines = mutableListOf(
        "MEGA-MULTISIG-VAULT-V1",
        "id:${vault.id}",
        "createdAt:${vault.createdAtEpochMillis}",
        "threshold:${vault.threshold}",
        "network:${vault.network.name}",
        "scriptType:${vault.scriptType.name}",
        "cosignerCount:${vault.cosigners.size}",
    )
    vault.cosigners.forEach { cosigner ->
        val passphraseUsedText = when (cosigner.passphraseUsed) {
            true -> "true"
            false -> "false"
            null -> "unknown"
        }
        lines.add(
            "COSIGNER:${cosigner.masterFingerprint}|${cosigner.derivationPath}|${cosigner.extendedPublicKey}|$passphraseUsedText|${encodeText(cosigner.label)}",
        )
    }
    lines.add("label64:${encodeText(vault.label)}")
    return lines.joinToString("\n").toByteArray(StandardCharsets.UTF_8)
}

/**
 * Decodes a vault file back into a SavedMultisigVault. Fails closed
 * (throws IllegalArgumentException) on any structural mismatch — a corrupt
 * or foreign-format file must never be silently misinterpreted.
 * MultisigVaultFileStore.listAllVaults() is what turns that failure into
 * "skip this one file" so one corrupt vault can't break the whole list.
 */
fun decodeMultisigVault(bytes: ByteArray): SavedMultisigVault {
    val text = bytes.decodeToString()
    val lines = text.split("\n")

    require(lines.size >= 8) { "Invalid multisig vault format: expected at least 8 lines, got ${lines.size}" }
    require(lines[0] == "MEGA-MULTISIG-VAULT-V1") { "Invalid multisig vault format: unrecognized header '${lines[0]}'" }

    fun extractValue(index: Int, key: String): String {
        val line = lines[index]
        require(line.startsWith("$key:")) { "Invalid multisig vault format: line $index must start with '$key:'" }
        return line.substringAfter("$key:")
    }

    val id = extractValue(1, "id")
    val createdAt = extractValue(2, "createdAt").toLongOrNull()
        ?: throw IllegalArgumentException("Invalid multisig vault format: malformed createdAt")
    val threshold = extractValue(3, "threshold").toIntOrNull()
        ?: throw IllegalArgumentException("Invalid multisig vault format: malformed threshold")
    val network = runCatching { WalletNetwork.valueOf(extractValue(4, "network")) }.getOrNull()
        ?: throw IllegalArgumentException("Invalid multisig vault format: unrecognized network")
    val scriptType = runCatching { MultisigScriptType.valueOf(extractValue(5, "scriptType")) }.getOrNull()
        ?: throw IllegalArgumentException("Invalid multisig vault format: unrecognized scriptType")
    val cosignerCount = extractValue(6, "cosignerCount").toIntOrNull()
        ?: throw IllegalArgumentException("Invalid multisig vault format: malformed cosignerCount")

    require(lines.size == 8 + cosignerCount) {
        "Invalid multisig vault format: expected ${8 + cosignerCount} lines for $cosignerCount cosigner(s), got ${lines.size}"
    }

    val cosigners = (0 until cosignerCount).map { i ->
        val line = lines[7 + i]
        require(line.startsWith("COSIGNER:")) { "Invalid multisig vault format: line ${7 + i} must start with 'COSIGNER:'" }
        val parts = line.substringAfter("COSIGNER:").split("|")
        require(parts.size == 4 || parts.size == 5) { "Invalid multisig vault format: COSIGNER line must have 4 or 5 '|'-delimited fields" }
        val passphraseUsed = when (parts[3]) {
            "true" -> true
            "false" -> false
            "unknown" -> null
            else -> throw IllegalArgumentException("Invalid multisig vault format: unrecognized passphraseUsed value '${parts[3]}'")
        }
        SavedMultisigCosigner(
            label = parts.getOrNull(4)?.let(::decodeText) ?: "${parts[0]} · ${parts[1]}",
            masterFingerprint = parts[0],
            derivationPath = parts[1],
            extendedPublicKey = parts[2],
            passphraseUsed = passphraseUsed,
        )
    }

    val labelLineIndex = 7 + cosignerCount
    val label = when {
        lines[labelLineIndex].startsWith("label64:") -> decodeText(lines[labelLineIndex].substringAfter("label64:"))
        lines[labelLineIndex].startsWith("label:") -> extractValue(labelLineIndex, "label")
        else -> throw IllegalArgumentException("Invalid multisig vault format: line $labelLineIndex must start with 'label64:'")
    }

    return SavedMultisigVault(
        id = id,
        createdAtEpochMillis = createdAt,
        label = label,
        threshold = threshold,
        network = network,
        scriptType = scriptType,
        cosigners = cosigners,
    )
}
