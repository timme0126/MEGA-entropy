package org.mega.entropycore

/**
 * Wallet Import Format: base58check(version || 32-byte private key ||
 * [0x01 if compressed]). Categorically more sensitive than anything else
 * this module exposes — an xpub leaks address history, but a WIF (or the
 * raw key inside it) can spend whatever funds are sent to that one
 * address, with nothing else required. Only ever called from the
 * Advanced Mode private-key-export path, which is off by default and
 * gated behind its own explicit settings toggle and confirmation dialog
 * in the UI — this function itself has no awareness of that gating, it's
 * purely the encoding.
 */
private fun wifVersionByte(network: Bip32Network): Byte = when (network) {
    Bip32Network.MAINNET -> 0x80.toByte()
    Bip32Network.TESTNET -> 0xEF.toByte()
}

internal fun encodeWif(privateKey: ByteArray, network: Bip32Network, compressed: Boolean = true): String {
    require(privateKey.size == 32) { "Private key must be 32 bytes, got ${privateKey.size}" }
    val payload = if (compressed) {
        byteArrayOf(wifVersionByte(network)) + privateKey + byteArrayOf(0x01)
    } else {
        byteArrayOf(wifVersionByte(network)) + privateKey
    }
    return encodeBase58Check(payload)
}
