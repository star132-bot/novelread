# MKread Phase 6 Acceptance Hardening And Release Gates Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prove the complete MKread Android application works offline without data loss, survives hostile inputs and long playback, meets scale/accessibility targets, and cannot be publicly released without licensing and ARM64 performance evidence.

**Architecture:** Add deterministic acceptance fixtures and scripts around the already separated import, reader, voice, speech, and service boundaries. Build debug acceptance for x86_64 emulator and an arm64-only release candidate, collect reproducible evidence, and enforce license/authorized-voice checks as Gradle release prerequisites.

**Tech Stack:** Gradle verification tasks, AndroidX instrumentation/Compose tests, ADB and PowerShell automation, StrictMode, Room migration schemas, Media3 session inspection, Android meminfo/thermal/battery diagnostics, R8 release build.

---

## Task 1: Turn Licensing And Built-In Voice Into Build Gates

**Files:**
- Create: `docs/licenses/component-inventory.md`
- Create: `docs/licenses/builtin-voice-consent.md`
- Create: `docs/licenses/release-decision.md`
- Create: `scripts/verify-release-assets.ps1`
- Modify: `app/build.gradle.kts`
- Modify: `.gitignore`
- Test: `scripts/tests/verify-release-assets.Tests.ps1`

- [ ] Create an inventory row for sherpa-onnx code, ZipVoice code, ZipVoice model, model training-data terms/provenance, Vocos code/model, eSpeak data, built-in prompt/voice identity, every bundled font, and every redistributed fixture. Each row has source URL/version/hash/license text location/distribution decision/reviewer/date.

- [ ] Set `docs/licenses/release-decision.md` to `Decision: INTERNAL_ONLY` until every inventory row is explicitly `APPROVED_FOR_DISTRIBUTION`. This is an enforceable current decision, not an undecided entry.

- [ ] Define the built-in consent record fields: voice id, speaker/legal authorizer, recording date, exact prompt transcript hash, WAV hash, permitted uses, prohibited uses, revocation/contact path, reviewer, and approval date. Do not place personal contact details in debug logs or UI.

- [ ] Put release-only inputs under ignored `.local-assets/release-assets/` with model files plus `voices/com.mkread.voice.builtin/{manifest.json,checksums.json,prompts/neutral.wav,prompts/neutral.txt}`. Configure only the release source set to read that directory after verification. Debug assets remain under `.local-assets/debug-assets/`; never mount a debug prompt directory into a release source set.

- [ ] Implement `verify-release-assets.ps1` to require `release-decision.md` value `APPROVED`, validate every inventory decision, reject `builtin-dev` ids or `leijun-1.wav`, run `.mkvoice`-equivalent manifest/WAV/checksum validation over the built-in directory, and compare hashes with the signed-off consent record.

- [ ] Add Pester tests with temporary trees for missing decision, one unapproved component, development prompt leak, wrong transcript/WAV hash, missing style, and a fully approved synthetic fixture. The successful fixture uses generated silence and is not committed as a real release voice.

- [ ] Make `preReleaseBuild` depend on `verifyReleaseAssets`. Keep debug builds available while the decision is `INTERNAL_ONLY`; a release build must fail with `Public release assets are not approved` until approvals and authorized assets actually exist.

- [ ] Verify current safe failure:

```powershell
Invoke-Pester scripts/tests/verify-release-assets.Tests.ps1
.\gradlew.bat assembleRelease
```

Expected: Pester cases pass; current release build fails specifically at `verifyReleaseAssets`, not compilation. It becomes successful only after real approvals and authorized assets are supplied.

- [ ] Commit:

```powershell
git add .gitignore app/build.gradle.kts docs/licenses scripts/verify-release-assets.ps1 scripts/tests/verify-release-assets.Tests.ps1
git commit -m "build: enforce MKread distribution approvals"
```

## Task 2: Consolidate Hostile-Input And Recovery Suites

**Files:**
- Create: `app/src/test/java/com/mkread/app/security/HostileInputCorpusTest.kt`
- Create: `app/src/androidTest/java/com/mkread/app/security/RecoveryMatrixTest.kt`
- Create: `docs/test-evidence/security/corpus.md`

- [ ] Generate, rather than commit, deterministic mutations for TXT invalid byte sequences/control density/limit overflow; EPUB traversal/duplicate/DOCTYPE/entity/ZIP bomb/no spine; `.mkvoice` traversal/symlink/duplicate/checksum bomb/WAV corruption; Room/file mismatch; invalid pagination JSON; invalid WAV cache; and corrupted DataStore settings.

- [ ] Give every corpus item a stable id and expected typed reason. Write a parameterized host test that asserts the parser/validator returns that reason within five seconds and writes nothing outside its temporary staging root.

- [ ] Write Android recovery tests that inject process interruption at every import, edit, voice-install, cache-commit, checkpoint, and service-restore boundary. After restart run reconciliation and assert one of two states only: previous complete state or new complete state; never a visible partial row/file pair.

- [ ] Enable debug StrictMode for disk/network on main thread, leaked closable objects, and untagged network use. Use `penaltyLog` plus a test listener that fails acceptance for MKread-owned violations while allowing documented emulator/framework noise by exact class signature rather than broad text filtering.

- [ ] Assert logs never contain fixture prose, transcript sentences, raw content URIs, or prompt file bytes. IDs, hashes, byte counts, and error codes are allowed.

- [ ] Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests com.mkread.app.security.HostileInputCorpusTest
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.mkread.app.security.RecoveryMatrixTest
```

Expected: all corpus ids return their expected reason, recovery converges, and no StrictMode violation is attributed to MKread code.

- [ ] Record corpus id/expected/actual/write-count/runtime in `corpus.md` and commit:

```powershell
git add app/src/test/java/com/mkread/app/security app/src/androidTest/java/com/mkread/app/security docs/test-evidence/security/corpus.md
git commit -m "test: harden hostile input and recovery paths"
```

## Task 3: Automate The Full Airplane-Mode Journey

**Files:**
- Create: `scripts/run-offline-acceptance.ps1`
- Create: `app/src/androidTest/java/com/mkread/app/acceptance/OfflineJourneyTest.kt`
- Create: `docs/test-evidence/emulator-api35/offline-journey.md`

- [ ] Make the script resolve JDK/SDK, build/install while the workstation may be online, then disable emulator Wi-Fi and cellular data before launching MKread. Save prior radio states and restore them in `finally`, even when tests fail.

- [ ] Confirm offline state with:

```powershell
adb shell svc wifi disable
adb shell svc data disable
adb shell dumpsys connectivity
```

Expected before the journey: no active default network. The app package itself still has no Internet permission regardless of emulator state.

- [ ] Write one ordered journey: cold launch; import UTF-8/GB18030 TXT and EPUB through test DocumentsProvider; verify chapters; search/sort/rename; open reader; paginate/turn/select/copy; edit/save/undo; select built-in voice; generate Chinese and English; toggle emotion off/on; change speed/theme/typography; background with notification controls; import/select/preview a valid `.mkvoice`; kill/recreate UI; restore exact semantic sentence; remove book and voice; verify external source hashes unchanged.

- [ ] Capture each checkpoint as structured instrumentation output with step id, start/end monotonic time, book/chapter/sentence ids, and pass/fail. Do not include prose or prompt transcript in output.

- [ ] Add a network guard test using `ConnectivityManager` plus package permission inspection; the journey fails if any MKread-owned socket appears in `/proc/<pid>/net/{tcp,tcp6,udp,udp6}` after startup and narration.

- [ ] Run:

```powershell
.\scripts\run-offline-acceptance.ps1
```

Expected: every journey step passes without an active network and the script restores emulator radio state.

- [ ] Save Git commit, APK hash, radio state proof, package permissions, step table, source hashes, and controller media-id sequence in `offline-journey.md`.

- [ ] Commit:

```powershell
git add scripts/run-offline-acceptance.ps1 app/src/androidTest/java/com/mkread/app/acceptance/OfflineJourneyTest.kt docs/test-evidence/emulator-api35/offline-journey.md
git commit -m "test: prove complete offline reading journey"
```

## Task 4: Prove The 20 MB And 100-Book Scale Targets

**Files:**
- Create: `app/src/androidTest/java/com/mkread/app/acceptance/ScaleAcceptanceTest.kt`
- Create: `scripts/run-scale-acceptance.ps1`
- Create: `docs/test-evidence/emulator-api35/scale.md`

- [ ] Generate a deterministic 20 MiB UTF-8 novel with 1,000 chapter headings and mixed Chinese/English paragraphs at test runtime. Its generator records seed, byte size, code-point count, chapter count, and SHA-256.

- [ ] Import it through the production coordinator; assert first progress appears, exactly 1,000 ordered chapters exist, reconstructed normalized text matches expected normalization, no main-thread work violation occurs, and source SHA-256 is unchanged. Use a ten-minute outer timeout only to catch deadlocks; record actual timing.

- [ ] Seed/import 100 varied books, reopen the app, search beginning/middle/end titles and authors, exercise all sorts, scroll the complete shelf, open first/middle/last reader, delete ten, restart, and assert 90 complete books plus unchanged external sources.

- [ ] Record median/p95 Room query time, shelf first-render time, import copy/parse/commit durations, peak process PSS during 20 MiB import, first-page/full-pagination timing, and storage delta. Correctness requirements are hard; emulator timings are diagnostic and cannot substitute for ARM64 gating.

- [ ] Make the script reset only MKread app data after validating exact package id, generate fixtures under the instrumentation sandbox, run the scale class, collect `dumpsys meminfo`, and retain the test XML/report.

- [ ] Run:

```powershell
.\scripts\run-scale-acceptance.ps1
```

Expected: 20 MiB/1,000 chapters and 100 books complete without ANR, crash, partial records, or source modification.

- [ ] Commit evidence and tests:

```powershell
git add scripts/run-scale-acceptance.ps1 app/src/androidTest/java/com/mkread/app/acceptance/ScaleAcceptanceTest.kt docs/test-evidence/emulator-api35/scale.md
git commit -m "test: prove MKread content scale targets"
```

## Task 5: Run Thirty-Minute Background Narration

**Files:**
- Create: `scripts/run-narration-endurance.ps1`
- Create: `app/src/androidTest/java/com/mkread/app/acceptance/NarrationEnduranceSetupTest.kt`
- Create: `docs/test-evidence/emulator-api35/narration-30m.md`

- [ ] Seed at least 240 short deterministic sentence ids and pre-generate/cache enough fixture audio for 35 minutes. Separately generate ten representative sentences with real ZipVoice before the run to confirm the native path; do not rely on 240 native generations for deterministic sequence testing.

- [ ] Start at id 0, background MKread, turn the emulator screen off, and poll MediaSession plus process/service state every ten seconds for 30 minutes. At minute 5 issue pause/resume, minute 10 headset-hook equivalent, minute 15 transient focus loss, minute 20 remove the task from recents, and minute 25 change playback speed.

- [ ] Append timestamp, media id, playback state, position, process pid, service foreground state, and cache size to a CSV. The script fails on duplicated/reversed/skipped ids, silent idle longer than 30 seconds without a recorded generation wait, process crash, missing service, or final duration under 30 minutes.

- [ ] After completion reopen MKread and assert the persisted checkpoint equals the next unread sentence. Scan logcat for fatal exceptions, ANRs, skipped-sentence warnings, and fixture prose leakage.

- [ ] Run:

```powershell
.\scripts\run-narration-endurance.ps1 -Minutes 30
```

Expected: continuous ordered narration, external events behave as specified, no silent skip, and exact checkpoint restoration.

- [ ] Summarize sequence count, waits/reasons, controls, pid/service continuity, cache growth/eviction, crash/ANR scan, and final checkpoint in `narration-30m.md`.

- [ ] Commit:

```powershell
git add scripts/run-narration-endurance.ps1 app/src/androidTest/java/com/mkread/app/acceptance/NarrationEnduranceSetupTest.kt docs/test-evidence/emulator-api35/narration-30m.md
git commit -m "test: prove thirty minute background narration"
```

## Task 6: Complete Accessibility And Responsive UI Checks

**Files:**
- Create: `app/src/androidTest/java/com/mkread/app/acceptance/AccessibilityAcceptanceTest.kt`
- Create: `app/src/androidTest/java/com/mkread/app/acceptance/ResponsiveLayoutTest.kt`
- Create: `docs/test-evidence/emulator-api35/accessibility.md`

- [ ] Enable Compose accessibility checks and assert every icon-only command has a localized content description/tooltip, actionable nodes have unique semantics, touch targets are at least 48 dp, selection actions are reachable, and decorative covers are not announced as controls.

- [ ] Test font scales 1.0, 1.3, and 2.0; display sizes default and large; portrait, landscape, and split-screen-compatible widths 360/600/840 dp. Cover shelf, import status, reader, editor, playback sheet, voice library/details, settings, dialogs, loading, empty, and failure states.

- [ ] Assert no control/text bounding boxes overlap incoherently, no text is clipped horizontally, reader bottom controls retain stable dimensions, page text does not sit under system insets, and active highlight does not change measurement.

- [ ] Use WCAG-style contrast calculations on resolved light/dark semantic colors: normal text/highlight/control icon pairs at least 4.5:1 where applicable and large text at least 3:1. Adjust theme colors rather than suppressing failures.

- [ ] Perform a documented TalkBack manual pass for shelf import, page turn, selection/copy, playback, voice selection, and settings. Record focus order and spoken labels; automated semantics do not replace this manual gate.

- [ ] Run:

```powershell
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.mkread.app.acceptance.AccessibilityAcceptanceTest
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.mkread.app.acceptance.ResponsiveLayoutTest
```

Expected: automated checks pass at every configuration and manual TalkBack results have no blocker.

- [ ] Commit:

```powershell
git add app/src/androidTest/java/com/mkread/app/acceptance docs/test-evidence/emulator-api35/accessibility.md
git commit -m "test: verify accessible responsive Android UI"
```

## Task 7: Run The Mandatory ARM64 Performance Gate

**Files:**
- Create: `scripts/run-arm64-performance.ps1`
- Create: `app/src/androidTest/java/com/mkread/app/acceptance/Arm64SpeechPerformanceTest.kt`
- Create after a device is available: `docs/test-evidence/arm64/device-profile.md`
- Create after a device is available: `docs/test-evidence/arm64/speech-performance.md`

- [ ] Require an explicit `-Serial`; reject emulator serials and any device whose `ro.product.cpu.abi` is not `arm64-v8a`, SDK is below 29, total memory is below 5.5 GiB, battery is below 50 percent while unplugged, or thermal status is already severe.

- [ ] Record manufacturer/model/build fingerprint/API/RAM/CPU ABI/battery/thermal status and APK/model hashes. Redact device serial from committed evidence.

- [ ] Warm the model once, then synthesize a fixed 50-sentence Chinese/English corpus in fluent mode and 20 sentences in high-quality mode. Record initialization time, sentence characters, audio duration, generation duration, real-time factor, peak PSS, native heap, cache hits, and boundary waits. Do not commit corpus prose; commit corpus hash and aggregate statistics.

- [ ] Hard fluent gates on the approved 6 GB-class target: initialization at most 30 seconds; p50 RTF at most 0.85; p95 RTF at most 1.00; peak PSS at most 2.5 GiB; no allocation failure; and a 30-minute prefetch narration simulation with zero skipped ids and no boundary wait longer than 10 seconds after initial preparation.

- [ ] High-quality gates: initializes without allocation fallback on a non-low-RAM device, p95 RTF at most 1.50, peak PSS at most 3.0 GiB, and no skipped id. If it fails, high quality remains disabled for that model/device while fluent must still pass.

- [ ] During a 30-minute screen-off real-voice run sample thermal/battery/memory each minute. Require no Android thermal status `SEVERE` or higher for more than two consecutive samples, no low-memory kill, stable PSS without monotonic leak, and battery drop no greater than 20 percentage points over 30 minutes when unplugged from a starting charge of at least 80 percent.

- [ ] Run only when a real device is connected:

```powershell
.\scripts\run-arm64-performance.ps1 -Serial '<adb-device-serial>'
```

Expected: script emits a red/green metric table and exits non-zero for any mandatory fluent gate. Without this report MKread remains internal and cannot be described as speech-production-ready.

- [ ] Commit only redacted evidence after a valid run:

```powershell
git add docs/test-evidence/arm64/device-profile.md docs/test-evidence/arm64/speech-performance.md
git commit -m "test: record ARM64 speech performance gate"
```

## Task 8: Harden Packaging And Produce An Internal Candidate

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `app/proguard-rules.pro`
- Create: `scripts/inspect-apk.ps1`
- Create: `docs/test-evidence/package/internal-candidate.md`

- [ ] Configure debug native ABIs `x86_64` and `arm64-v8a`; configure release native ABI `arm64-v8a` only. Enable release R8/minify and resource shrinking, retain JNI/serialization/Room/Media3 classes through the smallest tested rules, and retain native debug symbols in a separate local artifact.

- [ ] Add packaging checks for application id/version, min/target SDK, arm64-only release native libraries, no Internet/network-state/microphone/broad-storage permissions, exactly one exported launcher activity plus required exported MediaSessionService, media-playback foreground type, no debug speech screen/string, no development prompt id/name, and no source maps/raw fixtures.

- [ ] Scan the APK ZIP for unexpected `.txt`, `.epub`, `.mkvoice`, test WAV, and duplicate model files. Expected model/prompt paths must match the approved release asset manifest and hashes exactly.

- [ ] Build debug internal candidate while licensing is pending:

```powershell
.\gradlew.bat clean testDebugUnitTest lintDebug assembleDebug offlineContract
.\scripts\inspect-apk.ps1 -Apk app\build\outputs\apk\debug\app-debug.apk -ExpectedAbi x86_64,arm64-v8a -Internal
```

Expected: debug internal candidate passes technical inspection and remains labeled non-distributable.

- [ ] After Task 1 approvals and Task 7 ARM64 evidence, build the arm64 release candidate:

```powershell
.\gradlew.bat assembleRelease
.\scripts\inspect-apk.ps1 -Apk app\build\outputs\apk\release\app-release.apk -ExpectedAbi arm64-v8a
```

Expected: Gradle/inspection pass only with authorized release assets. Do not publish or upload the artifact in this plan.

- [ ] Record Git commit/tag candidate, Gradle/JDK versions, artifact SHA-256/size, native ABI/library list, model/voice hashes, permissions, R8 mapping location, and `INTERNAL_ONLY` or approved state in `internal-candidate.md`.

- [ ] Commit:

```powershell
git add app/build.gradle.kts app/proguard-rules.pro scripts/inspect-apk.ps1 docs/test-evidence/package/internal-candidate.md
git commit -m "build: harden MKread Android packaging"
```

## Task 9: Run The Final Android 1.0 Gate

**Files:**
- Create: `scripts/run-acceptance.ps1`
- Create: `docs/test-evidence/android-1.0-checklist.md`
- Modify: `README.md`

- [ ] Make `run-acceptance.ps1` execute toolchain check, pinned asset verification, unit tests, lint, debug assembly, offline manifest inspection, all emulator instrumentation, offline journey, scale journey, and 30-minute endurance in that order. Accept `-SkipEndurance` only for local iteration; final evidence must run without it.

- [ ] The script writes one machine-readable JSON summary with Git SHA, command, duration, exit code, report path, and evidence hash for every gate. A non-zero sub-gate stops later destructive/setup actions but still restores emulator radio/screen state in `finally`.

- [ ] Run the full emulator gate:

```powershell
.\scripts\run-acceptance.ps1
```

Expected: every emulator gate is green, no network is required after APK installation, no sentence is skipped, and source/voice atomicity checks pass.

- [ ] Link the Phase 1-6 evidence in `android-1.0-checklist.md` and mark each approved-scope item pass/fail with a concrete report/hash. Explicitly list PDF/OCR, character casting, cloud features, public marketplace, Play Store release, and Windows voice-production tool as deferred.

- [ ] Update `README.md` with project purpose, Android-only status, prerequisites, exact debug build/emulator commands, local speech-asset process, offline/privacy promise, internal-only licensing status, and links to design/master workflow/evidence.

- [ ] Run final repository checks:

```powershell
git diff --check
git status --short
git ls-files | Select-String '\.(aar|onnx|wav|epub|mkvoice|tar\.bz2)$'
git log --oneline --decorate -20
```

Expected: no whitespace issues, no downloaded/voice/book binaries tracked, and only final checklist/README/script changes remain.

- [ ] Commit:

```powershell
git add scripts/run-acceptance.ps1 docs/test-evidence/android-1.0-checklist.md README.md
git commit -m "docs: complete MKread Android 1.0 evidence"
```

- [ ] Mark Android 1.0 technically complete only when every emulator gate passes and the mandatory ARM64 fluent gate has committed evidence. Mark it publicly releasable only when `release-decision.md` is approved and the authorized release asset build passes; these are separate statuses.

Only after this final technical status is reached should a new design session begin for the separate Windows voice-production application. Its output contract is the already-tested `.mkvoice` schema version 1.
