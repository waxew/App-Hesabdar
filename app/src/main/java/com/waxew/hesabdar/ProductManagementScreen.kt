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

/** مدیریت کالا و دسترسی به کارتکس هر کالا. */
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
    var buy by remember { mutableStateOf("") }
    var sell by remember { mutableStateOf("") }
    var stock by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val f = remember { NumberFormat.getNumberInstance(Locale.US) }

    Column(modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("کالا و انبار")
        TextField(name, { name = it }, label = { Text("نام کالا") }, modifier = Modifier.fillMaxWidth())
        TextField(buy, { buy = it.filter(Char::isDigit) }, label = { Text("قیمت خرید") }, modifier = Modifier.fillMaxWidth())
        TextField(sell, { sell = it.filter(Char::isDigit) }, label = { Text("قیمت فروش") }, modifier = Modifier.fillMaxWidth())
        TextField(stock, { stock = it.filter(Char::isDigit) }, label = { Text("موجودی اولیه") }, modifier = Modifier.fillMaxWidth())
        Button(
            onClick = {
                if (name.isNotBlank()) scope.launch {
                    database.productDao().insert(
                        ProductEntity(
                            name = name.trim(),
                            buyPrice = buy.toLongOrNull() ?: 0,
                            sellPrice = sell.toLongOrNull() ?: 0,
                            stock = stock.toLongOrNull() ?: 0
                        )
                    )
                    name = ""; buy = ""; sell = ""; stock = ""
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("ثبت کالا") }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(products, key = { it.id }) { p ->
                Card(Modifier.fillMaxWidth().clickable { selected = p }) {
                    Column(Modifier.padding(12.dp)) {
                        Text(p.name)
                        Text("خرید ${f.format(p.buyPrice)} | فروش ${f.format(p.sellPrice)} تومان")
                        Text("موجودی ${f.format(p.stock)}")
                        Text("برای کارتکس لمس کنید")
                    }
                }
            }
        }
    }
}

/** کارتکس کامل ورود و خروج کالا بر اساس جدول inventory_movements. */
@Composable
private fun InventoryCardScreen(
    database: AppDatabase,
    product: ProductEntity,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val movements by database.reportingDao().observeInventoryCard(product.id).collectAsState(initial = emptyList())
    val f = remember { NumberFormat.getNumberInstance(Locale.US) }
    val date = remember { SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US) }

    LazyColumn(modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { OutlinedButton(onClick = onBack) { Text("بازگشت") } }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(product.name)
                    Text("موجودی فعلی: ${f.format(product.stock)}")
                    Text("قیمت خرید: ${f.format(product.buyPrice)} تومان")
                    Text("قیمت فروش: ${f.format(product.sellPrice)} تومان")
                }
            }
        }
        item { Text("گردش کالا") }
        if (movements.isEmpty()) item { Text("هنوز گردش انبار ثبت نشده است.") }
        items(movements, key = { it.movementId }) { row ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text(movementFa(row.movementType))
                        Text(date.format(Date(row.createdAt)))
                        if (row.invoiceId != null) Text("فاکتور #${row.invoiceId}")
                    }
                    Text(if (row.quantityDelta >= 0) "+${f.format(row.quantityDelta)}" else f.format(row.quantityDelta))
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
