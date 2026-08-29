package com.waxew.hesabdar.data

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import com.waxew.hesabdar.settings.BusinessSettings
import com.waxew.hesabdar.util.PersianDateConverter
import java.io.File
import java.io.FileOutputStream
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** مدیریت Backup محلی دیتابیس با checkpoint قبل از کپی. */
class BackupManager(private val context: Context, private val database: AppDatabase) {
    private val dbName = "hesabdar.db"

    /** ساخت Backup از فایل SQLite پس از انتقال WAL به فایل اصلی. */
    fun createBackup(): File {
        database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").close()
        val source = context.getDatabasePath(dbName)
        require(source.exists()) { "فایل دیتابیس پیدا نشد." }
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "backups").also { if (!it.exists()) it.mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val target = File(dir, "Hesabdar_$stamp.hdb")
        source.inputStream().use { input -> target.outputStream().use { output -> input.copyTo(output) } }
        return target
    }

    /** بازیابی Backup؛ UI بعد از اجرا برنامه را می‌بندد تا Room در اجرای بعدی فایل جدید را باز کند. */
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

/** خروجی CSV و PDF بدون وابستگی خارجی. */
class DataExportManager(private val context: Context, private val database: AppDatabase) {
    private fun exportDir(): File = File(context.getExternalFilesDir(null) ?: context.filesDir, "exports").also { if (!it.exists()) it.mkdirs() }

    /** CSV با شماره سند پایدار، وضعیت چرخه عمر و تمام اجزای مبلغ فاکتور. */
    fun exportInvoicesCsv(): File {
        val file = File(exportDir(), "invoices_${System.currentTimeMillis()}.csv")
        val db = database.openHelper.readableDatabase
        val cursor = db.query(
            "SELECT id,documentNumber,status,type,personId,subtotalAmount,discountAmount,taxAmount,shippingAmount,totalAmount,paidAmount,reversesInvoiceId,voidReason,note,createdAt FROM invoices ORDER BY id"
        )
        file.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.append('\uFEFF')
            writer.appendLine("id,documentNumber,status,type,personId,subtotal,discount,tax,shipping,total,paid,reversesInvoiceId,voidReason,note,createdAt")
            while (cursor.moveToNext()) {
                val voidReason = csvText(cursor.getString(12).orEmpty())
                val note = csvText(cursor.getString(13).orEmpty())
                writer.appendLine(
                    "${cursor.getLong(0)},\"${csvText(cursor.getString(1))}\",${cursor.getString(2)},${cursor.getString(3)}," +
                        "${if (cursor.isNull(4)) "" else cursor.getLong(4)},${cursor.getLong(5)},${cursor.getLong(6)},${cursor.getLong(7)},${cursor.getLong(8)}," +
                        "${cursor.getLong(9)},${cursor.getLong(10)},${if (cursor.isNull(11)) "" else cursor.getLong(11)},\"$voidReason\",\"$note\",${cursor.getLong(14)}"
                )
            }
        }
        cursor.close()
        return file
    }

    /** PDF فارسی یک فاکتور با شماره سند، وضعیت، مشخصات کسب‌وکار، ردیف‌ها و جزئیات مبلغ. */
    fun exportInvoicePdf(invoiceId: Long): File {
        val db = database.openHelper.readableDatabase
        val invoiceCursor = db.query(
            "SELECT i.id,i.documentNumber,i.status,i.type,i.subtotalAmount,i.discountAmount,i.taxAmount,i.shippingAmount,i.totalAmount,i.paidAmount,i.note,i.createdAt,COALESCE(p.name,'مشتری متفرقه'),i.reversesInvoiceId,i.voidReason " +
                "FROM invoices i LEFT JOIN persons p ON p.id=i.personId WHERE i.id=?",
            arrayOf(invoiceId.toString())
        )
        require(invoiceCursor.moveToFirst()) { "فاکتور پیدا نشد." }

        val documentNumber = invoiceCursor.getString(1).orEmpty().ifBlank { "#${invoiceCursor.getLong(0)}" }
        val status = invoiceCursor.getString(2)
        val type = invoiceCursor.getString(3)
        val subtotal = invoiceCursor.getLong(4)
        val discount = invoiceCursor.getLong(5)
        val tax = invoiceCursor.getLong(6)
        val shipping = invoiceCursor.getLong(7)
        val total = invoiceCursor.getLong(8)
        val paid = invoiceCursor.getLong(9)
        val note = invoiceCursor.getString(10).orEmpty()
        val createdAt = invoiceCursor.getLong(11)
        val person = invoiceCursor.getString(12)
        val reversesId = if (invoiceCursor.isNull(13)) null else invoiceCursor.getLong(13)
        val voidReason = invoiceCursor.getString(14).orEmpty()
        invoiceCursor.close()

        data class Line(val name: String, val qty: Long, val price: Long, val total: Long)
        val lines = mutableListOf<Line>()
        val itemCursor = db.query(
            "SELECT productNameSnapshot,quantity,unitPrice,lineTotal FROM invoice_items WHERE invoiceId=? ORDER BY id",
            arrayOf(invoiceId.toString())
        )
        while (itemCursor.moveToNext()) {
            lines += Line(itemCursor.getString(0), itemCursor.getLong(1), itemCursor.getLong(2), itemCursor.getLong(3))
        }
        itemCursor.close()

        val business = BusinessSettings(context).load()
        val formatter = NumberFormat.getNumberInstance(Locale.US)
        val document = PdfDocument()
        var pageNumber = 1
        var page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNumber).create())
        var canvas = page.canvas
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 22f
            textAlign = Paint.Align.RIGHT
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 14f; textAlign = Paint.Align.RIGHT }
        val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f; textAlign = Paint.Align.RIGHT }
        var y = 48f

        fun header() {
            y = 48f
            canvas.drawText(business.name, 555f, y, titlePaint); y += 32f
            canvas.drawText("${invoiceTypeFa(type)} $documentNumber", 555f, y, textPaint); y += 22f
            canvas.drawText("وضعیت: ${statusFa(status)}", 555f, y, textPaint); y += 22f
            canvas.drawText("تاریخ: ${PersianDateConverter.fromMillis(createdAt)}", 555f, y, textPaint); y += 22f
            canvas.drawText("طرف حساب: $person", 555f, y, textPaint); y += 28f
            canvas.drawLine(40f, y, 555f, y, textPaint); y += 24f
        }

        fun newPage() {
            document.finishPage(page)
            pageNumber++
            page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNumber).create())
            canvas = page.canvas
            header()
        }

        header()
        lines.forEachIndexed { index, line ->
            if (y > 700f) newPage()
            canvas.drawText("${index + 1}. ${line.name}", 555f, y, textPaint); y += 20f
            canvas.drawText("${formatter.format(line.qty)} × ${formatter.format(line.price)} = ${formatter.format(line.total)}", 540f, y, smallPaint); y += 24f
        }

        if (y > 590f) newPage()
        canvas.drawLine(40f, y, 555f, y, textPaint); y += 26f
        canvas.drawText("جمع ردیف‌ها: ${formatter.format(subtotal)} ${currencyFa(business.currency)}", 555f, y, textPaint); y += 22f
        if (discount > 0) { canvas.drawText("تخفیف: ${formatter.format(discount)}", 555f, y, textPaint); y += 22f }
        if (tax > 0) { canvas.drawText("مالیات: ${formatter.format(tax)}", 555f, y, textPaint); y += 22f }
        if (shipping > 0) { canvas.drawText("حمل / ارسال: ${formatter.format(shipping)}", 555f, y, textPaint); y += 22f }
        canvas.drawText("مبلغ نهایی: ${formatter.format(total)} ${currencyFa(business.currency)}", 555f, y, titlePaint); y += 28f
        canvas.drawText("تسویه: ${formatter.format(paid)}", 555f, y, textPaint); y += 22f
        canvas.drawText("مانده: ${formatter.format((total - paid).coerceAtLeast(0))}", 555f, y, textPaint); y += 22f
        if (reversesId != null) { canvas.drawText("سند معکوس فاکتور داخلی #$reversesId", 555f, y, smallPaint); y += 20f }
        if (voidReason.isNotBlank()) { canvas.drawText("علت ابطال: ${voidReason.take(70)}", 555f, y, smallPaint); y += 20f }
        if (note.isNotBlank()) canvas.drawText("توضیحات: ${note.take(70)}", 555f, y, smallPaint)
        document.finishPage(page)

        val safeNumber = documentNumber.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val file = File(exportDir(), "invoice_${safeNumber}_${System.currentTimeMillis()}.pdf")
        FileOutputStream(file).use { document.writeTo(it) }
        document.close()
        return file
    }

    /** گزارش PDF خلاصه مدیریتی؛ اسناد معکوس به صورت طبیعی اثر اسناد ابطال‌شده را خنثی می‌کنند. */
    fun exportSummaryPdf(): File {
        val formatter = NumberFormat.getNumberInstance(Locale.US)
        val db = database.openHelper.readableDatabase

        fun scalar(sql: String): Long {
            val cursor = db.query(sql)
            val value = if (cursor.moveToFirst()) cursor.getLong(0) else 0L
            cursor.close()
            return value
        }

        val sales = scalar("SELECT COALESCE(SUM(CASE WHEN type='SALE' THEN totalAmount WHEN type='SALE_RETURN' THEN -totalAmount ELSE 0 END),0) FROM invoices")
        val purchases = scalar("SELECT COALESCE(SUM(CASE WHEN type='PURCHASE' THEN totalAmount WHEN type='PURCHASE_RETURN' THEN -totalAmount ELSE 0 END),0) FROM invoices")
        val expenses = scalar("SELECT COALESCE(SUM(amount),0) FROM cash_entries WHERE kind='EXPENSE'")
        val income = scalar("SELECT COALESCE(SUM(amount),0) FROM cash_entries WHERE kind='INCOME'")
        val taxSales = scalar("SELECT COALESCE(SUM(CASE WHEN type='SALE' THEN taxAmount WHEN type='SALE_RETURN' THEN -taxAmount ELSE 0 END),0) FROM invoices")
        val taxPurchases = scalar("SELECT COALESCE(SUM(CASE WHEN type='PURCHASE' THEN taxAmount WHEN type='PURCHASE_RETURN' THEN -taxAmount ELSE 0 END),0) FROM invoices")

        val business = BusinessSettings(context).load()
        val document = PdfDocument()
        val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
        val canvas = page.canvas
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 18f; textAlign = Paint.Align.RIGHT }
        var y = 60f
        fun line(text: String) { canvas.drawText(text, 555f, y, paint); y += 34f }

        line(business.name)
        line("گزارش خلاصه حسابداری")
        line("فروش خالص: ${formatter.format(sales)}")
        line("خرید خالص: ${formatter.format(purchases)}")
        line("مالیات فروش: ${formatter.format(taxSales)}")
        line("مالیات خرید: ${formatter.format(taxPurchases)}")
        line("سایر درآمد: ${formatter.format(income)}")
        line("هزینه: ${formatter.format(expenses)}")
        line("خالص ساده: ${formatter.format(sales + income - purchases - expenses)}")
        document.finishPage(page)

        val file = File(exportDir(), "summary_${System.currentTimeMillis()}.pdf")
        FileOutputStream(file).use { document.writeTo(it) }
        document.close()
        return file
    }

    private fun invoiceTypeFa(type: String): String = when (type) {
        "SALE" -> "فاکتور فروش"
        "PURCHASE" -> "فاکتور خرید"
        "SALE_RETURN" -> "برگشت از فروش"
        "PURCHASE_RETURN" -> "برگشت از خرید"
        else -> "سند تجاری"
    }

    private fun statusFa(status: String): String = when (status) {
        "POSTED" -> "ثبت نهایی"
        "VOIDED" -> "باطل‌شده"
        "REVERSAL" -> "سند معکوس"
        else -> status
    }

    private fun currencyFa(currency: String): String = if (currency == "RIAL") "ریال" else "تومان"

    /** Escape کمینه برای متن CSV داخل کوتیشن. */
    private fun csvText(value: String): String = value.replace("\"", "\"\"")
}
