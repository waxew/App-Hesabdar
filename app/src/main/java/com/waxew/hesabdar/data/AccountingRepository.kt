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
    val totalAmount: Long,
    val documentNumber: String
)

/**
 * موتور ثبت خرید/فروش، مرجوعی و ابطال.
 * فاکتور، موجودی، تسویه خزانه، Audit و سند حسابداری خودکار داخل یک Transaction ثبت می‌شوند.
 */
class AccountingRepository(private val database: AppDatabase) {

    suspend fun postSale(
        personId: Long?,
        lines: List<InvoiceDraftLine>,
        paidAmount: Long,
        charges: InvoiceCharges = InvoiceCharges(),
        note: String = "",
        treasuryAccountId: Long? = null
    ) = postInventoryInvoice("SALE", personId, lines, paidAmount, charges, note, treasuryAccountId)

    suspend fun postPurchase(
        personId: Long?,
        lines: List<InvoiceDraftLine>,
        paidAmount: Long,
        charges: InvoiceCharges = InvoiceCharges(),
        note: String = "",
        treasuryAccountId: Long? = null
    ) = postInventoryInvoice("PURCHASE", personId, lines, paidAmount, charges, note, treasuryAccountId)

    suspend fun postSaleReturn(
        personId: Long?,
        lines: List<InvoiceDraftLine>,
        refundAmount: Long = 0,
        charges: InvoiceCharges = InvoiceCharges(),
        note: String = "",
        treasuryAccountId: Long? = null
    ) = postInventoryInvoice("SALE_RETURN", personId, lines, refundAmount, charges, note, treasuryAccountId)

    suspend fun postPurchaseReturn(
        personId: Long?,
        lines: List<InvoiceDraftLine>,
        receivedAmount: Long = 0,
        charges: InvoiceCharges = InvoiceCharges(),
        note: String = "",
        treasuryAccountId: Long? = null
    ) = postInventoryInvoice("PURCHASE_RETURN", personId, lines, receivedAmount, charges, note, treasuryAccountId)

    /**
     * ابطال حرفه‌ای سند نهایی‌شده.
     * اصل سند حذف یا بازنویسی نمی‌شود؛ یک سند معکوس کامل برای وجه، انبار و دفتر دوبل ایجاد می‌شود.
     *
     * fallbackTreasuryAccountId فقط برای اسناد قدیمی Beta لازم است که پیش از Schema 6 تسویه آن‌ها به
     * cash_entries متصل نبود. برای اسناد جدید حساب خزانه از گردش اصلی بازیابی می‌شود.
     */
    suspend fun voidInvoice(
        invoiceId: Long,
        reason: String,
        fallbackTreasuryAccountId: Long? = null
    ): PostedInvoiceResult = database.withTransaction {
        require(reason.isNotBlank()) { "علت ابطال را وارد کنید." }
        val original = database.invoiceDao().getById(invoiceId) ?: error("فاکتور پیدا نشد.")
        require(original.status == "POSTED") { "فقط سند نهایی و ابطال‌نشده قابل ابطال است." }
        require(original.type == "SALE" || original.type == "PURCHASE") {
            "ابطال خودکار در این نسخه برای فاکتور فروش و خرید اصلی فعال است."
        }

        val originalItems = database.invoiceDao().getItems(invoiceId)
        require(originalItems.isNotEmpty()) { "ردیف‌های فاکتور برای ابطال پیدا نشد." }

        // برای سندهای جدید خزانه از منبع اصلی پیدا می‌شود؛ اسناد قدیمی می‌توانند از انتخاب UI استفاده کنند.
        val originalCashEntries = database.cashEntryDao().getForSource("INVOICE", original.id)
        val reversalTreasuryId = if (original.paidAmount > 0) {
            val linkedId = originalCashEntries.firstOrNull()?.treasuryAccountId
            requireActiveTreasury(linkedId ?: fallbackTreasuryAccountId)
        } else {
            null
        }

        val reverseType = if (original.type == "SALE") "SALE_RETURN" else "PURCHASE_RETURN"
        val reverseNumber = DocumentNumberGenerator(database).next(reverseType)

        data class ReversalLine(
            val item: InvoiceItemEntity,
            val product: ProductEntity,
            val stockDelta: Long
        )

        val reversalLines = originalItems.map { item ->
            val productId = item.productId ?: error("کالای یکی از ردیف‌ها حذف شده و ابطال امن ممکن نیست.")
            val product = database.productDao().getById(productId) ?: error("کالای ${item.productNameSnapshot} پیدا نشد.")
            val stockDelta = if (product.isService) {
                0L
            } else if (original.type == "SALE") {
                item.quantity
            } else {
                -item.quantity
            }

            if (stockDelta < 0) {
                require(product.stock >= -stockDelta) {
                    "برای ابطال خرید، موجودی ${product.name} کافی نیست. ابتدا گردش‌های بعدی کالا را بررسی کنید."
                }
            }
            ReversalLine(item, product, stockDelta)
        }

        val totals = InvoiceTotals(
            subtotal = original.subtotalAmount,
            discountAmount = original.discountAmount,
            taxAmount = original.taxAmount,
            shippingAmount = original.shippingAmount,
            grandTotal = original.totalAmount
        )
        val estimatedCost = reversalLines.fold(0L) { acc, row ->
            if (row.product.isService) acc else Math.addExact(acc, Math.multiplyExact(row.item.quantity, row.item.unitCost))
        }
        val goodsSubtotal = reversalLines.filterNot { it.product.isService }
            .fold(0L) { acc, row -> Math.addExact(acc, row.item.lineTotal) }
        val serviceSubtotal = reversalLines.filter { it.product.isService }
            .fold(0L) { acc, row -> Math.addExact(acc, row.item.lineTotal) }

        val reversalId = database.invoiceDao().insertInvoice(
            InvoiceEntity(
                type = reverseType,
                personId = original.personId,
                totalAmount = original.totalAmount,
                paidAmount = original.paidAmount,
                note = "ابطال ${original.documentNumber}: ${reason.trim()}",
                subtotalAmount = original.subtotalAmount,
                discountAmount = original.discountAmount,
                taxAmount = original.taxAmount,
                shippingAmount = original.shippingAmount,
                documentNumber = reverseNumber,
                status = "REVERSAL",
                reversesInvoiceId = original.id
            )
        )

        database.invoiceDao().insertItems(
            reversalLines.map { row -> row.item.copy(id = 0, invoiceId = reversalId) }
        )

        reversalLines.filter { it.stockDelta != 0L }.forEach { row ->
            database.productDao().adjustStock(row.product.id, row.stockDelta)
            database.inventoryDao().insert(
                InventoryMovementEntity(
                    productId = row.product.id,
                    invoiceId = reversalId,
                    movementType = reverseType,
                    quantityDelta = row.stockDelta
                )
            )
        }

        if (original.paidAmount > 0) {
            val direction = if (original.type == "SALE") "PAY" else "RECEIVE"
            database.paymentDao().insert(
                PaymentEntity(
                    direction = direction,
                    invoiceId = reversalId,
                    personId = original.personId,
                    amount = original.paidAmount,
                    note = "برگشت تسویه بابت ابطال ${original.documentNumber}"
                )
            )
            database.cashEntryDao().insert(
                CashEntryEntity(
                    kind = direction,
                    treasuryAccountId = reversalTreasuryId,
                    personId = original.personId,
                    amount = original.paidAmount,
                    note = "برگشت تسویه ${original.documentNumber} با $reverseNumber",
                    sourceType = "INVOICE_REVERSAL",
                    sourceId = reversalId
                )
            )
        }

        AutomaticJournalEngine(database).postInvoiceJournal(
            invoiceId = reversalId,
            type = reverseType,
            totals = totals,
            settlement = original.paidAmount,
            estimatedCost = if (original.type == "SALE") estimatedCost else 0L,
            goodsSubtotal = goodsSubtotal,
            serviceSubtotal = serviceSubtotal,
            treasuryAccountId = reversalTreasuryId
        )

        val changed = database.invoiceDao().markVoided(
            invoiceId = original.id,
            voidedAt = System.currentTimeMillis(),
            reason = reason.trim()
        )
        require(changed == 1) { "وضعیت فاکتور تغییر کرده است؛ عملیات ابطال متوقف شد." }

        database.auditDao().insert(
            AuditLogEntity(
                action = "VOID",
                entityType = "INVOICE",
                entityId = original.id,
                detail = "ابطال ${original.documentNumber} با سند معکوس $reverseNumber - علت: ${reason.trim()}"
            )
        )

        PostedInvoiceResult(reversalId, original.totalAmount, reverseNumber)
    }

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
        note: String,
        treasuryAccountId: Long?
    ): PostedInvoiceResult = database.withTransaction {
        require(type in setOf("SALE", "PURCHASE", "SALE_RETURN", "PURCHASE_RETURN")) { "نوع فاکتور نامعتبر است." }
        require(lines.isNotEmpty()) { "فاکتور باید حداقل یک ردیف داشته باشد." }
        require(settlementAmount >= 0) { "مبلغ تسویه نمی‌تواند منفی باشد." }
        val resolvedTreasuryId = if (settlementAmount > 0) requireActiveTreasury(treasuryAccountId) else null

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

            val unitCost = if (product.isService) 0L else product.buyPrice
            ResolvedLine(
                product = product,
                draft = draft,
                lineTotal = Math.multiplyExact(draft.quantity, draft.unitPrice),
                stockDelta = stockDelta,
                unitCost = unitCost,
                estimatedCost = Math.multiplyExact(draft.quantity, unitCost)
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
        val goodsSubtotal = resolved.filterNot { it.product.isService }.fold(0L) { acc, row -> Math.addExact(acc, row.lineTotal) }
        val serviceSubtotal = resolved.filter { it.product.isService }.fold(0L) { acc, row -> Math.addExact(acc, row.lineTotal) }

        require(settlementAmount <= totals.grandTotal) { "مبلغ تسویه نمی‌تواند از مبلغ نهایی فاکتور بیشتر باشد." }

        val documentNumber = DocumentNumberGenerator(database).next(type)
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
                shippingAmount = totals.shippingAmount,
                documentNumber = documentNumber,
                status = "POSTED"
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
                    lineTotal = row.lineTotal,
                    unitCost = row.unitCost
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
                    note = "تسویه هنگام ثبت $documentNumber"
                )
            )
            database.cashEntryDao().insert(
                CashEntryEntity(
                    kind = direction,
                    treasuryAccountId = resolvedTreasuryId,
                    personId = personId,
                    amount = settlementAmount,
                    note = "تسویه هنگام ثبت $documentNumber",
                    sourceType = "INVOICE",
                    sourceId = invoiceId
                )
            )
        }

        // موتور سند خودکار اجزای فاکتور را تفکیک می‌کند: فروش/خرید، مالیات، حمل و خدمت.
        AutomaticJournalEngine(database).postInvoiceJournal(
            invoiceId = invoiceId,
            type = type,
            totals = totals,
            settlement = settlementAmount,
            estimatedCost = if (type == "PURCHASE" || type == "PURCHASE_RETURN") 0 else estimatedCost,
            goodsSubtotal = goodsSubtotal,
            serviceSubtotal = serviceSubtotal,
            treasuryAccountId = resolvedTreasuryId
        )

        database.auditDao().insert(
            AuditLogEntity(
                action = "POST",
                entityType = "INVOICE",
                entityId = invoiceId,
                detail = "$documentNumber ${typeFa(type)} - جمع ${totals.subtotal} - تخفیف ${totals.discountAmount} - مالیات ${totals.taxAmount} - حمل ${totals.shippingAmount} - نهایی ${totals.grandTotal}"
            )
        )

        PostedInvoiceResult(invoiceId, totals.grandTotal, documentNumber)
    }

    /** حساب خزانه را قبل از ایجاد هر اثر پولی اعتبارسنجی می‌کند. */
    private suspend fun requireActiveTreasury(treasuryAccountId: Long?): Long {
        val id = requireNotNull(treasuryAccountId) { "برای مبلغ تسویه، ابتدا صندوق یا حساب بانکی را انتخاب کنید." }
        val account = database.treasuryDao().getById(id) ?: error("حساب خزانه انتخاب‌شده پیدا نشد.")
        require(account.isActive) { "حساب خزانه انتخاب‌شده غیرفعال است." }
        return id
    }

    private data class ResolvedLine(
        val product: ProductEntity,
        val draft: InvoiceDraftLine,
        val lineTotal: Long,
        val stockDelta: Long,
        val unitCost: Long,
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
