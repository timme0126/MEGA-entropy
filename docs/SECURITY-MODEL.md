# Security Model

A plain-English threat model for MEGA. This mirrors the in-app Security
Model screen (`app/src/main/kotlin/org/mega/entropy/ui/security/SecurityModelScreen.kt`)
so the same claims are available to read outside the app, alongside the
source that backs them.

MEGA is a tool, not a guarantee. Below is what it protects against, and —
just as important — what it does not.

## MEGA does

- Derive mnemonic entropy only from the dice you enter — nothing else
  (see [`docs/NO-RNG-PROOF.md`](NO-RNG-PROOF.md))
- Work without any networking — there is no `INTERNET` permission in the
  manifest, and no networking code anywhere in the app
- Keep saved data inside this app's private sandbox
  (`context.filesDir`, never external storage — see
  [`docs/STORAGE-DESIGN.md`](STORAGE-DESIGN.md))
- Encrypt any data you explicitly choose to save, with AES-256-GCM via a
  dedicated Android Keystore key per session
- Optionally add a MEGA PIN as an extra access barrier, with rate-limited
  attempts and a scrambled keypad
- Suppress screenshots and recent-app thumbnails (`FLAG_SECURE`) on every
  screen that shows dice history or derived secrets
- Show every intermediate calculation, not just the final words — the goal
  is that a user (or reviewer) can trace exactly how each word was derived
- Allow independent verification of every step, by design: the algorithm,
  the vendored word list and its hash, and the test vectors are all in this
  repository

## MEGA does not protect against

- A compromised Android OS
- Malicious firmware
- Someone watching you type your dice or your seed (shoulder-surfing,
  hidden cameras)
- A malicious or biased physical die — MEGA has no way to verify the
  fairness of the die you actually rolled
- Someone obtaining your written-down seed after the fact
- A compromised keyboard, if one is ever used elsewhere with your seed
  (MEGA's own PIN/dice entry never uses the system keyboard, precisely to
  avoid this — see spec section 20)
- Flaws in your own physical entropy procedure (e.g. always favoring
  certain die faces, non-random placement)
- Undiscovered vulnerabilities in Android or the hardware itself
- A rooted or otherwise administratively-compromised device — app
  sandboxing assumes a stock, unmodified OS

MEGA is not marketed as unhackable, because nothing is. See
[`SECURITY.md`](../SECURITY.md) for reporting a vulnerability and the
current audit status.

## Rationale for a few specific decisions

- **Why no export feature at all (not even encrypted export)?** Every
  export mechanism (clipboard, share sheet, QR, screenshot, file, cloud)
  is also an exfiltration path if the device is compromised or another app
  is malicious. Removing the feature removes the entire attack surface
  category. This may be revisited in a future version with careful design,
  but v1 deliberately does not have it.
- **Why PBKDF2 for the PIN instead of Argon2/scrypt?** `PBKDF2WithHmacSHA256`
  is available in the standard Android/JVM crypto providers with no added
  dependency, keeping the dependency surface minimal (per this project's
  stated preference). It is explicitly documented as a UI-level access
  barrier, not as something with meaningful cryptographic entropy on its
  own — see `org.mega.entropy.security.pin.PinManager`'s KDoc.
- **Why per-session Keystore keys instead of one app-wide key?** Deleting a
  session can then also destroy that session's own key
  (`SessionCrypto.deleteKey`), rather than relying solely on file deletion.
