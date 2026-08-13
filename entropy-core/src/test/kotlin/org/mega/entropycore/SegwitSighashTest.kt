package org.mega.entropycore

import org.junit.Test
import org.junit.Assert.*

class SegwitSighashTest {
    private fun hexToBytes(hex: String): ByteArray = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    @Test
    fun `computeSegwitSighash matches BIP143's own official P2WPKH SIGHASH_ALL vector`() {
        val txHex = "0100000002fff7f7881a8099afa6940d42d1e7f6362bec38171ea3edf433541db4e4ad969f0000000000eeffffffef51e1b804cc89d182d279655c3aa89e815b1b309fe287d9b2b55d57b90ec68a0100000000ffffffff02202cb206000000001976a9148280b37df378db99f66f85c95a783a76ac7a6d5988ac9093510d000000001976a9143bde42dbee7e4dbe6a21b2d50ce2f0167faa815988ac11000000"
        val tx = parseTransaction(hexToBytes(txHex))
        val scriptCode = hexToBytes("1976a9141d0f172a0ecb48aee1be1f2687d2963ae33f71a188ac")
        val result = computeSegwitSighash(tx, 1, scriptCode, 600000000, 1)
        assertEquals("c37af31116d1b27caf68aae9e3ac82f1477929014d5b917657d0eb49478cb670", bytesToHex(result))
    }

    @Test
    fun `computeSegwitSighash matches the ALL plus ANYONECANPAY variant (Vector 2)`() {
        val txHex = "0100000002fff7f7881a8099afa6940d42d1e7f6362bec38171ea3edf433541db4e4ad969f0000000000eeffffffef51e1b804cc89d182d279655c3aa89e815b1b309fe287d9b2b55d57b90ec68a0100000000ffffffff02202cb206000000001976a9148280b37df378db99f66f85c95a783a76ac7a6d5988ac9093510d000000001976a9143bde42dbee7e4dbe6a21b2d50ce2f0167faa815988ac11000000"
        val tx = parseTransaction(hexToBytes(txHex))
        val scriptCode = hexToBytes("1976a9141d0f172a0ecb48aee1be1f2687d2963ae33f71a188ac")
        val result = computeSegwitSighash(tx, 1, scriptCode, 600000000, 0x81)
        assertEquals("fc5b6bbc855883bcfdaefb77071740ccde4929f15e6a13286584e779b2529d91", bytesToHex(result))
    }

    @Test
    fun `computeSegwitSighash matches the NONE variant (Vector 3)`() {
        val txHex = "0100000002fff7f7881a8099afa6940d42d1e7f6362bec38171ea3edf433541db4e4ad969f0000000000eeffffffef51e1b804cc89d182d279655c3aa89e815b1b309fe287d9b2b55d57b90ec68a0100000000ffffffff02202cb206000000001976a9148280b37df378db99f66f85c95a783a76ac7a6d5988ac9093510d000000001976a9143bde42dbee7e4dbe6a21b2d50ce2f0167faa815988ac11000000"
        val tx = parseTransaction(hexToBytes(txHex))
        val scriptCode = hexToBytes("1976a9141d0f172a0ecb48aee1be1f2687d2963ae33f71a188ac")
        val result = computeSegwitSighash(tx, 1, scriptCode, 600000000, 2)
        assertEquals("6ff11a9b87fb510a3a31af006bd3811b632f8a39d88a2bfda49cee203dcc356e", bytesToHex(result))
    }

    @Test
    fun `computeSegwitSighash matches the SINGLE variant (Vector 4)`() {
        val txHex = "0100000002fff7f7881a8099afa6940d42d1e7f6362bec38171ea3edf433541db4e4ad969f0000000000eeffffffef51e1b804cc89d182d279655c3aa89e815b1b309fe287d9b2b55d57b90ec68a0100000000ffffffff02202cb206000000001976a9148280b37df378db99f66f85c95a783a76ac7a6d5988ac9093510d000000001976a9143bde42dbee7e4dbe6a21b2d50ce2f0167faa815988ac11000000"
        val tx = parseTransaction(hexToBytes(txHex))
        val scriptCode = hexToBytes("1976a9141d0f172a0ecb48aee1be1f2687d2963ae33f71a188ac")
        val result = computeSegwitSighash(tx, 1, scriptCode, 600000000, 3)
        assertEquals("f4fe57286dd2ca8ac0e3dfccd54c352fcdcacbed80f194e264b75d7a7c74e4ce", bytesToHex(result))
    }

    @Test
    fun `computeSegwitSighash matches the NONE plus ANYONECANPAY variant (Vector 5)`() {
        val txHex = "0100000002fff7f7881a8099afa6940d42d1e7f6362bec38171ea3edf433541db4e4ad969f0000000000eeffffffef51e1b804cc89d182d279655c3aa89e815b1b309fe287d9b2b55d57b90ec68a0100000000ffffffff02202cb206000000001976a9148280b37df378db99f66f85c95a783a76ac7a6d5988ac9093510d000000001976a9143bde42dbee7e4dbe6a21b2d50ce2f0167faa815988ac11000000"
        val tx = parseTransaction(hexToBytes(txHex))
        val scriptCode = hexToBytes("1976a9141d0f172a0ecb48aee1be1f2687d2963ae33f71a188ac")
        val result = computeSegwitSighash(tx, 1, scriptCode, 600000000, 0x82)
        assertEquals("4abb5ef58a968f8e1ab88a9fb72f2ce74b3022e65d334ac7b8aeda747515dc15", bytesToHex(result))
    }

    @Test
    fun `computeSegwitSighash matches a real P2WSH native multisig vector (Vector 6)`() {
        val txHex = "0200000001279a2323a5dfb51fc45f220fa58b0fc13e1e3342792a85d7e36cd6333b5cbc390000000000ffffffff01a05aea0b000000001976a914ffe9c0061097cc3b636f2cb0460fa4fc427d2b4588ac00000000"
        val tx = parseTransaction(hexToBytes(txHex))
        val scriptCode = hexToBytes("47522103b1341ccba7683b6af4f1238cd6e97e7167d569fac47f1e48d47541844355bd462103de55d1e1dac805e3f8a58c1fbf9b94c02f3dbaafe127fefca4995f26f82083bd52ae")
        val result = computeSegwitSighash(tx, 0, scriptCode, 199909013, 1)
        assertEquals("768adbe5e70db1200ef6c6275b3006fda0577f83905854cf3669ff3ea3137848", bytesToHex(result))
    }

    @Test
    fun `changing an unrelated input's sequence changes the SIGHASH_ALL result but not an ANYONECANPAY result`() {
        val txHex = "0100000002fff7f7881a8099afa6940d42d1e7f6362bec38171ea3edf433541db4e4ad969f0000000000eeffffffef51e1b804cc89d182d279655c3aa89e815b1b309fe287d9b2b55d57b90ec68a0100000000ffffffff02202cb206000000001976a9148280b37df378db99f66f85c95a783a76ac7a6d5988ac9093510d000000001976a9143bde42dbee7e4dbe6a21b2d50ce2f0167faa815988ac11000000"
        val originalTx = parseTransaction(hexToBytes(txHex))
        val scriptCode = hexToBytes("1976a9141d0f172a0ecb48aee1be1f2687d2963ae33f71a188ac")

        // Modify input 0's sequence to 0, leave input 1 unchanged
        val modifiedTx = originalTx.copy(
            inputs = originalTx.inputs.mapIndexed { i, inp ->
                if (i == 0) inp.copy(sequence = 0) else inp
            }
        )

        val allOriginal = computeSegwitSighash(originalTx, 1, scriptCode, 600000000, 1)
        val allModified = computeSegwitSighash(modifiedTx, 1, scriptCode, 600000000, 1)
        assertNotEquals("SIGHASH_ALL should commit to all inputs' sequences", bytesToHex(allOriginal), bytesToHex(allModified))

        val anyAllOriginal = computeSegwitSighash(originalTx, 1, scriptCode, 600000000, 0x81)
        val anyAllModified = computeSegwitSighash(modifiedTx, 1, scriptCode, 600000000, 0x81)
        assertEquals("SIGHASH_ALL|ANYONECANPAY should ignore other inputs' sequences", bytesToHex(anyAllOriginal), bytesToHex(anyAllModified))
    }

    @Test
    fun `SIGHASH_NONE result is unaffected by changing an output`() {
        val txHex = "0100000002fff7f7881a8099afa6940d42d1e7f6362bec38171ea3edf433541db4e4ad969f0000000000eeffffffef51e1b804cc89d182d279655c3aa89e815b1b309fe287d9b2b55d57b90ec68a0100000000ffffffff02202cb206000000001976a9148280b37df378db99f66f85c95a783a76ac7a6d5988ac9093510d000000001976a9143bde42dbee7e4dbe6a21b2d50ce2f0167faa815988ac11000000"
        val originalTx = parseTransaction(hexToBytes(txHex))
        val scriptCode = hexToBytes("1976a9141d0f172a0ecb48aee1be1f2687d2963ae33f71a188ac")

        // Modify output 0's valueSats, leave everything else the same
        val modifiedTx = originalTx.copy(
            outputs = originalTx.outputs.mapIndexed { i, out ->
                if (i == 0) out.copy(valueSats = 123456789) else out
            }
        )

        val originalResult = computeSegwitSighash(originalTx, 1, scriptCode, 600000000, 2)
        val modifiedResult = computeSegwitSighash(modifiedTx, 1, scriptCode, 600000000, 2)
        assertEquals("SIGHASH_NONE should ignore all outputs", bytesToHex(originalResult), bytesToHex(modifiedResult))
    }

    @Test
    fun `computeSegwitSighash always returns exactly 32 bytes`() {
        val txHex1 = "0100000002fff7f7881a8099afa6940d42d1e7f6362bec38171ea3edf433541db4e4ad969f0000000000eeffffffef51e1b804cc89d182d279655c3aa89e815b1b309fe287d9b2b55d57b90ec68a0100000000ffffffff02202cb206000000001976a9148280b37df378db99f66f85c95a783a76ac7a6d5988ac9093510d000000001976a9143bde42dbee7e4dbe6a21b2d50ce2f0167faa815988ac11000000"
        val tx1 = parseTransaction(hexToBytes(txHex1))
        val scriptCode1 = hexToBytes("1976a9141d0f172a0ecb48aee1be1f2687d2963ae33f71a188ac")
        assertEquals(32, computeSegwitSighash(tx1, 1, scriptCode1, 600000000, 1).size)

        val txHex6 = "0200000001279a2323a5dfb51fc45f220fa58b0fc13e1e3342792a85d7e36cd6333b5cbc390000000000ffffffff01a05aea0b000000001976a914ffe9c0061097cc3b636f2cb0460fa4fc427d2b4588ac00000000"
        val tx6 = parseTransaction(hexToBytes(txHex6))
        val scriptCode6 = hexToBytes("47522103b1341ccba7683b6af4f1238cd6e97e7167d569fac47f1e48d47541844355bd462103de55d1e1dac805e3f8a58c1fbf9b94c02f3dbaafe127fefca4995f26f82083bd52ae")
        assertEquals(32, computeSegwitSighash(tx6, 0, scriptCode6, 199909013, 1).size)
    }
}
