package org.mega.entropycore

/** Bitcoin address encoding for the three script types MEGA's Advanced
 * Mode wallet-derivation tool supports. Every function here takes a
 * compressed public key (never a private key) — these produce display-
 * only, publicly shareable data, the same category as an xpub. */

private fun p2pkhVersionByte(network: Bip32Network): Byte = when (network) {
    Bip32Network.MAINNET -> 0x00
    Bip32Network.TESTNET -> 0x6F
}

private fun p2shVersionByte(network: Bip32Network): Byte = when (network) {
    Bip32Network.MAINNET -> 0x05
    Bip32Network.TESTNET -> 0xC4.toByte()
}

private fun segwitHrp(network: Bip32Network): String = when (network) {
    Bip32Network.MAINNET -> "bc"
    Bip32Network.TESTNET -> "tb"
}

/** Legacy P2PKH (BIP44) address: base58check(version || HASH160(pubkey)). */
internal fun encodeP2pkhAddress(compressedPublicKey: ByteArray, network: Bip32Network): String {
    val payload = byteArrayOf(p2pkhVersionByte(network)) + hash160(compressedPublicKey)
    return encodeBase58Check(payload)
}

/** Nested SegWit P2SH-P2WPKH (BIP49) address: the "redeem script" is the
 * witness-v0 program push (OP_0 <20-byte HASH160(pubkey)>), and the
 * address encodes HASH160 of THAT redeem script under the P2SH version. */
internal fun encodeP2shP2wpkhAddress(compressedPublicKey: ByteArray, network: Bip32Network): String {
    val witnessProgram = hash160(compressedPublicKey)
    val redeemScript = byteArrayOf(0x00, 0x14) + witnessProgram
    val payload = byteArrayOf(p2shVersionByte(network)) + hash160(redeemScript)
    return encodeBase58Check(payload)
}

/** Native SegWit P2WPKH (BIP84) address: bech32(HASH160(pubkey)). */
internal fun encodeP2wpkhAddress(compressedPublicKey: ByteArray, network: Bip32Network): String {
    val witnessProgram = hash160(compressedPublicKey)
    return encodeSegwitV0Address(segwitHrp(network), witnessProgram)
}
