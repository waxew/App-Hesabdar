package com.waxew.hesabdar.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/**
 * طرف حساب محلی.
 * مانده حساب عمداً در این جدول نگهداری نمی‌شود و از گردش واقعی مالی استخراج می‌شود.
 */
@Entity(tableName = "persons")
data class PersonEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val phone: String = ""
)

/**
 * کالای قابل فروش یا خدمت.
 * فیلدهای SKU، بارکد، دسته، واحد و حداقل موجودی از نسخه دیتابیس 4 اضافه شده‌اند.
 */
@Entity(tableName = "products", indices = [Index("sku"), Index("barcode")])
data class ProductEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val buyPrice: Long = 0,
    val sellPrice: Long = 0,
    val stock: Long = 0,
    val sku: String = "",
    val barcode: String = "",
    val category: String = "",
    val unit: String = "عدد",
    val minStock: Long = 0,
    val isService: Boolean = false
)

/**
 * سربرگ فاکتور.
 * totalAmount مبلغ نهایی است و چهار فیلد جدید جزئیات محاسبه را برای گزارش و PDF نگه می‌دارند.
 */
@Entity(
    tableName = "invoices",
    foreignKeys = [
        ForeignKey(
            entity = PersonEntity::class,
            parentColumns = ["id"],
            childColumns = ["personId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("personId")]
)
data class InvoiceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val personId: Long? = null,
    val totalAmount: Long,
    val paidAmount: Long = 0,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val subtotalAmount: Long = totalAmount,
    val discountAmount: Long = 0,
    val taxAmount: Long = 0,
    val shippingAmount: Long = 0
)

/** ردیف فاکتور با Snapshot نام و قیمت زمان ثبت. */
@Entity(
    tableName = "invoice_items",
    foreignKeys = [
        ForeignKey(entity = InvoiceEntity::class, parentColumns = ["id"], childColumns = ["invoiceId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ProductEntity::class, parentColumns = ["id"], childColumns = ["productId"], onDelete = ForeignKey.SET_NULL)
    ],
    indices = [Index("invoiceId"), Index("productId")]
)
data class InvoiceItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val invoiceId: Long,
    val productId: Long? = null,
    val productNameSnapshot: String,
    val quantity: Long,
    val unitPrice: Long,
    val lineTotal: Long
)

/** دریافت یا پرداخت وجه؛ اتصال به فاکتور اختیاری است. */
@Entity(
    tableName = "payments",
    foreignKeys = [
        ForeignKey(entity = InvoiceEntity::class, parentColumns = ["id"], childColumns = ["invoiceId"], onDelete = ForeignKey.SET_NULL),
        ForeignKey(entity = PersonEntity::class, parentColumns = ["id"], childColumns = ["personId"], onDelete = ForeignKey.SET_NULL)
    ],
    indices = [Index("invoiceId"), Index("personId")]
)
data class PaymentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val direction: String,
    val invoiceId: Long? = null,
    val personId: Long? = null,
    val amount: Long,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

/** کارتکس انبار؛ برای خدمت‌ها گردش موجودی ساخته نمی‌شود. */
@Entity(
    tableName = "inventory_movements",
    foreignKeys = [
        ForeignKey(entity = ProductEntity::class, parentColumns = ["id"], childColumns = ["productId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = InvoiceEntity::class, parentColumns = ["id"], childColumns = ["invoiceId"], onDelete = ForeignKey.SET_NULL)
    ],
    indices = [Index("productId"), Index("invoiceId")]
)
data class InventoryMovementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val productId: Long,
    val invoiceId: Long? = null,
    val movementType: String,
    val quantityDelta: Long,
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface PersonDao {
    @Query("SELECT * FROM persons ORDER BY id DESC")
    fun observeAll(): Flow<List<PersonEntity>>

    @Query("SELECT * FROM persons WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): PersonEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(person: PersonEntity): Long
}

@Dao
interface ProductDao {
    @Query("SELECT * FROM products ORDER BY id DESC")
    fun observeAll(): Flow<List<ProductEntity>>

    @Query("SELECT * FROM products WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): ProductEntity?

    @Query("SELECT * FROM products WHERE isService = 0 AND minStock > 0 AND stock <= minStock ORDER BY stock ASC")
    fun observeLowStock(): Flow<List<ProductEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(product: ProductEntity): Long

    @Query("UPDATE products SET stock = stock + :delta WHERE id = :productId")
    suspend fun adjustStock(productId: Long, delta: Long)
}

@Dao
interface InvoiceDao {
    @Query("SELECT * FROM invoices ORDER BY createdAt DESC, id DESC")
    fun observeAll(): Flow<List<InvoiceEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertInvoice(invoice: InvoiceEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertItems(items: List<InvoiceItemEntity>)
}

@Dao
interface PaymentDao {
    @Query("SELECT * FROM payments ORDER BY createdAt DESC, id DESC")
    fun observeAll(): Flow<List<PaymentEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(payment: PaymentEntity): Long
}

@Dao
interface InventoryDao {
    @Query("SELECT * FROM inventory_movements ORDER BY createdAt DESC, id DESC")
    fun observeAll(): Flow<List<InventoryMovementEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(movement: InventoryMovementEntity): Long
}

/** Queryهای کارت‌های داشبورد. */
@Dao
interface DashboardDao {
    @Query("SELECT COALESCE(SUM(CASE WHEN type='SALE' THEN totalAmount WHEN type='SALE_RETURN' THEN -totalAmount ELSE 0 END),0) FROM invoices")
    fun observeSalesTotal(): Flow<Long>

    @Query("SELECT COALESCE(SUM(CASE WHEN type='PURCHASE' THEN totalAmount WHEN type='PURCHASE_RETURN' THEN -totalAmount ELSE 0 END),0) FROM invoices")
    fun observePurchasesTotal(): Flow<Long>

    @Query("SELECT COALESCE(SUM(CASE WHEN type='SALE' THEN (totalAmount-paidAmount) WHEN type='SALE_RETURN' THEN -(totalAmount-paidAmount) ELSE 0 END),0) FROM invoices")
    fun observeReceivables(): Flow<Long>

    @Query("SELECT COALESCE(SUM(CASE WHEN type='PURCHASE' THEN (totalAmount-paidAmount) WHEN type='PURCHASE_RETURN' THEN -(totalAmount-paidAmount) ELSE 0 END),0) FROM invoices")
    fun observePayables(): Flow<Long>

    @Query("SELECT COUNT(*) FROM products WHERE isService = 0 AND minStock > 0 AND stock <= minStock")
    fun observeLowStockCount(): Flow<Int>
}

/**
 * دیتابیس اصلی حسابدار.
 * نسخه 4 اطلاعات حرفه‌ای‌تر کالا و اجزای مبلغ فاکتور را اضافه می‌کند.
 */
@Database(
    entities = [
        PersonEntity::class,
        ProductEntity::class,
        InvoiceEntity::class,
        InvoiceItemEntity::class,
        PaymentEntity::class,
        InventoryMovementEntity::class,
        TreasuryAccountEntity::class,
        LedgerAccountEntity::class,
        JournalDocumentEntity::class,
        JournalLineEntity::class,
        CheckEntity::class,
        InstallmentEntity::class,
        CashEntryEntity::class,
        AuditLogEntity::class
    ],
    version = 4,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun personDao(): PersonDao
    abstract fun productDao(): ProductDao
    abstract fun invoiceDao(): InvoiceDao
    abstract fun paymentDao(): PaymentDao
    abstract fun inventoryDao(): InventoryDao
    abstract fun dashboardDao(): DashboardDao
    abstract fun treasuryDao(): TreasuryDao
    abstract fun ledgerDao(): LedgerDao
    abstract fun checkDao(): CheckDao
    abstract fun installmentDao(): InstallmentDao
    abstract fun cashEntryDao(): CashEntryDao
    abstract fun auditDao(): AuditDao
    abstract fun reportingDao(): ReportingDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        /** نسخه 1 -> 2: فاکتور، پرداخت و کارتکس. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `invoices` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `type` TEXT NOT NULL, `personId` INTEGER, `totalAmount` INTEGER NOT NULL, `paidAmount` INTEGER NOT NULL, `note` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, FOREIGN KEY(`personId`) REFERENCES `persons`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_invoices_personId` ON `invoices` (`personId`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `invoice_items` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `invoiceId` INTEGER NOT NULL, `productId` INTEGER, `productNameSnapshot` TEXT NOT NULL, `quantity` INTEGER NOT NULL, `unitPrice` INTEGER NOT NULL, `lineTotal` INTEGER NOT NULL, FOREIGN KEY(`invoiceId`) REFERENCES `invoices`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`productId`) REFERENCES `products`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_invoice_items_invoiceId` ON `invoice_items` (`invoiceId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_invoice_items_productId` ON `invoice_items` (`productId`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `payments` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `direction` TEXT NOT NULL, `invoiceId` INTEGER, `personId` INTEGER, `amount` INTEGER NOT NULL, `note` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, FOREIGN KEY(`invoiceId`) REFERENCES `invoices`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL, FOREIGN KEY(`personId`) REFERENCES `persons`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_payments_invoiceId` ON `payments` (`invoiceId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_payments_personId` ON `payments` (`personId`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `inventory_movements` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `productId` INTEGER NOT NULL, `invoiceId` INTEGER, `movementType` TEXT NOT NULL, `quantityDelta` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, FOREIGN KEY(`productId`) REFERENCES `products`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`invoiceId`) REFERENCES `invoices`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_inventory_movements_productId` ON `inventory_movements` (`productId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_inventory_movements_invoiceId` ON `inventory_movements` (`invoiceId`)")
            }
        }

        /** نسخه 2 -> 3: خزانه، کدینگ، اسناد حسابداری، چک، اقساط و Audit. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `treasury_accounts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `type` TEXT NOT NULL, `openingBalance` INTEGER NOT NULL, `isActive` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `ledger_accounts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `code` TEXT NOT NULL, `name` TEXT NOT NULL, `level` TEXT NOT NULL, `parentId` INTEGER, `nature` TEXT NOT NULL, `isActive` INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_ledger_accounts_code` ON `ledger_accounts` (`code`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_ledger_accounts_parentId` ON `ledger_accounts` (`parentId`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `journal_documents` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `number` TEXT NOT NULL, `description` TEXT NOT NULL, `sourceType` TEXT NOT NULL, `sourceId` INTEGER, `createdAt` INTEGER NOT NULL, `isPosted` INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `journal_lines` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `documentId` INTEGER NOT NULL, `accountId` INTEGER NOT NULL, `debit` INTEGER NOT NULL, `credit` INTEGER NOT NULL, `description` TEXT NOT NULL, FOREIGN KEY(`documentId`) REFERENCES `journal_documents`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`accountId`) REFERENCES `ledger_accounts`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_journal_lines_documentId` ON `journal_lines` (`documentId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_journal_lines_accountId` ON `journal_lines` (`accountId`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `checks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `direction` TEXT NOT NULL, `personId` INTEGER, `amount` INTEGER NOT NULL, `bankName` TEXT NOT NULL, `checkNumber` TEXT NOT NULL, `sayadId` TEXT NOT NULL, `dueAt` INTEGER NOT NULL, `status` TEXT NOT NULL, `note` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_checks_personId` ON `checks` (`personId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_checks_dueAt` ON `checks` (`dueAt`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `installments` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `personId` INTEGER, `invoiceId` INTEGER, `title` TEXT NOT NULL, `amount` INTEGER NOT NULL, `paidAmount` INTEGER NOT NULL, `dueAt` INTEGER NOT NULL, `status` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_installments_personId` ON `installments` (`personId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_installments_invoiceId` ON `installments` (`invoiceId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_installments_dueAt` ON `installments` (`dueAt`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `cash_entries` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `kind` TEXT NOT NULL, `treasuryAccountId` INTEGER, `personId` INTEGER, `amount` INTEGER NOT NULL, `category` TEXT NOT NULL, `note` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_cash_entries_treasuryAccountId` ON `cash_entries` (`treasuryAccountId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_cash_entries_personId` ON `cash_entries` (`personId`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `audit_logs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `action` TEXT NOT NULL, `entityType` TEXT NOT NULL, `entityId` INTEGER, `detail` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_audit_logs_entityType` ON `audit_logs` (`entityType`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_audit_logs_entityId` ON `audit_logs` (`entityId`)")
            }
        }

        /**
         * نسخه 3 -> 4: اطلاعات کاتالوگ کالا و اجزای مبلغ فاکتور.
         * فقط ستون و ایندکس اضافه می‌شوند و هیچ داده قبلی حذف نمی‌شود.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `products` ADD COLUMN `sku` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `products` ADD COLUMN `barcode` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `products` ADD COLUMN `category` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `products` ADD COLUMN `unit` TEXT NOT NULL DEFAULT 'عدد'")
                db.execSQL("ALTER TABLE `products` ADD COLUMN `minStock` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `products` ADD COLUMN `isService` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_products_sku` ON `products` (`sku`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_products_barcode` ON `products` (`barcode`)")

                db.execSQL("ALTER TABLE `invoices` ADD COLUMN `subtotalAmount` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `invoices` ADD COLUMN `discountAmount` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `invoices` ADD COLUMN `taxAmount` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `invoices` ADD COLUMN `shippingAmount` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE `invoices` SET `subtotalAmount` = `totalAmount` WHERE `subtotalAmount` = 0")
            }
        }

        /** ساخت Singleton دیتابیس با تمام Migrationهای غیرمخرب. */
        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "hesabdar.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
                .also { instance = it }
        }
    }
}
