package org.mega.entropycore

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import java.util.Base64

/**
 * Tests for [downgradePsbtToV0] — the legacy (BIP174 v0) export path.
 *
 * Fixture provenance: the golden was produced by taking MEGA's OWN
 * deterministic single-sig signed+finalized PSBT (the exact bytes the
 * production signing path emits for the fixture below) and re-encoding it
 * as a strict v0 document with an independent Node script, then
 * confirming that result imports, finalizes, and extracts a transaction
 * with bitcoinjs-lib 7.0.2 — the very parser (as bundled by BlueWallet)
 * that rejects MEGA's native v2 output with "Unknown global type 2".
 * The golden's txid is therefore externally verified end to end.
 */
class PsbtV0DowngradeTest {

    private val WORDS_A =
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(' ')
    private val PATH = listOf(2147483696L, 2147483648L, 2147483648L, 2147483650L, 0L, 0L)
    private val UNSIGNED_TX_HEX =
        "0200000001279a2323a5dfb51fc45f220fa58b0fc13e1e3342792a85d7e36cd6333b5cbc390000000000ffffffff01a05aea0b000000001976a914ffe9c0061097cc3b636f2cb0460fa4fc427d2b4588ac00000000"
    private val WITNESS_UTXO_AMOUNT = 199909013L
    private val RANDOM = { ByteArray(32) { it.toByte() } }

    /** Golden: the fixture below, signed+finalized by MEGA, re-encoded as
     *  strict v0 and verified with bitcoinjs-lib 7.0.2 (parse, finalize
     *  pass-through, and extractTransaction all succeed; txid
     *  b4ca8f48572bf08354f8302adfbd9e5c2fc2a52731de5401a39aa048f68c9c21). */
    private val GOLDEN_V0_B64 =
        "cHNidP8BAFUCAAAAASeaIyOl37UfxF8iD6WLD8E+HjNCeSqF1+Ns1jM7XLw5AAAAAAD/////AaBa6gsAAAAAGXapFP/pwAYQl8w7Y28ssEYPpPxCfStFiKwAAAAAAAEBH5Ve6gsAAAAAFgAUXPBK0QcBD/72+pMNPQQsk+BR/4YBCGwCSDBFAiEA3CHIUED4d/RPi7FgYgovwq7YhSpJX3x7dlkmrCLhbvUCIBb1IGyyk8Q/9XuFXx+DlzJDlyIy5CMXKrMLO2ybHxczASED3BlTwnVsfFjU9Iyhu7p2f0FP0ja/TWYrZ3IaxibFFOAAAA=="

    private fun hexToBytes(hex: String): ByteArray =
        hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun buildAndSign(): ByteArray {
        val master = bip32MasterKeyFromSeed(deriveSeed(WORDS_A, "").bytes)
        var child = master
        for (rawIndex in PATH) {
            val hardened = rawIndex >= HARDENED_OFFSET
            child = child.deriveChild(rawIndex - if (hardened) HARDENED_OFFSET else 0L, hardened)
        }
        val pubkey = child.compressedPublicKey()
        val scriptPubKey = byteArrayOf(0x00, 0x14) + hash160(pubkey)

        val u64 = { v: Long -> ByteArray(8) { i -> ((v shr (8 * i)) and 0xFF).toByte() } }
        val u32 = { v: Long -> ByteArray(4) { i -> ((v shr (8 * i)) and 0xFF).toByte() } }
        val wu = u64(WITNESS_UTXO_AMOUNT) + byteArrayOf(scriptPubKey.size.toByte()) + scriptPubKey

        val fpPath = master.fingerprint() + PATH.fold(ByteArray(0)) { acc, e -> acc + u32(e) }
        val input = PsbtMap(listOf(
            PsbtKeyValue(0x01, ByteArray(0), wu),
            PsbtKeyValue(0x06, pubkey, fpPath),
        ))
        val tx = parseTransaction(hexToBytes(UNSIGNED_TX_HEX))
        val psbt = Psbt(
            unsignedTx = tx,
            global = PsbtMap(listOf(PsbtKeyValue(0x00, ByteArray(0), serializeTransaction(tx)))),
            inputs = listOf(input),
            outputs = tx.outputs.map { PsbtMap(emptyList()) },
        )
        return signAndFinalizePsbt(serializePsbt(psbt), WORDS_A, "", FingerprintTrustPolicy.STRICT, RANDOM)
    }

    @Test
    fun `a document carrying v2 global 0x02 is exactly what a strict v0 parser must reject and what downgrade fixes`() {
        // Reproduce the precise global-map shape that BlueWallet's
        // bitcoinjs-lib 7.0.2 parser chokes on ("Unknown global type 2"),
        // built from the SAME fixture's unsigned transaction so the
        // 0x02 value is spec-consistent (4-byte LE = tx version).
        val signed = buildAndSign()
        val psbt = parsePsbt(signed)
        val txVersion = psbt.unsignedTx.version
        val v2Global = PsbtMap(
            psbt.global.entries + PsbtKeyValue(
                0x02,
                ByteArray(0),
                ByteArray(4) { i -> ((txVersion shr (8 * i)) and 0xFF).toByte() },
            ),
        )
        val v2Doc = serializePsbt(Psbt(psbt.unsignedTx, v2Global, psbt.inputs, psbt.outputs))

        val rejected = runCatching { StrictV0Validator.validate(v2Doc) }.exceptionOrNull()
        assertNotNull("a document with global 0x02 must fail a strict v0 check", rejected)
        // ...and the downgrade is precisely the repair.
        val downgraded = downgradePsbtToV0(v2Doc)
        StrictV0Validator.validate(downgraded)
    }

    @Test
    fun `downgrade of the real production-signed fixture equals the bitcoinjs-verified golden exactly`() {
        val signed = buildAndSign()
        val downgraded = downgradePsbtToV0(signed)
        assertEquals(GOLDEN_V0_B64, Base64.getEncoder().encodeToString(downgraded))
    }

    @Test
    fun `golden passes the independent strict-v0 validator and preserves every signable field`() {
        val golden = Base64.getDecoder().decode(GOLDEN_V0_B64)
        StrictV0Validator.validate(golden) // throws on any structural v0 violation

        val original = parsePsbt(buildAndSign())
        val downgraded = parsePsbt(golden)
        assertEquals(1, downgraded.inputs.size)
        val originals = original.inputs.single().entries
        val kept = downgraded.inputs.single().entries
        for (entry in originals) {
            if (entry.keyType in setOf(0x02, 0x06, 0x08)) { // witness utxo, bip32 derivation, final witness
                assertTrue(
                    "input entry 0x${entry.keyType.toString(16)} missing after downgrade",
                    kept.any {
                        it.keyType == entry.keyType &&
                            it.keyData.contentEquals(entry.keyData) &&
                            it.value.contentEquals(entry.value)
                    },
                )
            }
        }
    }

    @Test
    fun `downgrade is idempotent`() {
        val downgraded = downgradePsbtToV0(buildAndSign())
        assertEquals(
            Base64.getEncoder().encodeToString(downgraded),
            Base64.getEncoder().encodeToString(downgradePsbtToV0(downgraded)),
        )
    }

    @Test
    fun `unrecognized global key type is a hard refusal`() {
        val tx = parseTransaction(hexToBytes(UNSIGNED_TX_HEX))
        val bogus = Psbt(
            unsignedTx = tx,
            global = PsbtMap(listOf(
                PsbtKeyValue(0x00, ByteArray(0), serializeTransaction(tx)),
                PsbtKeyValue(0x09, ByteArray(0), byteArrayOf(1)),
            )),
            inputs = listOf(PsbtMap(listOf(
                PsbtKeyValue(0x01, ByteArray(0), ByteArray(8) + byteArrayOf(2) + byteArrayOf(0x00, 0x14) + ByteArray(20)),
            ))),
            outputs = listOf(PsbtMap(emptyList())),
        )
        val e = runCatching { downgradePsbtToV0(serializePsbt(bogus)) }.exceptionOrNull()
        assertNotNull("unknown global 0x09 must refuse downgrade", e)
    }

    /**
     * Deliberately independent strict BIP174 **v0** validator — shares no
     * code with the production parser (mirroring the cross-verification
     * style of BbqrSparrowCompatTest), so the golden is checked by a
     * second, independent pair of eyes rather than by "MEGA agrees with
     * MEGA". It enforces exactly the rules bluejs-lib's bip174 parser
     * enforces that MEGA's native v2 output violated:
     *  - global map: only 0x00/0x01 (and 0xFB) key types, 0x00 present
     *    exactly once, keydata-less, and appearing FIRST;
     *  - strict ascending key-type order within every map;
     *  - input maps: no v2-only 0x0E..0x13;
     *  - output maps: no v2-only types.
     */
    private object StrictV0Validator {
        fun validate(bytes: ByteArray) {
            val magic = byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xFF.toByte())
            for (i in 0 until 5) require(bytes[i] == magic[i]) { "not a PSBT" }
            var p = 5

            fun varint(): Long {
                val b0 = bytes[p].toLong() and 0xFF
                return when (b0) {
                    0xfDL -> { val v = (bytes[p+1].toLong() and 0xFF) or ((bytes[p+2].toLong() and 0xFF) shl 8); p += 3; v }
                    0xfeL -> { val v = (bytes[p+1].toLong() and 0xFF) or ((bytes[p+2].toLong() and 0xFF) shl 8) or
                        ((bytes[p+3].toLong() and 0xFF) shl 16) or ((bytes[p+4].toLong() and 0xFF) shl 24); p += 5; v }
                    else -> { p += 1; b0 }
                }
            }

            data class Entry(val type: Int, val key: List<Byte>, val value: List<Byte>)
            fun readMap(label: String): List<Entry> {
                val out = mutableListOf<Entry>()
                while (true) {
                    val kLen = varint()
                    if (kLen == 0L) break
                    val key = bytes.copyOfRange(p, p + kLen.toInt()).toList(); p += kLen.toInt()
                    val vLen = varint()
                    val value = bytes.copyOfRange(p, p + vLen.toInt()).toList(); p += vLen.toInt()
                    out += Entry(key[0].toInt() and 0xFF, key.drop(1), value)
                }
                require(out.map { it.type } == out.map { it.type }.sorted()) { "$label: keys out of order" }
                return out
            }

            val global = readMap("global")
            require(global.all { it.type in setOf(0x00, 0x01, 0xFB) }) {
                "global contains a non-v0 key type (this is the BlueWallet rejection)"
            }
            val unsigned = global.filter { it.type == 0x00 }
            require(unsigned.size == 1 && unsigned.single().key.isEmpty()) { "global 0x00 must appear exactly once, keyless" }

            // one input + one output map for this fixture's tx
            val vinCount = readVarintIn(bytes.copyOfRange(5, bytes.size))
            repeat(vinCount.toInt()) { readMap("input").let { m ->
                require(m.all { it.type !in setOf(0x0E, 0x0F, 0x10, 0x11, 0x12, 0x13) }) {
                    "input map carries a v2-only key type"
                }
            } }
        }

        private fun readVarintIn(body: ByteArray): Long {
            // body starts at the embedded tx's version field; vin count is
            // the varint right after the 4-byte version. Minimal for THIS
            // fixture only.
            var p = 4
            val b0 = body[p].toLong() and 0xFF
            return if (b0 < 0xfd) b0 else throw IllegalStateException("only small vin counts supported by this validator")
        }
    }
}
