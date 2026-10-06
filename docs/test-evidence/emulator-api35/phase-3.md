# Phase 3 Gate Evidence

- Observed: `2026-08-05T20:35:17.7151326+08:00`
- Tested implementation commit: `8707efef48f21d35f9e88b1bcf12aa6d43e37718`
- Branch: `feature/mkread-android`
- Device: `MKread_API_35_CI` (`emulator-5554`)
- Android API: 35
- ABI: `x86_64`
- Display: `1080x2400`, density `420`

## Gate Results

- `gradlew clean testDebugUnitTest lintDebug assembleDebug offlineContract`: passed from a clean build in 2 minutes 26 seconds.
- Debug unit tests: 139 total, 138 passed, 0 failed, 0 errors, 1 optional desktop-package-fixture test skipped across 19 suites.
- Lint: 0 errors and 25 non-blocking warnings.
- `gradlew connectedDebugAndroidTest`: passed in 3 minutes 2 seconds (109.735 seconds of device test time).
- Debug instrumented tests: 92 total, 90 passed, 0 failed, 0 errors, and 2 host-orchestrated process-restoration stages skipped by the ordinary suite as designed.
- The two skipped stages were run separately through `scripts/test-reader-process-restoration.ps1`; both passed.
- The deterministic layout-race regression and focused reader journey passed before the full suite.
- `git diff --check`: passed.

The device suite covers deterministic pagination, progressive long-chapter pagination, viewport and typography changes, 200% font scaling, native selectable text, copy and edit behavior, semantic reading-position restoration, import-source preservation, Room persistence, and the complete Phase 3 reader journey.

## Real Process Restoration

The host gate imported a deterministic TXT fixture, checkpointed a sentence, force-stopped the target and test packages, changed Android font scale from `1.0` to `1.35`, and started a new instrumentation process.

- Seed PID: `13896`
- Restored PID: `14014`
- Semantic character offset: `3060` before and after restart
- Baseline page range: `3060..4050`
- Restored page range: `3060..3579`
- Source SHA-256 before and after restart: `5ffb29dc6354cf11226e55c59e8fe9868565d94f5fc354e3eb6f281fdb16eac9`

The changed page boundary proves that the reader repaginated for the new scaled density. The unchanged semantic offset proves that restoration did not depend on the obsolete page index.

## Reader Journey

- Saved semantic offset: `1340`
- Selection: `1340..1360`
- Restored visible page: `774..1523`
- Original chapter-two SHA-256: `137adba8033f8bc669607468a707b147690c9fec5c87b19d6de8f956936be163`
- Edited chapter-two SHA-256: `e1cd55441820d0741fd80c8af82beb15eb267276d10c4102a72c4e364a741b15`
- Preserved imported source SHA-256: `5dba395bcb3782d5767451acae6ed981f43d5a522e19b51b240e6e131af14533`

The journey imports through the production worker, opens and paginates the novel, selects and copies text, checkpoints reading state, edits a chapter, recreates persistence, and verifies both the semantic position and original imported source.

## Pagination Diagnostics

- Long chapter: 426,125 characters
- First page available: 22 ms
- Full pagination: 6,585 ms
- Pages: 750
- Progressive batches: 95

The first page is published before full pagination completes, so the reader remains usable while later pages are produced.

## Deterministic Test Font

- Test-only subset: `app/src/androidTest/assets/fonts/NotoSansCJKsc-ReaderTest.otf`
- Size: 232,632 bytes
- Source Android API 35 font SHA-256: `9ca9debb09459bf4e3e7f826f5cd0f35f253902b85684921fce2ba3f28dd0f50`
- Subset SHA-256: `9d08babe463fbd7e11792e5a399f97afb3892a2db968fa5b1032fda07b5987ba`
- License: SIL Open Font License 1.1

The subset is packaged only in the instrumentation APK and removes emulator-default-font selection from pagination assertions.

## APK and Offline Contract

- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`
- Size: 287,872,071 bytes
- SHA-256: `3FBAEF8626DEB9D72CA3D34724E978F7D5F20932955ABBC95DB550C34C8439F1`
- Offline manifest contract: passed.
- Forbidden permissions absent: `android.permission.INTERNET` and `android.permission.ACCESS_NETWORK_STATE`.

## Runtime Diagnostics and Hygiene

- No StrictMode violation was present in any of the 92 per-test logcat artifacts from the Phase 3 full device run.
- The process-restoration script restores the original font scale and force-stops test processes in `finally` blocks.
- Host-only process-test bodies require an explicit instrumentation stage argument and are skipped by the ordinary connected suite. Their Compose rule may still launch the application during JUnit rule setup, so ordinary startup reconciliation remains possible.
- Test imports, edited books, process-gate state, and provider state are removed after verification.
- Local AVD, build outputs, captures, Gradle state, and Kotlin session state are ignored by Git.
