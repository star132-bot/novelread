package com.mkread.app.core.files

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SafeZipReaderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var fixtures: EpubFixtureFactory

    @Before
    fun createFixtures() {
        fixtures = EpubFixtureFactory(temporaryFolder.root)
    }

    @Test
    fun validArchive_normalizesBackslashesAndReadsBytes() {
        val expected = "chapter".toByteArray(Charsets.UTF_8)
        val file = fixtures.zip(
            "valid.zip",
            listOf("OPS\\chapter.xhtml" to expected),
        )

        SafeZipReader().open(file).use { archive ->
            assertTrue(archive.contains("OPS/chapter.xhtml"))
            assertArrayEquals(expected, archive.read("OPS/chapter.xhtml"))
        }
    }

    @Test
    fun traversalEntry_isRejected() {
        assertZipFailure(SafeZipFailure.PATH_TRAVERSAL) {
            SafeZipReader().open(fixtures.zip("traversal.zip", listOf("../escape" to byteArrayOf())))
        }
    }

    @Test
    fun absoluteAndDriveEntries_areRejected() {
        listOf("/escape", "C:\\escape", "\\\\server\\share").forEachIndexed { index, path ->
            assertZipFailure(SafeZipFailure.ABSOLUTE_PATH) {
                SafeZipReader().open(
                    fixtures.zip("absolute-$index.zip", listOf(path to byteArrayOf())),
                )
            }
        }
    }

    @Test
    fun absoluteHref_isRejectedBeforeResolvingAgainstPackageDirectory() {
        listOf("/outside.xhtml", "C:\\outside.xhtml", "\\\\server\\outside.xhtml").forEach { href ->
            assertZipFailure(SafeZipFailure.ABSOLUTE_PATH) {
                SafeZipPath.resolve("OPS", href)
            }
        }
    }

    @Test
    fun duplicateNormalizedPath_isRejectedCaseSensitively() {
        val file = fixtures.zip(
            "duplicate.zip",
            listOf(
                "OPS\\chapter.xhtml" to byteArrayOf(1),
                "OPS/chapter.xhtml" to byteArrayOf(2),
            ),
        )

        assertZipFailure(SafeZipFailure.DUPLICATE_PATH) {
            SafeZipReader().open(file)
        }

        val caseDistinct = fixtures.zip(
            "case-distinct.zip",
            listOf("A.txt" to byteArrayOf(1), "a.txt" to byteArrayOf(2)),
        )
        SafeZipReader().open(caseDistinct).use { archive ->
            assertEquals(setOf("A.txt", "a.txt"), archive.entries)
        }
    }

    @Test
    fun fiveThousandAndOneEntries_areRejected() {
        val file = fixtures.zipWithEntryCount("too-many.zip", ImportLimits.ZIP_ENTRIES + 1)

        assertZipFailure(SafeZipFailure.ENTRY_LIMIT) {
            SafeZipReader().open(file)
        }
    }

    @Test
    fun actualExpandedBytes_areCountedInsteadOfMetadata() {
        val file = fixtures.zip("expanded.zip", listOf("large.txt" to ByteArray(17)))

        assertZipFailure(SafeZipFailure.EXPANDED_SIZE_LIMIT) {
            SafeZipReader(expandedByteLimit = 16).open(file)
        }
    }

    @Test
    fun unreadableArchive_hasStableReasonCode() {
        val file = File(temporaryFolder.root, "not-a-zip.epub").apply {
            writeText("not a zip")
        }

        assertZipFailure(SafeZipFailure.ENCRYPTED_OR_UNREADABLE) {
            SafeZipReader().open(file)
        }
    }

    private fun assertZipFailure(
        expected: SafeZipFailure,
        block: () -> Unit,
    ) {
        try {
            block()
            fail("Expected ZIP failure $expected")
        } catch (failure: SafeZipException) {
            assertEquals(expected, failure.failure)
        }
    }
}
