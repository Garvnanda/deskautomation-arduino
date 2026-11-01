package dev.deskmatrix

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

val Red = Color(0xFFFF3B30)
val Mono = FontFamily.Monospace

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("dm", Context.MODE_PRIVATE)
        setContent {
            var dark by remember { mutableStateOf(prefs.getBoolean("dark", true)) }
            val scheme = if (dark)
                darkColorScheme(primary = Red, background = Color(0xFF0B0B0D), surface = Color(0xFF141417))
            else lightColorScheme(primary = Color(0xFFD70015), background = Color(0xFFF4F4F6))
            MaterialTheme(colorScheme = scheme, typography = Typography(
                bodyLarge = TextStyle(fontFamily = Mono, fontSize = 15.sp),
                bodyMedium = TextStyle(fontFamily = Mono, fontSize = 13.sp),
                titleMedium = TextStyle(fontFamily = Mono, fontSize = 17.sp),
            )) {
                App(dark) { dark = it; prefs.edit().putBoolean("dark", it).apply() }
            }
        }
    }
}

/** Polls the board and runs API calls off the main thread. */
class BoardVm(private val ctx: Context, private val scope: kotlinx.coroutines.CoroutineScope) {
    var state by mutableStateOf<JSONObject?>(null)
    var error by mutableStateOf<String?>(null)

    suspend fun poll() {
        while (true) {
            state = withContext(Dispatchers.IO) { Board.call(ctx, "GET", "/state") }
            delay(300)
        }
    }

    fun send(method: String, path: String, body: JSONObject? = null) = scope.launch {
        val r = withContext(Dispatchers.IO) { Board.call(ctx, method, path, body) }
        error = r?.optString("error")?.takeIf { it.isNotEmpty() } ?: if (r == null) "Board unreachable" else null
    }
}

fun obj(vararg kv: Pair<String, Any>) = JSONObject().apply { kv.forEach { (k, v) -> put(k, v) } }

@Composable
fun App(dark: Boolean, setDark: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val vm = remember { BoardVm(ctx, scope) }
    LaunchedEffect(Unit) { vm.poll() }
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("HOME", "TASKS", "TOOLS", "SETUP")

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, t ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = {},
                        label = { Text(t, fontFamily = Mono) })
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).fillMaxSize()) {
            vm.error?.let { Text("! $it", color = Red) }
            when (tab) {
                0 -> Home(vm)
                1 -> Tasks(vm)
                2 -> Tools(vm)
                else -> Setup(vm, dark, setDark)
            }
        }
    }
}

@Composable
fun Matrix(frame: String) {
    Column(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(Color.Black).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (y in 0 until 8) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (x in 0 until 13) {
                val on = frame.getOrNull(y * 13 + x) == '1'
                Box(Modifier.weight(1f).aspectRatio(1f).clip(CircleShape)
                    .background(if (on) Red else Color(0xFF2A1010)))
            }
        }
    }
}

@Composable
fun Home(vm: BoardVm) {
    val s = vm.state
    val settings = s?.optJSONObject("settings")
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(if (s == null) "○ OFFLINE" else "● CONNECTED", color = if (s == null) Color.Gray else Red,
            style = MaterialTheme.typography.titleMedium)
        Matrix(s?.optString("frame") ?: "")
        if (settings != null) {
            SettingSlider("BRIGHTNESS", settings.optInt("brightness"), 0f..7f, 6) {
                vm.send("POST", "/settings", obj("brightness" to it))
            }
            // Lower ms per pixel = faster; the slider shows speed so right = faster.
            SettingSlider("MESSAGE SPEED", 240 - settings.optInt("scroll_ms"), 40f..200f, 15) {
                vm.send("POST", "/settings", obj("scroll_ms" to 240 - it))
            }
            Toggle("PRIVATE MODE (hide message text)", settings.optBoolean("privacy")) {
                vm.send("POST", "/settings", obj("privacy" to it))
            }
            if (s.optBoolean("alarm_ringing")) Button({ vm.send("POST", "/alarm/dismiss") }) { Text("DISMISS ALARM") }
        }
    }
}

@Composable
fun SettingSlider(label: String, value: Int, range: ClosedFloatingPointRange<Float>, steps: Int, set: (Int) -> Unit) {
    var v by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Text("$label  ${v.toInt()}")
    Slider(v, { v = it }, valueRange = range, steps = steps, onValueChangeFinished = { set(v.toInt()) })
}

@Composable
fun Toggle(label: String, on: Boolean, set: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(on, set)
    }
}

@Composable
fun Tasks(vm: BoardVm) {
    val ctx = LocalContext.current
    var title by remember { mutableStateOf("") }
    var remind by remember { mutableStateOf("10") }
    val due = remember { Calendar.getInstance().apply { add(Calendar.HOUR, 1); set(Calendar.SECOND, 0) } }
    var dueLabel by remember { mutableStateOf(fmt(due.timeInMillis)) }
    val tasks = vm.state?.optJSONArray("tasks").toList()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(title, { title = it }, label = { Text("TASK") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton({
                DatePickerDialog(ctx, { _, y, m, d ->
                    due.set(y, m, d)
                    TimePickerDialog(ctx, { _, h, min ->
                        due.set(Calendar.HOUR_OF_DAY, h); due.set(Calendar.MINUTE, min); dueLabel = fmt(due.timeInMillis)
                    }, due.get(Calendar.HOUR_OF_DAY), due.get(Calendar.MINUTE), false).show()
                }, due.get(Calendar.YEAR), due.get(Calendar.MONTH), due.get(Calendar.DAY_OF_MONTH)).show()
            }) { Text(dueLabel) }
            OutlinedTextField(remind, { remind = it.filter(Char::isDigit) }, label = { Text("REMIND MIN") },
                singleLine = true, modifier = Modifier.width(120.dp))
        }
        Button({
            if (title.isNotBlank()) {
                vm.send("POST", "/tasks", obj("title" to title, "due_at" to due.timeInMillis / 1000,
                    "remind_before_min" to (remind.toIntOrNull() ?: 0)))
                title = ""
            }
        }) { Text("ADD TASK") }
        HorizontalDivider()
        LazyColumn {
            items(tasks.sortedBy { it.optLong("due_at") }) { t ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t.optString("title"))
                        Text("${fmt(t.optLong("due_at") * 1000)} · ${t.optInt("remind_before_min")}m before",
                            color = Color.Gray, style = MaterialTheme.typography.bodyMedium)
                    }
                    TextButton({ vm.send("DELETE", "/tasks/${t.optInt("id")}") }) { Text("DONE") }
                }
            }
        }
    }
}

@Composable
fun Tools(vm: BoardVm) {
    val ctx = LocalContext.current
    val s = vm.state
    var mins by remember { mutableStateOf("5") }
    var work by remember { mutableStateOf("25") }
    var brk by remember { mutableStateOf("5") }
    var label by remember { mutableStateOf("") }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("MODE: ${s?.optString("mode")?.takeIf { it != "null" && it.isNotEmpty() }?.uppercase() ?: "CLOCK"}", color = Red)

        Text("TIMER", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            NumField("MIN", mins) { mins = it }
            Button({ vm.send("POST", "/timer", obj("seconds" to (mins.toIntOrNull() ?: 1) * 60)) }) { Text("START") }
            OutlinedButton({ vm.send("POST", "/timer", obj("action" to "stop")) }) { Text("STOP") }
        }

        Text("STOPWATCH", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("start", "pause", "reset", "stop").forEach { a ->
                OutlinedButton({ vm.send("POST", "/stopwatch", obj("action" to a)) }) { Text(a.uppercase()) }
            }
        }

        Text("POMODORO", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            NumField("WORK", work) { work = it }
            NumField("BREAK", brk) { brk = it }
            Button({ vm.send("POST", "/pomodoro", obj("work" to (work.toIntOrNull() ?: 25), "break" to (brk.toIntOrNull() ?: 5))) }) { Text("GO") }
            OutlinedButton({ vm.send("POST", "/pomodoro", obj("action" to "stop")) }) { Text("STOP") }
        }

        Text("ALARMS", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(label, { label = it }, label = { Text("LABEL") }, singleLine = true, modifier = Modifier.weight(1f))
            Button({
                val now = Calendar.getInstance()
                TimePickerDialog(ctx, { _, h, m ->
                    vm.send("POST", "/alarms", obj("time" to "%02d:%02d".format(h, m), "label" to label))
                    label = ""
                }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), false).show()
            }) { Text("+ ALARM") }
        }
        s?.optJSONArray("alarms").toList().forEach { a ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${a.optString("time")}  ${a.optString("label")}${if (a.optBoolean("enabled")) "" else "  (done)"}",
                    Modifier.weight(1f))
                TextButton({ vm.send("DELETE", "/alarms/${a.optInt("id")}") }) { Text("DELETE") }
            }
        }
        if (s?.optBoolean("alarm_ringing") == true) Button({ vm.send("POST", "/alarm/dismiss") }) { Text("DISMISS ALARM") }
    }
}

@Composable
fun NumField(label: String, v: String, set: (String) -> Unit) =
    OutlinedTextField(v, { set(it.filter(Char::isDigit).take(3)) }, label = { Text(label) },
        singleLine = true, modifier = Modifier.width(84.dp))

@Composable
fun Setup(vm: BoardVm, dark: Boolean, setDark: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    var addr by remember { mutableStateOf(Board.address(ctx)) }
    val settings = vm.state?.optJSONObject("settings")
    val listenerOn = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
        ?.contains(ctx.packageName) == true
    val batteryFree = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("PHONE", style = MaterialTheme.typography.titleMedium)
        Step("1. Notification access", listenerOn) {
            ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        val phoneOk = ctx.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        Step("2. Phone state (calls)", phoneOk) {
            (ctx as ComponentActivity).requestPermissions(arrayOf(android.Manifest.permission.READ_PHONE_STATE), 1)
        }
        Step("3. Ignore battery optimisation", batteryFree) {
            ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
        }

        Text("BOARD", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(addr, { addr = it }, label = { Text("IP:PORT") }, singleLine = true, modifier = Modifier.weight(1f))
            Button({ Board.setAddress(ctx, addr) }) { Text("SAVE") }
        }

        if (settings != null) {
            Text("SHOW ON MATRIX", style = MaterialTheme.typography.titleMedium)
            val apps = settings.optJSONArray("apps").strings()
            listOf("whatsapp", "instagram", "sms", "gmail").forEach { a ->
                Toggle(a.uppercase(), a in apps) { on ->
                    val next = if (on) apps + a else apps - a
                    vm.send("POST", "/settings", obj("apps" to JSONArray(next)))
                }
            }
            Toggle("NOW PLAYING", settings.optBoolean("media")) { vm.send("POST", "/settings", obj("media" to it)) }
            SettingSlider("MAX MESSAGE LENGTH", settings.optInt("max_len"), 20f..200f, 17) {
                vm.send("POST", "/settings", obj("max_len" to it))
            }
        }

        Text("APP", style = MaterialTheme.typography.titleMedium)
        Toggle("DARK THEME", dark, setDark)
    }
}

@Composable
fun Step(label: String, done: Boolean, open: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text((if (done) "✓ " else "✗ ") + label, Modifier.weight(1f), color = if (done) Color.Unspecified else Red)
        if (!done) OutlinedButton(open) { Text("OPEN") }
    }
}

fun fmt(ms: Long): String = SimpleDateFormat("dd MMM h:mm a", Locale.getDefault()).format(Date(ms))

fun JSONArray?.toList(): List<JSONObject> = if (this == null) emptyList() else List(length()) { getJSONObject(it) }

fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else List(length()) { getString(it) }
