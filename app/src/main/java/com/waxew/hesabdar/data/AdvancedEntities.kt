package com.waxew.hesabdar.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** صندوق، حساب بانکی یا کیف پول داخلی کسب‌وکار. */
@Entity(tableName = "treasury_accounts")
data class TreasuryAccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: String = "CASH",
    val openingBalance: Long = 0,
    val isActive: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

/** کدینگ حسابداری درختی؛ parentId برای ساخت سطح کل/معین/تفصیلی استفاده می‌شود. */
@Entity(tableName = "ledger_accounts", indices = [Index("parentId"), Index(value = ["code"], unique = true)])
data class LedgerAccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val code: String,
    val name: String,
    val level: String,
    val parentId: Long? = null,
    val nature: String = "DEBIT",
    val isActive: Boolean = true
)

/** سربرگ سند حسابداری. */
@Entity(tableName = "journal_documents")
data class JournalDocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val number: String,
    val description: String = "",
    val sourceType: String = "MANUAL",
    val sourceId: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val isPosted: Boolean = true
)

/** آرتیکل سند حسابداری؛ مجموع بدهکار و بستانکار هر سند باید برابر باشد. */
@Entity(
    tableName = "journal_lines",
    foreignKeys = [
        ForeignKey(entity = JournalDocumentEntity::class, parentColumns = ["id"], childColumns = ["documentId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = LedgerAccountEntity::class, parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.RESTRICT)
    ],
    indices = [Index("documentId"), Index("accountId")]
)
data class JournalLineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val documentId: Long,
    val accountId: Long,
    val debit: Long = 0,
    val credit: Long = 0,
    val description: String = ""
)

/** چک دریافتی یا پرداختی. dueAt به epoch millis ذخیره می‌شود تا مستقل از نوع تقویم باشد. */
@Entity(tableName = "checks", indices = [Index("personId"), Index("dueAt")])
data class CheckEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val direction: String,
    val personId: Long? = null,
    val amount: Long,
    val bankName: String = "",
    val checkNumber: String = "",
    val sayadId: String = "",
    val dueAt: Long,
    val status: String = "PENDING",
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

/** قسط مستقل که می‌تواند به شخص و فاکتور متصل شود. */
@Entity(tableName = "installments", indices = [Index("personId"), Index("invoiceId"), Index("dueAt")])
data class InstallmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val personId: Long? = null,
    val invoiceId: Long? = null,
    val title: String,
    val amount: Long,
    val paidAmount: Long = 0,
    val dueAt: Long,
    val status: String = "PENDING",
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * گردش صندوق/بانک.
 *
 * sourceType/sourceId از نسخه 1.0 اضافه شده‌اند تا هر حرکت خزانه به منبع اصلی خود مثل فاکتور،
 * ابطال فاکتور یا دریافت/پرداخت مستقل وصل باشد. این اتصال برای Reversal، گزارش و Audit ضروری است.
 */
@Entity(
    tableName = "cash_entries",
    indices = [
        Index("treasuryAccountId"),
        Index("personId"),
        Index("sourceId"),
        Index(value = ["sourceType", "sourceId"])
    ]
)
data class CashEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val treasuryAccountId: Long? = null,
    val personId: Long? = null,
    val amount: Long,
    val category: String = "",
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "''") val sourceType: String = "",
    val sourceId: Long? = null
)

/** ثبت وقایع حساس برای Audit Trail. */
@Entity(tableName = "audit_logs", indices = [Index("entityType"), Index("entityId")])
data class AuditLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val action: String,
    val entityType: String,
    val entityId: Long? = null,
    val detail: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface TreasuryDao {
    @Query("SELECT * FROM treasury_accounts WHERE isActive = 1 ORDER BY id")
    fun observeAccounts(): Flow<List<TreasuryAccountEntity>>

    /** دریافت مستقیم حساب برای اعتبارسنجی عملیات مالی و انتخاب حساب دفتر (صندوق/بانک). */
    @Query("SELECT * FROM treasury_accounts WHERE id=:accountId LIMIT 1")
    suspend fun getById(accountId: Long): TreasuryAccountEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAccount(account: TreasuryAccountEntity): Long

    /**
     * مانده خزانه فقط از گردش‌های تخصیص‌یافته همان حساب محاسبه می‌شود.
     * RECEIVE و INCOME ورودی و PAY و EXPENSE خروجی هستند.
     */
    @Query("SELECT COALESCE((SELECT openingBalance FROM treasury_accounts WHERE id=:accountId),0) + COALESCE(SUM(CASE WHEN kind IN ('INCOME','RECEIVE') THEN amount ELSE -amount END),0) FROM cash_entries WHERE treasuryAccountId=:accountId")
    fun observeBalance(accountId: Long): Flow<Long>
}

@Dao
interface LedgerDao {
    @Query("SELECT * FROM ledger_accounts ORDER BY code")
    fun observeAccounts(): Flow<List<LedgerAccountEntity>>

    @Query("SELECT * FROM ledger_accounts WHERE code=:code LIMIT 1")
    suspend fun getByCode(code: String): LedgerAccountEntity?

    @Query("SELECT COUNT(*) FROM ledger_accounts")
    suspend fun countAccounts(): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAccount(account: LedgerAccountEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAccounts(accounts: List<LedgerAccountEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertDocument(document: JournalDocumentEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertLines(lines: List<JournalLineEntity>)

    @Query("SELECT * FROM journal_documents ORDER BY createdAt DESC, id DESC")
    fun observeDocuments(): Flow<List<JournalDocumentEntity>>

    @Query("SELECT * FROM journal_lines WHERE documentId=:documentId ORDER BY id")
    fun observeLines(documentId: Long): Flow<List<JournalLineEntity>>

    @Query("SELECT COALESCE(SUM(debit-credit),0) FROM journal_lines WHERE accountId=:accountId")
    fun observeAccountBalance(accountId: Long): Flow<Long>
}

@Dao
interface CheckDao {
    @Query("SELECT * FROM checks ORDER BY dueAt, id DESC")
    fun observeAll(): Flow<List<CheckEntity>>

    @Query("SELECT * FROM checks WHERE status='PENDING' ORDER BY dueAt")
    fun observePending(): Flow<List<CheckEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(check: CheckEntity): Long

    @Query("UPDATE checks SET status=:status WHERE id=:id")
    suspend fun updateStatus(id: Long, status: String)
}

@Dao
interface InstallmentDao {
    @Query("SELECT * FROM installments ORDER BY dueAt, id DESC")
    fun observeAll(): Flow<List<InstallmentEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(installment: InstallmentEntity): Long

    @Query("UPDATE installments SET paidAmount=:paidAmount, status=:status WHERE id=:id")
    suspend fun updatePayment(id: Long, paidAmount: Long, status: String)
}

@Dao
interface CashEntryDao {
    @Query("SELECT * FROM cash_entries ORDER BY createdAt DESC, id DESC")
    fun observeAll(): Flow<List<CashEntryEntity>>

    /** تمام حرکات خزانه مربوط به یک منبع؛ برای ابطال امن سند استفاده می‌شود. */
    @Query("SELECT * FROM cash_entries WHERE sourceType=:sourceType AND sourceId=:sourceId ORDER BY id")
    suspend fun getForSource(sourceType: String, sourceId: Long): List<CashEntryEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entry: CashEntryEntity): Long

    @Query("SELECT COALESCE(SUM(CASE WHEN kind='INCOME' THEN amount ELSE 0 END),0) FROM cash_entries")
    fun observeOtherIncome(): Flow<Long>

    @Query("SELECT COALESCE(SUM(CASE WHEN kind='EXPENSE' THEN amount ELSE 0 END),0) FROM cash_entries")
    fun observeExpenses(): Flow<Long>
}

@Dao
interface AuditDao {
    @Query("SELECT * FROM audit_logs ORDER BY createdAt DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int = 100): Flow<List<AuditLogEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(log: AuditLogEntity): Long
}
