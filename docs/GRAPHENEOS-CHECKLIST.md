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
- [ ] Background the app (e.g. press Home) while unlocked, then reopen it
      and navigate to Saved Sessions — confirm the PIN is required again
- [ ] Delete one saved session; confirm it disappears from the list
- [ ] Delete all MEGA data; confirm the Saved Sessions list is now empty

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
