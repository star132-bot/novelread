package com.mkread.app.feature.reader

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PaginationCacheTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun everyPaginationKeyFieldChangesStableSha256Filename() {
        val base = key()
        val variants = listOf(
            base.copy(chapterId = "chapter-b"),
            base.copy(contentSha256 = "content-b"),
            base.copy(spec = base.spec.copy(widthPx = 801)),
            base.copy(spec = base.spec.copy(heightPx = 1201)),
            base.copy(spec = base.spec.copy(densityDpi = 421)),
            base.copy(spec = base.spec.copy(fontScale = 1.25f)),
            base.copy(spec = base.spec.copy(fontFamilyId = "serif")),
            base.copy(spec = base.spec.copy(fontSizeSp = 19f)),
            base.copy(spec = base.spec.copy(lineSpacingMultiplier = 1.6f)),
            base.copy(spec = base.spec.copy(horizontalMarginPx = 49)),
            base.copy(algorithmVersion = 2),
        )

        assertTrue(base.cacheId().matches(Regex("[0-9a-f]{64}")))
        assertEquals(base.cacheId(), key().cacheId())
        assertEquals(variants.size, variants.map(PaginationKey::cacheId).toSet().size)
        assertFalse(variants.any { it.cacheId() == base.cacheId() })

        val cacheIdsAtPlaybackSpeeds = listOf(0.5f, 1.0f, 1.8f, 2.0f).map { _ -> base.cacheId() }
        assertEquals(listOf(base.cacheId()), cacheIdsAtPlaybackSpeeds.distinct())
    }

    @Test
    fun validRangesRoundTripThroughAtomicPrefixPath() = runBlocking {
        val cacheDir = temporaryFolder.newFolder("round-trip")
        val cache = cache(cacheDir)
        val key = key()
        val text = "A😀B"
        val ranges = listOf(
            PageRange(0, 0, 1),
            PageRange(1, 1, 3),
            PageRange(2, 3, 4),
        )

        assertTrue(cache.store(key, text, ranges))
        assertEquals(ranges, cache.load(key, text))

        val body = cacheFile(cacheDir, key)
        val parent = requireNotNull(body.parentFile)
        assertTrue(body.isFile)
        assertEquals(key.cacheId().take(2), parent.name)
        assertFalse(File(parent, "${body.name}.partial").exists())
    }

    @Test
    fun corruptOverlappingOutOfBoundsAndSurrogateSplittingBodiesBecomeMisses() = runBlocking {
        val cacheDir = temporaryFolder.newFolder("corrupt")
        val cache = cache(cacheDir)
        val key = key()
        val text = "A😀B"
        val body = cacheFile(cacheDir, key)
        val invalidBodies = listOf(
            "not json",
            bodyJson(key, text.length, """[{"index":0,"start":0,"endExclusive":2},{"index":1,"start":1,"endExclusive":4}]"""),
            bodyJson(key, text.length, """[{"index":0,"start":0,"endExclusive":5}]"""),
            bodyJson(key, text.length, """[{"index":0,"start":0,"endExclusive":2},{"index":1,"start":2,"endExclusive":4}]"""),
        )

        invalidBodies.forEach { invalid ->
            requireNotNull(body.parentFile).mkdirs()
            body.writeText(invalid, Charsets.UTF_8)
            assertNull(cache.load(key, text))
            assertFalse(body.exists())
        }
    }

    @Test
    fun invalidationRebuildsCorruptIndexAndOnlyDeletesSelectedChapter() = runBlocking {
        val cacheDir = temporaryFolder.newFolder("invalidate")
        val cache = cache(cacheDir)
        val first = key().copy(chapterId = "chapter-1")
        val second = key().copy(chapterId = "chapter-2")
        val text = "Page"
        val ranges = listOf(PageRange(0, 0, text.length))
        assertTrue(cache.store(first, text, ranges))
        assertTrue(cache.store(second, text, ranges))
        File(cacheDir, "pagination/index.json").writeText("broken", Charsets.UTF_8)

        cache.invalidateChapter("chapter-1")

        assertNull(cache.load(first, text))
        assertEquals(ranges, cache.load(second, text))
    }

    @Test
    fun leastRecentlyUsedBodyIsEvictedAtConfiguredLimit() = runBlocking {
        val sampleRoot = temporaryFolder.newFolder("sample")
        val sample = cache(sampleRoot)
        val text = "Same page body"
        val ranges = listOf(PageRange(0, 0, text.length))
        val first = key().copy(chapterId = "chapter-1")
        assertTrue(sample.store(first, text, ranges))
        val bodyBytes = cacheFile(sampleRoot, first).length()

        var now = 1_000L
        val lruRoot = temporaryFolder.newFolder("lru")
        val cache = cache(
            cacheDir = lruRoot,
            maxCacheBytes = bodyBytes * 2L + 16L,
            clock = { now },
        )
        val second = key().copy(chapterId = "chapter-2")
        val third = key().copy(chapterId = "chapter-3")
        assertTrue(cache.store(first, text, ranges))
        now += 1_000L
        assertTrue(cache.store(second, text, ranges))
        now += 1_000L
        assertEquals(ranges, cache.load(first, text))
        now += 1_000L
        assertTrue(cache.store(third, text, ranges))

        assertEquals(ranges, cache.load(first, text))
        assertNull(cache.load(second, text))
        assertEquals(ranges, cache.load(third, text))
    }

    private fun cache(
        cacheDir: File,
        maxCacheBytes: Long = 100L * 1024L * 1024L,
        clock: () -> Long = { 1_000L },
    ) = FilePaginationCache(
        cacheDir = cacheDir,
        ioDispatcher = Dispatchers.Unconfined,
        maxCacheBytes = maxCacheBytes,
        clock = clock,
    )

    private fun cacheFile(cacheDir: File, key: PaginationKey): File = File(
        cacheDir,
        "pagination/${key.cacheId().take(2)}/${key.cacheId()}.json",
    )

    private fun bodyJson(key: PaginationKey, textLength: Int, ranges: String): String =
        """{"schemaVersion":1,"keyHash":"${key.cacheId()}","chapterId":"${key.chapterId}","textLength":$textLength,"ranges":$ranges}"""

    private fun key() = PaginationKey(
        chapterId = "chapter-a",
        contentSha256 = "content-a",
        spec = PaginationSpec(
            widthPx = 800,
            heightPx = 1_200,
            densityDpi = 420,
            fontFamilyId = "source-serif",
            fontSizeSp = 18f,
            lineSpacingMultiplier = 1.5f,
            horizontalMarginPx = 48,
        ),
    )
}
