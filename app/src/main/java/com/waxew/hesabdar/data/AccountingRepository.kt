package com.waxew.hesabdar.data

import androidx.room.withTransaction

/** ردیف پیش‌نویس فاکتور؛ UI می‌تواند چندین نمونه را در یک فاکتور ارسال کند. */
data class InvoiceDraftLine(
    val productId: Long,
    val quantity: Long,
    val unitPrice: Long
)

/** هزینه‌ها و تعدیلات سطح فاکتور. */
data class InvoiceCharges(
    val discountAmount: Long = 0,
    val taxPercent: Int = 0,
    val shippingAmount: Long = 0
)

/** نتیجه ثبت موفق سند تجاری. */
data class PostedInvoiceResult(
    val invoiceId: Long,
    val totalAmount: Long
)

/**
 * موتور ثبت خرید/فروش و مرجوعی.
 * فاکتور، موجودی، تسویه، Audit و سند حسابداری خودکار داخل یک Transaction ثبت می‌شوند.
 */
class AccountingRepository(private val database: AppDatabase) {

    suspend fun postSale(
        personId: Long?,
        lines: List<InvoiceDraftLine>,
        paidAmount: Long,
        charges: InvoiceCharges = InvoiceCharges(),
        note: String = ""
    ) = postInventoryInvoice("SALE", personId, lines, paidAmount, charges, note)

    suspend fun postPurchase(
        personId: Long?,
        lines: List<InvoiceDraftLine>,
        paidAmount: Long,
        charges: InvoiceCharges = InvoiceCharges(),
        note: String = ""
    ) = postInventoryInvoice("PURCHASE", personId, lines, paidAmount, charges, note)

    suspend fun postSaleReturn(
        personId: Long?,
        lines: List<InvoiceDraftLine>,
        refundAmount: Long = 0,
        charges: InvoiceCharges = InvoiceCharges(),
        note: String = ""
    ) = postInventoryInvoice("SALE_RETURN", personId, lines, refundAmount, charges, note)

    suspend fun postPurchaseReturn(
        personId: Long?,
        lines: List<InvoiceDraftLine>,
        receivedAmount: Long = 0,
        charges: InvoiceCharges = InvoiceCharges(),
        note: String = ""
    ) = postInventoryInvoice("PURCHASE_RETURN", personId, lines, receivedAmount, charges, note)

    /**
     * ثبت نهایی فاکتور. خدمت‌ها عمداً گردش موجودی ایجاد نمی‌کنند؛ بنابراین یک فاکتور می‌تواند
     * همزمان شامل کالا و خدمت باشد بدون اینکه موجودی خدمت به عدد منفی یا مثبت مصنوعی تبدیل شود.
     */
    private suspend fun postInventoryInvoice(
        type: String,
        personId: Long?,
        lines: List<InvoiceDraftLine>,
        settlementAmount: Long,
        charges: InvoiceCharges,
        note: String
    ): PostedInvoiceResult = database.withTransaction {
        require(type in setOf("SALE", "PURCHASE", "SALE_RETURN", "PURCHASE_RETURN")) { "نوع فاکتور نامعتبر است." }
        require(lines.isNotEmpty()) { "فاکتور باید حداقل یک ردیف داشته باشد." }
        require(settlementAmount >= 0) { "مبلغ تسویه نمی‌تواند منفی باشد." }

        val resolved = lines.map { draft ->
            require(draft.quantity > 0) { "تعداد هر ردیف باید بیشتر از صفر باشد." }
            require(draft.unitPrice >= 0) { "قیمت واحد نمی‌تواند منفی باشد." }

            val product = database.productDao().getById(draft.productId)
                ?: error("کالا یا خدمت انتخاب‌شده پیدا نشد.")

            val stockDelta = if (product.isService) {
                0L
            } else {
                when (type) {
                    "SALE" -> -draft.quantity
                    "PURCHASE" -> draft.quantity
                    "SALE_RETURN" -> draft.quantity
                    else -> -draft.quantity
                }
            }

            if (stockDelta < 0) {
                require(product.stock >= -stockDelta) {
                    "موجودی ${product.name} کافی نیست. موجودی فعلی: ${product.stock} ${product.unit}"
                }
            }

            ResolvedLine(
                product = product,
                draft = draft,
                lineTotal = Math.multiplyExact(draft.quantity, draft.unitPrice),
                stockDelta = stockDelta,
                estimatedCost = if (product.isService) 0 else Math.multiplyExact(draft.quantity, product.buyPrice)
            )
        }

        val subtotal = resolved.fold(0L) { acc, row -> Math.addExact(acc, row.lineTotal) }
        require(subtotal > 0) { "مبلغ فاکتور باید بیشتر از صفر باشد." }

        val totals = InvoiceMath.calculate(
            subtotal = subtotal,
            discountAmount = charges.discountAmount,
            taxPercent = charges.taxPercent,
            shippingAmount = charges.shippingAmount
        )
        val estimatedCost = resolved.fold(0L) { acc, row -> Math.addExact(acc, row.estimatedCost) }
        require(settlementAmount <= totals.grandTotal) { "مبلغ تسویه نمی‌تواند از مبلغ نهایی فاکتور بیشتر باشد." }

        val invoiceId = database.invoiceDao().insertInvoice(
            InvoiceEntity(
                type = type,
                personId = personId,
                totalAmount = totals.grandTotal,
                paidAmount = settlementAmount,
                note = note.trim(),
                subtotalAmount = totals.subtotal,
                discountAmount = totals.discountAmount,
                taxAmount = totals.taxAmount,
                shippingAmount = totals.shippingAmount
            )
        )

        database.invoiceDao().insertItems(
            resolved.map { row ->
                InvoiceItemEntity(
                    invoiceId = invoiceId,
                    productId = row.product.id,
                    productNameSnapshot = row.product.name,
                    quantity = row.draft.quantity,
                    unitPrice = row.draft.unitPrice,
                    lineTotal = row.lineTotal
                )
            }
        )

        // تنها کالاهای واقعی کارتکس دارند؛ خدمت روی انبار اثر ندارد.
        resolved.filter { it.stockDelta != 0L }.forEach { row ->
            database.productDao().adjustStock(row.product.id, row.stockDelta)
            database.inventoryDao().insert(
                InventoryMovementEntity(
                    productId = row.product.id,
                    invoiceId = invoiceId,
                    movementType = type,
                    quantityDelta = row.stockDelta
                )
            )
        }

        if (settlementAmount > 0) {
            val direction = when (type) {
                "SALE", "PURCHASE_RETURN" -> "RECEIVE"
                else -> "PAY"
            }
            database.paymentDao().insert(
                PaymentEntity(
                    direction = direction,
                    invoiceId = invoiceId,
                    personId = personId,
                    amount = settlementAmount,
                    note = "تسویه هنگام ثبت ${typeFa(type)}"
                )
            )
        }

        // در این نسخه سند خودکار بر مبلغ نهایی فاکتور ثبت می‌شود؛ حساب‌های مالیات و حمل در نسخه بعد تفکیک می‌شوند.
        AutomaticJournalEngine(database).postInvoiceJournal(
            invoiceId = invoiceId,
            type = type,
            total = totals.grandTotal,
            settlement = settlementAmount,
            estimatedCost = if (type == "PURCHASE" || type == "PURCHASE_RETURN") 0 else estimatedCost
        )

        database.auditDao().insert(
            AuditLogEntity(
                action = "POST",
                entityType = "INVOICE",
                entityId = invoiceId,
                detail = "${typeFa(type)} - جمع ${totals.subtotal} - تخفیف ${totals.discountAmount} - مالیات ${totals.taxAmount} - حمل ${totals.shippingAmount} - نهایی ${totals.grandTotal}"
            )
        )

        PostedInvoiceResult(invoiceId, totals.grandTotal)
    }

    private data class ResolvedLine(
        val product: ProductEntity,
        val draft: InvoiceDraftLine,
        val lineTotal: Long,
        val stockDelta: Long,
        val estimatedCost: Long
    )

    private fun typeFa(type: String): String = when (type) {
        "SALE" -> "فروش"
        "PURCHASE" -> "خرید"
        "SALE_RETURN" -> "برگشت از فروش"
        "PURCHASE_RETURN" -> "برگشت از خرید"
        else -> type
    }
}
