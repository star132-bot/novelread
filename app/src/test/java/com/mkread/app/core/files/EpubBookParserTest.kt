package com.mkread.app.core.files

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EpubBookParserTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var fixtures: EpubFixtureFactory
    private val parser = EpubBookParser()

    @Before
    fun createFixtures() {
        fixtures = EpubFixtureFactory(temporaryFolder.root)
    }

    @Test
    fun validEpub_followsSpine_extractsMetadata_andSanitizesXhtml() {
        val parsed = parser.parse(fixtures.validEpub())

        assertEquals("Wind and Snow", parsed.title)
        assertEquals("MK Author", parsed.author)
        assertEquals("zh-CN", parsed.language)
        assertArrayEquals(EpubFixtureFactory.PNG_BYTES, parsed.coverBytes)
        assertEquals(listOf("Second", "First"), parsed.chapters.map { it.title })
        val second = parsed.chapters.first().text
        assertTrue(second.contains("Second body."))
        assertTrue(second.contains("List item"))
        assertTrue(second.contains("Line\nbreak"))
        assertTrue(second.contains("* * *"))
        assertFalse(second.contains("script must disappear"))
        assertFalse(second.contains("nav must disappear"))
        assertFalse(second.contains("hidden must disappear"))
    }

    @Test
    fun missingOptionalMetadata_usesFilenameAndNulls() {
        val parsed = parser.parse(fixtures.missingOptionalMetadata())

        assertEquals("missing-metadata", parsed.title)
        assertNull(parsed.author)
        assertNull(parsed.language)
        assertNull(parsed.coverBytes)
        assertEquals("Readable body.", parsed.chapters.single().text)
    }

    @Test
    fun malformedOptionalNav_doesNotBlockReadableSpine() {
        val parsed = parser.parse(fixtures.malformedOptionalNav())

        assertEquals("Still Readable", parsed.title)
        assertEquals(listOf("Only"), parsed.chapters.map { it.title })
    }

    @Test
    fun unreadableSpineItem_isSkippedWhenReadableItemRemains() {
        val parsed = parser.parse(fixtures.unreadableThenReadableSpine())

        assertEquals(listOf("Readable"), parsed.chapters.map { it.title })
        assertEquals("Kept body.", parsed.chapters.single().text)
    }

    @Test
    fun noReadableSpine_isRejected() {
        assertParseFailure(BookParseFailure.NO_READABLE_CONTENT) {
            parser.parse(fixtures.noReadableSpine())
        }
    }

    @Test
    fun epub2CoverMetadata_isResolved() {
        val parsed = parser.parse(fixtures.epub2Cover())

        assertArrayEquals(EpubFixtureFactory.PNG_BYTES, parsed.coverBytes)
    }

    @Test
    fun spineHrefCannotTraverseAboveArchiveRoot() {
        assertParseFailure(BookParseFailure.MALFORMED_EPUB) {
            parser.parse(fixtures.unsafeSpineHref())
        }
    }

    @Test
    fun doctypeInContainer_isRejectedWithoutEntityExpansion() {
        assertParseFailure(BookParseFailure.MALFORMED_EPUB) {
            parser.parse(fixtures.doctypeContainer())
        }
    }

    private fun assertParseFailure(
        expected: BookParseFailure,
        block: () -> Unit,
    ) {
        try {
            block()
            fail("Expected EPUB parse failure $expected")
        } catch (failure: BookParseException) {
            assertEquals(expected, failure.failure)
        }
    }
}
