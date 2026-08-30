package com.waxew.hesabdar

import com.waxew.hesabdar.data.InstallmentMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** تست تقسیم اقساط برای جلوگیری از ایجاد قسط صفر و خطای جمع. */
class InstallmentMathTest {

    @Test
    fun split_keepsExactTotalAndPositiveParts() {
        val parts = InstallmentMath.split(totalAmount = 1_000_001L, count = 3)

        assertEquals(3, parts.size)
        assertEquals(1_000_001L, parts.sum())
        assertTrue(parts.all { it > 0 })
    }

    @Test(expected = IllegalArgumentException::class)
    fun split_rejectsMoreInstallmentsThanAmountUnits() {
        InstallmentMath.split(totalAmount = 1L, count = 2)
    }

    @Test(expected = IllegalArgumentException::class)
    fun split_rejectsZeroCount() {
        InstallmentMath.split(totalAmount = 100L, count = 0)
    }
}
