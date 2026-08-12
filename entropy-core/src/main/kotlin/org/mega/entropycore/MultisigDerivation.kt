package org.mega.entropycore

enum class MultisigScriptType(internal val bip48ScriptTypeIndex: Long, val displayName: String) {
    NATIVE_SEGWIT(2L, "Native SegWit (P2WSH)"),
}

/** Plain BIP32 extended-public-key version bytes (lowercase hex) — xpub
 * (mainnet) and tpub (testnet) — the ONLY forms this app accepts for a
 * multisig cosigner. A descriptor's own wsh() wrapper already conveys
 * script type, so a SLIP-132 ypub/zpub prefix inside one would be
 * redundant at best and misleading about what kind of key it actually is
 * at worst. Shared by both enforcement points below (parsing a pasted
 * fragment, and assembling the final descriptor) so they can't drift
 * apart into checking two different lists. */
private val PLAIN_XPUB_VERSION_HEXES = setOf("0488b21e", "043587cf")

/** Upper bound on scanned/pasted descriptor-shaped text, checked before any
 * regex runs against it. A real descriptor is self-bounding in practice: a
 * QR code physically tops out around ~4300 alphanumeric characters
 * (version 40, low error correction), and the largest legitimate MEGA
 * multisig descriptor — 15 cosigners, the maximum buildMultisigWallet
 * allows — is only a few thousand characters. Pasted text has no such
 * physical ceiling, though, so this guard exists for that path: it
 * rejects pathological input before it ever reaches
 * DESCRIPTOR_COSIGNER_REGEX.findAll or parseCosignerDescriptorFragment's
 * own regex, both of which are near-linear on well-formed input but not
 * provably so on adversarial non-matching input. 8000 characters is
 * roughly 3x the largest legitimate 15-cosigner descriptor, comfortably
 * inside any real QR's capacity, and small enough that even a worst-case
 * parse attempt completes in well under a second. */
private const val MAX_DESCRIPTOR_INPUT_LENGTH = 8000

data class MultisigCosignerAccountKeys(
    val derivationPath: String,
    val masterFingerprint: String,
    val extendedPublicKey: String,
)

fun deriveMultisigCosignerAccountKeys(
    mnemonicWords: List<String>,
    passphrase: String,
    scriptType: MultisigScriptType,
    network: WalletNetwork,
    account: Int,
): MultisigCosignerAccountKeys {
    require(account >= 0 && account.toLong() < HARDENED_OFFSET) {
        "Account index must be between 0 and ${HARDENED_OFFSET - 1}, got $account"
    }
    val seed = deriveSeed(mnemonicWords, passphrase)
    val master = bip32MasterKeyFromSeed(seed.bytes)
    val accountKey = master
        .deriveChild(48L, hardened = true)
        .deriveChild(network.coinType, hardened = true)
        .deriveChild(account.toLong(), hardened = true)
        .deriveChild(scriptType.bip48ScriptTypeIndex, hardened = true)
    val masterFingerprint = master.fingerprint().toHex()
    val extendedPublicKey = accountKey.serializeExtendedPublicKey(ExtendedKeyScriptType.LEGACY, network.bip32Network)
    val path = "m/48'/${network.coinType}'/${account}'/${scriptType.bip48ScriptTypeIndex}'"
    return MultisigCosignerAccountKeys(path, masterFingerprint, extendedPublicKey)
}

data class MultisigCosignerOrigin(
    val masterFingerprint: String,
    val derivationPath: String,
    val extendedPublicKey: String,
)

data class MultisigWallet(
    val threshold: Int,
    val cosigners: List<MultisigCosignerOrigin>,
    val network: WalletNetwork,
    val descriptor: String,
    val firstReceiveAddress: String,
)

/**
 * Assembles a multisig wallet from cosigner origin data.
 * Validates cosigner count, threshold, and network consistency per cosigner.
 * Rejects duplicate extended public keys to prevent accidental double-inclusion.
 * Computes the P2WSH address by deriving receive-index-0 keys, sorting via BIP67,
 * and encoding the witness script. The descriptor uses the BIP389 multi-path
 * shorthand (external chain 0, internal/change chain 1, wildcard index) to
 * represent both external and internal/change chains for any index.
 */
fun buildMultisigWallet(
    threshold: Int,
    cosigners: List<MultisigCosignerOrigin>,
    network: WalletNetwork,
): MultisigWallet {
    require(cosigners.size in 2..15) { "Multisig requires between 2 and 15 cosigners, got ${cosigners.size}" }
    require(threshold in 1..cosigners.size) {
        "Multisig threshold must be between 1 and the number of cosigners (${cosigners.size}), got $threshold"
    }
    require(cosigners.map { it.extendedPublicKey }.toSet().size == cosigners.size) { "Duplicate cosigner extended public key" }

    val pubkeys = cosigners.map { cosigner ->
        val parsed = parseExtendedPublicKey(cosigner.extendedPublicKey)
        require(parsed.network == network.bip32Network) {
            "Cosigner extended public key is for the wrong network (expected ${network.bip32Network}, got ${parsed.network}) for fingerprint ${cosigner.masterFingerprint}"
        }
        // Same xpub/tpub-only restriction parseCosignerDescriptorFragment already
        // enforces on a pasted fragment — repeated here because a
        // MultisigCosignerOrigin can also reach this function hand-constructed,
        // bypassing that parser entirely (as several tests below do). This is
        // the function that actually assembles the wsh(sortedmulti(...))
        // descriptor, so it's the last line of defense against a SLIP-132
        // ypub/zpub ending up embedded in one regardless of how the caller
        // got here.
        require(parsed.versionHex in PLAIN_XPUB_VERSION_HEXES) {
            "Cosigner extended public key uses an unsupported version prefix ${parsed.versionHex} for fingerprint ${cosigner.masterFingerprint} — only plain xpub/tpub are supported for multisig, not SLIP-132 keys like ypub/zpub."
        }

        // Cross-check the cosigner's derivationPath coin-type component against `network`.
        // derivationPath always carries a leading "m" component (index 0), so the coin-type
        // is index 2 (m/purpose'/coin'/...), not index 1 (which is the purpose, always "48'"
        // by the time this runs) — handle malformed or short paths safely without throwing
        // IndexOutOfBoundsException.
        val pathParts = cosigner.derivationPath.split("/")
        require(pathParts.size >= 3) {
            "Cosigner derivation path is too short to contain a coin-type component (expected at least m/purpose'/coin'/...), got: ${cosigner.derivationPath}"
        }
        val coinTypeStr = pathParts[2].removeSuffix("'")
        val coinType = try {
            coinTypeStr.toLong()
        } catch (e: NumberFormatException) {
            throw IllegalArgumentException("Cosigner derivation path coin-type component is not a valid number: ${cosigner.derivationPath}")
        }
        require(coinType == network.coinType) {
            "Cosigner derivation path coin-type (${coinType}') does not match target network (${network.coinType}'). Fingerprint: ${cosigner.masterFingerprint}"
        }
        
        parsed.deriveChild(0).deriveChild(0).publicKey
    }

    // Defense in depth: reject if two cosigners derive to the same receive public key.
    // The existing extendedPublicKey-string check above catches identical xpub text;
    // this catches identical derived keys despite different-looking xpub strings.
    val pubkeyHexes = pubkeys.map { it.toHex() }
    require(pubkeyHexes.toSet().size == pubkeyHexes.size) {
        "Two cosigners derive to the same receive public key — check for an accidentally duplicated cosigner. Fingerprints: ${cosigners.joinToString(", ") { it.masterFingerprint }}"
    }

    val sortedPubkeys = sortPublicKeysBip67(pubkeys)
    val witnessScript = buildMultisigWitnessScript(threshold, sortedPubkeys)
    val firstReceiveAddress = encodeP2wshAddress(witnessScript, network.bip32Network)
    val descriptor = "wsh(sortedmulti($threshold," +
        cosigners.joinToString(",") {
            "[${it.masterFingerprint}/${stripLeadingPathRoot(it.derivationPath)}]${it.extendedPublicKey}/<0;1>/*"
        } +
        "))"

    return MultisigWallet(threshold, cosigners, network, descriptor, firstReceiveAddress)
}

/**
 * Parses a single BIP380-style descriptor key fragment of the form [fingerprint/path]xpub...
 * or the same fragment with a BIP389 receive/change wildcard suffix, which
 * many wallets include when exporting a descriptor key.
 * Validates the extended public key by delegating to parseExtendedPublicKey, which will
 * throw clear errors for malformed input or accidental private key pastes.
 * Reconstructs the derivation path with a leading "m/" to match the storage/display
 * convention used throughout this codebase.
 *
 * Validates that the extended public key uses the correct BIP32 version bytes for multisig
 * (plain xpub/tpub), rejecting SLIP-132 variants (zpub/ypub/etc.) which are intended for
 * single-sig or older multisig conventions not supported by this app.
 *
 * Validates the captured path has exactly 4 hardened components shaped 48'/{0 or 1}'/{any}'/2'
 * per BIP48, with a distinct, specific error message for each distinct way it can be wrong.
 */
/** Normalizes h/H hardened markers (BIP-380's alternate notation, used by
 * some wallets/coordinators instead of ') to apostrophe form before any
 * further processing, so every downstream check below runs on a single
 * canonical shape. A digit immediately followed by h/H only ever means
 * "hardened marker" in a valid fragment — it cannot collide with the
 * 8-hex-character fingerprint (hex digits are 0-9a-f; h/H are not hex
 * digits), so a simple global replace is safe. */
private fun normalizeHardenedMarkers(text: String): String =
    text.replace(Regex("""(\d)[hH]"""), "$1'")

fun parseCosignerDescriptorFragment(text: String): MultisigCosignerOrigin {
    require(text.length <= MAX_DESCRIPTOR_INPUT_LENGTH) {
        "Cosigner fragment is too long (${text.length} characters, max $MAX_DESCRIPTOR_INPUT_LENGTH) — this does not look like a valid descriptor key fragment."
    }
    // The path capture group alone allows h/H alongside '; normalization is applied
    // only to that captured path substring below, never to the raw xpub — a base58
    // xpub can coincidentally contain a digit immediately followed by h/H (base58
    // includes both), so running the regex over the whole fragment would corrupt it.
    val regex = Regex("""^\[([0-9a-fA-F]{8})/([0-9'hH]+(?:/[0-9'hH]+)*)\]([A-Za-z0-9]+)(?:/<0;1>/\*)?$""")
    val match = regex.find(text)
    if (match == null) {
        if (text.startsWith('[')) {
            throw IllegalArgumentException("Invalid bracketed descriptor fragment format: $text")
        }
        throw IllegalArgumentException(
            "Expected the bracketed form [fingerprint/path]xpub..., got a bare extended public key — include the origin information.",
        )
    }

    val fingerprint = match.groupValues[1].lowercase()
    val normalizedPathComponents = normalizeHardenedMarkers(match.groupValues[2])
    val path = "m/$normalizedPathComponents"
    val xpub = match.groupValues[3]

    val parsedKey = parseExtendedPublicKey(xpub)
    
    // (a) Reject SLIP-132 keys. Only plain BIP32 xpub/tpub are allowed for multisig in this app.
    require(parsedKey.versionHex in PLAIN_XPUB_VERSION_HEXES) {
        val expected = if (parsedKey.network == Bip32Network.MAINNET) "0488b21e (xpub)" else "043587cf (tpub)"
        "Cosigner extended public key uses an unsupported version prefix ${parsedKey.versionHex} (expected $expected for multisig). SLIP-132 keys like zpub/ypub are not supported for multisig."
    }

    // (b) Validate the captured path has exactly 4 hardened components shaped 48'/{0 or 1}'/{any}'/2'
    val pathComponents = normalizedPathComponents.split("/")
    require(pathComponents.size == 4) {
        "Cosigner derivation path must have exactly 4 components (e.g., 48'/0'/0'/2'), got ${pathComponents.size}: $path"
    }

    // Check purpose component
    require(pathComponents[0] == "48'") {
        "Cosigner derivation path must start with 48' (BIP48), got ${pathComponents[0]} in: $path"
    }

    // Check coin component
    require(pathComponents[1].endsWith("'")) {
        "Cosigner derivation path coin-type component must be hardened (ends with '), got: ${pathComponents[1]} in: $path"
    }
    val coinType = try {
        pathComponents[1].removeSuffix("'").toLong()
    } catch (e: NumberFormatException) {
        throw IllegalArgumentException("Cosigner derivation path coin-type component is not a valid number: ${pathComponents[1]} in: $path")
    }
    require(coinType in listOf(0L, 1L)) {
        "Cosigner derivation path coin-type must be 0' (mainnet) or 1' (testnet), got ${pathComponents[1]} in: $path"
    }

    // Check account component: hardened, numeric, and in BIP32's valid
    // pre-hardening index range — same bound deriveMultisigCosignerAccountKeys
    // above already enforces on this device's own account index. Without
    // this, a pasted fragment claiming an account like 3000000000' (which
    // is numeric and hardened, so the two checks above alone would accept
    // it) would silently carry an index BIP32 can't actually represent.
    require(pathComponents[2].endsWith("'")) {
        "Cosigner derivation path account component must be hardened (ends with '), got: ${pathComponents[2]} in: $path"
    }
    val accountValue = try {
        pathComponents[2].removeSuffix("'").toLong()
    } catch (e: NumberFormatException) {
        throw IllegalArgumentException("Cosigner derivation path account component is not a valid number: ${pathComponents[2]} in: $path")
    }
    require(accountValue in 0 until HARDENED_OFFSET) {
        "Cosigner derivation path account component must be between 0 and ${HARDENED_OFFSET - 1}, got ${pathComponents[2]} in: $path"
    }

    // Check script_type component
    require(pathComponents[3] == "2'") {
        "Cosigner derivation path script-type component must be 2' (Native SegWit), got ${pathComponents[3]} in: $path"
    }

    return MultisigCosignerOrigin(fingerprint, path, xpub)
}

private fun stripLeadingPathRoot(path: String): String = path.removePrefix("m/")

/** Extracts the account index — the 3rd hardened component of a standard
 * BIP48 cosigner path "m/48'/coin'/{account}'/script'" — for display
 * purposes only. Returns null for anything that isn't shaped exactly like
 * that (e.g. a fully custom path entered through the "Complete Cosigner
 * Info" helper), rather than guessing; a UI showing this should treat null
 * as "not a standard account index" and simply omit it. */
fun cosignerAccountIndex(derivationPath: String): Int? {
    val components = stripLeadingPathRoot(derivationPath).split("/")
    if (components.size != 4) return null
    return components[2].removeSuffix("'").toIntOrNull()
}

/** A BARE extended public key — no [fingerprint/path] origin information
 * attached — detected while trying to fill a multisig cosigner slot from
 * scanned or pasted text. Many wallets (Sparrow included) let a user copy
 * just the xpub, which multisig needs alongside its origin fingerprint/
 * path to build a correct wsh(sortedmulti(...)) descriptor; this carries
 * exactly what was safely recoverable from the key itself — never a
 * fingerprint, which cannot be derived from an account-level xpub at all
 * (see completeBareCosignerExtendedKey) — so a "Complete Cosigner Info"
 * helper can ask the user for the rest instead of just failing. */
data class BareCosignerExtendedKey(
    val extendedPublicKey: String,
    val network: WalletNetwork,
    val isPlainXpub: Boolean,
    val displayPrefix: String,
)

/**
 * Attempts to parse [text] as a BARE extended public key: no bracketed
 * [fingerprint/path] origin, no wsh(sortedmulti(...)) wrapper. Returns null
 * for anything that isn't a plausible bare key (bracketed fragments, full
 * descriptors, or text that doesn't even base58check-decode as an extended
 * key at all), so a caller can safely try this only after
 * parseCosignerDescriptorFragment / parseMultisigDescriptor have already
 * failed, to offer the "Complete Cosigner Info" fallback instead of a raw
 * parse error.
 */
fun parseBareCosignerExtendedKey(text: String): BareCosignerExtendedKey? {
    val trimmed = text.trim()
    if (trimmed.startsWith('[') || trimmed.startsWith("wsh(")) return null
    val parsed = try {
        parseExtendedPublicKey(trimmed)
    } catch (e: IllegalArgumentException) {
        return null
    }
    val network = when (parsed.network) {
        Bip32Network.MAINNET -> WalletNetwork.MAINNET
        Bip32Network.TESTNET -> WalletNetwork.TESTNET
    }
    return BareCosignerExtendedKey(
        extendedPublicKey = trimmed,
        network = network,
        isPlainXpub = parsed.versionHex in PLAIN_XPUB_VERSION_HEXES,
        displayPrefix = extendedPublicKeyDisplayPrefix(parsed.versionHex),
    )
}

/** The default BIP48 cosigner path for [network]/[scriptType]/[account] —
 * "48'/{coin}'/{account}'/{script}'", the same shape
 * deriveMultisigCosignerAccountKeys derives for this device's own cosigner
 * key. Exposed so the "Complete Cosigner Info" helper can show/default this
 * path for a bare extended key without needing network's coinType or
 * scriptType's bip48ScriptTypeIndex itself — both internal to this
 * module. */
fun defaultCosignerDerivationPath(network: WalletNetwork, scriptType: MultisigScriptType, account: Int): String {
    require(account >= 0 && account.toLong() < HARDENED_OFFSET) {
        "Account index must be between 0 and ${HARDENED_OFFSET - 1}, got $account"
    }
    return "48'/${network.coinType}'/${account}'/${scriptType.bip48ScriptTypeIndex}'"
}

private val MASTER_FINGERPRINT_REGEX = Regex("^[0-9a-fA-F]{8}$")

/**
 * Completes a bare extended public key (no origin information) into a
 * validated [MultisigCosignerOrigin], given a user-supplied master
 * fingerprint and derivation path — never a fingerprint MEGA invents
 * itself. A BIP32 extended key only carries its own IMMEDIATE parent's
 * fingerprint (one derivation level up), not the master/root fingerprint a
 * multisig descriptor's origin needs; those are cryptographically
 * different values, and nothing in an account-level xpub can recover the
 * true master fingerprint. That is precisely why BIP380 descriptors carry
 * origin info out-of-band alongside the key instead of embedding it, and
 * why this function requires the caller to supply one rather than reading
 * or guessing it from [extendedPublicKey].
 *
 * Builds the exact same [fingerprint/path]xpub descriptor-key-fragment
 * text parseCosignerDescriptorFragment already validates pasted/scanned
 * fragments against, and runs it through that SAME function — so a
 * completed bare key gets exactly the same BIP48-shape, SLIP-132-
 * rejection, and format checks as any other cosigner input, with zero
 * duplicated validation logic.
 */
fun completeBareCosignerExtendedKey(
    masterFingerprint: String,
    derivationPath: String,
    extendedPublicKey: String,
): MultisigCosignerOrigin {
    val trimmedFingerprint = masterFingerprint.trim()
    require(MASTER_FINGERPRINT_REGEX.matches(trimmedFingerprint)) {
        "Master fingerprint must be exactly 8 hex characters, got: $trimmedFingerprint"
    }
    val fragment = "[${trimmedFingerprint.lowercase()}/${stripLeadingPathRoot(derivationPath)}]$extendedPublicKey"
    return parseCosignerDescriptorFragment(fragment)
}

data class ParsedMultisigDescriptor(
    val threshold: Int,
    val cosigners: List<MultisigCosignerOrigin>,
)

/** Matches the exact [fingerprint/path]xpub/BIP389-multipath cosigner shape
 * that buildMultisigWallet's descriptor output uses, accepting h/H alongside
 * ' in the path since parseCosignerDescriptorFragment normalizes either. */
private val DESCRIPTOR_COSIGNER_REGEX = Regex("""\[([0-9a-fA-F]{8}/[0-9'hH]+(?:/[0-9'hH]+)*)\]([A-Za-z0-9]+)/<0;1>/\*""")

/**
 * Parses a full output descriptor of the form wsh(sortedmulti(M,[fpr/path]xpub/BIP389-multipath,...))
 * into its threshold and individual cosigner fragments, delegating each fragment to
 * parseCosignerDescriptorFragment so it goes through the exact same validation
 * (SLIP-132 rejection, BIP48 path shape) as a single pasted fragment would.
 */
fun parseMultisigDescriptor(text: String): ParsedMultisigDescriptor {
    require(text.length <= MAX_DESCRIPTOR_INPUT_LENGTH) {
        "Descriptor is too long (${text.length} characters, max $MAX_DESCRIPTOR_INPUT_LENGTH) — this does not look like a valid multisig descriptor."
    }
    if (!text.startsWith("wsh(sortedmulti(") || !text.endsWith("))")) {
        throw IllegalArgumentException("Multisig descriptor must be wrapped in wsh(sortedmulti(...))")
    }

    val inner = text.removePrefix("wsh(sortedmulti(").removeSuffix("))")
    val firstCommaIndex = inner.indexOf(',')
    if (firstCommaIndex == -1) {
        throw IllegalArgumentException("Malformed multisig descriptor: missing threshold or cosigner list")
    }
    val thresholdStr = inner.substringBefore(',')
    val rest = inner.substringAfter(',')

    val threshold = try {
        thresholdStr.toInt()
    } catch (e: NumberFormatException) {
        throw IllegalArgumentException("Multisig threshold must be a valid integer, got: $thresholdStr")
    }

    val matches = DESCRIPTOR_COSIGNER_REGEX.findAll(rest).toList()
    if (matches.isEmpty()) {
        throw IllegalArgumentException("Malformed multisig descriptor: no valid cosigner fragments found")
    }
    val reconstructedCosignerList = matches.joinToString(",") { it.value }
    if (reconstructedCosignerList != rest) {
        throw IllegalArgumentException("Malformed multisig descriptor: unsupported or invalid cosigner fragment")
    }
    val cosigners = matches
        .map { match -> parseCosignerDescriptorFragment("[${match.groupValues[1]}]${match.groupValues[2]}") }
        .toList()
    if (threshold !in 1..cosigners.size) {
        throw IllegalArgumentException("Multisig threshold must be between 1 and the number of cosigners (${cosigners.size}), got $threshold")
    }

    return ParsedMultisigDescriptor(threshold, cosigners)
}
