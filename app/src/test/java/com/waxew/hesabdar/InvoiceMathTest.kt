package com.waxew.hesabdar

import com.waxew.hesabdar.data.InvoiceMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** تست‌های عددی برای جلوگیری از خطای محاسبات مبلغ نهایی فاکتور. */
class InvoiceMathTest {

    @Test
    fun `discount tax and shipping are applied in defined order`() {
        val result = InvoiceMath.calculate(
            subtotal = 1_000_000,
            discountAmount = 100_000,
            taxPercent = 10,
            shippingAmount = 50_000
        )

        assertEquals(1_000_000, result.subtotal)
        assertEquals(100_000, result.discountAmount)
        assertEquals(90_000, result.taxAmount)
        assertEquals(50_000, result.shippingAmount)
        assertEquals(1_040_000, result.grandTotal)
    }

    @Test
    fun `zero adjustments preserve subtotal`() {
        val result = InvoiceMath.calculate(subtotal = 12_345_678)
        assertEquals(12_345_678, result.grandTotal)
    }

    @Test
    fun `discount cannot exceed subtotal`() {
        assertThrows(IllegalArgumentException::class.java) {
            InvoiceMath.calculate(subtotal = 100, discountAmount = 101)
        }
    }

    @Test
    fun `tax percent must stay in valid range`() {
        assertThrows(IllegalArgumentException::class.java) {
            InvoiceMath.calculate(subtotal = 100, taxPercent = 101)
        }
    }
}
