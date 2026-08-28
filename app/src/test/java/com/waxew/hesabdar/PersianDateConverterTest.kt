package com.waxew.hesabdar

import com.waxew.hesabdar.util.PersianDateConverter
import org.junit.Assert.assertEquals
import org.junit.Test

class PersianDateConverterTest {
    @Test
    fun nowruz_2025_maps_to_1404_01_01() {
        assertEquals("1404/01/01", PersianDateConverter.fromGregorian(2025, 3, 21).toString())
    }

    @Test
    fun nowruz_2026_maps_to_1405_01_01() {
        assertEquals("1405/01/01", PersianDateConverter.fromGregorian(2026, 3, 21).toString())
    }

    @Test
    fun late_august_2026_is_in_shahrivar_1405() {
        val date = PersianDateConverter.fromGregorian(2026, 8, 29)
        assertEquals(1405, date.year)
        assertEquals(6, date.month)
    }
}
