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
import com.waxew.hesabdar.data.PersonEntity
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

/** مدیریت طرف‌حساب و نمایش مانده محاسبه‌شده از گردش واقعی. */
@Composable
fun PeopleScreen(
    database: AppDatabase,
    persons: List<PersonEntity>,
    modifier: Modifier = Modifier
) {
    var selected by remember { mutableStateOf<PersonEntity?>(null) }
    if (selected != null) {
        PersonProfileScreen(database, selected!!, onBack = { selected = null }, modifier = modifier)
        return
    }

    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Column(modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("اشخاص و طرف‌حساب‌ها")
        TextField(name, { name = it }, label = { Text("نام / نام شرکت") }, modifier = Modifier.fillMaxWidth())
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
        ) { Text("افزودن طرف‌حساب") }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(persons, key = { it.id }) { person ->
                Card(Modifier.fillMaxWidth().clickable { selected = person }) {
                    Column(Modifier.padding(12.dp)) {
                        Text(person.name)
                        if (person.phone.isNotBlank()) Text(person.phone)
                        Text("برای مشاهده گردش حساب لمس کنید")
                    }
                }
            }
        }
    }
}

/** پروفایل مالی طرف‌حساب. */
@Composable
private fun PersonProfileScreen(
    database: AppDatabase,
    person: PersonEntity,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sales by database.reportingDao().observePersonSales(person.id).collectAsState(initial = 0L)
    val purchases by database.reportingDao().observePersonPurchases(person.id).collectAsState(initial = 0L)
    val received by database.reportingDao().observePersonReceived(person.id).collectAsState(initial = 0L)
    val paid by database.reportingDao().observePersonPaid(person.id).collectAsState(initial = 0L)
    val f = remember { NumberFormat.getNumberInstance(Locale.US) }

    val customerBalance = sales - received
    val supplierBalance = purchases - paid
    val net = customerBalance - supplierBalance

    LazyColumn(modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { OutlinedButton(onClick = onBack) { Text("بازگشت") } }
        item { Text(person.name) }
        if (person.phone.isNotBlank()) item { Text(person.phone) }
        item { MetricCard("کل فروش", sales, f) }
        item { MetricCard("کل دریافت", received, f) }
        item { MetricCard("کل خرید", purchases, f) }
        item { MetricCard("کل پرداخت", paid, f) }
        item { MetricCard("مانده مشتری", customerBalance, f) }
        item { MetricCard("مانده تامین‌کننده", supplierBalance, f) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("خالص حساب")
                    Text("${f.format(kotlin.math.abs(net))} تومان")
                    Text(if (net > 0) "بدهکار به کسب‌وکار" else if (net < 0) "بستانکار از کسب‌وکار" else "تسویه")
                }
            }
        }
    }
}

@Composable
private fun MetricCard(title: String, value: Long, formatter: NumberFormat) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title)
            Text("${formatter.format(value)} تومان")
        }
    }
}
