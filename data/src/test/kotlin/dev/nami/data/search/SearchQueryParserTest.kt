package dev.nami.data.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SearchQueryParserTest {
    @Test
    fun `plain text has no operators`() {
        val result = SearchQueryParser.parse("window view")
        assertEquals("window view", result.text)
        assertNull(result.format)
        assertNull(result.year)
    }

    @Test
    fun `extracts format operator and strips it from text`() {
        val result = SearchQueryParser.parse("window format:flac")
        assertEquals("window", result.text)
        assertEquals("flac", result.format)
    }

    @Test
    fun `extracts year operator and strips it from text`() {
        val result = SearchQueryParser.parse("year:2023 window")
        assertEquals("window", result.text)
        assertEquals(2023, result.year)
    }

    @Test
    fun `format is lowercased`() {
        val result = SearchQueryParser.parse("format:FLAC")
        assertEquals("flac", result.format)
    }

    @Test
    fun `operators only leaves empty text`() {
        val result = SearchQueryParser.parse("format:flac year:2023")
        assertEquals("", result.text)
        assertEquals("flac", result.format)
        assertEquals(2023, result.year)
    }
}
