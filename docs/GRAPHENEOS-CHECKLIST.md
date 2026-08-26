# GrapheneOS Manual Test Checklist

A manual QA checklist for verifying MEGA on GrapheneOS (or stock Android —
most items apply either way). This is a checklist for a human to run
through on a real device; it complements, not replaces, the automated
suite (`./gradlew test lint securityAudit`).

**Do not use real Bitcoin seed material for any of this.** Roll dice for
these tests the same as normal use, but treat every resulting mnemonic as
disposable test data, never as a seed for a wallet holding real funds.

## Install and basic operation

- [ ] Install the APK via ADB: `adb install -r
      app/build/outputs/apk/debug/app-debug.apk` (see
      [`BUILD-AND-INSTALL.md`](BUILD-AND-INSTALL.md))
- [ ] App launches successfully with no Google Play Services present
- [ ] In GrapheneOS's per-app Network permission toggle (Settings → Apps →
      MEGA → Permissions, or the "Network" toggle GrapheneOS may show at
      install time): disable network access for MEGA entirely
- [ ] Confirm the app functions completely normally with network access
      disabled (expected — it never uses the network in the first place)
- [ ] Confirm there is no `INTERNET` permission requested: `adb shell dumpsys
      package org.mega.entropy | grep -A5 "requested permissions"` — should
      show no `android.permission.INTERNET` entry

## Dice entry and derivation flow

- [ ] Enter a full 100-roll test sequence, batch by batch
- [ ] Undo works correctly (removes the most recent roll, including
      reopening the previous batch if the current one is empty)
- [ ] Editing/reopening an earlier batch correctly recomputes everything
      after it, and the app doesn't let you silently overwrite a value
      without acknowledging the recalculation
- [ ] If your test sequence happens to get rejected at the Bias Check
      screen, confirm it clearly explains why and requires a full restart
      — never a partial retry
- [ ] Confirm the mnemonic is never shown before the Bias Check has passed
- [ ] "Show the math" / "Show technical details" expanders on the batch
      calculation card work and show correct values
- [ ] Final Mnemonic screen requires the deliberate "Reveal 24 Words" tap
      before showing anything
- [ ] After the fifth roll of each batch, the die grid, Undo, and Clear
      Batch all become briefly unresponsive (~1.1s) while the fifth roll
      stays visible, then the batch commits automatically — confirm this
      doesn't drop or double-count a roll under rapid tapping

## Privacy screens

- [ ] Background the app while on the Final Mnemonic (revealed) screen,
      then check the Recent Apps / Overview screen — the thumbnail should
      NOT show the mnemonic (should appear blank/obscured, due to
      `FLAG_SECURE`)
- [ ] Attempt a screenshot on the Final Mnemonic screen — it should be
      blocked or produce a black/empty image
- [ ] Repeat the screenshot/recent-apps check on the Dice Entry, Bias
      Check, 256-Bit Entropy, Checksum, Split Groups, and Word Derivation
      screens

## Saving and PIN

- [ ] Save a test session with "Save Dice Rolls" only; confirm it appears
      in Saved Sessions with the correct roll count
- [ ] Save a second test session with "Save Dice Rolls + Derived
      Mnemonic"; confirm the extra confirmation step actually appeared
      before it saved
- [ ] Close and reopen the app; confirm saved sessions persisted
- [ ] Enable a MEGA PIN (5–8 digits); confirm the confirm-PIN step
      correctly rejects a mismatched confirmation and makes you start over
- [ ] Verify the PIN keypad's digit layout is different each time the PIN
      screen is shown, and again after an incorrect attempt
- [ ] Enter an incorrect PIN 5 times in a row; confirm a lockout appears
      and that it does NOT delete or affect any saved session data
      (rate-limiting must never destroy user data)
- [ ] Rapidly tap a full PIN's worth of digits back-to-back (no pauses);
      confirm every digit registers once, in order, with none dropped or
      duplicated. Repeat rapidly tapping Delete (⌫) and Clear (CLR) mid-
      entry; confirm the digit count and Submit/CLR/⌫ enabled state stay
      correct throughout
- [ ] Background the app (e.g. press Home) while unlocked, then reopen it
      and navigate to Saved Sessions — confirm the PIN is required again
- [ ] Delete one saved session; confirm it disappears from the list
- [ ] Delete all MEGA data; confirm the Saved Sessions list is now empty

## About screen

- [ ] "Open GitHub source" is visible and tappable on the About screen
- [ ] Tapping it shows a confirmation dialog naming GrapheneOS, Samsung
      Knox Secure Folder, and Android Private Space
- [ ] Cancel dismisses the dialog and keeps the user inside MEGA
- [ ] Continue opens `github.com/timme0126/MEGA-entropy` in an external
      browser, leaving MEGA in the background (not closed)

## Uninstall and profile isolation

- [ ] Uninstall the app (`adb uninstall org.mega.entropy` or via system
      settings)
- [ ] Reinstall and confirm old saved sessions/PIN state do NOT return —
      app-private storage should be gone with the uninstall
- [ ] Create a secondary GrapheneOS user profile
- [ ] Install and use MEGA within that secondary profile (save a test
      session, set a PIN)
- [ ] Delete the secondary GrapheneOS user profile entirely
- [ ] Confirm the MEGA data that lived in that profile is gone (this is a
      property of Android/GrapheneOS profile isolation itself — MEGA does
      not implement or control profile deletion, it simply relies on
      standard app-sandbox behavior, per
      [`SECURITY-MODEL.md`](SECURITY-MODEL.md))

## Test Results Log

### 2026-08-26 — Release-readiness validation (public main, v0.1.13 hotfix)

**Device:** Pixel 9a, GrapheneOS build `2026081300`, Android 17 (device
`tegu`, adb serial `56221JEBF00854`).

**Build under test:** public `main` at commit `5f0f254` (fast-forwarded
from `c0bd6f6`, which added the About-screen GitHub link, on top of
`1485fd0` rapid-PIN-entry hotfix and `7c964c1` fifth-roll pause).
Debug APK SHA-256: `e9456f138f2f9f0ee3ff9735300ee9cfa51569880f1bdf0bd80d5ea0d7214f70`
(matches the `mega-beta-v0.1.13-debug-compat.apk` GitHub release asset,
replaced this session). `:app:testDebugUnitTest` and `:app:assembleDebug`
both passed, exit 0.

**Pre-existing finding (fixed this session, not part of the hotfix scope
above):** `MegaDestinations.kt` on public `main` still had 10 unused
`INHERITANCE_*`/`ASSISTANT` route-string constants left over from an
earlier commit — not wired into any screen or nav graph entry, so no
private functionality was actually reachable, but the names themselves
shouldn't appear in the public repo. Removed in commit `36fe2c4`.

**adb-driven tests performed and result:**
- About screen: "Open GitHub source" visible/tappable; confirmation
  dialog correctly names GrapheneOS, Samsung Knox Secure Folder, and
  Android Private Space; Cancel keeps user in MEGA (confirmed via
  `dumpsys activity`); Continue opens `github.com/timme0126/MEGA-entropy`
  in Vanadium externally while MEGA stays in the recent-tasks stack. **PASS**
- Dice-roll entry: batch/roll counters increment correctly (0→5→10 rolls,
  batch 1→2→3) across 10 rapid taps. Fifth-roll pause (1100ms, gates die
  grid/Undo/Clear Batch) verified in code (`DiceSessionViewModel.kt`,
  `FIFTH_ROLL_DISPLAY_MILLIS`) — could not visually catch the paused
  frame itself over adb (uiautomator round-trip exceeds 1.1s), but no
  dropped/miscounted rolls under rapid input. **PASS** (functional +
  code-verified; visual timing not directly observed)
- Rapid PIN entry: 6 rapid digit taps register correctly every time
  (CLR/⌫/Submit enable state matches digit count); ⌫ removes exactly one
  digit; CLR wipes the field and correctly disables CLR/⌫/Submit; Submit
  with a wrong PIN shows "Incorrect PIN (attempt N)" and correctly
  increments the counter; keypad digit layout reshuffles after a real
  submit. **PASS**
- No Inheritance Planning/Mega Assistant UI reachable: Advanced Mode menu
  (with existing test data's toggle already enabled) shows only Manual
  Seed Word Entry, Import from Saved Session, Import via SeedQR, Recover
  From Backup Shares, Multi-Signature Vaults, and Security Check — no
  inheritance/assistant entries. **PASS**
- Navigation/back behavior: Back from Dice Entry mid-session returns to
  "Before You Begin" (session state reset, no trap); Back from there and
  from Advanced Mode both return cleanly to Welcome. **PASS**
- `INTERNET` permission: confirmed absent via `dumpsys package
  org.mega.entropy` — only `OTHER_SENSORS`, `CAMERA`, and the app's own
  dynamic-receiver permission are requested. **PASS**

**PIN-gated validation continued (2026-08-26, same day, PIN supplied by
Bob: device test PIN `13579`, current settings Auto-lock=Immediately,
PIN pad layout=Randomized):**
- FLAG_SECURE: on the revealed Final Mnemonic screen, `adb shell
  screencap` (which is NOT itself blocked by FLAG_SECURE — it captures
  the raw framebuffer via a different path than a normal screenshot/
  MediaProjection) produced an image with only the system status bar
  visible and the entire content area solid black, confirming the
  in-app content itself is correctly hidden from capture. **PASS**
- Session save and retrieval: completed a real 50-roll dice session
  (random rolls, passed Bias Check on the first attempt), saved via
  "Save Dice Rolls + Derived Mnemonic" (the extra confirmation step
  correctly appeared first), labeled it, and confirmed it immediately
  appeared in Saved Sessions alongside the pre-existing `tutorial-demo`
  entry with the correct roll count and label. Opened it via View and
  confirmed all 10 batches of dice rolls matched exactly what was
  entered. **PASS**
- Encrypted backup export: entered a passphrase (+ confirmation), tapped
  Export, watched the real ~20s scrypt derivation (`N:131072, r:8, p:1`
  per the backup file's own header — this is why it takes a few
  seconds, not a sign of a hang), saved via the SAF picker to Downloads,
  and confirmed the resulting `.megabackup` file exists with the
  expected `MEGA-BACKUP-V1` header format. **PASS**
- Encrypted backup import: **found and fixed a real bug** — see below.
  After the fix, verified end-to-end: picked the exported file, the
  passphrase dialog correctly appeared even after the forced PIN
  re-entry, entered the same passphrase, and both saved sessions were
  correctly restored (matching mnemonics, though restored as "Manually
  entered seed" rather than with the original dice rolls — the backup
  format stores each session's *resolved* mnemonic, not its raw dice
  rolls, so a restored session can no longer show/edit the original
  physical rolls; this looks like an intentional scope tradeoff, not
  itself a bug, but worth Bob confirming it's the intended restore
  behavior). Import does not deduplicate against an existing session
  with the same label — it always adds a new entry. That's reasonable
  for the primary use case (restoring onto a fresh second device) but
  worth being aware of if re-importing the same backup onto a device
  that already has some of its sessions. **PASS** (after fix)

**Bug found and fixed this session: Import Backup silently discarded
the picked file under Auto-lock=Immediately.** Launching the required
system file picker triggers `ON_STOP`, arming the saved-session lock
before the picker even returns; the resulting forced PIN re-entry tears
down the Settings screen's composition (Navigation-Compose only keeps
the current top-of-backstack destination composed), discarding
`BackupCard`'s plain `remember` state. The picker's async result
callback still fires afterward and sets `showingImportPassphraseDialog =
true`, but on an orphaned, unobserved State object — so the passphrase
dialog never appears and the import does nothing, with no error shown
to the user. Root-caused with temporary instrumentation logging (removed
before committing) confirming the exact sequence via logcat timestamps.
Fixed by moving the pending-import bytes and PIN-required flag into
`BackupViewModel` (scoped to the Settings back-stack entry, which is
never popped by this flow, only temporarily obscured — so it survives).
Verified fixed on-device: passphrase dialog now appears correctly after
re-auth, import completes, sessions restored. Commit `7465d45`. Shipped
as part of this session's hotfix — see [[project_mega]] memory for full
detail and the debug-compat APK/release-notes update that went with it.

Also observed (not itself a bug, just UX friction worth noting): under
Auto-lock=Immediately, backing out of any PIN-gated screen (Settings,
Saved Sessions) shows the PIN entry screen one extra time before
reaching Welcome, rather than going straight there — every protected
destination appears to sit in the back stack underneath an extra
lock-gate layer. Minor, but generates more PIN prompts than a user might
expect from a single "leave" action.

**Not completed this session:** the pre-existing app had no clean way to
test "wrong PIN" beyond confirming the retry-counter mechanics (already
covered above) without risking the duress-PIN wipe path, since the
actual duress PIN (if one is configured on this device) is unknown to
this session — only Bob, or a fresh test device with a known duress PIN
set up, can safely exercise that specific path.

**Human-required (Phase 4), not attempted this session:** physical
camera-to-camera QR round trip; multi-frame animated QR scanning;
interrupted/repeated/wrong-type/malformed/low-brightness QR cases;
migration-screen font/truncation review; wrong PIN/passphrase behavior
beyond the basic retry check above; app killed during signing and
relaunched.

**Release decision: DO NOT SHIP** as a full release-ready build — the
full adb-driven matrix (Phases 1-3) now passes, including a real bug
found and fixed mid-session, but every Phase 4 human-required test
remains unverified.
