package com.waxew.hesabdar

import android.app.Activity
import android.os.Process
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.dp
import com.waxew.hesabdar.data.AppDatabase
import com.waxew.hesabdar.data.BackupManager
import com.waxew.hesabdar.data.DataExportManager
import com.waxew.hesabdar.security.PinSecurityManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** مرکز ابزارهای داده، خروجی و امنیت. */
@Composable
fun DataToolsScreen(database: AppDatabase, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val backupManager = remember(database) { BackupManager(context, database) }
    val exportManager = remember(database) { DataExportManager(context, database) }
    val security = remember { PinSecurityManager(context) }
    var message by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var pinCheck by remember { mutableStateOf("") }
    var refresh by remember { mutableStateOf(0) }

    val backupDir = remember(refresh) { File(context.getExternalFilesDir(null) ?: context.filesDir, "backups") }
    val backups = remember(refresh) {
        backupDir.listFiles()?.filter { it.isFile && it.extension == "hdb" }?.sortedByDescending { it.lastModified() }.orEmpty()
    }

    LazyColumn(modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("پشتیبان‌گیری، خروجی و امنیت") }

        item {
            Button(onClick = {
                runCatching { backupManager.createBackup() }
                    .onSuccess { message = "Backup ساخته شد: ${it.name}"; refresh++ }
                    .onFailure { message = it.message ?: "خطا در Backup" }
            }, modifier = Modifier.fillMaxWidth()) { Text("ساخت Backup محلی") }
        }

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
        items(backups, key = { it.absolutePath }) { file -> BackupCard(file, backupManager) }

        item { Text("قفل PIN") }
        item { Text(if (security.hasPin()) "PIN فعال است." else "PIN هنوز فعال نشده است.") }
        item { TextField(pin, { pin = it.filter(Char::isDigit).take(12) }, label = { Text("PIN جدید 4 تا 12 رقمی") }, modifier = Modifier.fillMaxWidth()) }
        item {
            Button(onClick = {
                runCatching { security.setPin(pin) }
                    .onSuccess { pin = ""; message = "PIN ذخیره شد." }
                    .onFailure { message = it.message ?: "PIN نامعتبر" }
            }, modifier = Modifier.fillMaxWidth()) { Text("فعال/تغییر PIN") }
        }
        item { TextField(pinCheck, { pinCheck = it.filter(Char::isDigit).take(12) }, label = { Text("آزمایش PIN") }, modifier = Modifier.fillMaxWidth()) }
        item {
            OutlinedButton(onClick = { message = if (security.verifyPin(pinCheck)) "PIN صحیح است." else "PIN اشتباه است." }, modifier = Modifier.fillMaxWidth()) { Text("بررسی PIN") }
        }
        if (security.hasPin()) {
            item { OutlinedButton(onClick = { security.clearPin(); message = "قفل PIN غیرفعال شد." }, modifier = Modifier.fillMaxWidth()) { Text("حذف PIN") } }
        }
    }
}

@Composable
private fun BackupCard(file: File, manager: BackupManager) {
    val context = LocalContext.current
    val stamp = remember(file.lastModified()) { SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US).format(Date(file.lastModified())) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(file.name)
            Text(stamp)
            Text("${file.length() / 1024} KB")
            OutlinedButton(onClick = {
                manager.restoreBackup(file)
                (context as? Activity)?.finishAffinity()
                Process.killProcess(Process.myPid())
            }) { Text("بازیابی و بستن برنامه") }
        }
    }
}
