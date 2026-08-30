package com.waxew.hesabdar.data

/**
 * محاسبات خالص اقساط بدون وابستگی به Android/Room.
 * جدا بودن این منطق باعث می‌شود تقسیم مبالغ با Unit Test کنترل شود و هیچ قسط صفر ایجاد نشود.
 */
object InstallmentMath {

    /**
     * مبلغ کل را بین count قسط تقسیم می‌کند.
     * باقیمانده به قسط آخر اضافه می‌شود تا مجموع اقساط دقیقاً برابر مبلغ کل باشد.
     */
    fun split(totalAmount: Long, count: Int): List<Long> {
        require(totalAmount > 0) { "مبلغ کل باید بیشتر از صفر باشد." }
        require(count > 0) { "تعداد اقساط باید بیشتر از صفر باشد." }
        require(totalAmount >= count.toLong()) { "مبلغ کل برای تعداد اقساط انتخاب‌شده کافی نیست." }

        val base = totalAmount / count
        val remainder = totalAmount % count
        return List(count) { index ->
            base + if (index == count - 1) remainder else 0L
        }.also { installments ->
            require(installments.all { it > 0 }) { "مبلغ هیچ قسطی نمی‌تواند صفر باشد." }
            require(installments.sum() == totalAmount) { "جمع اقساط با مبلغ کل برابر نیست." }
        }
    }
}
