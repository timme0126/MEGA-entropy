package org.mega.entropy.ui.advancedmode.multisig

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.mega.entropy.storage.SavedMultisigCosigner
import org.mega.entropycore.BareCosignerExtendedKey
import org.mega.entropycore.MultisigCosignerOrigin
import org.mega.entropycore.MultisigScriptType
import org.mega.entropycore.MultisigWallet
import org.mega.entropycore.WalletNetwork
import org.mega.entropycore.buildMultisigWallet
import org.mega.entropycore.completeBareCosignerExtendedKey
import org.mega.entropycore.defaultCosignerDerivationPath
import org.mega.entropycore.normalizeMasterFingerprint
import org.mega.entropycore.parseBareCosignerExtendedKey
import org.mega.entropycore.parseCosignerDescriptorFragment
import org.mega.entropycore.parseMultisigDescriptor

/** The three stages of the "Setup Multi-Signature Vault" flow, in order:
 * choose N/M/network/script type, fill each of the N cosigner slots, then
 * see the resulting descriptor/address. */
enum class MultisigSetupStep { POLICY, SLOTS, RESULT }

/** What a single cosigner slot currently holds, independent of HOW it got
 * filled (saved session, paste, or — once implemented — scan): all three
 * paths converge on the same [MultisigCosignerOrigin], so the slot itself
 * doesn't need to remember its source. */
sealed class SlotStatus {
    data object Empty : SlotStatus()
    data class Filled(val label: String) : SlotStatus()
    data class Invalid(val message: String) : SlotStatus()
}

data class MultisigSlot(
    val status: SlotStatus = SlotStatus.Empty,
    val origin: MultisigCosignerOrigin? = null,
    /** null = unknown — true only when this slot was filled by deriving a
     * key on this device from a saved session and a passphrase was typed;
     * false when derived with no passphrase; null for anything pasted,
     * scanned, or completed through the bare-xpub helper, since there is
     * no way to know whether the source device used one. */
    val passphraseUsed: Boolean? = null,
)

data class MultisigVaultUiState(
    val step: MultisigSetupStep = MultisigSetupStep.POLICY,
    val n: Int? = null,
    val m: Int? = null,
    val network: WalletNetwork = WalletNetwork.MAINNET,
    val scriptType: MultisigScriptType = MultisigScriptType.NATIVE_SEGWIT,
    val slots: List<MultisigSlot> = emptyList(),
    // Which slot a picker/derive/paste/scan sub-screen is currently
    // filling — set by beginFillSlot, consumed and cleared by whichever
    // of fillPendingSlot / cancelFillSlot runs next.
    val pendingSlotIndex: Int? = null,
    // Set when scanned/pasted text is a plausible bare extended public key
    // (no [fingerprint/path] origin) — drives the "Complete Cosigner Info"
    // dialog in AdvancedModeMultisigVaultScreen. pendingSlotIndex still
    // names the target slot; this just carries what was safely recovered
    // from the key itself so the dialog can show it without re-parsing.
    val pendingBareXpub: BareCosignerExtendedKey? = null,
    val bareXpubError: String? = null,
    val walletResult: MultisigWallet? = null,
    val walletError: String? = null,
    // "Save Vault" on the Result step opens this; MegaLabelSessionDialog
    // (mandatory label) drives it, MegaNavGraph performs the actual save
    // via MultisigVaultRepository (I/O, so it can't live in this
    // plain-ViewModel — see MultisigVaultViewModel's own doc comment on
    // why it stays Context-free) and calls onVaultSaved on success.
    val showSaveVaultDialog: Boolean = false,
    // Transient "Saved as ..." confirmation, mirroring
    // MegaNavGraph's advancedModeSavedConfirmation pattern.
    val savedVaultLabel: String? = null,
    // Set by fillManySlotsFromDescriptor instead of applying immediately,
    // whenever the import would overwrite one or more already-filled
    // cosigner slots — see that function's doc comment. Drives a
    // confirmation dialog in AdvancedModeMultisigVaultScreen; the import
    // itself is only applied by confirmDescriptorImport.
    val pendingDescriptorImport: PendingDescriptorImport? = null,
) {
    val allSlotsFilled: Boolean
        get() = slots.isNotEmpty() && slots.all { it.status is SlotStatus.Filled }
}

/** A full descriptor import staged for explicit confirmation because
 * applying it would replace one or more cosigner slots the user already
 * filled. Carries everything [MultisigVaultViewModel.confirmDescriptorImport]
 * needs to apply it later, and everything the confirmation dialog needs to
 * describe what's about to change (M-of-N, network, and — implicitly, since
 * every slot is replaced — script/key policy for every cosigner). */
data class PendingDescriptorImport(
    val threshold: Int,
    val cosignerCount: Int,
    val network: WalletNetwork,
    val slots: List<MultisigSlot>,
)

/** Builds the list MultisigVaultRepository.saveVault needs from the
 * current Result-step state — a pure function (no I/O) so the actual save
 * call can live in MegaNavGraph, where the repository is constructed, while
 * this stays testable without a Context. */
fun MultisigVaultUiState.toSavedCosigners(): List<SavedMultisigCosigner> =
    slots.mapNotNull { slot ->
        val origin = slot.origin ?: return@mapNotNull null
        val label = (slot.status as? SlotStatus.Filled)?.label.orEmpty()
        SavedMultisigCosigner(
            label = label,
            masterFingerprint = origin.masterFingerprint,
            derivationPath = origin.derivationPath,
            extendedPublicKey = origin.extendedPublicKey,
            passphraseUsed = slot.passphraseUsed,
        )
    }

/**
 * Holds the "Setup Multi-Signature Vault" flow's state in memory only,
 * the same "ephemeral by default" principle DiceSessionViewModel documents
 * for the dice flow — nothing here is ever written to disk on its own; a
 * successful [buildVault] only ever produces public descriptor/address
 * data, never anything that needs saving as a session. Obtained once at
 * the top of MegaNavGraph (Activity-scoped, same pattern as
 * diceSessionViewModel) and passed down explicitly to the multisig
 * sub-graph's screens, rather than looked up per-screen, so every screen
 * in the flow shares the exact same instance without relying on
 * NavBackStackEntry-scoped ViewModel lookup subtleties.
 */
class MultisigVaultViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(MultisigVaultUiState())
    val uiState: StateFlow<MultisigVaultUiState> = _uiState.asStateFlow()

    fun setN(n: Int) {
        _uiState.update { state -> state.copy(n = n, m = state.m?.coerceAtMost(n)) }
    }

    /** Symmetric to [setN]'s own coercion: M can never exceed N, so if N was
     * already chosen and is now smaller than this M, raise N to match
     * rather than silently accepting an invalid M-of-N. Needed now that the
     * Policy step asks for M before N — with the old N-first order this
     * direction was unreachable (N couldn't yet exist when M was first
     * set). */
    fun setM(m: Int) {
        _uiState.update { state -> state.copy(m = m, n = state.n?.coerceAtLeast(m)) }
    }

    fun setNetwork(network: WalletNetwork) {
        _uiState.update { it.copy(network = network) }
    }

    fun setScriptType(scriptType: MultisigScriptType) {
        _uiState.update { it.copy(scriptType = scriptType) }
    }

    /** Advances Policy -> Slots, creating N empty slots. No-op if N hasn't
     * been chosen yet. */
    fun confirmPolicy() {
        _uiState.update { state ->
            val n = state.n ?: return@update state
            state.copy(step = MultisigSetupStep.SLOTS, slots = List(n) { MultisigSlot() })
        }
    }

    /** Back from Slots to Policy — the chosen N/M/network/scriptType stay
     * as they were (re-confirming just re-derives the same-size slot
     * list), but any already-filled slot data is intentionally discarded:
     * changing the policy can change the derivation path every slot needs
     * (different network/script type), so a slot filled under the old
     * policy is not safe to silently carry forward. */
    fun backToPolicy() {
        _uiState.update {
            it.copy(step = MultisigSetupStep.POLICY, slots = emptyList(), walletResult = null, walletError = null)
        }
    }

    fun beginFillSlot(index: Int) {
        _uiState.update { it.copy(pendingSlotIndex = index) }
    }

    fun cancelFillSlot() {
        _uiState.update { it.copy(pendingSlotIndex = null) }
    }

    /** Fills whichever slot [beginFillSlot] most recently marked pending —
     * used by both the saved-session-derive screen and (once implemented)
     * the scanner, which both ultimately produce a [MultisigCosignerOrigin]
     * some other way than pasted text. Rejects a duplicate extended public
     * key across slots immediately (the same string check
     * buildMultisigWallet repeats at build time) so a mistake is visible
     * right on the slot instead of only surfacing once "Build Vault" is
     * pressed. */
    fun fillPendingSlot(origin: MultisigCosignerOrigin, label: String, passphraseUsed: Boolean? = null) {
        _uiState.update { state ->
            val index = state.pendingSlotIndex ?: return@update state
            state.copy(
                slots = state.slots.withSlotFilled(index, origin, label, passphraseUsed),
                pendingSlotIndex = null,
                walletResult = null,
                walletError = null,
            )
        }
    }

    /** Fills slot [index] directly from pasted text — either a single
     * `[fingerprint/path]xpub...` fragment, or a full
     * `wsh(sortedmulti(...))` descriptor containing exactly one cosigner
     * (pasting a multi-cosigner descriptor into a single slot is
     * ambiguous about which cosigner belongs here, so that case is
     * rejected with a message pointing at [fillManySlotsFromDescriptor]
     * instead). Unlike [fillPendingSlot], this doesn't need
     * [beginFillSlot]/pendingSlotIndex first since the target slot is
     * already known from the paste dialog itself. */
    fun fillSlotFromPastedText(index: Int, text: String) {
        _uiState.update { state ->
            val trimmed = text.trim()
            if (trimmed.isEmpty()) {
                return@update state.copy(slots = state.slots.withSlotInvalid(index, "Paste a descriptor fragment first."))
            }
            val origin = try {
                if (looksLikeFullDescriptor(trimmed)) {
                    val parsed = parseMultisigDescriptor(trimmed)
                    if (parsed.cosigners.size != 1) {
                        return@update state.copy(
                            slots = state.slots.withSlotInvalid(
                                index,
                                "This descriptor has ${parsed.cosigners.size} cosigners — use \"Paste Full Descriptor\" " +
                                    "to fill multiple slots at once instead of pasting it into a single slot.",
                            ),
                        )
                    }
                    parsed.cosigners.single()
                } else {
                    parseCosignerDescriptorFragment(trimmed)
                }
            } catch (e: IllegalArgumentException) {
                // A bare xpub/tpub/zpub (no [fingerprint/path] origin) is the
                // single most common way this parse fails in practice — many
                // wallets (Sparrow included) let a user export "just the
                // xpub". Offer the "Complete Cosigner Info" helper instead of
                // a raw parser error, but only for text that actually looks
                // like a real extended key — anything else still becomes a
                // normal slot error exactly as before.
                val bareKey = parseBareCosignerExtendedKey(trimmed)
                if (bareKey != null) {
                    return@update state.copy(
                        pendingSlotIndex = index,
                        pendingBareXpub = bareKey,
                        bareXpubError = null,
                    )
                }
                return@update state.copy(slots = state.slots.withSlotInvalid(index, e.message ?: "Could not parse this input."))
            }
            val label = "${origin.masterFingerprint} · ${origin.derivationPath}"
            state.copy(
                slots = state.slots.withSlotFilled(index, origin, label),
                walletResult = null,
                walletError = null,
            )
        }
    }

    /** Completes a bare extended public key detected by [fillSlotFromPastedText]
     * / [fillPendingSlotFromScannedText] into the slot [pendingSlotIndex]
     * names, using a user-supplied master fingerprint (never invented — see
     * completeBareCosignerExtendedKey), a user-supplied [label], and the
     * vault's own standard account-0 BIP48 path. Account index and a custom
     * path used to be user-entered here too, but the exporting wallet
     * already fixed the actual derivation when it produced this xpub —
     * asking the user to re-specify it here just added friction for the
     * overwhelmingly common account-0 case, and account index in particular
     * has no way to be verified against the key itself anyway. A
     * non-default path is set afterward via [editSlotDerivationPath]
     * instead, once the cosigner is already in the slot.
     *
     * [label] is required (the calling dialog only enables its confirm
     * action once it's non-blank) rather than derived from fingerprint/path
     * the way it used to be: a derived "fingerprint · path" label went
     * stale the moment either was corrected afterward via their own pencil
     * icons, since a slot's status label is never regenerated after fill
     * time. A label the user actually typed has no such staleness problem,
     * and matches how a saved-session-derived cosigner is already labeled
     * (that session's own name) rather than by its raw key material.
     *
     * Re-checks SLIP-132/network defensively even though the helper UI
     * already hides the form in those cases, the same defense-in-depth
     * this file already applies elsewhere (see withSlotFilled's duplicate
     * check, also re-verified at buildVault time). */
    fun completeBareXpubCosigner(masterFingerprint: String, label: String) {
        _uiState.update { state ->
            val pending = state.pendingBareXpub ?: return@update state
            val index = state.pendingSlotIndex ?: return@update state.copy(pendingBareXpub = null, bareXpubError = null)

            if (!pending.isPlainXpub) {
                return@update state.copy(
                    bareXpubError = "MEGA multisig currently accepts plain xpub/tpub for BIP48 cosigners. " +
                        "Export the descriptor or plain xpub/tpub from Sparrow.",
                )
            }
            if (pending.network != state.network) {
                fun networkName(network: WalletNetwork) = if (network == WalletNetwork.MAINNET) "mainnet" else "testnet"
                return@update state.copy(
                    bareXpubError = "This ${pending.displayPrefix} is for ${networkName(pending.network)}, but this vault " +
                        "is set to ${networkName(state.network)} — use a matching key or change the vault's network " +
                        "in the Policy step.",
                )
            }
            val trimmedLabel = label.trim()
            if (trimmedLabel.isEmpty()) {
                return@update state.copy(bareXpubError = "Enter a label for this cosigner.")
            }

            val path = defaultCosignerDerivationPath(state.network, state.scriptType, 0)
            val origin = try {
                completeBareCosignerExtendedKey(masterFingerprint, path, pending.extendedPublicKey)
            } catch (e: IllegalArgumentException) {
                return@update state.copy(bareXpubError = e.message ?: "Could not complete this cosigner.")
            }

            state.copy(
                slots = state.slots.withSlotFilled(index, origin, trimmedLabel),
                pendingSlotIndex = null,
                pendingBareXpub = null,
                bareXpubError = null,
                walletResult = null,
                walletError = null,
            )
        }
    }

    /** Dismisses the "Complete Cosigner Info" helper without filling
     * anything — the slot it was targeting is left exactly as it was before
     * the scan/paste that triggered the helper, since that attempt never
     * reached the point of marking the slot Filled or Invalid. */
    fun cancelBareXpubHelper() {
        _uiState.update { it.copy(pendingSlotIndex = null, pendingBareXpub = null, bareXpubError = null) }
    }

    /** Consumes text produced by the camera scanner. A single descriptor
     * fragment fills the slot marked by [beginFillSlot]; a full descriptor
     * replaces the whole slot set after the same policy checks used by
     * [fillManySlotsFromDescriptor]. */
    fun fillPendingSlotFromScannedText(text: String) {
        val index = _uiState.value.pendingSlotIndex
        val trimmed = text.trim()
        if (looksLikeFullDescriptor(trimmed)) {
            fillManySlotsFromDescriptor(trimmed)
            _uiState.update { it.copy(pendingSlotIndex = null) }
        } else if (index != null) {
            fillSlotFromPastedText(index, trimmed)
            // fillSlotFromPastedText may have just put us into the "needs
            // bare xpub origin info" state, which still needs pendingSlotIndex
            // to name the target slot until the helper completes or is
            // cancelled — only clear it when that ISN'T the outcome (slot
            // filled successfully, or marked invalid, exactly as before).
            _uiState.update { state -> if (state.pendingBareXpub != null) state else state.copy(pendingSlotIndex = null) }
        } else {
            _uiState.update { it.copy(walletError = "Choose a cosigner slot before scanning.") }
        }
    }

    /** Parses a full multisig descriptor and ADOPTS its own policy —
     * threshold, cosigner count, and network (read off the cosigners' own
     * key version bytes) — replacing whatever M/N/network was chosen on
     * the Policy step, rather than requiring them to already match. A
     * `wsh(sortedmulti(...))` string is self-describing; the ordinary
     * reason to paste or scan one is importing a vault someone else
     * already set up, where there is no reason the importer would already
     * know its exact M-of-N or network up front. Every cosigner still goes
     * through parseCosignerDescriptorFragment's full validation (inside
     * parseMultisigDescriptor) and the same duplicate-xpub check pasting a
     * single fragment gets, so this adopts the descriptor's policy without
     * weakening any of that.
     *
     * A descriptor is trusted to correctly describe its OWN vault (per the
     * validation above), but it is NOT trusted to silently discard cosigner
     * slots the user already filled and may have carefully verified — a
     * mistaken scan, or a maliciously crafted QR, could otherwise replace a
     * real cosigner set with a different one (potentially including an
     * attacker-controlled key) with no chance to notice before Build Vault.
     * So when any slot is already Filled, this stages the import into
     * [MultisigVaultUiState.pendingDescriptorImport] instead of applying it
     * — [confirmDescriptorImport] / [cancelDescriptorImport] resolve it.
     * When every slot is still empty (the common "importing a vault someone
     * else built" case this adoption behavior exists for), it applies
     * immediately with no added friction. */
    fun fillManySlotsFromDescriptor(text: String) {
        _uiState.update { state ->
            val parsed = try {
                parseMultisigDescriptor(text.trim())
            } catch (e: IllegalArgumentException) {
                return@update state.copy(walletError = e.message ?: "Could not parse this descriptor.")
            }

            val duplicateXpub = parsed.cosigners
                .groupingBy { it.extendedPublicKey }
                .eachCount()
                .any { it.value > 1 }
            if (duplicateXpub) {
                return@update state.copy(walletError = "Descriptor contains a duplicate extended public key.")
            }

            // parseMultisigDescriptor already guarantees at least one cosigner,
            // and every cosigner in it already passed parseCosignerDescriptorFragment's
            // own plain-xpub/tpub check — so this can only fail to resolve a
            // network if something upstream changes; kept as a guard rather
            // than a silent fallback so that case surfaces as an error instead
            // of an incorrect network.
            val network = parseBareCosignerExtendedKey(parsed.cosigners.first().extendedPublicKey)?.network
                ?: return@update state.copy(walletError = "Could not determine the network for this descriptor's cosigners.")

            val slots = parsed.cosigners.map { origin ->
                val label = "${origin.masterFingerprint} · ${origin.derivationPath}"
                MultisigSlot(status = SlotStatus.Filled(label), origin = origin)
            }

            if (state.slots.any { it.status is SlotStatus.Filled }) {
                return@update state.copy(
                    pendingDescriptorImport = PendingDescriptorImport(
                        threshold = parsed.threshold,
                        cosignerCount = parsed.cosigners.size,
                        network = network,
                        slots = slots,
                    ),
                    walletError = null,
                )
            }

            state.copy(
                m = parsed.threshold,
                n = parsed.cosigners.size,
                network = network,
                slots = slots,
                walletResult = null,
                walletError = null,
            )
        }
    }

    /** Applies a descriptor import staged by [fillManySlotsFromDescriptor]
     * after the user explicitly confirmed replacing their already-filled
     * cosigner slots. No-op if nothing is pending. */
    fun confirmDescriptorImport() {
        _uiState.update { state ->
            val pending = state.pendingDescriptorImport ?: return@update state
            state.copy(
                m = pending.threshold,
                n = pending.cosignerCount,
                network = pending.network,
                slots = pending.slots,
                pendingDescriptorImport = null,
                walletResult = null,
                walletError = null,
            )
        }
    }

    /** Discards a staged descriptor import — every already-filled slot is
     * left exactly as it was, since the import was never applied. */
    fun cancelDescriptorImport() {
        _uiState.update { it.copy(pendingDescriptorImport = null) }
    }

    /** Entry point for the dedicated "Scan Full Descriptor QR" action on the
     * Cosigners step — unlike [fillPendingSlotFromScannedText] (which a
     * per-slot camera icon uses, and which falls back to filling a single
     * slot for non-descriptor text), this only ever accepts a full
     * `wsh(sortedmulti(...))` descriptor; anything else is a clear,
     * specific error rather than being silently routed at whatever slot
     * [pendingSlotIndex] last happened to name. */
    fun fillManySlotsFromScannedText(text: String) {
        val trimmed = text.trim()
        if (looksLikeFullDescriptor(trimmed)) {
            fillManySlotsFromDescriptor(trimmed)
        } else {
            _uiState.update {
                it.copy(
                    walletError = "Expected a full multisig descriptor QR code (wsh(sortedmulti(...))). " +
                        "Use a cosigner slot's own camera icon to scan a single key instead.",
                )
            }
        }
    }

    /** Corrects an already-filled slot's master fingerprint in place —
     * the xpub and derivation path are untouched, so this can't turn a
     * validated cosigner into an invalid one, only relabel which device it
     * claims to be. Exists because entry paths that can't know the real
     * fingerprint (the "Complete Cosigner Info" bare-xpub helper) fill it
     * with the "00000000" unknown-origin placeholder by default; this is
     * how a user fills in the real one once they have it, without
     * re-entering the whole cosigner. Silently no-ops on an invalid
     * fingerprint or an empty/unfilled slot — the calling dialog only
     * enables its confirm action once the input is exactly 8 hex
     * characters, so this is defense-in-depth, not the primary check. */
    fun editSlotFingerprint(index: Int, newFingerprint: String) {
        _uiState.update { state ->
            val slot = state.slots.getOrNull(index) ?: return@update state
            val origin = slot.origin ?: return@update state
            val normalized = try {
                normalizeMasterFingerprint(newFingerprint)
            } catch (e: IllegalArgumentException) {
                return@update state
            }
            val slots = state.slots.toMutableList()
            slots[index] = slot.copy(origin = origin.copy(masterFingerprint = normalized))
            state.copy(slots = slots, walletResult = null, walletError = null)
        }
    }

    /** Corrects an already-filled slot's derivation path in place — the
     * counterpart to [editSlotFingerprint] for the other piece of origin
     * info "Complete Cosigner Info" no longer asks for up front (see
     * [completeBareXpubCosigner]'s doc comment): every bare-xpub cosigner
     * starts at the vault's standard account-0 BIP48 path, and this is how
     * a non-default account/path gets set afterward. Re-parses the FULL
     * fragment through [parseCosignerDescriptorFragment] rather than just
     * substring-replacing the path, so a bad path is rejected with the
     * exact same BIP48-shape validation a pasted/scanned fragment gets —
     * no separate, potentially-drifting path-only validator. Silently
     * no-ops on an invalid path or an empty/unfilled slot — see
     * [editSlotFingerprint]'s doc comment on why that's fine here too. */
    fun editSlotDerivationPath(index: Int, newPath: String) {
        _uiState.update { state ->
            val slot = state.slots.getOrNull(index) ?: return@update state
            val origin = slot.origin ?: return@update state
            val fragment = "[${origin.masterFingerprint}/${newPath.trim().removePrefix("m/")}]${origin.extendedPublicKey}"
            val updatedOrigin = try {
                parseCosignerDescriptorFragment(fragment)
            } catch (e: IllegalArgumentException) {
                return@update state
            }
            val slots = state.slots.toMutableList()
            slots[index] = slot.copy(origin = updatedOrigin)
            state.copy(slots = slots, walletResult = null, walletError = null)
        }
    }

    /** Corrects an already-filled slot's label in place — same "pencil
     * icon on the card, not locked in forever" treatment as
     * [editSlotFingerprint] and [editSlotDerivationPath], for the label
     * itself. Applies to a slot filled from any source (bare-xpub scan,
     * saved session, paste, full descriptor), not just the bare-xpub
     * "Complete Cosigner Info" path that first made a label mandatory
     * input — the label is just a display string regardless of where the
     * slot came from, so there's no reason to restrict which ones can be
     * relabeled. Rejects a blank label the same way completeBareXpubCosigner
     * does (a slot should never end up with no way to tell it apart from
     * the others), leaving the slot's existing label untouched; unlike
     * [editSlotFingerprint]/[editSlotDerivationPath] this doesn't touch
     * origin at all, only the status label. */
    fun editSlotLabel(index: Int, newLabel: String) {
        _uiState.update { state ->
            val slot = state.slots.getOrNull(index) ?: return@update state
            if (slot.status !is SlotStatus.Filled) return@update state
            val trimmed = newLabel.trim()
            if (trimmed.isEmpty()) return@update state
            val slots = state.slots.toMutableList()
            slots[index] = slot.copy(status = SlotStatus.Filled(trimmed))
            state.copy(slots = slots)
        }
    }

    fun clearSlot(index: Int) {
        _uiState.update { state ->
            val slots = state.slots.toMutableList()
            if (index in slots.indices) slots[index] = MultisigSlot()
            state.copy(slots = slots, walletResult = null, walletError = null)
        }
    }

    fun buildVault() {
        _uiState.update { state ->
            val m = state.m
            if (m == null) return@update state.copy(walletError = "Choose a signature threshold first.")
            if (!state.allSlotsFilled) return@update state.copy(walletError = "Fill every cosigner slot first.")
            val origins = state.slots.mapNotNull { it.origin }
            try {
                val wallet = buildMultisigWallet(m, origins, state.network)
                state.copy(step = MultisigSetupStep.RESULT, walletResult = wallet, walletError = null)
            } catch (e: IllegalArgumentException) {
                state.copy(walletError = e.message ?: "Could not build multisig wallet.")
            }
        }
    }

    /** Back from Result to Slots, to fix a slot without losing the others
     * or re-answering the policy questions. */
    fun backToSlots() {
        _uiState.update { it.copy(step = MultisigSetupStep.SLOTS, walletResult = null, walletError = null) }
    }

    fun beginSaveVault() {
        _uiState.update { it.copy(showSaveVaultDialog = true) }
    }

    fun cancelSaveVault() {
        _uiState.update { it.copy(showSaveVaultDialog = false) }
    }

    /** Called by MegaNavGraph after MultisigVaultRepository.saveVault
     * actually persists the vault — this ViewModel never touches storage
     * itself (see its own doc comment), it only reflects the outcome. */
    fun onVaultSaved(label: String) {
        _uiState.update { it.copy(showSaveVaultDialog = false, savedVaultLabel = label) }
    }

    fun dismissSavedVaultConfirmation() {
        _uiState.update { it.copy(savedVaultLabel = null) }
    }

    /** Discards the whole flow — called when leaving "Setup Multi-
     * Signature Vault" entirely (back from Policy, or after a successful
     * build is dismissed), so no cosigner data (all public, but still
     * specific to one vault attempt) lingers if the user starts over. */
    fun resetSession() {
        _uiState.value = MultisigVaultUiState()
    }

    private fun looksLikeFullDescriptor(text: String): Boolean = text.startsWith("wsh(")

    private fun List<MultisigSlot>.withSlotFilled(
        index: Int,
        origin: MultisigCosignerOrigin,
        label: String,
        passphraseUsed: Boolean? = null,
    ): List<MultisigSlot> {
        if (index !in indices) return this
        val duplicate = any { it.origin?.extendedPublicKey == origin.extendedPublicKey }
        val slots = toMutableList()
        slots[index] = if (duplicate) {
            MultisigSlot(status = SlotStatus.Invalid("This extended public key is already used in another slot."))
        } else {
            MultisigSlot(status = SlotStatus.Filled(label), origin = origin, passphraseUsed = passphraseUsed)
        }
        return slots
    }

    private fun List<MultisigSlot>.withSlotInvalid(index: Int, message: String): List<MultisigSlot> {
        if (index !in indices) return this
        val slots = toMutableList()
        slots[index] = MultisigSlot(status = SlotStatus.Invalid(message))
        return slots
    }
}
