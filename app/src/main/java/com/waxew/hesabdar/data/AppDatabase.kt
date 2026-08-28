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
 *
 * نکته مهم: مانده حساب عمداً در این جدول ذخیره نمی‌شود. مانده در نسخه‌های بعدی از
 * اسناد، فاکتورها و پرداخت‌ها محاسبه می‌شود تا یک عدد دستی با گردش واقعی اختلاف پیدا نکند.
 */
@Entity(tableName = "persons")
data class PersonEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val phone: String = ""
)

/**
 * کالای پایه برنامه.
 * تمام مبالغ پولی با Long ذخیره می‌شوند و stock تعداد موجودی فعلی را نگهداری می‌کند.
 */
@Entity(tableName = "products")
data class ProductEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val buyPrice: Long = 0,
    val sellPrice: Long = 0,
    val stock: Long = 0
)

/**
 * سربرگ فاکتور خرید یا فروش.
 * type در حال حاضر یکی از SALE یا PURCHASE است و paidAmount بخش تسویه‌شده همان فاکتور است.
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
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * ردیف فاکتور.
 * نام کالا و قیمت زمان ثبت، Snapshot می‌شوند تا تغییر نام یا قیمت آینده تاریخچه فاکتور را خراب نکند.
 */
@Entity(
    tableName = "invoice_items",
    foreignKeys = [
        ForeignKey(
            entity = InvoiceEntity::class,
            parentColumns = ["id"],
            childColumns = ["invoiceId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = ProductEntity::class,
            parentColumns = ["id"],
            childColumns = ["productId"],
            onDelete = ForeignKey.SET_NULL
        )
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

/**
 * دریافت یا پرداخت وجه.
 * direction یکی از RECEIVE یا PAY است. ارتباط با فاکتور اختیاری است تا بعداً دریافت/پرداخت آزاد هم داشته باشیم.
 */
@Entity(
    tableName = "payments",
    foreignKeys = [
        ForeignKey(
            entity = InvoiceEntity::class,
            parentColumns = ["id"],
            childColumns = ["invoiceId"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = PersonEntity::class,
            parentColumns = ["id"],
            childColumns = ["personId"],
            onDelete = ForeignKey.SET_NULL
        )
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

/**
 * کارتکس انبار.
 * quantityDelta برای ورود کالا مثبت و برای خروج کالا منفی است؛ بنابراین دلیل هر تغییر موجودی قابل ردیابی می‌ماند.
 */
@Entity(
    tableName = "inventory_movements",
    foreignKeys = [
        ForeignKey(
            entity = ProductEntity::class,
            parentColumns = ["id"],
            childColumns = ["productId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = InvoiceEntity::class,
            parentColumns = ["id"],
            childColumns = ["invoiceId"],
            onDelete = ForeignKey.SET_NULL
        )
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

/** DAO طرف حساب‌ها؛ رابط مستقیم لایه داده با جدول persons. */
@Dao
interface PersonDao {
    @Query("SELECT * FROM persons ORDER BY id DESC")
    fun observeAll(): Flow<List<PersonEntity>>

    @Query("SELECT * FROM persons WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): PersonEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(person: PersonEntity): Long
}

/** DAO کالا و موجودی. تغییر موجودی از طریق AccountingRepository و داخل Transaction انجام می‌شود. */
@Dao
interface ProductDao {
    @Query("SELECT * FROM products ORDER BY id DESC")
    fun observeAll(): Flow<List<ProductEntity>>

    @Query("SELECT * FROM products WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): ProductEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(product: ProductEntity): Long

    @Query("UPDATE products SET stock = stock + :delta WHERE id = :productId")
    suspend fun adjustStock(productId: Long, delta: Long)
}

/** DAO فاکتورها و ردیف‌های فاکتور. */
@Dao
interface InvoiceDao {
    @Query("SELECT * FROM invoices ORDER BY createdAt DESC, id DESC")
    fun observeAll(): Flow<List<InvoiceEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertInvoice(invoice: InvoiceEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertItems(items: List<InvoiceItemEntity>)
}

/** DAO دریافت‌ها و پرداخت‌ها. */
@Dao
interface PaymentDao {
    @Query("SELECT * FROM payments ORDER BY createdAt DESC, id DESC")
    fun observeAll(): Flow<List<PaymentEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(payment: PaymentEntity): Long
}

/** DAO کارتکس انبار. */
@Dao
interface InventoryDao {
    @Query("SELECT * FROM inventory_movements ORDER BY createdAt DESC, id DESC")
    fun observeAll(): Flow<List<InventoryMovementEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(movement: InventoryMovementEntity): Long
}

/**
 * Queryهای خلاصه داشبورد.
 * COALESCE باعث می‌شود دیتابیس خالی به جای null مقدار صفر برگرداند.
 */
@Dao
interface DashboardDao {
    @Query("SELECT COALESCE(SUM(totalAmount), 0) FROM invoices WHERE type = 'SALE'")
    fun observeSalesTotal(): Flow<Long>

    @Query("SELECT COALESCE(SUM(totalAmount), 0) FROM invoices WHERE type = 'PURCHASE'")
    fun observePurchasesTotal(): Flow<Long>

    @Query("SELECT COALESCE(SUM(totalAmount - paidAmount), 0) FROM invoices WHERE type = 'SALE'")
    fun observeReceivables(): Flow<Long>

    @Query("SELECT COALESCE(SUM(totalAmount - paidAmount), 0) FROM invoices WHERE type = 'PURCHASE'")
    fun observePayables(): Flow<Long>
}

/**
 * دیتابیس اصلی حسابدار.
 * نسخه 2 چهار جدول عملیاتی فاکتور، ردیف فاکتور، پرداخت و کارتکس انبار را بدون حذف داده نسخه 1 اضافه می‌کند.
 */
@Database(
    entities = [
        PersonEntity::class,
        ProductEntity::class,
        InvoiceEntity::class,
        InvoiceItemEntity::class,
        PaymentEntity::class,
        InventoryMovementEntity::class
    ],
    version = 2,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun personDao(): PersonDao
    abstract fun productDao(): ProductDao
    abstract fun invoiceDao(): InvoiceDao
    abstract fun paymentDao(): PaymentDao
    abstract fun inventoryDao(): InventoryDao
    abstract fun dashboardDao(): DashboardDao

    companion object {
        /** Singleton مانع ساخته‌شدن چند نمونه همزمان از دیتابیس در Process برنامه می‌شود. */
        @Volatile
        private var instance: AppDatabase? = null

        /**
         * Migration واقعی 1 به 2؛ هیچ جدول قدیمی حذف یا بازسازی نمی‌شود و اطلاعات اشخاص و کالاها حفظ می‌شوند.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `invoices` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `type` TEXT NOT NULL,
                        `personId` INTEGER,
                        `totalAmount` INTEGER NOT NULL,
                        `paidAmount` INTEGER NOT NULL,
                        `note` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        FOREIGN KEY(`personId`) REFERENCES `persons`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_invoices_personId` ON `invoices` (`personId`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `invoice_items` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `invoiceId` INTEGER NOT NULL,
                        `productId` INTEGER,
                        `productNameSnapshot` TEXT NOT NULL,
                        `quantity` INTEGER NOT NULL,
                        `unitPrice` INTEGER NOT NULL,
                        `lineTotal` INTEGER NOT NULL,
                        FOREIGN KEY(`invoiceId`) REFERENCES `invoices`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`productId`) REFERENCES `products`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_invoice_items_invoiceId` ON `invoice_items` (`invoiceId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_invoice_items_productId` ON `invoice_items` (`productId`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `payments` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `direction` TEXT NOT NULL,
                        `invoiceId` INTEGER,
                        `personId` INTEGER,
                        `amount` INTEGER NOT NULL,
                        `note` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        FOREIGN KEY(`invoiceId`) REFERENCES `invoices`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL,
                        FOREIGN KEY(`personId`) REFERENCES `persons`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_payments_invoiceId` ON `payments` (`invoiceId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_payments_personId` ON `payments` (`personId`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `inventory_movements` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `productId` INTEGER NOT NULL,
                        `invoiceId` INTEGER,
                        `movementType` TEXT NOT NULL,
                        `quantityDelta` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        FOREIGN KEY(`productId`) REFERENCES `products`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`invoiceId`) REFERENCES `invoices`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_inventory_movements_productId` ON `inventory_movements` (`productId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_inventory_movements_invoiceId` ON `inventory_movements` (`invoiceId`)")
            }
        }

        /** ساخت یا دریافت Singleton دیتابیس همراه با Migrationهای مجاز. */
        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "hesabdar.db"
            )
                .addMigrations(MIGRATION_1_2)
                .build()
                .also { instance = it }
        }
    }
}
