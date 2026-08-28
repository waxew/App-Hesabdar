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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.waxew.hesabdar.data.AccountingRepository
import com.waxew.hesabdar.data.AppDatabase
import com.waxew.hesabdar.data.DataExportManager
import com.waxew.hesabdar.data.InvoiceDraftLine
import com.waxew.hesabdar.data.InvoiceEntity
import com.waxew.hesabdar.data.PersonEntity
import com.waxew.hesabdar.data.ProductEntity
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

private data class UiInvoiceLine(val key: Long, val product: ProductEntity, val quantity: Long, val unitPrice: Long) {
    val total: Long get() = quantity * unitPrice
}

/** فاکتور چندردیفی فروش، خرید و مرجوعی با خروجی PDF. */
@Composable
fun InvoiceComposerScreen(
    database: AppDatabase,
    persons: List<PersonEntity>,
    products: List<ProductEntity>,
    invoices: List<InvoiceEntity>,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val repo = remember(database) { AccountingRepository(database) }
    val exportManager = remember(database) { DataExportManager(context, database) }
    val scope = rememberCoroutineScope()
    val formatter = remember { NumberFormat.getNumberInstance(Locale.US) }
    val cart = remember { mutableStateListOf<UiInvoiceLine>() }

    var type by remember { mutableStateOf("SALE") }
    var personId by remember { mutableStateOf<Long?>(null) }
    var productId by remember { mutableStateOf<Long?>(null) }
    var quantityText by remember { mutableStateOf("1") }
    var priceText by remember { mutableStateOf("") }
    var settlementText by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var nextKey by remember { mutableStateOf(1L) }

    val selectedProduct = products.firstOrNull { it.id == productId }
    val total = cart.sumOf { it.total }

    LazyColumn(modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("فاکتور چندردیفی") }
        item {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                typeButton("SALE", "فروش", type) { type = it; cart.clear() }
                typeButton("PURCHASE", "خرید", type) { type = it; cart.clear() }
                typeButton("SALE_RETURN", "برگشت فروش", type) { type = it; cart.clear() }
                typeButton("PURCHASE_RETURN", "برگشت خرید", type) { type = it; cart.clear() }
            }
        }
        item {
            Text("طرف حساب")
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { personId = null }) { Text(if (personId == null) "✓ متفرقه" else "متفرقه") }
                persons.forEach { p -> OutlinedButton(onClick = { personId = p.id }) { Text(if (personId == p.id) "✓ ${p.name}" else p.name) } }
            }
        }
        item {
            Text("انتخاب کالا")
            if (products.isEmpty()) Text("ابتدا کالا ثبت کنید.")
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                products.forEach { p ->
                    OutlinedButton(onClick = {
                        productId = p.id
                        priceText = if (type == "PURCHASE" || type == "PURCHASE_RETURN") p.buyPrice.toString() else p.sellPrice.toString()
                    }) { Text(if (productId == p.id) "✓ ${p.name}" else p.name) }
                }
            }
        }
        item { TextField(quantityText, { quantityText = it.filter(Char::isDigit) }, label = { Text("تعداد") }, modifier = Modifier.fillMaxWidth()) }
        item { TextField(priceText, { priceText = it.filter(Char::isDigit) }, label = { Text("قیمت واحد") }, modifier = Modifier.fillMaxWidth()) }
        item {
            Button(onClick = {
                val p = selectedProduct ?: return@Button
                val q = quantityText.toLongOrNull() ?: 0
                val price = priceText.toLongOrNull() ?: 0
                if (q <= 0) { message = "تعداد معتبر وارد کنید."; return@Button }
                cart += UiInvoiceLine(nextKey++, p, q, price)
                quantityText = "1"; productId = null; priceText = ""; message = ""
            }, modifier = Modifier.fillMaxWidth()) { Text("افزودن ردیف به فاکتور") }
        }

        if (cart.isNotEmpty()) item { Text("ردیف‌های فاکتور") }
        items(cart, key = { it.key }) { line ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(line.product.name)
                    Text("${formatter.format(line.quantity)} × ${formatter.format(line.unitPrice)} = ${formatter.format(line.total)} تومان")
                    OutlinedButton(onClick = { cart.remove(line) }) { Text("حذف ردیف") }
                }
            }
        }

        item { Text("جمع فاکتور: ${formatter.format(total)} تومان") }
        item { TextField(settlementText, { settlementText = it.filter(Char::isDigit) }, label = { Text(settlementLabel(type)) }, modifier = Modifier.fillMaxWidth()) }
        item { TextField(note, { note = it }, label = { Text("توضیحات") }, modifier = Modifier.fillMaxWidth()) }
        item {
            Button(onClick = {
                if (cart.isEmpty()) { message = "فاکتور خالی است."; return@Button }
                val lines = cart.map { InvoiceDraftLine(it.product.id, it.quantity, it.unitPrice) }
                val settlement = settlementText.toLongOrNull() ?: 0
                scope.launch {
                    runCatching {
                        when (type) {
                            "SALE" -> repo.postSale(personId, lines, settlement, note)
                            "PURCHASE" -> repo.postPurchase(personId, lines, settlement, note)
                            "SALE_RETURN" -> repo.postSaleReturn(personId, lines, settlement, note)
                            else -> repo.postPurchaseReturn(personId, lines, settlement, note)
                        }
                    }.onSuccess {
                        message = "${typeFa(type)} شماره ${it.invoiceId} ثبت شد."
                        cart.clear(); settlementText = ""; note = ""
                    }.onFailure { message = it.message ?: "خطا در ثبت فاکتور" }
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("ثبت نهایی ${typeFa(type)}") }
        }
        if (message.isNotBlank()) item { Text(message) }

        item { Text("آخرین اسناد تجاری") }
        items(invoices.take(20), key = { it.id }) { invoice ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("#${invoice.id} - ${typeFa(invoice.type)}")
                    Text("مبلغ ${formatter.format(invoice.totalAmount)} تومان")
                    Text("تسویه ${formatter.format(invoice.paidAmount)} تومان")
                    OutlinedButton(onClick = {
                        runCatching { exportManager.exportInvoicePdf(invoice.id) }
                            .onSuccess { message = "PDF فاکتور ساخته شد: ${it.name}" }
                            .onFailure { message = it.message ?: "خطا در ساخت PDF" }
                    }) { Text("ساخت PDF فاکتور") }
                }
            }
        }
    }
}

@Composable
private fun typeButton(code: String, title: String, selected: String, onSelected: (String) -> Unit) {
    OutlinedButton(onClick = { onSelected(code) }) { Text(if (selected == code) "✓ $title" else title) }
}

private fun settlementLabel(type: String): String = when (type) {
    "SALE" -> "مبلغ دریافتی"
    "PURCHASE" -> "مبلغ پرداختی"
    "SALE_RETURN" -> "مبلغ عودت به مشتری"
    else -> "مبلغ دریافت از تامین‌کننده"
}

private fun typeFa(type: String): String = when (type) {
    "SALE" -> "فروش"
    "PURCHASE" -> "خرید"
    "SALE_RETURN" -> "برگشت از فروش"
    "PURCHASE_RETURN" -> "برگشت از خرید"
    else -> type
}
