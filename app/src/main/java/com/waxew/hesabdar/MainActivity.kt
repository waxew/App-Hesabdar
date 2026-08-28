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
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
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

/**
 * Activity اصلی برنامه.
 * دیتابیس فقط یک‌بار ساخته می‌شود و Compose رابط RTL حسابدار را نمایش می‌دهد.
 */
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

/**
 * پوسته اصلی برنامه و Navigation پایین.
 * فعلاً چهار ناحیه عملیاتی داریم و در نسخه‌های بعد گزارش‌ها و Drawer حرفه‌ای اضافه می‌شوند.
 */
@OptIn(ExperimentalMaterial3Api::class)
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

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Default.Home, contentDescription = null) },
                    label = { Text("خانه") }
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Default.People, contentDescription = null) },
                    label = { Text("اشخاص") }
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Icon(Icons.Default.Inventory2, contentDescription = null) },
                    label = { Text("کالاها") }
                )
                NavigationBarItem(
                    selected = tab == 3,
                    onClick = { tab = 3 },
                    icon = { Icon(Icons.Default.AccountBalanceWallet, contentDescription = null) },
                    label = { Text("معاملات") }
                )
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
                modifier = Modifier.padding(padding)
            )

            1 -> PersonsScreen(database, persons, Modifier.padding(padding))
            2 -> ProductsScreen(database, products, Modifier.padding(padding))
            else -> TransactionsScreen(
                database = database,
                persons = persons,
                products = products,
                invoices = invoices,
                modifier = Modifier.padding(padding)
            )
        }
    }
}

/** داشبورد مدیریتی نسخه فعلی؛ مبالغ از Queryهای واقعی دیتابیس خوانده می‌شوند. */
@Composable
private fun Dashboard(
    personCount: Int,
    productCount: Int,
    invoiceCount: Int,
    salesTotal: Long,
    purchasesTotal: Long,
    receivables: Long,
    payables: Long,
    modifier: Modifier = Modifier
) {
    val formatter = remember { NumberFormat.getNumberInstance(Locale.US) }

    LazyColumn(
        modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("حسابدار", style = MaterialTheme.typography.headlineMedium)
            Text("نسخه 0.2.0 — دیتابیس محلی و عملیات مالی اولیه")
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SummaryCard("طرف حساب", personCount.toString(), Modifier.weight(1f))
                SummaryCard("کالا", productCount.toString(), Modifier.weight(1f))
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SummaryCard("فاکتور", invoiceCount.toString(), Modifier.weight(1f))
                SummaryCard("فروش", "${formatter.format(salesTotal)} تومان", Modifier.weight(1f))
            }
        }

        item { SummaryCard("خرید", "${formatter.format(purchasesTotal)} تومان", Modifier.fillMaxWidth()) }
        item { SummaryCard("مطالبات", "${formatter.format(receivables)} تومان", Modifier.fillMaxWidth()) }
        item { SummaryCard("بدهی‌ها", "${formatter.format(payables)} تومان", Modifier.fillMaxWidth()) }

        item {
            Text(
                "در این نسخه ثبت فروش و خرید، تسویه اولیه و گردش موجودی به‌صورت Transactional فعال شده است."
            )
        }
    }
}

/** کارت کوچک داشبورد برای نمایش یک شاخص مدیریتی. */
@Composable
private fun SummaryCard(title: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(value, style = MaterialTheme.typography.titleLarge)
        }
    }
}

/** فرم و لیست اشخاص؛ داده‌ها مستقیماً در Room ذخیره می‌شوند. */
@Composable
private fun PersonsScreen(
    database: AppDatabase,
    persons: List<PersonEntity>,
    modifier: Modifier = Modifier
) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("اشخاص و طرف‌حساب‌ها", style = MaterialTheme.typography.headlineSmall)

        TextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("نام") },
            modifier = Modifier.fillMaxWidth()
        )

        TextField(
            value = phone,
            onValueChange = { phone = it },
            label = { Text("شماره تماس") },
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = {
                if (name.isNotBlank()) {
                    scope.launch {
                        database.personDao().insert(
                            PersonEntity(name = name.trim(), phone = phone.trim())
                        )
                        name = ""
                        phone = ""
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Text(" افزودن شخص")
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(persons, key = { it.id }) { person ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text(person.name, style = MaterialTheme.typography.titleMedium)
                        if (person.phone.isNotBlank()) Text(person.phone)
                    }
                }
            }
        }
    }
}

/**
 * فرم کالا.
 * قیمت خرید، قیمت فروش و موجودی اولیه از همین نسخه ذخیره می‌شوند تا فاکتورهای خرید/فروش قابل تست باشند.
 */
@Composable
private fun ProductsScreen(
    database: AppDatabase,
    products: List<ProductEntity>,
    modifier: Modifier = Modifier
) {
    var name by remember { mutableStateOf("") }
    var buyPrice by remember { mutableStateOf("") }
    var sellPrice by remember { mutableStateOf("") }
    var stock by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val formatter = remember { NumberFormat.getNumberInstance(Locale.US) }

    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("کالاها و موجودی", style = MaterialTheme.typography.headlineSmall)

        TextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("نام کالا") },
            modifier = Modifier.fillMaxWidth()
        )

        TextField(
            value = buyPrice,
            onValueChange = { buyPrice = it.filter(Char::isDigit) },
            label = { Text("قیمت خرید") },
            modifier = Modifier.fillMaxWidth()
        )

        TextField(
            value = sellPrice,
            onValueChange = { sellPrice = it.filter(Char::isDigit) },
            label = { Text("قیمت فروش") },
            modifier = Modifier.fillMaxWidth()
        )

        TextField(
            value = stock,
            onValueChange = { stock = it.filter(Char::isDigit) },
            label = { Text("موجودی اولیه") },
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = {
                if (name.isNotBlank()) {
                    scope.launch {
                        database.productDao().insert(
                            ProductEntity(
                                name = name.trim(),
                                buyPrice = buyPrice.toLongOrNull() ?: 0L,
                                sellPrice = sellPrice.toLongOrNull() ?: 0L,
                                stock = stock.toLongOrNull() ?: 0L
                            )
                        )
                        name = ""
                        buyPrice = ""
                        sellPrice = ""
                        stock = ""
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Text(" افزودن کالا")
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(products, key = { it.id }) { product ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(product.name, style = MaterialTheme.typography.titleMedium)
                        Text("خرید: ${formatter.format(product.buyPrice)} تومان")
                        Text("فروش: ${formatter.format(product.sellPrice)} تومان")
                        Text("موجودی: ${formatter.format(product.stock)}")
                    }
                }
            }
        }
    }
}

/**
 * صفحه معاملات اولیه.
 * برای ساده نگه‌داشتن نسخه 0.2.0 یک ردیف کالا در هر ثبت پذیرفته می‌شود، اما دیتابیس و Repository از چند ردیف پشتیبانی می‌کنند.
 */
@Composable
private fun TransactionsScreen(
    database: AppDatabase,
    persons: List<PersonEntity>,
    products: List<ProductEntity>,
    invoices: List<InvoiceEntity>,
    modifier: Modifier = Modifier
) {
    val repository = remember(database) { AccountingRepository(database) }
    val scope = rememberCoroutineScope()
    val formatter = remember { NumberFormat.getNumberInstance(Locale.US) }

    var transactionType by remember { mutableStateOf("SALE") }
    var selectedPersonId by remember { mutableStateOf<Long?>(null) }
    var selectedProductId by remember { mutableStateOf<Long?>(null) }
    var quantityText by remember { mutableStateOf("1") }
    var unitPriceText by remember { mutableStateOf("") }
    var paidText by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }

    val selectedProduct = products.firstOrNull { it.id == selectedProductId }

    LazyColumn(
        modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { Text("فروش و خرید", style = MaterialTheme.typography.headlineSmall) }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { transactionType = "SALE" }, modifier = Modifier.weight(1f)) {
                    Text(if (transactionType == "SALE") "✓ فروش" else "فروش")
                }
                Button(onClick = { transactionType = "PURCHASE" }, modifier = Modifier.weight(1f)) {
                    Text(if (transactionType == "PURCHASE") "✓ خرید" else "خرید")
                }
            }
        }

        item {
            Text("طرف حساب")
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = { selectedPersonId = null }) {
                    Text(if (selectedPersonId == null) "✓ متفرقه" else "متفرقه")
                }
                persons.forEach { person ->
                    OutlinedButton(onClick = { selectedPersonId = person.id }) {
                        Text(if (selectedPersonId == person.id) "✓ ${person.name}" else person.name)
                    }
                }
            }
        }

        item {
            Text("کالا")
            if (products.isEmpty()) {
                Text("ابتدا از بخش کالاها یک کالا ثبت کنید.")
            } else {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    products.forEach { product ->
                        OutlinedButton(
                            onClick = {
                                selectedProductId = product.id
                                unitPriceText = if (transactionType == "SALE") {
                                    product.sellPrice.toString()
                                } else {
                                    product.buyPrice.toString()
                                }
                            }
                        ) {
                            Text(if (selectedProductId == product.id) "✓ ${product.name}" else product.name)
                        }
                    }
                }
            }
        }

        item {
            TextField(
                value = quantityText,
                onValueChange = { quantityText = it.filter(Char::isDigit) },
                label = { Text("تعداد") },
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            TextField(
                value = unitPriceText,
                onValueChange = { unitPriceText = it.filter(Char::isDigit) },
                label = { Text(if (transactionType == "SALE") "قیمت فروش واحد" else "قیمت خرید واحد") },
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            TextField(
                value = paidText,
                onValueChange = { paidText = it.filter(Char::isDigit) },
                label = { Text(if (transactionType == "SALE") "مبلغ دریافتی" else "مبلغ پرداختی") },
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            TextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("یادداشت") },
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            val quantity = quantityText.toLongOrNull() ?: 0L
            val unitPrice = unitPriceText.toLongOrNull() ?: 0L
            val total = quantity * unitPrice

            Text("جمع: ${formatter.format(total)} تومان")
            selectedProduct?.let { product ->
                Text("موجودی فعلی ${product.name}: ${formatter.format(product.stock)}")
            }
        }

        item {
            Button(
                onClick = {
                    val productId = selectedProductId
                    val quantity = quantityText.toLongOrNull() ?: 0L
                    val unitPrice = unitPriceText.toLongOrNull() ?: 0L
                    val paid = paidText.toLongOrNull() ?: 0L

                    if (productId == null) {
                        message = "یک کالا انتخاب کنید."
                        return@Button
                    }

                    scope.launch {
                        runCatching {
                            val line = InvoiceDraftLine(
                                productId = productId,
                                quantity = quantity,
                                unitPrice = unitPrice
                            )

                            if (transactionType == "SALE") {
                                repository.postSale(
                                    personId = selectedPersonId,
                                    lines = listOf(line),
                                    paidAmount = paid,
                                    note = note
                                )
                            } else {
                                repository.postPurchase(
                                    personId = selectedPersonId,
                                    lines = listOf(line),
                                    paidAmount = paid,
                                    note = note
                                )
                            }
                        }.onSuccess { result ->
                            message = "فاکتور شماره ${result.invoiceId} با مبلغ ${formatter.format(result.totalAmount)} تومان ثبت شد."
                            quantityText = "1"
                            paidText = ""
                            note = ""
                        }.onFailure { error ->
                            message = error.message ?: "خطا در ثبت معامله"
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (transactionType == "SALE") "ثبت نهایی فروش" else "ثبت نهایی خرید")
            }
        }

        if (message.isNotBlank()) {
            item { Text(message) }
        }

        item { Text("آخرین فاکتورها", style = MaterialTheme.typography.titleMedium) }

        items(invoices.take(10), key = { it.id }) { invoice ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("#${invoice.id} — ${if (invoice.type == "SALE") "فروش" else "خرید"}")
                    Text("مبلغ: ${formatter.format(invoice.totalAmount)} تومان")
                    Text("تسویه: ${formatter.format(invoice.paidAmount)} تومان")
                    Text("مانده: ${formatter.format(invoice.totalAmount - invoice.paidAmount)} تومان")
                }
            }
        }
    }
}
