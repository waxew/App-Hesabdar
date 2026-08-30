package com.waxew.hesabdar

import androidx.activity.compose.BackHandler
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
import com.waxew.hesabdar.data.InventoryRepository
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
    var selectedId by remember { mutableStateOf<Long?>(null) }
    if (selectedId != null) {
        // Back سیستم ابتدا از جزئیات/کارتکس به فهرست کالا برمی‌گردد.
        BackHandler { selectedId = null }
        InventoryCardScreen(database, selectedId!!, onBack = { selectedId = null }, modifier = modifier)
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
    val inventoryRepository = remember(database) { InventoryRepository(database) }
    val formatter = remember { NumberFormat.getNumberInstance(Locale.US) }
    val lowStock by database.productDao().observeLowStock().collectAsState(initial = emptyList())
    val filtered = remember(products, search) {
        val query = search.trim()
        if (query.isBlank()) products else products.filter { product ->
            product.name.contains(query, ignoreCase = true) ||
                product.sku.contains(query, ignoreCase = true) ||
                product.barcode.contains(query, ignoreCase = true) ||
                product.category.contains(query, ignoreCase = true)
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
                            // ثبت از Repository باعث می‌شود موجودی اولیه همزمان در کارتکس و Audit ثبت شود.
                            inventoryRepository.createCatalogItem(
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
            Card(Modifier.fillMaxWidth().clickable { selectedId = product.id }) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(product.name + if (product.isService) " — خدمت" else "")
                    if (product.sku.isNotBlank()) Text("کد: ${product.sku}")
                    if (product.barcode.isNotBlank()) Text("بارکد: ${product.barcode}")
                    if (product.category.isNotBlank()) Text("دسته: ${product.category}")
                    Text("خرید ${formatter.format(product.buyPrice)} | فروش ${formatter.format(product.sellPrice)} تومان")
                    if (!product.isService) {
                        Text("موجودی ${formatter.format(product.stock)} ${product.unit}${if (isLow) " — نیاز به تامین" else ""}")
                    }
                    Text("برای جزئیات، ویرایش و کارتکس لمس کنید")
                }
            }
        }
    }
}

/**
 * صفحه جزئیات زنده کالا/خدمت.
 * اصلاح موجودی فقط از InventoryRepository انجام می‌شود تا کارتکس و Audit همیشه همزمان ثبت شوند.
 */
@Composable
private fun InventoryCardScreen(
    database: AppDatabase,
    productId: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val observedProduct by database.productDao().observeById(productId).collectAsState(initial = null)
    val product = observedProduct
    val movements by database.reportingDao().observeInventoryCard(productId).collectAsState(initial = emptyList())
    val formatter = remember { NumberFormat.getNumberInstance(Locale.US) }
    val date = remember { SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US) }
    val inventoryRepository = remember(database) { InventoryRepository(database) }
    val scope = rememberCoroutineScope()

    var editMode by remember { mutableStateOf(false) }
    var adjustmentText by remember { mutableStateOf("") }
    var adjustmentDirection by remember { mutableStateOf("IN") }
    var adjustmentReason by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }

    if (product == null) {
        Column(modifier.fillMaxSize().padding(12.dp)) {
            OutlinedButton(onClick = onBack) { Text("بازگشت") }
            Text("کالا پیدا نشد.")
        }
        return
    }

    var editName by remember(product.id, editMode) { mutableStateOf(product.name) }
    var editSku by remember(product.id, editMode) { mutableStateOf(product.sku) }
    var editBarcode by remember(product.id, editMode) { mutableStateOf(product.barcode) }
    var editCategory by remember(product.id, editMode) { mutableStateOf(product.category) }
    var editUnit by remember(product.id, editMode) { mutableStateOf(product.unit) }
    var editBuy by remember(product.id, editMode) { mutableStateOf(product.buyPrice.toString()) }
    var editSell by remember(product.id, editMode) { mutableStateOf(product.sellPrice.toString()) }
    var editMinStock by remember(product.id, editMode) { mutableStateOf(product.minStock.toString()) }
    var editIsService by remember(product.id, editMode) { mutableStateOf(product.isService) }

    LazyColumn(modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("بازگشت") }
                OutlinedButton(onClick = { editMode = !editMode }, modifier = Modifier.weight(1f)) {
                    Text(if (editMode) "لغو ویرایش" else "ویرایش مشخصات")
                }
            }
        }

        if (!editMode) {
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
        } else {
            item { Text("ویرایش مشخصات کاتالوگ") }
            item { TextField(editName, { editName = it }, label = { Text("نام") }, modifier = Modifier.fillMaxWidth()) }
            item { TextField(editSku, { editSku = it }, label = { Text("SKU") }, modifier = Modifier.fillMaxWidth()) }
            item { TextField(editBarcode, { editBarcode = it.filter(Char::isDigit) }, label = { Text("بارکد") }, modifier = Modifier.fillMaxWidth()) }
            item { TextField(editCategory, { editCategory = it }, label = { Text("دسته‌بندی") }, modifier = Modifier.fillMaxWidth()) }
            item { TextField(editUnit, { editUnit = it }, label = { Text("واحد") }, modifier = Modifier.fillMaxWidth()) }
            item { TextField(editBuy, { editBuy = it.filter(Char::isDigit) }, label = { Text("قیمت خرید") }, modifier = Modifier.fillMaxWidth()) }
            item { TextField(editSell, { editSell = it.filter(Char::isDigit) }, label = { Text("قیمت فروش") }, modifier = Modifier.fillMaxWidth()) }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Checkbox(checked = editIsService, onCheckedChange = { editIsService = it })
                    Text("خدمت است", modifier = Modifier.padding(top = 12.dp))
                }
            }
            if (!editIsService) {
                item { TextField(editMinStock, { editMinStock = it.filter(Char::isDigit) }, label = { Text("حداقل موجودی") }, modifier = Modifier.fillMaxWidth()) }
            }
            item {
                Button(onClick = {
                    scope.launch {
                        runCatching {
                            inventoryRepository.updateCatalog(
                                product.copy(
                                    name = editName.trim(),
                                    sku = editSku.trim(),
                                    barcode = editBarcode.trim(),
                                    category = editCategory.trim(),
                                    unit = editUnit.trim().ifBlank { "عدد" },
                                    buyPrice = editBuy.toLongOrNull() ?: 0,
                                    sellPrice = editSell.toLongOrNull() ?: 0,
                                    minStock = editMinStock.toLongOrNull() ?: 0,
                                    isService = editIsService
                                )
                            )
                        }.onSuccess {
                            editMode = false
                            message = "مشخصات بروزرسانی شد."
                        }.onFailure { message = it.message ?: "خطا در ویرایش" }
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("ذخیره ویرایش") }
            }
        }

        if (!product.isService) {
            item { Text("اصلاح دستی موجودی") }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { adjustmentDirection = "IN" }, modifier = Modifier.weight(1f)) {
                        Text(if (adjustmentDirection == "IN") "✓ افزایش" else "افزایش")
                    }
                    OutlinedButton(onClick = { adjustmentDirection = "OUT" }, modifier = Modifier.weight(1f)) {
                        Text(if (adjustmentDirection == "OUT") "✓ کاهش" else "کاهش")
                    }
                }
            }
            item { TextField(adjustmentText, { adjustmentText = it.filter(Char::isDigit) }, label = { Text("مقدار اصلاح") }, modifier = Modifier.fillMaxWidth()) }
            item { TextField(adjustmentReason, { adjustmentReason = it }, label = { Text("علت اصلاح، مثل شمارش انبار یا ضایعات") }, modifier = Modifier.fillMaxWidth()) }
            item {
                Button(onClick = {
                    val amount = adjustmentText.toLongOrNull() ?: 0
                    if (amount <= 0) {
                        message = "مقدار اصلاح را وارد کنید."
                        return@Button
                    }
                    val delta = if (adjustmentDirection == "IN") amount else -amount
                    scope.launch {
                        runCatching { inventoryRepository.adjustStock(product.id, delta, adjustmentReason) }
                            .onSuccess {
                                adjustmentText = ""
                                adjustmentReason = ""
                                message = "اصلاح موجودی ثبت شد."
                            }
                            .onFailure { message = it.message ?: "خطا در اصلاح موجودی" }
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("ثبت اصلاح موجودی") }
            }
        }

        if (message.isNotBlank()) item { Text(message) }

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

/** تبدیل نوع فنی گردش انبار به عنوان فارسی قابل فهم. */
private fun movementFa(type: String): String = when (type) {
    "OPENING" -> "موجودی افتتاحیه"
    "SALE" -> "خروج بابت فروش"
    "PURCHASE" -> "ورود بابت خرید"
    "SALE_RETURN" -> "ورود بابت برگشت فروش"
    "PURCHASE_RETURN" -> "خروج بابت برگشت خرید"
    "ADJUSTMENT_IN" -> "افزایش دستی موجودی"
    "ADJUSTMENT_OUT" -> "کاهش دستی موجودی"
    else -> type
}
