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
    val extendedPublicKey: String,
    val firstReceiveAddress: String,
)

/**
 * Derives account-level wallet keys (spec: BIP44/49/84 "m/purpose'/coin'/
 * account'") from a BIP39 mnemonic and optional passphrase: the account
 * extended public key (xpub/ypub/zpub, display + QR only — never a
 * private key) and its first external receive address ("/0/0").
 *
 * This never returns or logs any private key material — only what an xpub
 * itself already exposes (see the "exporting an xpub leaks address
 * history" warning shown alongside it in the UI).
 */
fun deriveWalletAccountKeys(
    mnemonicWords: List<String>,
    passphrase: String,
    scriptType: WalletScriptType,
    network: WalletNetwork,
    account: Int,
): WalletAccountKeys {
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

    val path = "m/${purpose}'/${network.coinType}'/${account}'"
    return WalletAccountKeys(path, extendedPublicKey, address)
}
