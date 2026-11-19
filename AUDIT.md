# Audit record — WinLauncherRoot

Audit date: 2026-11-19. Scope: the 13 delivered text parts restored to 36 files, static review of
all 23 Java sources plus the root bridge protocol, fixes for the defects found, and re-verification.

## Verification status

| Check | Result | Reproduce |
|---|---|---|
| File restoration | 36/36 files from 13 parts, no guesswork reconstruction | `python3 tools/verify_quality.py` |
| Static source checks | 43/43 PASS, exit 0 | `python3 tools/verify_quality.py` |
| Java compilation | 0 errors, 63 `.class` files (23 top-level + 40 nested/anonymous) | see README "Сборка" and REPORT.md |
| Gradle/AGP build | **not run** — no Android SDK, no Gradle, no AGP 8.6.1 in the audit sandbox | — |
| Device/runtime | **not run** — no SM-T555 device, no root | — |

Static checks and a javac compile against a bootclasspath substitute are **not** an Android build,
not an APK installation and not device acceptance. `TEST_PLAN.md` rows stay "Not run".

## Delivery fixes that were already present before upload

The four compile-blocking defects were fixed locally before the bundle was sent and are verified
present in the restored sources (they are *not* re-applied by this audit):

| # | File | Verification |
|---|---|---|
| a | `ChromeManager.java` | call site `trackShake(w,x)` matches declaration `trackShake(Win w,float x)` |
| b | `TaskbarOverlay.java` | `lastWindows` declared before the `hideRunnable` lambda, one declaration only |
| c | `ChromeManager.java` (DragTouch) | `longPress` assigned in the constructor after `this.chrome=chrome` |
| d | `SnapController.java` | `hideGroupHint` assigned in the constructor after `this.wm=...` |

## Defects found and fixed by this audit

25 code-marked fixes were applied across 16 source files (the full table, with the nine items
left open and the four withdrawn after analysis, is in REPORT.md). Each fix carries a comment at the
site with its `S<number>` id. Highlights:

- **S1** status-bar retry storm and an over-broad `disable()` mask that also removed Back/Home/Recents.
- **S2** no socket timeout: a hung RootServer stalled every queued command forever.
- **S4** every 750 ms poll re-rendered every chrome, grip, divider, taskbar button and Task View row.
- **S6** `restoreToNormal` mutated state/stack before `moveToStack` succeeded (model/stack divergence).
- **S15** the "token" travelled in clear and was ignored by any listener — now mutual SHA-256
  proof bound to a fresh 128-bit nonce, plus peer-uid check and so-timeouts.
- **S17/S18** `app_process` was started without `ANDROID_ROOT`/`ANDROID_DATA`, and every shell helper
  was invoked by bare name from an environment with no `PATH`.

## Defects found and deliberately not fixed

| Id | Defect | Reason |
|---|---|---|
| S3 | 750 ms polling cannot observe tasks that appear and vanish between ticks | needs `IActivityManager.registerTaskStackListener` (hidden API, API 24+ only) — out of scope for the API 25 target and unverifiable without the device |
| S19 | drag base rectangle | verified **not** a defect: the delta is absolute from `startBounds`, and `requestBounds` already assigns `w.bounds` in place, so the base object is the same entity — no fix needed |
| S22 | caption under a visible status bar | `FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS` on a `TYPE_SYSTEM_ALERT` overlay is untested and can push the caption under the system bar; left as documented behaviour |
| S23 | management screens stay in the Back stack | `setTaskAlwaysFinish` is a hidden `Activity` method absent from the public SDK; calling it would break the real Gradle build |
| S33 | `AccessibilityServiceInfo` flags in `onServiceConnected` vs `key_service_config.xml` | the XML already carries them; changing runtime flags is unverifiable without a device |
| S34 | `GlobalScope`-style listener churn in overlays | cosmetic; each overlay owns its lifecycle correctly |
| S38 | `getAllStackInfos` field names (`taskBounds`, `taskNames`) | firmware-dependent hidden `StackInfo` layout — must be confirmed by E-5 on the device |
| S40 | `svc bluetooth` availability on Samsung | vendor feature; already documented in README and covered by R-04 |

## Known non-defects

- `tools/verify_quality.py` contained a stray `))))` in PART 2. This was a delivery transcription
  artifact repaired during restore and is **not** a project defect.
- `.gitignore` is present; the earlier baseline snapshot omitted it because `cp -r tree/*` skips
  dotfiles.
