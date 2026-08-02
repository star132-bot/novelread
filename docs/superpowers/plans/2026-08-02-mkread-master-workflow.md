# MKread Android Master Workflow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver the approved MKread Android 1.0 application as a fully offline TXT/EPUB reader with local expressive speech, background playback, editing, voice packages, and emulator acceptance evidence.

**Architecture:** Build one native Kotlin/Compose `:app` module with feature-oriented packages and explicit repository, speech, and playback interfaces. Persist structured state in Room and DataStore, store imported content and generated audio in app-private files, run ZipVoice through sherpa-onnx, and host Media3 in a `MediaSessionService`.

**Tech Stack:** Kotlin 2.0.21, Android Gradle Plugin 8.6.0, Gradle 8.8, JDK 17, Jetpack Compose, Room 2.6.1, DataStore 1.1.1, Media3 1.5.1, WorkManager 2.10.0, Jsoup 1.18.3, kotlinx.serialization 1.7.3, sherpa-onnx 1.13.2, ZipVoice-Distill INT8, JUnit 4, AndroidX Test, Espresso.

---

## Source Of Truth

- Approved behavior: `docs/superpowers/specs/2026-08-02-mkread-android-design.md`.
- Existing emulator operations reference: `android-emulator-start-guide.md`; Phase 1 wraps its verified SDK/AVD paths in project scripts.
- This file controls phase order, gates, and handoffs.
- The six sibling phase plans control exact files, tests, commands, and commits.
- When a phase plan and the approved design disagree, stop and amend the phase plan before changing production code.
- Android work is the only current implementation scope. The separate Windows voice-production tool starts only after Phase 6 passes.

## Fixed Product Decisions

- Package/application id: `com.mkread.app`.
- App name: `MKread`.
- Minimum SDK: 29; compile and target SDK: 35.
- Supported release ABI: `arm64-v8a`; supported development ABI: `x86_64`.
- UI language for 1.0: Simplified Chinese.
- File formats: TXT and EPUB only.
- Reader mode: discrete left/right pages only.
- Speech: completely local Chinese/English synthesis, one selected voice at a time.
- Emotion mode: enabled by default, user-toggleable, deterministic five-label analyzer.
- Network: the app manifest must not request `android.permission.INTERNET`.
- Imported source files are never modified or deleted.
- Public distribution is blocked until model, dataset, built-in voice, and consent licenses have written approval.

## Phase Dependency Graph

```text
Phase 1: build foundation + speech spike
                  |
                  v
Phase 2: storage + TXT/EPUB library
                  |
                  v
Phase 3: pagination + reader + editing
                  |
                  v
Phase 4: speech queue + background playback
                  |
                  v
Phase 5: voice packages + emotion + settings
                  |
                  v
Phase 6: offline acceptance + hardening + release gates
```

No phase may begin while the previous phase gate is red. A failing gate is fixed in the owning phase; it is not waived in a later phase.

## Plans And Exit Gates

| Phase | Plan | Required exit evidence |
|---|---|---|
| 1 | `2026-08-02-mkread-phase-1-foundation-speech-spike.md` | Debug APK builds with JDK 17, launches on `AffectLive_API_35`, has no Internet permission, and produces a valid Chinese WAV through x86_64 sherpa-onnx. |
| 2 | `2026-08-02-mkread-phase-2-library-import.md` | TXT/EPUB fixtures pass, imports are atomic, 100-book shelf operations work, and external source files remain unchanged. |
| 3 | `2026-08-02-mkread-phase-3-reader-pagination-editing.md` | Page offsets are stable, selection/copy/edit/save/undo work, and semantic position survives typography and process recreation. |
| 4 | `2026-08-02-mkread-phase-4-background-playback.md` | Sentence queue, cache, highlight, notification, audio focus, headset controls, sleep timer, and service restoration pass on the emulator. |
| 5 | `2026-08-02-mkread-phase-5-voices-emotion-settings.md` | `.mkvoice` validation is atomic, voice preview/selection/deletion work, emotion toggle is repeatable, and all user settings persist. |
| 6 | `2026-08-02-mkread-phase-6-acceptance-hardening.md` | Airplane-mode E2E, malformed-input suite, 20 MB/100-book targets, 30-minute playback, accessibility checks, and ARM64 performance report are complete. |

## Requirement Coverage Map

| Approved requirement | Owning plan/tasks |
|---|---|
| Native Android/API 29-35, local emulator, no Internet permission | Phase 1 Tasks 1-3 and 9 |
| Offline Chinese/English ZipVoice synthesis and one built-in voice | Phase 1 Tasks 4-9; Phase 5 Tasks 3-4 |
| TXT/EPUB SAF import, private copy, parsing, file management | Phase 2 Tasks 2-9 |
| Search/sort/rename/metadata/delete and 100-book shelf | Phase 2 Tasks 7-9; Phase 6 Task 4 |
| Automatic page calculation, page turning, sentence offsets | Phase 3 Tasks 2-4 and 6-9 |
| Android selection, copy, read from selection | Phase 3 Tasks 2 and 7-9 |
| Chapter edit/save/one-level undo with original preserved | Phase 3 Tasks 5 and 8-9 |
| Background, notification, lock-screen, headset, audio focus | Phase 4 Tasks 4-9 |
| Speed, quality, sleep timer, cache, process/service restoration | Phase 4 Tasks 1-9; Phase 5 Tasks 7-8 |
| `.mkvoice` import/library/preview/select/delete | Phase 5 Tasks 1-4 and 9 |
| Optional contextual emotion and expressive fallback | Phase 5 Tasks 5-6 and 9 |
| Night mode, typography, spacing, margins, screen-on setting | Phase 5 Tasks 7-9 |
| Malformed-input, airplane-mode, scale, endurance, accessibility | Phase 6 Tasks 2-6 and 9 |
| ARM64 performance and public distribution licensing | Phase 6 Tasks 1, 7-9 |
| Separate Windows voice-production tool | Explicitly deferred until the final Android gate |

## Work Item Protocol

Every checkbox in a phase plan is completed with this loop:

- [ ] Read the named production and test files before editing; preserve unrelated user changes.
- [ ] Add the smallest failing test described by the checkbox.
- [ ] Run only that test and record the expected failure reason, not merely a non-zero exit code.
- [ ] Add the minimum production code that makes the test pass.
- [ ] Run the focused test again and require a passing result.
- [ ] Run the owning package or feature test suite to detect regressions.
- [ ] Inspect `git diff --check` and `git diff --stat` before committing.
- [ ] Commit only the files named by that work item with the exact commit subject given in the phase plan.

If an Android framework behavior cannot be exercised in a JVM test, use an instrumented test and keep pure parsing, mapping, cache-key, and state-transition logic out of Android classes so it remains JVM-testable.

## Repository Shape

The plans converge on this structure:

```text
app/
  libs/
  src/main/
    AndroidManifest.xml
    java/com/mkread/app/
      MkreadApplication.kt
      AppContainer.kt
      core/{database,files,model,testing}/
      feature/{library,reader,settings,voice}/
      navigation/
      playback/
      speech/
      ui/theme/
    res/
  src/test/java/com/mkread/app/
  src/androidTest/java/com/mkread/app/
docs/
  licenses/
  test-evidence/
gradle/libs.versions.toml
scripts/
  android-env.ps1
  fetch-speech-assets.ps1
  start-emulator.ps1
  run-acceptance.ps1
```

Use a hand-written `AppContainer` for dependency construction. Do not add Hilt, a second Gradle module, a remote API client, analytics, or a WebView.

## Local Environment Contract

- JDK 17 is mandatory even though the workstation default is Java 8.
- Reuse SDK `D:\spless\AffectLive\.android-sdk` and AVD `AffectLive_API_35`.
- `local.properties`, `.local-assets/`, generated models, WAV cache, APKs, and emulator state stay untracked.
- The sherpa-onnx AAR and speech assets are fetched by a development script and pinned by `speech-assets.lock.json`; the Android app itself never downloads them.
- Debug builds may use the upstream ZipVoice test prompt only for engineering evaluation. Release builds require an authorized built-in prompt, transcript, consent record, and hash.

The common shell preamble is:

```powershell
.\scripts\android-env.ps1
java -version
```

Expected result: `java -version` reports major version `17`. If the installed patch directory differs, `scripts/android-env.ps1` resolves it and fails with a direct installation message when no JDK 17 is present.

## Standard Verification Commands

Run from `D:\spless\novelread`:

```powershell
.\scripts\android-env.ps1
.\gradlew.bat --version
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
.\gradlew.bat assembleDebug
.\scripts\start-emulator.ps1
.\gradlew.bat connectedDebugAndroidTest
& "$env:ANDROID_SDK_ROOT\platform-tools\adb.exe" shell dumpsys package com.mkread.app
```

The package dump must show no requested `android.permission.INTERNET`. All Gradle commands must end with `BUILD SUCCESSFUL`.

## Cross-Phase Invariants

- [ ] UI classes depend on use cases or ViewModels, never directly on Room DAOs, filesystem paths, or JNI classes.
- [ ] Room rows never point at files that were not durably moved into place.
- [ ] File import and voice import use staging directories and atomic rename/compensating cleanup.
- [ ] Logs contain ids, byte counts, and reason codes, never full book text or raw prompt samples.
- [ ] All ZIP and XML parsing is bounded; path traversal, duplicate entries, absolute paths, and excessive expansion are rejected.
- [ ] All TTS initialization and generation occurs outside the main thread on a bounded executor.
- [ ] Playback checkpoints commit at sentence boundaries, and recovery never silently skips a sentence.
- [ ] Playback speed is excluded from TTS cache keys because Media3 applies it.
- [ ] Editing invalidates only the edited chapter's pages, sentence map, and audio cache.
- [ ] A corrupt imported voice falls back to the built-in neutral voice and surfaces a warning.
- [ ] Every destructive library or voice action requires explicit UI confirmation and affects only app-private data.

## Definition Of Android 1.0 Done

- [ ] All six phase plans have every checkbox checked and their commits are present.
- [ ] `testDebugUnitTest`, `lintDebug`, `assembleDebug`, and `connectedDebugAndroidTest` pass from a clean checkout plus locally fetched licensed assets.
- [ ] The emulator acceptance script saves its report under `docs/test-evidence/emulator-api35/`.
- [ ] An ARM64 device report records synthesis real-time factor, peak memory, thermal state, and battery delta under `docs/test-evidence/arm64/`.
- [ ] A 30-minute background run has zero silently skipped sentence ids.
- [ ] TXT and EPUB originals have identical SHA-256 values before and after import, edit, and deletion from MKread.
- [ ] Airplane-mode launch, import, reading, synthesis, background playback, restart restoration, and voice import all pass.
- [ ] Android package inspection confirms no Internet permission and only the documented foreground media-playback permissions.
- [ ] Accessibility scan covers TalkBack labels, 200 percent font scale, touch targets, contrast, and orientation changes.
- [ ] `docs/licenses/release-decision.md` has explicit approvals for sherpa-onnx code, ZipVoice code/model/data terms, Vocos, the built-in voice, and every redistributed fixture.
- [ ] No item from the deferred scope was smuggled into 1.0.

## Public Release Stop Conditions

Any one of the following keeps the build at internal-development status:

- Missing written distribution approval for a model, dataset, vocoder, or built-in prompt.
- No authorized neutral built-in voice recording and exact transcript.
- ARM64 synthesis cannot sustain the chosen prefetch policy without repeated boundary stalls.
- A crash, corruption, data-loss, path-traversal, or source-file modification defect remains open.
- Offline acceptance requires Internet access at any point after APK installation.
- The 30-minute narration run skips or reorders a sentence.

## Completion Handoff

After Phase 6, create a new approved design and implementation plan for the Windows voice-production project. Its first contract test must emit a schema-version-1 `.mkvoice` file that passes MKread's already-shipped validator; the Android package format is not changed to accommodate an unfinished desktop tool.
