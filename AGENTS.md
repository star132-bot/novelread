# MKread — agent notes

Offline Chinese novel reader for Android (Kotlin, Compose, Room, Media3, sherpa-onnx TTS) plus a cloud library service (`server/`, FastAPI + PostgreSQL).

Cloud library design and operations: `docs/cloud-library.md`. Book format: `docs/formats/mkbook-v1.md`. Server inventory and handoff notes are kept outside the repository (`docs/private/`, gitignored).

## Build and test

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
./scripts/fetch-speech-assets.sh          # once: speech models + sherpa-onnx AAR (gitignored)
./gradlew :app:testDebugUnitTest :app:assembleDebug
(cd tools/mkbook && python3 -m unittest)
```

The repository path must not contain spaces (Room/KSP fails).

## Rules

- Reading and narration must keep working fully offline; the network is only for the cloud library. `OfflineContractTest` enforces the permission/cleartext boundary.
- Book format changes go through `docs/formats/mkbook-v1.md` first; `tools/mkbook/mkbook.py` and `core/files/MkBookParser.kt` must enforce the same rules.
- Room schema changes need a migration in `MkreadDatabase` and the exported schema JSON in `app/schemas/`.
- The production servers run other services too. Change only MKread's own resources (`/opt/mkread-library`, `/srv/mkread-library`, database `mkread_library`); back up any shared config before editing; never print `.env` contents or secrets.
- Deploy the server by building on the app server itself with domestic mirrors (see `docs/cloud-library.md`); do not push images across the border.
- Never commit server addresses, credentials or `.env` files; this repository is public.
