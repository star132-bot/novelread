# MKread Phase 2 Library And Import Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver a Room-backed bookshelf and atomic TXT/EPUB import pipeline that preserves the external source, normalizes chapters into app-private storage, and remains responsive at the approved 20 MB/book and 100-book scale.

**Architecture:** Keep parsing and filesystem operations behind pure interfaces. Copy a selected SAF document into a bounded staging transaction, identify and parse it, atomically promote normalized files, then commit Room metadata; compensate and clean orphan directories on failure. Expose library state through a repository and ViewModel to Compose.

**Tech Stack:** Room 2.6.1 with KSP 2.0.21-1.0.28, WorkManager 2.10.0, kotlinx.coroutines 1.9.0, kotlinx.serialization JSON 1.7.3, Jsoup 1.18.3, Android Storage Access Framework, Compose Material 3, JUnit temporary folders, AndroidX instrumented tests.

---

## Task 1: Add Core Book Models And Room Schema

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Create: `app/src/main/java/com/mkread/app/core/model/BookModels.kt`
- Create: `app/src/main/java/com/mkread/app/core/database/BookEntity.kt`
- Create: `app/src/main/java/com/mkread/app/core/database/ChapterEntity.kt`
- Create: `app/src/main/java/com/mkread/app/core/database/BookDao.kt`
- Create: `app/src/main/java/com/mkread/app/core/database/ChapterDao.kt`
- Create: `app/src/main/java/com/mkread/app/core/database/MkreadDatabase.kt`
- Create: `app/schemas/com.mkread.app.core.database.MkreadDatabase/1.json`
- Test: `app/src/androidTest/java/com/mkread/app/core/database/MkreadDatabaseTest.kt`

- [ ] Add Room runtime/ktx/compiler, KSP, WorkManager, serialization JSON, Jsoup, and the Kotlin serialization Gradle plugin version `2.0.21`. Enable Room schema export to `$projectDir/schemas` and package those schemas as Android test assets.

- [ ] Define stable domain values:

```kotlin
enum class SourceType { TXT, EPUB }
enum class LibrarySort { LAST_OPENED, TITLE, IMPORTED }

data class BookSummary(
    val id: String,
    val title: String,
    val author: String?,
    val sourceType: SourceType,
    val coverPath: String?,
    val chapterTitle: String?,
    val progressFraction: Float,
    val lastOpenedAt: Long?,
)
```

- [ ] Define `BookEntity` with primary key `id`, `title`, nullable `author`, `sourceType`, `sourceSha256`, nullable `coverRelativePath`, `importedAt`, `modifiedAt`, and nullable `lastOpenedAt`. Add unique index on `sourceSha256` so re-importing the same bytes produces a duplicate result rather than a second book.

- [ ] Define `ChapterEntity` with primary key `id`, indexed foreign key `bookId` with cascade delete, unique `(bookId, ordinal)` index, `ordinal`, `title`, `relativePath`, `characterCount`, and `contentSha256`.

- [ ] Write database tests first for inserting a book with two ordered chapters, observing the library flow, rejecting a duplicate source hash, and cascading chapter deletion.

- [ ] Run before DAO/database implementation:

```powershell
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.mkread.app.core.database.MkreadDatabaseTest
```

Expected failure: Room entities/DAO/database are unresolved.

- [ ] Implement DAO queries with deterministic tie breakers: library search matches title or author using escaped `LIKE`; sort by last-opened/import/title according to separate query methods; chapters always order by `ordinal ASC`. Use one `@Transaction` method to insert a book and all chapters.

- [ ] Build an in-memory `MkreadDatabase` in the test, pass all cases, and verify schema JSON is exported.

- [ ] Commit:

```powershell
git add gradle/libs.versions.toml app/build.gradle.kts app/src/main/java/com/mkread/app/core app/src/androidTest/java/com/mkread/app/core app/schemas
git commit -m "feat: add Room book and chapter schema"
```

## Task 2: Build Bounded App-Private Book Storage

**Files:**
- Create: `app/src/main/java/com/mkread/app/core/files/BookStorage.kt`
- Create: `app/src/main/java/com/mkread/app/core/files/FileHash.kt`
- Create: `app/src/main/java/com/mkread/app/core/files/ImportLimits.kt`
- Create: `app/src/main/java/com/mkread/app/core/files/StorageException.kt`
- Test: `app/src/test/java/com/mkread/app/core/files/BookStorageTest.kt`

- [ ] Fix limits in `ImportLimits`: source bytes `20 * 1024 * 1024`, expanded EPUB bytes `80 * 1024 * 1024`, ZIP entries `5_000`, chapter characters `5_000_000`, copy buffer `64 * 1024`.

- [ ] Write failing tests using JUnit `TemporaryFolder` for: staging directory creation under `cache/import/<transaction-id>`; bounded stream copy and SHA-256; rejection at source limit plus one byte; normalized chapter filenames `chapter-0001.txt`; atomic promotion into `files/books/<book-id>`; cleanup after an injected move failure; source input remaining byte-identical; deleting one app-private book without touching an external sentinel.

- [ ] Define the storage API:

```kotlin
interface BookStorage {
    fun begin(transactionId: String): ImportStaging
    fun copySource(input: InputStream, staging: ImportStaging, extension: String): CopiedSource
    fun writeChapter(staging: ImportStaging, ordinal: Int, text: String): StoredChapter
    fun writeMetadata(staging: ImportStaging, metadata: StoredBookMetadata)
    fun promote(staging: ImportStaging, bookId: String): File
    fun deleteBook(bookId: String)
    fun cleanStaleTransactions(nowMillis: Long)
}
```

- [ ] Implement path containment with `Path.normalize()` and an explicit `candidate.startsWith(root)` check for every relative path. Write UTF-8 chapter data through a temporary file, flush and close it, then rename. Never accept a caller-supplied absolute path.

- [ ] Make stale cleanup delete staging directories older than 24 hours and book directories absent from a supplied set of Room book ids; do not scan or modify any directory outside app files/cache roots.

- [ ] Verify:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.mkread.app.core.files.*"
```

Expected: all size, containment, atomicity, and source-preservation cases pass.

- [ ] Commit:

```powershell
git add app/src/main/java/com/mkread/app/core/files app/src/test/java/com/mkread/app/core/files
git commit -m "feat: add transactional private book storage"
```

## Task 3: Parse TXT Encodings And Chapters

**Files:**
- Create: `app/src/main/java/com/mkread/app/core/files/BookParser.kt`
- Create: `app/src/main/java/com/mkread/app/core/files/TxtEncodingDetector.kt`
- Create: `app/src/main/java/com/mkread/app/core/files/TxtChapterDetector.kt`
- Create: `app/src/main/java/com/mkread/app/core/files/TxtBookParser.kt`
- Create: `app/src/test/java/com/mkread/app/core/files/TxtEncodingDetectorTest.kt`
- Create: `app/src/test/java/com/mkread/app/core/files/TxtChapterDetectorTest.kt`
- Create: `app/src/test/resources/txt/utf8-bom.txt`
- Create: `app/src/test/resources/txt/utf16le.txt`
- Create: `app/src/test/resources/txt/gb18030.txt`
- Create: `app/src/test/resources/txt/no-headings.txt`

- [ ] Define parser output as `ParsedBook(title, author, language, coverBytes, chapters)` and `ParsedChapter(title, text)`. Parsers receive a local staged `File`, not a `Uri` or Android `Context`.

- [ ] Add encoding tests for UTF-8 BOM, UTF-16LE/BE BOM, valid UTF-8 without BOM, invalid UTF-8 falling back to GB18030, truncated multibyte input rejection, and CRLF/CR normalization to LF.

- [ ] Use `CharsetDecoder` with malformed/unmappable input set to `REPORT`. Detection order is BOM, strict UTF-8, then strict GB18030; never use the platform default charset.

- [ ] Add chapter tests for these anchored headings with optional surrounding whitespace:

```text
第1章 初见
第一百二十章 风雪
第 8 节 重逢
卷二 北境
序章
楔子
后记
尾声
```

- [ ] Use this reviewed heading shape, compiled with Unicode case-insensitive matching: `^\s*(?:(?:第\s*[零〇一二两三四五六七八九十百千万0-9]{1,12}\s*[章节卷回部篇])|(?:[卷部篇]\s*[零〇一二两三四五六七八九十百千万0-9]{1,12})|(?:序章|楔子|后记|尾声))(?:[\s:：._-]+.{0,60})?\s*$`. Keep the 80-character line limit as an independent precondition.

- [ ] Implement heading recognition with a maximum heading length of 80 characters and require either at least two detected headings or one heading within the first 10 percent of content. When confidence is lower, return one synthetic chapter titled `正文`.

- [ ] Normalize by removing NUL, converting all line endings to LF, trimming trailing spaces, limiting consecutive blank lines to two, preserving paragraph boundaries, and rejecting decoded content with more than 1 percent Unicode replacement/control characters.

- [ ] Derive the default title from the first non-empty line only when it is at most 60 characters and is not a chapter heading; otherwise use the source filename without extension.

- [ ] Verify:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.mkread.app.core.files.Txt*"
```

Expected: every fixture decodes deterministically and chapter ranges reconstruct the normalized input without dropped body text.

- [ ] Commit:

```powershell
git add app/src/main/java/com/mkread/app/core/files app/src/test/java/com/mkread/app/core/files app/src/test/resources/txt
git commit -m "feat: parse Chinese TXT books safely"
```

## Task 4: Parse EPUB Containers And Reading Order

**Files:**
- Create: `app/src/main/java/com/mkread/app/core/files/SafeZipReader.kt`
- Create: `app/src/main/java/com/mkread/app/core/files/SecureXml.kt`
- Create: `app/src/main/java/com/mkread/app/core/files/EpubBookParser.kt`
- Create: `app/src/test/java/com/mkread/app/core/files/EpubFixtureFactory.kt`
- Create: `app/src/test/java/com/mkread/app/core/files/SafeZipReaderTest.kt`
- Create: `app/src/test/java/com/mkread/app/core/files/EpubBookParserTest.kt`

- [ ] Generate EPUB fixtures in `EpubFixtureFactory` with `ZipOutputStream`; do not commit opaque binary EPUB fixtures. Include valid EPUB 3, missing optional metadata, malformed optional nav, unreadable spine item plus readable item, no readable spine, traversal entry, absolute entry, duplicate normalized path, 5,001 entries, and expanded-size overflow.

- [ ] Write `SafeZipReaderTest` first. Require rejection reason codes `PATH_TRAVERSAL`, `ABSOLUTE_PATH`, `DUPLICATE_PATH`, `ENTRY_LIMIT`, `EXPANDED_SIZE_LIMIT`, and `ENCRYPTED_OR_UNREADABLE`.

- [ ] Implement ZIP path normalization by replacing backslashes, rejecting drive/UNC/leading slash paths, splitting components, rejecting `..`, ignoring directory entries in byte accounting, and tracking case-sensitive normalized names. Stream every read through a remaining-byte counter rather than trusting ZIP metadata.

- [ ] Configure `DocumentBuilderFactory` namespace-aware with DOCTYPE disallowed, external general/parameter entities disabled, external DTD loading disabled, XInclude disabled, and entity expansion disabled. Parse only bounded byte arrays from `SafeZipReader`.

- [ ] Parse `META-INF/container.xml`, the rootfile OPF, manifest, and spine. Resolve hrefs against the OPF directory through the same safe path resolver. Follow `itemref` order and skip an unreadable item when at least one readable item remains.

- [ ] Parse XHTML with Jsoup XML parser. Remove `script`, `style`, `nav`, `[hidden]`, and elements with `aria-hidden=true`; render headings, paragraphs, list items, `br`, and scene-break `hr` into normalized text. Reject a book only when no non-blank readable chapter remains.

- [ ] Extract Dublin Core title, creator, language, EPUB 3 cover properties or EPUB 2 cover metadata. Bound cover bytes to 10 MB and accept only JPEG, PNG, or WebP magic signatures.

- [ ] Verify:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.mkread.app.core.files.SafeZipReaderTest"
.\gradlew.bat testDebugUnitTest --tests "com.mkread.app.core.files.EpubBookParserTest"
```

Expected: valid reading order is exact, optional corruption degrades gracefully, and every malicious archive is rejected with its specific reason.

- [ ] Commit:

```powershell
git add app/src/main/java/com/mkread/app/core/files app/src/test/java/com/mkread/app/core/files
git commit -m "feat: parse bounded EPUB reading order"
```

## Task 5: Coordinate Atomic Imports

**Files:**
- Create: `app/src/main/java/com/mkread/app/feature/library/ImportBookUseCase.kt`
- Create: `app/src/main/java/com/mkread/app/feature/library/ImportResult.kt`
- Create: `app/src/main/java/com/mkread/app/feature/library/BookRepository.kt`
- Create: `app/src/main/java/com/mkread/app/feature/library/RoomBookRepository.kt`
- Test: `app/src/test/java/com/mkread/app/feature/library/ImportBookUseCaseTest.kt`

- [ ] Define typed outcomes: `Success(bookId)`, `Duplicate(existingBookId)`, and `Failure(code, userMessage)` with codes for size, unsupported type, encoding, malformed EPUB, no readable content, storage full, source unavailable, and internal commit failure.

- [ ] Write use-case tests with fake storage/parser/repository for this exact order: copy source; hash/type validation; parse; write normalized files and metadata; promote directory; Room transaction. Assert a failure before promote removes staging; a Room failure after promote deletes the promoted directory; a duplicate hash skips parsing; and cancellation performs compensating cleanup.

- [ ] Detect file type by content first: EPUB requires ZIP magic plus `mimetype` value `application/epub+zip`; TXT accepts non-ZIP text. Extension/MIME may support an error message but cannot override content validation.

- [ ] Generate book ids as lowercase UUID strings and chapter ids as `<book-id>:<four-digit-ordinal>`. Metadata JSON is kotlinx-serialization schema version 1 and includes the source name, source hash, parser version, title, author, language, and chapter records.

- [ ] Execute filesystem work on `Dispatchers.IO`; wrap Room writes in `withTransaction`; convert `SQLiteConstraintException` for source hash into `Duplicate`; preserve the selected external stream and never call delete or write through its `Uri`.

- [ ] Verify:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.mkread.app.feature.library.ImportBookUseCaseTest"
```

Expected: every failure-injection point leaves either one complete book plus Room rows or no book/rows at all.

- [ ] Commit:

```powershell
git add app/src/main/java/com/mkread/app/feature/library app/src/test/java/com/mkread/app/feature/library
git commit -m "feat: coordinate atomic book imports"
```

## Task 6: Run Imports Reliably From The System Picker

**Files:**
- Create: `app/src/main/java/com/mkread/app/feature/library/ImportBookWorker.kt`
- Create: `app/src/main/java/com/mkread/app/feature/library/BookImportScheduler.kt`
- Modify: `app/src/main/java/com/mkread/app/AppContainer.kt`
- Modify: `app/src/main/java/com/mkread/app/MkreadApplication.kt`
- Test: `app/src/androidTest/java/com/mkread/app/feature/library/ImportBookWorkerTest.kt`

- [ ] Add WorkManager test dependencies and write worker tests that provide a `content://` test provider URI, import a valid TXT, surface a typed permanent parser failure, retry once for a transient source-open failure, and leave no staging data after cancellation.

- [ ] Schedule unique work `import-<sha-of-uri-and-display-name>` with input URI and display name. Take read-only persistable permission immediately after picker result when the provider supports it; still copy the bytes and release the persisted permission after terminal worker completion.

- [ ] Restrict picker MIME choices to `text/plain`, `application/epub+zip`, and `application/octet-stream` using `ActivityResultContracts.OpenDocument`; do not request broad storage permissions.

- [ ] Map `Success` and `Duplicate` to WorkManager success output; map unavailable source/storage I/O to retry with maximum three attempts and linear ten-second backoff; map validation/parser errors to failure output. Show the exact stored user message through observed work info.

- [ ] Construct database, storage, parsers, repository, import use case, WorkManager factory, and scheduler once in `AppContainer`. `MkreadApplication` exposes the configuration; no Compose function constructs them.

- [ ] Verify:

```powershell
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.mkread.app.feature.library.ImportBookWorkerTest
```

Expected: valid work commits once, retry count is bounded, permanent failure does not retry, and URI permissions are released.

- [ ] Commit:

```powershell
git add app/build.gradle.kts app/src/main/java/com/mkread/app app/src/androidTest/java/com/mkread/app/feature/library
git commit -m "feat: import books from Android document picker"
```

## Task 7: Implement Library Repository Operations

**Files:**
- Modify: `app/src/main/java/com/mkread/app/feature/library/BookRepository.kt`
- Modify: `app/src/main/java/com/mkread/app/feature/library/RoomBookRepository.kt`
- Create: `app/src/main/java/com/mkread/app/feature/library/LibraryQuery.kt`
- Test: `app/src/androidTest/java/com/mkread/app/feature/library/RoomBookRepositoryTest.kt`

- [ ] Write repository tests for blank query, case-insensitive title/author search, all three deterministic sorts, rename with whitespace normalization, metadata edit, last-opened update, and confirmed removal.

- [ ] Validate title as trimmed non-blank and at most 200 Unicode code points; author is nullable after trimming and at most 200 code points. Store user edits in Room only; do not rewrite imported source or chapter text.

- [ ] Implement removal as: mark the id in an in-memory deletion guard, delete app-private book files, delete Room rows in a transaction, and surface a recoverable error if file removal fails. If Room deletion fails after files are removed, keep a diagnostic tombstone and hide the broken row until startup reconciliation deletes it.

- [ ] On application startup, run bounded reconciliation: remove staging older than 24 hours, remove book dirs with no Room id, remove/tombstone Room rows whose book directory is absent, and never traverse outside `files/books` and `cache/import`.

- [ ] Verify:

```powershell
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.mkread.app.feature.library.RoomBookRepositoryTest
```

Expected: query/sort metadata are stable and deletion touches only app-private paths.

- [ ] Commit:

```powershell
git add app/src/main/java/com/mkread/app/feature/library app/src/androidTest/java/com/mkread/app/feature/library
git commit -m "feat: add searchable book repository"
```

## Task 8: Build The Native Bookshelf UI

**Files:**
- Create: `app/src/main/java/com/mkread/app/feature/library/LibraryViewModel.kt`
- Create: `app/src/main/java/com/mkread/app/feature/library/LibraryScreen.kt`
- Create: `app/src/main/java/com/mkread/app/feature/library/BookRow.kt`
- Create: `app/src/main/java/com/mkread/app/navigation/MkreadNavHost.kt`
- Modify: `app/src/main/java/com/mkread/app/MainActivity.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Test: `app/src/test/java/com/mkread/app/feature/library/LibraryViewModelTest.kt`
- Test: `app/src/androidTest/java/com/mkread/app/feature/library/LibraryScreenTest.kt`

- [ ] Model UI state as `Loading`, `Empty`, `Content(books,query,sort,importState)`, and `Error(message)`. Keep one-shot picker, snackbar, rename dialog, metadata dialog, and deletion-confirmation events separate from durable state.

- [ ] Write ViewModel tests for debounced 200 ms search, sort persistence during a process-local recreation, import work progress, duplicate notification, rename validation, and deletion requiring an explicit confirm event.

- [ ] Build a compact Material 3 shelf: top app bar titled `MKread`; search and sort icon actions with tooltips; import floating action button using the standard add/import icon; list rows with stable cover aspect ratio, title, author/current chapter, last-opened time, and linear progress. Do not nest cards or show instructional marketing text.

- [ ] Provide row overflow actions `重命名`, `编辑信息`, and `移出书架`. The final action opens a confirmation dialog stating the original file will be retained. Empty state shows `书架中还没有小说` and one `导入小说` command.

- [ ] Route a row tap through a testable `onOpenBook(bookId)` callback. Phase 3 registers the concrete reader destination; Phase 2 must not render a dead control or a visible coming-soon screen. Keep the debug speech spike behind an overflow item available only in debug builds.

- [ ] Write Compose tests for empty state, searching, sorting, successful import result, duplicate result, rename, metadata editing, cancellation of delete, confirmation of delete, and content descriptions for all icon-only controls.

- [ ] Verify:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.mkread.app.feature.library.*"
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.mkread.app.feature.library.LibraryScreenTest
```

Expected: state and UI cases pass without real filesystem or native TTS dependencies.

- [ ] Commit:

```powershell
git add app/src/main/java/com/mkread/app/feature/library app/src/main/java/com/mkread/app/navigation app/src/main/java/com/mkread/app/MainActivity.kt app/src/main/res/values/strings.xml app/src/test app/src/androidTest
git commit -m "feat: build MKread bookshelf experience"
```

## Task 9: Run The Phase 2 Gate

**Files:**
- Create: `app/src/androidTest/assets/import/valid-utf8.txt`
- Create: `app/src/androidTest/java/com/mkread/app/feature/library/ImportJourneyFixtureFactory.kt`
- Create: `app/src/androidTest/java/com/mkread/app/feature/library/LibraryImportJourneyTest.kt`
- Create: `docs/test-evidence/emulator-api35/phase-2.md`

- [ ] Create the human-readable TXT asset and an Android-test fixture factory that writes valid and malformed EPUB ZIPs from reviewed inline XML/text into the test cache directory at runtime; do not hand-edit or commit binary ZIP output.

- [ ] Write the emulator journey: start empty; import TXT through the picker test provider; verify title/chapters; hash the external provider bytes; rename and edit metadata; remove from shelf; verify external hash unchanged; import EPUB; verify spine order; reject malformed EPUB; restart process; verify EPUB remains.

- [ ] Add a 100-row repository seed test that searches and sorts within 500 ms on the emulator. Add a synthetic 20 MB TXT parser benchmark with a 60-second correctness timeout; record timing but do not make host-specific millisecond speed a release assertion.

- [ ] Run:

```powershell
.\gradlew.bat clean testDebugUnitTest lintDebug assembleDebug
.\scripts\start-emulator.ps1
.\gradlew.bat connectedDebugAndroidTest
```

Expected: all Phase 1 and Phase 2 tests pass, no partial books appear, and source hashes are unchanged.

- [ ] Record Git commit, emulator API/ABI, fixture hashes, test counts, 20 MB parse time, 100-book query time, and malformed-input results in `phase-2.md`.

- [ ] Verify hygiene and commit:

```powershell
git diff --check
git status --short
git add app/src/androidTest docs/test-evidence/emulator-api35/phase-2.md
git commit -m "test: record Phase 2 library import gate"
```

Phase 3 begins only when the source-preservation assertions and every malicious EPUB case pass.
