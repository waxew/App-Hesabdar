package com.waxew.hesabdar.data

import androidx.room.withTransaction

/** ردیف پیش‌نویس فاکتور؛ UI می‌تواند چندین نمونه را در یک فاکتور ارسال کند. */
data class InvoiceDraftLine(
    val productId: Long,
    val quantity: Long,
    val unitPrice: Long
)

/** نتیجه ثبت موفق سند تجاری. */
data class PostedInvoiceResult(
    val invoiceId: Long,
    val totalAmount: Long
)

/**
 * موتور ثبت خرید/فروش و مرجوعی.
 * همه تغییرات فاکتور، ردیف، موجودی، پرداخت و Audit داخل یک Transaction انجام می‌شوند.
 */
class AccountingRepository(private val database: AppDatabase) {

    suspend fun postSale(
        personId: Long?,
        lines: List<InvoiceDraftLine>,
        paidAmount: Long,
        note: String = ""
    ): PostedInvoiceResult = postInventoryInvoice(
        type = "SALE",
        personId = personId,
        lines = lines,
        settlementAmount = paidAmount,
        note = note
    )

    suspend fun postPurchase(
        personId: Long?,
        lines: List<InvoiceDraftLine>,
        paidAmount: Long,
        note: String = ""
    ): PostedInvoiceResult = postInventoryInvoice(
        type = "PURCHASE",
        personId = personId,
        lines = lines,
        settlementAmount = paidAmount,
        note = note
    )

    /** مرجوعی از فروش: کالا به انبار برمی‌گردد و در صورت refund مبلغ به مشتری پرداخت می‌شود. */
    suspend fun postSaleReturn(
        personId: Long?,
        lines: List<InvoiceDraftLine>,
        refundAmount: Long = 0,
        note: String = ""
    ): PostedInvoiceResult = postInventoryInvoice(
        type = "SALE_RETURN",
        personId = personId,
        lines = lines,
        settlementAmount = refundAmount,
        note = note
    )

    /** مرجوعی از خرید: کالا از انبار خارج می‌شود و در صورت دریافت وجه، مبلغ از تامین‌کننده دریافت می‌شود. */
    suspend fun postPurchaseReturn(
        personId: Long?,
        lines: List<InvoiceDraftLine>,
        receivedAmount: Long = 0,
        note: String = ""
    ): PostedInvoiceResult = postInventoryInvoice(
        type = "PURCHASE_RETURN",
        personId = personId,
        lines = lines,
        settlementAmount = receivedAmount,
        note = note
    )

    private suspend fun postInventoryInvoice(
        type: String,
        personId: Long?,
        lines: List<InvoiceDraftLine>,
        settlementAmount: Long,
        note: String
    ): PostedInvoiceResult = database.withTransaction {
        require(type in setOf("SALE", "PURCHASE", "SALE_RETURN", "PURCHASE_RETURN")) { "نوع فاکتور نامعتبر است." }
        require(lines.isNotEmpty()) { "فاکتور باید حداقل یک ردیف داشته باشد." }
        require(settlementAmount >= 0) { "مبلغ تسویه نمی‌تواند منفی باشد." }

        val resolved = lines.map { draft ->
            require(draft.quantity > 0) { "تعداد هر ردیف باید بیشتر از صفر باشد." }
            require(draft.unitPrice >= 0) { "قیمت واحد نمی‌تواند منفی باشد." }
            val product = database.productDao().getById(draft.productId)
                ?: error("کالای انتخاب‌شده پیدا نشد.")

            val stockDelta = when (type) {
                "SALE" -> -draft.quantity
                "PURCHASE" -> draft.quantity
                "SALE_RETURN" -> draft.quantity
                else -> -draft.quantity
            }
            if (stockDelta < 0) {
                require(product.stock >= -stockDelta) {
                    "موجودی ${product.name} کافی نیست. موجودی فعلی: ${product.stock}"
                }
            }
            ResolvedLine(product, draft, draft.quantity * draft.unitPrice, stockDelta)
        }

        val total = resolved.sumOf { it.lineTotal }
        require(settlementAmount <= total) { "مبلغ تسویه نمی‌تواند از مبلغ فاکتور بیشتر باشد." }

        val invoiceId = database.invoiceDao().insertInvoice(
            InvoiceEntity(
                type = type,
                personId = personId,
                totalAmount = total,
                paidAmount = settlementAmount,
                note = note.trim()
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

        resolved.forEach { row ->
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

        database.auditDao().insert(
            AuditLogEntity(
                action = "POST",
                entityType = "INVOICE",
                entityId = invoiceId,
                detail = "${typeFa(type)} - مبلغ $total - تسویه $settlementAmount"
            )
        )

        PostedInvoiceResult(invoiceId, total)
    }

    private data class ResolvedLine(
        val product: ProductEntity,
        val draft: InvoiceDraftLine,
        val lineTotal: Long,
        val stockDelta: Long
    )

    private fun typeFa(type: String): String = when (type) {
        "SALE" -> "فروش"
        "PURCHASE" -> "خرید"
        "SALE_RETURN" -> "برگشت از فروش"
        "PURCHASE_RETURN" -> "برگشت از خرید"
        else -> type
    }
}
