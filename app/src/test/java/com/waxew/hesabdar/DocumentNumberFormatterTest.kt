package com.waxew.hesabdar

import com.waxew.hesabdar.data.DocumentNumberFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** تست فرمت شماره اسناد؛ تغییر ناخواسته Prefixها می‌تواند ارجاع کاربران به فاکتورها را خراب کند. */
class DocumentNumberFormatterTest {

    @Test
    fun `sale and purchase prefixes are stable`() {
        assertEquals("S-000001", DocumentNumberFormatter.format("SALE", 1))
        assertEquals("P-000042", DocumentNumberFormatter.format("PURCHASE", 42))
    }

    @Test
    fun `return documents use distinct prefixes`() {
        assertEquals("SR-000007", DocumentNumberFormatter.format("SALE_RETURN", 7))
        assertEquals("PR-000007", DocumentNumberFormatter.format("PURCHASE_RETURN", 7))
    }

    @Test
    fun `sequence must be positive`() {
        assertThrows(IllegalArgumentException::class.java) {
            DocumentNumberFormatter.format("SALE", 0)
        }
    }
}
