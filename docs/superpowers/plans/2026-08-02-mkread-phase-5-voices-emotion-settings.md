# MKread Phase 5 Voices Emotion And Settings Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let users safely import and manage `.mkvoice` packages, choose one voice for all narration, optionally add deterministic contextual emotion, select quality, and persist complete reading/display preferences.

**Architecture:** Treat `.mkvoice` as a bounded data-only ZIP with strict manifest/checksum validation and atomic installation. Resolve an emotion label through a pure analyzer, select the best prompt through a fallback resolver, apply bounded audio-style adjustments, and keep global preferences in DataStore plus per-book display overrides in Room.

**Tech Stack:** Kotlin serialization JSON, Apache Commons Compress 1.27.1, Room schema 4, Preferences DataStore, Compose Material 3, sherpa-onnx ZipVoice, Android low-RAM capability APIs, JUnit and AndroidX instrumented tests.

---

## Task 1: Define Schema-Version-1 Voice Data

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Create: `app/src/main/java/com/mkread/app/voice/VoiceManifest.kt`
- Create: `app/src/main/java/com/mkread/app/voice/VoiceModels.kt`
- Create: `app/src/main/java/com/mkread/app/voice/VoiceJson.kt`
- Test: `app/src/test/java/com/mkread/app/voice/VoiceManifestTest.kt`

- [ ] Add Apache Commons Compress `1.27.1` for central-directory metadata and symlink inspection. Do not add a networking library.

- [ ] Define the serialized model with `schemaVersion`, `id`, `displayName`, `engine`, `languages`, `creator`, `consent`, `styles`, and `checksums`; a style contains `emotion`, `audio`, `transcript`, and `sampleRate`. The only valid emotions are `neutral`, `joy`, `sadness`, `anger`, and `tension`.

- [ ] Configure one bounded `Json` instance with `ignoreUnknownKeys=false`, `isLenient=false`, `allowSpecialFloatingPointValues=false`, and `explicitNulls=false`. Unknown fields are rejected so package meaning cannot silently drift.

- [ ] Write manifest tests for this valid minimum:

```json
{
  "schemaVersion": 1,
  "id": "com.mkread.voice.yunlan",
  "displayName": "云岚",
  "engine": "zipvoice-distill-int8-zh-en",
  "languages": ["zh-CN", "en"],
  "creator": "Local creator",
  "consent": {
    "declared": true,
    "statement": "The creator declares authorization to package this voice."
  },
  "styles": [{
    "emotion": "neutral",
    "audio": "prompts/neutral.wav",
    "transcript": "prompts/neutral.txt",
    "sampleRate": 24000
  }],
  "checksums": "checksums.json"
}
```

- [ ] Add failing cases for schema other than 1, invalid/reused id, blank or over-80-code-point name, wrong engine, unsupported/duplicate language, blank/over-200 creator, undeclared consent, consent statement outside 10-500 code points, no neutral style, duplicate style, unsupported emotion, wrong sample rate, absolute or escaping paths, same file used twice, and unknown JSON fields.

- [ ] Validate id against `^[a-z][a-z0-9]*(\.[a-z][a-z0-9-]*)+$` with maximum 120 ASCII characters; require at least one of `zh-CN` or `en`; require exactly one neutral style; allow each optional style at most once.

- [ ] Verify:

```powershell
.\gradlew.bat testDebugUnitTest --tests com.mkread.app.voice.VoiceManifestTest
```

Expected: the minimum manifest round-trips byte-stably after canonical encoding and every invalid field reports its JSON path.

- [ ] Commit:

```powershell
git add gradle/libs.versions.toml app/build.gradle.kts app/src/main/java/com/mkread/app/voice app/src/test/java/com/mkread/app/voice
git commit -m "feat: define mkvoice schema version one"
```

## Task 2: Validate `.mkvoice` Archives Before Extraction

**Files:**
- Create: `app/src/main/java/com/mkread/app/voice/VoicePackageValidator.kt`
- Create: `app/src/main/java/com/mkread/app/voice/VoiceValidationError.kt`
- Create: `app/src/test/java/com/mkread/app/voice/VoicePackageFixtureFactory.kt`
- Create: `app/src/test/java/com/mkread/app/voice/VoicePackageValidatorTest.kt`

- [ ] Generate test packages with `ZipArchiveOutputStream`, including Unix symlink mode fixtures; do not commit binary ZIP fixtures. Cover valid neutral-only and all-five-style packages.

- [ ] Fix validation bounds: compressed input 250 MB, total uncompressed data 250 MB, 64 entries, manifest 128 KiB, checksums JSON 256 KiB, transcript 64 KiB each, prompt duration 60 seconds each, path length 240 bytes, and compression ratio 200:1 per entry.

- [ ] Write failing cases for `../`, backslash traversal, drive/UNC/absolute paths, NUL, duplicate normalized path, case-fold duplicate, symbolic link, directory posing as file, encrypted entry, entry/count/size/ratio overflow, missing manifest/checksums/neutral files, undeclared payload, missing declared file, checksum missing/extra/mismatch, malformed JSON, non-PCM WAV, stereo, non-24-kHz, non-16-bit, zero samples, prompt longer than 60 seconds, and blank transcript.

- [ ] Inspect the ZIP central directory with Commons Compress before opening entry streams. Normalize separators, reject paths with empty/dot/parent components, compare duplicates with `Locale.ROOT` lowercase, reject `ZipArchiveEntry.isUnixSymlink`, and stream every entry through an actual byte counter plus SHA-256 rather than trusting declared sizes.

- [ ] Allow payload files only when they are exactly `manifest.json`, the manifest's `checksums.json` path, or a style's audio/transcript path. Reject native libraries, DEX/JAR/class files, and all extra content through the same undeclared-payload rule.

- [ ] Parse `checksums.json` as a strict object from normalized relative path to lowercase 64-character SHA-256. Require a hash for each declared audio/transcript; disallow a self-hash for checksums JSON and allow manifest hash only if declared consistently. No hash entry may reference undeclared payload.

- [ ] Validate WAV structure with `WaveValidator`, derive duration from sample count/sample rate, decode transcripts strictly as UTF-8, normalize line endings, and require 1-10,000 non-whitespace code points. The app validates structure but does not claim semantic audio/transcript matching.

- [ ] Return an immutable `ValidatedVoicePackage(manifest, packageSha256, entries)` without extracting it. Error output contains a reason code and safe filename, never prompt bytes/transcript content.

- [ ] Verify:

```powershell
.\gradlew.bat testDebugUnitTest --tests com.mkread.app.voice.VoicePackageValidatorTest
```

Expected: all malicious packages fail before any file is written outside the test's temporary directory, and valid package hashes are stable.

- [ ] Commit:

```powershell
git add app/src/main/java/com/mkread/app/voice app/src/test/java/com/mkread/app/voice
git commit -m "feat: validate hostile mkvoice packages"
```

## Task 3: Install Voice Packages Atomically

**Files:**
- Create: `app/src/main/java/com/mkread/app/core/database/VoiceProfileEntity.kt`
- Modify: `app/src/main/java/com/mkread/app/core/database/MkreadDatabase.kt`
- Modify: `scripts/fetch-speech-assets.ps1`
- Create: `app/src/main/java/com/mkread/app/voice/VoiceRepository.kt`
- Create: `app/src/main/java/com/mkread/app/voice/InstalledVoiceRepository.kt`
- Create: `app/src/main/java/com/mkread/app/voice/VoicePackageInstaller.kt`
- Create: `app/src/main/java/com/mkread/app/voice/ActiveVoiceStore.kt`
- Create: `app/schemas/com.mkread.app.core.database.MkreadDatabase/4.json`
- Test: `app/src/androidTest/java/com/mkread/app/core/database/Migration3To4Test.kt`
- Test: `app/src/androidTest/java/com/mkread/app/voice/VoicePackageInstallerTest.kt`

- [ ] Define `VoiceProfileEntity`: id primary key, display name, engine, comma-free canonical language JSON, installed-style bit mask, package hash, relative directory, creator, consent statement, `isBuiltIn`, and installed time. Store no raw transcript or prompt in Room.

- [ ] Add migration 3-to-4 and test all earlier book, chapter, position, and cache rows survive. Export schema 4.

- [ ] Write installer tests for success, same package duplicate, same id/different hash conflict, invalid package preserving existing voice, injected extraction failure, injected Room failure, cancellation, deleting inactive imported voice, refusing built-in deletion, and deleting the active imported voice with fallback to built-in.

- [ ] Validate before extraction. Extract only declared files through bounded streams into `files/voice-staging/<uuid>`, recheck every SHA-256 during extraction, write canonical manifest/checksums, then atomically rename to `files/voices/<voice-id>` and insert Room metadata. On Room failure remove the promoted directory.

- [ ] Conflict policy: identical id/hash returns `AlreadyInstalled`; identical id/different hash returns `IdConflict` and does not replace. The user must explicitly delete the old imported voice before installing a different package with the same id.

- [ ] Extend the development asset script to create `voices/builtin-dev/manifest.json` and `checksums.json` around the upstream engineering prompt. Its consent is explicitly `declared=false` with statement `Upstream engineering sample; not authorized for distribution.` Permit that one unconsented id only when all three conditions hold: `BuildConfig.DEBUG`, package origin is the bundled debug asset tree, and id equals `com.mkread.voice.builtin-dev`; external imports with undeclared consent remain rejected.

- [ ] Treat the development/release built-in voice through the same manifest and repository. Mark it built-in and install it before opening the voice library. Release packaging must provide consent-declared `com.mkread.voice.builtin` and may never fall back to the upstream test identity.

- [ ] Store active voice id in DataStore. If it is absent, corrupt, or deleted, select the installed built-in voice, emit a one-time package warning, and keep playback available. Deleting a voice invalidates cache rows by that package hash but never touches books.

- [ ] Verify:

```powershell
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.mkread.app.core.database.Migration3To4Test
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.mkread.app.voice.VoicePackageInstallerTest
```

Expected: install outcomes are atomic and every active-voice deletion/corruption path resolves to built-in neutral.

- [ ] Commit:

```powershell
git add scripts/fetch-speech-assets.ps1 app/src/main/java/com/mkread/app/core/database app/src/main/java/com/mkread/app/voice app/src/androidTest app/schemas
git commit -m "feat: install and persist local voices"
```

## Task 4: Build Voice Library, Inspection, And Preview

**Files:**
- Create: `app/src/main/java/com/mkread/app/feature/voice/VoiceLibraryViewModel.kt`
- Create: `app/src/main/java/com/mkread/app/feature/voice/VoiceLibraryScreen.kt`
- Create: `app/src/main/java/com/mkread/app/feature/voice/VoiceDetailsSheet.kt`
- Create: `app/src/main/java/com/mkread/app/voice/VoicePreviewUseCase.kt`
- Modify: `app/src/main/java/com/mkread/app/navigation/MkreadNavHost.kt`
- Modify: `app/src/main/java/com/mkread/app/AppContainer.kt`
- Test: `app/src/test/java/com/mkread/app/feature/voice/VoiceLibraryViewModelTest.kt`
- Test: `app/src/androidTest/java/com/mkread/app/feature/voice/VoiceLibraryJourneyTest.kt`

- [ ] Add a voice-library route from the app overflow/settings. Launch `OpenDocument` for MIME `application/zip` and `application/octet-stream`; also accept a `.mkvoice` display name after content validation. Do not request storage permission.

- [ ] Model import states validation/install/success/failure with typed Chinese messages. Show a persistent warning near import: `只导入你有权使用的音色；音色与小说均仅保存在本机。`

- [ ] Render the active voice first, then built-in/imported voices ordered by display name. Each row has name, creator, language/style swatches, selected indicator, preview icon with tooltip, and overflow actions inspect/select/delete. Built-in delete is absent; imported delete requires confirmation.

- [ ] Details show package id/hash prefix, engine, languages, available styles, creator, consent declaration, install time, and local-only status. Do not expose app-private filesystem paths or full prompt transcripts.

- [ ] Preview text is fixed and reviewable: Chinese `你好，这里是 MKread 音色试听。` and English `Hello, this is an MKread voice preview.` Choose by declared language, synthesize neutral/fluent into `cache/voice-preview/<package-hash>.wav`, and use the existing PlaybackClient preview command. Cancel prior preview when another starts.

- [ ] Write ViewModel/UI tests for built-in display, valid import, invalid checksum message, id conflict, preview state, select persistence, inspect metadata, delete cancellation/confirmation, active deletion fallback, and picker cancellation.

- [ ] Verify:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.mkread.app.feature.voice.*"
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.mkread.app.feature.voice.VoiceLibraryJourneyTest
```

Expected: voice operations do not interrupt book data, preview is local, and invalid packages never alter the visible list.

- [ ] Commit:

```powershell
git add app/src/main/java/com/mkread/app/feature/voice app/src/main/java/com/mkread/app/voice app/src/main/java/com/mkread/app/navigation app/src/main/java/com/mkread/app/AppContainer.kt app/src/test app/src/androidTest
git commit -m "feat: manage and preview offline voices"
```

## Task 5: Classify Contextual Emotion Deterministically

**Files:**
- Create: `app/src/main/java/com/mkread/app/speech/Emotion.kt`
- Create: `app/src/main/java/com/mkread/app/speech/EmotionAnalyzer.kt`
- Create: `app/src/main/java/com/mkread/app/speech/ContextEmotionAnalyzer.kt`
- Create: `app/src/main/assets/speech/emotion-lexicon-zh.json`
- Test: `app/src/test/java/com/mkread/app/speech/ContextEmotionAnalyzerTest.kt`

- [ ] Define `Emotion { NEUTRAL, JOY, SADNESS, ANGER, TENSION }`, a lowercase `wireName` mapping used by `.mkvoice` and cache keys, `EmotionContext(previous,current,next)`, and `EmotionDecision(emotion,scores,ruleVersion=1)`. Production logs may record only the chosen enum and rule version, never text or token matches.

- [ ] Add reviewed lexicon groups: joy (`开心`, `喜悦`, `幸福`, `太好了`, `笑着`, `欣喜`); sadness (`悲伤`, `难过`, `绝望`, `眼泪`, `哭泣`, `失去`); anger (`愤怒`, `怒吼`, `可恶`, `混蛋`, `咬牙`, `暴怒`); tension (`突然`, `猛地`, `危险`, `逃跑`, `追赶`, `屏住呼吸`, `颤抖`). Store UTF-8 JSON with unique terms and a schema version.

- [ ] Score current-sentence lexicon hits as 3 and adjacent-sentence hits as 1, capped at 9 per emotion. Add 2 to anger for anger lexicon plus `！`; add 2 to tension for tension lexicon plus `！` or `……`; add 1 to sadness for sadness lexicon plus `……`; add 1 to joy for joy lexicon plus `！`. Dialogue/narration words alone never determine emotion.

- [ ] Return neutral when all scores are zero, when the highest score is tied, or when the lead over second place is less than 2. Otherwise return the unique leader. This conservative rule is the entire version-1 classifier; do not add an ML dependency.

- [ ] Write tests for each strong label, neutral prose, ironic/ambiguous mixtures, ties, previous/next context changing a weak decision, punctuation without lexicon remaining neutral, emotion words inside longer unrelated tokens, English neutral fallback, deterministic repeated calls, and toggle bypass performed by caller.

- [ ] Verify:

```powershell
.\gradlew.bat testDebugUnitTest --tests com.mkread.app.speech.ContextEmotionAnalyzerTest
```

Expected: decisions and score snapshots match the documented scoring table exactly.

- [ ] Commit:

```powershell
git add app/src/main/java/com/mkread/app/speech app/src/main/assets/speech/emotion-lexicon-zh.json app/src/test/java/com/mkread/app/speech
git commit -m "feat: classify narration emotion deterministically"
```

## Task 6: Resolve Voice Styles And Apply Bounded Expression

**Files:**
- Create: `app/src/main/java/com/mkread/app/speech/VoiceStyleResolver.kt`
- Create: `app/src/main/java/com/mkread/app/speech/AudioStyleProcessor.kt`
- Modify: `app/src/main/java/com/mkread/app/speech/NarrationVoiceProvider.kt`
- Modify: `app/src/main/java/com/mkread/app/speech/SpeechGenerationCoordinator.kt`
- Test: `app/src/test/java/com/mkread/app/speech/VoiceStyleResolverTest.kt`
- Test: `app/src/test/java/com/mkread/app/speech/AudioStyleProcessorTest.kt`

- [ ] Define resolution priority: active matching emotion prompt; active neutral prompt with bounded fallback profile; built-in neutral when the active package is unreadable. With emotion disabled, skip analyzer and request active neutral directly; package corruption may still trigger built-in neutral.

- [ ] Use these fixed neutral-fallback profiles: neutral speed 1.00/gain 1.00/no added pause; joy 1.03/1.02/no pause; sadness 0.96/0.96/120 ms trailing silence; anger 1.01/1.04/40 ms leading silence; tension 0.98/0.98/60 ms leading plus 60 ms trailing silence. Matching emotion prompts use 1.00/1.00/no added pause.

- [ ] Put generation speed in sherpa `GenerationConfig.speed`; post-process mono PCM16 WAV gain and silence through `AudioStyleProcessor`, saturating rather than overflowing samples. Validate the processed output again and atomically replace the unprocessed partial file.

- [ ] Include resolved style id, fallback-profile version, prompt package hash, quality, and generation configuration version in the cache input. User playback speed remains excluded.

- [ ] Write resolver tests for all five matching styles, every missing-style neutral fallback, emotion-off path, missing active files, missing built-in neutral as a hard initialization error, and installed package corruption warning emitted once.

- [ ] Write audio tests for exact silence sample counts at 24 kHz, positive/negative saturation, gain below one, preserved RIFF chunk integrity, empty sample rejection, and source file not modified when output fails.

- [ ] Verify:

```powershell
.\gradlew.bat testDebugUnitTest --tests com.mkread.app.speech.VoiceStyleResolverTest
.\gradlew.bat testDebugUnitTest --tests com.mkread.app.speech.AudioStyleProcessorTest
```

Expected: every fallback is deterministic, bounded, cache-distinct, and disabling emotion uses no non-neutral reference/profile.

- [ ] Commit:

```powershell
git add app/src/main/java/com/mkread/app/speech app/src/test/java/com/mkread/app/speech
git commit -m "feat: resolve expressive voice styles safely"
```

## Task 7: Persist Global And Per-Book Settings

**Files:**
- Create: `app/src/main/java/com/mkread/app/feature/settings/ReaderSettings.kt`
- Create: `app/src/main/java/com/mkread/app/feature/settings/SettingsRepository.kt`
- Create: `app/src/main/java/com/mkread/app/feature/settings/DataStoreSettingsRepository.kt`
- Create: `app/src/main/java/com/mkread/app/core/database/BookDisplaySettingsEntity.kt`
- Modify: `app/src/main/java/com/mkread/app/core/database/MkreadDatabase.kt`
- Create: `app/src/main/java/com/mkread/app/feature/settings/BookDisplaySettingsRepository.kt`
- Create: `app/schemas/com.mkread.app.core.database.MkreadDatabase/5.json`
- Test: `app/src/test/java/com/mkread/app/feature/settings/SettingsRepositoryTest.kt`
- Test: `app/src/androidTest/java/com/mkread/app/core/database/Migration4To5Test.kt`

- [ ] Define defaults: emotion on, fluent quality, playback speed 1.0, sleep timer off, theme system, font size 19 sp, line spacing 1.35, horizontal margin 20 dp, and keep-screen-on off.

- [ ] Define validated ranges: speed 0.5-2.0; font 14-32 sp; line spacing 1.0-2.0; margin 8-40 dp. Clamp corrupted persisted numeric values and replace unknown enums with defaults.

- [ ] Keep global defaults, active voice, and speech settings in the same application-scoped `mkread.preferences_pb` DataStore instance created in Phase 4. Reuse the existing `playback_speed` key as the sole speed value; do not create a second DataStore instance or duplicate speed key. Add `BookDisplaySettingsEntity(bookId,fontSizeSp,lineSpacing,horizontalMarginDp,themeOverride)` with cascade foreign key and nullable override fields so a book can inherit globals.

- [ ] Add Room migration 4-to-5 and test all earlier tables plus cascade behavior. Export schema 5.

- [ ] Create `DeviceSpeechCapability`: high quality is available only when `ActivityManager.isLowRamDevice` is false and no model-allocation failure has been recorded for the current model hash. An allocation failure immediately reverts to fluent, records the reason, and leaves fluent usable; a model hash change clears that failure record.

- [ ] Write repository tests for defaults, every range edge, corrupted raw preferences, process reconstruction, per-book override/inheritance, high-quality rejection on low-RAM fake, allocation fallback, and speed consistency with Phase 4 checkpoint store.

- [ ] Verify:

```powershell
.\gradlew.bat testDebugUnitTest --tests com.mkread.app.feature.settings.SettingsRepositoryTest
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.mkread.app.core.database.Migration4To5Test
```

Expected: settings survive reconstruction, invalid data is repaired, and no Room migration loses prior user data.

- [ ] Commit:

```powershell
git add app/src/main/java/com/mkread/app/feature/settings app/src/main/java/com/mkread/app/core/database app/src/test app/src/androidTest app/schemas
git commit -m "feat: persist speech and display settings"
```

## Task 8: Build Settings UI And Apply It Live

**Files:**
- Create: `app/src/main/java/com/mkread/app/feature/settings/SettingsViewModel.kt`
- Create: `app/src/main/java/com/mkread/app/feature/settings/SettingsScreen.kt`
- Create: `app/src/main/java/com/mkread/app/feature/reader/ReadingDisplaySheet.kt`
- Modify: `app/src/main/java/com/mkread/app/ui/theme/Theme.kt`
- Modify: `app/src/main/java/com/mkread/app/feature/reader/ReaderScreen.kt`
- Modify: `app/src/main/java/com/mkread/app/feature/reader/ReaderViewModel.kt`
- Modify: `app/src/main/java/com/mkread/app/speech/SpeechGenerationCoordinator.kt`
- Modify: `app/src/main/java/com/mkread/app/navigation/MkreadNavHost.kt`
- Test: `app/src/androidTest/java/com/mkread/app/feature/settings/SettingsJourneyTest.kt`

- [ ] Use switches for emotion and keep-screen-on, a two-option segmented control for fluent/high quality, a 0.5x-2.0x slider with numeric value for speed, menus for sleep timer and theme, and sliders for font/line spacing/margins. Use icons plus tooltips for compact reader actions.

- [ ] When high quality is unavailable, disable that segment and show the concrete low-memory/allocation reason beneath it. Do not imply the emulator performance is representative of a real ARM64 phone.

- [ ] Apply system/light/dark at application theme level. Add a reader top-bar quick night toggle that changes the current book's theme override between dark and inherited theme; it must remain visible and coherent after process recreation.

- [ ] Re-paginate on font, line-spacing, margin, viewport, or theme font-metric changes while preserving semantic character offset. Theme color-only changes must not regenerate pages. Apply keep-screen-on only while the reader activity is foregrounded through `DisposableEffect` and clear the window flag on disposal.

- [ ] Feed emotion and quality into future generation requests; invalidate/requeue only current and prefetched not-yet-played items when either changes. Already cached items remain available under their distinct keys. Apply user speed to Media3 immediately without synthesis.

- [ ] Write the journey: defaults; emotion off/on; fluent/high-quality availability; speed 0.5/1/2; timer preset; each theme; font/spacing/margin extremes; book override; process recreation; clear generated audio; and verify books/voices remain after cache clear.

- [ ] Verify at 1.0x and 2.0x Android font scale plus portrait/landscape:

```powershell
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.mkread.app.feature.settings.SettingsJourneyTest
```

Expected: text and controls do not overlap, semantic position remains on the same sentence, and all settings persist.

- [ ] Commit:

```powershell
git add app/src/main/java/com/mkread/app/feature/settings app/src/main/java/com/mkread/app/feature/reader app/src/main/java/com/mkread/app/ui/theme app/src/main/java/com/mkread/app/speech app/src/main/java/com/mkread/app/navigation app/src/androidTest
git commit -m "feat: apply reader and narration settings"
```

## Task 9: Run The Phase 5 Gate

**Files:**
- Create: `app/src/androidTest/java/com/mkread/app/voice/VoiceEmotionJourneyTest.kt`
- Create: `docs/test-evidence/emulator-api35/phase-5.md`

- [ ] Generate one valid all-five-style test `.mkvoice` and the full malicious package corpus from source during the Android test build. The prompt WAVs are synthetic tones/silence for validator/UI determinism; the real ZipVoice test uses only locally authorized/development prompt assets.

- [ ] Run the journey: install valid voice; inspect/select/preview; narrate five reviewed passages with emotion on and assert resolved labels/style ids; narrate again with emotion off and assert neutral style for all; restart process; verify selection/settings; delete active imported voice; assert built-in neutral fallback and warning; reject each malicious package without modifying installed voices.

- [ ] Run all gates:

```powershell
.\gradlew.bat clean testDebugUnitTest lintDebug assembleDebug offlineContract
.\scripts\start-emulator.ps1
.\gradlew.bat connectedDebugAndroidTest
```

Expected: Phases 1-5 pass; package validation is atomic; emotion labels are repeatable; emotion-off is neutral; settings survive recreation; Internet permission remains absent.

- [ ] Record Git commit, package fixture hashes, validator result matrix, resolved emotion/style matrix, voice fallback result, theme/font screenshots references, test counts, and permission inspection in `phase-5.md`.

- [ ] Check logs and tracked binaries:

```powershell
adb logcat -d | Select-String -Pattern 'Local creator|音色试听|prompt'
git ls-files | Select-String '\.(mkvoice|wav|onnx|aar)$'
git diff --check
```

Expected: logs contain no transcripts/raw prompts, no local model/voice binary is tracked, and there are no whitespace errors.

- [ ] Commit:

```powershell
git add app/src/androidTest/java/com/mkread/app/voice/VoiceEmotionJourneyTest.kt docs/test-evidence/emulator-api35/phase-5.md
git commit -m "test: record Phase 5 voice emotion gate"
```

Phase 6 begins only after the valid voice works in airplane mode and every invalid package leaves the existing voice directory, Room row, and active selection unchanged.
