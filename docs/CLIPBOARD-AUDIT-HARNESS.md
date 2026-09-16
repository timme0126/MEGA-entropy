# Clipboard retention audit harness

`app/src/androidTest/kotlin/org/mega/entropy/ClipboardRetentionInstrumentedTest.kt`
validates the security-audit finding that the 60s clipboard auto-clear in
`MegaCopyIconButton` is a composition-scoped `LaunchedEffect`: if the app is
task-removed, force-stopped, or crashes within 60s of a copy, the clear never
runs and the clip persists in the system clipboard (owned by ClipboardService,
not the app process).

## Running it (two phases across process death)

```bash
adb logcat -c
RUN=clip-$(date +%s)
# 1. Write a sensitive-marker clip while MEGA MainActivity is focused,
#    exactly like the copy button does (EXTRA_IS_SENSITIVE included).
adb shell am instrument -w \
  -e class org.mega.entropy.ClipboardRetentionInstrumentedTest \
  -e phase write -e runid $RUN \
  org.mega.entropy.test/androidx.test.runner.AndroidJUnitRunner

# 2. Kill everything and wait past the 60s timer.
adb shell am force-stop org.mega.entropy.test
adb shell am force-stop org.mega.entropy
adb shell input keyevent KEYCODE_HOME
sleep 70

# 3. Fresh process: is the marker still on the clipboard?
adb shell am instrument -w \
  -e class org.mega.entropy.ClipboardRetentionInstrumentedTest \
  -e phase read -e runid $RUN \
  org.mega.entropy.test/androidx.test.runner.AndroidJUnitRunner

adb logcat -d -s ClipAudit:V
```

Result format: `retained_after_process_death=true|false`.

## Observed result (2026-09-16)

Pixel 9a, Android 17 / SDK 37, GrapheneOS: `retained_after_process_death=true`
— the marker survived force-stop + kill-all + 70s verbatim. Classified:
mechanism confirmed, severity low (seed/WIF copy is opt-in via
allowSeedCopy/allowPrivateKeyExport, target devices are air-gapped, and
EXTRA_IS_SENSITIVE suppresses preview UI though not retention). Optional
follow-up: repeat on an OEM skin with persistent clipboard history
(Samsung/Gboard) if testers use one.
