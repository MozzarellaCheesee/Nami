package dev.nami.data.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FtsQueryBuilderTest {
    @Test
    fun `single token gets prefix wildcard`() {
        assertEquals("\"window\"*", FtsQueryBuilder.build("window"))
    }

    @Test
    fun `multiple tokens each get quoted and wildcarded`() {
        assertEquals("\"window\"* \"view\"*", FtsQueryBuilder.build("window view"))
    }

    @Test
    fun `blank text returns null`() {
        assertNull(FtsQueryBuilder.build("   "))
    }

    @Test
    fun `embedded quotes are escaped`() {
        assertEquals("\"say \"\"hi\"\"\"*", FtsQueryBuilder.build("say \"hi\""))
    }
}
