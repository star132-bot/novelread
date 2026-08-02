# MKread Phase 3 Reader Pagination And Editing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver a focused paginated reader with stable semantic positions, Android text selection/copy, read-from-selection, chapter editing with one-level undo, and precise invalidation of derived data.

**Architecture:** Represent chapter text by immutable character offsets and keep page/sentence ranges as derived indexes. Paginate through an Android text-layout adapter on a background dispatcher, persist semantic positions in Room, wrap a selectable native TextView for reliable Android selection actions, and update chapter files transactionally through an editor repository.

**Tech Stack:** Kotlin coroutines/Flow, Jetpack Compose Foundation pager, Android `StaticLayout` and selectable `TextView`, Room migration testing, kotlinx.serialization for offset caches, JUnit 4, AndroidX Compose tests.

---

## Task 1: Persist Semantic Reading Positions

**Files:**
- Create: `app/src/main/java/com/mkread/app/core/database/ReadingPositionEntity.kt`
- Modify: `app/src/main/java/com/mkread/app/core/database/MkreadDatabase.kt`
- Create: `app/src/main/java/com/mkread/app/feature/reader/ReadingPositionRepository.kt`
- Create: `app/src/main/java/com/mkread/app/feature/reader/RoomReadingPositionRepository.kt`
- Create: `app/schemas/com.mkread.app.core.database.MkreadDatabase/2.json`
- Test: `app/src/androidTest/java/com/mkread/app/core/database/Migration1To2Test.kt`
- Test: `app/src/androidTest/java/com/mkread/app/feature/reader/ReadingPositionRepositoryTest.kt`

- [ ] Define one position per book:

```kotlin
@Entity(
    tableName = "reading_positions",
    foreignKeys = [ForeignKey(
        entity = BookEntity::class,
        parentColumns = ["id"],
        childColumns = ["bookId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("chapterId")],
)
data class ReadingPositionEntity(
    @PrimaryKey val bookId: String,
    val chapterId: String,
    val characterOffset: Int,
    val pageIndex: Int,
    val sentenceIndex: Int,
    val updatedAt: Long,
)
```

- [ ] Write migration test first: create schema 1, insert a book/chapter, migrate to 2, assert the original rows remain and the new table accepts a constrained position. Also assert deleting the book cascades the position.

- [ ] Add `MIGRATION_1_2` with explicit SQL and export schema 2. Never use destructive migration.

- [ ] Repository rules: clamp character offset to `[0, chapterLength]`, page/sentence indexes to non-negative values, update at most once per second while stationary, force-write at sentence changes, activity stop, chapter change, and process checkpoint requests.

- [ ] Write repository tests with a fake clock for coalescing and forced checkpoints.

- [ ] Verify:

```powershell
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.mkread.app.core.database.Migration1To2Test
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.mkread.app.feature.reader.ReadingPositionRepositoryTest
```

Expected: migration preserves Phase 2 data and checkpoint coalescing never loses a forced position.

- [ ] Commit:

```powershell
git add app/src/main/java/com/mkread/app/core/database app/src/main/java/com/mkread/app/feature/reader app/src/androidTest app/schemas
git commit -m "feat: persist semantic reading positions"
```

## Task 2: Load Chapter Text And Build Sentence Boundaries

**Files:**
- Create: `app/src/main/java/com/mkread/app/feature/reader/ChapterContentRepository.kt`
- Create: `app/src/main/java/com/mkread/app/feature/reader/FileChapterContentRepository.kt`
- Create: `app/src/main/java/com/mkread/app/feature/reader/SentenceRange.kt`
- Create: `app/src/main/java/com/mkread/app/feature/reader/SentenceSegmenter.kt`
- Test: `app/src/test/java/com/mkread/app/feature/reader/SentenceSegmenterTest.kt`
- Test: `app/src/test/java/com/mkread/app/feature/reader/FileChapterContentRepositoryTest.kt`

- [ ] Define `SentenceRange(index,startInclusive,endExclusive)` with non-empty half-open offsets and `contains(offset)`. The repository returns `ChapterContent(chapter, text, contentSha256)` and validates that the stored relative path remains inside the book directory.

- [ ] Write segmentation tests for Chinese `。！？……`, English `.?!`, quoted dialogue, paired closing quotes/brackets after punctuation, paragraph breaks, decimal `3.14`, abbreviation `Mr.`, ellipsis, whitespace-only input, surrogate pairs, and a 20,000-character paragraph without punctuation.

- [ ] Implement deterministic segmentation: include trailing closing quotes/brackets and horizontal whitespace in the sentence; paragraph break ends a sentence; do not split a decimal or the tested abbreviation list; force a boundary at the nearest whitespace before 500 code points, or at a Unicode code-point boundary when no whitespace exists.

- [ ] Add `sentenceAt(offset)` and `nearestBoundary(offset)` binary searches. `nearestBoundary` selects the sentence containing the selection start, or the next sentence when selection starts in inter-sentence whitespace.

- [ ] Load chapter text as UTF-8 with a maximum of 5,000,000 characters and verify its SHA-256 against Room. Return a typed corruption error instead of presenting mismatched data.

- [ ] Verify:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.mkread.app.feature.reader.SentenceSegmenterTest"
.\gradlew.bat testDebugUnitTest --tests "com.mkread.app.feature.reader.FileChapterContentRepositoryTest"
```

Expected: concatenated sentence/interstitial ranges cover the chapter without overlap, and all boundaries are valid UTF-16 code-point boundaries.

- [ ] Commit:

```powershell
git add app/src/main/java/com/mkread/app/feature/reader app/src/test/java/com/mkread/app/feature/reader
git commit -m "feat: map chapter sentence boundaries"
```

## Task 3: Define Pagination Keys And Offset Cache

**Files:**
- Create: `app/src/main/java/com/mkread/app/feature/reader/PaginationModels.kt`
- Create: `app/src/main/java/com/mkread/app/feature/reader/PaginationCache.kt`
- Create: `app/src/main/java/com/mkread/app/feature/reader/FilePaginationCache.kt`
- Test: `app/src/test/java/com/mkread/app/feature/reader/PaginationCacheTest.kt`

- [ ] Define immutable models:

```kotlin
data class PaginationSpec(
    val widthPx: Int,
    val heightPx: Int,
    val densityDpi: Int,
    val fontFamilyId: String,
    val fontSizeSp: Float,
    val lineSpacingMultiplier: Float,
    val horizontalMarginPx: Int,
)

data class PageRange(val index: Int, val start: Int, val endExclusive: Int)

data class PaginationKey(
    val chapterId: String,
    val contentSha256: String,
    val spec: PaginationSpec,
    val algorithmVersion: Int = 1,
)
```

- [ ] Write tests that every key field changes the SHA-256 cache filename, identical keys are stable across process runs, playback settings do not affect the key, and corrupt/overlapping/out-of-bounds cached ranges are discarded.

- [ ] Store compact JSON under `cache/pagination/<first-two-key-chars>/<key>.json` with schema version, text length, and ordered ranges. Write to `.partial`, flush, rename, and cap total pagination cache at 100 MB with LRU-by-last-modified eviction.

- [ ] Validate cached ranges: first starts at zero, each end is greater than start, adjacent ranges touch, final end equals chapter length, and no boundary splits a surrogate pair.

- [ ] Provide `invalidateChapter(chapterId)` by maintaining a small cache index file rather than parsing every cache body. Rebuild the index from filenames/headers if it is corrupt.

- [ ] Verify:

```powershell
.\gradlew.bat testDebugUnitTest --tests com.mkread.app.feature.reader.PaginationCacheTest
```

Expected: cache keys are stable and malformed cache input always becomes a miss.

- [ ] Commit:

```powershell
git add app/src/main/java/com/mkread/app/feature/reader app/src/test/java/com/mkread/app/feature/reader
git commit -m "feat: cache pagination offset maps"
```

## Task 4: Paginate With Android Font Metrics Off The Main Thread

**Files:**
- Create: `app/src/main/java/com/mkread/app/feature/reader/PaginationEngine.kt`
- Create: `app/src/main/java/com/mkread/app/feature/reader/AndroidPaginationEngine.kt`
- Create: `app/src/main/java/com/mkread/app/feature/reader/PageBoundary.kt`
- Test: `app/src/test/java/com/mkread/app/feature/reader/PageBoundaryTest.kt`
- Test: `app/src/androidTest/java/com/mkread/app/feature/reader/AndroidPaginationEngineTest.kt`

- [ ] Define the engine as `fun paginate(text, spec): Flow<PaginationBatch>`, where each batch contains all ranges known so far plus `complete`; emit after the first page and at least every eight pages.

- [ ] Unit-test `PageBoundary.safeEnd(text, proposedEnd)` for CRLF, high/low surrogate pairs, combining marks, and zero progress. It must return a boundary greater than the page start unless the text is exhausted.

- [ ] Instrumented pagination tests use a fixed bundled font and density, not the emulator default font. Cover empty chapter, one short paragraph, Chinese dialogue, long English words, emoji, 100 pages, width/height changes, font-size changes, and cancellation after the first batch.

- [ ] Implement on a dedicated limited-parallelism dispatcher. For each page, build `StaticLayout` over a 16,384-character lookahead; double up to 65,536 only when all lookahead lines fit. Select the final line whose bottom is within available height and use its line end; fall back to the first code point only when a single glyph is taller/wider than the viewport.

- [ ] Reject non-positive content width/height. Subtract horizontal margins before layout, apply font size via scaled density, include font padding consistently, and set line-spacing multiplier from the spec.

- [ ] On cache hit emit one complete batch. On cache miss emit progressive batches, stop promptly on coroutine cancellation, and write the cache only after the final complete range list validates.

- [ ] Verify:

```powershell
.\gradlew.bat testDebugUnitTest --tests com.mkread.app.feature.reader.PageBoundaryTest
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.mkread.app.feature.reader.AndroidPaginationEngineTest
```

Expected: every non-empty character appears in exactly one page, first batch arrives before full pagination, and no test blocks the UI thread.

- [ ] Commit:

```powershell
git add app/src/main/java/com/mkread/app/feature/reader app/src/test/java/com/mkread/app/feature/reader app/src/androidTest/java/com/mkread/app/feature/reader
git commit -m "feat: paginate chapters with stable offsets"
```

## Task 5: Save Chapter Edits Transactionally And Undo Once

**Files:**
- Create: `app/src/main/java/com/mkread/app/feature/reader/ChapterEditor.kt`
- Create: `app/src/main/java/com/mkread/app/feature/reader/FileChapterEditor.kt`
- Create: `app/src/main/java/com/mkread/app/feature/reader/DerivedDataInvalidator.kt`
- Create: `app/src/main/java/com/mkread/app/feature/reader/PositionRemapper.kt`
- Test: `app/src/test/java/com/mkread/app/feature/reader/PositionRemapperTest.kt`
- Test: `app/src/androidTest/java/com/mkread/app/feature/reader/FileChapterEditorTest.kt`

- [ ] Test position remapping with unchanged prefix, insertion before position, deletion across position, replacement around position, unchanged suffix, empty new text, and emoji. Use longest common prefix/suffix at code-point-safe boundaries; positions inside the replaced middle map to the end of the surviving prefix.

- [ ] Define `DerivedDataInvalidator.invalidateChapter(chapterId, oldHash, newHash)`; Phase 3 implementation deletes page/sentence indexes, while Phase 4 adds audio-cache invalidation through the same composite.

- [ ] Write editor failure-injection tests for failure writing new text, failure renaming original to undo, failure promoting new text, failure updating Room, and invalidator failure. File/Room failures restore the prior saved chapter; invalidator failure commits content but records a startup cleanup marker because derived data is disposable.

- [ ] Normalize edited line endings and reject blank chapters or text over 5,000,000 characters. Save through `chapter.txt.new`, fsync, move current to `.undo/<chapter-id>.txt`, move new to current, then update chapter hash/count/modified time in Room. Retain only one prior snapshot.

- [ ] `undo(chapterId)` swaps current and snapshot through the same transactional path, updates Room, remaps position, and invalidates derived data. The original imported file under `original/` is never opened for write.

- [ ] Verify:

```powershell
.\gradlew.bat testDebugUnitTest --tests com.mkread.app.feature.reader.PositionRemapperTest
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.mkread.app.feature.reader.FileChapterEditorTest
```

Expected: every injected failure leaves a readable current chapter and matching Room hash; source hash remains identical.

- [ ] Commit:

```powershell
git add app/src/main/java/com/mkread/app/feature/reader app/src/test/java/com/mkread/app/feature/reader app/src/androidTest/java/com/mkread/app/feature/reader
git commit -m "feat: edit and undo private chapters safely"
```

## Task 6: Build Reader State And Navigation

**Files:**
- Create: `app/src/main/java/com/mkread/app/feature/reader/ReaderUiState.kt`
- Create: `app/src/main/java/com/mkread/app/feature/reader/ReaderViewModel.kt`
- Create: `app/src/main/java/com/mkread/app/feature/reader/ReaderActions.kt`
- Modify: `app/src/main/java/com/mkread/app/AppContainer.kt`
- Modify: `app/src/main/java/com/mkread/app/navigation/MkreadNavHost.kt`
- Test: `app/src/test/java/com/mkread/app/feature/reader/ReaderViewModelTest.kt`

- [ ] Model states `Loading`, `Paginating(firstPages)`, `Ready`, and `Error(retryable,message)`. `Ready` contains book/chapter metadata, page ranges, current page, selected range, active sentence range, pagination completion, and whether undo is available.

- [ ] Write ViewModel tests for initial saved-position load, missing position starting at chapter 0/offset 0, first pagination batch display, page turn checkpoint, next/previous chapter boundary, layout change mapping the semantic offset to a new page, selection-to-sentence mapping, edit save remap, undo, and process-style reconstruction from repositories.

- [ ] Use `SavedStateHandle` only for `bookId`; repositories are authoritative for position/content. Cancel old pagination when chapter/spec changes and ignore late batches by request id.

- [ ] Preserve the current page's start semantic offset when typography/viewport changes, then locate the new page satisfying `start <= offset < end`. At chapter end, map offset equal to text length to the final page.

- [ ] Expose commands for page, chapter, selection, read-from-here, open editor, save, undo, retry, and checkpoint. Do not expose mutable repository or Android `Context` to Compose.

- [ ] Register the concrete `reader/{bookId}` destination and connect Phase 2's `onOpenBook(bookId)` callback to a ViewModel factory using `AppContainer` and `bookId`.

- [ ] Verify:

```powershell
.\gradlew.bat testDebugUnitTest --tests com.mkread.app.feature.reader.ReaderViewModelTest
```

Expected: virtual-time tests pass without Android text layout or filesystem access.

- [ ] Commit:

```powershell
git add app/src/main/java/com/mkread/app/feature/reader app/src/main/java/com/mkread/app/AppContainer.kt app/src/main/java/com/mkread/app/navigation app/src/test/java/com/mkread/app/feature/reader
git commit -m "feat: coordinate paginated reader state"
```

## Task 7: Render Pages With Selection And Spoken Highlight

**Files:**
- Create: `app/src/main/java/com/mkread/app/feature/reader/ReaderScreen.kt`
- Create: `app/src/main/java/com/mkread/app/feature/reader/SelectablePageText.kt`
- Create: `app/src/main/java/com/mkread/app/feature/reader/ReaderBottomBar.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Test: `app/src/androidTest/java/com/mkread/app/feature/reader/SelectablePageTextTest.kt`
- Test: `app/src/androidTest/java/com/mkread/app/feature/reader/ReaderScreenTest.kt`

- [ ] Wrap an AppCompat/native `TextView` in `AndroidView`, set `textIsSelectable=true`, disable vertical scrolling, apply the same font/margins/line spacing as pagination, and render the page substring as a `SpannableString`. Translate chapter-level active/selected ranges to page-local offsets and use a theme-aware background span for the spoken sentence.

- [ ] Override selection `ActionMode.Callback2` to retain Android `复制`, add `编辑章节` and `从这里朗读`, and report the normalized chapter-level selection. Copy plain text through `ClipboardManager` and show a short `已复制` confirmation without logging text.

- [ ] Write selection tests for a single word, cross-line Chinese range, selection touching page start/end, copy clipboard value, read-from-here sentence snap, and active highlight update without changing the TextView's measured dimensions.

- [ ] Build a top bar with back, chapter title, and overflow; a full-height `HorizontalPager`; tap left/right zones plus horizontal swipe; and a fixed bottom bar with page/chapter navigation. Phase 4 adds the previous/play/next/replay/speed controls in that reserved bottom-bar slot after Media3 commands exist; do not render dead speech buttons in Phase 3.

- [ ] Show page count without changing layout width, a preparing indicator only while the first batch is absent, and automatic navigation to a newly available page when the ViewModel requests it. Ensure no text or controls overlap at 200 percent font scale.

- [ ] Verify:

```powershell
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.mkread.app.feature.reader.SelectablePageTextTest
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.mkread.app.feature.reader.ReaderScreenTest
```

Expected: copy/read actions return exact chapter offsets and page controls keep stable dimensions.

- [ ] Commit:

```powershell
git add app/src/main/java/com/mkread/app/feature/reader app/src/main/res/values/strings.xml app/src/androidTest/java/com/mkread/app/feature/reader
git commit -m "feat: render selectable highlighted pages"
```

## Task 8: Add Chapter Navigation And Editor UI

**Files:**
- Create: `app/src/main/java/com/mkread/app/feature/reader/ChapterListSheet.kt`
- Create: `app/src/main/java/com/mkread/app/feature/reader/ChapterEditorScreen.kt`
- Modify: `app/src/main/java/com/mkread/app/navigation/MkreadNavHost.kt`
- Test: `app/src/androidTest/java/com/mkread/app/feature/reader/ChapterEditorJourneyTest.kt`

- [ ] Add chapter-list bottom sheet with current chapter marked, stable ordered rows, character counts, and direct navigation. It is an unframed sheet list, not cards nested in a card.

- [ ] Build the editor as one chapter-level `BasicTextField` with top actions close, undo, and save. Track dirty state; back/close with changes opens `放弃未保存的修改？`; save validates before showing progress; controls are disabled during the transaction.

- [ ] Write the journey: open chapter 2, select `编辑章节`, insert Chinese and English text, save, verify reader/pagination content, restart activity, verify persisted content/position, undo, verify original normalized chapter returns, and compare the app-private original source SHA-256 before/after.

- [ ] Add failure UI tests for blank chapter, over-limit text, storage failure, and Room failure. Show specific actionable messages and retain unsaved editor text after a failed save.

- [ ] Verify:

```powershell
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.mkread.app.feature.reader.ChapterEditorJourneyTest
```

Expected: save/undo survive recreation, derived page cache changes only for the edited chapter, and the imported original is unchanged.

- [ ] Commit:

```powershell
git add app/src/main/java/com/mkread/app/feature/reader app/src/main/java/com/mkread/app/navigation app/src/androidTest/java/com/mkread/app/feature/reader
git commit -m "feat: navigate and edit novel chapters"
```

## Task 9: Run The Phase 3 Gate

**Files:**
- Create: `app/src/androidTest/java/com/mkread/app/feature/reader/ReaderJourneyTest.kt`
- Create: `docs/test-evidence/emulator-api35/phase-3.md`

- [ ] Write one emulator journey that imports a multi-chapter TXT, opens the saved offset, turns pages, selects/copies, reads from selection, changes viewport orientation, changes test typography, edits/saves/undoes, navigates chapters, kills the process, and restores the same semantic sentence.

- [ ] Add a long-chapter test that asserts first-page availability before full pagination and complete offset coverage. Record first-page and full-pagination timing; assert only correctness and a 60-second deadlock timeout.

- [ ] Run all gates:

```powershell
.\gradlew.bat clean testDebugUnitTest lintDebug assembleDebug offlineContract
.\scripts\start-emulator.ps1
.\gradlew.bat connectedDebugAndroidTest
```

Expected: Phases 1-3 pass; no source hash changes; no main-thread disk or pagination StrictMode violation appears in test logs.

- [ ] Record Git commit, screen metrics, font settings, pagination timings/page counts, selection offsets, edit invalidation evidence, source hashes, and restoration result in `phase-3.md`.

- [ ] Inspect and commit:

```powershell
git diff --check
git status --short
git add app/src/androidTest/java/com/mkread/app/feature/reader/ReaderJourneyTest.kt docs/test-evidence/emulator-api35/phase-3.md
git commit -m "test: record Phase 3 reader gate"
```

Phase 4 begins only when restoration is based on the character offset rather than the previous page number and the original source hash remains unchanged.
