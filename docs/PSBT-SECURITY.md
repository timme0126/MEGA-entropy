# PSBT Signing Security

How MEGA's PSBT (BIP174) signing is kept safe, and what a reviewer should
check first. This documents the security invariants of the signing pipeline
added in v0.1.8–v0.1.9 and hardened after the v0.1.9 audit.

## The one-sentence rule

**The transaction the user reviews is the transaction the signature commits
to — or nothing is signed at all.**

## Pipeline

```
scan (camera, local-only)  →  review (computePsbtSummary, no keys touched)
  →  explicit "Confirm and Sign" tap  →  sign + finalize (entropy-core)
  →  result (final tx, or updated PSBT for the next cosigner)
```

- Scanning never signs. There is no navigation path to the signing screen
  except the review screen's Confirm action, and signing state never
  survives process death (all flow state is in-memory `remember` state,
  cleared on every exit path).
- Signing executes at most once per distinct (psbt, seed, passphrase,
  cosigner) input set — it is cached in `remember`, so recomposition can
  never re-sign.
- The saved-vault flow additionally verifies the candidate seed's BIP32
  master fingerprint against the selected cosigner twice: once on the
  verify screen before the scanner even opens, and again inside
  `signPsbtForCosigner` immediately before signing.

## What the signer refuses (fail-closed, all covered by tests)

- **Any sighash type other than SIGHASH_ALL** (absent means ALL per
  BIP143). NONE, SINGLE, ANYONECANPAY variants, or out-of-range values
  abort the whole signing operation (`validateSighashType` in
  `PsbtSigning.kt`). A non-ALL signature does not commit to every output
  shown in review, which is precisely the attack this flow exists to
  prevent.
- **Inputs whose UTXO doesn't commit to the script being signed.** For
  P2WSH the witness_utxo's scriptPubKey must be exactly
  `OP_0 <sha256(witnessScript)>`; for P2WPKH the program must equal
  `hash160` of the derived pubkey. Mismatches are skipped, never signed.
  This also excludes P2SH-wrapped inputs, which MEGA cannot correctly
  finalize (no final_scriptSig support).
- **Inputs that are already finalized** are never re-signed.
- **Keys the device doesn't control.** A partial signature is only added
  when the input's bip32 derivation names this device's master fingerprint
  AND the key derived along the stated path actually equals the claimed
  pubkey.
- **Malformed PSBTs**: duplicate keys within a map (BIP174 requires
  uniqueness), a non-canonical or witness-serialized unsigned transaction,
  or non-empty scriptSigs in the unsigned transaction are all rejected at
  parse time.
- **Non-canonical PSBT encodings** (added after the second review pass —
  see [`SECURITY-AUDIT-V0.1.9.md`](SECURITY-AUDIT-V0.1.9.md) NEW-1..NEW-3):
  a global unsigned-transaction key must appear exactly once and carry no
  keydata, and no key may carry keydata for a type BIP174 defines as
  un-keyed (`witness_utxo`, `sighash_type`, `witness_script`,
  `final_scriptWitness`, …). Without this, a decoy key such as `01 aa`
  would be returned by the accessor's `find { keyType == 0x01 }` ahead of
  the real one — letting a file show MEGA one value and a strict peer
  another. Trailing bytes after the final output map are rejected, so the
  signed PSBT is never merely a prefix of what was scanned. Compact-size
  varints must use their minimal form, and an 8-byte length with bit 63
  set (which reads back negative and would slip past bounds checks) is
  refused outright.
- **Non-standard multisig finalization**: `finalizePsbt` only finalizes
  exact `OP_M <pubkeys> OP_N OP_CHECKMULTISIG` templates whose script
  matches the spent UTXO; anything else stays unfinalized rather than
  becoming an invalid "final" transaction.
- **BBQr zip bombs**: 'Z'-encoding inflate output is capped (8 MB); every
  frame's Base32 chunk must independently decode to whole bytes (Sparrow
  compatibility); conflicting same-index frames in the scanner are
  reported, never silently mixed.

## What the review shows (and how it stays honest)

- Network (caller-known, or inferred from derivation-path coin types and
  labeled as such), input/output counts, per-output amounts and addresses
  (bech32 where decodable, raw hex otherwise — never a guessed encoding),
  total in/out, fee, estimated fee rate, existing signatures, required
  threshold, whether this device can sign, whether signing finalizes.
- Anything undeterminable is shown as **Unknown**, never guessed.
- **Blocking** conditions (no Confirm button offered): an unsupported
  sighash request, or outputs exceeding inputs (negative fee).
- **Warnings**: fee above 10% of total inputs; a change-looking output
  that could not be verified.
- **Change verification**: in the saved-vault flow, an output is only
  labeled "Change back to this vault (verified)" when
  `verifyVaultChangeOutput` cryptographically re-derives the vault's
  change address from the stored cosigner xpubs and the output matches.
  Fingerprint-matching metadata alone is explicitly reported as
  unverified — a coordinator can fabricate it.

## Known limits (by design)

- Only bare P2WPKH (single-sig) and bare P2WSH sortedmulti (multisig)
  inputs are signed. No P2SH wrapping, no Taproot, no legacy.
- Only SIGHASH_ALL is signed (see above).
- The fee-rate figure is an estimate (pre-signature witness sizes vary);
  the fee itself in sats is exact whenever every input amount is known.
- Amounts come from each input's witness_utxo. A PSBT without them is
  shown with Unknown amounts and MEGA will not sign it — the segwit
  sighash commits to the amount, so signing without it is impossible to
  do honestly anyway. (For P2WPKH/P2WSH inputs, a malicious coordinator
  lying about witness_utxo amounts produces signatures that won't
  validate against the real UTXO set — the transaction would be
  rejected by the network, not silently steal the difference as in the
  legacy-inputs case. MEGA never signs legacy inputs at all.)
