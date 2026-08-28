package com.waxew.hesabdar.data

import androidx.room.withTransaction

/**
 * سرویس عملیات حسابداری پیشرفته. تمام عملیات حساس چندجدولی از این لایه عبور می‌کنند.
 */
class AdvancedAccountingRepository(
    private val database: AppDatabase
) {

    /** ساخت صندوق یا حساب بانکی محلی. */
    suspend fun createTreasuryAccount(
        name: String,
        type: String,
        openingBalance: Long = 0
    ): Long = database.withTransaction {
        require(name.isNotBlank()) { "نام حساب خزانه الزامی است." }
        val id = database.treasuryDao().insertAccount(
            TreasuryAccountEntity(
                name = name.trim(),
                type = type,
                openingBalance = openingBalance
            )
        )
        database.auditDao().insert(
            AuditLogEntity(
                action = "CREATE",
                entityType = "TREASURY_ACCOUNT",
                entityId = id,
                detail = "ایجاد حساب خزانه ${name.trim()}"
            )
        )
        id
    }

    /** ثبت دریافت یا پرداخت آزاد که الزاماً به فاکتور متصل نیست. */
    suspend fun postStandalonePayment(
        direction: String,
        personId: Long?,
        treasuryAccountId: Long?,
        amount: Long,
        note: String = ""
    ): Long = database.withTransaction {
        require(direction == "RECEIVE" || direction == "PAY") { "نوع عملیات مالی نامعتبر است." }
        require(amount > 0) { "مبلغ باید بیشتر از صفر باشد." }

        val paymentId = database.paymentDao().insert(
            PaymentEntity(
                direction = direction,
                personId = personId,
                amount = amount,
                note = note.trim()
            )
        )

        database.cashEntryDao().insert(
            CashEntryEntity(
                kind = if (direction == "RECEIVE") "RECEIVE" else "PAY",
                treasuryAccountId = treasuryAccountId,
                personId = personId,
                amount = amount,
                note = note.trim()
            )
        )

        database.auditDao().insert(
            AuditLogEntity(
                action = "POST",
                entityType = "PAYMENT",
                entityId = paymentId,
                detail = "$direction مبلغ $amount"
            )
        )
        paymentId
    }

    /** ثبت هزینه یا درآمد غیر فاکتوری. */
    suspend fun postCashEntry(
        kind: String,
        treasuryAccountId: Long?,
        amount: Long,
        category: String,
        note: String = ""
    ): Long = database.withTransaction {
        require(kind == "INCOME" || kind == "EXPENSE") { "نوع ثبت باید درآمد یا هزینه باشد." }
        require(amount > 0) { "مبلغ باید بیشتر از صفر باشد." }
        val id = database.cashEntryDao().insert(
            CashEntryEntity(
                kind = kind,
                treasuryAccountId = treasuryAccountId,
                amount = amount,
                category = category.trim(),
                note = note.trim()
            )
        )
        database.auditDao().insert(
            AuditLogEntity(
                action = "POST",
                entityType = kind,
                entityId = id,
                detail = "${category.trim()} - $amount"
            )
        )
        id
    }

    /** ثبت سند حسابداری دوبل با کنترل توازن بدهکار و بستانکار. */
    suspend fun postJournal(
        number: String,
        description: String,
        sourceType: String,
        sourceId: Long?,
        lines: List<JournalLineEntity>
    ): Long = database.withTransaction {
        require(number.isNotBlank()) { "شماره سند الزامی است." }
        require(lines.size >= 2) { "سند حسابداری حداقل دو آرتیکل نیاز دارد." }
        val debit = lines.sumOf { it.debit }
        val credit = lines.sumOf { it.credit }
        require(debit > 0 && debit == credit) { "جمع بدهکار و بستانکار سند برابر نیست." }
        require(lines.all { it.debit >= 0 && it.credit >= 0 && !(it.debit > 0 && it.credit > 0) }) {
            "هر آرتیکل باید فقط بدهکار یا فقط بستانکار باشد."
        }

        val documentId = database.ledgerDao().insertDocument(
            JournalDocumentEntity(
                number = number.trim(),
                description = description.trim(),
                sourceType = sourceType,
                sourceId = sourceId
            )
        )
        database.ledgerDao().insertLines(lines.map { it.copy(id = 0, documentId = documentId) })
        database.auditDao().insert(
            AuditLogEntity(
                action = "POST",
                entityType = "JOURNAL",
                entityId = documentId,
                detail = "سند ${number.trim()} به مبلغ $debit"
            )
        )
        documentId
    }

    /** ثبت چک. */
    suspend fun createCheck(
        direction: String,
        personId: Long?,
        amount: Long,
        bankName: String,
        checkNumber: String,
        sayadId: String,
        dueAt: Long,
        note: String = ""
    ): Long = database.withTransaction {
        require(direction == "RECEIVE" || direction == "PAY") { "نوع چک نامعتبر است." }
        require(amount > 0) { "مبلغ چک باید بیشتر از صفر باشد." }
        val id = database.checkDao().insert(
            CheckEntity(
                direction = direction,
                personId = personId,
                amount = amount,
                bankName = bankName.trim(),
                checkNumber = checkNumber.trim(),
                sayadId = sayadId.trim(),
                dueAt = dueAt,
                note = note.trim()
            )
        )
        database.auditDao().insert(
            AuditLogEntity(
                action = "CREATE",
                entityType = "CHECK",
                entityId = id,
                detail = "$direction مبلغ $amount"
            )
        )
        id
    }

    /** تغییر وضعیت چک با ثبت Audit. */
    suspend fun changeCheckStatus(checkId: Long, status: String) = database.withTransaction {
        val allowed = setOf("PENDING", "CLEARED", "BOUNCED", "TRANSFERRED", "CANCELLED")
        require(status in allowed) { "وضعیت چک نامعتبر است." }
        database.checkDao().updateStatus(checkId, status)
        database.auditDao().insert(
            AuditLogEntity(
                action = "STATUS_CHANGE",
                entityType = "CHECK",
                entityId = checkId,
                detail = status
            )
        )
    }

    /** ساخت برنامه اقساط مساوی بر مبنای تاریخ‌های داده‌شده. */
    suspend fun createInstallments(
        personId: Long?,
        invoiceId: Long?,
        title: String,
        totalAmount: Long,
        dueDates: List<Long>
    ): List<Long> = database.withTransaction {
        require(totalAmount > 0) { "مبلغ اقساط باید بیشتر از صفر باشد." }
        require(dueDates.isNotEmpty()) { "حداقل یک سررسید لازم است." }

        val base = totalAmount / dueDates.size
        val remainder = totalAmount % dueDates.size
        val ids = mutableListOf<Long>()
        dueDates.forEachIndexed { index, dueAt ->
            val amount = base + if (index == dueDates.lastIndex) remainder else 0
            ids += database.installmentDao().insert(
                InstallmentEntity(
                    personId = personId,
                    invoiceId = invoiceId,
                    title = if (dueDates.size == 1) title else "$title - قسط ${index + 1}",
                    amount = amount,
                    dueAt = dueAt
                )
            )
        }
        database.auditDao().insert(
            AuditLogEntity(
                action = "CREATE",
                entityType = "INSTALLMENT_PLAN",
                detail = "$title - ${dueDates.size} قسط - $totalAmount"
            )
        )
        ids
    }

    /** ثبت پرداخت یک قسط. */
    suspend fun payInstallment(installment: InstallmentEntity, amount: Long) = database.withTransaction {
        require(amount > 0) { "مبلغ پرداخت باید بیشتر از صفر باشد." }
        val newPaid = (installment.paidAmount + amount).coerceAtMost(installment.amount)
        val status = if (newPaid >= installment.amount) "PAID" else "PARTIAL"
        database.installmentDao().updatePayment(installment.id, newPaid, status)
        database.auditDao().insert(
            AuditLogEntity(
                action = "PAY",
                entityType = "INSTALLMENT",
                entityId = installment.id,
                detail = "پرداخت $amount، جمع پرداخت $newPaid"
            )
        )
    }
}
