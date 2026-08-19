package org.mega.entropycore

/**
 * Builds an UNSIGNED PSBT from fully-specified inputs and outputs — the one
 * piece the existing PSBT infrastructure (Psbt.kt/PsbtSigning.kt/
 * PsbtFinalization.kt/PsbtWorkflow.kt) didn't have, because until now MEGA
 * only ever SIGNED a PSBT built elsewhere (Sparrow/BlueWallet on a
 * watch-only device). This module never touches a private key and never
 * signs anything — it only assembles the unsigned transaction plus the
 * witness_utxo/bip32_derivation metadata a caller of
 * [PsbtWorkflow.signAndFinalizePsbt] needs to recognize and sign its own
 * inputs, exactly the way an external coordinator's PSBT already does.
 *
 * Used by Advanced Mode's "Structure a Transaction" (UTXO split) flow, in
 * its primary form: scan an ordinary transaction built in Sparrow (or any
 * watch-only wallet tracking this seed) — which, unlike MEGA, has real
 * blockchain access and already knows exactly which UTXOs exist — harvest
 * its real inputs via [harvestOwnedInputsForStructuring] (no manual
 * txid/vout/amount entry needed), derive new destination/change addresses
 * with [deriveWalletAddress]/[deriveAddressFromExtendedPublicKey], and
 * rebuild the transaction with [restructurePsbt]. [buildUnsignedPsbt]
 * (fully manual input entry) remains available as the lower-level
 * primitive both paths share, and as a fallback when no companion wallet
 * is available to harvest from. Either way, the resulting bytes flow into
 * the SAME review → sign → result screens an externally-scanned PSBT
 * already goes through.
 */

private const val FINAL_SEQUENCE = 0xFFFFFFFFL

/** BIP125 opt-in RBF signal: any sequence number below 0xFFFFFFFE. Every
 * MEGA-constructed input uses the same value when RBF is requested, which
 * is what every wallet's "Replace-by-fee" checkbox does in practice. */
private const val RBF_SEQUENCE = 0xFFFFFFFDL

/** Everything needed to add one spendable input to a MEGA-constructed
 * PSBT. [txid] is the conventional DISPLAY-order hex string (as shown by
 * a block explorer, Sparrow's UTXO list, etc.) — this function handles
 * the byte-reversal into wire order itself, so callers never need to
 * think about that convention. [derivation] must be this device's OWN
 * key (a real fingerprint, not the 00000000 placeholder) — building an
 * input for a key this device can't sign would only produce a PSBT that
 * silently fails to finalize later. */
data class PsbtInputPlan(
    val txid: String,
    val vout: Long,
    val amountSats: Long,
    val scriptPubKey: ByteArray,
    val derivation: PsbtBip32Derivation,
)

/** One output. [changeDerivation], when present, marks this as a
 * self-owned change output — purely a display hint consumed by
 * [PsbtSummary]'s existing `isLikelyChange` heuristic; it has no bearing
 * on signing. Leave it null for every output paying an external
 * destination (including a self-split "new UTXO" output — those are new
 * receive addresses, not change, even when the wallet is the same one). */
data class PsbtOutputPlan(
    val amountSats: Long,
    val scriptPubKey: ByteArray,
    val changeDerivation: PsbtBip32Derivation? = null,
)

/**
 * Assembles a minimal, valid, UNSIGNED PSBT: one global unsigned
 * transaction (BIP174's only required global field), and per-input/
 * per-output maps carrying just enough metadata
 * ([PsbtWorkflow.signAndFinalizePsbt]) needs to sign and finalize it.
 *
 * Throws [IllegalArgumentException] for an empty input/output list or a
 * duplicate `txid:vout` outpoint (spending the same UTXO twice) — both
 * would otherwise produce a PSBT that's either malformed or spends
 * nothing, silently.
 */
fun buildUnsignedPsbt(
    inputs: List<PsbtInputPlan>,
    outputs: List<PsbtOutputPlan>,
    rbf: Boolean,
): ByteArray {
    require(inputs.isNotEmpty()) { "buildUnsignedPsbt requires at least one input" }
    require(outputs.isNotEmpty()) { "buildUnsignedPsbt requires at least one output" }

    val outpointKeys = inputs.map { "${it.txid.lowercase()}:${it.vout}" }
    require(outpointKeys.toSet().size == outpointKeys.size) {
        "Duplicate UTXO supplied more than once (same txid:vout spent twice)"
    }

    val sequence = if (rbf) RBF_SEQUENCE else FINAL_SEQUENCE
    val txIns = inputs.map { plan ->
        val txidBytes = plan.txid.hexToFixedBytes(32, "txid")
        // Display-order txid (what a human reads/pastes) is the reverse of
        // the wire-order bytes TxIn.previousTxid stores — see that field's
        // own doc comment in Transaction.kt.
        TxIn(previousTxid = txidBytes.reversedArray(), previousVout = plan.vout, scriptSig = ByteArray(0), sequence = sequence)
    }
    val txOuts = outputs.map { TxOut(requireValidSatsAmount(it.amountSats, "output amount"), it.scriptPubKey) }
    val unsignedTx = Transaction(version = 2L, inputs = txIns, outputs = txOuts, locktime = 0L)
    val unsignedTxBytes = serializeTransaction(unsignedTx)

    val inputMaps = inputs.map { plan ->
        val witnessUtxo = TxOut(requireValidSatsAmount(plan.amountSats, "input amount"), plan.scriptPubKey)
        PsbtMap(
            entries = listOf(
                PsbtKeyValue(keyType = 0x01, keyData = ByteArray(0), value = witnessUtxoValue(witnessUtxo)),
                PsbtKeyValue(keyType = 0x06, keyData = plan.derivation.pubkey, value = bip32DerivationValue(plan.derivation)),
            ),
        )
    }

    val outputMaps = outputs.map { plan ->
        val derivation = plan.changeDerivation
        PsbtMap(
            entries = if (derivation != null) {
                listOf(PsbtKeyValue(keyType = 0x02, keyData = derivation.pubkey, value = bip32DerivationValue(derivation)))
            } else {
                emptyList()
            },
        )
    }

    val psbt = Psbt(
        unsignedTx = unsignedTx,
        global = PsbtMap(listOf(PsbtKeyValue(keyType = 0x00, keyData = ByteArray(0), value = unsignedTxBytes))),
        inputs = inputMaps,
        outputs = outputMaps,
    )
    return serializePsbt(psbt)
}

private fun witnessUtxoValue(txOut: TxOut): ByteArray =
    writeUInt64LE(txOut.valueSats) + writeCompactSize(txOut.scriptPubKey.size.toLong()) + txOut.scriptPubKey

private fun bip32DerivationValue(derivation: PsbtBip32Derivation): ByteArray =
    derivation.masterFingerprint + derivation.path.fold(ByteArray(0)) { acc, element -> acc + writeUInt32LE(element) }

private fun String.hexToFixedBytes(expectedByteCount: Int, fieldName: String): ByteArray {
    val cleaned = trim()
    require(cleaned.length == expectedByteCount * 2) {
        "$fieldName must be $expectedByteCount bytes (${expectedByteCount * 2} hex chars), got ${cleaned.length} chars"
    }
    require(cleaned.all { it in "0123456789abcdefABCDEF" }) { "$fieldName must be hex" }
    return cleaned.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

// ---------------------------------------------------------------------------
// Address/script derivation for building PsbtInputPlan/PsbtOutputPlan
// ---------------------------------------------------------------------------

/** One derived native-SegWit (P2WPKH) address, with everything a caller
 * needs to build either a [PsbtInputPlan]/change [PsbtOutputPlan] (owned
 * by this seed — [derivation] proves it) or a plain destination
 * [PsbtOutputPlan] (external — just [scriptPubKey]/[address]). */
data class DerivedWalletAddress(
    val derivation: PsbtBip32Derivation,
    val scriptPubKey: ByteArray,
    val address: String,
)

/**
 * Derives the P2WPKH address at an arbitrary `(account, chain, index)`
 * from a mnemonic — generalizing [deriveWalletAccountKeys], which only
 * ever derives the conventional first receive address ("/0/0"). [chain]
 * is `0` for the external/receive chain, `1` for internal/change,
 * matching BIP44's own convention. Used both to derive the source
 * wallet's OWN receive address (to attach to a manually-entered UTXO's
 * [PsbtInputPlan]), a self-split destination address, and the change
 * address — all three are the same private-key-owned derivation, just at
 * different chain/index values.
 */
fun deriveWalletAddress(
    mnemonicWords: List<String>,
    passphrase: String,
    network: WalletNetwork,
    account: Int,
    chain: Int,
    index: Int,
): DerivedWalletAddress {
    require(chain == 0 || chain == 1) { "chain must be 0 (external/receive) or 1 (internal/change), got $chain" }
    require(index >= 0 && index.toLong() < HARDENED_OFFSET) { "index must be between 0 and ${HARDENED_OFFSET - 1}, got $index" }
    require(account >= 0 && account.toLong() < HARDENED_OFFSET) { "account must be between 0 and ${HARDENED_OFFSET - 1}, got $account" }

    val seed = deriveSeed(mnemonicWords, passphrase)
    val master = bip32MasterKeyFromSeed(seed.bytes)
    val masterFingerprint = master.fingerprint()
    val purpose = WalletScriptType.NATIVE_SEGWIT.bipNumber.toLong()
    val accountKey = master
        .deriveChild(purpose, hardened = true)
        .deriveChild(network.coinType, hardened = true)
        .deriveChild(account.toLong(), hardened = true)
    val addressKey = accountKey
        .deriveChild(chain.toLong(), hardened = false)
        .deriveChild(index.toLong(), hardened = false)
    val pubkey = addressKey.compressedPublicKey()
    val scriptPubKey = p2wpkhScript(pubkey)
    val address = encodeP2wpkhAddress(pubkey, network.bip32Network)
    val path = listOf(
        purpose + HARDENED_OFFSET,
        network.coinType + HARDENED_OFFSET,
        account.toLong() + HARDENED_OFFSET,
        chain.toLong(),
        index.toLong(),
    )
    return DerivedWalletAddress(PsbtBip32Derivation(pubkey, masterFingerprint, path), scriptPubKey, address)
}

/** Public-key-only counterpart of [deriveWalletAddress], for when the
 * Destination Wallet is a SEPARATE account (scanned or pasted xpub) this
 * device holds no private key for — CKDpub derivation only, via
 * [Bip32ExtendedPublicKey.deriveChild]. Only the native-SegWit account
 * xpub prefixes are accepted (SLIP-132 zpub/vpub, or the generic xpub/
 * tpub many wallets export regardless of script type) — a ypub/Ypub/
 * zpub-multisig-flavoured key would silently produce the WRONG address
 * type for the wallet it names, since script type is a labeling
 * convention, not something CKDpub derivation itself checks.
 */
fun deriveAddressFromExtendedPublicKey(
    extendedPublicKey: String,
    network: WalletNetwork,
    chain: Int,
    index: Int,
): DerivedWalletAddress {
    require(chain == 0 || chain == 1) { "chain must be 0 (external/receive) or 1 (internal/change), got $chain" }
    require(index >= 0 && index.toLong() < HARDENED_OFFSET) { "index must be between 0 and ${HARDENED_OFFSET - 1}, got $index" }

    val parsed = parseExtendedPublicKey(extendedPublicKey)
    require(parsed.network == network.bip32Network) {
        "Extended public key is for ${parsed.network}, but this wallet expects $network"
    }
    val allowedVersions = setOf(
        "0488b21e", "043587cf", // generic xpub/tpub — many wallets export these for any script type
        "04b24746", "045f1cf6", // SLIP-132 native-SegWit zpub/vpub
    )
    require(parsed.versionHex in allowedVersions) {
        "This extended public key's prefix doesn't match a native SegWit (BIP84) account — " +
            "paste the account's xpub/zpub, not a multisig-flavoured (ypub/Ypub/Zpub) key"
    }
    val chainKey = parsed.deriveChild(chain.toLong())
    val addressKey = chainKey.deriveChild(index.toLong())
    val pubkey = addressKey.publicKey
    val scriptPubKey = p2wpkhScript(pubkey)
    val address = encodeP2wpkhAddress(pubkey, network.bip32Network)
    // No masterFingerprint/derivationPath is knowable from an xpub alone
    // (that's exactly what makes it public-key-only) — this device never
    // signs for this address, so PsbtBip32Derivation is never needed for
    // it; DerivedWalletAddress.derivation is populated with zeroed/empty
    // placeholders purely so both derivation functions share one return
    // type, and callers for the xpub path must never read it.
    val unusedDerivation = PsbtBip32Derivation(pubkey, ByteArray(4), emptyList())
    return DerivedWalletAddress(unusedDerivation, scriptPubKey, address)
}

private fun p2wpkhScript(pubkey: ByteArray): ByteArray = byteArrayOf(0x00, 0x14) + hash160(pubkey)

// ---------------------------------------------------------------------------
// Harvesting inputs from an externally-built PSBT, to restructure its
// outputs — see harvestOwnedInputsForStructuring's own doc for why this
// exists: MEGA has no blockchain access, so it can never know which UTXOs
// exist on its own, but a PSBT built by a real watch-only wallet (Sparrow,
// which DOES have chain access) already carries every input's exact
// txid/vout/amount/scriptPubKey/derivation — the same data this file's
// buildUnsignedPsbt would otherwise need a human to type in by hand.
// ---------------------------------------------------------------------------

/** One input harvested from an externally-built PSBT that this device can
 * prove ownership of. [txIn] is carried forward exactly as parsed (wire-
 * order txid, original vout) — its scriptSig/sequence are overwritten by
 * [restructurePsbt] to build the new unsigned transaction. */
data class HarvestedPsbtInput(
    val txIn: TxIn,
    val amountSats: Long,
    val scriptPubKey: ByteArray,
    val derivation: PsbtBip32Derivation,
)

/** Every input [harvestOwnedInputsForStructuring] found, plus the single
 * account/network they all share — extracted from the inputs' own
 * derivation paths, so a caller restructuring this PSBT never needs to
 * ask the user to re-enter what a real coordinator (Sparrow) already told
 * MEGA once. */
data class HarvestedPsbtInputs(
    val inputs: List<HarvestedPsbtInput>,
    val network: WalletNetwork,
    val account: Int,
) {
    val totalAmountSats: Long get() = checkedSumSats(inputs.map { it.amountSats }, "Total harvested input amount")
}

/**
 * Extracts every input of [psbtBytes] that this device (identified by
 * [mnemonicWords]/[passphrase]) can prove ownership of, for
 * [restructurePsbt] to spend with brand-new outputs — the "Structure a
 * Transaction" flow's entry point: scan an ordinary transaction built in
 * Sparrow (or any watch-only wallet tracking this same seed), harvest its
 * real inputs here, then replace its outputs with a split/consolidation
 * plan instead of signing what Sparrow originally proposed.
 *
 * Deliberately STRICT — unlike [FingerprintTrustPolicy.ALLOW_UNKNOWN_FINGERPRINT_WITH_KEY_MATCH],
 * which single-seed PSBT *signing* may opt into, every input here must
 * carry a real (non-placeholder) master fingerprint verifiably matching
 * this device's own, cross-checked against the actual derived public key
 * along the claimed path (the fingerprint alone is advisory metadata a
 * coordinator writes, never itself proof — same reasoning as
 * [derivedPubkeyMatchesClaimed]'s other caller, signPsbt). Restructuring
 * silently discards the original outputs entirely, a higher-stakes
 * operation than plain signing, so it doesn't get the relaxed policy.
 *
 * Throws [IllegalArgumentException] if: the PSBT has no inputs; any input
 * lacks a resolvable UTXO or a derivation matching this device by both
 * fingerprint AND derived pubkey (mixed-ownership PSBTs — some inputs
 * this device owns, some it doesn't — are refused outright rather than
 * silently dropping the ones it can't sign); or the matched inputs don't
 * all share one account and network (spending across accounts in a
 * single structured transaction is refused rather than guessed at).
 */
fun harvestOwnedInputsForStructuring(psbtBytes: ByteArray, mnemonicWords: List<String>, passphrase: String): HarvestedPsbtInputs {
    val psbt = parsePsbt(psbtBytes)
    require(psbt.unsignedTx.inputs.isNotEmpty()) { "This transaction has no inputs." }

    val masterKey = bip32MasterKeyFromSeed(deriveSeed(mnemonicWords, passphrase).bytes)
    val myFingerprint = masterKey.fingerprint()

    val harvested = psbt.unsignedTx.inputs.mapIndexed { index, txIn ->
        val inputMap = psbt.inputs[index]
        val utxo = resolveInputUtxo(psbt.unsignedTx, index, inputMap)
            ?: throw IllegalArgumentException(
                "Input ${index + 1} has no witness_utxo/non_witness_utxo — MEGA cannot determine its amount or script.",
            )
        val derivation = inputMap.bip32Derivations().find { it.masterFingerprint.contentEquals(myFingerprint) }
            ?: throw IllegalArgumentException(
                "Input ${index + 1} has no BIP32 derivation matching this device's key (${myFingerprint.toHex()}) — " +
                    "every input must belong to the loaded seed for MEGA to restructure this transaction.",
            )
        if (!derivedPubkeyMatchesClaimed(derivation, masterKey)) {
            throw IllegalArgumentException(
                "Input ${index + 1}'s claimed public key does not match this device's derived key along its stated path.",
            )
        }
        HarvestedPsbtInput(txIn, utxo.valueSats, utxo.scriptPubKey, derivation)
    }

    val networks = harvested.map { h ->
        val coinType = h.derivation.path.getOrNull(1)
            ?: throw IllegalArgumentException("An input's derivation path is too short to determine its network.")
        when (coinType) {
            HARDENED_OFFSET + 0L -> WalletNetwork.MAINNET
            HARDENED_OFFSET + 1L -> WalletNetwork.TESTNET
            else -> throw IllegalArgumentException("An input's derivation path has an unrecognized coin type.")
        }
    }.toSet()
    require(networks.size == 1) { "This transaction's inputs span more than one network — cannot restructure it." }

    val accounts = harvested.map { h ->
        val accountH = h.derivation.path.getOrNull(2)
            ?: throw IllegalArgumentException("An input's derivation path is too short to determine its account.")
        require(accountH >= HARDENED_OFFSET) { "An input's derivation path account element must be hardened." }
        (accountH - HARDENED_OFFSET).toInt()
    }.toSet()
    require(accounts.size == 1) { "This transaction's inputs span more than one account — cannot restructure it." }

    return HarvestedPsbtInputs(harvested, networks.single(), accounts.single())
}

/**
 * Builds a brand-new unsigned PSBT spending EXACTLY [harvested]'s inputs
 * (carried forward with fresh scriptSig/sequence per [rbf]) to
 * [outputs] — [harvestOwnedInputsForStructuring]'s outputs entirely
 * discarding whatever the original PSBT proposed, since the whole point
 * of "Structure a Transaction" is replacing them with a split plan.
 * Shares its wire-format helpers with [buildUnsignedPsbt]; the two differ
 * only in where their inputs come from (a human-typed display-hex txid
 * there, an already-parsed [TxIn] here — no redundant hex round-trip
 * needed since [HarvestedPsbtInput.txIn] already has the correct wire-
 * order bytes from the original scan).
 */
fun restructurePsbt(harvested: HarvestedPsbtInputs, outputs: List<PsbtOutputPlan>, rbf: Boolean): ByteArray {
    require(harvested.inputs.isNotEmpty()) { "restructurePsbt requires at least one harvested input" }
    require(outputs.isNotEmpty()) { "restructurePsbt requires at least one output" }

    val outpointKeys = harvested.inputs.map { "${it.txIn.previousTxid.toHex()}:${it.txIn.previousVout}" }
    require(outpointKeys.toSet().size == outpointKeys.size) {
        "Duplicate UTXO in the source transaction (same txid:vout spent twice)"
    }

    val sequence = if (rbf) RBF_SEQUENCE else FINAL_SEQUENCE
    val txIns = harvested.inputs.map { it.txIn.copy(scriptSig = ByteArray(0), sequence = sequence) }
    val txOuts = outputs.map { TxOut(requireValidSatsAmount(it.amountSats, "output amount"), it.scriptPubKey) }
    val unsignedTx = Transaction(version = 2L, inputs = txIns, outputs = txOuts, locktime = 0L)
    val unsignedTxBytes = serializeTransaction(unsignedTx)

    val inputMaps = harvested.inputs.map { h ->
        val witnessUtxo = TxOut(requireValidSatsAmount(h.amountSats, "input amount"), h.scriptPubKey)
        PsbtMap(
            entries = listOf(
                PsbtKeyValue(keyType = 0x01, keyData = ByteArray(0), value = witnessUtxoValue(witnessUtxo)),
                PsbtKeyValue(keyType = 0x06, keyData = h.derivation.pubkey, value = bip32DerivationValue(h.derivation)),
            ),
        )
    }

    val outputMaps = outputs.map { plan ->
        val derivation = plan.changeDerivation
        PsbtMap(
            entries = if (derivation != null) {
                listOf(PsbtKeyValue(keyType = 0x02, keyData = derivation.pubkey, value = bip32DerivationValue(derivation)))
            } else {
                emptyList()
            },
        )
    }

    val psbt = Psbt(
        unsignedTx = unsignedTx,
        global = PsbtMap(listOf(PsbtKeyValue(keyType = 0x00, keyData = ByteArray(0), value = unsignedTxBytes))),
        inputs = inputMaps,
        outputs = outputMaps,
    )
    return serializePsbt(psbt)
}

// ---------------------------------------------------------------------------
// Fee estimation shared with PsbtSummary.kt's post-hoc review estimate
// ---------------------------------------------------------------------------

/**
 * Estimates the fee (in sats) for a not-yet-built all-P2WPKH split
 * transaction with [inputCount] inputs and [outputCount] outputs, using
 * the EXACT SAME weight formula [estimateTransactionVBytes] applies to an
 * already-built PSBT — so the number the split planner targets while
 * choosing how many outputs fit, and the number [PsbtSummary]'s review
 * screen displays afterward for the transaction actually built, never
 * disagree. Only the input/output COUNT matters (not real txids/scripts):
 * the formula depends purely on serialized byte lengths, and a P2WPKH
 * input/output's serialized length is fixed regardless of its content.
 */
fun estimateSplitTransactionFeeSats(inputCount: Int, outputCount: Int, feeRateSatsPerVByte: Double): Long {
    require(inputCount >= 1) { "inputCount must be at least 1" }
    require(outputCount >= 1) { "outputCount must be at least 1" }
    require(feeRateSatsPerVByte > 0.0) { "feeRateSatsPerVByte must be positive" }

    val dummyTxid = ByteArray(32)
    val dummyScriptPubKey = byteArrayOf(0x00, 0x14) + ByteArray(20) // 22 bytes, same length as any real P2WPKH output
    val dummyTx = Transaction(
        version = 2L,
        inputs = List(inputCount) { TxIn(dummyTxid, 0L, ByteArray(0), FINAL_SEQUENCE) },
        outputs = List(outputCount) { TxOut(0L, dummyScriptPubKey) },
        locktime = 0L,
    )
    val dummyInputMaps = List(inputCount) { PsbtMap(emptyList()) }
    val estimatedVBytes = estimateTransactionVBytes(dummyTx, dummyInputMaps)
    return Math.ceil(estimatedVBytes * feeRateSatsPerVByte).toLong()
}
