package com.waxew.hesabdar.data

import androidx.room.Dao
import androidx.room.Query

/** Queryهای یک‌باره برای بازسازی Alarmهای آفلاین هنگام اجرای برنامه. */
@Dao
interface ReminderDao {
    @Query("SELECT * FROM checks WHERE status='PENDING' AND dueAt>:now ORDER BY dueAt")
    suspend fun getPendingChecks(now: Long): List<CheckEntity>

    @Query("SELECT * FROM installments WHERE status!='PAID' AND dueAt>:now ORDER BY dueAt")
    suspend fun getPendingInstallments(now: Long): List<InstallmentEntity>
}
