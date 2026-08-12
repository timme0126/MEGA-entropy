package org.mega.entropy.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mega.entropycore.MultisigScriptType
import org.mega.entropycore.WalletNetwork

class MultisigVaultSerializerTest {
    private val cosignerA = SavedMultisigCosigner(
        label = "Alice",
        masterFingerprint = "751e76e8",
        derivationPath = "m/48'/0'/0'/2'",
        extendedPublicKey = "xpub6DkFAXWQ2dHxq2vatrt9qyA3bXYU4ToWQwCHbf5XB2mSTexcHZCeKS1VZYcPoBd5X8yVcbXFHJR9R8UCVpt82VX1VhR28mCyxUFL4r6KFrf",
        passphraseUsed = true,
    )
    private val cosignerB = SavedMultisigCosigner(
        label = "Bob",
        masterFingerprint = "06afd46b",
        derivationPath = "m/48'/0'/0'/2'",
        extendedPublicKey = "xpub661MyMwAqRbcEYS8w7XLSVeEsBXy79zSzH1J8vCdxAZningWLdN3zgtU6RaWczAs6MVywiybuhjHuUQKNNTPv4jYsDwwKwKyhjPrr2oGiVK",
        passphraseUsed = false,
    )
    private val cosignerC = SavedMultisigCosigner(
        label = "Carol",
        masterFingerprint = "7dd65592",
        derivationPath = "m/48'/0'/0'/2'",
        extendedPublicKey = "xpub661MyMwAqRbcEYS8w7XLSVeEsBXy79zSzH1J8vCdxAZningWLdN3zgtU6Ry3jzyxjRpjQ6N8aa1M55DxuLjf24UZ8ufawmLVf8NWMG88kcq",
        passphraseUsed = null,
    )

    @Test
    fun `round-trips a vault with multiple cosigners and every passphraseUsed state`() {
        val vault = SavedMultisigVault(
            id = "vault-1",
            createdAtEpochMillis = 1_700_000_000_000L,
            label = "Cold storage",
            threshold = 2,
            network = WalletNetwork.MAINNET,
            scriptType = MultisigScriptType.NATIVE_SEGWIT,
            cosigners = listOf(cosignerA, cosignerB, cosignerC),
        )

        val decoded = decodeMultisigVault(encodeMultisigVault(vault))

        assertEquals(vault, decoded)
    }

    @Test
    fun `label can contain a colon without breaking decoding`() {
        val vault = SavedMultisigVault(
            id = "vault-2",
            createdAtEpochMillis = 1_700_000_000_000L,
            label = "Cosigners: Alice, Bob, and Carol",
            threshold = 1,
            network = WalletNetwork.TESTNET,
            scriptType = MultisigScriptType.NATIVE_SEGWIT,
            cosigners = listOf(cosignerA),
        )

        val decoded = decodeMultisigVault(encodeMultisigVault(vault))

        assertEquals("Cosigners: Alice, Bob, and Carol", decoded.label)
    }

    @Test
    fun `labels can contain delimiters and newlines without breaking decoding`() {
        val vault = SavedMultisigVault(
            id = "vault-labels",
            createdAtEpochMillis = 1_700_000_000_000L,
            label = "Vault: Alice | Bob\nOffline copy",
            threshold = 1,
            network = WalletNetwork.MAINNET,
            scriptType = MultisigScriptType.NATIVE_SEGWIT,
            cosigners = listOf(cosignerA.copy(label = "Alice | GrapheneOS\nSlot 1")),
        )

        val decoded = decodeMultisigVault(encodeMultisigVault(vault))

        assertEquals(vault.label, decoded.label)
        assertEquals("Alice | GrapheneOS\nSlot 1", decoded.cosigners.single().label)
    }

    @Test
    fun `rejects a file with the wrong header`() {
        assertThrows(IllegalArgumentException::class.java) {
            decodeMultisigVault("NOT-A-VAULT-FILE\nid:x".toByteArray())
        }
    }

    @Test
    fun `rejects a file whose cosigner count does not match the number of COSIGNER lines`() {
        val vault = SavedMultisigVault(
            id = "vault-3",
            createdAtEpochMillis = 1L,
            label = "x",
            threshold = 1,
            network = WalletNetwork.MAINNET,
            scriptType = MultisigScriptType.NATIVE_SEGWIT,
            cosigners = listOf(cosignerA, cosignerB),
        )
        val corrupted = encodeMultisigVault(vault).decodeToString().replace("cosignerCount:2", "cosignerCount:5")

        assertThrows(IllegalArgumentException::class.java) {
            decodeMultisigVault(corrupted.toByteArray())
        }
    }

    @Test
    fun `rejects an unrecognized passphraseUsed value`() {
        val vault = SavedMultisigVault(
            id = "vault-4",
            createdAtEpochMillis = 1L,
            label = "x",
            threshold = 1,
            network = WalletNetwork.MAINNET,
            scriptType = MultisigScriptType.NATIVE_SEGWIT,
            cosigners = listOf(cosignerA),
        )
        val corrupted = encodeMultisigVault(vault).decodeToString().replace("|true", "|maybe")

        assertThrows(IllegalArgumentException::class.java) {
            decodeMultisigVault(corrupted.toByteArray())
        }
    }
}
