package de.autoknigge.planung

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        setContent { PlanungTheme { Root() } }
        // Startanzeige nur beim frischen Start, nicht nach einer Drehung
        if (savedInstanceState == null) Ads.showStartAd(this)
    }
}

private fun Context.activity(): Activity? {
    var c = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

@Composable
private fun Root() {
    val ctx = LocalContext.current
    val plans = remember { mutableStateListOf<Plan>().apply { addAll(Store.load(ctx).sortedByDescending { it.updated }) } }
    var openId by remember { mutableStateOf<String?>(null) }

    fun persist() = Store.save(ctx, plans.toList())

    Column(Modifier.fillMaxSize().background(Forest).systemBarsPadding()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val open = plans.firstOrNull { it.id == openId }
            if (open == null) {
                ListScreen(
                    plans = plans,
                    onOpen = { openId = it.id },
                    onCreate = { name ->
                        val p = Plan(name = name)
                        plans.add(0, p)
                        persist()
                        openId = p.id
                    },
                    onRename = { p, n ->
                        val i = plans.indexOfFirst { it.id == p.id }
                        if (i >= 0) { plans[i] = p.copy(name = n); persist() }
                    },
                    onCopy = { p ->
                        plans.add(0, p.copy(id = java.util.UUID.randomUUID().toString(), name = p.name + " (Kopie)", updated = System.currentTimeMillis()))
                        persist()
                    },
                    onDelete = { p -> plans.removeAll { it.id == p.id }; persist() }
                )
            } else {
                BackHandler { openId = null }
                EditorScreen(
                    plan = open,
                    onSave = { p ->
                        val i = plans.indexOfFirst { it.id == p.id }
                        if (i >= 0) { plans[i] = p; persist() }
                    },
                    onBack = { openId = null }
                )
            }
        }
        AdBanner()
    }
}

@Composable
private fun AdBanner() {
    val ctx = LocalContext.current
    AndroidView(
        modifier = Modifier.fillMaxWidth(),
        factory = { c ->
            LinearLayout(c).also { box ->
                box.orientation = LinearLayout.VERTICAL
                c.activity()?.let { Ads.attach(it, box) }
            }
        }
    )
}

@Composable
private fun ListScreen(
    plans: List<Plan>,
    onOpen: (Plan) -> Unit,
    onCreate: (String) -> Unit,
    onRename: (Plan, String) -> Unit,
    onCopy: (Plan) -> Unit,
    onDelete: (Plan) -> Unit
) {
    val ctx = LocalContext.current
    var newDlg by remember { mutableStateOf(false) }
    var renameFor by remember { mutableStateOf<Plan?>(null) }
    var deleteFor by remember { mutableStateOf<Plan?>(null) }
    var menu by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Planung", color = Gold, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text("Grundrisse einfach zeichnen", color = Muted, fontSize = 13.sp)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Menü", tint = Ink) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Werbe-Einstellungen") }, onClick = {
                        menu = false
                        ctx.activity()?.let { Ads.showPrivacyOptions(it) }
                    })
                    DropdownMenuItem(text = { Text("Datenschutz") }, onClick = {
                        menu = false
                        try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://autoknigge.de/planung/"))) } catch (e: Exception) {}
                    })
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (plans.isEmpty()) {
                Text(
                    "Noch keine Pläne.\nTippe auf „Neuer Plan“ und füge Räume, Wände, Türen, Fenster und Möbel hinzu. Die Flächen in m² rechnet die App für dich aus.",
                    color = Muted, fontSize = 15.sp,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp)
                )
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(plans, key = { it.id }) { p ->
                        var m by remember { mutableStateOf(false) }
                        Row(
                            Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(14.dp)).clickable { onOpen(p) }.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(p.name, color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                val rooms = p.floors.sumOf { it.rooms.size }
                                Text(
                                    (if (rooms == 1) "1 Raum" else "$rooms Räume") + " · " + (if (p.floors.size == 1) "1 Geschoss" else "${p.floors.size} Geschosse") + " · " + fmtArea(p.roomArea) + " · " +
                                        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(p.updated)),
                                    color = Muted, fontSize = 12.sp
                                )
                            }
                            Box {
                                IconButton(onClick = { m = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Mehr", tint = Muted) }
                                DropdownMenu(expanded = m, onDismissRequest = { m = false }) {
                                    DropdownMenuItem(text = { Text("Umbenennen") }, onClick = { m = false; renameFor = p })
                                    DropdownMenuItem(text = { Text("Kopieren") }, onClick = { m = false; onCopy(p) })
                                    DropdownMenuItem(text = { Text("Löschen") }, onClick = { m = false; deleteFor = p })
                                }
                            }
                        }
                    }
                }
            }
            ExtendedFloatingActionButton(
                onClick = { newDlg = true },
                containerColor = Gold,
                contentColor = androidx.compose.ui.graphics.Color(0xFF1A1400),
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Neuer Plan") },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
            )
        }
        Text(
            "Eine Idee von autoknigge.de",
            color = Muted, fontSize = 12.sp,
            modifier = Modifier.fillMaxWidth().clickable {
                try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://autoknigge.de"))) } catch (e: Exception) {}
            }.padding(vertical = 6.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }

    if (newDlg) {
        var t by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newDlg = false },
            title = { Text("Neuer Plan") },
            text = { OutlinedTextField(value = t, onValueChange = { t = it }, singleLine = true, label = { Text("Name, z. B. Erdgeschoss") }) },
            confirmButton = { TextButton(onClick = { newDlg = false; onCreate(t.trim().ifEmpty { "Neuer Plan" }) }) { Text("Anlegen") } },
            dismissButton = { TextButton(onClick = { newDlg = false }) { Text("Abbrechen") } }
        )
    }
    renameFor?.let { p ->
        var t by remember(p.id) { mutableStateOf(p.name) }
        AlertDialog(
            onDismissRequest = { renameFor = null },
            title = { Text("Plan umbenennen") },
            text = { OutlinedTextField(value = t, onValueChange = { t = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { if (t.isNotBlank()) onRename(p, t.trim()); renameFor = null }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { renameFor = null }) { Text("Abbrechen") } }
        )
    }
    deleteFor?.let { p ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text("Plan löschen?") },
            text = { Text("„" + p.name + "“ wird endgültig gelöscht.") },
            confirmButton = { TextButton(onClick = { onDelete(p); deleteFor = null }) { Text("Löschen") } },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text("Abbrechen") } }
        )
    }
}
