package org.mega.entropycore

/**
 * Computes the BIP143 segwit v0 sighash preimage hash for a given transaction input.
 * This is the exact 32-byte hash that gets signed for real Bitcoin transactions.
 * Follows BIP143 strictly.
 */
fun computeSegwitSighash(
    unsignedTx: Transaction,
    inputIndex: Int,
    scriptCode: ByteArray,
    amountSats: Long,
    sighashType: Int,
): ByteArray {
    // BIP143 convention for extracting flags: mask with 0x1f to get base type,
    // check 0x80 for anyoneCanPay. This matches Bitcoin Core's reference impl.
    val anyoneCanPay = (sighashType and 0x80) != 0
    val baseType = sighashType and 0x1f

    // 1. hashPrevouts (32 bytes)
    val hashPrevouts = if (anyoneCanPay) {
        ByteArray(32)
    } else {
        // Concatenate outpoints for ALL inputs: txid (32) + vout (4 LE)
        var prevoutsBytes = ByteArray(0)
        for (inp in unsignedTx.inputs) {
            prevoutsBytes += inp.previousTxid + writeUInt32LE(inp.previousVout)
        }
        doubleSha256(prevoutsBytes)
    }

    // 2. hashSequence (32 bytes)
    val hashSequence = if (anyoneCanPay || baseType == 2 || baseType == 3) {
        ByteArray(32)
    } else {
        // Concatenate sequences for ALL inputs: 4 LE bytes each
        var seqBytes = ByteArray(0)
        for (inp in unsignedTx.inputs) {
            seqBytes += writeUInt32LE(inp.sequence)
        }
        doubleSha256(seqBytes)
    }

    // 3. outpoint (36 bytes) for the specific input being signed
    val input = unsignedTx.inputs[inputIndex]
    val outpoint = input.previousTxid + writeUInt32LE(input.previousVout)

    // 4. scriptCode is written into the preimage exactly as received — per
    // the test vectors, the CALLER already includes its own compact-size
    // length prefix (see the P2WPKH vector: scriptCode starts with 0x19,
    // the length byte for the 25-byte script that follows), so this
    // function must not add a second one.

    // 5. amount (8 bytes LE)
    val amountBytes = writeUInt64LE(amountSats)

    // 6. nSequence (4 bytes LE) for the specific input
    val sequenceBytes = writeUInt32LE(input.sequence)

    // 7. hashOutputs (32 bytes)
    fun serializeOutput(out: TxOut): ByteArray =
        writeUInt64LE(out.valueSats) + writeCompactSize(out.scriptPubKey.size.toLong()) + out.scriptPubKey

    val hashOutputs = when {
        baseType == 1 -> {
            // ALL: hash all outputs serialized as CTxOut (value 8 LE + compactSize script + script)
            var outBytes = ByteArray(0)
            for (out in unsignedTx.outputs) {
                outBytes += serializeOutput(out)
            }
            doubleSha256(outBytes)
        }
        baseType == 3 && inputIndex < unsignedTx.outputs.size -> {
            // SINGLE: hash only the output at the same index as the input
            doubleSha256(serializeOutput(unsignedTx.outputs[inputIndex]))
        }
        else -> {
            // NONE, or SINGLE with out-of-range index: 32 zero bytes
            ByteArray(32)
        }
    }

    // 8. Full preimage concatenation in exact BIP143 order
    val preimage = writeUInt32LE(unsignedTx.version) +
        hashPrevouts +
        hashSequence +
        outpoint +
        scriptCode +
        amountBytes +
        sequenceBytes +
        hashOutputs +
        writeUInt32LE(unsignedTx.locktime) +
        writeUInt32LE(sighashType.toLong())

    // 9. Return double-SHA256 of the preimage
    return doubleSha256(preimage)
}

/**
 * Reuses the existing single SHA-256 from Sha256Checksum.kt to compute double-SHA256.
 * This avoids importing java.security.MessageDigest directly in this module.
 */
private fun doubleSha256(bytes: ByteArray): ByteArray = sha256(sha256(bytes))
