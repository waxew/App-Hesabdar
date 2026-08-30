package com.waxew.hesabdar.data

import androidx.room.withTransaction

/** سرویس عملیات حسابداری پیشرفته؛ عملیات چندجدولی در Transaction انجام می‌شوند. */
class AdvancedAccountingRepository(private val database: AppDatabase) {

    suspend fun createTreasuryAccount(name: String, type: String, openingBalance: Long = 0): Long = database.withTransaction {
        require(name.isNotBlank()) { "نام حساب خزانه الزامی است." }
        require(type == "CASH" || type == "BANK") { "نوع حساب خزانه نامعتبر است." }
        val id = database.treasuryDao().insertAccount(TreasuryAccountEntity(name = name.trim(), type = type, openingBalance = openingBalance))
        database.auditDao().insert(AuditLogEntity(action = "CREATE", entityType = "TREASURY_ACCOUNT", entityId = id, detail = "ایجاد حساب ${name.trim()}"))
        id
    }

    /**
     * دریافت یا پرداخت مستقل؛ همزمان گردش خزانه، Payment، سند دوبل و Audit می‌سازد.
     * حساب صندوق/بانک اجباری است تا هیچ تراکنش مالی بدون محل نگهداری وجه ثبت نشود.
     */
    suspend fun postStandalonePayment(
        direction: String,
        personId: Long?,
        treasuryAccountId: Long?,
        amount: Long,
        note: String = ""
    ): Long = database.withTransaction {
        require(direction == "RECEIVE" || direction == "PAY") { "نوع عملیات مالی نامعتبر است." }
        require(amount > 0) { "مبلغ باید بیشتر از صفر باشد." }
        val resolvedTreasuryId = requireActiveTreasury(treasuryAccountId)

        val paymentId = database.paymentDao().insert(
            PaymentEntity(direction = direction, personId = personId, amount = amount, note = note.trim())
        )
        val entryId = database.cashEntryDao().insert(
            CashEntryEntity(
                kind = direction,
                treasuryAccountId = resolvedTreasuryId,
                personId = personId,
                amount = amount,
                note = note.trim(),
                sourceType = "PAYMENT",
                sourceId = paymentId
            )
        )
        AutomaticJournalEngine(database).postCashEntryJournal(entryId, direction, amount, resolvedTreasuryId)
        database.auditDao().insert(
            AuditLogEntity(action = "POST", entityType = "PAYMENT", entityId = paymentId, detail = "$direction مبلغ $amount - سند خودکار ثبت شد")
        )
        paymentId
    }

    /** هزینه یا درآمد غیر فاکتوری با سند دوبل خودکار و حساب خزانه اجباری. */
    suspend fun postCashEntry(
        kind: String,
        treasuryAccountId: Long?,
        amount: Long,
        category: String,
        note: String = ""
    ): Long = database.withTransaction {
        require(kind == "INCOME" || kind == "EXPENSE") { "نوع ثبت باید درآمد یا هزینه باشد." }
        require(amount > 0) { "مبلغ باید بیشتر از صفر باشد." }
        val resolvedTreasuryId = requireActiveTreasury(treasuryAccountId)
        val id = database.cashEntryDao().insert(
            CashEntryEntity(
                kind = kind,
                treasuryAccountId = resolvedTreasuryId,
                amount = amount,
                category = category.trim(),
                note = note.trim(),
                sourceType = kind
            )
        )
        AutomaticJournalEngine(database).postCashEntryJournal(id, kind, amount, resolvedTreasuryId)
        database.auditDao().insert(
            AuditLogEntity(action = "POST", entityType = kind, entityId = id, detail = "${category.trim()} - $amount - سند خودکار ثبت شد")
        )
        id
    }

    /** ثبت سند حسابداری دستی با کنترل توازن. */
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
        require(lines.all { it.debit >= 0 && it.credit >= 0 && !(it.debit > 0 && it.credit > 0) }) { "هر آرتیکل باید فقط بدهکار یا فقط بستانکار باشد." }

        val documentId = database.ledgerDao().insertDocument(
            JournalDocumentEntity(number = number.trim(), description = description.trim(), sourceType = sourceType, sourceId = sourceId)
        )
        database.ledgerDao().insertLines(lines.map { it.copy(id = 0, documentId = documentId) })
        database.auditDao().insert(AuditLogEntity(action = "POST", entityType = "JOURNAL", entityId = documentId, detail = "سند ${number.trim()} به مبلغ $debit"))
        documentId
    }

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
        require(dueAt > 0) { "تاریخ سررسید نامعتبر است." }
        val id = database.checkDao().insert(
            CheckEntity(direction = direction, personId = personId, amount = amount, bankName = bankName.trim(), checkNumber = checkNumber.trim(), sayadId = sayadId.trim(), dueAt = dueAt, note = note.trim())
        )
        database.auditDao().insert(AuditLogEntity(action = "CREATE", entityType = "CHECK", entityId = id, detail = "$direction مبلغ $amount"))
        id
    }

    suspend fun changeCheckStatus(checkId: Long, status: String) = database.withTransaction {
        val allowed = setOf("PENDING", "CLEARED", "BOUNCED", "TRANSFERRED", "CANCELLED")
        require(status in allowed) { "وضعیت چک نامعتبر است." }
        database.checkDao().updateStatus(checkId, status)
        database.auditDao().insert(AuditLogEntity(action = "STATUS_CHANGE", entityType = "CHECK", entityId = checkId, detail = status))
    }

    /**
     * ساخت اقساط مساوی. تعداد اقساط نمی‌تواند از کوچک‌ترین واحد مبلغ بیشتر باشد،
     * چون در آن حالت قسط صفر ایجاد می‌شود و هیچ‌وقت قابل تسویه نخواهد بود.
     */
    suspend fun createInstallments(
        personId: Long?,
        invoiceId: Long?,
        title: String,
        totalAmount: Long,
        dueDates: List<Long>
    ): List<Long> = database.withTransaction {
        require(title.isNotBlank()) { "عنوان اقساط الزامی است." }
        require(totalAmount > 0) { "مبلغ اقساط باید بیشتر از صفر باشد." }
        require(dueDates.isNotEmpty()) { "حداقل یک سررسید لازم است." }
        require(dueDates.all { it > 0 }) { "یکی از تاریخ‌های سررسید نامعتبر است." }
        require(totalAmount >= dueDates.size.toLong()) { "مبلغ کل برای تعداد اقساط انتخاب‌شده کافی نیست." }

        val base = totalAmount / dueDates.size
        val remainder = totalAmount % dueDates.size
        val ids = mutableListOf<Long>()
        dueDates.forEachIndexed { index, dueAt ->
            val value = base + if (index == dueDates.lastIndex) remainder else 0
            require(value > 0) { "مبلغ هر قسط باید بیشتر از صفر باشد." }
            ids += database.installmentDao().insert(
                InstallmentEntity(personId = personId, invoiceId = invoiceId, title = if (dueDates.size == 1) title else "$title - قسط ${index + 1}", amount = value, dueAt = dueAt)
            )
        }
        database.auditDao().insert(AuditLogEntity(action = "CREATE", entityType = "INSTALLMENT_PLAN", detail = "$title - ${dueDates.size} قسط - $totalAmount"))
        ids
    }

    /** پرداخت قسط بدون سرریز یا ثبت مبلغ بیشتر از مانده. */
    suspend fun payInstallment(installment: InstallmentEntity, amount: Long) = database.withTransaction {
        val remaining = installment.amount - installment.paidAmount
        require(remaining > 0) { "این قسط قبلاً تسویه شده است." }
        require(amount > 0) { "مبلغ پرداخت باید بیشتر از صفر باشد." }
        require(amount <= remaining) { "مبلغ پرداخت نمی‌تواند از مانده قسط بیشتر باشد." }
        val newPaid = Math.addExact(installment.paidAmount, amount)
        val status = if (newPaid == installment.amount) "PAID" else "PARTIAL"
        database.installmentDao().updatePayment(installment.id, newPaid, status)
        database.auditDao().insert(AuditLogEntity(action = "PAY", entityType = "INSTALLMENT", entityId = installment.id, detail = "پرداخت $amount، جمع پرداخت $newPaid"))
    }

    /** اعتبارسنجی یک حساب فعال خزانه و برگرداندن ID قطعی آن. */
    private suspend fun requireActiveTreasury(treasuryAccountId: Long?): Long {
        val id = requireNotNull(treasuryAccountId) { "ابتدا یک صندوق یا حساب بانکی انتخاب کنید." }
        val account = database.treasuryDao().getById(id) ?: error("حساب خزانه انتخاب‌شده پیدا نشد.")
        require(account.isActive) { "حساب خزانه انتخاب‌شده غیرفعال است." }
        return id
    }
}
