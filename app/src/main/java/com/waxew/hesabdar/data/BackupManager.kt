package com.waxew.hesabdar.data

import android.content.Context
import android.graphics.pdf.PdfDocument
import java.io.File
import java.io.FileOutputStream
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * مدیریت Backup محلی دیتابیس.
 * قبل از کپی فایل اصلی، WAL checkpoint اجرا می‌شود تا داده‌های نوشته‌شده به فایل اصلی SQLite منتقل شوند.
 */
class BackupManager(
    private val context: Context,
    private val database: AppDatabase
) {
    private val dbName = "hesabdar.db"

    /** ساخت نسخه پشتیبان در پوشه خصوصی external files برنامه. */
    fun createBackup(): File {
        database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").close()
        val source = context.getDatabasePath(dbName)
        require(source.exists()) { "فایل دیتابیس پیدا نشد." }

        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "backups")
        if (!dir.exists()) dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val target = File(dir, "Hesabdar_$stamp.hdb")
        source.inputStream().use { input -> target.outputStream().use { output -> input.copyTo(output) } }
        return target
    }

    /**
     * بازیابی فایل پشتیبان.
     * باید در لایه UI بعد از گرفتن تایید کاربر اجرا شود و سپس برنامه Restart شود.
     */
    fun restoreBackup(backupFile: File) {
        require(backupFile.exists() && backupFile.length() > 0) { "فایل پشتیبان معتبر نیست." }
        database.close()
        val target = context.getDatabasePath(dbName)
        target.parentFile?.mkdirs()
        backupFile.inputStream().use { input -> target.outputStream().use { output -> input.copyTo(output) } }
        File("${target.path}-wal").delete()
        File("${target.path}-shm").delete()
    }
}

/** خروجی‌های ساده CSV و PDF بدون نیاز به کتابخانه خارجی. */
class DataExportManager(
    private val context: Context,
    private val database: AppDatabase
) {
    private fun exportDir(): File = File(context.getExternalFilesDir(null) ?: context.filesDir, "exports").also { if (!it.exists()) it.mkdirs() }

    /** خروجی CSV از فاکتورها برای Excel و نرم‌افزارهای دیگر. */
    fun exportInvoicesCsv(): File {
        val file = File(exportDir(), "invoices_${System.currentTimeMillis()}.csv")
        val db = database.openHelper.readableDatabase
        val cursor = db.query("SELECT id,type,personId,totalAmount,paidAmount,note,createdAt FROM invoices ORDER BY id")
        file.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.appendLine("id,type,personId,totalAmount,paidAmount,note,createdAt")
            while (cursor.moveToNext()) {
                val note = cursor.getString(5).orEmpty().replace("\"", "\"\"")
                writer.appendLine("${cursor.getLong(0)},${cursor.getString(1)},${if (cursor.isNull(2)) "" else cursor.getLong(2)},${cursor.getLong(3)},${cursor.getLong(4)},\"$note\",${cursor.getLong(6)}")
            }
        }
        cursor.close()
        return file
    }

    /** گزارش PDF خلاصه مدیریتی فعلی. */
    fun exportSummaryPdf(): File {
        val f = NumberFormat.getNumberInstance(Locale.US)
        val db = database.openHelper.readableDatabase
        fun scalar(sql: String): Long {
            val c = db.query(sql)
            val value = if (c.moveToFirst()) c.getLong(0) else 0L
            c.close()
            return value
        }

        val sales = scalar("SELECT COALESCE(SUM(totalAmount),0) FROM invoices WHERE type='SALE'")
        val purchases = scalar("SELECT COALESCE(SUM(totalAmount),0) FROM invoices WHERE type='PURCHASE'")
        val expenses = scalar("SELECT COALESCE(SUM(amount),0) FROM cash_entries WHERE kind='EXPENSE'")
        val income = scalar("SELECT COALESCE(SUM(amount),0) FROM cash_entries WHERE kind='INCOME'")

        val document = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
        val page = document.startPage(pageInfo)
        val canvas = page.canvas
        val paint = android.graphics.Paint().apply { textSize = 18f }
        var y = 60f
        fun line(text: String) { canvas.drawText(text, 40f, y, paint); y += 34f }
        line("Hesabdar - Accounting Summary")
        line("Sales: ${f.format(sales)}")
        line("Purchases: ${f.format(purchases)}")
        line("Other income: ${f.format(income)}")
        line("Expenses: ${f.format(expenses)}")
        line("Simple net: ${f.format(sales + income - purchases - expenses)}")
        document.finishPage(page)

        val file = File(exportDir(), "summary_${System.currentTimeMillis()}.pdf")
        FileOutputStream(file).use { document.writeTo(it) }
        document.close()
        return file
    }
}
