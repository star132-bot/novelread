# MKread Phase 4 Background Speech Playback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn paginated text into a reliable sentence queue that speaks locally, highlights the active sentence, follows pages automatically, and continues through a Media3 background service with system controls and recovery.

**Architecture:** Normalize and cache one WAV per sentence, pre-generate through a single bounded coordinator, and expose an ordered queue to a pure playback state machine. Let a `MediaSessionService` own ExoPlayer, MediaSession, foreground lifecycle, audio focus, and external commands; let UI communicate only through a MediaController-backed client.

**Tech Stack:** sherpa-onnx/ZipVoice from Phase 1, Media3 ExoPlayer/Session 1.5.1, Preferences DataStore 1.1.1, Room migration 3, Kotlin coroutines/Flow, Android foreground media playback, JUnit and AndroidX MediaController tests.

---

## Task 1: Add The Audio Cache Schema And Stable Key

**Files:**
- Create: `app/src/main/java/com/mkread/app/core/database/AudioCacheEntity.kt`
- Modify: `app/src/main/java/com/mkread/app/core/database/MkreadDatabase.kt`
- Create: `app/src/main/java/com/mkread/app/speech/AudioCacheKey.kt`
- Create: `app/src/main/java/com/mkread/app/speech/AudioCacheRepository.kt`
- Create: `app/src/main/java/com/mkread/app/speech/RoomAudioCacheRepository.kt`
- Create: `app/schemas/com.mkread.app.core.database.MkreadDatabase/3.json`
- Test: `app/src/test/java/com/mkread/app/speech/AudioCacheKeyTest.kt`
- Test: `app/src/androidTest/java/com/mkread/app/core/database/Migration2To3Test.kt`

- [ ] Define `AudioCacheKeyInput` with book content hash, chapter id, sentence start/end, normalized text SHA-256, voice package SHA-256, style id, quality id, and generation configuration version `1`. Serialize fields in that fixed order with length prefixes before hashing.

- [ ] Write key tests proving every listed field changes the key, field delimiter characters cannot collide, and playback speed values are impossible to supply to the key function.

- [ ] Define `AudioCacheEntity` with primary key `cacheKey`, indexed `bookId`, indexed `chapterId`, sentence offsets, normalized text hash, voice package hash, style, quality, generation version, relative WAV path, byte size, last-access time, and `protectedUntil`. Add a foreign key to `BookEntity` with cascade delete.

- [ ] Write migration 2-to-3 and test old book/chapter/position data survives. Export schema 3; do not use destructive migration.

- [ ] Implement repository operations `find`, `commit`, `touch`, `protect`, `invalidateChapter`, `removeInvalid`, `evictToBudget`, and `clearGeneratedAudio`. A lookup validates file existence plus `WaveValidator`; invalid rows/files are removed and become cache misses.

- [ ] Store files under `cache/tts/<first-two-key-chars>/<cache-key>.wav`; commit a validated `.partial` file by atomic rename followed by the Room row. Delete the promoted file if the Room insert fails.

- [ ] Verify:

```powershell
.\gradlew.bat testDebugUnitTest --tests com.mkread.app.speech.AudioCacheKeyTest
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.mkread.app.core.database.Migration2To3Test
```

Expected: keys are deterministic, speed is absent, and migration preserves prior schemas.

- [ ] Commit:

```powershell
git add app/src/main/java/com/mkread/app/core/database app/src/main/java/com/mkread/app/speech app/src/test app/src/androidTest app/schemas
git commit -m "feat: add validated sentence audio cache"
```

## Task 2: Normalize Narration Text Deterministically

**Files:**
- Create: `app/src/main/java/com/mkread/app/speech/TextNormalizer.kt`
- Create: `app/src/main/java/com/mkread/app/speech/PronunciationOverrides.kt`
- Create: `app/src/main/assets/speech/pronunciation-zh-en.json`
- Test: `app/src/test/java/com/mkread/app/speech/TextNormalizerTest.kt`

- [ ] Write tests for full-width Latin letters/digits, repeated spaces, line breaks, curly quotes, Chinese ellipsis, decimal numbers, times, URLs, `AI`/`CPU` overrides, mixed Chinese/English, empty output, and an override trying to replace across word boundaries.

- [ ] Normalize with Unicode NFKC, convert horizontal/vertical whitespace runs to one pause-appropriate space, preserve `，。！？；：、`, normalize repeated dots to `……`, and trim. Do not translate prose or convert all Arabic numbers into Chinese numerals; ZipVoice retains responsibility for general number reading.

- [ ] Load reviewed literal/whole-token overrides from bounded JSON. Initial entries pronounce `AI` as `A I`, `CPU` as `C P U`, `GPU` as `G P U`, `Wi-Fi` as `Wi Fi`, and common URL scheme punctuation as short pauses. Apply longest token first with Unicode letter/digit boundaries.

- [ ] Return `NormalizedText(value, sha256, normalizerVersion=1)` and reject input that becomes blank. Never log the input/output value; errors identify chapter id and sentence offsets at the caller.

- [ ] Verify:

```powershell
.\gradlew.bat testDebugUnitTest --tests com.mkread.app.speech.TextNormalizerTest
```

Expected: normalization and hash snapshots are stable across repeated runs.

- [ ] Commit:

```powershell
git add app/src/main/java/com/mkread/app/speech app/src/main/assets/speech app/src/test/java/com/mkread/app/speech
git commit -m "feat: normalize Chinese English narration text"
```

## Task 3: Generate And Prefetch Sentence Audio

**Files:**
- Create: `app/src/main/java/com/mkread/app/speech/NarrationRequest.kt`
- Create: `app/src/main/java/com/mkread/app/speech/NarrationVoiceProvider.kt`
- Create: `app/src/main/java/com/mkread/app/speech/BuiltInVoiceProvider.kt`
- Create: `app/src/main/java/com/mkread/app/speech/SpeechGenerationCoordinator.kt`
- Create: `app/src/main/java/com/mkread/app/speech/GenerationState.kt`
- Test: `app/src/test/java/com/mkread/app/speech/SpeechGenerationCoordinatorTest.kt`

- [ ] Define `NarrationSentence` with book/chapter hashes and ids, sentence index/start/end, raw text, title metadata, and a monotonic queue generation id. Phase 4's `BuiltInVoiceProvider` always resolves the installed development/authorized neutral reference and its package hash.

- [ ] Write coordinator tests with fake engine/cache/voice/clock for cache hit, cache miss, current plus next-three prefetch order, duplicate request coalescing, cancellation on seek/chapter change, stale generation result rejection, one native call at a time, invalid generated WAV, and storage-full response.

- [ ] Implement a priority channel: current sentence priority 0, next three priorities 1-3. Deduplicate by cache key, allow only one engine generation, and cap queued requests at eight. A new queue generation cancels pending old work but lets an in-flight native call finish into an uncommitted temporary file that is discarded.

- [ ] Normal path: resolve voice; normalize; calculate key; validate cache hit; otherwise generate `.partial`; validate WAV/duration/sample count; commit cache; emit `Ready(sentence,file)`. Protect current plus next three cache entries for ten minutes.

- [ ] Failure policy: first attempt uses requested settings; second attempt uses built-in neutral voice plus fluent quality; after the second failure emit `Blocked(sentence,reason,retryable=true)`. Never advance to the following sentence on failure.

- [ ] Compute cache budget as `min(1 GiB, availableBytes / 10)`, with a 100 MB minimum only when available storage permits. Evict unprotected least-recently-used rows after commit and when app starts; stop prefetch and emit `StorageLow` when even the current chunk cannot be committed.

- [ ] Verify:

```powershell
.\gradlew.bat testDebugUnitTest --tests com.mkread.app.speech.SpeechGenerationCoordinatorTest
```

Expected: sentence order is invariant under cache hits, slow generation, cancellation, and retry; concurrency never exceeds one native call.

- [ ] Commit:

```powershell
git add app/src/main/java/com/mkread/app/speech app/src/test/java/com/mkread/app/speech
git commit -m "feat: pre-generate ordered sentence audio"
```

## Task 4: Specify The Playback State Machine

**Files:**
- Create: `app/src/main/java/com/mkread/app/playback/PlaybackModels.kt`
- Create: `app/src/main/java/com/mkread/app/playback/PlaybackReducer.kt`
- Create: `app/src/main/java/com/mkread/app/playback/PlaybackCheckpointStore.kt`
- Create: `app/src/main/java/com/mkread/app/playback/DataStorePlaybackCheckpointStore.kt`
- Test: `app/src/test/java/com/mkread/app/playback/PlaybackReducerTest.kt`
- Test: `app/src/test/java/com/mkread/app/playback/PlaybackCheckpointStoreTest.kt`

- [ ] Model states `Idle`, `Preparing(queue,current)`, `Playing(queue,current)`, `Paused(queue,current)`, `WaitingForAudio(queue,current)`, `Failed(queue,current,message)`, and `Completed`. Each queued entry has an exact `SentenceId(bookId,chapterId,index,start,end)`.

- [ ] Model events: load, audio-ready, play, pause, media-ended, next, previous, replay, seek-to-sentence, queue-replaced, generation-failed, focus-loss, focus-gain, noisy-output, timer-expired, and service-restored.

- [ ] Write table-driven reducer tests proving: ordered media end advances only to the immediately next sentence; missing audio enters `WaitingForAudio`; an old-generation audio result is ignored; next/previous clamp across chapter boundaries supplied by the queue source; replay retains id; permanent generation failure pauses on the same id; focus loss/noisy output pauses; timer expiry requests stop after the current sentence.

- [ ] The reducer returns state plus explicit effects (`RequestGeneration`, `SetPlayerItems`, `PlayPlayer`, `PausePlayer`, `PersistCheckpoint`, `StopService`, `ShowError`). It contains no Android or Media3 type.

- [ ] Persist book/chapter/sentence offsets, queue generation id, speed, was-playing flag, and update time in the application-scoped Preferences DataStore file `mkread.preferences_pb`. Prefix checkpoint keys with `playback_`, validate speed in `[0.5f,2.0f]`, and restore an invalid checkpoint as absent. `AppContainer` creates exactly one DataStore instance for this file; Phase 5 reuses it. Do not auto-play after an ordinary user relaunch; system media-button/service restoration may explicitly request resume.

- [ ] Verify:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.mkread.app.playback.*"
```

Expected: every transition and effect sequence matches the table; no test needs Android framework classes.

- [ ] Commit:

```powershell
git add gradle/libs.versions.toml app/build.gradle.kts app/src/main/java/com/mkread/app/playback app/src/test/java/com/mkread/app/playback
git commit -m "feat: define narration playback state machine"
```

## Task 5: Host Playback In A MediaSessionService

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/com/mkread/app/playback/PlaybackService.kt`
- Create: `app/src/main/java/com/mkread/app/playback/PlaybackCoordinator.kt`
- Create: `app/src/main/java/com/mkread/app/playback/SentenceMediaItemFactory.kt`
- Create: `app/src/main/java/com/mkread/app/playback/PlaybackNotificationProvider.kt`
- Test: `app/src/androidTest/java/com/mkread/app/playback/PlaybackServiceTest.kt`

- [ ] Add Media3 common, exoplayer, session, and test-utils. Declare only `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, and API-33 `POST_NOTIFICATIONS`; declare `PlaybackService` exported true with `foregroundServiceType="mediaPlayback"` and the `androidx.media3.session.MediaSessionService` intent action. Keep Internet absent.

- [ ] Create ExoPlayer with `AudioAttributes` usage media/content speech, automatic audio-focus handling, `setHandleAudioBecomingNoisy(true)`, and wake mode local. Create one MediaSession, return it from `onGetSession`, release player/session/engine dispatcher in `onDestroy`.

- [ ] Convert ready WAV files to `MediaItem`s with media id encoding the complete `SentenceId`, local file URI, title=book, artist=chapter, and extras for sentence offsets. Do not expose the raw sentence text in metadata or notification.

- [ ] Drive player calls only from reducer effects on the service main scope. Map `Player.Listener` item transitions, ended state, errors, and is-playing changes back into reducer events. Before adding a local file, revalidate it; remove/regenerate invalid cache entries.

- [ ] Use Media3's media notification lifecycle and a stable notification channel `mkread_playback` named `小说朗读`. Show book/chapter and standard previous/play-next controls. Starting narration from the visible reader is the only ordinary path that starts the foreground service.

- [ ] Write an instrumented test that connects a `MediaController` via `SessionToken`, loads three generated fixture WAVs, plays, pauses, seeks next/previous, replays, sets speed, and asserts controller timeline/media metadata/session state. Also assert the service enters foreground only during active/preparing playback.

- [ ] Verify:

```powershell
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.mkread.app.playback.PlaybackServiceTest
.\gradlew.bat offlineContract
```

Expected: controller commands work, service releases cleanly, and offline permission inspection still passes.

- [ ] Commit:

```powershell
git add gradle/libs.versions.toml app/build.gradle.kts app/src/main/AndroidManifest.xml app/src/main/java/com/mkread/app/playback app/src/androidTest/java/com/mkread/app/playback
git commit -m "feat: host narration in Media3 service"
```

## Task 6: Connect The Reader To Background Playback

**Files:**
- Create: `app/src/main/java/com/mkread/app/playback/PlaybackClient.kt`
- Create: `app/src/main/java/com/mkread/app/playback/MediaControllerPlaybackClient.kt`
- Modify: `app/src/main/java/com/mkread/app/feature/reader/ReaderViewModel.kt`
- Modify: `app/src/main/java/com/mkread/app/feature/reader/ReaderBottomBar.kt`
- Modify: `app/src/main/java/com/mkread/app/feature/reader/ReaderScreen.kt`
- Modify: `app/src/main/java/com/mkread/app/AppContainer.kt`
- Test: `app/src/test/java/com/mkread/app/feature/reader/ReaderPlaybackTest.kt`
- Test: `app/src/androidTest/java/com/mkread/app/feature/reader/ReaderPlaybackJourneyTest.kt`

- [ ] Define `PlaybackClient` commands `load(bookId,fromSentence)`, `play`, `pause`, `previous`, `next`, `replay`, `seekTo(sentenceId)`, and `setSpeed`; expose a read-only state flow. Hide Media3 futures/controllers behind this interface.

- [ ] Write ViewModel tests: play from saved/current sentence; play from selection; current sentence range updates from service state; active range clears on idle/error; active sentence outside the displayed page requests pager navigation; user page turns while playing seek only after explicit `从本页朗读`, never accidentally; end of chapter queues the first sentence of the next chapter.

- [ ] Replace disabled reader controls with icon buttons and tooltips: previous sentence, play/pause, next sentence, replay, and speed. Give every control a stable 48 dp touch target and do not let labels resize the bottom bar.

- [ ] Keep the controller connection application-scoped and release it when the process container closes, not on every activity recreation. Surface `正在准备语音`, `等待语音生成`, and typed retry/cleanup failures without hiding the readable page.

- [ ] Instrument the actual journey with short fixture WAV generation: tap play, observe highlight, wait for item transition, assert highlight/page follows, background the activity, wait for another transition, reopen, and assert the same active sentence.

- [ ] Verify:

```powershell
.\gradlew.bat testDebugUnitTest --tests com.mkread.app.feature.reader.ReaderPlaybackTest
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.mkread.app.feature.reader.ReaderPlaybackJourneyTest
```

Expected: controller remains connected across activity recreation and the UI follows the service's exact sentence id.

- [ ] Commit:

```powershell
git add app/src/main/java/com/mkread/app/playback app/src/main/java/com/mkread/app/feature/reader app/src/main/java/com/mkread/app/AppContainer.kt app/src/test app/src/androidTest
git commit -m "feat: synchronize reader with background narration"
```

## Task 7: Handle External Controls, Focus, And Sleep Timer

**Files:**
- Create: `app/src/main/java/com/mkread/app/playback/SleepTimer.kt`
- Modify: `app/src/main/java/com/mkread/app/playback/PlaybackService.kt`
- Modify: `app/src/main/java/com/mkread/app/playback/PlaybackCoordinator.kt`
- Create: `app/src/main/java/com/mkread/app/feature/reader/PlaybackOptionsSheet.kt`
- Test: `app/src/test/java/com/mkread/app/playback/SleepTimerTest.kt`
- Test: `app/src/androidTest/java/com/mkread/app/playback/ExternalControlsTest.kt`

- [ ] Implement monotonic-clock timer presets off/10/20/30/45/60/90 minutes. At expiry set `stopAfterCurrentSentence`; when that item ends, pause, persist the next unread sentence as checkpoint, clear playlist, and stop foreground/service. Cancel/replacing a timer is idempotent.

- [ ] Write virtual-time tests for every preset, replace/cancel, expiry while paused, expiry during a long sentence, service recreation from an absolute elapsed-realtime deadline, and clock reset causing safe cancellation rather than immediate stop.

- [ ] In the options bottom sheet add a 0.5x-2.0x stepper/slider in 0.05 increments and sleep timer menu. Apply speed through ExoPlayer and persist it; do not regenerate or change cache keys.

- [ ] Instrument media key play/pause, headset hook, next, previous, `AUDIO_BECOMING_NOISY`, transient focus loss, permanent focus loss, and focus regain. Lock the emulator screen with playback active and use UI Automator to assert the lock-screen media title and previous/play-next controls operate on the same sentence ids. Assert noisy/permanent loss pauses; transient loss follows ExoPlayer's focus policy; no command skips an unavailable sentence.

- [ ] Test notification permission denial on API 35: reader remains usable, starting playback shows an actionable notification-permission prompt/result, and the service follows platform foreground requirements without a crash loop.

- [ ] Verify:

```powershell
.\gradlew.bat testDebugUnitTest --tests com.mkread.app.playback.SleepTimerTest
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.mkread.app.playback.ExternalControlsTest
```

Expected: all external events map to one reducer transition and timer stops only at a sentence boundary.

- [ ] Commit:

```powershell
git add app/src/main/java/com/mkread/app/playback app/src/main/java/com/mkread/app/feature/reader app/src/test app/src/androidTest
git commit -m "feat: add media controls and sleep timer"
```

## Task 8: Restore Playback And Make Failures Actionable

**Files:**
- Create: `app/src/main/java/com/mkread/app/playback/PlaybackRestorer.kt`
- Create: `app/src/main/java/com/mkread/app/playback/PlaybackErrorMapper.kt`
- Modify: `app/src/main/java/com/mkread/app/playback/PlaybackService.kt`
- Modify: `app/src/main/java/com/mkread/app/feature/reader/ReaderScreen.kt`
- Test: `app/src/androidTest/java/com/mkread/app/playback/PlaybackRestorationTest.kt`

- [ ] Write restoration tests for activity removal from recents, activity process recreation while service remains, service recreation with valid checkpoint/cache, invalid cached WAV, edited chapter hash mismatch, deleted book, storage exhaustion, native initialization failure, and generation failure after neutral/fluent retry.

- [ ] Rebuild sentence identity from current chapter content and persisted character offset, not a stale page or queue index. If content hash changed, remap through the current sentence map and discard old queue/cache items.

- [ ] On invalid cache, delete/regenerate the same sentence. On deleted book, clear checkpoint and stop. On storage low, protect progress and offer `清理生成音频`; on TTS init failure keep reader/editor enabled and offer `重试语音引擎`; on repeated generation failure pause on the same sentence and offer retry.

- [ ] Removing the task from recents must not stop active playback. An explicit notification stop or app `停止朗读` command persists the next unread checkpoint, releases the queue, and stops the service.

- [ ] Never auto-play on ordinary app launch after a prior user stop. Restore playing only when the platform restarts an already-active foreground service or a media-button command explicitly asks for playback.

- [ ] Verify:

```powershell
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.mkread.app.playback.PlaybackRestorationTest
```

Expected: restoration never changes sentence order, readable text remains available during TTS failures, and no error path loops indefinitely.

- [ ] Commit:

```powershell
git add app/src/main/java/com/mkread/app/playback app/src/main/java/com/mkread/app/feature/reader app/src/androidTest/java/com/mkread/app/playback
git commit -m "fix: restore narration without skipping text"
```

## Task 9: Run The Phase 4 Gate

**Files:**
- Create: `scripts/run-background-smoke.ps1`
- Create: `app/src/androidTest/java/com/mkread/app/playback/BackgroundNarrationJourneyTest.kt`
- Create: `docs/test-evidence/emulator-api35/phase-4.md`

- [ ] Make the smoke script install debug, grant notification permission, seed a deterministic 30-sentence fixture, start narration from sentence 0, background the app, poll MediaSession every ten seconds for five minutes, and fail if media ids are duplicated, reversed, or advance by more than one. The final 60-minute endurance run belongs to Phase 6.

- [ ] The instrumented journey uses real ZipVoice for at least three short Chinese/English sentences, then cache-backed fixture WAVs for deterministic external-control scenarios. Assert cache hit on replay and no new generation when only speed changes.

- [ ] Run:

```powershell
.\gradlew.bat clean testDebugUnitTest lintDebug assembleDebug offlineContract
.\scripts\start-emulator.ps1
.\gradlew.bat connectedDebugAndroidTest
.\scripts\run-background-smoke.ps1
```

Expected: all suites pass, real local speech transitions in order, notification/controller remain usable in background, and five-minute sequence evidence has no gaps.

- [ ] Record Git commit, APK hash, permission list, native generation/cache timing, media id sequence, focus/noisy/headset outcomes, task-removal result, and service-restoration result in `phase-4.md`.

- [ ] Inspect logs for text leakage and commit:

```powershell
adb logcat -d | Select-String -Pattern '窗外下着小雨|我们回家吧'
git diff --check
git status --short
```

Expected: log search returns no prose; only evidence/smoke files remain uncommitted.

```powershell
git add scripts/run-background-smoke.ps1 app/src/androidTest/java/com/mkread/app/playback/BackgroundNarrationJourneyTest.kt docs/test-evidence/emulator-api35/phase-4.md
git commit -m "test: record Phase 4 background playback gate"
```

Phase 5 begins only when MediaSession controls and the reader agree on the same exact sentence id throughout backgrounding and restoration.
