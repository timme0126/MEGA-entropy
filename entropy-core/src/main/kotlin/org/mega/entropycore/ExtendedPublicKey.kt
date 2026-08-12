package org.mega.entropycore

import java.math.BigInteger

/**
 * A BIP32 extended public key (xpub/ypub/zpub/etc.) — the public-key-only
 * counterpart of Bip32ExtendedPrivateKey. Holds the public key and chain
 * code needed for non-hardened child derivation (CKDpub) without ever
 * touching a private key. Hardened derivation is structurally impossible
 * from here: its HMAC input is the parent's raw PRIVATE key bytes, which
 * this type never has access to — not a cryptographic hardness result,
 * just a straightforward consequence of what data is and isn't present.
 */
internal data class Bip32ExtendedPublicKey(
    val publicKey: ByteArray,
    val chainCode: ByteArray,
    val depth: Int,
    val parentFingerprint: ByteArray,
    val childNumber: Long,
    val network: Bip32Network,
    /** The exact 4-byte version prefix (lowercase hex) this key was parsed
     * from — e.g. "0488b21e" for a plain mainnet xpub, "04b24746" for a
     * SLIP-132 mainnet zpub. Unlike [network], which multiple version
     * bytes map to (xpub AND zpub both resolve to MAINNET), this lets a
     * caller tell exactly which prefix was used — needed to reject
     * SLIP-132 ypub/zpub keys pasted as multisig cosigners, since a
     * descriptor's wsh() wrapper already conveys script type and a
     * SLIP-132 prefix would claim a conflicting one. Only meaningful for
     * a freshly-parsed key; derived children carry the parent's value
     * forward unchanged since nothing re-checks it past the initial
     * cosigner-fragment parse. */
    val versionHex: String,
) {
    init {
        require(publicKey.size == 33) { "BIP32 public key must be 33 bytes" }
        require(publicKey[0] == 0x02.toByte() || publicKey[0] == 0x03.toByte()) {
            "BIP32 public key must start with 0x02 or 0x03"
        }
        require(chainCode.size == 32) { "BIP32 chain code must be 32 bytes" }
        require(parentFingerprint.size == 4) { "Parent fingerprint must be 4 bytes" }
    }

    fun fingerprint(): ByteArray = hash160(publicKey).copyOfRange(0, 4)
}

private val PUBLIC_VERSIONS = mapOf(
    "0488b21e" to Bip32Network.MAINNET,
    "049d7cb2" to Bip32Network.MAINNET,
    "0295b43f" to Bip32Network.MAINNET,
    "04b24746" to Bip32Network.MAINNET,
    "02aa7ed3" to Bip32Network.MAINNET,
    "043587cf" to Bip32Network.TESTNET,
    "044a5262" to Bip32Network.TESTNET,
    "024289ef" to Bip32Network.TESTNET,
    "045f1cf6" to Bip32Network.TESTNET,
    "02575483" to Bip32Network.TESTNET,
)

private val PRIVATE_VERSIONS = mapOf(
    "0488ade4" to Bip32Network.MAINNET,
    "049d7878" to Bip32Network.MAINNET,
    "0295b005" to Bip32Network.MAINNET,
    "04b2430c" to Bip32Network.MAINNET,
    "02aa7a99" to Bip32Network.MAINNET,
    "04358394" to Bip32Network.TESTNET,
    "044a4e28" to Bip32Network.TESTNET,
    "024285b5" to Bip32Network.TESTNET,
    "045f18bc" to Bip32Network.TESTNET,
    "02575048" to Bip32Network.TESTNET,
)

/** Same version-byte set as [PUBLIC_VERSIONS], keyed to the conventional
 * display prefix (e.g. "zpub") instead of the network — kept as its own
 * table right beside PUBLIC_VERSIONS, rather than derived from it, so the
 * two can be visually cross-checked and can never silently drift apart. */
private val PUBLIC_VERSION_DISPLAY_PREFIXES = mapOf(
    "0488b21e" to "xpub",
    "049d7cb2" to "ypub",
    "0295b43f" to "Ypub",
    "04b24746" to "zpub",
    "02aa7ed3" to "Zpub",
    "043587cf" to "tpub",
    "044a5262" to "upub",
    "024289ef" to "Upub",
    "045f1cf6" to "vpub",
    "02575483" to "Vpub",
)

/** Human-readable prefix for a parsed extended public key's version bytes
 * (e.g. "zpub", "tpub") — for display only (e.g. the "Complete Cosigner
 * Info" helper telling the user what kind of key it scanned); never
 * affects parsing or validation. */
internal fun extendedPublicKeyDisplayPrefix(versionHex: String): String =
    PUBLIC_VERSION_DISPLAY_PREFIXES[versionHex] ?: "extended public key"

/**
 * Parses a base58check-encoded BIP32 extended public key string (xpub/
 * ypub/zpub/Ypub/Zpub, mainnet or testnet) into its structural components,
 * at ANY depth — unlike decodeBip32RootXprv, which only handles root
 * (depth 0) xprv strings for BIP85. Validates the 78-byte payload,
 * resolves the network from the version bytes, and rejects private-key
 * prefixes (xprv/yprv/zprv/...) with a distinct error so a user who
 * pastes the wrong kind of key gets a clear message.
 */
internal fun parseExtendedPublicKey(text: String): Bip32ExtendedPublicKey {
    val decoded = decodeBase58Check(text)
    require(decoded.size == 78) { "Extended public key payload must be 78 bytes, got ${decoded.size}" }

    val versionHex = decoded.copyOfRange(0, 4).toHex()
    val network = PUBLIC_VERSIONS[versionHex]
        ?: if (PRIVATE_VERSIONS.containsKey(versionHex)) {
            throw IllegalArgumentException(
                "This looks like a private key (xprv/yprv/zprv), not a public key — paste an xpub/ypub/zpub instead.",
            )
        } else {
            throw IllegalArgumentException("Unrecognized extended key version bytes: $versionHex")
        }

    val depth = decoded[4].toInt() and 0xFF
    val parentFingerprint = decoded.copyOfRange(5, 9)
    val childNumber = readUInt32BigEndian(decoded, 9)
    val chainCode = decoded.copyOfRange(13, 45)
    val publicKey = decoded.copyOfRange(45, 78)

    try {
        Secp256k1.decompressPoint(publicKey)
    } catch (e: Exception) {
        throw IllegalArgumentException("Invalid public key in extended key: ${e.message}")
    }

    return Bip32ExtendedPublicKey(
        publicKey = publicKey,
        chainCode = chainCode,
        depth = depth,
        parentFingerprint = parentFingerprint,
        childNumber = childNumber,
        network = network,
        versionHex = versionHex,
    )
}

/** Inverse of writeUInt32BigEndian (Bip32.kt). Reads all four bytes as
 * Long from the start rather than assembling an Int first — a uint32 with
 * its top bit set (any HARDENED child number, e.g. every real cosigner
 * account xpub's own childNumber, which was hardened-derived) would
 * otherwise become a negative Int and then sign-extend into a negative
 * Long when converted, silently corrupting exactly the values this is
 * most likely to actually be called with. */
internal fun readUInt32BigEndian(bytes: ByteArray, offset: Int): Long {
    return ((bytes[offset].toLong() and 0xFF) shl 24) or
        ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
        ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
        (bytes[offset + 3].toLong() and 0xFF)
}

/**
 * BIP32 CKDpub: derives a non-hardened child of a public-only extended
 * key. Where CKDpriv mixes the parent's PRIVATE key into the HMAC input,
 * CKDpub mixes in the parent's PUBLIC key (compressed point) instead —
 * exactly the substitution that makes it possible to derive further
 * public child keys from an xpub alone, with no private key ever
 * involved. The child's public key is then IL·G + parentPubkeyPoint (EC
 * point addition), not IL + parentPrivateKey (modular addition) as on the
 * private side — the same relationship the BIP32 spec requires between
 * the two derivation paths so they always agree on the same child key.
 */
internal fun Bip32ExtendedPublicKey.deriveChild(index: Long): Bip32ExtendedPublicKey {
    require(index in 0 until HARDENED_OFFSET) { "Child index must be non-hardened for public derivation, got $index" }

    val data = ByteArray(37)
    publicKey.copyInto(data, destinationOffset = 0)
    writeUInt32BigEndian(index, data, 33)

    val digest = hmacSha512(chainCode, data)
    val il = digest.copyOfRange(0, 32).toPositiveBigInteger()
    require(il < Secp256k1.N) { "Invalid BIP32 child key: left half is outside curve order" }
    require(il != BigInteger.ZERO) { "Invalid BIP32 child key: zero left half" }

    val parentPoint = Secp256k1.decompressPoint(publicKey)
    val ilG = Secp256k1.scalarMultiply(il, Secp256k1.G)
    val childPoint = Secp256k1.pointAdd(ilG, parentPoint)
    require(childPoint != Secp256k1.INFINITY) { "Invalid BIP32 child key: point at infinity" }

    return Bip32ExtendedPublicKey(
        publicKey = Secp256k1.compressPoint(childPoint),
        chainCode = digest.copyOfRange(32, 64),
        depth = depth + 1,
        parentFingerprint = fingerprint(),
        childNumber = index,
        network = network,
        versionHex = versionHex,
    )
}
