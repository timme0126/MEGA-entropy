# Beta Testing MEGA

MEGA is experimental software for deriving a BIP39 mnemonic from physical dice rolls. The beta goal is not to prove the app is ready for real funds; it is to expose mistakes, confusing screens, weak documentation, and any gap between the stated offline design and the actual Android behavior.

Do not use beta output to secure meaningful funds.

## What MEGA Is Trying To Solve

Most wallet seed generators ask the user to trust device randomness, firmware, wallet software, a hardware wallet, or an online tool. The educational point behind MEGA is the same "don't trust, verify" concern raised by paper seed-generation guides: if the entropy source is hidden, users cannot personally inspect the most important input. MEGA takes a narrower approach: the user supplies the entropy with a physical d6, and the app acts as a transparent calculator for turning those rolls into a valid BIP39 mnemonic.

The intended guarantee is:

```text
24-word mnemonic = f(100 user-entered d6 rolls)
```

The seed-generation path must not depend on Android randomness, clocks, device identifiers, network state, or hidden app state. Review [`NO-RNG-PROOF.md`](NO-RNG-PROOF.md) and [`ENTROPY-MATH.md`](ENTROPY-MATH.md) when testing that claim.

## Why An Offline GrapheneOS Phone

MEGA is an offline Android app. It requests no `INTERNET` permission, and the entropy pipeline lives in a pure Kotlin/JVM module with static checks that reject random, clock, UUID, and Android API imports.

GrapheneOS is not required for the math to work. It is recommended for serious testing because it lets a tester reduce device-level risk around the app:

- Disable MEGA's per-app Network permission and confirm the app still works.
- Use a fresh user profile dedicated to testing.
- Avoid Google Play Services unless your test specifically needs them.
- Use verified boot and a locked bootloader.
- Wipe the test profile after the session.

MEGA can run on an internet-connected Android device because the app itself is designed to be offline. That does not make the device safe. If the OS, firmware, keyboard, screen recorder, or another privileged component is compromised, MEGA cannot protect the mnemonic from that environment.

## Good Beta Test Reports

Please include:

- Device model, Android version, and whether GrapheneOS was used.
- App build source: commit hash, APK source, or release tag.
- Whether Network permission was disabled at the OS level.
- Whether the test used a fresh user profile.
- The exact screen where the issue appeared.
- Screenshots only if they do not reveal seed words, dice history, PINs, or passphrase material.
- Disposable dice-roll sequences only. Never send a real mnemonic.

## Test Paths

Run through:

1. Fresh install with Network permission disabled.
2. Full 100-roll entry.
3. Undo and edit behavior in earlier batches.
4. Bias-check rejection if you encounter one naturally.
5. Word derivation screen and paper lookup verification.
6. Save-session behavior with dice only.
7. Save-session behavior with mnemonic enabled.
8. PIN creation, unlock, failed attempts, and lockout.
9. Screenshot and Recent Apps blocking on sensitive screens.
10. Uninstall or user-profile deletion to confirm app data disappears.
11. Advanced Mode: manual mnemonic entry, wallet public key/address
    12. PSBT signing: import a disposable PSBT into Advanced Mode and an existing saved multisig vault; verify the transaction review shows amounts, fee information, signature state, and unknown values honestly; confirm that Cancel/Back never signs; then use "Confirm and Sign" and verify the partially-signed PSBT or finalized transaction can be imported by Sparrow. Test both single-frame base64 and animated BBQr input/output, including a real 2-of-2 multisig fixture. Never use a wallet containing meaningful funds.
    derivation, BIP85 child mnemonics, "Import via SeedQR" (both Standard
    and Compact SeedQR, e.g. from SeedSigner or Sparrow), and "Setup
    Multi-Signature Vault" (choose an M-of-N policy, fill each cosigner
    slot from a saved session, a pasted descriptor fragment, and a pasted
    full `wsh(sortedmulti(...))` descriptor, and a QR-scanned descriptor
    fragment/full descriptor — including a BBQr animated series, e.g.
    Sparrow's multisig descriptor export — then cross-check the resulting
    descriptor/address against another descriptor-aware wallet, e.g.
    Sparrow).

Use [`GRAPHENEOS-CHECKLIST.md`](GRAPHENEOS-CHECKLIST.md) for the full manual QA checklist.

## Verify By Hand

The repository includes paper references in [`docs/references/`](references/):

- `dplusplus-hex.pdf`
- `bip39-24-word-dice-worksheet.pdf`
- the vendored BIP39 English word list at `entropy-core/src/main/resources/bip39/english.txt`

Use [`HAND-VERIFICATION.md`](HAND-VERIFICATION.md) for the recommended workflow.
