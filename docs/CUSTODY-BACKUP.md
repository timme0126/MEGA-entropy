# Custody Backup+ (MEGACUSTODY-V1)

Sealed transport container for the third-party custody arrangement:
the client wraps their **complete standard MEGA backup file** plus **one
attached document** (e.g. estate planning documents) into a single
opaque container, gives the **passphrase** to a trusted third party,
and sends the container file to a custodian (e.g. their Bitcoin
Advisor) for safekeeping.

Reached in-app via: Advanced Mode → **Encrypted Backup+**.

## Format

```
file   = header || AES-256-GCM(aad = header, inner)
header = plaintext US-ASCII "KEY:value\n" lines:
  MEGACUSTODY-V1 / V:1 / N / R / P / SALT(hex128) / IV(hex96)
  NAME:<attachment filename, <=256 UTF-8 bytes>
  PSIZE:<inner length> / FSIZE:<attachment length> / END-HEADER
inner  = "MEGACUSTODY-INNER-V1\n" + three length-prefixed records:
  name | complete standard MEGA-BACKUP-V1 file | attachment bytes
```

- KDF: scrypt **N=2^17, r=8, p=1** — the same production parameters as
  the ordinary backup file. No other cost values parse.
- Cipher: AES-256-GCM, 128-bit tag, fresh 16-byte salt + 12-byte IV per
  container.
- The header is the GCM **additional authenticated data**: tampering
  with any metadata line — including an attempted KDF downgrade —
  fails authentication before the key is ever exercised.
- Hostile headers are rejected at **parse time, before any key
  derivation**, so a malicious file can never force unbounded memory.
- Transport integrity: SHA-256 fingerprint of the whole container is
  shown at creation for out-of-band comparison by sender and custodian.

## One secret per arrangement

The SAME passphrase encrypts the inner standard backup and the outer
envelope. The trusted third party therefore holds exactly one secret;
the client restores a fresh MEGA install with the normal
Settings → Backup import using that same passphrase. Nothing about the
envelope is needed for restore tooling.

## Streaming

Create and verify stream the attachment through the cipher in a 64 KB
window straight to/from the SAF-destined file — an arbitrarily large
attachment never materializes in memory. Files written by either code
path are byte-identical in framing (covered by unit tests).

Verify behavior by size:

- ≤ 32 MB: fully authenticated in memory **before** any output file is
  written — a wrong passphrase leaves nothing behind;
- \> 32 MB: streamed; the GCM tag can only fail at the final block, so
  a wrong-key attempt leaves two garbage files that must be deleted
  (the UI states this).

## Usage

Create: pick/enter a passphrase (≥ 16 chars, or generate a 256-bit one
— shown exactly once, copy or write it down immediately) → attach one
document → **Create Encrypted Container** → save the file → record the
on-screen fingerprint and share it out-of-band.

Verify: Advanced Mode → Encrypted Backup+ → choose the container (no
passphrase needed to see its header) → enter the passphrase → choose
destinations for the two extracted records. Success proves the
passphrase, the container's integrity, and the fidelity of the
transfer in one step.

## Honest caveats

1. **The custodian trusts the key-holder, not the container.** The
   attachment is arbitrary user data; MEGA proves only that
   passphrase + container match.
2. **The passphrase is the weak link.** Client-chosen passphrases can
   be weak; scrypt slows guessing but cannot fix "password123…".
   Encourage the generated 256-bit passphrase for anything sensitive.
3. **Anyone with both the container and the passphrase holds the
   client's full wallet.** That is the arrangement by design — the
   client must understand it.
4. **No recovery, ever.** Lose the passphrase and the container is
   permanently unreadable. There is no back door, by design.
5. **Size disclosure.** The plaintext header exposes the attachment
   filename and approximate sizes.
6. **A custody backup is a transfer envelope, not redundancy.** Keep
   ordinary backups per the standard guidance.

## Threat-model notes for reviewers

- All file movement is user-chosen SAF documents; no new permissions,
  no app-private staging of user files.
- Key material is zeroed as soon as each use completes; inner backup
  bytes are zeroed after framing.
- The generated custody passphrase is a new random secret — never
  derived from, and never able to reveal, anything already on device.
- `entropy-core` contains no crypto imports: the envelope helpers are
  pure framing/KDF-argument parsing, independently testable and
  auditable (`CustodyEnvelopeTest`).
