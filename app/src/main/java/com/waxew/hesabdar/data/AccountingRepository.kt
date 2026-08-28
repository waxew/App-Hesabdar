package com.waxew.hesabdar.data

import androidx.room.withTransaction

/**
 * ورودی ساده برای ثبت یک ردیف فاکتور از UI.
 * قیمت و نام در لحظه ثبت Snapshot می‌شوند تا تاریخچه فاکتور مستقل از تغییرات آینده کالا بماند.
 */
data class InvoiceDraftLine(
    val productId: Long,
    val quantity: Long,
    val unitPrice: Long
)

/**
 * نتیجه ثبت فاکتور که شناسه فاکتور و مبلغ نهایی را به لایه بالاتر برمی‌گرداند.
 */
data class PostedInvoiceResult(
    val invoiceId: Long,
    val totalAmount: Long
)

/**
 * لایه مرکزی عملیات مالی اولیه.
 *
 * دلیل وجود این Repository این است که UI مستقیماً چند جدول را جداگانه تغییر ندهد.
 * ثبت فاکتور، ردیف‌ها، موجودی و پرداخت داخل یک Transaction انجام می‌شود؛ اگر هر مرحله خطا بدهد، همه مراحل Rollback می‌شوند.
 */
class AccountingRepository(
    private val database: AppDatabase
) {

    /**
     * ثبت فروش نهایی.
     * برای هر ردیف موجودی کم می‌شود و یک گردش انبار منفی ثبت می‌شود.
     */
    suspend fun postSale(
        personId: Long?,
        lines: List<InvoiceDraftLine>,
        paidAmount: Long,
        note: String = ""
    ): PostedInvoiceResult = database.withTransaction {
        require(lines.isNotEmpty()) { "فاکتور فروش باید حداقل یک ردیف داشته باشد." }
        require(paidAmount >= 0) { "مبلغ پرداختی نمی‌تواند منفی باشد." }

        val resolvedLines = lines.map { draft ->
            require(draft.quantity > 0) { "تعداد کالا باید بیشتر از صفر باشد." }
            require(draft.unitPrice >= 0) { "قیمت فروش نمی‌تواند منفی باشد." }

            val product = database.productDao().getById(draft.productId)
                ?: error("کالای انتخاب‌شده پیدا نشد.")

            require(product.stock >= draft.quantity) {
                "موجودی ${product.name} کافی نیست. موجودی فعلی: ${product.stock}"
            }

            Triple(product, draft, draft.quantity * draft.unitPrice)
        }

        val total = resolvedLines.sumOf { it.third }
        require(paidAmount <= total) { "مبلغ پرداختی نمی‌تواند از مبلغ فاکتور بیشتر باشد." }

        val invoiceId = database.invoiceDao().insertInvoice(
            InvoiceEntity(
                type = "SALE",
                personId = personId,
                totalAmount = total,
                paidAmount = paidAmount,
                note = note.trim()
            )
        )

        database.invoiceDao().insertItems(
            resolvedLines.map { (product, draft, lineTotal) ->
                InvoiceItemEntity(
                    invoiceId = invoiceId,
                    productId = product.id,
                    productNameSnapshot = product.name,
                    quantity = draft.quantity,
                    unitPrice = draft.unitPrice,
                    lineTotal = lineTotal
                )
            }
        )

        resolvedLines.forEach { (product, draft, _) ->
            database.productDao().adjustStock(product.id, -draft.quantity)
            database.inventoryDao().insert(
                InventoryMovementEntity(
                    productId = product.id,
                    invoiceId = invoiceId,
                    movementType = "SALE",
                    quantityDelta = -draft.quantity
                )
            )
        }

        if (paidAmount > 0) {
            database.paymentDao().insert(
                PaymentEntity(
                    direction = "RECEIVE",
                    invoiceId = invoiceId,
                    personId = personId,
                    amount = paidAmount,
                    note = "دریافت هنگام ثبت فاکتور فروش"
                )
            )
        }

        PostedInvoiceResult(invoiceId = invoiceId, totalAmount = total)
    }

    /**
     * ثبت خرید نهایی.
     * موجودی هر کالا افزایش پیدا می‌کند و گردش مثبت PURCHASE برای کارتکس ساخته می‌شود.
     */
    suspend fun postPurchase(
        personId: Long?,
        lines: List<InvoiceDraftLine>,
        paidAmount: Long,
        note: String = ""
    ): PostedInvoiceResult = database.withTransaction {
        require(lines.isNotEmpty()) { "فاکتور خرید باید حداقل یک ردیف داشته باشد." }
        require(paidAmount >= 0) { "مبلغ پرداختی نمی‌تواند منفی باشد." }

        val resolvedLines = lines.map { draft ->
            require(draft.quantity > 0) { "تعداد کالا باید بیشتر از صفر باشد." }
            require(draft.unitPrice >= 0) { "قیمت خرید نمی‌تواند منفی باشد." }

            val product = database.productDao().getById(draft.productId)
                ?: error("کالای انتخاب‌شده پیدا نشد.")

            Triple(product, draft, draft.quantity * draft.unitPrice)
        }

        val total = resolvedLines.sumOf { it.third }
        require(paidAmount <= total) { "مبلغ پرداختی نمی‌تواند از مبلغ فاکتور بیشتر باشد." }

        val invoiceId = database.invoiceDao().insertInvoice(
            InvoiceEntity(
                type = "PURCHASE",
                personId = personId,
                totalAmount = total,
                paidAmount = paidAmount,
                note = note.trim()
            )
        )

        database.invoiceDao().insertItems(
            resolvedLines.map { (product, draft, lineTotal) ->
                InvoiceItemEntity(
                    invoiceId = invoiceId,
                    productId = product.id,
                    productNameSnapshot = product.name,
                    quantity = draft.quantity,
                    unitPrice = draft.unitPrice,
                    lineTotal = lineTotal
                )
            }
        )

        resolvedLines.forEach { (product, draft, _) ->
            database.productDao().adjustStock(product.id, draft.quantity)
            database.inventoryDao().insert(
                InventoryMovementEntity(
                    productId = product.id,
                    invoiceId = invoiceId,
                    movementType = "PURCHASE",
                    quantityDelta = draft.quantity
                )
            )
        }

        if (paidAmount > 0) {
            database.paymentDao().insert(
                PaymentEntity(
                    direction = "PAY",
                    invoiceId = invoiceId,
                    personId = personId,
                    amount = paidAmount,
                    note = "پرداخت هنگام ثبت فاکتور خرید"
                )
            )
        }

        PostedInvoiceResult(invoiceId = invoiceId, totalAmount = total)
    }
}
