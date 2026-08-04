# Phase 2 Gate Evidence

- Observed: `2026-08-04T17:12:19.2415149+08:00`
- Tested implementation base: `569307f592f04fc0e30a47d33ca2e6fc45919ba2`
- Branch: `feature/mkread-android`
- Device: `AffectLive_API_35` (`emulator-5554`)
- Android API: 35
- ABI: `x86_64`

## Gate Results

- `gradlew clean testDebugUnitTest lintDebug assembleDebug`: passed from a clean build in 1 minute 19 seconds.
- `gradlew offlineContract`: passed.
- `gradlew connectedDebugAndroidTest`: passed in 1 minute on the API 35 emulator.
- Debug unit tests: 72 passed, 0 failed, 0 errors, 0 skipped across 12 suites.
- Debug instrumented tests: 45 passed, 0 failed, 0 errors, 0 skipped.
- Lint: 0 fatal issues, 0 errors, and 24 non-blocking warnings.

The app launch check verifies the native bookshelf is the start destination. The full device run also covers the bookshelf UI, Room persistence, import worker, source reconciliation, secure Android XML parsing, speech asset installation, native speech smoke generation, and the Phase 2 import journey.

## Import Journey

- Human-readable TXT fixture SHA-256: `71ed39d535c8693e7226d0afe1076f030f7056f654ade7b72443457c966834dc`
- Generated valid EPUB SHA-256: `f07aa9395c59bfd5642eec3ab01338d35fe7b87f9fffad0b3da8c5692b37af11`
- Generated malformed EPUB SHA-256: `7fccdbe667032c32a9562d796d1f61497231de4696787cbf0c5509d9f49b6cda`

The TXT import produced `第一章 雨夜` and `第二章 清晨`. Renaming, author editing, and removal changed only app-private data; the external provider byte hash remained unchanged. The EPUB import followed its declared spine order (`第二章`, then `第一章`) and remained available with the same chapter order and external hash after the file-backed Room database and repository were closed and recreated.

The malformed EPUB referenced a missing package document. It returned `MALFORMED_EPUB`, committed no partial book, and left the previously imported valid EPUB intact. Unit and device tests also passed for traversal and absolute ZIP paths, duplicate entries, excessive entry count, expanded-size limits, invalid ZIP signatures, unsafe spine references, and XML `DOCTYPE` rejection.

## Performance Diagnostics

- Search and deterministic title sort across 100 seeded books: 5 ms; release assertion limit: 500 ms.
- Exact 20 MiB TXT parse: 2,898 ms; result: 5 bounded chapters; correctness timeout: 60 seconds.

These measurements are emulator diagnostics. The 20 MiB timing is recorded without a host-specific release threshold.

## APK Contract

- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`
- Size: 287,320,628 bytes
- SHA-256: `DE2BF57F2297790D579BB1DF251E8B658A579EA58B19C4FCD7CC9594C0B728E6`
- Offline manifest contract: passed.
- Forbidden permissions absent: `android.permission.INTERNET` and `android.permission.ACCESS_NETWORK_STATE`.

## Repository Hygiene

- Runtime EPUB fixtures are generated from reviewed inline text; no binary EPUB fixture is tracked.
- Source fixtures, generated import files, and temporary databases are deleted after each device test.
- `git diff --check`: passed before the gate commit.
