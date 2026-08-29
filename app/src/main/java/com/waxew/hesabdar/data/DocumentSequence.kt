package com.waxew.hesabdar.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * شمارنده پایدار هر نوع سند.
 * nextValue همیشه شماره‌ای است که سند بعدی باید دریافت کند؛ بنابراین شماره‌های قبلی دوباره استفاده نمی‌شوند.
 */
@Entity(tableName = "document_sequences")
data class DocumentSequenceEntity(
    @PrimaryKey val docType: String,
    val nextValue: Long = 1
)

/** DAO شمارنده اسناد؛ فراخوانی next داخل Transaction بالادستی انجام می‌شود. */
@Dao
interface DocumentSequenceDao {
    @Query("SELECT * FROM document_sequences WHERE docType=:docType LIMIT 1")
    suspend fun get(docType: String): DocumentSequenceEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(sequence: DocumentSequenceEntity): Long

    @Query("UPDATE document_sequences SET nextValue=:nextValue WHERE docType=:docType")
    suspend fun updateNext(docType: String, nextValue: Long)
}

/** تبدیل نوع فاکتور و شماره ترتیبی به شماره سند خوانا و پایدار. */
object DocumentNumberFormatter {
    fun format(type: String, sequence: Long): String {
        require(sequence > 0) { "شماره سند باید بیشتر از صفر باشد." }
        val prefix = when (type) {
            "SALE" -> "S"
            "PURCHASE" -> "P"
            "SALE_RETURN" -> "SR"
            "PURCHASE_RETURN" -> "PR"
            else -> "D"
        }
        return "$prefix-${sequence.toString().padStart(6, '0')}"
    }
}

/**
 * مولد شماره سند. چون این کلاس از داخل database.withTransaction استفاده می‌شود، خواندن و افزایش شمارنده Atomic است.
 */
class DocumentNumberGenerator(private val database: AppDatabase) {
    suspend fun next(type: String): String {
        val current = database.documentSequenceDao().get(type)
        return if (current == null) {
            database.documentSequenceDao().insert(DocumentSequenceEntity(docType = type, nextValue = 2))
            DocumentNumberFormatter.format(type, 1)
        } else {
            val following = Math.addExact(current.nextValue, 1L)
            database.documentSequenceDao().updateNext(type, following)
            DocumentNumberFormatter.format(type, current.nextValue)
        }
    }
}
