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

**Not completed this session (existing PIN-gated test data found on
device):** the installed app already had a PIN and saved sessions from
prior testing (data preserved across `adb install -r`, as intended). The
real PIN was unknown, and MEGA has a duress-PIN wipe path
(`PinVerifyScreen.kt`), so further PIN guessing was deliberately stopped
after 2 failed attempts rather than risk triggering it or another wipe
condition. As a result, **Session save and retrieval**, **encrypted
backup export/import**, and the PIN-gated parts of **Settings/Privacy**
were not adb-validated this session — needs Bob, who knows the device's
actual PIN, to run those (see checklist items above).

**Human-required (Phase 4), not attempted this session:** physical
camera-to-camera QR round trip; multi-frame animated QR scanning;
interrupted/repeated/wrong-type/malformed/low-brightness QR cases;
migration-screen font/truncation review; wrong PIN/passphrase behavior
beyond the basic retry check above; app killed during signing and
relaunched.

**Release decision: DO NOT SHIP** as a full release-ready build — the
adb-driven matrix above passes, but PIN-gated data flows and every
Phase 4 human-required test remain unverified this session.
