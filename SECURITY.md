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

1. Independent source review of `:entropy-core` against BIP-0039
2. Independent re-derivation of this repository's test vectors
   (`docs/TEST-VECTORS.md`) using a separate, trusted implementation
3. Reproducible-build verification (`docs/REPRODUCIBLE-BUILD.md`) — confirm
   the APK you install actually matches this source
4. A formal, independent security/cryptography audit
