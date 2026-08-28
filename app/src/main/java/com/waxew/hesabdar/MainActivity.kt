package com.waxew.hesabdar

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalLayoutDirection
import com.waxew.hesabdar.data.AppDatabase
import com.waxew.hesabdar.data.PersonEntity
import com.waxew.hesabdar.data.ProductEntity
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val database = AppDatabase.get(this)

        setContent {
            MaterialTheme {
                androidx.compose.runtime.CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    HesabdarApp(database)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HesabdarApp(database: AppDatabase) {
    var tab by remember { mutableIntStateOf(0) }
    val persons by database.personDao().observeAll().collectAsState(initial = emptyList())
    val products by database.productDao().observeAll().collectAsState(initial = emptyList())

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Icon(Icons.Default.Home, null) }, label = { Text("خانه") })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Icon(Icons.Default.People, null) }, label = { Text("اشخاص") })
                NavigationBarItem(selected = tab == 2, onClick = { tab = 2 }, icon = { Icon(Icons.Default.Inventory2, null) }, label = { Text("کالاها") })
            }
        }
    ) { padding ->
        when (tab) {
            0 -> Dashboard(persons.size, products.size, Modifier.padding(padding))
            1 -> PersonsScreen(database, persons, Modifier.padding(padding))
            else -> ProductsScreen(database, products, Modifier.padding(padding))
        }
    }
}

@Composable
private fun Dashboard(personCount: Int, productCount: Int, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("حسابدار", style = MaterialTheme.typography.headlineMedium)
        Text("نسخه آزمایشی 0.1.0 — اطلاعات روی همین گوشی ذخیره می‌شود.")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SummaryCard("طرف حساب", personCount.toString(), Modifier.weight(1f))
            SummaryCard("کالا", productCount.toString(), Modifier.weight(1f))
        }
        SummaryCard("فروش امروز", "0 تومان", Modifier.fillMaxWidth())
        SummaryCard("مطالبات", "0 تومان", Modifier.fillMaxWidth())
        Text("در نسخه‌های بعد: فروش، خرید، دریافت/پرداخت، انبار، چک و گزارش‌های حسابداری.")
    }
}

@Composable
private fun SummaryCard(title: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(value, style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun PersonsScreen(database: AppDatabase, persons: List<PersonEntity>, modifier: Modifier = Modifier) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("اشخاص و طرف‌حساب‌ها", style = MaterialTheme.typography.headlineSmall)
        TextField(name, { name = it }, label = { Text("نام") }, modifier = Modifier.fillMaxWidth())
        TextField(phone, { phone = it }, label = { Text("شماره تماس") }, modifier = Modifier.fillMaxWidth())
        Button(
            onClick = {
                if (name.isNotBlank()) scope.launch {
                    database.personDao().insert(PersonEntity(name = name.trim(), phone = phone.trim()))
                    name = ""
                    phone = ""
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Icon(Icons.Default.Add, null); Text(" افزودن شخص") }

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

@Composable
private fun ProductsScreen(database: AppDatabase, products: List<ProductEntity>, modifier: Modifier = Modifier) {
    var name by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val formatter = remember { NumberFormat.getNumberInstance(Locale.US) }

    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("کالاها", style = MaterialTheme.typography.headlineSmall)
        TextField(name, { name = it }, label = { Text("نام کالا") }, modifier = Modifier.fillMaxWidth())
        TextField(price, { price = it.filter(Char::isDigit) }, label = { Text("قیمت فروش") }, modifier = Modifier.fillMaxWidth())
        Button(
            onClick = {
                val sellPrice = price.toLongOrNull() ?: 0
                if (name.isNotBlank()) scope.launch {
                    database.productDao().insert(ProductEntity(name = name.trim(), sellPrice = sellPrice))
                    name = ""
                    price = ""
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Icon(Icons.Default.Add, null); Text(" افزودن کالا") }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(products, key = { it.id }) { product ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text(product.name, style = MaterialTheme.typography.titleMedium)
                        Text("قیمت فروش: ${formatter.format(product.sellPrice)} تومان")
                    }
                }
            }
        }
    }
}
