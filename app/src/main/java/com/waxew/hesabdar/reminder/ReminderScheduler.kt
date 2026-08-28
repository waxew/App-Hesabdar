package com.waxew.hesabdar.reminder

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.waxew.hesabdar.MainActivity
import com.waxew.hesabdar.data.AppDatabase
import com.waxew.hesabdar.util.PersianDateConverter
import kotlinx.coroutines.flow.first
import java.text.NumberFormat
import java.util.Locale

/** بازسازی و زمان‌بندی Alarmهای آفلاین چک و قسط. */
class ReminderScheduler(private val context: Context, private val database: AppDatabase) {
    suspend fun scheduleExisting() {
        createChannel(context)
        val now = System.currentTimeMillis()
        database.checkDao().observePending().first().filter { it.dueAt > now }.forEach { check ->
            schedule(
                requestCode = 100_000 + check.id.toInt().coerceAtMost(800_000),
                dueAt = check.dueAt,
                title = "یادآوری سررسید چک",
                body = "چک ${formatMoney(check.amount)} تومان - سررسید ${PersianDateConverter.fromMillis(check.dueAt)}"
            )
        }
        database.installmentDao().observeAll().first().filter { it.status != "PAID" && it.dueAt > now }.forEach { installment ->
            schedule(
                requestCode = 1_000_000 + installment.id.toInt().coerceAtMost(800_000),
                dueAt = installment.dueAt,
                title = "یادآوری قسط",
                body = "${installment.title} - مانده ${formatMoney(installment.amount - installment.paidAmount)} تومان"
            )
        }
    }

    private fun schedule(requestCode: Int, dueAt: Long, title: String, body: String) {
        val now = System.currentTimeMillis()
        if (dueAt <= now) return
        val oneDay = 24L * 60L * 60L * 1000L
        val triggerAt = (dueAt - oneDay).coerceAtLeast(now + 5_000L)
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_BODY, body)
            putExtra(EXTRA_NOTIFICATION_ID, requestCode)
        }
        val pending = PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
    }

    companion object {
        const val CHANNEL_ID = "financial_due_dates"
        const val EXTRA_TITLE = "title"
        const val EXTRA_BODY = "body"
        const val EXTRA_NOTIFICATION_ID = "notification_id"

        fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(CHANNEL_ID, "سررسیدهای مالی", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "یادآوری چک‌ها و اقساط نزدیک سررسید"
                }
                context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
            }
        }

        private fun formatMoney(value: Long): String = NumberFormat.getNumberInstance(Locale.US).format(value)
    }
}

/** نمایش اعلان زمانی که Alarm فراخوانی می‌شود. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ReminderScheduler.createChannel(context)
        val title = intent.getStringExtra(ReminderScheduler.EXTRA_TITLE) ?: "یادآوری حسابدار"
        val body = intent.getStringExtra(ReminderScheduler.EXTRA_BODY) ?: "یک سررسید مالی نزدیک است."
        val id = intent.getIntExtra(ReminderScheduler.EXTRA_NOTIFICATION_ID, 1001)
        val openPending = PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, ReminderScheduler.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openPending)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
            // در صورت رد مجوز اعلان، نرم‌افزار بدون Crash ادامه می‌دهد.
        }
    }
}
