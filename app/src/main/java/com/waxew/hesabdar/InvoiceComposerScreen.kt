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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import com.waxew.hesabdar.data.InvoiceCharges
import com.waxew.hesabdar.data.InvoiceDraftLine
import com.waxew.hesabdar.data.InvoiceEntity
import com.waxew.hesabdar.data.InvoiceMath
import com.waxew.hesabdar.data.PersonEntity
import com.waxew.hesabdar.data.ProductEntity
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

/** ردیف موقت سبد قبل از ثبت نهایی در Room. */
private data class UiInvoiceLine(
    val key: Long,
    val product: ProductEntity,
    val quantity: Long,
    val unitPrice: Long
) {
    val total: Long get() = Math.multiplyExact(quantity, unitPrice)
}

/**
 * فاکتور چندردیفی فروش، خرید و مرجوعی.
 * اسناد نهایی شماره پایدار دارند و فروش/خرید ثبت‌شده فقط با سند معکوس قابل ابطال است.
 * هر مبلغ تسویه نیز به صندوق/بانک واقعی متصل می‌شود تا مانده خزانه با دفتر حسابداری هماهنگ بماند.
 */
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
    val treasuryAccounts by database.treasuryDao().observeAccounts().collectAsState(initial = emptyList())

    var type by remember { mutableStateOf("SALE") }
    var personId by remember { mutableStateOf<Long?>(null) }
    var productId by remember { mutableStateOf<Long?>(null) }
    var treasuryAccountId by remember { mutableStateOf<Long?>(null) }
    var productSearch by remember { mutableStateOf("") }
    var quantityText by remember { mutableStateOf("1") }
    var priceText by remember { mutableStateOf("") }
    var discountText by remember { mutableStateOf("") }
    var taxPercentText by remember { mutableStateOf("") }
    var shippingText by remember { mutableStateOf("") }
    var settlementText by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var nextKey by remember { mutableStateOf(1L) }

    // در صورت انتخاب ابطال، ابتدا Dialog علت را می‌گیرد؛ عملیات مالی بدون تایید اجرا نمی‌شود.
    var pendingVoid by remember { mutableStateOf<InvoiceEntity?>(null) }
    var voidReason by remember { mutableStateOf("") }

    val selectedProduct = products.firstOrNull { it.id == productId }
    val filteredProducts = remember(products, productSearch) {
        val query = productSearch.trim()
        if (query.isBlank()) products else products.filter { product ->
            product.name.contains(query, ignoreCase = true) ||
                product.sku.contains(query, ignoreCase = true) ||
                product.barcode.contains(query, ignoreCase = true) ||
                product.category.contains(query, ignoreCase = true)
        }
    }

    val subtotal = runCatching {
        cart.fold(0L) { acc, line -> Math.addExact(acc, line.total) }
    }.getOrDefault(0L)
    val discount = discountText.toLongOrNull() ?: 0L
    val taxPercent = taxPercentText.toIntOrNull() ?: 0
    val shipping = shippingText.toLongOrNull() ?: 0L
    val settlementPreview = settlementText.toLongOrNull() ?: 0L
    val previewTotals = runCatching {
        InvoiceMath.calculate(subtotal, discount, taxPercent, shipping)
    }.getOrNull()

    LazyColumn(modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("فاکتور چندردیفی") }
        item {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                typeButton("SALE", "فروش", type) { type = it; cart.clear(); productId = null }
                typeButton("PURCHASE", "خرید", type) { type = it; cart.clear(); productId = null }
                typeButton("SALE_RETURN", "برگشت فروش", type) { type = it; cart.clear(); productId = null }
                typeButton("PURCHASE_RETURN", "برگشت خرید", type) { type = it; cart.clear(); productId = null }
            }
        }

        item {
            Text("طرف حساب")
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { personId = null }) { Text(if (personId == null) "✓ متفرقه" else "متفرقه") }
                persons.forEach { person ->
                    OutlinedButton(onClick = { personId = person.id }) {
                        Text(if (personId == person.id) "✓ ${person.name}" else person.name)
                    }
                }
            }
        }

        item { TextField(productSearch, { productSearch = it }, label = { Text("جستجوی کالا / خدمت / بارکد") }, modifier = Modifier.fillMaxWidth()) }
        item {
            Text("انتخاب کالا یا خدمت")
            if (products.isEmpty()) Text("ابتدا کالا یا خدمت ثبت کنید.")
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                filteredProducts.take(40).forEach { product ->
                    OutlinedButton(onClick = {
                        productId = product.id
                        priceText = if (type == "PURCHASE" || type == "PURCHASE_RETURN") {
                            product.buyPrice.toString()
                        } else {
                            product.sellPrice.toString()
                        }
                    }) {
                        Text(if (productId == product.id) "✓ ${product.name}" else product.name)
                    }
                }
            }
        }

        if (selectedProduct != null) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(selectedProduct.name + if (selectedProduct.isService) " — خدمت" else "")
                        if (!selectedProduct.isService) Text("موجودی: ${formatter.format(selectedProduct.stock)} ${selectedProduct.unit}")
                        if (selectedProduct.barcode.isNotBlank()) Text("بارکد: ${selectedProduct.barcode}")
                    }
                }
            }
        }

        item { TextField(quantityText, { quantityText = it.filter(Char::isDigit) }, label = { Text("تعداد") }, modifier = Modifier.fillMaxWidth()) }
        item { TextField(priceText, { priceText = it.filter(Char::isDigit) }, label = { Text("قیمت واحد") }, modifier = Modifier.fillMaxWidth()) }
        item {
            Button(onClick = {
                val product = selectedProduct ?: run {
                    message = "کالا یا خدمت را انتخاب کنید."
                    return@Button
                }
                val quantity = quantityText.toLongOrNull() ?: 0
                val price = priceText.toLongOrNull() ?: 0
                if (quantity <= 0) {
                    message = "تعداد معتبر وارد کنید."
                    return@Button
                }
                if (price < 0) {
                    message = "قیمت معتبر وارد کنید."
                    return@Button
                }
                cart += UiInvoiceLine(nextKey++, product, quantity, price)
                quantityText = "1"
                productId = null
                priceText = ""
                productSearch = ""
                message = ""
            }, modifier = Modifier.fillMaxWidth()) { Text("افزودن ردیف به فاکتور") }
        }

        if (cart.isNotEmpty()) item { Text("ردیف‌های فاکتور") }
        items(cart, key = { it.key }) { line ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(line.product.name + if (line.product.isService) " — خدمت" else "")
                    Text("${formatter.format(line.quantity)} ${line.product.unit} × ${formatter.format(line.unitPrice)} = ${formatter.format(line.total)} تومان")
                    OutlinedButton(onClick = { cart.remove(line) }) { Text("حذف ردیف") }
                }
            }
        }

        item { Text("جمع ردیف‌ها: ${formatter.format(subtotal)} تومان") }
        item { TextField(discountText, { discountText = it.filter(Char::isDigit) }, label = { Text("تخفیف مبلغی") }, modifier = Modifier.fillMaxWidth()) }
        item { TextField(taxPercentText, { taxPercentText = it.filter(Char::isDigit).take(3) }, label = { Text("درصد مالیات") }, modifier = Modifier.fillMaxWidth()) }
        item { TextField(shippingText, { shippingText = it.filter(Char::isDigit) }, label = { Text("هزینه حمل / ارسال") }, modifier = Modifier.fillMaxWidth()) }

        if (previewTotals != null) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("جمع اولیه: ${formatter.format(previewTotals.subtotal)} تومان")
                        Text("تخفیف: ${formatter.format(previewTotals.discountAmount)} تومان")
                        Text("مالیات: ${formatter.format(previewTotals.taxAmount)} تومان")
                        Text("حمل: ${formatter.format(previewTotals.shippingAmount)} تومان")
                        Text("مبلغ نهایی: ${formatter.format(previewTotals.grandTotal)} تومان")
                    }
                }
            }
        } else if (cart.isNotEmpty()) {
            item { Text("مقادیر تخفیف یا مالیات معتبر نیستند.") }
        }

        item { TextField(settlementText, { settlementText = it.filter(Char::isDigit) }, label = { Text(settlementLabel(type)) }, modifier = Modifier.fillMaxWidth()) }

        // هر تسویه باید به یک صندوق یا بانک متصل باشد؛ در حالت نسیه کامل انتخاب خزانه لازم نیست.
        if (settlementPreview > 0) {
            item { Text("محل تسویه") }
            if (treasuryAccounts.isEmpty()) {
                item { Text("برای ثبت تسویه ابتدا در بخش حسابداری حرفه‌ای یک صندوق یا حساب بانکی بسازید.") }
            } else {
                item {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        treasuryAccounts.forEach { account ->
                            OutlinedButton(onClick = { treasuryAccountId = account.id }) {
                                Text(if (treasuryAccountId == account.id) "✓ ${account.name}" else account.name)
                            }
                        }
                    }
                }
            }
        }

        item { TextField(note, { note = it }, label = { Text("توضیحات") }, modifier = Modifier.fillMaxWidth()) }
        item {
            Button(onClick = {
                if (cart.isEmpty()) {
                    message = "فاکتور خالی است."
                    return@Button
                }
                val totals = previewTotals ?: run {
                    message = "مقادیر تخفیف، مالیات یا حمل را بررسی کنید."
                    return@Button
                }
                if (totals.grandTotal <= 0) {
                    message = "مبلغ نهایی فاکتور باید بیشتر از صفر باشد."
                    return@Button
                }

                val lines = cart.map { InvoiceDraftLine(it.product.id, it.quantity, it.unitPrice) }
                val settlement = settlementText.toLongOrNull() ?: 0
                if (settlement > 0 && treasuryAccountId == null) {
                    message = "برای مبلغ تسویه، صندوق یا حساب بانکی را انتخاب کنید."
                    return@Button
                }
                val charges = InvoiceCharges(discountAmount = discount, taxPercent = taxPercent, shippingAmount = shipping)

                scope.launch {
                    runCatching {
                        when (type) {
                            "SALE" -> repo.postSale(personId, lines, settlement, charges, note, treasuryAccountId)
                            "PURCHASE" -> repo.postPurchase(personId, lines, settlement, charges, note, treasuryAccountId)
                            "SALE_RETURN" -> repo.postSaleReturn(personId, lines, settlement, charges, note, treasuryAccountId)
                            else -> repo.postPurchaseReturn(personId, lines, settlement, charges, note, treasuryAccountId)
                        }
                    }.onSuccess { result ->
                        message = "${typeFa(type)} ${result.documentNumber} به مبلغ ${formatter.format(result.totalAmount)} تومان ثبت شد."
                        cart.clear()
                        settlementText = ""
                        discountText = ""
                        taxPercentText = ""
                        shippingText = ""
                        note = ""
                    }.onFailure { message = it.message ?: "خطا در ثبت فاکتور" }
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("ثبت نهایی ${typeFa(type)}") }
        }
        if (message.isNotBlank()) item { Text(message) }

        item { Text("آخرین اسناد تجاری") }
        items(invoices.take(30), key = { it.id }) { invoice ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${invoice.documentNumber.ifBlank { "#${invoice.id}" }} — ${typeFa(invoice.type)}")
                    Text("وضعیت: ${statusFa(invoice.status)}")
                    Text("مبلغ نهایی ${formatter.format(invoice.totalAmount)} تومان")
                    if (invoice.discountAmount > 0) Text("تخفیف ${formatter.format(invoice.discountAmount)} تومان")
                    if (invoice.taxAmount > 0) Text("مالیات ${formatter.format(invoice.taxAmount)} تومان")
                    if (invoice.shippingAmount > 0) Text("حمل ${formatter.format(invoice.shippingAmount)} تومان")
                    Text("تسویه ${formatter.format(invoice.paidAmount)} تومان")
                    Text("مانده ${formatter.format((invoice.totalAmount - invoice.paidAmount).coerceAtLeast(0))} تومان")
                    if (invoice.reversesInvoiceId != null) Text("سند معکوس فاکتور #${invoice.reversesInvoiceId}")
                    if (invoice.status == "VOIDED" && invoice.voidReason.isNotBlank()) Text("علت ابطال: ${invoice.voidReason}")

                    OutlinedButton(onClick = {
                        runCatching { exportManager.exportInvoicePdf(invoice.id) }
                            .onSuccess { message = "PDF فاکتور ساخته شد: ${it.name}" }
                            .onFailure { message = it.message ?: "خطا در ساخت PDF" }
                    }) { Text("ساخت PDF فاکتور") }

                    if (invoice.status == "POSTED" && (invoice.type == "SALE" || invoice.type == "PURCHASE")) {
                        OutlinedButton(onClick = {
                            pendingVoid = invoice
                            voidReason = ""
                        }) { Text("ابطال با سند معکوس") }
                    }
                }
            }
        }
    }

    // Dialog ابطال خارج از LazyColumn قرار دارد تا روی صفحه فعلی نمایش داده شود و علت اجباری باشد.
    val invoiceToVoid = pendingVoid
    if (invoiceToVoid != null) {
        AlertDialog(
            onDismissRequest = { pendingVoid = null; voidReason = "" },
            title = { Text("ابطال ${invoiceToVoid.documentNumber.ifBlank { "فاکتور #${invoiceToVoid.id}" }}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("این سند روی حساب‌ها و موجودی اثر گذاشته است. برنامه اصل سند را حذف نمی‌کند و یک سند معکوس ایجاد می‌کند.")
                    if (invoiceToVoid.paidAmount > 0) {
                        Text("اگر این فاکتور متعلق به نسخه‌های قدیمی Beta است، صندوق/بانک انتخاب‌شده بالا برای برگشت وجه استفاده می‌شود.")
                    }
                    TextField(voidReason, { voidReason = it }, label = { Text("علت ابطال") }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = invoiceToVoid
                    if (voidReason.isBlank()) {
                        message = "علت ابطال الزامی است."
                        return@TextButton
                    }
                    pendingVoid = null
                    scope.launch {
                        runCatching { repo.voidInvoice(target.id, voidReason, treasuryAccountId) }
                            .onSuccess { result ->
                                message = "${target.documentNumber} با سند معکوس ${result.documentNumber} ابطال شد."
                                voidReason = ""
                            }
                            .onFailure { message = it.message ?: "خطا در ابطال فاکتور" }
                    }
                }) { Text("تایید ابطال") }
            },
            dismissButton = {
                TextButton(onClick = { pendingVoid = null; voidReason = "" }) { Text("انصراف") }
            }
        )
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

private fun statusFa(status: String): String = when (status) {
    "POSTED" -> "ثبت نهایی"
    "VOIDED" -> "باطل‌شده"
    "REVERSAL" -> "سند معکوس سیستمی"
    else -> status
}
