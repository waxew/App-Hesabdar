package com.waxew.hesabdar.data

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** خلاصه مانده یک شخص. */
data class PersonBalanceSummary(
    val sales: Long,
    val purchases: Long,
    val received: Long,
    val paid: Long
) {
    val net: Long get() = sales - received - purchases + paid
}

/** ردیف گردش زمانی یک طرف حساب. */
data class PersonStatementRow(
    val eventId: Long,
    val eventType: String,
    val amount: Long,
    val note: String,
    val createdAt: Long
)

/** ردیف کارتکس کالا. */
data class InventoryCardRow(
    val movementId: Long,
    val movementType: String,
    val quantityDelta: Long,
    val invoiceId: Long?,
    val createdAt: Long
)

/**
 * سود برآوردی کالا بر پایه Snapshot بهای واحد همان فاکتور.
 * برخلاف نسخه‌های قبلی، تغییر قیمت خرید امروز سود فروش‌های گذشته را تغییر نمی‌دهد.
 */
data class ProductProfitRow(
    val productId: Long,
    val productName: String,
    val soldQuantity: Long,
    val salesAmount: Long,
    val estimatedCost: Long
) {
    val estimatedProfit: Long get() = salesAmount - estimatedCost
}

/** یک ردیف تراز آزمایشی. */
data class TrialBalanceRow(
    val accountId: Long,
    val code: String,
    val name: String,
    val debitTurnover: Long,
    val creditTurnover: Long
) {
    val debitBalance: Long get() = (debitTurnover - creditTurnover).coerceAtLeast(0)
    val creditBalance: Long get() = (creditTurnover - debitTurnover).coerceAtLeast(0)
}

@Dao
interface ReportingDao {
    @Query("SELECT COALESCE(SUM(CASE WHEN type='SALE' THEN totalAmount WHEN type='SALE_RETURN' THEN -totalAmount ELSE 0 END),0) FROM invoices WHERE personId=:personId")
    fun observePersonSales(personId: Long): Flow<Long>

    @Query("SELECT COALESCE(SUM(CASE WHEN type='PURCHASE' THEN totalAmount WHEN type='PURCHASE_RETURN' THEN -totalAmount ELSE 0 END),0) FROM invoices WHERE personId=:personId")
    fun observePersonPurchases(personId: Long): Flow<Long>

    @Query("SELECT COALESCE(SUM(amount),0) FROM payments WHERE personId=:personId AND direction='RECEIVE'")
    fun observePersonReceived(personId: Long): Flow<Long>

    @Query("SELECT COALESCE(SUM(amount),0) FROM payments WHERE personId=:personId AND direction='PAY'")
    fun observePersonPaid(personId: Long): Flow<Long>

    /** ترکیب فاکتورها و پرداخت‌ها در یک Timeline. */
    @Query("""
        SELECT id AS eventId,
               CASE type WHEN 'SALE' THEN 'SALE' WHEN 'PURCHASE' THEN 'PURCHASE' WHEN 'SALE_RETURN' THEN 'SALE_RETURN' ELSE 'PURCHASE_RETURN' END AS eventType,
               totalAmount AS amount,
               note AS note,
               createdAt AS createdAt
        FROM invoices WHERE personId=:personId
        UNION ALL
        SELECT id AS eventId,
               direction AS eventType,
               amount AS amount,
               note AS note,
               createdAt AS createdAt
        FROM payments WHERE personId=:personId
        ORDER BY createdAt DESC
    """)
    fun observePersonStatement(personId: Long): Flow<List<PersonStatementRow>>

    @Query("SELECT id AS movementId, movementType, quantityDelta, invoiceId, createdAt FROM inventory_movements WHERE productId=:productId ORDER BY createdAt DESC, id DESC")
    fun observeInventoryCard(productId: Long): Flow<List<InventoryCardRow>>

    /**
     * فروش و برگشت فروش با علامت معکوس جمع می‌شوند.
     * هزینه از ii.unitCost گرفته می‌شود تا سود تاریخی نسبت به تغییر قیمت فعلی کالا ثابت بماند.
     */
    @Query("""
        SELECT p.id AS productId,
               p.name AS productName,
               COALESCE(SUM(CASE WHEN i.type='SALE' THEN ii.quantity WHEN i.type='SALE_RETURN' THEN -ii.quantity ELSE 0 END),0) AS soldQuantity,
               COALESCE(SUM(CASE WHEN i.type='SALE' THEN ii.lineTotal WHEN i.type='SALE_RETURN' THEN -ii.lineTotal ELSE 0 END),0) AS salesAmount,
               COALESCE(SUM(CASE WHEN i.type='SALE' THEN ii.quantity*ii.unitCost WHEN i.type='SALE_RETURN' THEN -(ii.quantity*ii.unitCost) ELSE 0 END),0) AS estimatedCost
        FROM products p
        LEFT JOIN invoice_items ii ON ii.productId=p.id
        LEFT JOIN invoices i ON i.id=ii.invoiceId
        GROUP BY p.id,p.name
        HAVING salesAmount != 0 OR soldQuantity != 0
        ORDER BY salesAmount DESC
    """)
    fun observeProductProfit(): Flow<List<ProductProfitRow>>

    @Query("""
        SELECT a.id AS accountId,
               a.code AS code,
               a.name AS name,
               COALESCE(SUM(l.debit),0) AS debitTurnover,
               COALESCE(SUM(l.credit),0) AS creditTurnover
        FROM ledger_accounts a
        LEFT JOIN journal_lines l ON l.accountId=a.id
        WHERE a.isActive=1
        GROUP BY a.id,a.code,a.name
        ORDER BY a.code
    """)
    fun observeTrialBalance(): Flow<List<TrialBalanceRow>>

    @Query("SELECT COALESCE(SUM(debit),0) FROM journal_lines")
    fun observeJournalDebitTotal(): Flow<Long>

    @Query("SELECT COALESCE(SUM(credit),0) FROM journal_lines")
    fun observeJournalCreditTotal(): Flow<Long>
}
