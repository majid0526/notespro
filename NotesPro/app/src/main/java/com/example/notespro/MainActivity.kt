package com.example.notespro

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

const val FREE_LIMIT = 10

data class Note(val id: Long, val title: String, val body: String)

/** ذخیره‌سازی محلی ساده با SharedPreferences (بدون سرور و بدون وابستگی اضافه) */
class Store(ctx: Context) {
    private val sp = ctx.getSharedPreferences("notes", Context.MODE_PRIVATE)
    var isPro: Boolean
        get() = sp.getBoolean("pro", false)
        set(v) = sp.edit().putBoolean("pro", v).apply()

    fun load(): List<Note> {
        val arr = JSONArray(sp.getString("data", "[]"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Note(o.getLong("id"), o.getString("title"), o.getString("body"))
        }
    }
    fun save(list: List<Note>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("title", it.title).put("body", it.body)) }
        sp.edit().putString("data", arr.toString()).apply()
    }
}

class MainActivity : ComponentActivity() {
    private lateinit var billing: MyketBilling
    private var pro by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = Store(this)
        pro = store.isPro
        billing = MyketBilling(this) { store.isPro = true; pro = true }
        billing.start()
        setContent {
            MaterialTheme { NotesApp(store, pro) { billing.buy() } }
        }
    }

    override fun onResume() { super.onResume(); billing.refresh() }
    override fun onDestroy() { billing.stop(); super.onDestroy() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesApp(store: Store, pro: Boolean, onBuy: () -> Unit) {
    var notes by remember { mutableStateOf(store.load()) }
    var editing by remember { mutableStateOf<Note?>(null) }
    var showPaywall by remember { mutableStateOf(false) }

    fun update(list: List<Note>) { notes = list; store.save(list) }

    if (editing != null) {
        val n = editing!!
        var title by remember(n.id) { mutableStateOf(n.title) }
        var body by remember(n.id) { mutableStateOf(n.body) }
        fun commit() {
            if (title.isNotBlank() || body.isNotBlank()) {
                val saved = n.copy(title = title, body = body)
                update(if (notes.any { it.id == n.id }) notes.map { if (it.id == n.id) saved else it } else listOf(saved) + notes)
            }
            editing = null
        }
        BackHandler { commit() }
        Scaffold(topBar = {
            TopAppBar(
                title = { Text("ویرایش") },
                navigationIcon = { IconButton(onClick = { commit() }) { Icon(Icons.Default.ArrowBack, "بازگشت") } },
                actions = {
                    IconButton(onClick = { update(notes.filter { it.id != n.id }); editing = null }) {
                        Icon(Icons.Default.Delete, "حذف")
                    }
                }
            )
        }) { pad ->
            Column(Modifier.padding(pad).padding(16.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("عنوان") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(body, { body = it }, label = { Text("متن") }, modifier = Modifier.fillMaxSize())
            }
        }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(if (pro) "یادداشت Pro ⭐" else "یادداشت‌ها (${notes.size}/$FREE_LIMIT)") },
                actions = { if (!pro) TextButton(onClick = { showPaywall = true }) { Text("Pro") } })
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                if (!pro && notes.size >= FREE_LIMIT) showPaywall = true
                else editing = Note(System.currentTimeMillis(), "", "")
            }) { Icon(Icons.Default.Add, "یادداشت جدید") }
        }
    ) { pad ->
        if (notes.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Text("هنوز یادداشتی نداری. با + شروع کن.")
            }
        } else LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(notes, key = { it.id }) { n ->
                Card(Modifier.fillMaxWidth().clickable { editing = n }) {
                    Column(Modifier.padding(16.dp)) {
                        Text(n.title.ifBlank { "بدون عنوان" }, style = MaterialTheme.typography.titleMedium)
                        if (n.body.isNotBlank()) Text(n.body, maxLines = 2, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }

    if (showPaywall) AlertDialog(
        onDismissRequest = { showPaywall = false },
        title = { Text("ارتقا به Pro") },
        text = { Text("نسخه رایگان تا $FREE_LIMIT یادداشت داره. با یک بار پرداخت، یادداشت نامحدود داشته باش — بدون اشتراک.") },
        confirmButton = { Button(onClick = { showPaywall = false; onBuy() }) { Text("خرید Pro") } },
        dismissButton = { TextButton(onClick = { showPaywall = false }) { Text("بعداً") } }
    )
}
