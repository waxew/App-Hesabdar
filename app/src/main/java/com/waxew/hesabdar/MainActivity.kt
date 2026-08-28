package com.waxew.hesabdar

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.waxew.hesabdar.data.AccountingRepository
import com.waxew.hesabdar.data.AppDatabase
import com.waxew.hesabdar.data.InvoiceDraftLine
import com.waxew.hesabdar.data.InvoiceEntity
import com.waxew.hesabdar.data.PersonEntity
import com.waxew.hesabdar.data.ProductEntity
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

/** Activity اصلی حسابدار؛ تمام اطلاعات عملیاتی از Room محلی خوانده می‌شوند. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val database = AppDatabase.get(this)
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    HesabdarApp(database)
                }
            }
        }
    }
}

/** پوسته اصلی نسخه 0.3.0 با پنج ناحیه اصلی. */
@Composable
private fun HesabdarApp(database: AppDatabase) {
    var tab by remember { mutableIntStateOf(0) }
    val persons by database.personDao().observeAll().collectAsState(initial = emptyList())
    val products by database.productDao().observeAll().collectAsState(initial = emptyList())
    val invoices by database.invoiceDao().observeAll().collectAsState(initial = emptyList())
    val salesTotal by database.dashboardDao().observeSalesTotal().collectAsState(initial = 0L)
    val purchasesTotal by database.dashboardDao().observePurchasesTotal().collectAsState(initial = 0L)
    val receivables by database.dashboardDao().observeReceivables().collectAsState(initial = 0L)
    val payables by database.dashboardDao().observePayables().collectAsState(initial = 0L)
    val expenses by database.cashEntryDao().observeExpenses().collectAsState(initial = 0L)
    val otherIncome by database.cashEntryDao().observeOtherIncome().collectAsState(initial = 0L)
    val pendingChecks by database.checkDao().observePending().collectAsState(initial = emptyList())

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Default.Home, null) }, label = { Text("خانه") })
                NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.Default.People, null) }, label = { Text("اشخاص") })
                NavigationBarItem(tab == 2, { tab = 2 }, { Icon(Icons.Default.Inventory2, null) }, label = { Text("کالا") })
                NavigationBarItem(tab == 3, { tab = 3 }, { Icon(Icons.Default.AccountBalanceWallet, null) }, label = { Text("معاملات") })
                NavigationBarItem(tab == 4, { tab = 4 }, { Icon(Icons.Default.AccountBalance, null) }, label = { Text("حسابداری") })
            }
        }
    ) { padding ->
        when (tab) {
            0 -> Dashboard(
                personCount = persons.size,
                productCount = products.size,
                invoiceCount = invoices.size,
                salesTotal = salesTotal,
                purchasesTotal = purchasesTotal,
                receivables = receivables,
                payables = payables,
                otherIncome = otherIncome,
                expenses = expenses,
                pendingCheckCount = pendingChecks.size,
                modifier = Modifier.padding(padding)
            )
            1 -> PersonsScreen(database, persons, Modifier.padding(padding))
            2 -> ProductsScreen(database, products, Modifier.padding(padding))
            3 -> TransactionsScreen(database, persons, products, invoices, Modifier.padding(padding))
            else -> AdvancedAccountingHub(database, persons, Modifier.padding(padding))
        }
    }
}

/** داشبورد واقعی با شاخص‌های دیتابیس و یک سود ساده مدیریتی. */
@Composable
private fun Dashboard(
    personCount: Int,
    productCount: Int,
    invoiceCount: Int,
    salesTotal: Long,
    purchasesTotal: Long,
    receivables: Long,
    payables: Long,
    otherIncome: Long,
    expenses: Long,
    pendingCheckCount: Int,
    modifier: Modifier = Modifier
) {
    val f = remember { NumberFormat.getNumberInstance(Locale.US) }
    val netSimple = salesTotal + otherIncome - purchasesTotal - expenses
    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("حسابدار", style = MaterialTheme.typography.headlineMedium); Text("نسخه 0.3.0 — حسابداری آفلاین روی دستگاه") }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { SummaryCard("اشخاص", personCount.toString(), Modifier.weight(1f)); SummaryCard("کالا", productCount.toString(), Modifier.weight(1f)) } }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { SummaryCard("فاکتورها", invoiceCount.toString(), Modifier.weight(1f)); SummaryCard("چک باز", pendingCheckCount.toString(), Modifier.weight(1f)) } }
        item { SummaryCard("فروش", "${f.format(salesTotal)} تومان", Modifier.fillMaxWidth()) }
        item { SummaryCard("خرید", "${f.format(purchasesTotal)} تومان", Modifier.fillMaxWidth()) }
        item { SummaryCard("مطالبات", "${f.format(receivables)} تومان", Modifier.fillMaxWidth()) }
        item { SummaryCard("بدهی", "${f.format(payables)} تومان", Modifier.fillMaxWidth()) }
        item { SummaryCard("درآمد جانبی", "${f.format(otherIncome)} تومان", Modifier.fillMaxWidth()) }
        item { SummaryCard("هزینه", "${f.format(expenses)} تومان", Modifier.fillMaxWidth()) }
        item { SummaryCard("خالص ساده", "${f.format(netSimple)} تومان", Modifier.fillMaxWidth()) }
        item { Text("برای خزانه، چک، اقساط، دریافت/پرداخت مستقل، کدینگ و گزارش‌ها وارد بخش حسابداری شوید.") }
    }
}

@Composable
private fun SummaryCard(title: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) { Column(Modifier.padding(12.dp)) { Text(title); Text(value, style = MaterialTheme.typography.titleMedium) } }
}

/** ثبت ساده طرف‌حساب. */
@Composable
private fun PersonsScreen(database: AppDatabase, persons: List<PersonEntity>, modifier: Modifier = Modifier) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    Column(modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("اشخاص و طرف‌حساب‌ها", style = MaterialTheme.typography.headlineSmall)
        TextField(name, { name = it }, label = { Text("نام") }, modifier = Modifier.fillMaxWidth())
        TextField(phone, { phone = it }, label = { Text("شماره تماس") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { if (name.isNotBlank()) scope.launch { database.personDao().insert(PersonEntity(name = name.trim(), phone = phone.trim())); name = ""; phone = "" } }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, null); Text(" افزودن شخص") }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(persons, key = { it.id }) { p -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(10.dp)) { Text(p.name); if (p.phone.isNotBlank()) Text(p.phone) } } }
        }
    }
}

/** کالا با قیمت خرید/فروش و موجودی اولیه. */
@Composable
private fun ProductsScreen(database: AppDatabase, products: List<ProductEntity>, modifier: Modifier = Modifier) {
    var name by remember { mutableStateOf("") }
    var buy by remember { mutableStateOf("") }
    var sell by remember { mutableStateOf("") }
    var stock by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val f = remember { NumberFormat.getNumberInstance(Locale.US) }
    Column(modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("کالا و موجودی", style = MaterialTheme.typography.headlineSmall)
        TextField(name, { name = it }, label = { Text("نام کالا") }, modifier = Modifier.fillMaxWidth())
        TextField(buy, { buy = digits(it) }, label = { Text("قیمت خرید") }, modifier = Modifier.fillMaxWidth())
        TextField(sell, { sell = digits(it) }, label = { Text("قیمت فروش") }, modifier = Modifier.fillMaxWidth())
        TextField(stock, { stock = digits(it) }, label = { Text("موجودی اولیه") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            if (name.isNotBlank()) scope.launch {
                database.productDao().insert(ProductEntity(name = name.trim(), buyPrice = buy.toLongOrNull() ?: 0, sellPrice = sell.toLongOrNull() ?: 0, stock = stock.toLongOrNull() ?: 0))
                name = ""; buy = ""; sell = ""; stock = ""
            }
        }, modifier = Modifier.fillMaxWidth()) { Text("ثبت کالا") }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(products, key = { it.id }) { p -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(10.dp)) { Text(p.name); Text("خرید ${f.format(p.buyPrice)} | فروش ${f.format(p.sellPrice)} تومان"); Text("موجودی ${f.format(p.stock)}") } } }
        }
    }
}

/** فروش و خرید با اثر خودکار روی انبار و پرداخت اولیه. */
@Composable
private fun TransactionsScreen(
    database: AppDatabase,
    persons: List<PersonEntity>,
    products: List<ProductEntity>,
    invoices: List<InvoiceEntity>,
    modifier: Modifier = Modifier
) {
    val repo = remember(database) { AccountingRepository(database) }
    val scope = rememberCoroutineScope()
    val f = remember { NumberFormat.getNumberInstance(Locale.US) }
    var type by remember { mutableStateOf("SALE") }
    var personId by remember { mutableStateOf<Long?>(null) }
    var productId by remember { mutableStateOf<Long?>(null) }
    var quantity by remember { mutableStateOf("1") }
    var price by remember { mutableStateOf("") }
    var paid by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }

    LazyColumn(modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("فروش و خرید", style = MaterialTheme.typography.headlineSmall) }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) { Button({ type = "SALE" }, Modifier.weight(1f)) { Text(if (type == "SALE") "✓ فروش" else "فروش") }; Button({ type = "PURCHASE" }, Modifier.weight(1f)) { Text(if (type == "PURCHASE") "✓ خرید" else "خرید") } } }
        item {
            Text("طرف حساب")
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton({ personId = null }) { Text(if (personId == null) "✓ متفرقه" else "متفرقه") }
                persons.forEach { p -> OutlinedButton({ personId = p.id }) { Text(if (personId == p.id) "✓ ${p.name}" else p.name) } }
            }
        }
        item {
            Text("کالا")
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                products.forEach { p -> OutlinedButton({ productId = p.id; price = if (type == "SALE") p.sellPrice.toString() else p.buyPrice.toString() }) { Text(if (productId == p.id) "✓ ${p.name}" else p.name) } }
            }
        }
        item { TextField(quantity, { quantity = digits(it) }, label = { Text("تعداد") }, modifier = Modifier.fillMaxWidth()) }
        item { TextField(price, { price = digits(it) }, label = { Text("قیمت واحد") }, modifier = Modifier.fillMaxWidth()) }
        item { TextField(paid, { paid = digits(it) }, label = { Text(if (type == "SALE") "دریافتی" else "پرداختی") }, modifier = Modifier.fillMaxWidth()) }
        item { TextField(note, { note = it }, label = { Text("یادداشت") }, modifier = Modifier.fillMaxWidth()) }
        item { Text("جمع: ${f.format((quantity.toLongOrNull() ?: 0) * (price.toLongOrNull() ?: 0))} تومان") }
        item {
            Button(onClick = {
                val pid = productId
                if (pid == null) { message = "کالا انتخاب نشده است."; return@Button }
                scope.launch {
                    runCatching {
                        val line = InvoiceDraftLine(pid, quantity.toLongOrNull() ?: 0, price.toLongOrNull() ?: 0)
                        if (type == "SALE") repo.postSale(personId, listOf(line), paid.toLongOrNull() ?: 0, note)
                        else repo.postPurchase(personId, listOf(line), paid.toLongOrNull() ?: 0, note)
                    }.onSuccess { message = "فاکتور #${it.invoiceId} ثبت شد."; paid = ""; note = "" }
                        .onFailure { message = it.message ?: "خطا" }
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("ثبت نهایی") }
        }
        if (message.isNotBlank()) item { Text(message) }
        item { Text("آخرین فاکتورها") }
        items(invoices.take(15), key = { it.id }) { i -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(10.dp)) { Text("#${i.id} ${if (i.type == "SALE") "فروش" else "خرید"}"); Text("${f.format(i.totalAmount)} تومان | مانده ${f.format(i.totalAmount - i.paidAmount)}") } } }
    }
}

private fun digits(value: String): String = value.filter(Char::isDigit)
