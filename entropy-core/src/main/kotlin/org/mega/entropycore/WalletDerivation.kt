package org.mega.entropycore

/** Bitcoin network to derive and format keys/addresses for. Derivation math
 * is identical either way — only the coin type in the path and the
 * version bytes/address prefixes differ. Testnet exists so a cautious
 * user can cross-check MEGA's output against another wallet before ever
 * touching a mainnet key. */
enum class WalletNetwork(internal val bip32Network: Bip32Network, internal val coinType: Long) {
    MAINNET(Bip32Network.MAINNET, coinType = 0L),
    TESTNET(Bip32Network.TESTNET, coinType = 1L),
}

/** Which account-level derivation standard (and therefore which extended-
 * public-key prefix and address format) to use. Taproot (BIP86) is
 * intentionally not included — deferred, not silently unsupported: see
 * the Advanced Mode wallet screen, which only offers these three. */
enum class WalletScriptType(
    internal val extendedKeyScriptType: ExtendedKeyScriptType,
    val displayName: String,
    val bipNumber: Int,
) {
    LEGACY(ExtendedKeyScriptType.LEGACY, "Legacy (P2PKH)", 44),
    NESTED_SEGWIT(ExtendedKeyScriptType.NESTED_SEGWIT, "Nested SegWit (P2SH-P2WPKH)", 49),
    NATIVE_SEGWIT(ExtendedKeyScriptType.NATIVE_SEGWIT, "Native SegWit (P2WPKH)", 84),
}

data class WalletAccountKeys(
    val derivationPath: String,
    /** First 4 bytes of HASH160(master pubkey), as 8 lowercase hex chars —
     * the "master fingerprint" every PSBT/descriptor-aware wallet displays
     * alongside a derivation path (the `[fingerprint/path]xpub...` output
     * descriptor format), so this account's key can be tied back to a
     * specific seed even across multiple wallets or devices. Not the same
     * as the account key's own *parent* fingerprint one level up. */
    val masterFingerprint: String,
    val extendedPublicKey: String,
    val firstReceiveAddress: String,
)

/** Shared by both deriveWalletAccountKeys (public-only) and
 * deriveWalletReceivePrivateKey (WIF export) so there is exactly one
 * implementation of "how do we get from a mnemonic to the account-level
 * key" — the two must agree on which key an address/WIF corresponds to,
 * and a second hand-written copy of this chain could quietly drift out of
 * sync with the first. */
private fun deriveAccountKey(
    mnemonicWords: List<String>,
    passphrase: String,
    scriptType: WalletScriptType,
    network: WalletNetwork,
    account: Int,
): Pair<Bip32ExtendedPrivateKey, Bip32ExtendedPrivateKey> {
    require(account >= 0 && account.toLong() < HARDENED_OFFSET) {
        "Account index must be between 0 and ${HARDENED_OFFSET - 1}, got $account"
    }

    val seed = deriveSeed(mnemonicWords, passphrase)
    val master = bip32MasterKeyFromSeed(seed.bytes)
    val purpose = scriptType.bipNumber.toLong()
    val accountKey = master
        .deriveChild(purpose, hardened = true)
        .deriveChild(network.coinType, hardened = true)
        .deriveChild(account.toLong(), hardened = true)
    return master to accountKey
}

/**
 * Derives account-level wallet keys (spec: BIP44/49/84 "m/purpose'/coin'/
 * account'") from a BIP39 mnemonic and optional passphrase: the account
 * extended public key (xpub/ypub/zpub, display + QR only — never a
 * private key) and its first external receive address ("/0/0").
 *
 * This never returns or logs any private key material — only what an xpub
 * itself already exposes (see the "exporting an xpub leaks address
 * history" warning shown alongside it in the UI). For the private key at
 * this same address, see deriveWalletReceivePrivateKey — deliberately a
 * separate, distinctly named function so nothing here can accidentally
 * start returning private material.
 */
fun deriveWalletAccountKeys(
    mnemonicWords: List<String>,
    passphrase: String,
    scriptType: WalletScriptType,
    network: WalletNetwork,
    account: Int,
): WalletAccountKeys {
    val (master, accountKey) = deriveAccountKey(mnemonicWords, passphrase, scriptType, network, account)
    val masterFingerprint = master.fingerprint().toHex()
    val extendedPublicKey = accountKey.serializeExtendedPublicKey(scriptType.extendedKeyScriptType, network.bip32Network)

    // External chain (0), first address index (0) — the conventional
    // "first receive address" shown by every wallet for verification.
    val receiveKey = accountKey.deriveChild(0, hardened = false).deriveChild(0, hardened = false)
    val pubkey = receiveKey.compressedPublicKey()
    val address = when (scriptType) {
        WalletScriptType.LEGACY -> encodeP2pkhAddress(pubkey, network.bip32Network)
        WalletScriptType.NESTED_SEGWIT -> encodeP2shP2wpkhAddress(pubkey, network.bip32Network)
        WalletScriptType.NATIVE_SEGWIT -> encodeP2wpkhAddress(pubkey, network.bip32Network)
    }

    val purpose = scriptType.bipNumber
    val path = "m/${purpose}'/${network.coinType}'/${account}'"
    return WalletAccountKeys(path, masterFingerprint, extendedPublicKey, address)
}

data class WalletReceivePrivateKey(
    /** Full path to this specific address, e.g. "m/84'/0'/0'/0/0". */
    val derivationPath: String,
    /** WIF-encoded private key for this one address only. Can spend
     * anything sent there — treat it exactly like the funds themselves. */
    val wif: String,
    /** BIP380/381-style public output descriptor for the *account*
     * (xpub-based, no private material) — safe to import into a
     * descriptor-aware wallet as a watch-only entry, e.g.
     * "wpkh([fingerprint/84'/0'/0']xpub.../0/0)". */
    val descriptor: String,
)

/**
 * Derives the private key (WIF) for the same first external receive
 * address ("/0/0") that deriveWalletAccountKeys shows the public address
 * for — an intentionally separate, distinctly named function so exposing
 * private key material is never a side effect of the ordinary public-only
 * derivation path. Callers are expected to gate this behind an explicit
 * opt-in setting and a confirmation step; this function itself performs
 * no such gating.
 */
fun deriveWalletReceivePrivateKey(
    mnemonicWords: List<String>,
    passphrase: String,
    scriptType: WalletScriptType,
    network: WalletNetwork,
    account: Int,
): WalletReceivePrivateKey {
    val (master, accountKey) = deriveAccountKey(mnemonicWords, passphrase, scriptType, network, account)
    val masterFingerprint = master.fingerprint().toHex()
    val extendedPublicKey = accountKey.serializeExtendedPublicKey(scriptType.extendedKeyScriptType, network.bip32Network)

    val receiveKey = accountKey.deriveChild(0, hardened = false).deriveChild(0, hardened = false)
    val wif = encodeWif(receiveKey.privateKey, network.bip32Network)

    val purpose = scriptType.bipNumber
    val accountOrigin = "$masterFingerprint/${purpose}'/${network.coinType}'/${account}'"
    val descriptorBody = "[$accountOrigin]$extendedPublicKey/0/0"
    val descriptor = when (scriptType) {
        WalletScriptType.LEGACY -> "pkh($descriptorBody)"
        WalletScriptType.NESTED_SEGWIT -> "sh(wpkh($descriptorBody))"
        WalletScriptType.NATIVE_SEGWIT -> "wpkh($descriptorBody)"
    }

    val path = "m/${purpose}'/${network.coinType}'/${account}'/0/0"
    return WalletReceivePrivateKey(path, wif, descriptor)
}
