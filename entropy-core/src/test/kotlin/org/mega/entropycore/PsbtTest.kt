package org.mega.entropycore

import org.junit.Assert.*
import org.junit.Test

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

private fun String.toBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

class PsbtTest {

    companion object {
        private const val FULL_HEX = "70736274ff0100550200000001279a2323a5dfb51fc45f220fa58b0fc13e1e3342792a85d7e36cd6333b5cbc390000000000ffffffff01a05aea0b000000001976a914ffe9c0061097cc3b636f2cb0460fa4fc427d2b4588ac0000000000010120955eea0b0000000017a9146345200f68d189e1adc0df1c4d16ea8f14c0dbeb87220203b1341ccba7683b6af4f1238cd6e97e7167d569fac47f1e48d47541844355bd4646304302200424b58effaaa694e1559ea5c93bbfd4a89064224055cdf070b6771469442d07021f5c8eb0fea6516d60b8acb33ad64ede60e8785bfb3aa94b99bdf86151db9a9a010104220020771fd18ad459666dd49f3d564e3dbc42f4c84774e360ada16816a8ed488d5681010547522103b1341ccba7683b6af4f1238cd6e97e7167d569fac47f1e48d47541844355bd462103de55d1e1dac805e3f8a58c1fbf9b94c02f3dbaafe127fefca4995f26f82083bd52ae220603b1341ccba7683b6af4f1238cd6e97e7167d569fac47f1e48d47541844355bd4610b4a6ba67000000800000008004000080220603de55d1e1dac805e3f8a58c1fbf9b94c02f3dbaafe127fefca4995f26f82083bd10b4a6ba670000008000000080050000800000"
        private val FULL_BYTES = FULL_HEX.toBytes()
    }

    @Test
    fun `parsePsbt reads the official BIP174 P2SH-P2WSH multisig vector`() {
        val psbt = parsePsbt(FULL_BYTES)

        assertEquals(2L, psbt.unsignedTx.version)
        assertEquals(1, psbt.unsignedTx.inputs.size)
        assertEquals("279a2323a5dfb51fc45f220fa58b0fc13e1e3342792a85d7e36cd6333b5cbc39", psbt.unsignedTx.inputs[0].previousTxid.toHex())
        assertEquals(0L, psbt.unsignedTx.inputs[0].previousVout)
        assertTrue(psbt.unsignedTx.inputs[0].scriptSig.isEmpty())
        assertEquals(0xffffffffL, psbt.unsignedTx.inputs[0].sequence)

        assertEquals(1, psbt.unsignedTx.outputs.size)
        assertEquals(199908000L, psbt.unsignedTx.outputs[0].valueSats)
        assertEquals("76a914ffe9c0061097cc3b636f2cb0460fa4fc427d2b4588ac", psbt.unsignedTx.outputs[0].scriptPubKey.toHex())
        assertEquals(0L, psbt.unsignedTx.locktime)

        assertEquals(1, psbt.global.entries.size)
        assertEquals(1, psbt.inputs.size)
        // 6 entries, not 5 — keyType 0x06 (bip32_derivation) appears TWICE, once
        // per cosigner pubkey, not once total (see the bip32Derivations test below,
        // which already correctly expects 2 results from this same map).
        assertEquals(6, psbt.inputs[0].entries.size)
        assertEquals(0x01, psbt.inputs[0].entries[0].keyType)
        assertEquals(0x02, psbt.inputs[0].entries[1].keyType)
        assertEquals(0x04, psbt.inputs[0].entries[2].keyType)
        assertEquals(0x05, psbt.inputs[0].entries[3].keyType)
        assertEquals(0x06, psbt.inputs[0].entries[4].keyType)
        assertEquals(0x06, psbt.inputs[0].entries[5].keyType)

        assertEquals(1, psbt.outputs.size)
        assertTrue(psbt.outputs[0].entries.isEmpty())
    }

    @Test
    fun `witnessUtxo decodes the amount and scriptPubKey from input 0`() {
        val psbt = parsePsbt(FULL_BYTES)
        val wu = psbt.inputs[0].witnessUtxo()
        assertNotNull(wu)
        assertEquals(199909013L, wu!!.valueSats)
        assertEquals("a9146345200f68d189e1adc0df1c4d16ea8f14c0dbeb87", wu.scriptPubKey.toHex())
    }

    @Test
    fun `partialSigs returns the one collected signature for input 0`() {
        val psbt = parsePsbt(FULL_BYTES)
        val sigs = psbt.inputs[0].partialSigs()
        assertEquals(1, sigs.size)
        assertEquals("03b1341ccba7683b6af4f1238cd6e97e7167d569fac47f1e48d47541844355bd46", sigs[0].pubkey.toHex())
        assertEquals("304302200424b58effaaa694e1559ea5c93bbfd4a89064224055cdf070b6771469442d07021f5c8eb0fea6516d60b8acb33ad64ede60e8785bfb3aa94b99bdf86151db9a9a01", sigs[0].signature.toHex())
    }

    @Test
    fun `witnessScript returns the raw multisig script for input 0`() {
        val psbt = parsePsbt(FULL_BYTES)
        val ws = psbt.inputs[0].witnessScript()
        assertNotNull(ws)
        assertEquals("522103b1341ccba7683b6af4f1238cd6e97e7167d569fac47f1e48d47541844355bd462103de55d1e1dac805e3f8a58c1fbf9b94c02f3dbaafe127fefca4995f26f82083bd52ae", ws!!.toHex())
    }

    @Test
    fun `bip32Derivations returns both cosigner entries with their unsigned hardened path values`() {
        val psbt = parsePsbt(FULL_BYTES)
        val derivs = psbt.inputs[0].bip32Derivations()
        assertEquals(2, derivs.size)

        val d1 = derivs[0]
        assertEquals("03b1341ccba7683b6af4f1238cd6e97e7167d569fac47f1e48d47541844355bd46", d1.pubkey.toHex())
        assertEquals("b4a6ba67", d1.masterFingerprint.toHex())
        assertEquals(listOf(2147483648L, 2147483648L, 2147483652L), d1.path)

        val d2 = derivs[1]
        assertEquals("03de55d1e1dac805e3f8a58c1fbf9b94c02f3dbaafe127fefca4995f26f82083bd", d2.pubkey.toHex())
        assertEquals("b4a6ba67", d2.masterFingerprint.toHex())
        assertEquals(listOf(2147483648L, 2147483648L, 2147483653L), d2.path)
    }

    @Test
    fun `sighashType and finalScriptWitness are both null when absent`() {
        val psbt = parsePsbt(FULL_BYTES)
        assertNull(psbt.inputs[0].sighashType())
        assertNull(psbt.inputs[0].finalScriptWitness())
    }

    @Test
    fun `an empty output map's accessors all return null or empty, not throw`() {
        val psbt = parsePsbt(FULL_BYTES)
        assertNull(psbt.outputs[0].witnessUtxo())
        assertNull(psbt.outputs[0].witnessScript())
        assertTrue(psbt.outputs[0].bip32Derivations().isEmpty())
        assertTrue(psbt.outputs[0].partialSigs().isEmpty())
    }

    @Test
    fun `serializePsbt(parsePsbt(bytes)) round-trips the vector byte-for-byte`() {
        val parsed = parsePsbt(FULL_BYTES)
        val serialized = serializePsbt(parsed)
        assertEquals(FULL_HEX, serialized.toHex())
    }

    @Test
    fun `parseTransaction(serializeTransaction(tx)) round-trips the embedded unsigned tx byte-for-byte`() {
        // The unsigned tx is 85 bytes. The global map's first (only) entry has an
        // empty keyData (its key is just <keylen=0x01><keytype=0x00>, no keyData
        // bytes at all for this key type), so the prefix before the tx value is
        // just Magic(5) + keylen-byte(1) + keytype-byte(1) + vallen-byte(1) = 8 bytes.
        val expectedTxBytes = FULL_BYTES.copyOfRange(8, 93)
        val parsedTx = parseTransaction(expectedTxBytes)
        val reSerializedTx = serializeTransaction(parsedTx)
        assertEquals(expectedTxBytes.toHex(), reSerializedTx.toHex())
    }

    @Test
    fun `parsePsbt rejects data with the wrong magic bytes`() {
        val badHex = "70736274fe" + FULL_HEX.substring(10)
        assertThrows(IllegalArgumentException::class.java) { parsePsbt(badHex.toBytes()) }
    }

    @Test
    fun `parsePsbt rejects truncated data`() {
        val truncatedHex = FULL_HEX.substring(0, FULL_HEX.length - 20)
        assertThrows(IllegalArgumentException::class.java) { parsePsbt(truncatedHex.toBytes()) }
    }

    @Test
    fun `parsePsbt rejects an empty byte array`() {
        assertThrows(IllegalArgumentException::class.java) { parsePsbt(ByteArray(0)) }
    }
}
