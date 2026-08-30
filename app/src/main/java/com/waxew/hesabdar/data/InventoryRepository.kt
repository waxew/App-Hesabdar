package com.waxew.hesabdar.data

import androidx.room.withTransaction

/**
 * عملیات کاتالوگ و انبار خارج از فاکتور.
 * این Repository تضمین می‌کند موجودی، کارتکس و Audit همیشه با هم سازگار بمانند.
 */
class InventoryRepository(private val database: AppDatabase) {

    /**
     * ساخت کالا یا خدمت.
     * موجودی اولیه کالا علاوه بر فیلد stock به‌صورت OPENING در کارتکس ثبت می‌شود تا منشا موجودی قابل ردیابی باشد.
     */
    suspend fun createCatalogItem(item: ProductEntity): Long = database.withTransaction {
        require(item.name.isNotBlank()) { "نام کالا یا خدمت الزامی است." }
        require(item.buyPrice >= 0 && item.sellPrice >= 0) { "قیمت نمی‌تواند منفی باشد." }
        require(item.stock >= 0) { "موجودی اولیه نمی‌تواند منفی باشد." }
        require(item.minStock >= 0) { "حداقل موجودی نمی‌تواند منفی باشد." }

        val normalized = item.copy(
            id = 0,
            name = item.name.trim(),
            sku = item.sku.trim(),
            barcode = item.barcode.trim(),
            category = item.category.trim(),
            unit = item.unit.trim().ifBlank { "عدد" },
            stock = if (item.isService) 0 else item.stock,
            minStock = if (item.isService) 0 else item.minStock
        )
        val id = database.productDao().insert(normalized)

        if (!normalized.isService && normalized.stock > 0) {
            database.inventoryDao().insert(
                InventoryMovementEntity(
                    productId = id,
                    movementType = "OPENING",
                    quantityDelta = normalized.stock
                )
            )
        }
        database.auditDao().insert(
            AuditLogEntity(
                action = "CREATE",
                entityType = "PRODUCT",
                entityId = id,
                detail = "ایجاد ${if (normalized.isService) "خدمت" else "کالا"} ${normalized.name} با موجودی اولیه ${normalized.stock}"
            )
        )
        id
    }

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
     * ویرایش مشخصات کاتالوگ.
     * موجودی فعلی همیشه حفظ می‌شود و نوع «کالا/خدمت» پس از ایجاد قابل تغییر نیست، چون تغییر نوع
     * می‌تواند تاریخچه کارتکس و بهای تمام‌شده اسناد قبلی را نامعتبر کند.
     */
    suspend fun updateCatalog(updated: ProductEntity) = database.withTransaction {
        val current = database.productDao().getById(updated.id) ?: error("کالا پیدا نشد.")
        require(updated.name.isNotBlank()) { "نام کالا یا خدمت الزامی است." }
        require(updated.buyPrice >= 0 && updated.sellPrice >= 0) { "قیمت نمی‌تواند منفی باشد." }
        require(updated.minStock >= 0) { "حداقل موجودی نمی‌تواند منفی باشد." }
        require(updated.isService == current.isService) {
            "نوع کالا/خدمت پس از ثبت قابل تغییر نیست؛ برای نوع جدید یک مورد جداگانه بسازید."
        }

        database.productDao().update(
            updated.copy(
                stock = current.stock,
                minStock = if (current.isService) 0 else updated.minStock,
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
