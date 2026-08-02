# MKread Phase 1 Foundation And Speech Spike Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Establish a reproducible native Android project and retire the highest technical risk by generating and playing a Chinese ZipVoice WAV on the local x86_64 API 35 emulator without any app network permission.

**Architecture:** Use a single Compose `:app`, a manual application container, and a narrow `SpeechEngine` interface. Package model data from a developer-local asset directory at build time, load sherpa-onnx through its prebuilt AAR on a bounded executor, and expose a debug-only speech-spike screen before feature development begins.

**Tech Stack:** JDK 17, Gradle 8.8, Android Gradle Plugin 8.6.0, Kotlin/Compose 2.0.21, compileSdk 35, sherpa-onnx 1.13.4 AAR, ZipVoice-Distill INT8 zh/en model, Vocos 24 kHz, JUnit 4, AndroidX Test.

---

## Task 1: Pin The Workstation Toolchain

**Files:**
- Modify: `.gitignore`
- Create: `scripts/android-env.ps1`
- Create: `scripts/start-emulator.ps1`
- Create: `docs/toolchain.md`

- [ ] Confirm the workstation facts before writing scripts:

```powershell
java -version
Get-ChildItem 'D:\spless\AffectLive\.android-sdk\platforms' -Directory
Get-ChildItem 'D:\spless\AffectLive\.android-sdk\build-tools' -Directory
& 'D:\spless\AffectLive\.android-sdk\emulator\emulator.exe' -list-avds
```

Expected: the default Java is not accepted unless it reports 17; `android-35`, build-tools `35.0.0`, and `AffectLive_API_35` are present.

- [ ] When JDK 17 is absent, install a user-scoped Temurin 17 JDK:

```powershell
winget install --exact --id EclipseAdoptium.Temurin.17.JDK --scope user --accept-package-agreements --accept-source-agreements
```

Expected: `winget` exits 0. Do not switch Gradle to Java 24 or the existing Java 8 installation.

- [ ] Add these untracked local inputs to `.gitignore`:

```gitignore
local.properties
.gradle/
.local-assets/
app/libs/*.aar
app/build/
build/
captures/
*.hprof
```

- [ ] Implement `scripts/android-env.ps1` so it searches in this order: `$env:MKREAD_JAVA_HOME`, directories matching `C:\Program Files\Microsoft\jdk-17*`, `C:\Program Files\Eclipse Adoptium\jdk-17*`, then `D:\java\jdk17*`; verifies `<candidate>\bin\java.exe`; parses `java -version`; rejects any major version other than 17; sets `JAVA_HOME`, `ANDROID_SDK_ROOT=D:\spless\AffectLive\.android-sdk`, and prepends both tool directories to `Path`; writes the resolved paths. On this workstation it must resolve `C:\Program Files\Microsoft\jdk-17.0.16.8-hotspot` without installing another JDK.

- [ ] Implement `scripts/start-emulator.ps1` from the verified paths in `android-emulator-start-guide.md`: check `adb devices` for an online emulator, otherwise start `emulator.exe -avd AffectLive_API_35 -no-snapshot-save` through `Start-Process -WindowStyle Hidden`, wait at most 180 seconds for `sys.boot_completed=1`, and fail with the emulator log path when boot does not complete.

- [ ] Document the fixed versions, SDK/AVD paths, optional `MKREAD_JAVA_HOME`, and the rule that model downloads happen outside the app in `docs/toolchain.md`.

- [ ] Verify:

```powershell
.\scripts\android-env.ps1
java -version
.\scripts\start-emulator.ps1
adb shell getprop ro.build.version.sdk
adb shell getprop ro.product.cpu.abi
```

Expected: Java 17, SDK `35`, ABI `x86_64`, and one device in state `device`.

- [ ] Commit:

```powershell
git add .gitignore scripts/android-env.ps1 scripts/start-emulator.ps1 docs/toolchain.md
git commit -m "build: pin Android development environment"
```

## Task 2: Scaffold The Gradle And Compose Application

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle.properties`
- Create: `gradle/libs.versions.toml`
- Create: `gradle/wrapper/gradle-wrapper.properties`
- Create: `gradlew`
- Create: `gradlew.bat`
- Create: `gradle/wrapper/gradle-wrapper.jar`
- Create: `app/build.gradle.kts`
- Create: `app/proguard-rules.pro`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/com/mkread/app/MkreadApplication.kt`
- Create: `app/src/main/java/com/mkread/app/MainActivity.kt`
- Create: `app/src/main/java/com/mkread/app/AppContainer.kt`
- Create: `app/src/main/java/com/mkread/app/ui/theme/Theme.kt`
- Create: `app/src/main/res/values/strings.xml`
- Create: `app/src/main/res/values/themes.xml`
- Create: `app/src/main/res/xml/backup_rules.xml`
- Create: `app/src/main/res/xml/data_extraction_rules.xml`
- Test: `app/src/test/java/com/mkread/app/FoundationTest.kt`
- Test: `app/src/androidTest/java/com/mkread/app/AppLaunchTest.kt`

- [ ] Copy the wrapper scripts and JAR from `D:\spless\AffectLive`, then set the distribution URL exactly:

```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-8.8-bin.zip
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```

- [ ] Define these version-catalog values: AGP `8.6.0`, Kotlin and Compose plugin `2.0.21`, Compose BOM `2024.12.01`, core-ktx `1.15.0`, activity-compose `1.9.3`, lifecycle `2.8.7`, navigation-compose `2.8.5`, coroutines `1.9.0`, JUnit `4.13.2`, AndroidX test `1.6.1`, Espresso `3.6.1`.

- [ ] Configure `app/build.gradle.kts` with namespace/application id `com.mkread.app`, minSdk 29, compile/targetSdk 35, Java/Kotlin target 17, Compose and BuildConfig generation enabled, and ABI filters `arm64-v8a` plus `x86_64`. Add `.local-assets/debug-assets` only to the debug source set when it exists; never add development prompts to `main` or `release` assets.

- [ ] Set `android:allowBackup="false"`, `android:supportsRtl="true"`, application class `.MkreadApplication`, and launcher activity `.MainActivity`. Do not declare Internet, storage, microphone, or media-playback service permissions yet.

- [ ] Create `FoundationTest` first:

```kotlin
package com.mkread.app

import org.junit.Assert.assertEquals
import org.junit.Test

class FoundationTest {
    @Test fun packageName_isStable() {
        assertEquals("com.mkread.app", BuildConfig.APPLICATION_ID)
    }
}
```

- [ ] Run it before the project exists:

```powershell
.\gradlew.bat testDebugUnitTest --tests com.mkread.app.FoundationTest
```

Expected failure: the `:app` project or `FoundationTest` source set is missing.

- [ ] Add the Gradle, manifest, resource, application, container, theme, and activity files. `MainActivity` calls `enableEdgeToEdge()` and renders a Material 3 scaffold that consumes safe drawing insets, with title `MKread` and body text `语音引擎准备中`; `AppContainer` is an application-scoped class with no dependencies in this task.

- [ ] Add `AppLaunchTest` that launches `MainActivity` through `createAndroidComposeRule<MainActivity>()` and asserts both `MKread` and `语音引擎准备中` are displayed.

- [ ] Verify:

```powershell
.\gradlew.bat testDebugUnitTest --tests com.mkread.app.FoundationTest
.\gradlew.bat assembleDebug
.\gradlew.bat installDebug
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.mkread.app.AppLaunchTest
```

Expected: every command ends in `BUILD SUCCESSFUL`, and MKread is launchable from the emulator.

- [ ] Commit:

```powershell
git add settings.gradle.kts build.gradle.kts gradle.properties gradle app
git commit -m "feat: scaffold native MKread Android app"
```

## Task 3: Enforce The Offline Manifest Contract

**Files:**
- Create: `scripts/check-offline-manifest.ps1`
- Create: `app/src/test/java/com/mkread/app/OfflineContractTest.kt`
- Modify: `app/build.gradle.kts`

- [ ] Write `OfflineContractTest` to read `src/main/AndroidManifest.xml` relative to the module directory and assert it contains neither `android.permission.INTERNET` nor `android.permission.ACCESS_NETWORK_STATE`.

- [ ] Run the focused test and temporarily add an Internet permission only in the test fixture string, not the real manifest, to confirm the assertion reports `INTERNET permission is forbidden`; remove that fixture mutation before proceeding.

- [ ] Implement `scripts/check-offline-manifest.ps1` to locate the newest `app-debug.apk`, run SDK `apkanalyzer manifest permissions`, fail if any line contains `INTERNET` or `ACCESS_NETWORK_STATE`, and print `Offline manifest contract passed` otherwise.

- [ ] Add a Gradle `offlineContract` task that depends on `assembleDebug` and invokes the script. Keep this check independent of source-text inspection because merged library manifests are the real package contract.

- [ ] Verify:

```powershell
.\gradlew.bat testDebugUnitTest --tests com.mkread.app.OfflineContractTest
.\gradlew.bat offlineContract
```

Expected: unit test passes and the script prints `Offline manifest contract passed`.

- [ ] Commit:

```powershell
git add scripts/check-offline-manifest.ps1 app/build.gradle.kts app/src/test/java/com/mkread/app/OfflineContractTest.kt
git commit -m "test: enforce offline Android package contract"
```

## Task 4: Fetch And Pin Development Speech Assets

**Files:**
- Create: `scripts/fetch-speech-assets.ps1`
- Create after first successful fetch: `speech-assets.lock.json`
- Modify: `.gitignore`
- Create: `docs/licenses/development-assets.md`

- [ ] Write the script with these fixed upstream URLs:

```text
https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.4/sherpa-onnx-1.13.4.aar
https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/sherpa-onnx-zipvoice-distill-int8-zh-en-emilia.tar.bz2
https://github.com/k2-fsa/sherpa-onnx/releases/download/vocoder-models/vocos_24khz.onnx
```

- [ ] Make `fetch-speech-assets.ps1` download into `.local-assets/downloads`, compute SHA-256 for every archive, and create `speech-assets.lock.json` on the first trusted fetch. On later runs it must compare hashes to the committed lock before extracting or copying any file.

- [ ] Extract with `tar.exe`, copy the AAR to `app/libs/sherpa-onnx-1.13.4.aar`, and produce this build-time layout:

```text
.local-assets/debug-assets/models/zipvoice/encoder.int8.onnx
.local-assets/debug-assets/models/zipvoice/decoder.int8.onnx
.local-assets/debug-assets/models/zipvoice/tokens.txt
.local-assets/debug-assets/models/zipvoice/lexicon.txt
.local-assets/debug-assets/models/zipvoice/espeak-ng-data/
.local-assets/debug-assets/models/zipvoice/vocos_24khz.onnx
.local-assets/debug-assets/voices/builtin-dev/prompts/neutral.wav
.local-assets/debug-assets/voices/builtin-dev/prompts/neutral.txt
.local-assets/debug-assets/speech-assets.json
```

- [ ] Copy `test_wavs/leijun-1.wav` only into the ignored debug asset directory and write its exact transcript as UTF-8:

```text
那还是三十六年前, 一九八七年. 我呢考上了武汉大学的计算机系.
```

- [ ] Validate required model filenames, reject files larger than 2 GB, generate `speech-assets.json` with schema version/path/size/SHA-256 for every copied model and prompt file, and print each copied file's size and SHA-256. Do not place downloaded binaries or prompt audio in Git.

- [ ] Record in `development-assets.md` that the upstream sample prompt is internal engineering material, is not the MKread production built-in voice, and cannot enter a release artifact without separate authorization.

- [ ] Run:

```powershell
.\scripts\fetch-speech-assets.ps1
Get-Content -Raw speech-assets.lock.json | ConvertFrom-Json | Format-List
git status --short
```

Expected: all required files exist, the lock has three URL/hash/size records, and `git status` shows the lock and documentation but not AAR/model/WAV binaries.

- [ ] Commit:

```powershell
git add .gitignore scripts/fetch-speech-assets.ps1 speech-assets.lock.json docs/licenses/development-assets.md
git commit -m "build: pin local ZipVoice development assets"
```

## Task 5: Define A Testable Speech Boundary

**Files:**
- Create: `app/src/main/java/com/mkread/app/speech/SpeechEngine.kt`
- Create: `app/src/main/java/com/mkread/app/speech/SpeechModels.kt`
- Create: `app/src/main/java/com/mkread/app/speech/WaveValidator.kt`
- Test: `app/src/test/java/com/mkread/app/speech/WaveValidatorTest.kt`
- Test: `app/src/test/java/com/mkread/app/speech/FakeSpeechEngine.kt`

- [ ] Add the production contract exactly:

```kotlin
package com.mkread.app.speech

import java.io.File

data class VoiceReference(
    val audioFile: File,
    val transcript: String,
    val sampleRate: Int = 24_000,
)

enum class SpeechQuality(val numSteps: Int) { FLUENT(4), HIGH(8) }

data class SpeechRequest(
    val text: String,
    val reference: VoiceReference,
    val quality: SpeechQuality,
    val outputFile: File,
)

data class SpeechResult(
    val file: File,
    val sampleRate: Int,
    val sampleCount: Int,
    val generationMillis: Long,
)

interface SpeechEngine : AutoCloseable {
    suspend fun initialize(): Result<Unit>
    suspend fun generate(request: SpeechRequest): Result<SpeechResult>
    override fun close()
}
```

- [ ] Write failing `WaveValidatorTest` cases for: missing file, header shorter than 44 bytes, non-RIFF data, zero sample data, wrong sample rate, and a valid mono 24 kHz PCM16 WAV.

- [ ] Implement `WaveValidator.requirePlayable(file)` with `RandomAccessFile`: check `RIFF`, `WAVE`, PCM format 1, mono channel count 1, sample rate 24,000, 16 bits per sample, and non-zero data chunk; return sample count. Walk chunks by declared size and account for even-byte padding instead of assuming a fixed 44-byte header.

- [ ] Provide `FakeSpeechEngine` only in test sources. It records requests and copies a generated valid 100 ms PCM16 silence WAV to `outputFile`; it does not sleep or touch Android APIs.

- [ ] Verify:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.mkread.app.speech.*"
```

Expected: all WAV boundary cases pass.

- [ ] Commit:

```powershell
git add app/src/main/java/com/mkread/app/speech app/src/test/java/com/mkread/app/speech
git commit -m "feat: define offline speech engine boundary"
```

## Task 6: Install Assets Into App-Private Storage

**Files:**
- Create: `app/src/main/java/com/mkread/app/speech/SpeechAssetInstaller.kt`
- Create: `app/src/main/java/com/mkread/app/speech/SpeechAssetManifest.kt`
- Test: `app/src/androidTest/java/com/mkread/app/speech/SpeechAssetInstallerTest.kt`

- [ ] Define `SpeechAssetManifest` with schema version, relative path, SHA-256, and byte size for every model and prompt file. Read the already generated `assets/speech-assets.json` bundled by the debug source set; fail installation when it is absent or does not match. Do not log prompt contents.

- [ ] Write an instrumented test that supplies a small fake asset tree, calls install twice, and asserts: first call copies into `files/models/zipvoice` and `files/voices/builtin-dev`; second call performs zero replacements; corrupting one installed file replaces only that file; a cancellation leaves the previous complete install intact.

- [ ] Run the focused test before implementation:

```powershell
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.mkread.app.speech.SpeechAssetInstallerTest
```

Expected failure: `SpeechAssetInstaller` does not exist.

- [ ] Implement installation through `files/speech-staging/<uuid>`, bounded 64 KiB copies, SHA-256 validation, a completion manifest written last, and atomic directory replacement. Reuse an already valid installed version by hash.

- [ ] Verify the focused test and inspect app-private files:

```powershell
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.mkread.app.speech.SpeechAssetInstallerTest
adb shell run-as com.mkread.app find files/models files/voices -type f
```

Expected: test passes and the required model and neutral prompt files are listed.

- [ ] Commit:

```powershell
git add app/build.gradle.kts app/src/main/java/com/mkread/app/speech app/src/androidTest/java/com/mkread/app/speech
git commit -m "feat: install verified speech assets privately"
```

## Task 7: Implement The sherpa-onnx ZipVoice Adapter

**Files:**
- Modify: `app/build.gradle.kts`
- Create: `app/src/main/java/com/mkread/app/speech/ZipVoiceSpeechEngine.kt`
- Create: `app/src/main/java/com/mkread/app/speech/SherpaWaveReader.kt`
- Test: `app/src/test/java/com/mkread/app/speech/ZipVoiceConfigTest.kt`
- Test: `app/src/androidTest/java/com/mkread/app/speech/ZipVoiceSmokeTest.kt`

- [ ] Add `implementation(files("libs/sherpa-onnx-1.13.4.aar"))` and retain only `arm64-v8a` and `x86_64` native libraries.

- [ ] Extract a pure `ZipVoicePaths.toConfig()` function and test that it maps private paths to `tokens.txt`, `encoder.int8.onnx`, `decoder.int8.onnx`, `vocos_24khz.onnx`, `espeak-ng-data`, and `lexicon.txt`, sets provider `cpu`, `numThreads=2`, `maxNumSentences=1`, and `silenceScale=0.2f`.

- [ ] Implement `SherpaWaveReader` for mono PCM16 reference prompts, returning normalized `FloatArray` values in `[-1, 1]` and rejecting other formats via `WaveValidator`.

- [ ] Implement `ZipVoiceSpeechEngine` with one dedicated single-thread `CoroutineDispatcher`, one `OfflineTts` instance, idempotent initialization, and synchronized close. Map a request to `GenerationConfig` with its reference samples, reference sample rate/transcript, `numSteps=request.quality.numSteps`, and `extra=mapOf("min_char_in_sentence" to "10")`.

- [ ] Generate to a sibling `*.partial` path, call `GeneratedAudio.save`, validate WAV structure and non-zero samples, atomically rename to the requested path, and return timing. On failure delete only the partial file and return `Result.failure`.

- [ ] Write `ZipVoiceSmokeTest` with text `窗外下着小雨，她轻声说：“我们回家吧。”`, the installed development neutral reference, fluent quality, and output under `cacheDir/spike/chinese.wav`. Assert a 24 kHz playable WAV, duration between 0.5 and 30 seconds, and no NaN sample values.

- [ ] Run JVM config tests first:

```powershell
.\gradlew.bat testDebugUnitTest --tests com.mkread.app.speech.ZipVoiceConfigTest
```

Expected: config mapping passes without loading JNI.

- [ ] Run the native emulator smoke test:

```powershell
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.mkread.app.speech.ZipVoiceSmokeTest
adb shell run-as com.mkread.app ls -l cache/spike/chinese.wav
```

Expected: the test passes and `chinese.wav` is larger than its header. A missing `libonnxruntime.so`, unsupported ABI, native crash, or empty audio is a Phase 1 blocker.

- [ ] Commit:

```powershell
git add app/build.gradle.kts app/src/main/java/com/mkread/app/speech app/src/test app/src/androidTest
git commit -m "feat: generate Chinese speech with ZipVoice"
```

## Task 8: Add A Debug-Only Speech Spike Screen

**Files:**
- Create: `app/src/debug/java/com/mkread/app/speech/SpikeScreen.kt`
- Create: `app/src/debug/java/com/mkread/app/speech/SpikeViewModel.kt`
- Create: `app/src/release/java/com/mkread/app/speech/SpikeScreen.kt`
- Modify: `app/src/main/java/com/mkread/app/MainActivity.kt`
- Test: `app/src/androidTest/java/com/mkread/app/speech/SpikeScreenTest.kt`

- [ ] Write a Compose test that launches the debug screen, asserts a text field prefilled with the smoke-test sentence, taps `生成并试听`, observes `正在生成`, waits up to 120 seconds, then observes `生成完成` and an enabled replay button.

- [ ] Implement immutable states `Idle`, `Installing`, `Generating`, `Ready(file,durationMs,generationMs)`, and `Failed(message)`. Disable duplicate generation while work is active and expose retry after failure.

- [ ] Use Android `MediaPlayer` only on this isolated risk screen to play the generated WAV. Release it in `onCleared`. Phase 4 replaces this with Media3; no feature code may depend on `MediaPlayer`.

- [ ] Make the release-source-set `SpikeScreen` return no UI and ensure debug-only labels cannot appear in a release APK.

- [ ] Verify on the emulator manually and with the test:

```powershell
.\gradlew.bat installDebug
adb shell am force-stop com.mkread.app
adb shell monkey -p com.mkread.app 1
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.mkread.app.speech.SpikeScreenTest
```

Expected: audible intelligible Chinese from the selected emulator audio output, no application-not-responding dialog, and a passing UI test.

- [ ] Capture generation time and duration in `docs/test-evidence/emulator-api35/speech-spike.md`; label real-time factor as diagnostic only because emulator speed is not the ARM64 performance gate.

- [ ] Commit:

```powershell
git add app/src/debug app/src/release app/src/main/java/com/mkread/app/MainActivity.kt app/src/androidTest docs/test-evidence/emulator-api35/speech-spike.md
git commit -m "feat: expose debug ZipVoice speech spike"
```

## Task 9: Run The Phase 1 Gate

**Files:**
- Create: `docs/test-evidence/emulator-api35/phase-1.md`

- [ ] Run the complete gate from a fresh app installation:

```powershell
.\scripts\android-env.ps1
.\scripts\fetch-speech-assets.ps1
.\gradlew.bat clean testDebugUnitTest lintDebug assembleDebug offlineContract
.\scripts\start-emulator.ps1
adb uninstall com.mkread.app
.\gradlew.bat installDebug connectedDebugAndroidTest
adb shell dumpsys package com.mkread.app
```

Expected: all builds/tests pass; uninstall may report `Unknown package` on the first run and is otherwise successful; package dump has no Internet permission; x86_64 JNI loads; the smoke WAV is valid.

- [ ] Record command timestamp, Git commit, emulator API/ABI, APK SHA-256 and size, unit/instrumented test counts, WAV size/duration/generation time, and permission inspection in `phase-1.md`.

- [ ] Inspect native ABIs:

```powershell
jar tf app\build\outputs\apk\debug\app-debug.apk | Select-String 'lib/(arm64-v8a|x86_64)/.*\.so'
```

Expected: both `arm64-v8a` and `x86_64` sherpa/ONNX native libraries are packaged; no 32-bit ABI is packaged.

- [ ] Run repository hygiene checks:

```powershell
git diff --check
git status --short
git ls-files | Select-String '\.(aar|onnx|wav|tar\.bz2)$'
```

Expected: no whitespace errors, only the evidence document is uncommitted, and no downloaded binary/model/prompt file is tracked.

- [ ] Commit:

```powershell
git add docs/test-evidence/emulator-api35/phase-1.md
git commit -m "test: record Phase 1 speech spike gate"
```

Phase 2 may begin only after this commit exists. If native generation fails, remain in Phase 1 and resolve the ABI, model, memory, or binding issue before building the library UI.
