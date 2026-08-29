package com.waxew.hesabdar.data

/**
 * خروجی محاسبات مالی فاکتور.
 * تمام مقادیر پولی با Long نگهداری می‌شوند تا خطای اعشاری Float/Double وارد حسابداری نشود.
 */
data class InvoiceTotals(
    val subtotal: Long,
    val discountAmount: Long,
    val taxAmount: Long,
    val shippingAmount: Long,
    val grandTotal: Long
)

/**
 * ماشین‌حساب خالص فاکتور.
 * ترتیب محاسبه: جمع ردیف‌ها -> تخفیف -> مالیات -> هزینه حمل.
 */
object InvoiceMath {
    fun calculate(
        subtotal: Long,
        discountAmount: Long = 0,
        taxPercent: Int = 0,
        shippingAmount: Long = 0
    ): InvoiceTotals {
        require(subtotal >= 0) { "جمع اولیه فاکتور نمی‌تواند منفی باشد." }
        require(discountAmount >= 0) { "تخفیف نمی‌تواند منفی باشد." }
        require(discountAmount <= subtotal) { "تخفیف نمی‌تواند از جمع فاکتور بیشتر باشد." }
        require(taxPercent in 0..100) { "درصد مالیات باید بین صفر تا صد باشد." }
        require(shippingAmount >= 0) { "هزینه حمل نمی‌تواند منفی باشد." }

        val taxableAmount = subtotal - discountAmount
        val taxAmount = Math.multiplyExact(taxableAmount, taxPercent.toLong()) / 100L
        val grandTotal = Math.addExact(Math.addExact(taxableAmount, taxAmount), shippingAmount)

        return InvoiceTotals(
            subtotal = subtotal,
            discountAmount = discountAmount,
            taxAmount = taxAmount,
            shippingAmount = shippingAmount,
            grandTotal = grandTotal
        )
    }
}
