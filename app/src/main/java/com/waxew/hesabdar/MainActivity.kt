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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.waxew.hesabdar.data.AppDatabase
import com.waxew.hesabdar.security.PinSecurityManager
import java.text.NumberFormat
import java.util.Locale

/** Activity اصلی نرم‌افزار حسابدار. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val database = AppDatabase.get(this)
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    SecureRoot(database)
                }
            }
        }
    }
}

/** لایه قفل محلی؛ در صورت فعال بودن PIN، داده‌های مالی قبل از احراز نمایش داده نمی‌شوند. */
@Composable
private fun SecureRoot(database: AppDatabase) {
    val context = LocalContext.current
    val security = remember { PinSecurityManager(context) }
    var unlocked by remember { mutableStateOf(!security.hasPin()) }
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }

    if (unlocked) {
        HesabdarApp(database)
        return
    }

    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("حسابدار قفل است", style = MaterialTheme.typography.headlineMedium)
        TextField(
            value = pin,
            onValueChange = { pin = it.filter(Char::isDigit).take(12) },
            label = { Text("PIN") },
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
        )
        Button(
            onClick = {
                if (security.verifyPin(pin)) {
                    unlocked = true
                    error = ""
                } else error = "PIN اشتباه است."
            },
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
        ) { Text("باز کردن برنامه") }
        if (error.isNotBlank()) Text(error, modifier = Modifier.padding(top = 8.dp))
    }
}

/** پوسته اصلی و Navigation نرم‌افزار. */
@Composable
private fun HesabdarApp(database: AppDatabase) {
    var tab by remember { mutableIntStateOf(0) }
    val persons by database.personDao().observeAll().collectAsState(initial = emptyList())
    val products by database.productDao().observeAll().collectAsState(initial = emptyList())
    val invoices by database.invoiceDao().observeAll().collectAsState(initial = emptyList())
    val sales by database.dashboardDao().observeSalesTotal().collectAsState(initial = 0L)
    val purchases by database.dashboardDao().observePurchasesTotal().collectAsState(initial = 0L)
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
                NavigationBarItem(tab == 2, { tab = 2 }, { Icon(Icons.Default.Inventory2, null) }, label = { Text("انبار") })
                NavigationBarItem(tab == 3, { tab = 3 }, { Icon(Icons.Default.AccountBalanceWallet, null) }, label = { Text("فاکتور") })
                NavigationBarItem(tab == 4, { tab = 4 }, { Icon(Icons.Default.AccountBalance, null) }, label = { Text("حسابداری") })
                NavigationBarItem(tab == 5, { tab = 5 }, { Icon(Icons.Default.Settings, null) }, label = { Text("ابزار") })
            }
        }
    ) { padding ->
        val contentModifier = Modifier.padding(padding)
        when (tab) {
            0 -> DashboardScreen(
                persons = persons.size,
                products = products.size,
                invoices = invoices.size,
                sales = sales,
                purchases = purchases,
                receivables = receivables,
                payables = payables,
                income = otherIncome,
                expenses = expenses,
                pendingChecks = pendingChecks.size,
                modifier = contentModifier
            )
            1 -> PeopleScreen(database, persons, contentModifier)
            2 -> ProductManagementScreen(database, products, contentModifier)
            3 -> InvoiceComposerScreen(database, persons, products, invoices, contentModifier)
            4 -> AdvancedAccountingHub(database, persons, contentModifier)
            else -> DataToolsScreen(database, contentModifier)
        }
    }
}

/** داشبورد مدیریتی اصلی. */
@Composable
private fun DashboardScreen(
    persons: Int,
    products: Int,
    invoices: Int,
    sales: Long,
    purchases: Long,
    receivables: Long,
    payables: Long,
    income: Long,
    expenses: Long,
    pendingChecks: Int,
    modifier: Modifier = Modifier
) {
    val f = remember { NumberFormat.getNumberInstance(Locale.US) }
    val netSimple = sales + income - purchases - expenses
    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("حسابدار", style = MaterialTheme.typography.headlineMedium); Text("هسته حسابداری آفلاین — اطلاعات روی گوشی") }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { Summary("اشخاص", persons.toString(), Modifier.weight(1f)); Summary("کالا", products.toString(), Modifier.weight(1f)) } }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { Summary("اسناد تجاری", invoices.toString(), Modifier.weight(1f)); Summary("چک باز", pendingChecks.toString(), Modifier.weight(1f)) } }
        item { Summary("فروش خالص", "${f.format(sales)} تومان") }
        item { Summary("خرید خالص", "${f.format(purchases)} تومان") }
        item { Summary("مطالبات", "${f.format(receivables)} تومان") }
        item { Summary("بدهی", "${f.format(payables)} تومان") }
        item { Summary("سایر درآمد", "${f.format(income)} تومان") }
        item { Summary("هزینه", "${f.format(expenses)} تومان") }
        item { Summary("خالص ساده", "${f.format(netSimple)} تومان") }
    }
}

@Composable
private fun Summary(title: String, value: String, modifier: Modifier = Modifier.fillMaxWidth()) {
    Card(modifier) { Column(Modifier.padding(12.dp)) { Text(title); Text(value, style = MaterialTheme.typography.titleMedium) } }
}
