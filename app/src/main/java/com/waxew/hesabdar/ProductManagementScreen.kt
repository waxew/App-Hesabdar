package com.waxew.hesabdar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
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
import com.waxew.hesabdar.data.AppDatabase
import com.waxew.hesabdar.data.ProductEntity
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * مدیریت کاتالوگ کالا و خدمت.
 * جستجو روی نام، کد کالا، بارکد و دسته‌بندی انجام می‌شود و هشدار کمبود موجودی از Room خوانده می‌شود.
 */
@Composable
fun ProductManagementScreen(
    database: AppDatabase,
    products: List<ProductEntity>,
    modifier: Modifier = Modifier
) {
    var selected by remember { mutableStateOf<ProductEntity?>(null) }
    if (selected != null) {
        InventoryCardScreen(database, selected!!, onBack = { selected = null }, modifier = modifier)
        return
    }

    var name by remember { mutableStateOf("") }
    var sku by remember { mutableStateOf("") }
    var barcode by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("عدد") }
    var buy by remember { mutableStateOf("") }
    var sell by remember { mutableStateOf("") }
    var stock by remember { mutableStateOf("") }
    var minStock by remember { mutableStateOf("") }
    var isService by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }

    val scope = rememberCoroutineScope()
    val formatter = remember { NumberFormat.getNumberInstance(Locale.US) }
    val lowStock by database.productDao().observeLowStock().collectAsState(initial = emptyList())
    val filtered = remember(products, search) {
        val q = search.trim()
        if (q.isBlank()) products else products.filter { product ->
            product.name.contains(q, ignoreCase = true) ||
                product.sku.contains(q, ignoreCase = true) ||
                product.barcode.contains(q, ignoreCase = true) ||
                product.category.contains(q, ignoreCase = true)
        }
    }

    LazyColumn(modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("کالا، خدمت و انبار") }

        if (lowStock.isNotEmpty()) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("هشدار موجودی: ${lowStock.size} کالا به حداقل موجودی رسیده‌اند.")
                        lowStock.take(5).forEach { product ->
                            Text("${product.name}: ${formatter.format(product.stock)} ${product.unit}")
                        }
                    }
                }
            }
        }

        item { TextField(search, { search = it }, label = { Text("جستجو نام / کد / بارکد / دسته") }, modifier = Modifier.fillMaxWidth()) }
        item { Text("ثبت مورد جدید") }
        item { TextField(name, { name = it }, label = { Text("نام کالا یا خدمت") }, modifier = Modifier.fillMaxWidth()) }
        item { TextField(sku, { sku = it }, label = { Text("کد کالا / SKU") }, modifier = Modifier.fillMaxWidth()) }
        item { TextField(barcode, { barcode = it.filter(Char::isDigit) }, label = { Text("بارکد") }, modifier = Modifier.fillMaxWidth()) }
        item { TextField(category, { category = it }, label = { Text("دسته‌بندی") }, modifier = Modifier.fillMaxWidth()) }
        item { TextField(unit, { unit = it }, label = { Text("واحد، مثل عدد / کیلو / متر") }, modifier = Modifier.fillMaxWidth()) }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Checkbox(checked = isService, onCheckedChange = { isService = it })
                Text("این مورد خدمت است و موجودی انبار ندارد", modifier = Modifier.padding(top = 12.dp))
            }
        }
        item { TextField(buy, { buy = it.filter(Char::isDigit) }, label = { Text("قیمت خرید / بهای پایه") }, modifier = Modifier.fillMaxWidth()) }
        item { TextField(sell, { sell = it.filter(Char::isDigit) }, label = { Text("قیمت فروش") }, modifier = Modifier.fillMaxWidth()) }
        if (!isService) {
            item { TextField(stock, { stock = it.filter(Char::isDigit) }, label = { Text("موجودی اولیه") }, modifier = Modifier.fillMaxWidth()) }
            item { TextField(minStock, { minStock = it.filter(Char::isDigit) }, label = { Text("حداقل موجودی برای هشدار") }, modifier = Modifier.fillMaxWidth()) }
        }
        item {
            Button(
                onClick = {
                    if (name.isBlank()) {
                        message = "نام کالا یا خدمت را وارد کنید."
                        return@Button
                    }
                    scope.launch {
                        runCatching {
                            database.productDao().insert(
                                ProductEntity(
                                    name = name.trim(),
                                    buyPrice = buy.toLongOrNull() ?: 0,
                                    sellPrice = sell.toLongOrNull() ?: 0,
                                    stock = if (isService) 0 else stock.toLongOrNull() ?: 0,
                                    sku = sku.trim(),
                                    barcode = barcode.trim(),
                                    category = category.trim(),
                                    unit = unit.trim().ifBlank { "عدد" },
                                    minStock = if (isService) 0 else minStock.toLongOrNull() ?: 0,
                                    isService = isService
                                )
                            )
                        }.onSuccess {
                            name = ""
                            sku = ""
                            barcode = ""
                            category = ""
                            unit = "عدد"
                            buy = ""
                            sell = ""
                            stock = ""
                            minStock = ""
                            isService = false
                            message = "ثبت شد."
                        }.onFailure { message = it.message ?: "خطا در ثبت" }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("ثبت کالا / خدمت") }
        }
        if (message.isNotBlank()) item { Text(message) }

        item { Text("فهرست (${filtered.size})") }
        items(filtered, key = { it.id }) { product ->
            val isLow = !product.isService && product.minStock > 0 && product.stock <= product.minStock
            Card(Modifier.fillMaxWidth().clickable { selected = product }) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(product.name + if (product.isService) " — خدمت" else "")
                    if (product.sku.isNotBlank()) Text("کد: ${product.sku}")
                    if (product.barcode.isNotBlank()) Text("بارکد: ${product.barcode}")
                    if (product.category.isNotBlank()) Text("دسته: ${product.category}")
                    Text("خرید ${formatter.format(product.buyPrice)} | فروش ${formatter.format(product.sellPrice)} تومان")
                    if (!product.isService) {
                        Text("موجودی ${formatter.format(product.stock)} ${product.unit}${if (isLow) " — نیاز به تامین" else ""}")
                    }
                    Text(if (product.isService) "برای مشاهده جزئیات لمس کنید" else "برای مشاهده کارتکس لمس کنید")
                }
            }
        }
    }
}

/** کارتکس ورود و خروج یک کالا؛ خدمت‌ها به‌درستی بدون گردش انبار نمایش داده می‌شوند. */
@Composable
private fun InventoryCardScreen(
    database: AppDatabase,
    product: ProductEntity,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val movements by database.reportingDao().observeInventoryCard(product.id).collectAsState(initial = emptyList())
    val formatter = remember { NumberFormat.getNumberInstance(Locale.US) }
    val date = remember { SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US) }

    LazyColumn(modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { OutlinedButton(onClick = onBack) { Text("بازگشت") } }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(product.name)
                    Text(if (product.isService) "نوع: خدمت" else "نوع: کالا")
                    if (product.sku.isNotBlank()) Text("کد کالا: ${product.sku}")
                    if (product.barcode.isNotBlank()) Text("بارکد: ${product.barcode}")
                    if (product.category.isNotBlank()) Text("دسته‌بندی: ${product.category}")
                    if (!product.isService) {
                        Text("موجودی فعلی: ${formatter.format(product.stock)} ${product.unit}")
                        Text("حداقل موجودی: ${formatter.format(product.minStock)} ${product.unit}")
                    }
                    Text("قیمت خرید: ${formatter.format(product.buyPrice)} تومان")
                    Text("قیمت فروش: ${formatter.format(product.sellPrice)} تومان")
                }
            }
        }
        item { Text(if (product.isService) "خدمت، گردش انبار ندارد." else "گردش کالا") }
        if (!product.isService && movements.isEmpty()) item { Text("هنوز گردش انبار ثبت نشده است.") }
        if (!product.isService) {
            items(movements, key = { it.movementId }) { row ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text(movementFa(row.movementType))
                            Text(date.format(Date(row.createdAt)))
                            if (row.invoiceId != null) Text("فاکتور #${row.invoiceId}")
                        }
                        Text((if (row.quantityDelta >= 0) "+" else "") + formatter.format(row.quantityDelta) + " ${product.unit}")
                    }
                }
            }
        }
    }
}

private fun movementFa(type: String): String = when (type) {
    "SALE" -> "خروج بابت فروش"
    "PURCHASE" -> "ورود بابت خرید"
    "SALE_RETURN" -> "ورود بابت برگشت فروش"
    "PURCHASE_RETURN" -> "خروج بابت برگشت خرید"
    else -> type
}
