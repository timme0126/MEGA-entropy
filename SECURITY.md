# Security Policy

MEGA is experimental software that, if used as intended, can control
access to real funds. Please read this before using it for anything beyond
testing, and before reporting an issue.

## Status: not yet independently audited

MEGA has not undergone an independent security audit. Passing this
project's own test suite and static checks (`./gradlew test lint
securityAudit`) is a baseline, not a certification. See
[`docs/NO-RNG-PROOF.md`](docs/NO-RNG-PROOF.md) for the argument that device
randomness cannot influence the derived mnemonic, and
[`docs/SECURITY-MODEL.md`](docs/SECURITY-MODEL.md) for what this app does
and does not protect against.

**Do not use MEGA to generate a seed for a wallet holding meaningful funds
until it has had an independent review.** See "Next security steps" below.

## Reporting a vulnerability

If you find a security issue in MEGA — anything that could cause the
derived mnemonic to depend on something other than the user's 100 dice
rolls, or that could expose saved dice/mnemonic data — please report it
privately rather than opening a public issue, so users aren't put at risk
before a fix is available. Open a private security advisory on this
repository's GitHub page (Security tab → "Report a vulnerability") if
available, or contact the maintainer listed in the repository's profile
directly. Include:

- The affected file(s)/function(s)
- Steps to reproduce, or a test case
- What you'd expect vs. what actually happens

## What's in scope

- Anything that lets device/network state influence wallet entropy
  (see `:entropy-core` and `docs/NO-RNG-PROOF.md`)
- Anything that reads or exfiltrates saved dice rolls, mnemonics, or PIN
  material without user action
- Flaws in the encryption of saved sessions (`org.mega.entropy.storage`) or
  PIN handling (`org.mega.entropy.security.pin`)
- Incorrect BIP39 derivation (wrong checksum, wrong word indices, wrong
  rejection-sampling threshold)

## What's out of scope

- A compromised Android OS, malicious firmware, or a physically compromised
  device — see `docs/SECURITY-MODEL.md` for the full list of accepted
  limitations
- Issues that require root or a modified OS to exploit are still worth
  reporting, but are lower priority than issues exploitable on a stock
  device

## Next security steps before trusting MEGA with real funds

1. **Done (automated, not human-independent):** [`docs/CODEX-AUDIT-ENTROPY-CORE.md`](docs/CODEX-AUDIT-ENTROPY-CORE.md)
   — a source review of `:entropy-core` against BIP-0039, run with no access
   to any of this repo's other audit docs so it couldn't just echo them back
   (see the correction note in
   [`docs/SECURITY-AUDIT-ENTROPY-CORE.md`](docs/SECURITY-AUDIT-ENTROPY-CORE.md)
   for why that isolation mattered). Found one real MEDIUM finding — public
   low-level APIs in `:entropy-core` could be composed to bypass rejection
   sampling — not caught by either prior pass; fixed the same day (see the
   remediation-status note at the top of the report). Still AI-run, not a
   substitute for #4.
2. **Done:** [`docs/CODEX-INDEPENDENT-DERIVATION.md`](docs/CODEX-INDEPENDENT-DERIVATION.md)
   — a from-scratch reimplementation ([`tools/independent_derivation.py`](tools/independent_derivation.py)),
   written with no access to this repo's Kotlin source, matches all 5
   vectors in `docs/TEST-VECTORS.md`.
3. **Partial:** two clean rebuilds on the same machine produced
   byte-identical debug APKs (rules out incidental build non-determinism),
   but full reproducible-build verification needs the release-build/signing
   pipeline described as future work in
   [`docs/REPRODUCIBLE-BUILD.md`](docs/REPRODUCIBLE-BUILD.md), which doesn't
   exist yet.
4. **Not done, and can't be done by any AI tool run by the project itself**
   run by the project itself — this needs a human/firm with no relationship
   to development. Steps 1-3 are defense-in-depth evidence for this step,
   not a substitute for it.
