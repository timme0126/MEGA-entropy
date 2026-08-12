package org.mega.entropy.ui.advancedmode.multisig

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mega.entropycore.MultisigScriptType
import org.mega.entropycore.WalletNetwork
import org.mega.entropycore.WalletScriptType
import org.mega.entropycore.deriveMultisigCosignerAccountKeys
import org.mega.entropycore.deriveWalletAccountKeys

class MultisigVaultViewModelTest {
    private val cosignerA = "[751e76e8/48'/0'/0'/2']xpub661MyMwAqRbcEYS8w7XLSVeEsBXy79zSzH1J8vCdxAZningWLdN3zgtU6QzvJsNBNF5QPBBBg1yVF2LKrcfGdJq86PeLWDMUCYatZPzQu8R/<0;1>/*"
    private val cosignerB = "[06afd46b/48'/0'/0'/2']xpub661MyMwAqRbcEYS8w7XLSVeEsBXy79zSzH1J8vCdxAZningWLdN3zgtU6RaWczAs6MVywiybuhjHuUQKNNTPv4jYsDwwKwKyhjPrr2oGiVK/<0;1>/*"
    private val cosignerC = "[7dd65592/48'/0'/0'/2']xpub661MyMwAqRbcEYS8w7XLSVeEsBXy79zSzH1J8vCdxAZningWLdN3zgtU6Ry3jzyxjRpjQ6N8aa1M55DxuLjf24UZ8ufawmLVf8NWMG88kcq/<0;1>/*"

    // Real, correctly base58check-encoded keys — not fake strings that merely
    // start with the right prefix — derived the same way the entropy-core
    // parser tests do, so SLIP-132 rejection and plain-xpub acceptance are
    // both exercised by genuine version bytes, not by string-prefix sniffing.
    private val testMnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        .split(" ")

    private fun bareMainnetXpub(account: Int = 0) = deriveMultisigCosignerAccountKeys(
        testMnemonic, "", MultisigScriptType.NATIVE_SEGWIT, WalletNetwork.MAINNET, account,
    ).extendedPublicKey

    private fun bareTestnetTpub(account: Int = 0) = deriveMultisigCosignerAccountKeys(
        testMnemonic, "", MultisigScriptType.NATIVE_SEGWIT, WalletNetwork.TESTNET, account,
    ).extendedPublicKey

    private fun bareMainnetZpub() = deriveWalletAccountKeys(
        testMnemonic, "", WalletScriptType.NATIVE_SEGWIT, WalletNetwork.MAINNET, 0,
    ).extendedPublicKey

    @Test
    fun `matching full descriptor fills every slot`() {
        val viewModel = MultisigVaultViewModel()
        viewModel.setN(3)
        viewModel.setM(2)
        viewModel.confirmPolicy()

        viewModel.fillManySlotsFromDescriptor("wsh(sortedmulti(2,$cosignerA,$cosignerB,$cosignerC))")

        val state = viewModel.uiState.value
        assertEquals(3, state.slots.size)
        assertTrue(state.slots.all { it.status is SlotStatus.Filled })
        assertEquals(null, state.walletError)
    }

    @Test
    fun `full descriptor with a different threshold than chosen adopts the descriptor's own policy`() {
        val viewModel = MultisigVaultViewModel()
        viewModel.setN(3)
        viewModel.setM(2)
        viewModel.confirmPolicy()

        viewModel.fillManySlotsFromDescriptor("wsh(sortedmulti(3,$cosignerA,$cosignerB,$cosignerC))")

        val state = viewModel.uiState.value
        assertEquals(3, state.m)
        assertEquals(3, state.n)
        assertEquals(3, state.slots.size)
        assertTrue(state.slots.all { it.status is SlotStatus.Filled })
        assertEquals(null, state.walletError)
    }

    @Test
    fun `fillManySlotsFromScannedText rejects non-descriptor text with a specific error`() {
        val viewModel = MultisigVaultViewModel()
        viewModel.setN(2)
        viewModel.setM(1)
        viewModel.confirmPolicy()

        viewModel.fillManySlotsFromScannedText(cosignerA)

        val state = viewModel.uiState.value
        assertTrue(state.slots.all { it.status is SlotStatus.Empty })
        assertNotNull(state.walletError)
        assertTrue(state.walletError.orEmpty().contains("full multisig descriptor"))
    }

    @Test
    fun `fillManySlotsFromScannedText fills slots from a full descriptor regardless of pendingSlotIndex`() {
        val viewModel = MultisigVaultViewModel()
        viewModel.setN(3)
        viewModel.setM(2)
        viewModel.confirmPolicy()
        viewModel.beginFillSlot(1) // stale/irrelevant — must not affect the outcome

        viewModel.fillManySlotsFromScannedText("wsh(sortedmulti(2,$cosignerA,$cosignerB,$cosignerC))")

        val state = viewModel.uiState.value
        assertTrue(state.slots.all { it.status is SlotStatus.Filled })
        assertEquals(null, state.walletError)
    }

    @Test
    fun `scanned descriptor fragment fills pending slot`() {
        val viewModel = MultisigVaultViewModel()
        viewModel.setN(2)
        viewModel.setM(1)
        viewModel.confirmPolicy()
        viewModel.beginFillSlot(0)

        viewModel.fillPendingSlotFromScannedText(cosignerA)

        val state = viewModel.uiState.value
        assertTrue(state.slots[0].status is SlotStatus.Filled)
        assertEquals(null, state.pendingSlotIndex)
        assertEquals(null, state.walletError)
    }

    @Test
    fun `scanned full descriptor fills every slot and clears pending slot`() {
        val viewModel = MultisigVaultViewModel()
        viewModel.setN(3)
        viewModel.setM(2)
        viewModel.confirmPolicy()
        viewModel.beginFillSlot(1)

        viewModel.fillPendingSlotFromScannedText("wsh(sortedmulti(2,$cosignerA,$cosignerB,$cosignerC))")

        val state = viewModel.uiState.value
        assertEquals(3, state.slots.size)
        assertTrue(state.slots.all { it.status is SlotStatus.Filled })
        assertEquals(null, state.pendingSlotIndex)
        assertEquals(null, state.walletError)
    }

    @Test
    fun `bare xpub triggers the Complete Cosigner Info helper instead of a slot error`() {
        val viewModel = MultisigVaultViewModel()
        viewModel.setN(2)
        viewModel.setM(1)
        viewModel.confirmPolicy()
        viewModel.beginFillSlot(0)

        viewModel.fillPendingSlotFromScannedText(bareMainnetXpub())

        val state = viewModel.uiState.value
        assertTrue(state.slots[0].status is SlotStatus.Empty)
        assertNull(state.walletError)
        assertNotNull(state.pendingBareXpub)
        assertEquals(0, state.pendingSlotIndex)
        assertTrue(state.pendingBareXpub!!.isPlainXpub)
        assertEquals("xpub", state.pendingBareXpub.displayPrefix)
        assertEquals(WalletNetwork.MAINNET, state.pendingBareXpub.network)
    }

    @Test
    fun `completing bare xpub with valid fingerprint and account fills the pending slot`() {
        val viewModel = MultisigVaultViewModel()
        viewModel.setN(2)
        viewModel.setM(1)
        viewModel.confirmPolicy()
        viewModel.beginFillSlot(0)
        viewModel.fillPendingSlotFromScannedText(bareMainnetXpub())

        viewModel.completeBareXpubCosigner("751e76e8", 0, null)

        val state = viewModel.uiState.value
        assertTrue(state.slots[0].status is SlotStatus.Filled)
        assertNull(state.pendingBareXpub)
        assertNull(state.pendingSlotIndex)
        assertNull(state.bareXpubError)
        assertEquals("751e76e8", state.slots[0].origin?.masterFingerprint)
        assertEquals("m/48'/0'/0'/2'", state.slots[0].origin?.derivationPath)
    }

    @Test
    fun `completing bare xpub with an invalid fingerprint is rejected`() {
        val viewModel = MultisigVaultViewModel()
        viewModel.setN(2)
        viewModel.setM(1)
        viewModel.confirmPolicy()
        viewModel.beginFillSlot(0)
        viewModel.fillPendingSlotFromScannedText(bareMainnetXpub())

        viewModel.completeBareXpubCosigner("zzzzzzzz", 0, null)

        val state = viewModel.uiState.value
        assertTrue(state.slots[0].status is SlotStatus.Empty)
        assertNotNull(state.pendingBareXpub)
        assertNotNull(state.bareXpubError)
    }

    @Test
    fun `completing bare xpub with an out-of-range account index is rejected`() {
        val viewModel = MultisigVaultViewModel()
        viewModel.setN(2)
        viewModel.setM(1)
        viewModel.confirmPolicy()
        viewModel.beginFillSlot(0)
        viewModel.fillPendingSlotFromScannedText(bareMainnetXpub())

        viewModel.completeBareXpubCosigner("751e76e8", -1, null)

        val state = viewModel.uiState.value
        assertTrue(state.slots[0].status is SlotStatus.Empty)
        assertNotNull(state.pendingBareXpub)
        assertNotNull(state.bareXpubError)
    }

    @Test
    fun `completing bare xpub with an unparseable account index is rejected, not silently defaulted to 0`() {
        val viewModel = MultisigVaultViewModel()
        viewModel.setN(2)
        viewModel.setM(1)
        viewModel.confirmPolicy()
        viewModel.beginFillSlot(0)
        viewModel.fillPendingSlotFromScannedText(bareMainnetXpub())

        viewModel.completeBareXpubCosigner("751e76e8", null, null)

        val state = viewModel.uiState.value
        assertTrue(state.slots[0].status is SlotStatus.Empty)
        assertNotNull(state.bareXpubError)
    }

    @Test
    fun `completing bare xpub with a network mismatch is rejected`() {
        val viewModel = MultisigVaultViewModel()
        viewModel.setN(2)
        viewModel.setM(1)
        viewModel.setNetwork(WalletNetwork.MAINNET)
        viewModel.confirmPolicy()
        viewModel.beginFillSlot(0)

        viewModel.fillPendingSlotFromScannedText(bareTestnetTpub())
        viewModel.completeBareXpubCosigner("751e76e8", 0, null)

        val state = viewModel.uiState.value
        assertTrue(state.slots[0].status is SlotStatus.Empty)
        assertNotNull(state.pendingBareXpub)
        assertNotNull(state.bareXpubError)
        assertTrue(state.bareXpubError.orEmpty().contains("testnet"))
    }

    @Test
    fun `bare zpub is rejected with a SLIP-132 specific message and no auto-canonicalization`() {
        val viewModel = MultisigVaultViewModel()
        viewModel.setN(2)
        viewModel.setM(1)
        viewModel.confirmPolicy()
        viewModel.beginFillSlot(0)

        viewModel.fillPendingSlotFromScannedText(bareMainnetZpub())

        val afterScan = viewModel.uiState.value
        assertNotNull(afterScan.pendingBareXpub)
        assertFalse(afterScan.pendingBareXpub!!.isPlainXpub)
        assertEquals("zpub", afterScan.pendingBareXpub.displayPrefix)

        viewModel.completeBareXpubCosigner("751e76e8", 0, null)

        val state = viewModel.uiState.value
        assertTrue(state.slots[0].status is SlotStatus.Empty)
        assertNotNull(state.pendingBareXpub)
        assertNotNull(state.bareXpubError)
        assertTrue(state.bareXpubError.orEmpty().contains("xpub/tpub"))
        assertTrue(state.bareXpubError.orEmpty().contains("Sparrow"))
    }

    @Test
    fun `completing the same bare xpub into two slots rejects the second as a duplicate`() {
        val viewModel = MultisigVaultViewModel()
        viewModel.setN(2)
        viewModel.setM(1)
        viewModel.confirmPolicy()
        val bareXpub = bareMainnetXpub()

        viewModel.beginFillSlot(0)
        viewModel.fillPendingSlotFromScannedText(bareXpub)
        viewModel.completeBareXpubCosigner("751e76e8", 0, null)

        viewModel.beginFillSlot(1)
        viewModel.fillPendingSlotFromScannedText(bareXpub)
        viewModel.completeBareXpubCosigner("06afd46b", 1, null)

        val state = viewModel.uiState.value
        assertTrue(state.slots[0].status is SlotStatus.Filled)
        assertTrue(state.slots[1].status is SlotStatus.Invalid)
        assertTrue((state.slots[1].status as SlotStatus.Invalid).message.contains("already used"))
    }

    @Test
    fun `cancelling the Complete Cosigner Info helper leaves the slot untouched`() {
        val viewModel = MultisigVaultViewModel()
        viewModel.setN(2)
        viewModel.setM(1)
        viewModel.confirmPolicy()
        viewModel.beginFillSlot(0)
        viewModel.fillPendingSlotFromScannedText(bareMainnetXpub())

        viewModel.cancelBareXpubHelper()

        val state = viewModel.uiState.value
        assertTrue(state.slots[0].status is SlotStatus.Empty)
        assertNull(state.pendingBareXpub)
        assertNull(state.pendingSlotIndex)
        assertNull(state.bareXpubError)
    }

    @Test
    fun `setM raises N to match when N was already chosen smaller`() {
        val viewModel = MultisigVaultViewModel()
        viewModel.setN(2)

        viewModel.setM(4)

        val state = viewModel.uiState.value
        assertEquals(4, state.m)
        assertEquals(4, state.n)
    }

    @Test
    fun `setM leaves N unchanged when N already accommodates it`() {
        val viewModel = MultisigVaultViewModel()
        viewModel.setN(5)

        viewModel.setM(3)

        val state = viewModel.uiState.value
        assertEquals(3, state.m)
        assertEquals(5, state.n)
    }

    @Test
    fun `setN still coerces M down when M was already chosen larger`() {
        val viewModel = MultisigVaultViewModel()
        viewModel.setM(4)

        viewModel.setN(2)

        val state = viewModel.uiState.value
        assertEquals(2, state.m)
        assertEquals(2, state.n)
    }

    @Test
    fun `fillPendingSlot records whether a passphrase was used`() {
        val viewModel = MultisigVaultViewModel()
        viewModel.setN(2)
        viewModel.setM(1)
        viewModel.confirmPolicy()
        viewModel.beginFillSlot(0)

        val origin = deriveMultisigCosignerAccountKeys(
            testMnemonic, "", MultisigScriptType.NATIVE_SEGWIT, WalletNetwork.MAINNET, 0,
        )
        viewModel.fillPendingSlot(
            org.mega.entropycore.MultisigCosignerOrigin(origin.masterFingerprint, origin.derivationPath, origin.extendedPublicKey),
            "label",
            passphraseUsed = true,
        )

        assertEquals(true, viewModel.uiState.value.slots[0].passphraseUsed)
    }

    @Test
    fun `pasted and scanned cosigners have unknown passphraseUsed`() {
        val viewModel = MultisigVaultViewModel()
        viewModel.setN(2)
        viewModel.setM(1)
        viewModel.confirmPolicy()

        viewModel.fillSlotFromPastedText(0, cosignerA)

        assertNull(viewModel.uiState.value.slots[0].passphraseUsed)
    }

    @Test
    fun `beginSaveVault and cancelSaveVault toggle the dialog state`() {
        val viewModel = MultisigVaultViewModel()

        viewModel.beginSaveVault()
        assertTrue(viewModel.uiState.value.showSaveVaultDialog)

        viewModel.cancelSaveVault()
        assertFalse(viewModel.uiState.value.showSaveVaultDialog)
    }

    @Test
    fun `onVaultSaved closes the dialog and sets the confirmation label`() {
        val viewModel = MultisigVaultViewModel()
        viewModel.beginSaveVault()

        viewModel.onVaultSaved("Cold storage")

        val state = viewModel.uiState.value
        assertFalse(state.showSaveVaultDialog)
        assertEquals("Cold storage", state.savedVaultLabel)

        viewModel.dismissSavedVaultConfirmation()
        assertNull(viewModel.uiState.value.savedVaultLabel)
    }

    @Test
    fun `toSavedCosigners carries origin fields and passphraseUsed for every filled slot`() {
        val viewModel = MultisigVaultViewModel()
        viewModel.setN(2)
        viewModel.setM(1)
        viewModel.confirmPolicy()
        viewModel.fillSlotFromPastedText(0, cosignerA)
        viewModel.beginFillSlot(1)
        val origin = deriveMultisigCosignerAccountKeys(
            testMnemonic, "", MultisigScriptType.NATIVE_SEGWIT, WalletNetwork.MAINNET, 0,
        )
        viewModel.fillPendingSlot(
            org.mega.entropycore.MultisigCosignerOrigin(origin.masterFingerprint, origin.derivationPath, origin.extendedPublicKey),
            "label",
            passphraseUsed = false,
        )

        val saved = viewModel.uiState.value.toSavedCosigners()

        assertEquals(2, saved.size)
        assertNull(saved[0].passphraseUsed)
        assertEquals(false, saved[1].passphraseUsed)
        assertEquals("751e76e8 · m/48'/0'/0'/2'", saved[0].label)
        assertEquals("label", saved[1].label)
        assertEquals(origin.masterFingerprint, saved[1].masterFingerprint)
    }

    @Test
    fun `full descriptor import is staged, not applied, when a slot is already filled`() {
        val viewModel = MultisigVaultViewModel()
        viewModel.setN(2)
        viewModel.setM(1)
        viewModel.confirmPolicy()
        viewModel.fillSlotFromPastedText(0, cosignerA)

        viewModel.fillManySlotsFromDescriptor("wsh(sortedmulti(2,$cosignerB,$cosignerC))")

        val state = viewModel.uiState.value
        // Nothing about the existing state changed yet.
        assertEquals(1, state.m)
        assertEquals(2, state.n)
        assertTrue(state.slots[0].status is SlotStatus.Filled)
        assertEquals("751e76e8", state.slots[0].origin?.masterFingerprint)
        assertTrue(state.slots[1].status is SlotStatus.Empty)
        assertEquals(null, state.walletError)
        // The import is staged for confirmation instead.
        val pending = state.pendingDescriptorImport
        assertNotNull(pending)
        assertEquals(2, pending!!.threshold)
        assertEquals(2, pending.cosignerCount)
        assertEquals(WalletNetwork.MAINNET, pending.network)
    }

    @Test
    fun `confirmDescriptorImport applies the staged import, replacing policy and every slot`() {
        val viewModel = MultisigVaultViewModel()
        viewModel.setN(2)
        viewModel.setM(1)
        viewModel.confirmPolicy()
        viewModel.fillSlotFromPastedText(0, cosignerA)
        viewModel.fillManySlotsFromDescriptor("wsh(sortedmulti(2,$cosignerB,$cosignerC))")

        viewModel.confirmDescriptorImport()

        val state = viewModel.uiState.value
        assertEquals(2, state.m)
        assertEquals(2, state.n)
        assertEquals(2, state.slots.size)
        assertTrue(state.slots.all { it.status is SlotStatus.Filled })
        assertEquals(null, state.pendingDescriptorImport)
        assertEquals(null, state.walletError)
    }

    @Test
    fun `cancelDescriptorImport discards the staged import and leaves existing slots untouched`() {
        val viewModel = MultisigVaultViewModel()
        viewModel.setN(2)
        viewModel.setM(1)
        viewModel.confirmPolicy()
        viewModel.fillSlotFromPastedText(0, cosignerA)
        viewModel.fillManySlotsFromDescriptor("wsh(sortedmulti(2,$cosignerB,$cosignerC))")

        viewModel.cancelDescriptorImport()

        val state = viewModel.uiState.value
        // Original policy and slot 0's original cosigner survive untouched.
        assertEquals(1, state.m)
        assertEquals(2, state.n)
        assertTrue(state.slots[0].status is SlotStatus.Filled)
        assertTrue(state.slots[1].status is SlotStatus.Empty)
        assertEquals(null, state.pendingDescriptorImport)
    }

    @Test
    fun `fillManySlotsFromDescriptor applies immediately when no slot is filled yet`() {
        val viewModel = MultisigVaultViewModel()
        viewModel.setN(2)
        viewModel.setM(1)
        viewModel.confirmPolicy()

        viewModel.fillManySlotsFromDescriptor("wsh(sortedmulti(2,$cosignerB,$cosignerC))")

        val state = viewModel.uiState.value
        assertEquals(null, state.pendingDescriptorImport)
        assertTrue(state.slots.all { it.status is SlotStatus.Filled })
        assertEquals(2, state.m)
    }

    @Test
    fun `fillManySlotsFromScannedText also stages an import when a slot is already filled`() {
        val viewModel = MultisigVaultViewModel()
        viewModel.setN(2)
        viewModel.setM(1)
        viewModel.confirmPolicy()
        viewModel.fillSlotFromPastedText(0, cosignerA)

        viewModel.fillManySlotsFromScannedText("wsh(sortedmulti(2,$cosignerB,$cosignerC))")

        val state = viewModel.uiState.value
        assertTrue(state.slots[0].status is SlotStatus.Filled)
        assertNotNull(state.pendingDescriptorImport)
    }
}
