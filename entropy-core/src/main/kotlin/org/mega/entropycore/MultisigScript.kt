package org.mega.entropycore

/**
 * Orders public keys according to BIP67 canonical sorting.
 *
 * BIP67 mandates ascending lexicographic ordering of raw compressed public key bytes to ensure
 * deterministic script construction. Bitcoin treats byte arrays as unsigned big-endian sequences,
 * so a strict unsigned comparison is required; Kotlin's default signed comparison produces
 * incorrect ordering for bytes >= 0x80. This function returns a new list without mutating the
 * input, guaranteeing that identical cosigner sets always yield identical script templates
 * regardless of original import order.
 */
internal fun sortPublicKeysBip67(pubkeys: List<ByteArray>): List<ByteArray> {
    val comparator = Comparator<ByteArray> { a, b ->
        val limit = if (a.size < b.size) a.size else b.size
        for (i in 0 until limit) {
            val diff = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (diff != 0) return@Comparator diff
        }
        a.size - b.size
    }
    return pubkeys.sortedWith(comparator)
}

/**
 * Constructs the raw Bitcoin script for a standard bare multisig template used inside P2WSH.
 *
 * The resulting byte sequence follows the canonical template: `OP_<threshold> <pubkey1> ...
 * <pubkeyN> OP_<N> OP_CHECKMULTISIG`. Opcodes for small integers (1-16) map directly to
 * `0x51`-`0x60`, pubkey pushes use the single-byte `0x21` length prefix, and termination
 * uses `0xAE`. This function trusts the caller to supply already-sorted keys. Skipping BIP67
 * sorting produces a fully valid multisig script, but it will diverge from scripts generated
 * by wallets that enforce canonical ordering, causing address mismatch rather than silent
 * corruption.
 */
internal fun buildMultisigWitnessScript(threshold: Int, sortedPublicKeys: List<ByteArray>): ByteArray {
    require(sortedPublicKeys.all { it.size == 33 }) { "Each multisig public key must be 33 bytes (compressed)" }
    require(sortedPublicKeys.size in 2..15) { "Multisig requires between 2 and 15 public keys, got ${sortedPublicKeys.size}" }
    require(threshold in 1..sortedPublicKeys.size) {
        "Multisig threshold must be between 1 and the number of public keys (${sortedPublicKeys.size}), got $threshold"
    }

    val n = sortedPublicKeys.size
    val script = ByteArray(1 + n * 34 + 2)
    var offset = 0
    script[offset++] = (0x50 + threshold).toByte()
    for (pk in sortedPublicKeys) {
        script[offset++] = 0x21
        pk.copyInto(script, offset)
        offset += 33
    }
    script[offset++] = (0x50 + n).toByte()
    script[offset++] = 0xAE.toByte()
    return script
}
