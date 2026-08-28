package com.waxew.hesabdar.data

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** ردیف گردش حساب طرف حساب برای نمایش صورت‌حساب. */
data class PersonStatementRow(
    val eventId: Long,
    val eventType: String,
    val amount: Long,
    val debit: Long,
    val credit: Long,
    val note: String,
    val createdAt: Long
)

/** خلاصه مانده یک شخص بر اساس فاکتور و پرداخت‌های مستقل. */
data class PersonBalanceSummary(
    val sales: Long,
    val purchases: Long,
    val received: Long,
    val paid: Long
) {
    val receivable: Long get() = (sales - received).coerceAtLeast(0)
    val payable: Long get() = (purchases - paid).coerceAtLeast(0)
    val net: Long get() = sales - received - purchases + paid
}

/** ردیف کارتکس کالا. */
data class InventoryCardRow(
    val movementId: Long,
    val movementType: String,
    val quantityDelta: Long,
    val invoiceId: Long?,
    val createdAt: Long
)

/** گزارش سود ساده بر اساس قیمت خرید ثبت‌شده کالا و ردیف‌های فروش. */
data class ProductProfitRow(
    val productId: Long,
    val productName: String,
    val soldQuantity: Long,
    val salesAmount: Long,
    val estimatedCost: Long
) {
    val estimatedProfit: Long get() = salesAmount - estimatedCost
}

@Dao
interface ReportingDao {
    @Query("SELECT COALESCE(SUM(totalAmount),0) FROM invoices WHERE personId=:personId AND type='SALE'")
    fun observePersonSales(personId: Long): Flow<Long>

    @Query("SELECT COALESCE(SUM(totalAmount),0) FROM invoices WHERE personId=:personId AND type='PURCHASE'")
    fun observePersonPurchases(personId: Long): Flow<Long>

    @Query("SELECT COALESCE(SUM(amount),0) FROM payments WHERE personId=:personId AND direction='RECEIVE'")
    fun observePersonReceived(personId: Long): Flow<Long>

    @Query("SELECT COALESCE(SUM(amount),0) FROM payments WHERE personId=:personId AND direction='PAY'")
    fun observePersonPaid(personId: Long): Flow<Long>

    @Query("SELECT id AS movementId, movementType, quantityDelta, invoiceId, createdAt FROM inventory_movements WHERE productId=:productId ORDER BY createdAt DESC, id DESC")
    fun observeInventoryCard(productId: Long): Flow<List<InventoryCardRow>>

    @Query("""
        SELECT p.id AS productId,
               p.name AS productName,
               COALESCE(SUM(ii.quantity),0) AS soldQuantity,
               COALESCE(SUM(ii.lineTotal),0) AS salesAmount,
               COALESCE(SUM(ii.quantity * p.buyPrice),0) AS estimatedCost
        FROM products p
        LEFT JOIN invoice_items ii ON ii.productId = p.id
        LEFT JOIN invoices i ON i.id = ii.invoiceId AND i.type='SALE'
        WHERE i.id IS NOT NULL
        GROUP BY p.id, p.name
        ORDER BY salesAmount DESC
    """)
    fun observeProductProfit(): Flow<List<ProductProfitRow>>
}
