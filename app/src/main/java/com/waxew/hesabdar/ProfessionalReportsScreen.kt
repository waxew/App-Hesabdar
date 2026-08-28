package com.waxew.hesabdar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.waxew.hesabdar.data.AppDatabase
import java.text.NumberFormat
import java.util.Locale

/** گزارش‌های حرفه‌ای حسابداری که مستقیماً از اسناد و گردش‌های ثبت‌شده خوانده می‌شوند. */
@Composable
fun ProfessionalReportsScreen(database: AppDatabase, modifier: Modifier = Modifier) {
    val trial by database.reportingDao().observeTrialBalance().collectAsState(initial = emptyList())
    val profits by database.reportingDao().observeProductProfit().collectAsState(initial = emptyList())
    val debitTotal by database.reportingDao().observeJournalDebitTotal().collectAsState(initial = 0L)
    val creditTotal by database.reportingDao().observeJournalCreditTotal().collectAsState(initial = 0L)
    val documents by database.ledgerDao().observeDocuments().collectAsState(initial = emptyList())
    val f = remember { NumberFormat.getNumberInstance(Locale.US) }

    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("گزارش‌های حسابداری") }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("کنترل توازن دفتر")
                    Text("جمع بدهکار: ${f.format(debitTotal)}")
                    Text("جمع بستانکار: ${f.format(creditTotal)}")
                    Text(if (debitTotal == creditTotal) "دفتر تراز است." else "هشدار: دفتر تراز نیست.")
                }
            }
        }
        item { Text("تراز آزمایشی") }
        if (trial.isEmpty()) item { Text("هنوز سند حسابداری ثبت نشده است.") }
        items(trial, key = { it.accountId }) { row ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp)) {
                    Text("${row.code} - ${row.name}")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("گردش بدهکار ${f.format(row.debitTurnover)}")
                        Text("گردش بستانکار ${f.format(row.creditTurnover)}")
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("مانده بدهکار ${f.format(row.debitBalance)}")
                        Text("مانده بستانکار ${f.format(row.creditBalance)}")
                    }
                }
            }
        }
        item { Text("سود برآوردی کالاها") }
        if (profits.isEmpty()) item { Text("برای محاسبه سود، ابتدا فروش ثبت کنید.") }
        items(profits, key = { it.productId }) { row ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp)) {
                    Text(row.productName)
                    Text("تعداد فروش خالص: ${f.format(row.soldQuantity)}")
                    Text("فروش: ${f.format(row.salesAmount)} تومان")
                    Text("بهای برآوردی: ${f.format(row.estimatedCost)} تومان")
                    Text("سود برآوردی: ${f.format(row.estimatedProfit)} تومان")
                }
            }
        }
        item { Text("آخرین اسناد حسابداری") }
        items(documents.take(30), key = { it.id }) { document ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp)) {
                    Text(document.number)
                    Text(document.description)
                    Text("منبع: ${document.sourceType}")
                }
            }
        }
    }
}
