package com.waxew.hesabdar.data

import androidx.room.withTransaction

/**
 * کدینگ پایه‌ای که برای ثبت خودکار اسناد استفاده می‌شود.
 * کاربر بعداً می‌تواند حساب‌های بیشتری اضافه کند؛ این کدهای سیستمی ثابت می‌مانند.
 */
object SystemAccountCodes {
    const val CASH = "1010"
    const val BANK = "1020"
    const val RECEIVABLE = "1100"
    const val INVENTORY = "1200"
    const val PAYABLE = "2100"
    const val SALES = "4000"
    const val SALES_RETURN = "4010"
    const val COGS = "5000"
    const val PURCHASE_RETURN = "5010"
    const val EXPENSE = "6000"
    const val OTHER_INCOME = "7000"
}

/** راه‌انداز کدینگ پیش‌فرض. insert با IGNORE باعث می‌شود اجرای چندباره امن باشد. */
class LedgerBootstrapper(private val database: AppDatabase) {
    suspend fun ensureDefaults() = database.withTransaction {
        database.ledgerDao().insertAccounts(
            listOf(
                LedgerAccountEntity(code = SystemAccountCodes.CASH, name = "صندوق", level = "GENERAL", nature = "DEBIT"),
                LedgerAccountEntity(code = SystemAccountCodes.BANK, name = "بانک", level = "GENERAL", nature = "DEBIT"),
                LedgerAccountEntity(code = SystemAccountCodes.RECEIVABLE, name = "حساب‌های دریافتنی", level = "GENERAL", nature = "DEBIT"),
                LedgerAccountEntity(code = SystemAccountCodes.INVENTORY, name = "موجودی کالا", level = "GENERAL", nature = "DEBIT"),
                LedgerAccountEntity(code = SystemAccountCodes.PAYABLE, name = "حساب‌های پرداختنی", level = "GENERAL", nature = "CREDIT"),
                LedgerAccountEntity(code = SystemAccountCodes.SALES, name = "فروش", level = "GENERAL", nature = "CREDIT"),
                LedgerAccountEntity(code = SystemAccountCodes.SALES_RETURN, name = "برگشت از فروش", level = "GENERAL", nature = "DEBIT"),
                LedgerAccountEntity(code = SystemAccountCodes.COGS, name = "بهای تمام‌شده", level = "GENERAL", nature = "DEBIT"),
                LedgerAccountEntity(code = SystemAccountCodes.PURCHASE_RETURN, name = "برگشت از خرید", level = "GENERAL", nature = "CREDIT"),
                LedgerAccountEntity(code = SystemAccountCodes.EXPENSE, name = "هزینه‌ها", level = "GENERAL", nature = "DEBIT"),
                LedgerAccountEntity(code = SystemAccountCodes.OTHER_INCOME, name = "سایر درآمدها", level = "GENERAL", nature = "CREDIT")
            )
        )
    }
}

/**
 * موتور اسناد خودکار. این کلاس داخل Transaction بالادستی فراخوانی می‌شود تا سند و عملیات تجاری با هم Atomic باشند.
 */
class AutomaticJournalEngine(private val database: AppDatabase) {

    suspend fun postInvoiceJournal(
        invoiceId: Long,
        type: String,
        total: Long,
        settlement: Long,
        estimatedCost: Long
    ): Long {
        LedgerBootstrapper(database).ensureDefaults()
        val cash = account(SystemAccountCodes.CASH)
        val ar = account(SystemAccountCodes.RECEIVABLE)
        val inventory = account(SystemAccountCodes.INVENTORY)
        val ap = account(SystemAccountCodes.PAYABLE)
        val sales = account(SystemAccountCodes.SALES)
        val salesReturn = account(SystemAccountCodes.SALES_RETURN)
        val cogs = account(SystemAccountCodes.COGS)
        val purchaseReturn = account(SystemAccountCodes.PURCHASE_RETURN)

        val lines = mutableListOf<JournalLineEntity>()
        val remainder = total - settlement

        when (type) {
            "SALE" -> {
                if (settlement > 0) lines += line(cash.id, debit = settlement, text = "دریافت فروش")
                if (remainder > 0) lines += line(ar.id, debit = remainder, text = "مطالبات فروش")
                lines += line(sales.id, credit = total, text = "درآمد فروش")
                if (estimatedCost > 0) {
                    lines += line(cogs.id, debit = estimatedCost, text = "بهای تمام‌شده فروش")
                    lines += line(inventory.id, credit = estimatedCost, text = "کاهش موجودی")
                }
            }
            "PURCHASE" -> {
                lines += line(inventory.id, debit = total, text = "خرید کالا")
                if (settlement > 0) lines += line(cash.id, credit = settlement, text = "پرداخت خرید")
                if (remainder > 0) lines += line(ap.id, credit = remainder, text = "بدهی خرید")
            }
            "SALE_RETURN" -> {
                lines += line(salesReturn.id, debit = total, text = "برگشت از فروش")
                if (settlement > 0) lines += line(cash.id, credit = settlement, text = "وجه عودتی")
                if (remainder > 0) lines += line(ar.id, credit = remainder, text = "کاهش مطالبات")
                if (estimatedCost > 0) {
                    lines += line(inventory.id, debit = estimatedCost, text = "بازگشت کالا به انبار")
                    lines += line(cogs.id, credit = estimatedCost, text = "برگشت بهای تمام‌شده")
                }
            }
            "PURCHASE_RETURN" -> {
                if (settlement > 0) lines += line(cash.id, debit = settlement, text = "دریافت برگشت خرید")
                if (remainder > 0) lines += line(ap.id, debit = remainder, text = "کاهش بدهی تامین‌کننده")
                lines += line(purchaseReturn.id, credit = total, text = "برگشت از خرید")
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

    suspend fun postCashEntryJournal(entryId: Long, kind: String, amount: Long): Long {
        LedgerBootstrapper(database).ensureDefaults()
        val cash = account(SystemAccountCodes.CASH)
        val expense = account(SystemAccountCodes.EXPENSE)
        val income = account(SystemAccountCodes.OTHER_INCOME)
        val ar = account(SystemAccountCodes.RECEIVABLE)
        val ap = account(SystemAccountCodes.PAYABLE)

        val lines = when (kind) {
            "INCOME" -> listOf(line(cash.id, debit = amount), line(income.id, credit = amount))
            "EXPENSE" -> listOf(line(expense.id, debit = amount), line(cash.id, credit = amount))
            "RECEIVE" -> listOf(line(cash.id, debit = amount), line(ar.id, credit = amount))
            "PAY" -> listOf(line(ap.id, debit = amount), line(cash.id, credit = amount))
            else -> error("نوع گردش نقدی نامعتبر است.")
        }
        return insertBalancedDocument("AUTO-CASH-$entryId", "سند خودکار گردش نقدی", kind, entryId, lines)
    }

    private suspend fun account(code: String): LedgerAccountEntity =
        database.ledgerDao().getByCode(code) ?: error("حساب سیستمی $code ساخته نشده است.")

    private fun line(accountId: Long, debit: Long = 0, credit: Long = 0, text: String = "") =
        JournalLineEntity(documentId = 0, accountId = accountId, debit = debit, credit = credit, description = text)

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
