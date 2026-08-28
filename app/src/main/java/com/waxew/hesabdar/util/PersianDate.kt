package com.waxew.hesabdar.util

import java.util.Calendar

/** تاریخ شمسی سبک و بدون وابستگی خارجی؛ زمان در دیتابیس همچنان Epoch ذخیره می‌شود. */
data class PersianDate(val year: Int, val month: Int, val day: Int) {
    override fun toString(): String = "%04d/%02d/%02d".format(year, month, day)
}

object PersianDateConverter {
    fun now(): PersianDate {
        val c = Calendar.getInstance()
        return fromGregorian(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    fun fromMillis(millis: Long): PersianDate {
        val c = Calendar.getInstance().apply { timeInMillis = millis }
        return fromGregorian(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    /** الگوریتم تبدیل میلادی به جلالی با محاسبه تعداد روز سپری‌شده. */
    fun fromGregorian(gyInput: Int, gmInput: Int, gdInput: Int): PersianDate {
        val gDaysInMonth = intArrayOf(31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        val jDaysInMonth = intArrayOf(31, 31, 31, 31, 31, 31, 30, 30, 30, 30, 30, 29)

        var gy = gyInput - 1600
        val gm = gmInput - 1
        val gd = gdInput - 1

        var gDayNo = 365 * gy + (gy + 3) / 4 - (gy + 99) / 100 + (gy + 399) / 400
        for (i in 0 until gm) gDayNo += gDaysInMonth[i]
        if (gm > 1 && isGregorianLeap(gyInput)) gDayNo++
        gDayNo += gd

        var jDayNo = gDayNo - 79
        val jNp = jDayNo / 12053
        jDayNo %= 12053

        var jy = 979 + 33 * jNp + 4 * (jDayNo / 1461)
        jDayNo %= 1461

        if (jDayNo >= 366) {
            jy += (jDayNo - 1) / 365
            jDayNo = (jDayNo - 1) % 365
        }

        var jm = 0
        while (jm < 11 && jDayNo >= jDaysInMonth[jm]) {
            jDayNo -= jDaysInMonth[jm]
            jm++
        }
        return PersianDate(jy, jm + 1, jDayNo + 1)
    }

    private fun isGregorianLeap(year: Int): Boolean = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
}
