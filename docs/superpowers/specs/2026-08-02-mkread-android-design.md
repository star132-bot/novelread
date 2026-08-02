# MKread Android Design

**Status:** Approved

**Date:** 2026-08-02

## 1. Product Goal

MKread is an offline Android novel reader for people who have a novel but are not able or ready to read it visually. Users import a TXT or EPUB file, choose a local voice, and listen while MKread keeps the spoken sentence synchronized with the paginated text.

The first release prioritizes a complete Android application. A separate Windows voice-production project is deferred until MKread can import and use the agreed `.mkvoice` package format.

## 2. Approved Scope

### 2.1 Included in the first Android release

- Import TXT and EPUB files through the Android system file picker.
- Copy imported books into app-private storage and preserve the source file.
- Detect TXT encodings and parse EPUB reading order and chapters.
- Build a local bookshelf with search, sorting, deletion, metadata, and progress.
- Paginate text for the current screen, typography, spacing, and insets.
- Support text selection, copying, chapter-level editing, save, and undo.
- Provide page-turn reading with current-sentence highlighting and automatic page following.
- Run Chinese and English TTS completely offline.
- Include one built-in voice and import additional `.mkvoice` packages.
- Provide an optional context-sensitive emotion mode.
- Configure playback speed, inference quality, sleep timer, typography, and night mode.
- Continue playback in the background with notification, lock-screen, headset, and audio-focus controls.
- Restore the book, chapter, page, sentence, and playback settings after process death or restart.
- Run functional acceptance tests on the local API 35 Android emulator.

### 2.2 Deferred

- PDF parsing, scanned-document OCR, and image-only books.
- Automatic character detection and multiple voices within one novel.
- Cloud TTS, cloud backup, accounts, analytics, and telemetry.
- A public voice marketplace or remote model downloads.
- The separate Windows voice-production application.
- Play Store release work until model, dataset, voice, and consent licensing has been reviewed.

## 3. Platform and Constraints

- Application type: native Android, not a web app and not a WebView shell.
- Language and UI: Kotlin with Jetpack Compose; Simplified Chinese is the primary UI language.
- Minimum Android version: Android 10, API 29.
- Development emulator: `AffectLive_API_35`, reused from `D:\spless\AffectLive`.
- Release CPU target: `arm64-v8a`.
- Emulator CPU target: `x86_64`.
- Minimum target device memory: 6 GB RAM.
- Build JDK: JDK 17. The machine's default Java 8 is not sufficient for the planned Android toolchain.
- Initial content scale: one book up to 20 MB and a library of up to 100 books.
- Privacy: no network permission, no upload of books, recordings, voices, progress, or usage data.
- Offline definition: importing, parsing, reading, editing, synthesis, and playback work in airplane mode after installation.

## 4. Architecture

MKread uses feature-oriented modules with explicit interfaces between reading, storage, speech generation, and playback. The TTS model is shared by all voices; user voice packages contain reference material and metadata rather than executable code.

```text
System file picker
       |
       v
Book import -> TXT/EPUB parser -> chapter store -> pagination engine
                                             |             |
                                             |             v
                                             |       Compose reader UI
                                             |             |
                                             v             v
                                      sentence selector -> highlight state
                                             |
                                             v
text normalizer -> emotion analyzer -> voice-style resolver
                                             |
                                             v
                        sherpa-onnx + ZipVoice-Distill INT8
                                             |
                                             v
                                  audio pre-generation queue
                                             |
                                             v
                         Media3 player in MediaSessionService
                                             |
                    notification / lock screen / headset controls
```

### 4.1 Main component boundaries

- `library`: book import, metadata, bookshelf, deletion, and search.
- `reader`: chapter navigation, pagination, selection, editing, highlighting, and position restoration.
- `voice`: voice-package validation, installation, selection, preview, and style resolution.
- `speech`: text normalization, contextual emotion selection, ZipVoice inference, and audio caching.
- `playback`: queue coordination, Media3 integration, foreground service lifecycle, and external controls.
- `settings`: DataStore-backed display, speech, quality, and timer settings.
- `database`: Room entities and transactional repositories.

No UI component accesses files, Room, or native TTS bindings directly. UI state is exposed through ViewModels and use-case interfaces.

## 5. Native Android User Experience

### 5.1 Bookshelf

- `MKread` is the primary title.
- Search and sort actions appear in the top app bar.
- Each row shows cover treatment, title, current chapter, last-opened time, and progress.
- A familiar add/import icon opens the system file picker.
- Long-press or an overflow menu exposes rename, metadata edit, and remove from library.
- Removing a book requires confirmation, deletes only the app-private copy, and never deletes the user's original external file.

### 5.2 Reader

- The first release uses focused left/right page turning, not continuous scrolling.
- The top bar contains back, chapter title, night-mode access, and an overflow menu.
- A long press opens Android text selection and commands for copy, edit, and read from selection.
- The currently spoken sentence is highlighted; the page changes when the sentence leaves the current page.
- The bottom player exposes previous sentence, play/pause, next sentence, replay, and speed.
- Additional controls are placed in a bottom sheet to keep the reading surface quiet.

### 5.3 Voice library

- Shows the active voice, built-in voice, and imported voices.
- Each voice supports preview, select, and package inspection; imported voices can be deleted, while the built-in voice cannot.
- `.mkvoice` import uses the system file picker and validates before installation.
- Invalid packages never replace or modify an installed voice.

### 5.4 Settings

- Emotion reading toggle, enabled by default.
- Inference quality: `Fluent` and `High quality`.
- Playback speed from 0.5x to 2.0x.
- Sleep timer presets: off, 10, 20, 30, 45, 60, and 90 minutes.
- System, light, and dark theme modes.
- Font size, line spacing, horizontal margin, and screen-on behavior while reading.

## 6. Book Import and Parsing

### 6.1 Import transaction

1. The user selects a TXT or EPUB document through the Storage Access Framework.
2. MKread copies the selected stream into a temporary directory under app-private storage.
3. The file type, size, hash, and structure are validated.
4. Parsing writes normalized chapters and metadata into a new book directory.
5. Room metadata is committed only after all required files are durable.
6. On failure, the temporary directory is removed and no partial book appears on the shelf.

### 6.2 TXT rules

- Detect UTF-8 and UTF-16 byte-order marks first.
- Validate UTF-8 without a byte-order mark.
- Fall back to GB18030 for common Chinese legacy text files.
- Normalize CRLF and CR line endings to LF internally.
- Collapse excessive blank lines while retaining paragraph boundaries.
- Use tested Chinese chapter-heading patterns such as `第...章`, `第...节`, `卷`, and common prologue/epilogue labels.
- Preserve a single synthetic chapter when no reliable chapter headings are found.

### 6.3 EPUB rules

- Treat EPUB as a ZIP container and reject path traversal entries.
- Read `META-INF/container.xml`, the package document, manifest, spine, and navigation data through XML parsers.
- Follow spine order for reading order.
- Parse XHTML with a structured HTML parser.
- Remove scripts, styles, navigation chrome, and hidden content.
- Preserve headings, paragraphs, lists, scene breaks, and meaningful line breaks.
- Extract title, author, language, and cover when available.
- Continue with readable spine items when optional metadata is malformed; fail only when no readable content remains.

## 7. Storage and Data Model

### 7.1 File layout

```text
files/
  books/<book-id>/
    original/source.txt-or-epub
    content/chapter-0001.txt
    content/chapter-0002.txt
    metadata.json
  voices/<voice-id>/
    manifest.json
    prompts/
    checksums.json
  models/zipvoice/
    encoder.int8.onnx
    decoder.int8.onnx
    vocos_24khz.onnx
    tokens-and-lexicon-files

cache/
  tts/<hash-prefix>/<cache-key>.wav
  import/<transaction-id>/
```

### 7.2 Room records

- `BookEntity`: identity, title, author, source type, hashes, cover, import time, and modified time.
- `ChapterEntity`: book, order, title, file path, character count, and content hash.
- `ReadingPositionEntity`: chapter, character offset, page index, sentence index, and update time.
- `BookDisplaySettingsEntity`: font, line spacing, margins, and theme override.
- `VoiceProfileEntity`: package identity, name, engine, languages, installed styles, and package hash.
- `AudioCacheEntity`: cache key, sentence identity, voice/style parameters, file path, size, and last access.

Data changes that affect files and Room records use repositories with explicit transactions and compensating cleanup.

## 8. Pagination, Selection, and Editing

### 8.1 Pagination

- Page boundaries are calculated from the current window size, safe insets, font metrics, line spacing, margins, and chapter text.
- A page stores start and end character offsets, not copied text.
- Pagination runs off the main thread and publishes batches so the first pages appear before a long chapter is fully paginated.
- The cache key includes chapter content hash, viewport dimensions, font identity, font size, line spacing, and margins.
- Rotation, split-screen changes, or typography changes create a new pagination cache without losing the semantic character position.

### 8.2 Copy and selection

- Android selection handles define the range.
- Copy writes plain text to the Android clipboard.
- `Read from here` snaps to the nearest sentence boundary and updates playback position.

### 8.3 Editing

- Editing applies only to the normalized app-private chapter copy.
- The original imported TXT or EPUB remains unchanged.
- The editor loads one chapter at a time.
- Save creates a transactional chapter update and one undo snapshot for the previous saved version.
- After save, only the edited chapter's pagination, sentence map, and audio cache are invalidated.
- Reading position is remapped to the nearest surviving character offset.

## 9. Offline Speech Engine

### 9.1 Model choice

- Runtime: sherpa-onnx native Android bindings.
- TTS model: ZipVoice-Distill INT8 Chinese-English model.
- Vocoder: compatible 24 kHz Vocos model.
- Model files are shared across all installed voices.
- The development build includes local model assets so the entire acceptance flow works without network access.
- Distribution packaging is reconsidered only after the prototype's size and licensing review; network download is not introduced into the first approved scope.

ZipVoice was selected because it supports Chinese and English zero-shot voice cloning. Supertonic 3 was rejected for this scope because its official open model language list does not include Chinese.

### 9.2 Speech pipeline

1. Resolve the current sentence and a small context window around it.
2. Normalize punctuation, numbers, Latin abbreviations, and known pronunciation overrides.
3. Run contextual emotion analysis when enabled.
4. Select the best available reference style from the active voice.
5. Generate a WAV chunk through sherpa-onnx on a dedicated bounded executor.
6. Validate the generated duration and sample data.
7. Commit the file to the audio cache and enqueue it as a Media3 item.

The service pre-generates the next two or three sentences. Generation never runs on the main thread.

### 9.3 Quality modes

- `Fluent`: fewer flow-matching steps and a conservative thread count; this is the default.
- `High quality`: more flow-matching steps and deeper prefetch. It is disabled with an explanation when Android reports a low-RAM device or model allocation fails; `Fluent` remains available.
- Playback speed is applied by the player from 0.5x to 2.0x so the same generated chunk can be reused at different user speeds.
- Emotion-specific pacing stays within narrow bounds to avoid distorted speech.

## 10. Contextual Emotion Design

The first release uses a deterministic, testable `ContextEmotionAnalyzer`. It examines the current sentence plus adjacent sentences and maps the passage to one of five labels:

- `neutral`
- `joy`
- `sadness`
- `anger`
- `tension`

Signals include punctuation, dialogue markers, intensifiers, emotion vocabulary, narration verbs, sentence length, and nearby context. Ambiguous input returns `neutral`.

The `VoiceStyleResolver` then applies this priority:

1. Use the matching emotion reference supplied by the voice package.
2. Use the neutral reference with conservative pause, volume, and pacing changes.
3. If an installed package becomes unreadable at runtime, use the built-in neutral voice and display a package warning.

Disabling emotion reading skips the analyzer and always selects the active voice's neutral reference. The analyzer is behind an interface so a licensed compact classifier can replace it later without changing reader or playback code.

## 11. `.mkvoice` Package Format

`.mkvoice` is a ZIP container with schema version 1. It contains data only and never contains executable code or native libraries.

```text
manifest.json
prompts/neutral.wav
prompts/neutral.txt
prompts/joy.wav
prompts/joy.txt
prompts/sadness.wav
prompts/sadness.txt
prompts/anger.wav
prompts/anger.txt
prompts/tension.wav
prompts/tension.txt
checksums.json
```

Only `neutral` is required. The other four styles are optional but required for full-fidelity emotion switching.

Example manifest:

```json
{
  "schemaVersion": 1,
  "id": "com.example.voice.yunlan",
  "displayName": "Yunlan",
  "engine": "zipvoice-distill-int8-zh-en",
  "languages": ["zh-CN", "en"],
  "creator": "Local creator",
  "consent": {
    "declared": true,
    "statement": "The creator declares authorization to package this voice."
  },
  "styles": [
    {
      "emotion": "neutral",
      "audio": "prompts/neutral.wav",
      "transcript": "prompts/neutral.txt",
      "sampleRate": 24000
    }
  ],
  "checksums": "checksums.json"
}
```

Validation rules:

- Maximum uncompressed package size: 250 MB.
- Maximum individual prompt duration: 60 seconds.
- Prompt audio is normalized by the future voice tool to mono, 24 kHz, PCM 16-bit WAV.
- Reference transcript must be non-empty. MKread validates its structure but cannot prove semantic correspondence; the future voice tool generates it from the selected recording and requires user confirmation.
- Every declared file must have a SHA-256 entry.
- Absolute paths, parent-directory paths, symbolic links, duplicate paths, and undeclared payload files are rejected.
- A failed import leaves existing voices unchanged.

The built-in voice uses the same manifest and resolver path as imported voices.

## 12. Background Playback

- Playback is hosted by a `MediaSessionService` with Media3.
- The service owns the player, media session, TTS generation coordinator, and sentence queue.
- Required foreground service permissions and the `mediaPlayback` service type are declared.
- Media metadata shows book and chapter information in Android system controls.
- Notification, lock-screen, headset, Bluetooth, and audio-focus commands map to play, pause, previous sentence, and next sentence.
- Incoming audio focus loss pauses or ducks according to Android guidance; calls and exclusive audio focus pause narration.
- Removing the activity from recent apps does not stop ongoing playback.
- A sleep timer stops after the selected duration or at the end of the current sentence.
- If generation cannot keep up, playback waits at a sentence boundary and shows a preparing state. It never skips text silently.

The persisted playback checkpoint is updated at sentence boundaries and periodically during long sentences.

## 13. Cache Policy

The audio cache key includes:

```text
book content hash
chapter id and sentence offsets
normalized text hash
voice package hash
selected emotion style
quality mode
generation configuration version
```

Playback speed is intentionally excluded because Media3 applies it during playback.

- Default cache budget: 1 GB or 10 percent of available app storage, whichever is smaller.
- Active-book and next-sentence chunks are protected from eviction.
- Remaining chunks use least-recently-used eviction.
- Users can clear generated audio without deleting books or voices.
- Content edits invalidate only matching chapter entries.

## 14. Privacy, Security, and Licensing

- The Android manifest does not request Internet access.
- Books, prompts, voices, generated audio, and progress stay in app-private storage.
- XML, HTML, ZIP, and JSON inputs are parsed with bounded, structured parsers.
- ZIP traversal and decompression expansion limits are enforced for EPUB and `.mkvoice`.
- Debug logs never contain full book text or raw voice recordings.
- Voice metadata records a consent declaration, but MKread does not claim to prove legal ownership.
- UI copy warns users to import only voices they are authorized to use.
- The ZipVoice code and model package, training-data provenance, built-in voice, and future voice-tool outputs require a written distribution-license review before any public release.

## 15. Failure Handling

- Import failure: remove temporary data, preserve the external source, and show the specific validation or parsing reason.
- Malformed EPUB: use readable spine items when possible; otherwise reject with a clear message.
- Corrupt `.mkvoice`: reject atomically and retain installed voices.
- TTS initialization failure: keep reading and editing available, disable play, and expose a retry action.
- Generation failure: retry once with neutral style and fluent quality, then pause with an actionable message.
- Slow generation: wait at a sentence boundary rather than skip content.
- Storage exhaustion: stop pre-generation, protect progress, and offer cache cleanup.
- Service or process death: restore the last committed sentence checkpoint and queue from local data.
- Invalid cached audio: remove the entry and regenerate it.

## 16. Testing Strategy

### 16.1 JVM tests

- TXT encoding and chapter detection fixtures.
- EPUB container, OPF, spine, navigation, and XHTML fixtures.
- ZIP traversal and decompression-limit cases.
- Pagination offset stability across typography changes.
- Chapter edit and position-remapping behavior.
- Emotion classification and neutral fallback cases.
- Voice package manifest and checksum validation.
- Audio cache keys and targeted invalidation.

### 16.2 Android instrumented tests

- System picker import into app-private storage.
- Bookshelf search, sort, rename, and delete.
- Page turning, selection, clipboard copy, edit, save, and undo.
- Night mode and process recreation.
- Voice import, preview, select, and delete.
- Media controller connection and service restoration.

### 16.3 End-to-end acceptance

- Build, install, and launch on `AffectLive_API_35`.
- Import valid TXT and EPUB samples and reject invalid samples.
- Read, paginate, copy, edit, undo, and restore position.
- Generate Chinese speech with the built-in voice in airplane mode.
- Compare emotion enabled and disabled with repeatable sample passages.
- Import a valid `.mkvoice`, preview it, and use it for narration.
- Continue narration with the app backgrounded and the screen locked.
- Run continuous background narration for 60 minutes without skipped sentences.
- Validate the 20 MB single-book and 100-book library targets.

The emulator is the functional gate. An ARM64 phone is the mandatory performance gate for real-time factor, memory, temperature, and battery consumption before declaring the speech engine production-ready.

## 17. Delivery Sequence

1. Initialize the Android project, Git repository, JDK 17, SDK paths, NDK/native libraries, and emulator scripts.
2. Build a risk-first speech spike that generates Chinese ZipVoice audio on x86_64 emulator and later ARM64 hardware.
3. Build the Room-backed bookshelf and transactional TXT/EPUB importer.
4. Build chapter storage, pagination, selection, editing, and reading-position restoration.
5. Build Media3 playback, foreground service, notification, external controls, and audio-focus handling.
6. Add TTS pre-generation, cache policy, active-sentence highlighting, and automatic page following.
7. Add `.mkvoice` validation, voice library, built-in voice packaging, and preview.
8. Add contextual emotion analysis, style resolution, quality modes, and sleep timer.
9. Complete offline, failure, performance, accessibility, and emulator acceptance testing.
10. After MKread is stable, design the separate Windows voice-production application against `.mkvoice` schema version 1.

## 18. Approved Decisions Summary

- Android application first; Windows voice tool later.
- Fully offline operation.
- TXT and EPUB in the first release; PDF/OCR later.
- One selected voice for all narration; no automatic multi-character casting.
- Context-sensitive emotion is optional and can be disabled.
- Android 10+, ARM64, 6 GB RAM target; x86_64 API 35 emulator for functional work.
- Focused page-turn reading rather than continuous scroll.
- Edits affect an app-private copy and preserve the source file.
- One built-in offline voice plus `.mkvoice` import support.
- Native Kotlin/Compose, sherpa-onnx, ZipVoice-Distill INT8, Room, DataStore, and Media3.
- Functional acceptance on the emulator and performance acceptance on a real ARM64 device.
