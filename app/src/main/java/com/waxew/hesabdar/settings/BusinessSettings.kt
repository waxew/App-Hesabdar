package com.waxew.hesabdar.settings

import android.content.Context

/** تنظیمات سبک کسب‌وکار؛ داده‌های مالی اصلی در Room و تنظیمات نمایشی در SharedPreferences نگهداری می‌شوند. */
data class BusinessProfile(
    val name: String = "کسب‌وکار من",
    val phone: String = "",
    val address: String = "",
    val currency: String = "TOMAN",
    val fiscalYearTitle: String = "سال مالی جاری",
    val invoicePrefix: String = "INV"
)

class BusinessSettings(context: Context) {
    private val prefs = context.getSharedPreferences("hesabdar_business", Context.MODE_PRIVATE)

    fun load(): BusinessProfile = BusinessProfile(
        name = prefs.getString("name", "کسب‌وکار من") ?: "کسب‌وکار من",
        phone = prefs.getString("phone", "") ?: "",
        address = prefs.getString("address", "") ?: "",
        currency = prefs.getString("currency", "TOMAN") ?: "TOMAN",
        fiscalYearTitle = prefs.getString("fiscal_year", "سال مالی جاری") ?: "سال مالی جاری",
        invoicePrefix = prefs.getString("invoice_prefix", "INV") ?: "INV"
    )

    fun save(profile: BusinessProfile) {
        require(profile.name.isNotBlank()) { "نام کسب‌وکار الزامی است." }
        require(profile.currency in setOf("TOMAN", "RIAL")) { "واحد پول نامعتبر است." }
        prefs.edit()
            .putString("name", profile.name.trim())
            .putString("phone", profile.phone.trim())
            .putString("address", profile.address.trim())
            .putString("currency", profile.currency)
            .putString("fiscal_year", profile.fiscalYearTitle.trim())
            .putString("invoice_prefix", profile.invoicePrefix.trim().ifBlank { "INV" })
            .apply()
    }
}
