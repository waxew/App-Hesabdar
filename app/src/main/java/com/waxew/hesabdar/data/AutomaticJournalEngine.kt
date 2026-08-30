package com.waxew.hesabdar.data

import androidx.room.withTransaction
import java.math.BigInteger

/**
 * کدهای حساب‌های سیستمی مورد استفاده اسناد خودکار.
 * کدها ثابت می‌مانند تا گزارش‌های نسخه‌های بعدی بتوانند روی آن‌ها تکیه کنند.
 */
object SystemAccountCodes {
    const val CASH = "1010"
    const val BANK = "1020"
    const val RECEIVABLE = "1100"
    const val VAT_RECEIVABLE = "1150"
    const val INVENTORY = "1200"
    const val PAYABLE = "2100"
    const val VAT_PAYABLE = "2200"
    const val SALES = "4000"
    const val SALES_RETURN = "4010"
    const val SHIPPING_INCOME = "4020"
    const val COGS = "5000"
    const val PURCHASED_SERVICES = "5100"
    const val EXPENSE = "6000"
    const val OTHER_INCOME = "7000"
}

/** کدینگ پیش‌فرض؛ اجرای چندباره به دلیل OnConflict.IGNORE امن است. */
class LedgerBootstrapper(private val database: AppDatabase) {
    suspend fun ensureDefaults() = database.withTransaction {
        database.ledgerDao().insertAccounts(
            listOf(
                LedgerAccountEntity(code = SystemAccountCodes.CASH, name = "صندوق", level = "GENERAL", nature = "DEBIT"),
                LedgerAccountEntity(code = SystemAccountCodes.BANK, name = "بانک", level = "GENERAL", nature = "DEBIT"),
                LedgerAccountEntity(code = SystemAccountCodes.RECEIVABLE, name = "حساب‌های دریافتنی", level = "GENERAL", nature = "DEBIT"),
                LedgerAccountEntity(code = SystemAccountCodes.VAT_RECEIVABLE, name = "مالیات خرید / ارزش افزوده دریافتنی", level = "GENERAL", nature = "DEBIT"),
                LedgerAccountEntity(code = SystemAccountCodes.INVENTORY, name = "موجودی کالا", level = "GENERAL", nature = "DEBIT"),
                LedgerAccountEntity(code = SystemAccountCodes.PAYABLE, name = "حساب‌های پرداختنی", level = "GENERAL", nature = "CREDIT"),
                LedgerAccountEntity(code = SystemAccountCodes.VAT_PAYABLE, name = "مالیات فروش / ارزش افزوده پرداختنی", level = "GENERAL", nature = "CREDIT"),
                LedgerAccountEntity(code = SystemAccountCodes.SALES, name = "فروش", level = "GENERAL", nature = "CREDIT"),
                LedgerAccountEntity(code = SystemAccountCodes.SALES_RETURN, name = "برگشت از فروش", level = "GENERAL", nature = "DEBIT"),
                LedgerAccountEntity(code = SystemAccountCodes.SHIPPING_INCOME, name = "درآمد حمل و ارسال", level = "GENERAL", nature = "CREDIT"),
                LedgerAccountEntity(code = SystemAccountCodes.COGS, name = "بهای تمام‌شده", level = "GENERAL", nature = "DEBIT"),
                LedgerAccountEntity(code = SystemAccountCodes.PURCHASED_SERVICES, name = "خدمات خریداری‌شده", level = "GENERAL", nature = "DEBIT"),
                LedgerAccountEntity(code = SystemAccountCodes.EXPENSE, name = "هزینه‌ها", level = "GENERAL", nature = "DEBIT"),
                LedgerAccountEntity(code = SystemAccountCodes.OTHER_INCOME, name = "سایر درآمدها", level = "GENERAL", nature = "CREDIT")
            )
        )
    }
}

/**
 * موتور سند دوبل خودکار فاکتور و گردش نقدی.
 * مالیات و حمل از مبلغ اصلی فروش/خرید جدا می‌شوند تا گزارش سود و بدهی مالیاتی مخدوش نشود.
 * هر تسویه نقدی/بانکی بر اساس حساب خزانه انتخاب‌شده به حساب سیستمی صحیح صندوق یا بانک می‌رود.
 */
class AutomaticJournalEngine(private val database: AppDatabase) {

    suspend fun postInvoiceJournal(
        invoiceId: Long,
        type: String,
        totals: InvoiceTotals,
        settlement: Long,
        estimatedCost: Long,
        goodsSubtotal: Long,
        serviceSubtotal: Long,
        treasuryAccountId: Long? = null
    ): Long {
        LedgerBootstrapper(database).ensureDefaults()

        val settlementLedger = if (settlement > 0) {
            treasuryLedgerAccount(requireNotNull(treasuryAccountId) { "برای تسویه، حساب صندوق یا بانک الزامی است." })
        } else {
            null
        }
        val ar = account(SystemAccountCodes.RECEIVABLE)
        val vatReceivable = account(SystemAccountCodes.VAT_RECEIVABLE)
        val inventory = account(SystemAccountCodes.INVENTORY)
        val ap = account(SystemAccountCodes.PAYABLE)
        val vatPayable = account(SystemAccountCodes.VAT_PAYABLE)
        val sales = account(SystemAccountCodes.SALES)
        val salesReturn = account(SystemAccountCodes.SALES_RETURN)
        val shippingIncome = account(SystemAccountCodes.SHIPPING_INCOME)
        val cogs = account(SystemAccountCodes.COGS)
        val purchasedServices = account(SystemAccountCodes.PURCHASED_SERVICES)

        val lines = mutableListOf<JournalLineEntity>()
        val remainder = totals.grandTotal - settlement
        val netBase = totals.subtotal - totals.discountAmount

        // تقسیم مبلغ خالص خرید بین کالا و خدمت بدون ضرب مستقیم Long انجام می‌شود تا Overflow رخ ندهد.
        val goodsNet = proportionalShare(netBase, goodsSubtotal, totals.subtotal)
        val serviceNet = netBase - goodsNet

        when (type) {
            "SALE" -> {
                if (settlement > 0) lines += line(settlementLedger!!.id, debit = settlement, text = "دریافت فروش")
                if (remainder > 0) lines += line(ar.id, debit = remainder, text = "مطالبات فروش")
                if (netBase > 0) lines += line(sales.id, credit = netBase, text = "فروش خالص پس از تخفیف")
                if (totals.taxAmount > 0) lines += line(vatPayable.id, credit = totals.taxAmount, text = "مالیات فروش")
                if (totals.shippingAmount > 0) lines += line(shippingIncome.id, credit = totals.shippingAmount, text = "درآمد حمل و ارسال")
                if (estimatedCost > 0) {
                    lines += line(cogs.id, debit = estimatedCost, text = "بهای تمام‌شده فروش")
                    lines += line(inventory.id, credit = estimatedCost, text = "کاهش موجودی")
                }
            }

            "PURCHASE" -> {
                if (goodsNet > 0 || (totals.shippingAmount > 0 && goodsSubtotal > 0)) {
                    lines += line(
                        inventory.id,
                        debit = goodsNet + if (goodsSubtotal > 0) totals.shippingAmount else 0,
                        text = "خرید کالا و هزینه حمل قابل انتساب"
                    )
                }
                if (serviceNet > 0 || (totals.shippingAmount > 0 && goodsSubtotal == 0L)) {
                    lines += line(
                        purchasedServices.id,
                        debit = serviceNet + if (goodsSubtotal == 0L) totals.shippingAmount else 0,
                        text = "خدمات خریداری‌شده"
                    )
                }
                if (totals.taxAmount > 0) lines += line(vatReceivable.id, debit = totals.taxAmount, text = "مالیات خرید")
                if (settlement > 0) lines += line(settlementLedger!!.id, credit = settlement, text = "پرداخت خرید")
                if (remainder > 0) lines += line(ap.id, credit = remainder, text = "بدهی خرید")
            }

            "SALE_RETURN" -> {
                if (netBase > 0) lines += line(salesReturn.id, debit = netBase, text = "برگشت از فروش خالص")
                if (totals.taxAmount > 0) lines += line(vatPayable.id, debit = totals.taxAmount, text = "برگشت مالیات فروش")
                if (totals.shippingAmount > 0) lines += line(shippingIncome.id, debit = totals.shippingAmount, text = "برگشت هزینه حمل")
                if (settlement > 0) lines += line(settlementLedger!!.id, credit = settlement, text = "وجه عودتی")
                if (remainder > 0) lines += line(ar.id, credit = remainder, text = "کاهش مطالبات")
                if (estimatedCost > 0) {
                    lines += line(inventory.id, debit = estimatedCost, text = "بازگشت کالا به انبار")
                    lines += line(cogs.id, credit = estimatedCost, text = "برگشت بهای تمام‌شده")
                }
            }

            "PURCHASE_RETURN" -> {
                if (settlement > 0) lines += line(settlementLedger!!.id, debit = settlement, text = "دریافت وجه برگشت خرید")
                if (remainder > 0) lines += line(ap.id, debit = remainder, text = "کاهش بدهی تامین‌کننده")
                if (goodsNet > 0 || (totals.shippingAmount > 0 && goodsSubtotal > 0)) {
                    lines += line(
                        inventory.id,
                        credit = goodsNet + if (goodsSubtotal > 0) totals.shippingAmount else 0,
                        text = "کاهش موجودی بابت برگشت خرید"
                    )
                }
                if (serviceNet > 0 || (totals.shippingAmount > 0 && goodsSubtotal == 0L)) {
                    lines += line(
                        purchasedServices.id,
                        credit = serviceNet + if (goodsSubtotal == 0L) totals.shippingAmount else 0,
                        text = "برگشت خدمات خریداری‌شده"
                    )
                }
                if (totals.taxAmount > 0) lines += line(vatReceivable.id, credit = totals.taxAmount, text = "برگشت مالیات خرید")
            }

            else -> error("نوع سند خودکار پشتیبانی نمی‌شود.")
        }

        return insertBalancedDocument(
            number = "AUTO-INV-$invoiceId",
            description = "سند خودکار فاکتور $invoiceId",
            sourceType = type,
            sourceId = invoiceId,
            lines = lines
        )
    }

    /** ثبت خودکار اسناد درآمد، هزینه، دریافت و پرداخت مستقل. */
    suspend fun postCashEntryJournal(entryId: Long, kind: String, amount: Long, treasuryAccountId: Long): Long {
        LedgerBootstrapper(database).ensureDefaults()
        val treasury = treasuryLedgerAccount(treasuryAccountId)
        val expense = account(SystemAccountCodes.EXPENSE)
        val income = account(SystemAccountCodes.OTHER_INCOME)
        val ar = account(SystemAccountCodes.RECEIVABLE)
        val ap = account(SystemAccountCodes.PAYABLE)
        val lines = when (kind) {
            "INCOME" -> listOf(line(treasury.id, debit = amount), line(income.id, credit = amount))
            "EXPENSE" -> listOf(line(expense.id, debit = amount), line(treasury.id, credit = amount))
            "RECEIVE" -> listOf(line(treasury.id, debit = amount), line(ar.id, credit = amount))
            "PAY" -> listOf(line(ap.id, debit = amount), line(treasury.id, credit = amount))
            else -> error("نوع گردش نقدی نامعتبر است.")
        }
        return insertBalancedDocument("AUTO-CASH-$entryId", "سند خودکار گردش نقدی", kind, entryId, lines)
    }

    /** حساب خزانه عملیاتی را به حساب کل صندوق یا بانک نگاشت می‌کند. */
    private suspend fun treasuryLedgerAccount(treasuryAccountId: Long): LedgerAccountEntity {
        val treasury = database.treasuryDao().getById(treasuryAccountId)
            ?: error("حساب صندوق یا بانک انتخاب‌شده پیدا نشد.")
        require(treasury.isActive) { "حساب خزانه غیرفعال است." }
        val code = if (treasury.type == "BANK") SystemAccountCodes.BANK else SystemAccountCodes.CASH
        return account(code)
    }

    /** تقسیم صحیح و امن یک مبلغ بر اساس نسبت part/whole. */
    private fun proportionalShare(amount: Long, part: Long, whole: Long): Long {
        if (amount == 0L || part == 0L || whole == 0L) return 0L
        return BigInteger.valueOf(amount)
            .multiply(BigInteger.valueOf(part))
            .divide(BigInteger.valueOf(whole))
            .longValueExact()
    }

    private suspend fun account(code: String): LedgerAccountEntity =
        database.ledgerDao().getByCode(code) ?: error("حساب سیستمی $code ساخته نشده است.")

    private fun line(accountId: Long, debit: Long = 0, credit: Long = 0, text: String = "") =
        JournalLineEntity(documentId = 0, accountId = accountId, debit = debit, credit = credit, description = text)

    /** آخرین کنترل دفاعی قبل از ثبت سند: بدهکار و بستانکار باید دقیقاً برابر باشند. */
    private suspend fun insertBalancedDocument(
        number: String,
        description: String,
        sourceType: String,
        sourceId: Long?,
        lines: List<JournalLineEntity>
    ): Long {
        val debit = lines.sumOf { it.debit }
        val credit = lines.sumOf { it.credit }
        require(debit == credit && debit > 0) { "سند خودکار تراز نیست: بدهکار=$debit بستانکار=$credit" }
        val documentId = database.ledgerDao().insertDocument(
            JournalDocumentEntity(number = number, description = description, sourceType = sourceType, sourceId = sourceId)
        )
        database.ledgerDao().insertLines(lines.map { it.copy(documentId = documentId) })
        return documentId
    }
}
