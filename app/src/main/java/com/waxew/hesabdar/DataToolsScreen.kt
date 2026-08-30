package com.waxew.hesabdar

import android.app.Activity
import android.os.Process
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.waxew.hesabdar.data.AppDatabase
import com.waxew.hesabdar.data.BackupManager
import com.waxew.hesabdar.data.DataExportManager
import com.waxew.hesabdar.data.PortableBackupManager
import com.waxew.hesabdar.security.PinSecurityManager
import com.waxew.hesabdar.settings.BusinessProfile
import com.waxew.hesabdar.settings.BusinessSettings
import com.waxew.hesabdar.util.PersianDateConverter
import java.io.File

/** مرکز تنظیمات، گزارش حرفه‌ای، داده، خروجی و امنیت. */
@Composable
fun DataToolsScreen(database: AppDatabase, modifier: Modifier = Modifier) {
    var section by remember { mutableStateOf("BUSINESS") }
    Column(modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("تنظیمات و ابزارها")
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ToolTab("BUSINESS", "کسب‌وکار", section) { section = it }
            ToolTab("REPORTS", "گزارش حرفه‌ای", section) { section = it }
            ToolTab("DATA", "داده و خروجی", section) { section = it }
            ToolTab("SECURITY", "امنیت", section) { section = it }
        }
        when (section) {
            "BUSINESS" -> BusinessSection()
            "REPORTS" -> ProfessionalReportsScreen(database, Modifier.fillMaxSize())
            "DATA" -> DataSection(database)
            else -> SecuritySection()
        }
    }
}

@Composable
private fun ToolTab(code: String, title: String, selected: String, onSelect: (String) -> Unit) {
    OutlinedButton(onClick = { onSelect(code) }) { Text(if (selected == code) "✓ $title" else title) }
}

/** مشخصات کسب‌وکار، واحد پول و سال مالی نمایشی. */
@Composable
private fun BusinessSection() {
    val context = LocalContext.current
    val settings = remember { BusinessSettings(context) }
    val initial = remember { settings.load() }
    var name by remember { mutableStateOf(initial.name) }
    var phone by remember { mutableStateOf(initial.phone) }
    var address by remember { mutableStateOf(initial.address) }
    var currency by remember { mutableStateOf(initial.currency) }
    var fiscalYear by remember { mutableStateOf(initial.fiscalYearTitle) }
    var prefix by remember { mutableStateOf(initial.invoicePrefix) }
    var message by remember { mutableStateOf("") }
    val today = remember { PersianDateConverter.now().toString() }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("امروز: $today") }
        item { TextField(name, { name = it }, label = { Text("نام کسب‌وکار") }, modifier = Modifier.fillMaxWidth()) }
        item { TextField(phone, { phone = it }, label = { Text("تلفن") }, modifier = Modifier.fillMaxWidth()) }
        item { TextField(address, { address = it }, label = { Text("آدرس") }, modifier = Modifier.fillMaxWidth()) }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { currency = "TOMAN" }, modifier = Modifier.weight(1f)) { Text(if (currency == "TOMAN") "✓ تومان" else "تومان") }
                Button(onClick = { currency = "RIAL" }, modifier = Modifier.weight(1f)) { Text(if (currency == "RIAL") "✓ ریال" else "ریال") }
            }
        }
        item { TextField(fiscalYear, { fiscalYear = it }, label = { Text("عنوان سال مالی") }, modifier = Modifier.fillMaxWidth()) }
        item { TextField(prefix, { prefix = it.take(12) }, label = { Text("پیشوند شماره فاکتور") }, modifier = Modifier.fillMaxWidth()) }
        item {
            Button(onClick = {
                runCatching {
                    settings.save(BusinessProfile(name, phone, address, currency, fiscalYear, prefix))
                }.onSuccess { message = "تنظیمات کسب‌وکار ذخیره شد." }
                    .onFailure { message = it.message ?: "خطا در ذخیره تنظیمات" }
            }, modifier = Modifier.fillMaxWidth()) { Text("ذخیره تنظیمات") }
        }
        if (message.isNotBlank()) item { Text(message) }
    }
}

/**
 * Backup، Restore و خروجی‌های فایل.
 * Backup محلی سریع برای همین دستگاه و HDBX رمزگذاری‌شده برای انتقال امن بین دستگاه‌ها در دسترس است.
 */
@Composable
private fun DataSection(database: AppDatabase) {
    val context = LocalContext.current
    val backupManager = remember(database) { BackupManager(context, database) }
    val portableBackupManager = remember(database) { PortableBackupManager(context, database) }
    val exportManager = remember(database) { DataExportManager(context, database) }
    var message by remember { mutableStateOf("") }
    var refresh by remember { mutableStateOf(0) }
    var backupPassword by remember { mutableStateOf("") }
    val backupDir = remember(refresh) { File(context.getExternalFilesDir(null) ?: context.filesDir, "backups") }
    val backups = remember(refresh) {
        backupDir.listFiles()
            ?.filter { it.isFile && (it.extension == "hdb" || it.extension == "hdbx") }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("پشتیبان‌گیری") }
        item {
            Button(onClick = {
                runCatching { backupManager.createBackup() }
                    .onSuccess { message = "Backup محلی ساخته شد: ${it.name}"; refresh++ }
                    .onFailure { message = it.message ?: "خطا در Backup" }
            }, modifier = Modifier.fillMaxWidth()) { Text("ساخت Backup محلی سریع") }
        }

        item {
            TextField(
                value = backupPassword,
                onValueChange = { backupPassword = it.take(128) },
                label = { Text("رمز Backup قابل‌انتقال (حداقل ۸ کاراکتر)") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            Button(onClick = {
                runCatching { portableBackupManager.createEncryptedBackup(backupPassword) }
                    .onSuccess { message = "Backup رمزگذاری‌شده ساخته شد: ${it.name}"; refresh++ }
                    .onFailure { message = it.message ?: "خطا در Backup رمزگذاری‌شده" }
            }, modifier = Modifier.fillMaxWidth()) { Text("ساخت Backup امن HDBX") }
        }
        item { Text("فایل HDBX با AES-GCM رمزگذاری می‌شود و روی دستگاه دیگر نیز با همین رمز قابل بازیابی است.") }

        item {
            Button(onClick = {
                runCatching { exportManager.exportInvoicesCsv() }
                    .onSuccess { message = "CSV ساخته شد: ${it.name}" }
                    .onFailure { message = it.message ?: "خطا در CSV" }
            }, modifier = Modifier.fillMaxWidth()) { Text("خروجی CSV فاکتورها") }
        }
        item {
            Button(onClick = {
                runCatching { exportManager.exportSummaryPdf() }
                    .onSuccess { message = "PDF ساخته شد: ${it.name}" }
                    .onFailure { message = it.message ?: "خطا در PDF" }
            }, modifier = Modifier.fillMaxWidth()) { Text("گزارش PDF خلاصه") }
        }
        if (message.isNotBlank()) item { Text(message) }

        item { Text("Backupهای موجود") }
        if (backups.isEmpty()) item { Text("هنوز Backup ساخته نشده است.") }
        items(backups, key = { it.absolutePath }) { file ->
            BackupCard(
                file = file,
                localManager = backupManager,
                portableManager = portableBackupManager,
                password = backupPassword,
                onError = { message = it }
            )
        }
    }
}

/** تنظیم و بررسی PIN محلی. */
@Composable
private fun SecuritySection() {
    val context = LocalContext.current
    val security = remember { PinSecurityManager(context) }
    var pin by remember { mutableStateOf("") }
    var pinCheck by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var hasPin by remember { mutableStateOf(security.hasPin()) }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text(if (hasPin) "قفل PIN فعال است." else "قفل PIN غیرفعال است.") }
        item { TextField(pin, { pin = it.filter(Char::isDigit).take(12) }, label = { Text("PIN جدید 4 تا 12 رقمی") }, modifier = Modifier.fillMaxWidth()) }
        item {
            Button(onClick = {
                runCatching { security.setPin(pin) }
                    .onSuccess { pin = ""; hasPin = true; message = "PIN ذخیره شد. از اجرای بعدی برنامه قفل می‌شود." }
                    .onFailure { message = it.message ?: "PIN نامعتبر" }
            }, modifier = Modifier.fillMaxWidth()) { Text("فعال / تغییر PIN") }
        }
        item { TextField(pinCheck, { pinCheck = it.filter(Char::isDigit).take(12) }, label = { Text("آزمایش PIN") }, modifier = Modifier.fillMaxWidth()) }
        item { OutlinedButton(onClick = { message = if (security.verifyPin(pinCheck)) "PIN صحیح است." else "PIN اشتباه است." }, modifier = Modifier.fillMaxWidth()) { Text("بررسی PIN") } }
        if (hasPin) item { OutlinedButton(onClick = { security.clearPin(); hasPin = false; message = "قفل PIN غیرفعال شد." }, modifier = Modifier.fillMaxWidth()) { Text("حذف PIN") } }
        if (message.isNotBlank()) item { Text(message) }
    }
}

/** کارت یک Backup؛ نوع فایل تعیین می‌کند Restore ساده یا رمزگشایی‌شده انجام شود. */
@Composable
private fun BackupCard(
    file: File,
    localManager: BackupManager,
    portableManager: PortableBackupManager,
    password: String,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    val persianDate = remember(file.lastModified()) { PersianDateConverter.fromMillis(file.lastModified()).toString() }
    val encrypted = file.extension == "hdbx"
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(file.name)
            Text(if (encrypted) "نوع: رمزگذاری‌شده و قابل‌انتقال" else "نوع: محلی")
            Text("تاریخ: $persianDate")
            Text("${file.length() / 1024} KB")
            OutlinedButton(onClick = {
                runCatching {
                    if (encrypted) portableManager.restoreEncryptedBackup(file, password)
                    else localManager.restoreBackup(file)
                }.onSuccess {
                    // دیتابیس Singleton بعد از Restore باید در Process جدید باز شود.
                    (context as? Activity)?.finishAffinity()
                    Process.killProcess(Process.myPid())
                }.onFailure { error ->
                    onError(error.message ?: "خطا در بازیابی Backup")
                }
            }) { Text(if (encrypted) "بازیابی با رمز و راه‌اندازی مجدد" else "بازیابی و راه‌اندازی مجدد") }
        }
    }
}
