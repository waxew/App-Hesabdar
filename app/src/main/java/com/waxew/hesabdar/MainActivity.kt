package com.waxew.hesabdar

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.waxew.hesabdar.data.AppDatabase
import com.waxew.hesabdar.data.LedgerBootstrapper
import com.waxew.hesabdar.reminder.ReminderScheduler
import com.waxew.hesabdar.security.PinSecurityManager
import com.waxew.hesabdar.settings.AppearanceSettings
import com.waxew.hesabdar.settings.BusinessSettings
import com.waxew.hesabdar.util.PersianDateConverter
import kotlinx.coroutines.launch
import java.io.File
import java.text.NumberFormat
import java.util.Locale

/** Activity اصلی نرم‌افزار حسابدار. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()
        val database = AppDatabase.get(this)
        setContent {
            val systemDark = isSystemInDarkTheme()
            val appearance = remember { AppearanceSettings(this) }
            val mode = remember { appearance.getThemeMode() }
            val useDark = when (mode) {
                AppearanceSettings.DARK -> true
                AppearanceSettings.LIGHT -> false
                else -> systemDark
            }
            MaterialTheme(colorScheme = if (useDark) darkColorScheme() else lightColorScheme()) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    SecureRoot(database)
                }
            }
        }
    }

    /** Android 13+ برای اعلان سررسیدها مجوز Runtime لازم دارد. */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1201)
        }
    }
}

/** قفل برنامه + راه‌اندازی کدینگ و Alarmهای آفلاین. */
@Composable
private fun SecureRoot(database: AppDatabase) {
    val context = LocalContext.current
    val security = remember { PinSecurityManager(context) }
    var unlocked by remember { mutableStateOf(!security.hasPin()) }
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }

    LaunchedEffect(database) {
        runCatching { LedgerBootstrapper(database).ensureDefaults() }
        runCatching { ReminderScheduler(context, database).scheduleExisting() }
    }

    if (unlocked) {
        HesabdarApp(database)
        return
    }

    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("حسابدار قفل است", style = MaterialTheme.typography.headlineMedium)
        TextField(pin, { pin = it.filter(Char::isDigit).take(12) }, label = { Text("PIN") }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp))
        Button(
            onClick = { if (security.verifyPin(pin)) { unlocked = true; error = "" } else error = "PIN اشتباه است." },
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
        ) { Text("باز کردن برنامه") }
        if (error.isNotBlank()) Text(error, modifier = Modifier.padding(top = 8.dp))
    }
}

/** پوسته اصلی با Drawer استاندارد، Bottom Navigation و صفحات جانبی. */
@Composable
private fun HesabdarApp(database: AppDatabase) {
    val context = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val businessSettings = remember { BusinessSettings(context) }
    val business = remember { businessSettings.load() }
    var profileRevision by remember { mutableIntStateOf(0) }
    val profileFile = remember { File(context.filesDir, "hesabdar_profile.jpg") }
    val profileBitmap = remember(profileRevision) {
        if (profileFile.exists()) BitmapFactory.decodeFile(profileFile.absolutePath)?.asImageBitmap() else null
    }
    val imageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    profileFile.outputStream().use { output -> input.copyTo(output) }
                }
            }.onSuccess { profileRevision++ }
        }
    }

    val persons by database.personDao().observeAll().collectAsState(initial = emptyList())
    val products by database.productDao().observeAll().collectAsState(initial = emptyList())
    val invoices by database.invoiceDao().observeAll().collectAsState(initial = emptyList())
    val sales by database.dashboardDao().observeSalesTotal().collectAsState(initial = 0L)
    val purchases by database.dashboardDao().observePurchasesTotal().collectAsState(initial = 0L)
    val receivables by database.dashboardDao().observeReceivables().collectAsState(initial = 0L)
    val payables by database.dashboardDao().observePayables().collectAsState(initial = 0L)
    val lowStockCount by database.dashboardDao().observeLowStockCount().collectAsState(initial = 0)
    val expenses by database.cashEntryDao().observeExpenses().collectAsState(initial = 0L)
    val otherIncome by database.cashEntryDao().observeOtherIncome().collectAsState(initial = 0L)
    val pendingChecks by database.checkDao().observePending().collectAsState(initial = emptyList())

    fun openPage(page: Int) {
        tab = page
        scope.launch { drawerState.close() }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (profileBitmap != null) {
                        Image(
                            bitmap = profileBitmap,
                            contentDescription = "تصویر پروفایل",
                            modifier = Modifier.size(92.dp).clip(CircleShape).clickable { imageLauncher.launch("image/*") }
                        )
                    } else {
                        Icon(
                            Icons.Default.AccountCircle,
                            contentDescription = "افزودن تصویر پروفایل",
                            modifier = Modifier.size(92.dp).clickable { imageLauncher.launch("image/*") }
                        )
                    }
                    Text(business.name, style = MaterialTheme.typography.titleLarge)
                    Text("برای تغییر تصویر، عکس پروفایل را لمس کنید.")
                }
                HorizontalDivider()
                DrawerItem("خانه", tab == 0) { openPage(0) }
                DrawerItem("اشخاص و طرف‌حساب‌ها", tab == 1) { openPage(1) }
                DrawerItem("کالا و انبار", tab == 2) { openPage(2) }
                DrawerItem("فاکتور و معاملات", tab == 3) { openPage(3) }
                DrawerItem("حسابداری حرفه‌ای", tab == 4) { openPage(4) }
                DrawerItem("تنظیمات و ابزارها", tab == 5) { openPage(5) }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                NavigationDrawerItem(
                    label = { Text("ارتباط با ما") },
                    selected = tab == 6,
                    onClick = { openPage(6) },
                    icon = { Icon(Icons.Default.Mail, null) }
                )
                NavigationDrawerItem(
                    label = { Text("درباره نرم‌افزار") },
                    selected = tab == 7,
                    onClick = { openPage(7) },
                    icon = { Icon(Icons.Default.Info, null) }
                )
                Column(Modifier.fillMaxWidth().padding(20.dp)) {
                    HorizontalDivider()
                    Text("گروه توسعه فناوری و نرم افزاری as Team", modifier = Modifier.padding(top = 12.dp))
                    Text("AS.Support.info@Gmail.com")
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(pageTitle(tab)) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "منو")
                        }
                    }
                )
            },
            bottomBar = {
                if (tab <= 5) {
                    NavigationBar {
                        NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Default.Home, null) }, label = { Text("خانه") })
                        NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.Default.People, null) }, label = { Text("اشخاص") })
                        NavigationBarItem(tab == 2, { tab = 2 }, { Icon(Icons.Default.Inventory2, null) }, label = { Text("انبار") })
                        NavigationBarItem(tab == 3, { tab = 3 }, { Icon(Icons.Default.AccountBalanceWallet, null) }, label = { Text("فاکتور") })
                        NavigationBarItem(tab == 4, { tab = 4 }, { Icon(Icons.Default.AccountBalance, null) }, label = { Text("حسابداری") })
                        NavigationBarItem(tab == 5, { tab = 5 }, { Icon(Icons.Default.Settings, null) }, label = { Text("ابزار") })
                    }
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
                    lowStockCount = lowStockCount,
                    modifier = contentModifier
                )
                1 -> PeopleScreen(database, persons, contentModifier)
                2 -> ProductManagementScreen(database, products, contentModifier)
                3 -> InvoiceComposerScreen(database, persons, products, invoices, contentModifier)
                4 -> AdvancedAccountingHub(database, persons, contentModifier)
                5 -> DataToolsScreen(database, contentModifier)
                6 -> ContactScreen(contentModifier)
                else -> AboutScreen(contentModifier)
            }
        }
    }
}

/** آیتم تکرارشونده Drawer برای کاهش کد تکراری. */
@Composable
private fun DrawerItem(label: String, selected: Boolean, onClick: () -> Unit) {
    NavigationDrawerItem(label = { Text(label) }, selected = selected, onClick = onClick)
}

private fun pageTitle(tab: Int): String = when (tab) {
    0 -> "داشبورد"
    1 -> "اشخاص"
    2 -> "کالا و انبار"
    3 -> "فاکتور"
    4 -> "حسابداری"
    5 -> "تنظیمات"
    6 -> "ارتباط با ما"
    else -> "درباره نرم‌افزار"
}

@Composable
private fun ContactScreen(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("ارتباط با ما", style = MaterialTheme.typography.headlineMedium)
        Text("برای پشتیبانی، گزارش خطا و پیشنهاد توسعه می‌توانید از ایمیل زیر استفاده کنید.")
        Text("AS.Support.info@Gmail.com", style = MaterialTheme.typography.titleMedium)
        HorizontalDivider(Modifier.padding(top = 40.dp))
        Text("گروه توسعه فناوری و نرم افزاری as Team", style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun AboutScreen(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("حسابدار", style = MaterialTheme.typography.headlineMedium)
        Text("نرم‌افزار حسابداری فارسی و آفلاین برای مدیریت فروش، خرید، اشخاص، کالا و خدمت، انبار، خزانه، چک، اقساط و گزارش‌های مالی.")
        Text("نسخه 0.10.0 Beta")
        Text("اطلاعات اصلی برنامه در دیتابیس محلی دستگاه ذخیره می‌شوند.")
    }
}

/** داشبورد مدیریتی اصلی با هشدار موجودی پایین. */
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
    lowStockCount: Int,
    modifier: Modifier = Modifier
) {
    val formatter = remember { NumberFormat.getNumberInstance(Locale.US) }
    val netSimple = sales + income - purchases - expenses
    val today = remember { PersianDateConverter.now().toString() }

    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("حسابدار", style = MaterialTheme.typography.headlineMedium)
            Text("تاریخ شمسی: $today")
            Text("اطلاعات اصلی روی همین گوشی ذخیره می‌شود.")
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Summary("اشخاص", persons.toString(), Modifier.weight(1f))
                Summary("کالا / خدمت", products.toString(), Modifier.weight(1f))
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Summary("اسناد تجاری", invoices.toString(), Modifier.weight(1f))
                Summary("چک باز", pendingChecks.toString(), Modifier.weight(1f))
            }
        }
        if (lowStockCount > 0) item { Summary("نیازمند تامین موجودی", "$lowStockCount کالا") }
        item { Summary("فروش خالص", "${formatter.format(sales)} تومان") }
        item { Summary("خرید خالص", "${formatter.format(purchases)} تومان") }
        item { Summary("مطالبات", "${formatter.format(receivables)} تومان") }
        item { Summary("بدهی", "${formatter.format(payables)} تومان") }
        item { Summary("سایر درآمد", "${formatter.format(income)} تومان") }
        item { Summary("هزینه", "${formatter.format(expenses)} تومان") }
        item { Summary("خالص ساده", "${formatter.format(netSimple)} تومان") }
    }
}

@Composable
private fun Summary(title: String, value: String, modifier: Modifier = Modifier.fillMaxWidth()) {
    Card(modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(title)
            Text(value, style = MaterialTheme.typography.titleMedium)
        }
    }
}
