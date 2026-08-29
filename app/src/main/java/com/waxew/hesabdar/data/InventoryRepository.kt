package com.waxew.hesabdar.data

import androidx.room.withTransaction

/**
 * عملیات دستی انبار خارج از فاکتور.
 * این Repository تضمین می‌کند اصلاح موجودی همزمان در موجودی کالا، کارتکس و Audit ثبت شود.
 */
class InventoryRepository(private val database: AppDatabase) {

    /**
     * اصلاح موجودی با delta مثبت برای افزایش و delta منفی برای کاهش.
     * موجودی منفی در اصلاح دستی مجاز نیست.
     */
    suspend fun adjustStock(productId: Long, delta: Long, reason: String): Long = database.withTransaction {
        require(delta != 0L) { "مقدار اصلاح موجودی نمی‌تواند صفر باشد." }
        require(reason.isNotBlank()) { "علت اصلاح موجودی را وارد کنید." }

        val product = database.productDao().getById(productId)
            ?: error("کالا پیدا نشد.")
        require(!product.isService) { "برای خدمت موجودی انبار تعریف نمی‌شود." }

        val newStock = Math.addExact(product.stock, delta)
        require(newStock >= 0) { "موجودی پس از اصلاح نمی‌تواند منفی شود." }

        database.productDao().adjustStock(productId, delta)
        val movementId = database.inventoryDao().insert(
            InventoryMovementEntity(
                productId = productId,
                movementType = if (delta > 0) "ADJUSTMENT_IN" else "ADJUSTMENT_OUT",
                quantityDelta = delta
            )
        )
        database.auditDao().insert(
            AuditLogEntity(
                action = "STOCK_ADJUSTMENT",
                entityType = "PRODUCT",
                entityId = productId,
                detail = "${product.name}: تغییر $delta، موجودی جدید $newStock، علت: ${reason.trim()}"
            )
        )
        movementId
    }

    /**
     * ویرایش مشخصات کاتالوگ. موجودی فعلی از نسخه موجود در دیتابیس حفظ می‌شود تا ویرایش فرم
     * نتواند کارتکس را دور بزند و موجودی را بدون سند تغییر دهد.
     */
    suspend fun updateCatalog(updated: ProductEntity) = database.withTransaction {
        val current = database.productDao().getById(updated.id) ?: error("کالا پیدا نشد.")
        require(updated.name.isNotBlank()) { "نام کالا یا خدمت الزامی است." }
        require(updated.buyPrice >= 0 && updated.sellPrice >= 0) { "قیمت نمی‌تواند منفی باشد." }
        require(updated.minStock >= 0) { "حداقل موجودی نمی‌تواند منفی باشد." }

        database.productDao().update(
            updated.copy(
                stock = if (updated.isService) 0 else current.stock,
                minStock = if (updated.isService) 0 else updated.minStock,
                unit = updated.unit.ifBlank { "عدد" }
            )
        )
        database.auditDao().insert(
            AuditLogEntity(
                action = "UPDATE",
                entityType = "PRODUCT",
                entityId = updated.id,
                detail = "ویرایش مشخصات کاتالوگ ${updated.name.trim()}"
            )
        )
    }
}
