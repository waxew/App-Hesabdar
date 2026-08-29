package com.waxew.hesabdar

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.waxew.hesabdar.data.AdvancedAccountingRepository
import com.waxew.hesabdar.data.AppDatabase
import com.waxew.hesabdar.data.CheckEntity
import com.waxew.hesabdar.data.InstallmentEntity
import com.waxew.hesabdar.data.LedgerAccountEntity
import com.waxew.hesabdar.data.PersonEntity
import com.waxew.hesabdar.data.TreasuryAccountEntity
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

/**
 * مرکز امکانات حرفه‌ای نسخه 0.3.0.
 * این صفحه از چند زیرصفحه سبک تشکیل شده تا خزانه، دریافت/پرداخت، هزینه/درآمد، چک، اقساط و کدینگ حساب‌ها قابل تست باشند.
 */
@Composable
fun AdvancedAccountingHub(
    database: AppDatabase,
    persons: List<PersonEntity>,
    modifier: Modifier = Modifier
) {
    var section by remember { mutableStateOf("TREASURY") }

    Column(modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("مرکز حسابداری حرفه‌ای")
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            SectionButton("TREASURY", "خزانه", section) { section = it }
            SectionButton("CASH", "دریافت/پرداخت", section) { section = it }
            SectionButton("CHECK", "چک", section) { section = it }
            SectionButton("INSTALLMENT", "اقساط", section) { section = it }
            SectionButton("LEDGER", "کدینگ", section) { section = it }
            SectionButton("REPORT", "گزارش", section) { section = it }
        }

        when (section) {
            "TREASURY" -> TreasurySection(database)
            "CASH" -> CashSection(database, persons)
            "CHECK" -> ChecksSection(database, persons)
            "INSTALLMENT" -> InstallmentsSection(database, persons)
            "LEDGER" -> LedgerSection(database)
            else -> ReportsSection(database)
        }
    }
}

@Composable
private fun SectionButton(code: String, title: String, selected: String, onSelect: (String) -> Unit) {
    OutlinedButton(onClick = { onSelect(code) }) {
        Text(if (selected == code) "✓ $title" else title)
    }
}

/** ایجاد صندوق و حساب بانکی. */
@Composable
private fun TreasurySection(database: AppDatabase) {
    val repo = remember(database) { AdvancedAccountingRepository(database) }
    val scope = rememberCoroutineScope()
    val accounts by database.treasuryDao().observeAccounts().collectAsState(initial = emptyList())
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("CASH") }
    var opening by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("صندوق و حساب‌های بانکی") }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { type = "CASH" }, modifier = Modifier.weight(1f)) { Text(if (type == "CASH") "✓ صندوق" else "صندوق") }
                Button(onClick = { type = "BANK" }, modifier = Modifier.weight(1f)) { Text(if (type == "BANK") "✓ بانک" else "بانک") }
            }
        }
        item { TextField(name, { name = it }, label = { Text("نام حساب") }, modifier = Modifier.fillMaxWidth()) }
        item { TextField(opening, { opening = digitsOnly(it) }, label = { Text("مانده افتتاحیه") }, modifier = Modifier.fillMaxWidth()) }
        item {
            Button(
                onClick = {
                    scope.launch {
                        runCatching { repo.createTreasuryAccount(name, type, opening.toLongOrNull() ?: 0L) }
                            .onSuccess { name = ""; opening = ""; message = "حساب خزانه ثبت شد." }
                            .onFailure { message = it.message ?: "خطا" }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("ثبت حساب") }
        }
        if (message.isNotBlank()) item { Text(message) }
        items(accounts, key = { it.id }) { account -> TreasuryCard(database, account) }
    }
}

@Composable
private fun TreasuryCard(database: AppDatabase, account: TreasuryAccountEntity) {
    val balance by database.treasuryDao().observeBalance(account.id).collectAsState(initial = account.openingBalance)
    val f = remember { NumberFormat.getNumberInstance(Locale.US) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(account.name)
            Text(if (account.type == "BANK") "حساب بانکی" else "صندوق")
            Text("مانده: ${f.format(balance)} تومان")
        }
    }
}

/** دریافت/پرداخت آزاد و هزینه/درآمد. */
@Composable
private fun CashSection(database: AppDatabase, persons: List<PersonEntity>) {
    val repo = remember(database) { AdvancedAccountingRepository(database) }
    val scope = rememberCoroutineScope()
    val accounts by database.treasuryDao().observeAccounts().collectAsState(initial = emptyList())
    val entries by database.cashEntryDao().observeAll().collectAsState(initial = emptyList())
    var kind by remember { mutableStateOf("RECEIVE") }
    var personId by remember { mutableStateOf<Long?>(null) }
    var accountId by remember { mutableStateOf<Long?>(null) }
    var amount by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    val f = remember { NumberFormat.getNumberInstance(Locale.US) }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("RECEIVE" to "دریافت", "PAY" to "پرداخت", "INCOME" to "درآمد", "EXPENSE" to "هزینه").forEach { pair ->
                    OutlinedButton(onClick = { kind = pair.first }) { Text(if (kind == pair.first) "✓ ${pair.second}" else pair.second) }
                }
            }
        }
        item {
            Text("حساب خزانه")
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                accounts.forEach { a -> OutlinedButton(onClick = { accountId = a.id }) { Text(if (accountId == a.id) "✓ ${a.name}" else a.name) } }
            }
        }
        if (kind == "RECEIVE" || kind == "PAY") {
            item {
                Text("طرف حساب")
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { personId = null }) { Text(if (personId == null) "✓ متفرقه" else "متفرقه") }
                    persons.forEach { p -> OutlinedButton(onClick = { personId = p.id }) { Text(if (personId == p.id) "✓ ${p.name}" else p.name) } }
                }
            }
        }
        item { TextField(amount, { amount = digitsOnly(it) }, label = { Text("مبلغ") }, modifier = Modifier.fillMaxWidth()) }
        if (kind == "INCOME" || kind == "EXPENSE") item { TextField(category, { category = it }, label = { Text("دسته‌بندی") }, modifier = Modifier.fillMaxWidth()) }
        item { TextField(note, { note = it }, label = { Text("توضیحات") }, modifier = Modifier.fillMaxWidth()) }
        item {
            Button(
                onClick = {
                    scope.launch {
                        val value = amount.toLongOrNull() ?: 0L
                        runCatching {
                            if (kind == "RECEIVE" || kind == "PAY") repo.postStandalonePayment(kind, personId, accountId, value, note)
                            else repo.postCashEntry(kind, accountId, value, category, note)
                        }.onSuccess { amount = ""; note = ""; message = "عملیات ثبت شد." }
                            .onFailure { message = it.message ?: "خطا" }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("ثبت عملیات") }
        }
        if (message.isNotBlank()) item { Text(message) }
        items(entries.take(20), key = { it.id }) { e ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp)) {
                    Text(kindFa(e.kind))
                    Text("${f.format(e.amount)} تومان")
                    if (e.category.isNotBlank()) Text(e.category)
                    if (e.note.isNotBlank()) Text(e.note)
                }
            }
        }
    }
}

/** ثبت و پیگیری چک‌های دریافتی و پرداختی. */
@Composable
private fun ChecksSection(database: AppDatabase, persons: List<PersonEntity>) {
    val repo = remember(database) { AdvancedAccountingRepository(database) }
    val scope = rememberCoroutineScope()
    val checks by database.checkDao().observeAll().collectAsState(initial = emptyList())
    var direction by remember { mutableStateOf("RECEIVE") }
    var personId by remember { mutableStateOf<Long?>(null) }
    var amount by remember { mutableStateOf("") }
    var bank by remember { mutableStateOf("") }
    var number by remember { mutableStateOf("") }
    var sayad by remember { mutableStateOf("") }
    var dueDays by remember { mutableStateOf("30") }
    var message by remember { mutableStateOf("") }
    val f = remember { NumberFormat.getNumberInstance(Locale.US) }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { direction = "RECEIVE" }, modifier = Modifier.weight(1f)) { Text(if (direction == "RECEIVE") "✓ دریافتی" else "دریافتی") }
                Button(onClick = { direction = "PAY" }, modifier = Modifier.weight(1f)) { Text(if (direction == "PAY") "✓ پرداختی" else "پرداختی") }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { personId = null }) { Text("متفرقه") }
                persons.forEach { p -> OutlinedButton(onClick = { personId = p.id }) { Text(if (personId == p.id) "✓ ${p.name}" else p.name) } }
            }
        }
        item { TextField(amount, { amount = digitsOnly(it) }, label = { Text("مبلغ چک") }, modifier = Modifier.fillMaxWidth()) }
        item { TextField(bank, { bank = it }, label = { Text("بانک") }, modifier = Modifier.fillMaxWidth()) }
        item { TextField(number, { number = it }, label = { Text("شماره چک") }, modifier = Modifier.fillMaxWidth()) }
        item { TextField(sayad, { sayad = digitsOnly(it) }, label = { Text("شناسه صیادی") }, modifier = Modifier.fillMaxWidth()) }
        item { TextField(dueDays, { dueDays = digitsOnly(it) }, label = { Text("سررسید چند روز بعد") }, modifier = Modifier.fillMaxWidth()) }
        item {
            Button(
                onClick = {
                    scope.launch {
                        val dueAt = System.currentTimeMillis() + (dueDays.toLongOrNull() ?: 0L) * 86_400_000L
                        runCatching { repo.createCheck(direction, personId, amount.toLongOrNull() ?: 0, bank, number, sayad, dueAt) }
                            .onSuccess { amount = ""; number = ""; sayad = ""; message = "چک ثبت شد." }
                            .onFailure { message = it.message ?: "خطا" }
                    }
                }, modifier = Modifier.fillMaxWidth()
            ) { Text("ثبت چک") }
        }
        if (message.isNotBlank()) item { Text(message) }
        items(checks, key = { it.id }) { check -> CheckCard(repo, check, f) }
    }
}

@Composable
private fun CheckCard(repo: AdvancedAccountingRepository, check: CheckEntity, formatter: NumberFormat) {
    val scope = rememberCoroutineScope()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${if (check.direction == "RECEIVE") "چک دریافتی" else "چک پرداختی"} - ${formatter.format(check.amount)} تومان")
            Text("${check.bankName} / ${check.checkNumber}")
            Text("وضعیت: ${checkStatusFa(check.status)}")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { scope.launch { repo.changeCheckStatus(check.id, "CLEARED") } }) { Text("وصول") }
                OutlinedButton(onClick = { scope.launch { repo.changeCheckStatus(check.id, "BOUNCED") } }) { Text("برگشت") }
            }
        }
    }
}

/** برنامه اقساط؛ برای نسخه فعلی سررسیدها با فاصله 30 روز ساخته می‌شوند. */
@Composable
private fun InstallmentsSection(database: AppDatabase, persons: List<PersonEntity>) {
    val repo = remember(database) { AdvancedAccountingRepository(database) }
    val scope = rememberCoroutineScope()
    val list by database.installmentDao().observeAll().collectAsState(initial = emptyList())
    var personId by remember { mutableStateOf<Long?>(null) }
    var title by remember { mutableStateOf("") }
    var total by remember { mutableStateOf("") }
    var count by remember { mutableStateOf("1") }
    var message by remember { mutableStateOf("") }
    val f = remember { NumberFormat.getNumberInstance(Locale.US) }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { personId = null }) { Text("بدون طرف حساب") }
                persons.forEach { p -> OutlinedButton(onClick = { personId = p.id }) { Text(if (personId == p.id) "✓ ${p.name}" else p.name) } }
            }
        }
        item { TextField(title, { title = it }, label = { Text("عنوان اقساط") }, modifier = Modifier.fillMaxWidth()) }
        item { TextField(total, { total = digitsOnly(it) }, label = { Text("مبلغ کل") }, modifier = Modifier.fillMaxWidth()) }
        item { TextField(count, { count = digitsOnly(it) }, label = { Text("تعداد اقساط") }, modifier = Modifier.fillMaxWidth()) }
        item {
            Button(onClick = {
                scope.launch {
                    val n = (count.toIntOrNull() ?: 0).coerceAtMost(120)
                    val dates = (1..n).map { System.currentTimeMillis() + it * 30L * 86_400_000L }
                    runCatching { repo.createInstallments(personId, null, title, total.toLongOrNull() ?: 0, dates) }
                        .onSuccess { title = ""; total = ""; message = "برنامه اقساط ساخته شد." }
                        .onFailure { message = it.message ?: "خطا" }
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("ساخت اقساط") }
        }
        if (message.isNotBlank()) item { Text(message) }
        items(list, key = { it.id }) { item -> InstallmentCard(repo, item, f) }
    }
}

@Composable
private fun InstallmentCard(repo: AdvancedAccountingRepository, item: InstallmentEntity, formatter: NumberFormat) {
    val scope = rememberCoroutineScope()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(item.title)
            Text("${formatter.format(item.paidAmount)} / ${formatter.format(item.amount)} تومان")
            Text("وضعیت: ${if (item.status == "PAID") "پرداخت‌شده" else if (item.status == "PARTIAL") "پرداخت ناقص" else "در انتظار"}")
            if (item.status != "PAID") {
                OutlinedButton(onClick = { scope.launch { repo.payInstallment(item, item.amount - item.paidAmount) } }) { Text("تسویه این قسط") }
            }
        }
    }
}

/** کدینگ ساده حسابداری. */
@Composable
private fun LedgerSection(database: AppDatabase) {
    val accounts by database.ledgerDao().observeAccounts().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var nature by remember { mutableStateOf("DEBIT") }
    var message by remember { mutableStateOf("") }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { TextField(code, { code = digitsOnly(it) }, label = { Text("کد حساب") }, modifier = Modifier.fillMaxWidth()) }
        item { TextField(name, { name = it }, label = { Text("نام حساب") }, modifier = Modifier.fillMaxWidth()) }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { nature = "DEBIT" }, modifier = Modifier.weight(1f)) { Text(if (nature == "DEBIT") "✓ بدهکار" else "بدهکار") }
                Button(onClick = { nature = "CREDIT" }, modifier = Modifier.weight(1f)) { Text(if (nature == "CREDIT") "✓ بستانکار" else "بستانکار") }
            }
        }
        item {
            Button(onClick = {
                scope.launch {
                    runCatching {
                        require(code.isNotBlank() && name.isNotBlank()) { "کد و نام حساب الزامی است." }
                        database.ledgerDao().insertAccount(LedgerAccountEntity(code = code, name = name.trim(), level = "GENERAL", nature = nature))
                    }.onSuccess { code = ""; name = ""; message = "حساب ثبت شد." }
                        .onFailure { message = it.message ?: "خطا" }
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("ثبت حساب") }
        }
        if (message.isNotBlank()) item { Text(message) }
        items(accounts, key = { it.id }) { a ->
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(10.dp)) { Text("${a.code} - ${a.name}"); Text(if (a.nature == "DEBIT") "ماهیت بدهکار" else "ماهیت بستانکار") } }
        }
    }
}

/** گزارش مدیریتی پایه از داده‌های واقعی. */
@Composable
private fun ReportsSection(database: AppDatabase) {
    val sales by database.dashboardDao().observeSalesTotal().collectAsState(initial = 0L)
    val purchases by database.dashboardDao().observePurchasesTotal().collectAsState(initial = 0L)
    val receivables by database.dashboardDao().observeReceivables().collectAsState(initial = 0L)
    val payables by database.dashboardDao().observePayables().collectAsState(initial = 0L)
    val otherIncome by database.cashEntryDao().observeOtherIncome().collectAsState(initial = 0L)
    val expenses by database.cashEntryDao().observeExpenses().collectAsState(initial = 0L)
    val audits by database.auditDao().observeRecent(20).collectAsState(initial = emptyList())
    val f = remember { NumberFormat.getNumberInstance(Locale.US) }
    val gross = sales + otherIncome - purchases - expenses

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { ReportCard("فروش", sales, f) }
        item { ReportCard("خرید", purchases, f) }
        item { ReportCard("سایر درآمد", otherIncome, f) }
        item { ReportCard("هزینه", expenses, f) }
        item { ReportCard("خالص ساده فعلی", gross, f) }
        item { ReportCard("مطالبات", receivables, f) }
        item { ReportCard("بدهی", payables, f) }
        item { Text("آخرین رویدادهای مالی") }
        items(audits, key = { it.id }) { a -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(10.dp)) { Text("${a.action} / ${a.entityType}"); if (a.detail.isNotBlank()) Text(a.detail) } } }
    }
}

@Composable
private fun ReportCard(title: String, value: Long, formatter: NumberFormat) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) { Text(title); Text("${formatter.format(value)} تومان") } }
}

private fun digitsOnly(value: String): String = value.filter(Char::isDigit)
private fun kindFa(kind: String): String = when (kind) { "RECEIVE" -> "دریافت"; "PAY" -> "پرداخت"; "INCOME" -> "درآمد"; "EXPENSE" -> "هزینه"; else -> kind }
private fun checkStatusFa(status: String): String = when (status) { "PENDING" -> "در انتظار"; "CLEARED" -> "وصول شده"; "BOUNCED" -> "برگشتی"; "TRANSFERRED" -> "واگذار شده"; "CANCELLED" -> "باطل"; else -> status }
