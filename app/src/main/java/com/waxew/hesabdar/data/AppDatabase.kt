package com.waxew.hesabdar.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** طرف حساب محلی؛ مانده در نسخه‌های بعد از گردش حساب محاسبه می‌شود. */
@Entity(tableName = "persons")
data class PersonEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val phone: String = ""
)

/** کالای پایه با قیمت خرید/فروش و موجودی اولیه. مبالغ با Long ذخیره می‌شوند. */
@Entity(tableName = "products")
data class ProductEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val buyPrice: Long = 0,
    val sellPrice: Long = 0,
    val stock: Long = 0
)

@Dao
interface PersonDao {
    @Query("SELECT * FROM persons ORDER BY id DESC")
    fun observeAll(): Flow<List<PersonEntity>>

    @Insert
    suspend fun insert(person: PersonEntity)
}

@Dao
interface ProductDao {
    @Query("SELECT * FROM products ORDER BY id DESC")
    fun observeAll(): Flow<List<ProductEntity>>

    @Insert
    suspend fun insert(product: ProductEntity)
}

/** دیتابیس اصلی برنامه. هر تغییر Schema باید Migration صریح داشته باشد. */
@Database(
    entities = [PersonEntity::class, ProductEntity::class],
    version = 1,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun personDao(): PersonDao
    abstract fun productDao(): ProductDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "hesabdar.db"
            ).build().also { instance = it }
        }
    }
}
