package de.autoknigge.planung

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private fun num(s: String): Double? = s.trim().replace(',', '.').toDoubleOrNull()
private fun fmtNum(v: Double): String = String.format(Locale.GERMANY, "%.2f", v)

@Composable
fun EditorScreen(plan: Plan, onSave: (Plan) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val density = LocalDensity.current.density
    var name by remember { mutableStateOf(plan.name) }
    var els by remember { mutableStateOf(plan.els) }
    var selId by remember { mutableStateOf<String?>(null) }
    var scale by remember { mutableStateOf(40f * density) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var inited by remember { mutableStateOf(false) }
    var snapOn by remember { mutableStateOf(true) }
    var dimsOn by remember { mutableStateOf(true) }
    var menu by remember { mutableStateOf(false) }
    var editDlg by remember { mutableStateOf(false) }
    var furnDlg by remember { mutableStateOf(false) }
    var renameDlg by remember { mutableStateOf(false) }
    val undo = remember { mutableStateListOf<List<El>>() }

    val latest = rememberUpdatedState(Plan(plan.id, name, els, System.currentTimeMillis()))
    LaunchedEffect(els, name) {
        if (els !== plan.els || name != plan.name) {
            delay(500)
            onSave(latest.value)
        }
    }
    DisposableEffect(Unit) {
        onDispose { if (latest.value.els !== plan.els || latest.value.name != plan.name) onSave(latest.value) }
    }

    fun pushUndo() {
        undo.add(els)
        if (undo.size > 60) undo.removeAt(0)
    }

    fun fit() {
        val b = boundsOf(els)
        val vw = viewSize.width.toFloat()
        val vh = viewSize.height.toFloat()
        if (b == null) {
            scale = 40f * density
            origin = Offset(vw / 2, vh / 2)
        } else {
            val bw = (b[2] - b[0]).toFloat() + 2f
            val bh = (b[3] - b[1]).toFloat() + 2f
            scale = min(vw / bw, vh / bh).coerceIn(8f, 400f * density / 3f)
            val cx = ((b[0] + b[2]) / 2).toFloat()
            val cy = ((b[1] + b[3]) / 2).toFloat()
            origin = Offset(vw / 2 - cx * scale, vh / 2 - cy * scale)
        }
    }
    LaunchedEffect(viewSize) {
        if (!inited && viewSize.width > 0) {
            inited = true
            fit()
        }
    }

    fun add(kind: Kind, label: String, w: Double, h: Double) {
        pushUndo()
        val cx = snapTo(((viewSize.width / 2f - origin.x) / scale).toDouble(), 0.05)
        val cy = snapTo(((viewSize.height / 2f - origin.y) / scale).toDouble(), 0.05)
        val colorIdx = els.count { it.kind == Kind.ROOM } % ROOM_COLORS.size
        val e = El(kind = kind, name = label, x = cx, y = cy, w = w, h = h, color = colorIdx)
        els = els + e
        selId = e.id
    }

    fun hitAt(p: Offset): El? {
        val wx = ((p.x - origin.x) / scale).toDouble()
        val wy = ((p.y - origin.y) / scale).toDouble()
        val margin = (8f * density / scale).toDouble()
        return els.reversed().sortedByDescending { rank(it.kind) }.firstOrNull { hits(it, wx, wy, margin) }
    }

    fun handleHit(e: El, p: Offset): Boolean {
        val (vx, vy) = toWorldVec(e, e.w / 2, e.h / 2)
        val sx = origin.x + ((e.x + vx) * scale).toFloat()
        val sy = origin.y + ((e.y + vy) * scale).toFloat()
        val dx = p.x - sx
        val dy = p.y - sy
        val r = 30f * density
        return dx * dx + dy * dy <= r * r
    }

    val sel = els.firstOrNull { it.id == selId }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().background(Panel).padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück", tint = Ink) }
            Column(Modifier.weight(1f).clickable { renameDlg = true }) {
                Text(name, color = Ink, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1)
                Text("Räume gesamt: " + fmtArea(els.filter { it.kind == Kind.ROOM }.sumOf { it.w * it.h }), color = Muted, fontSize = 12.sp)
            }
            TextButton(
                onClick = {
                    if (undo.isNotEmpty()) {
                        els = undo.removeAt(undo.lastIndex)
                        selId = null
                    }
                },
                enabled = undo.isNotEmpty()
            ) { Text("Rückgängig", color = if (undo.isNotEmpty()) Gold else Muted, fontSize = 13.sp) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Menü", tint = Ink) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Einrasten (Raster, Kanten): " + if (snapOn) "an" else "aus") }, onClick = { snapOn = !snapOn; menu = false })
                    DropdownMenuItem(text = { Text("Maße bei Räumen: " + if (dimsOn) "an" else "aus") }, onClick = { dimsOn = !dimsOn; menu = false })
                    DropdownMenuItem(text = { Text("Ganzen Plan anzeigen") }, onClick = { fit(); menu = false })
                    DropdownMenuItem(text = { Text("Plan umbenennen") }, onClick = { renameDlg = true; menu = false })
                    DropdownMenuItem(text = { Text("Als Bild teilen (PNG)") }, onClick = {
                        menu = false
                        if (!Export.sharePng(ctx, latest.value)) Toast.makeText(ctx, "Der Plan ist noch leer.", Toast.LENGTH_SHORT).show()
                    })
                    DropdownMenuItem(text = { Text("Als PDF teilen") }, onClick = {
                        menu = false
                        if (!Export.sharePdf(ctx, latest.value)) Toast.makeText(ctx, "Der Plan ist noch leer.", Toast.LENGTH_SHORT).show()
                    })
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .background(PaperColor)
                    .onSizeChanged { viewSize = it }
                    .pointerInput(Unit) {
                        val slop = viewConfiguration.touchSlop
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val start = down.position
                            var mode = 0 // 0 Ansicht verschieben, 1 Element bewegen, 2 Größe ändern
                            var targetId: String? = null
                            var rawX = 0.0
                            var rawY = 0.0
                            var rawW = 0.0
                            var rawH = 0.0
                            val cur = els.firstOrNull { it.id == selId }
                            if (cur != null && handleHit(cur, start)) {
                                mode = 2; targetId = cur.id; rawW = cur.w; rawH = cur.h
                            } else {
                                val hit = hitAt(start)
                                if (hit != null) {
                                    mode = 1; targetId = hit.id; selId = hit.id; rawX = hit.x; rawY = hit.y
                                }
                            }
                            var total = Offset.Zero
                            var moved = false
                            var multi = false
                            var pushed = false
                            do {
                                val ev = awaitPointerEvent()
                                val active = ev.changes.count { it.pressed }
                                if (ev.changes.size >= 2 && active >= 2) {
                                    multi = true
                                    moved = true
                                    val z = ev.calculateZoom()
                                    val pan = ev.calculatePan()
                                    val cen = ev.calculateCentroid()
                                    val ns = (scale * z).coerceIn(8f, 600f)
                                    val f = ns / scale
                                    origin = Offset(cen.x - (cen.x - origin.x) * f + pan.x, cen.y - (cen.y - origin.y) * f + pan.y)
                                    scale = ns
                                } else if (!multi && active >= 1) {
                                    val d = ev.changes[0].positionChange()
                                    val was = moved
                                    total += d
                                    if (!moved && total.getDistance() > slop) moved = true
                                    if (moved) {
                                        val step = if (was) d else total
                                        if (step != Offset.Zero) {
                                            when (mode) {
                                                0 -> origin = Offset(origin.x + step.x, origin.y + step.y)
                                                1 -> {
                                                    if (!pushed) { pushUndo(); pushed = true }
                                                    rawX += (step.x / scale).toDouble()
                                                    rawY += (step.y / scale).toDouble()
                                                    val t = els.firstOrNull { it.id == targetId }
                                                    if (t != null) {
                                                        val st = if (snapOn) 0.05 else 0.01
                                                        var nx = snapTo(rawX, st)
                                                        var ny = snapTo(rawY, st)
                                                        if (snapOn) {
                                                            val m = magnet(t, nx, ny, els, (10f * density / scale).toDouble())
                                                            nx = m.first
                                                            ny = m.second
                                                        }
                                                        els = els.map { if (it.id == t.id) it.copy(x = nx, y = ny) else it }
                                                    }
                                                }
                                                2 -> {
                                                    if (!pushed) { pushUndo(); pushed = true }
                                                    val t = els.firstOrNull { it.id == targetId }
                                                    if (t != null) {
                                                        val r = Math.toRadians(t.rot)
                                                        val dx = (step.x / scale).toDouble()
                                                        val dy = (step.y / scale).toDouble()
                                                        rawW += cos(r) * dx + sin(r) * dy
                                                        rawH += -sin(r) * dx + cos(r) * dy
                                                        val st = if (snapOn) 0.05 else 0.01
                                                        val nw = max(0.1, snapTo(rawW, st))
                                                        val both = t.kind == Kind.ROOM || t.kind == Kind.FURN
                                                        val nh = if (both) max(0.1, snapTo(rawH, st)) else t.h
                                                        val (vx, vy) = toWorldVec(t, (nw - t.w) / 2, (nh - t.h) / 2)
                                                        els = els.map { if (it.id == t.id) it.copy(w = nw, h = nh, x = it.x + vx, y = it.y + vy) else it }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                ev.changes.forEach { if (it.positionChange() != Offset.Zero) it.consume() }
                            } while (ev.changes.any { it.pressed })
                            if (!moved) {
                                selId = hitAt(start)?.id
                            } else if (mode == 1 && pushed) {
                                val t = els.firstOrNull { it.id == targetId }
                                if (t != null && (t.kind == Kind.DOOR || t.kind == Kind.WINDOW)) {
                                    els = els.map { if (it.id == t.id) attachToWall(it, els) else it }
                                }
                            }
                        }
                    }
            ) {
                drawPlan(els, scale, origin, 12f * density, selId, true, dimsOn)
            }
            if (els.isEmpty()) {
                Text(
                    "Leerer Plan\nUnten einen Raum, eine Wand oder ein Möbelstück hinzufügen.\nMit einem Finger verschieben, mit zwei Fingern zoomen.",
                    color = Color0x55,
                    fontSize = 14.sp,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp)
                )
            }
        }

        if (sel != null) {
            Row(
                Modifier.fillMaxWidth().background(Panel2).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    (if (sel.name.isBlank()) sel.kind.label else sel.name) + " · " +
                        if (sel.kind == Kind.ROOM) fmtArea(sel.w * sel.h) else fmtM(sel.w) + " × " + fmtM(sel.h) + " m",
                    color = Ink, fontSize = 13.sp
                )
                BarButton("Bearbeiten") { editDlg = true }
                BarButton("Drehen 90°") {
                    pushUndo()
                    els = els.map { if (it.id == sel.id) it.copy(rot = (it.rot + 90.0) % 360.0) else it }
                }
                BarButton("Kopieren") {
                    pushUndo()
                    val c = sel.copy(id = java.util.UUID.randomUUID().toString(), x = sel.x + 0.5, y = sel.y + 0.5)
                    els = els + c
                    selId = c.id
                }
                BarButton("Löschen") {
                    pushUndo()
                    els = els.filter { it.id != sel.id }
                    selId = null
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().background(Panel).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)
        ) {
            BarButton("+ Raum") { add(Kind.ROOM, "Raum", 4.0, 3.5) }
            BarButton("+ Wand") { add(Kind.WALL, "", 3.0, 0.2) }
            BarButton("+ Tür") { add(Kind.DOOR, "", 0.9, 0.22) }
            BarButton("+ Fenster") { add(Kind.WINDOW, "", 1.2, 0.22) }
            BarButton("+ Möbel") { furnDlg = true }
        }
    }

    if (furnDlg) {
        AlertDialog(
            onDismissRequest = { furnDlg = false },
            confirmButton = { TextButton(onClick = { furnDlg = false }) { Text("Schließen") } },
            title = { Text("Möbel und Symbole") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    for (f in FURNITURE) {
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                furnDlg = false
                                add(Kind.FURN, if (f.name.startsWith("Frei")) "" else f.name, f.w, f.h)
                            }.padding(vertical = 10.dp),
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween
                        ) {
                            Text(f.name)
                            Text(fmtM(f.w) + " × " + fmtM(f.h) + " m", color = Muted)
                        }
                    }
                }
            }
        )
    }

    if (renameDlg) {
        var tmp by remember { mutableStateOf(name) }
        AlertDialog(
            onDismissRequest = { renameDlg = false },
            title = { Text("Name des Plans") },
            text = { OutlinedTextField(value = tmp, onValueChange = { tmp = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { if (tmp.isNotBlank()) name = tmp.trim(); renameDlg = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { renameDlg = false }) { Text("Abbrechen") } }
        )
    }

    if (editDlg && sel != null) {
        EditDialog(
            e = sel,
            onDismiss = { editDlg = false },
            onOk = { n ->
                pushUndo()
                els = els.map { if (it.id == n.id) n else it }
                editDlg = false
            }
        )
    }
}

private val Color0x55 = androidx.compose.ui.graphics.Color(0x66000000)

@Composable
private fun BarButton(text: String, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
        Text(text, fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
private fun EditDialog(e: El, onDismiss: () -> Unit, onOk: (El) -> Unit) {
    var nm by remember { mutableStateOf(e.name) }
    var w by remember { mutableStateOf(fmtNum(e.w)) }
    var h by remember { mutableStateOf(fmtNum(e.h)) }
    var rot by remember { mutableStateOf(String.format(Locale.GERMANY, "%.0f", e.rot)) }
    var color by remember { mutableStateOf(e.color) }
    val named = e.kind == Kind.ROOM || e.kind == Kind.FURN
    val wLabel = if (e.kind == Kind.WALL) "Länge (m)" else "Breite (m)"
    val hLabel = if (e.kind == Kind.WALL) "Dicke (m)" else "Tiefe (m)"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(e.kind.label + " bearbeiten") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (named) {
                    OutlinedTextField(value = nm, onValueChange = { nm = it }, label = { Text("Name") }, singleLine = true)
                    Spacer(Modifier.size(8.dp))
                }
                val kb = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                OutlinedTextField(value = w, onValueChange = { w = it }, label = { Text(wLabel) }, singleLine = true, keyboardOptions = kb)
                if (e.kind != Kind.DOOR && e.kind != Kind.WINDOW) {
                    Spacer(Modifier.size(8.dp))
                    OutlinedTextField(value = h, onValueChange = { h = it }, label = { Text(hLabel) }, singleLine = true, keyboardOptions = kb)
                }
                if (e.kind != Kind.ROOM) {
                    Spacer(Modifier.size(8.dp))
                    OutlinedTextField(value = rot, onValueChange = { rot = it }, label = { Text("Drehung (Grad)") }, singleLine = true, keyboardOptions = kb)
                }
                if (e.kind == Kind.ROOM) {
                    Spacer(Modifier.size(12.dp))
                    Text("Farbe", color = Muted, fontSize = 12.sp)
                    Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                        ROOM_COLORS.forEachIndexed { i, c ->
                            Box(
                                Modifier.size(34.dp).background(c)
                                    .border(if (i == color) 3.dp else 1.dp, if (i == color) Gold else Muted)
                                    .clickable { color = i }
                            )
                        }
                    }
                    Spacer(Modifier.width(1.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val nw = (num(w) ?: e.w).coerceIn(0.05, 200.0)
                val nh = (num(h) ?: e.h).coerceIn(0.05, 200.0)
                val nr = ((num(rot) ?: e.rot) % 360.0 + 360.0) % 360.0
                onOk(
                    e.copy(
                        name = if (named) nm.trim() else e.name,
                        w = nw,
                        h = if (e.kind == Kind.DOOR || e.kind == Kind.WINDOW) e.h else nh,
                        rot = if (e.kind == Kind.ROOM) e.rot else nr,
                        color = color
                    )
                )
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } }
    )
}
