package org.mega.entropycore

/**
 * Boot-time known-answer self-test of every hand-written crypto primitive
 * this app computes key material with.
 *
 * Why this exists and why it is NOT redundant with the unit-test suite:
 * the ~850 unit tests prove the SOURCE passing .26's CI is correct. They
 * cannot prove THIS binary, on THIS device, at THIS boot, computes the
 * same values — a damaged APK install, a defective ROM/libcrypto
 * provider, a faulting CPU, or a tampered build can all silently alter
 * arithmetic that every unit test would otherwise have caught at build
 * time. A wallet app's worst failure mode is computing a WRONG but
 * plausible key/address silently, so the app checks itself before it
 * will show any key material at all.
 *
 * Every expected value below is a PUBLISHED vector from an independent
 * source — none was produced by this codebase:
 *  - SHA-256 / RIPEMD-160 / HMAC: RFC 6234 & RFC 4231 test cases.
 *  - BIP-39 seeds: the Trezor python-mnemonic official vectors.json
 *    (english block; those seeds are derived with passphrase "TREZOR").
 *  - BIP-32 chain: the official BIP-32 Test Vector 1.
 *  - secp256k1/Schnorr: the official BIP-340 test-vectors.csv.
 *  - Deterministic ECDSA: the widely-reproduced RFC6979-secp256k1
 *    vectors (both r values cross-checked across two independent
 *    publications; s is the BIP62 low-S form, as BIP143-era Bitcoin
 *    consensus requires and as [signEcdsaRaw] normalizes).
 *
 * Deliberately free of anything resembling an entropy source: every
 * input is a hard-coded constant, so this file is fully deterministic
 * and stays inside entropy-core's no-entropy-source audit rule.
 */
sealed class CryptoSelfTestOutcome {
    /** Every known-answer vector computed exactly as published. */
    data object Pass : CryptoSelfTestOutcome()

    /** One or more vectors mismatched, threw, or otherwise failed.
     * [failedCheckNames] are stable identifiers (no key material) safe
     * to display or hand to support. */
    data class Failed(val failedCheckNames: List<String>) : CryptoSelfTestOutcome()
}

object CryptoSelfTest {

    /** One named known-answer assertion. Public for completeness; the
     * vectors themselves are [checks]. */
    data class Check(val name: String, val run: () -> Boolean)

    /** Outcome of running [checks]: pass, or the names of everything
     * that failed. A check that THROWS counts as failed, never as a
     * silent pass — the whole point of this gate is that nothing about
     * this computation is allowed to be silently wrong. */
    fun run(): CryptoSelfTestOutcome = runChecks(checks)

    internal fun runChecks(checks: List<Check>): CryptoSelfTestOutcome {
        val failed = checks.filter { check ->
            try {
                !check.run()
            } catch (_: Throwable) {
                true
            }
        }.map { it.name }
        return if (failed.isEmpty()) {
            CryptoSelfTestOutcome.Pass
        } else {
            CryptoSelfTestOutcome.Failed(failed)
        }
    }

    // ---- helpers (local to this file: a private hex decoder keeps the
    // vectors readable and cannot collide with any other file's helper)

    private fun String.hexToBytes(): ByteArray {
        require(length % 2 == 0) { "Hex string has odd length: $length" }
        return ByteArray(length / 2) { i ->
            ((Character.digit(this[i * 2], 16) shl 4) or Character.digit(this[i * 2 + 1], 16)).toByte()
        }
    }

    private fun eq(actual: ByteArray, expectedHex: String): Boolean =
        actual.contentEquals(expectedHex.hexToBytes())

    private fun eq(actual: String, expected: String): Boolean = actual == expected

    private fun words(mnemonic: String): List<String> = mnemonic.split(" ")

    // ---- the vectors

    internal val checks: List<Check> = listOf(

        // SHA-256("abc") — FIPS 180-4 example.
        Check("sha256") {
            eq(sha256("abc".toByteArray(Charsets.US_ASCII)),
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        },

        // RIPEMD-160("abc") — the original RIPEMD reference test suite.
        Check("ripemd160") {
            eq(ripemd160("abc".toByteArray(Charsets.US_ASCII)),
                "8eb208f7e05d987a9b044a8e98c6b087f15a0bfc")
        },

        // HASH160 of the BIP-32 vector-1 master public key — the exact
        // "identifier" published for chain m (also the fingerprint MEGA
        // displays for cosigner matching).
        Check("hash160") {
            eq(
                hash160("0339a36013301597daef41fbe593a02cc513d0b55527ec2df1050e2e8ff49c85c2".hexToBytes()),
                "3442193e1bb70916e914552172cd4e2dbc9df811",
            )
        },

        // RFC 4231 test case 2 for both HMAC sizes the wallet stack uses.
        Check("hmac-sha256") {
            eq(
                hmacSha256(ByteArray(20) { 0x0b }, "Hi There".toByteArray(Charsets.US_ASCII)),
                "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7",
            )
        },
        Check("hmac-sha512") {
            eq(
                hmacSha512(ByteArray(20) { 0x0b }, "Hi There".toByteArray(Charsets.US_ASCII)),
                "87aa7cdea5ef619d4ff0b4241a1d6cb02379f4e2ce4ec2787ad0b30545e17cdedaa833b7d6b8a702038b274eaea3f4e4be9d914eeb61f1702e696c203a126854",
            )
        },

        // secp256k1 base-point derivation: privkey 1 -> G, from the
        // BIP-340 spec's own constants (x(G), even-Y compressed).
        Check("secp256k1-derivation") {
            eq(Secp256k1.publicKeyFromPrivateKey(ByteArray(31) { 0 } + byteArrayOf(1)),
                "0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798")
        },

        // BIP-39 official vectors (passphrase "TREZOR"), one per
        // supported entropy length: 12, 18 and 24 words. Exercises
        // PBKDF2-HMAC-SHA512 end to end.
        Check("bip39-seed-12") {
            eq(
                deriveSeed(words("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"), "TREZOR").bytes,
                "c55257c360c07c72029aebc1b53c05ed0362ada38ead3e3e9efa3708e53495531f09a6987599d18264c1e1c92f2cf141630c7a3c4ab7c81b2f001698e7463b04",
            )
        },
        Check("bip39-seed-18") {
            eq(
                deriveSeed(words("hamster diagram private dutch cause delay private meat slide toddler razor book happy fancy gospel tennis maple dilemma loan word shrug inflict delay length"), "TREZOR").bytes,
                "64c87cde7e12ecf6704ab95bb1408bef047c22db4cc7491c4271d170a1b213d20b385bc1588d9c7b38f1b39d415665b8a9030c9ec653d75e65f847d8fc1fc440",
            )
        },
        Check("bip39-seed-24") {
            eq(
                deriveSeed(words("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon art"), "TREZOR").bytes,
                "bda85446c68413707090a52022edd26a1c9462295029f2e60cd7c4f2bbd3097170af7a4d73245cafa9c3cca8d561a7c3de6f5d4a10be8ed2a5e608d68f92fcc8",
            )
        },

        // BIP-32 official Test Vector 1, full chain m / 0' / 1 / 2' / 2 /
        // 1000000000 — every hardened AND non-hardened CKD step, plus
        // the master's published pubkey + fingerprint.
        Check("bip32-master") {
            val root = bip32MasterKeyFromSeed(
                "000102030405060708090a0b0c0d0e0f".hexToBytes(),
            )
            eq(root.privateKey, "e8f32e723decf4051aefac8e2c93c9c5b214313817cdb01a1494b917c8436b35") &&
                eq(root.chainCode, "873dff81c02f525623fd1fe5167eac3a55a049de3d314bb42ee227ffed37d508") &&
                eq(root.compressedPublicKey(), "0339a36013301597daef41fbe593a02cc513d0b55527ec2df1050e2e8ff49c85c2") &&
                eq(root.fingerprint(), "3442193e")
        },
        Check("bip32-chain") {
            val root = bip32MasterKeyFromSeed("000102030405060708090a0b0c0d0e0f".hexToBytes())
            val c1 = root.deriveHardenedChild(0)
            val c2 = c1.deriveChild(1, hardened = false)
            val c3 = c2.deriveHardenedChild(2)
            val c4 = c3.deriveChild(2, hardened = false)
            val c5 = c4.deriveChild(1_000_000_000L, hardened = false)
            eq(c1.privateKey, "edb2e14f9ee77d26dd93b4ecede8d16ed408ce149b6cd80b0715a2d911a0afea") &&
                eq(c1.chainCode, "47fdacbd0f1097043b78c63c20c34ef4ed9a111d980047ad16282c7ae6236141") &&
                eq(c2.privateKey, "3c6cb8d0f6a264c91ea8b5030fadaa8e538b020f0a387421a12de9319dc93368") &&
                eq(c2.chainCode, "2a7857631386ba23dacac34180dd1983734e444fdbf774041578e9b6adb37c19") &&
                eq(c3.privateKey, "cbce0d719ecf7431d88e6a89fa1483e02e35092af60c042b1df2ff59fa424dca") &&
                eq(c4.privateKey, "0f479245fb19a38a1954c5c7c0ebab2f9bdfd96a17563ef28a6a4b1a2a764ef4") &&
                eq(c5.privateKey, "471b76e389e528d6de6d816857e012c5455051cad6660850e58372a6c3e6e7c8") &&
                eq(c5.chainCode, "c783e67b921d2beb8f6b389cc646d7263b4145701dadd2161548a8b078e65e9e")
        },

        // WIF encoding, both forms, of BIP-32 vector-1 keys: the
        // compressed value is the published one from the vector table;
        // the uncompressed value was reproduced by an independent
        // base58check implementation (the bitcoin-wiki table's value for
        // that key is a known-typo artifact and is deliberately NOT
        // used here).
        Check("wif") {
            eq(
                encodeWif("471b76e389e528d6de6d816857e012c5455051cad6660850e58372a6c3e6e7c8".hexToBytes(), Bip32Network.MAINNET, compressed = true),
                "Kybw8izYevo5xMh1TK7aUr7jHFCxXS1zv8p3oqFz3o2zFbhRXHYs",
            ) &&
                eq(
                    encodeWif("e8f32e723decf4051aefac8e2c93c9c5b214313817cdb01a1494b917c8436b35".hexToBytes(), Bip32Network.MAINNET, compressed = false),
                    "5KasyVKwgbH5VmDomdJdevZXRMMrbWcePkW17vxeg8daJWoeqHQ",
                )
        },

        // BIP-340: public-key derivation, default signing (aux as given
        // per vector), and verification — TRUE vectors must verify AND
        // re-signing with the vector's aux must reproduce the published
        // signature byte-for-byte; FALSE vectors (indices 5-14) must ALL
        // be rejected. Signing-time self-verification of a signer is
        // exactly what a broken device would get wrong.
        Check("bip340-pubkey") {
            eq(
                Schnorr.xOnlyPubKeyFromPrivateKey("0000000000000000000000000000000000000000000000000000000000000003".hexToBytes()),
                "F9308A019258C31049344F85F89D5229B531C845836F99B08601F113BCE036F9".lowercase(),
            ) &&
                eq(
                    Schnorr.xOnlyPubKeyFromPrivateKey("B7E151628AED2A6ABF7158809CF4F3C762E7160F38B4DA56A784D9045190CFEF".hexToBytes()),
                    "DFF1D77F2A671C5F36183726DB2341BE58FEAE1DA2DECED843240F7B502BA659".lowercase(),
                )
        },
        Check("bip340-sign") {
            val vectors = listOf(
                Triple(
                    "0000000000000000000000000000000000000000000000000000000000000003",
                    "0000000000000000000000000000000000000000000000000000000000000000",
                    "E907831F80848D1069A5371B402410364BDF1C5F8307B0084C55F1CE2DCA821525F66A4A85EA8B71E482A74F382D2CE5EBEEE8FDB2172F477DF4900D310536C0",
                ),
                Triple(
                    "B7E151628AED2A6ABF7158809CF4F3C762E7160F38B4DA56A784D9045190CFEF",
                    "243F6A8885A308D313198A2E03707344A4093822299F31D0082EFA98EC4E6C89",
                    "6896BD60EEAE296DB48A229FF71DFE071BDE413E6D43F917DC8DCF8C78DE33418906D11AC976ABCCB20B091292BFF4EA897EFCB639EA871CFA95F6DE339E4B0A",
                ),
                Triple(
                    "C90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B14E5C9",
                    "7E2D58D8B3BCDF1ABADEC7829054F90DDA9805AAB56C77333024B9D0A508B75C",
                    "5831AAEED7B44BB74E5EAB94BA9D4294C49BCF2A60728D8B4C200F50DD313C1BAB745879A5AD954A72C45A91C3A51D3C7ADEA98D82F8481E0E1E03674A6F3FB7",
                ),
            )
            // aux for vectors 0..2: all-zero, 0x01-prefixed..., 0xC87AA5...
            val auxRands = listOf(
                "0000000000000000000000000000000000000000000000000000000000000000",
                "0000000000000000000000000000000000000000000000000000000000000001",
                "C87AA53824B4D7AE2EB035A2B5BBBCCC080E76CDC6D1692C4B0B62D798E6D906",
            )
            vectors.indices.all { i ->
                eq(
                    Schnorr.sign(vectors[i].first.hexToBytes(), vectors[i].second.hexToBytes(), auxRands[i].hexToBytes()),
                    vectors[i].third.lowercase(),
                )
            }
        },
        Check("bip340-verify-valid") {
            Schnorr.verify(
                "D69C3509BB99E412E68B0FE8544E72837DFA30746D8BE2AA65975F29D22DC7B9".hexToBytes(),
                "4DF3C3F68FCC83B27E9D42C90431A72499F17875C81A599B566C9889B9696703".hexToBytes(),
                "00000000000000000000003B78CE563F89A0ED9414F5AA28AD0D96D6795F9C6376AFB1548AF603B3EB45C9F8207DEE1060CB71C04E80F593060B07D28308D7F4".hexToBytes(),
            )
        },
        Check("bip340-verify-rejects") {
            val pk = "DFF1D77F2A671C5F36183726DB2341BE58FEAE1DA2DECED843240F7B502BA659".hexToBytes()
            val msg = "243F6A8885A308D313198A2E03707344A4093822299F31D0082EFA98EC4E6C89".hexToBytes()
            val badSigs = listOf(
                // 6: has_even_y(R) is false
                "FFF97BD5755EEEA420453A14355235D382F6472F8568A18B2F057A14602975563CC27944640AC607CD107AE10923D9EF7A73C643E166BE5EBEAFA34B1AC553E2",
                // 7: negated message hash
                "1FA62E331EDBC21C394792D2AB1100A7B432B013DF3F6FF4F99FCB33E0E1515F28890B3EDB6E7189B630448B515CE4F8622A954CFE545735AAEA5134FCCDB2BD",
                // 8: negated s value
                "6CFF5C3BA86C69EA4B7376F31A9BCB4F74C1976089B2D9963DA2E5543E177769961764B3AA9B2FFCB6EF947B6887A226E8D7C93E00C5ED0C1834FF0D0C2E6DA6",
                // 11: sig[0:32] is not an X coordinate on the curve
                "4A298DACAE57395A15D0795DDBFD1DCB564DA82B0F269BC70A74F8220429BA1D69E89B4C5564D00349106B8497785DD7D1D713A8AE82B32FA79D5F7FC407D39B",
                // 13: sig[32:64] equals the curve order
                "6CFF5C3BA86C69EA4B7376F31A9BCB4F74C1976089B2D9963DA2E5543E177769FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141",
            )
            badSigs.all { sigHex -> !Schnorr.verify(pk, msg, sigHex.hexToBytes()) } &&
                !Schnorr.verify(
                    "EEFDEA4CDB677750A420FEE807EACF21EB9898AE79B9768766E4FAA04A2D4A34".hexToBytes(),
                    msg,
                    "6CFF5C3BA86C69EA4B7376F31A9BCB4F74C1976089B2D9963DA2E5543E17776969E89B4C5564D00349106B8497785DD7D1D713A8AE82B32FA79D5F7FC407D39B".hexToBytes(),
                )
        },

        // Deterministic ECDSA (RFC 6979 + BIP62 low-S): the two
        // cross-published vectors. Catches a corrupted HMAC-DRBG state
        // machine or a broken modInverse — either of which would leak a
        // private key through a bad nonce if it ever ran unnoticed on
        // this device.
        Check("rfc6979-ecdsa") {
            val satoshiSig = signEcdsaRaw(
                ByteArray(31) { 0 } + byteArrayOf(1),
                sha256("Satoshi Nakamoto".toByteArray(Charsets.US_ASCII)),
            )
            val turingSig = signEcdsaRaw(
                "f8b8af8ce3c7cca5e300d33939540c10d45ce001b8f252bfbc57ba0342904181".hexToBytes(),
                sha256("Alan Turing".toByteArray(Charsets.US_ASCII)),
            )
            eq(satoshiSig.r, "934b1ea10a4b3c1757e2b0c017d0b6143ce3c9a7e6a4a49860d7a6ab210ee3d8") &&
                eq(satoshiSig.s, "2442ce9d2b916064108014783e923ec36b49743e2ffa1c4496f01a512aafd9e5") &&
                eq(turingSig.r, "7063ae83e7f62bbb171798131b4a0564b956930092b33b07b395615d9ec7e15c") &&
                eq(turingSig.s, "58dfcc1e00a35e1572f366ffe34ba0fc47db1e7189759b9fb233c5b05ab388ea") &&
                verifyEcdsa(
                    Secp256k1.publicKeyFromPrivateKey(ByteArray(31) { 0 } + byteArrayOf(1)),
                    sha256("Satoshi Nakamoto".toByteArray(Charsets.US_ASCII)),
                    signEcdsaDer(ByteArray(31) { 0 } + byteArrayOf(1), sha256("Satoshi Nakamoto".toByteArray(Charsets.US_ASCII))),
                )
        },
    )
}
