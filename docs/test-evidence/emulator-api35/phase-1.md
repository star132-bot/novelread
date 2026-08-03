# Phase 1 Gate Evidence

- Observed: `2026-08-03T16:30:47.8566693+08:00`
- Base commit: `e51959b8995823865e8fceee9aafb59cd6a8b0f1`
- Device: `AffectLive_API_35` (`emulator-5554`)
- Android API: 35
- ABI: `x86_64`

## Gate Results

- `scripts/android-env.ps1`: passed with Microsoft JDK 17.0.16 and the pinned Android SDK.
- `scripts/fetch-speech-assets.ps1`: passed; all 362 pinned assets were rebuilt or verified from the three cached source downloads.
- `gradlew clean testDebugUnitTest lintDebug assembleDebug offlineContract`: passed from a clean build in 48 seconds.
- `gradlew installDebug connectedDebugAndroidTest`: passed from an absent-package state in 40 seconds.
- `gradlew assembleRelease`: passed in 36 seconds.
- Debug unit tests: 11 passed, 0 failed, 0 skipped across 5 test suites.
- Debug instrumented tests: 6 passed, 0 failed, 0 skipped on the API 35 emulator.
- Lint: 0 fatal issues, 0 errors, and 4 non-blocking warnings.

The initial `adb uninstall com.mkread.app` returned `DELETE_FAILED_INTERNAL_ERROR`. Immediate `pm list packages`, `pm list packages -u`, and `dumpsys package` checks confirmed that the package was already absent. The following install and full connected test run succeeded.

## APK Contract

- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`
- Size: 253,211,348 bytes
- SHA-256: `55BF55FD5AFEF2B227F3DBACADABC359D2731C07A3E7E68668A948C40E6B411E`
- Offline manifest contract: passed.
- Installed requested permissions: only `com.mkread.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`.
- Forbidden permissions absent: `android.permission.INTERNET` and `android.permission.ACCESS_NETWORK_STATE`.

The APK contains five native libraries for each supported ABI, including ONNX Runtime and the sherpa-onnx C, C++, and JNI libraries:

- `arm64-v8a`: present
- `x86_64`: present
- `armeabi-v7a`: absent
- `x86`: absent

## Speech Output

The installed debug screen generated and started playback for its prefilled Chinese sentence through the real ZipVoice JNI path.

- Output: `cache/spike/ui-preview.wav`
- WAV size: 154,284 bytes
- Format: RIFF/WAVE, mono PCM16, 24,000 Hz
- Audio duration: 3,213 ms
- Generation time: 2,259 ms
- Emulator real-time factor: 0.703
- Button-to-ready wall time, including UI polling: 4,116 ms

The real-time factor is diagnostic only. Emulator x86_64 performance is not the ARM64 device performance gate.

## Repository Hygiene

- `git diff --check`: passed.
- No downloaded `.aar`, `.onnx`, `.wav`, or `.tar.bz2` file is tracked by Git.
- Generated assets, APKs, reports, and captures remain ignored.

Automation verifies offline asset installation, native inference, WAV validity, and playback startup. Human judgment of Chinese intelligibility, audible volume, and emotional quality remains a manual acceptance item.
