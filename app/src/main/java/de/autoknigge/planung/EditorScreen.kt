package de.autoknigge.planung

import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
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
import java.util.UUID
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private fun num(s: String): Double? = s.trim().replace(',', '.').toDoubleOrNull()
private fun fmtNum(v: Double): String = String.format(Locale.GERMANY, "%.2f", v)
private val DimHint = Color(0x66000000)

@Composable
fun EditorScreen(plan: Plan, onSave: (Plan) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val density = LocalDensity.current.density
    var p by remember { mutableStateOf(plan) }
    var fi by remember { mutableStateOf(0) }
    var selId by remember { mutableStateOf<String?>(null) }
    var selVert by remember { mutableStateOf<Pt?>(null) }
    var linked by remember { mutableStateOf(true) }
    var scale by remember { mutableStateOf(40f * density) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var inited by remember { mutableStateOf(false) }
    var snapOn by remember { mutableStateOf(true) }
    var dimsOn by remember { mutableStateOf(true) }
    var outerOn by remember { mutableStateOf(true) }
    var menu by remember { mutableStateOf(false) }
    var editDlg by remember { mutableStateOf(false) }
    var furnDlg by remember { mutableStateOf(false) }
    var stairDlg by remember { mutableStateOf(false) }
    var renameDlg by remember { mutableStateOf(false) }
    var houseDlg by remember { mutableStateOf(!plan.setup) }
    var roofDlg by remember { mutableStateOf(false) }
    var floorDlg by remember { mutableStateOf(false) }
    var addFloorDlg by remember { mutableStateOf(false) }
    var cornerDlg by remember { mutableStateOf(false) }
    var showView by remember { mutableStateOf(false) }
    var placeKind by remember { mutableStateOf<Kind?>(null) }
    // 0 keins, 1 Punkt einfügen, 2 Anbau, 3 Raum zeichnen, 4 Zusammenlegen
    var tool by remember { mutableStateOf(0) }
    var pending by remember { mutableStateOf<List<Pt>>(emptyList()) }
    val undo = remember { mutableStateListOf<Plan>() }

    val latest = rememberUpdatedState(p)
    LaunchedEffect(p) {
        if (p !== plan) {
            delay(500)
            onSave(latest.value.copy(updated = System.currentTimeMillis()))
        }
    }
    DisposableEffect(Unit) {
        onDispose { if (latest.value !== plan) onSave(latest.value.copy(updated = System.currentTimeMillis())) }
    }

    fun fiIdx(): Int = fi.coerceIn(0, p.floors.lastIndex)
    fun fl(): Floor = p.floors[fiIdx()]
    fun putFloor(gi: Int, f: Floor) {
        p = p.copy(floors = p.floors.mapIndexed { i, x -> if (i == gi) f else x })
    }
    fun pushUndo() {
        undo.add(p)
        if (undo.size > 60) undo.removeAt(0)
    }
    fun resetTools() {
        tool = 0
        placeKind = null
        pending = emptyList()
    }

    fun fit() {
        val x0 = p.bx - 2.2
        val y0 = p.by - 2.2
        val x1 = p.bx + p.w + 0.6
        val y1 = p.by + p.d + 0.6
        val vw = viewSize.width.toFloat()
        val vh = viewSize.height.toFloat()
        scale = min(vw / (x1 - x0).toFloat(), vh / (y1 - y0).toFloat()).coerceIn(8f, 400f * density / 3f)
        val cx = ((x0 + x1) / 2).toFloat()
        val cy = ((y0 + y1) / 2).toFloat()
        origin = Offset(vw / 2 - cx * scale, vh / 2 - cy * scale)
    }
    LaunchedEffect(viewSize) {
        if (!inited && viewSize.width > 0) {
            inited = true
            fit()
        }
    }

    fun centerX(): Double = clampD(snapTo(((viewSize.width / 2f - origin.x) / scale).toDouble(), 0.05), p.bx + 0.3, p.bx + p.w - 0.3)
    fun centerY(): Double = clampD(snapTo(((viewSize.height / 2f - origin.y) / scale).toDouble(), 0.05), p.by + 0.3, p.by + p.d - 0.3)

    fun addEl(kind: Kind, label: String, w: Double, h: Double, vh: Double = 0.0, sill: Double = 0.0, sub: Int = 0) {
        pushUndo()
        val e = El(kind = kind, name = label, x = centerX(), y = centerY(), w = w, h = h, vh = vh, sill = sill, sub = sub)
        putFloor(fiIdx(), fl().copy(els = fl().els + e))
        selId = e.id
    }

    fun addRoom() {
        val f = fl()
        val target = f.rooms.firstOrNull { it.id == selId } ?: f.rooms.maxByOrNull { polyArea(it.pts) }
        if (target == null) return
        val nf = splitRoom(f, target.id, null)
        if (nf == null) {
            Toast.makeText(ctx, "Der Raum ist zu klein zum Teilen.", Toast.LENGTH_SHORT).show()
            return
        }
        pushUndo()
        val oldIds = f.rooms.map { it.id }.toSet()
        putFloor(fiIdx(), nf)
        selId = nf.rooms.firstOrNull { it.id !in oldIds }?.id
        selVert = null
    }

    fun hitEl(pt: Offset): El? {
        val wx = ((pt.x - origin.x) / scale).toDouble()
        val wy = ((pt.y - origin.y) / scale).toDouble()
        val margin = (16f * density / scale).toDouble()
        return fl().els.sortedByDescending { rank(it.kind) }.firstOrNull { hits(it, wx, wy, margin) }
    }

    fun handleHit(e: El, pt: Offset): Boolean {
        val (vx, vy) = toWorldVec(e, e.w / 2, e.h / 2)
        val sx = origin.x + ((e.x + vx) * scale).toFloat()
        val sy = origin.y + ((e.y + vy) * scale).toFloat()
        val dx = pt.x - sx
        val dy = pt.y - sy
        val r = 30f * density
        return dx * dx + dy * dy <= r * r
    }

    /** Punkt auf Raster oder auf eine vorhandene Ecke in der Nähe setzen. */
    fun snapPoint(wx: Double, wy: Double, exclude: Pt?): Pt {
        val thr = (16f * density / scale).toDouble()
        var best: Pt? = null
        var bd = thr
        for (r in fl().rooms) for (q in r.pts) {
            if (exclude != null && samePt(q, exclude)) continue
            val d = hypot(q.x - wx, q.y - wy)
            if (d < bd) { bd = d; best = q }
        }
        val b = best
        if (b != null) return b
        val st = if (snapOn) 0.05 else 0.01
        var x = snapTo(wx, st)
        var y = snapTo(wy, st)
        if (snapOn) {
            val t2 = (10f * density / scale).toDouble()
            var bx = t2
            var by = t2
            for (r in fl().rooms) for (q in r.pts) {
                if (exclude != null && samePt(q, exclude)) continue
                if (abs(q.x - wx) < bx) { bx = abs(q.x - wx); x = q.x }
                if (abs(q.y - wy) < by) { by = abs(q.y - wy); y = q.y }
            }
        }
        return Pt(x, y)
    }

    fun tapSelect(pt: Offset) {
        val wx = ((pt.x - origin.x) / scale).toDouble()
        val wy = ((pt.y - origin.y) / scale).toDouble()
        val f = fl()
        val pk = placeKind
        if (pk != null) {
            val base = if (pk == Kind.DOOR) El(kind = pk, x = wx, y = wy, w = 0.9, h = 0.17, vh = 2.01)
            else El(kind = pk, x = wx, y = wy, w = 1.2, h = 0.17, vh = 1.2, sill = 0.9)
            val e = attachToWall(base, p, f, 1.2)
            if (e.x == wx && e.y == wy) {
                Toast.makeText(ctx, "Bitte direkt auf eine Wand tippen.", Toast.LENGTH_SHORT).show()
            } else {
                pushUndo()
                putFloor(fiIdx(), f.copy(els = f.els + e))
                selId = e.id
                placeKind = null
            }
            return
        }
        when (tool) {
            3 -> {
                val q = snapPoint(wx, wy, null)
                if (pending.size >= 3 && samePt(q, pending[0])) {
                    val nf = addDrawn(f, pending)
                    if (nf == null) Toast.makeText(ctx, "Der Raum ist zu klein oder die Punkte ergeben keine Fläche.", Toast.LENGTH_LONG).show()
                    else { pushUndo(); putFloor(fiIdx(), nf); selId = nf.rooms.last().id }
                    resetTools()
                } else pending = pending + q
                return
            }
            1, 2 -> {
                val r = f.rooms.firstOrNull { it.id == selId }
                val idx = if (r != null) nearestEdgeIndex(r, wx, wy, (24f * density / scale).toDouble()) else null
                if (r == null || idx == null) {
                    Toast.makeText(ctx, "Tippe auf eine Wand des gewählten Raums.", Toast.LENGTH_SHORT).show()
                    return
                }
                if (tool == 1) {
                    val a = r.pts[idx]
                    val b = r.pts[(idx + 1) % r.pts.size]
                    val (_, tt) = segProj(wx, wy, a.x, a.y, b.x, b.y)
                    val q = Pt(snapTo(a.x + (b.x - a.x) * tt, 0.05).let { if (abs(b.x - a.x) < EPS) a.x else it }, snapTo(a.y + (b.y - a.y) * tt, 0.05).let { if (abs(b.y - a.y) < EPS) a.y else it })
                    pushUndo()
                    putFloor(fiIdx(), insertVertex(f, r.id, idx, q))
                    selVert = q
                } else {
                    val nf = addAnnex(f, r.id, idx, 3.0)
                    if (nf == null) Toast.makeText(ctx, "Hier ist kein Platz für einen Anbau.", Toast.LENGTH_SHORT).show()
                    else { pushUndo(); putFloor(fiIdx(), nf); selId = nf.rooms.last().id; selVert = null }
                }
                tool = 0
                return
            }
            4 -> {
                val src = selId
                val other = f.rooms.firstOrNull { pointIn(it.pts, wx, wy) }
                tool = 0
                if (src != null && other != null && other.id != src) {
                    val nf = mergeRooms(f, src, other.id)
                    if (nf == null) Toast.makeText(ctx, "Die Räume haben keine gemeinsame Wand.", Toast.LENGTH_LONG).show()
                    else { pushUndo(); putFloor(fiIdx(), nf) }
                }
                return
            }
        }
        val hit = hitEl(pt)
        if (hit != null) {
            selId = hit.id
            selVert = null
            return
        }
        val sr = f.rooms.firstOrNull { it.id == selId }
        if (sr != null) {
            val v = findVertex(sr, wx, wy, (22f * density / scale).toDouble())
            if (v != null) {
                selVert = v
                return
            }
        }
        selVert = null
        selId = f.rooms.firstOrNull { pointIn(it.pts, wx, wy) }?.id
    }

    val flv = fl()
    val selEl = flv.els.firstOrNull { it.id == selId }
    val selRoom = flv.rooms.firstOrNull { it.id == selId }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().background(Panel).padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück", tint = Ink) }
            Column(Modifier.weight(1f).clickable { renameDlg = true }) {
                Text(p.name, color = Ink, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1)
                Text(
                    flv.name + " · Raumhöhe " + fmtM(flv.height) + " m · " + fmtArea(p.areaOf(flv)),
                    color = Muted, fontSize = 12.sp, maxLines = 1
                )
            }
            TextButton(
                onClick = {
                    if (undo.isNotEmpty()) {
                        p = undo.removeAt(undo.lastIndex)
                        selId = null
                        selVert = null
                        resetTools()
                    }
                },
                enabled = undo.isNotEmpty()
            ) { Text("Rückgängig", color = if (undo.isNotEmpty()) Gold else Muted, fontSize = 13.sp) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Menü", tint = Ink) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Einrasten (Raster, Ecken): " + if (snapOn) "an" else "aus") }, onClick = { snapOn = !snapOn; menu = false })
                    DropdownMenuItem(text = { Text("Maße in den Räumen: " + if (dimsOn) "an" else "aus") }, onClick = { dimsOn = !dimsOn; menu = false })
                    DropdownMenuItem(text = { Text("Außenmaße: " + if (outerOn) "an" else "aus") }, onClick = { outerOn = !outerOn; menu = false })
                    DropdownMenuItem(text = { Text("Ganzen Plan anzeigen") }, onClick = { fit(); menu = false })
                    DropdownMenuItem(text = { Text("Plan umbenennen") }, onClick = { renameDlg = true; menu = false })
                    DropdownMenuItem(text = { Text("Jetzt speichern") }, onClick = {
                        menu = false
                        onSave(latest.value.copy(updated = System.currentTimeMillis()))
                        Toast.makeText(ctx, "Gespeichert", Toast.LENGTH_SHORT).show()
                    })
                    DropdownMenuItem(text = { Text("Sicherungsdatei teilen (zum Wiederöffnen)") }, onClick = {
                        menu = false
                        Export.shareBackup(ctx, latest.value)
                    })
                    DropdownMenuItem(text = { Text("Dieses Geschoss als Bild teilen (PNG)") }, onClick = {
                        menu = false
                        Export.sharePng(ctx, latest.value, fiIdx())
                    })
                    DropdownMenuItem(text = { Text("Alles als PDF teilen") }, onClick = {
                        menu = false
                        Export.sharePdf(ctx, latest.value)
                    })
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().background(Panel2).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            p.floors.forEachIndexed { i, f ->
                val on = i == fiIdx()
                Box(
                    Modifier.background(if (on) Gold else Panel, RoundedCornerShape(8.dp))
                        .clickable {
                            if (on) floorDlg = true else { fi = i; selId = null; selVert = null; resetTools() }
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(f.name + if (on) " ✎" else "", color = if (on) Color(0xFF1A1400) else Ink, fontSize = 13.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
                }
            }
            BarButton("+ Geschoss") { addFloorDlg = true }
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
                            val wx0 = ((start.x - origin.x) / scale).toDouble()
                            val wy0 = ((start.y - origin.y) / scale).toDouble()
                            val gi = fiIdx()
                            val snapPlan = p
                            val snapFloor = snapPlan.floors[gi]
                            // 0 Ansicht verschieben, 1 Element bewegen, 2 Elementgröße, 3 Ecke ziehen, 5 Wand ziehen
                            var mode = 0
                            var targetId: String? = null
                            var rawX = 0.0
                            var rawY = 0.0
                            var rawW = 0.0
                            var rawH = 0.0
                            var raw = 0.0
                            var v0: Pt? = null
                            var edgeA: Pt? = null
                            var edgeB: Pt? = null
                            var chain: List<Pair<Pt, Pt>> = emptyList()
                            val cur = snapFloor.els.firstOrNull { it.id == selId }
                            val busy = tool != 0 || placeKind != null
                            if (!busy) {
                                if (cur != null && (cur.kind == Kind.FURN || cur.kind == Kind.STAIR) && handleHit(cur, start)) {
                                    mode = 2; targetId = cur.id; rawW = cur.w; rawH = cur.h
                                } else {
                                    val hit = hitEl(start)
                                    if (hit != null) {
                                        mode = 1; targetId = hit.id; selId = hit.id; selVert = null; rawX = hit.x; rawY = hit.y
                                    } else {
                                        val sr = snapFloor.rooms.firstOrNull { it.id == selId }
                                        val vh = if (sr != null) findVertex(sr, wx0, wy0, (22f * density / scale).toDouble()) else null
                                        if (vh != null) {
                                            mode = 3; v0 = vh; selVert = vh
                                        } else {
                                            val eh = findEdge(snapFloor.rooms, wx0, wy0, (14f * density / scale).toDouble())
                                            if (eh != null) {
                                                mode = 5; edgeA = eh.first; edgeB = eh.second
                                                chain = chainEdges(snapFloor.rooms, eh.first, eh.second)
                                            }
                                        }
                                    }
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
                                            val st = if (snapOn) 0.05 else 0.01
                                            when (mode) {
                                                0 -> origin = Offset(origin.x + step.x, origin.y + step.y)
                                                1 -> {
                                                    if (!pushed) { pushUndo(); pushed = true }
                                                    rawX += (step.x / scale).toDouble()
                                                    rawY += (step.y / scale).toDouble()
                                                    val f0 = p.floors[gi]
                                                    val t = f0.els.firstOrNull { it.id == targetId }
                                                    if (t != null) {
                                                        var nx = snapTo(rawX, st)
                                                        var ny = snapTo(rawY, st)
                                                        if (t.kind == Kind.DOOR || t.kind == Kind.WINDOW) {
                                                            val at = attachToWall(t.copy(x = nx, y = ny), p, f0, 1.5)
                                                            putFloor(gi, f0.copy(els = f0.els.map { if (it.id == t.id) at else it }))
                                                        } else {
                                                            if (snapOn) {
                                                                val faces = wallFaces(p, f0)
                                                                val m = magnet(t, nx, ny, f0.els, faces.first, faces.second, (10f * density / scale).toDouble())
                                                                nx = m.first
                                                                ny = m.second
                                                            }
                                                            putFloor(gi, f0.copy(els = f0.els.map { if (it.id == t.id) it.copy(x = nx, y = ny) else it }))
                                                        }
                                                    }
                                                }
                                                2 -> {
                                                    if (!pushed) { pushUndo(); pushed = true }
                                                    val f0 = p.floors[gi]
                                                    val t = f0.els.firstOrNull { it.id == targetId }
                                                    if (t != null) {
                                                        val r = Math.toRadians(t.rot)
                                                        val dx = (step.x / scale).toDouble()
                                                        val dy = (step.y / scale).toDouble()
                                                        rawW += cos(r) * dx + sin(r) * dy
                                                        rawH += -sin(r) * dx + cos(r) * dy
                                                        val nw = max(0.1, snapTo(rawW, st))
                                                        val both = t.kind == Kind.FURN || t.kind == Kind.STAIR
                                                        val nh = if (both) max(0.1, snapTo(rawH, st)) else t.h
                                                        val (vx, vy) = toWorldVec(t, (nw - t.w) / 2, (nh - t.h) / 2)
                                                        putFloor(gi, f0.copy(els = f0.els.map { if (it.id == t.id) it.copy(w = nw, h = nh, x = it.x + vx, y = it.y + vy) else it }))
                                                    }
                                                }
                                                3 -> {
                                                    val v = v0
                                                    if (v != null) {
                                                        if (!pushed) { pushUndo(); pushed = true }
                                                        rawX += (step.x / scale).toDouble()
                                                        rawY += (step.y / scale).toDouble()
                                                        val tgt = snapPoint(v.x + rawX, v.y + rawY, v)
                                                        val only = if (linked) null else selId
                                                        putFloor(gi, snapFloor.copy(rooms = movePoints(snapFloor.rooms, listOf(v), tgt.x - v.x, tgt.y - v.y, only)))
                                                        selVert = tgt
                                                    }
                                                }
                                                5 -> {
                                                    val a = edgeA
                                                    val b = edgeB
                                                    if (a != null && b != null) {
                                                        if (!pushed) { pushUndo(); pushed = true }
                                                        val len = hypot(b.x - a.x, b.y - a.y)
                                                        val nx = -(b.y - a.y) / len
                                                        val ny = (b.x - a.x) / len
                                                        raw += ((step.x * nx + step.y * ny) / scale)
                                                        val base = a.x * nx + a.y * ny
                                                        val ns = snapTo(base + raw, st) - base
                                                        val only = if (linked) null else selId
                                                        putFloor(
                                                            gi,
                                                            snapFloor.copy(
                                                                rooms = movePoints(snapFloor.rooms, chainPoints(chain), nx * ns, ny * ns, only),
                                                                els = shiftOpenings(snapFloor.els, chain, nx * ns, ny * ns)
                                                            )
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                ev.changes.forEach { if (it.positionChange() != Offset.Zero) it.consume() }
                            } while (ev.changes.any { it.pressed })
                            if (!moved) {
                                tapSelect(start)
                            } else if (pushed) {
                                if (mode == 1) {
                                    val f0 = p.floors[gi]
                                    val t = f0.els.firstOrNull { it.id == targetId }
                                    if (t != null && (t.kind == Kind.DOOR || t.kind == Kind.WINDOW)) {
                                        putFloor(gi, f0.copy(els = f0.els.map { if (it.id == t.id) attachToWall(it, p, f0, 0.8) else it }))
                                    }
                                } else if (mode == 3 || mode == 5) {
                                    p = reattachAll(p, 0.8)
                                }
                            }
                        }
                    }
            ) {
                drawFloor(p, fiIdx(), scale, origin, 12f * density, selId, true, dimsOn, outerOn, selVert, pending)
            }
            if (flv.els.isEmpty() && p.floors.size == 1 && flv.rooms.size == 1) {
                Text(
                    "Raum antippen, dann Ecken und Wände ziehen. „+ Raum“ teilt, „Raum zeichnen“ legt freie Formen an, „Haus“ stellt Maße und Wandstärken ein. Mit zwei Fingern zoomen.",
                    color = DimHint,
                    fontSize = 13.sp,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp)
                )
            }
        }

        if (tool == 3) {
            Row(
                Modifier.fillMaxWidth().background(Panel2).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Ecken antippen (rastet an vorhandenen Ecken ein) · " + pending.size + " Punkte", color = Ink, fontSize = 13.sp)
                BarButton("Fertig") {
                    val nf = addDrawn(flv, pending)
                    if (nf == null) Toast.makeText(ctx, "Mindestens 3 Punkte mit etwas Fläche nötig.", Toast.LENGTH_SHORT).show()
                    else { pushUndo(); putFloor(fiIdx(), nf); selId = nf.rooms.last().id; resetTools() }
                }
                BarButton("Letzten Punkt weg") { if (pending.isNotEmpty()) pending = pending.dropLast(1) }
                BarButton("Abbrechen") { resetTools() }
            }
        } else if (selRoom != null || selEl != null) {
            Row(
                Modifier.fillMaxWidth().background(Panel2).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (selRoom != null) {
                    val n = roomNet(p, flv, selRoom)
                    Text(
                        selRoom.name.ifBlank { "Raum" } + " · " + fmtArea(n.area) + (if (n.w != null && n.d != null) " · " + fmtM(n.w) + " × " + fmtM(n.d) + " m" else ""),
                        color = Ink, fontSize = 13.sp
                    )
                    if (tool != 0) Text(
                        when (tool) { 1 -> "Wand antippen, wo die Ecke hin soll …"; 2 -> "Wand antippen, an die der Anbau kommt …"; else -> "Raum antippen, der dazukommt …" },
                        color = Gold, fontSize = 13.sp
                    )
                    BarButton("Bearbeiten") { editDlg = true }
                    BarButton("Teilen senkrecht") {
                        val nf = splitRoom(flv, selRoom.id, true)
                        if (nf == null) Toast.makeText(ctx, "Der Raum ist zu klein zum Teilen.", Toast.LENGTH_SHORT).show()
                        else { pushUndo(); putFloor(fiIdx(), nf) }
                    }
                    BarButton("Teilen waagerecht") {
                        val nf = splitRoom(flv, selRoom.id, false)
                        if (nf == null) Toast.makeText(ctx, "Der Raum ist zu klein zum Teilen.", Toast.LENGTH_SHORT).show()
                        else { pushUndo(); putFloor(fiIdx(), nf) }
                    }
                    BarButton(if (tool == 1) "Abbrechen" else "Ecke einfügen") { tool = if (tool == 1) 0 else 1; placeKind = null }
                    BarButton(if (tool == 4) "Abbrechen" else "Zusammenlegen") { tool = if (tool == 4) 0 else 4; placeKind = null }
                    BarButton(if (tool == 2) "Abbrechen" else "Anbau") { tool = if (tool == 2) 0 else 2; placeKind = null }
                    BarButton("Nachbarn mitziehen: " + if (linked) "ja" else "nein") { linked = !linked }
                    if (selVert != null) {
                        BarButton("Ecke abtrennen") { cornerDlg = true }
                        BarButton("Ecke löschen") {
                            val nf = deleteVertex(flv, selVert!!)
                            if (nf == null) Toast.makeText(ctx, "Ein Raum braucht mindestens 3 Ecken.", Toast.LENGTH_SHORT).show()
                            else { pushUndo(); putFloor(fiIdx(), nf); selVert = null }
                        }
                    }
                    BarButton("Raum entfernen") {
                        val nf = deleteRoom(flv, selRoom.id)
                        if (nf == null) Toast.makeText(ctx, "Der letzte Raum eines Geschosses bleibt.", Toast.LENGTH_SHORT).show()
                        else { pushUndo(); putFloor(fiIdx(), nf); selId = null; selVert = null }
                    }
                } else if (selEl != null) {
                    val hasH = selEl.kind == Kind.FURN || selEl.kind == Kind.STAIR
                    Text(
                        (if (selEl.name.isBlank()) selEl.kind.label else selEl.name) + " · " + fmtM(selEl.w) +
                            (if (hasH) " × " + fmtM(selEl.h) else if (selEl.vh > 0) " × " + fmtM(selEl.vh) + " hoch" else "") + " m",
                        color = Ink, fontSize = 13.sp
                    )
                    BarButton("Bearbeiten") { editDlg = true }
                    if (hasH) BarButton("Drehen 90°") {
                        pushUndo()
                        putFloor(fiIdx(), flv.copy(els = flv.els.map { if (it.id == selEl.id) it.copy(rot = (it.rot + 90.0) % 360.0) else it }))
                    }
                    if (selEl.kind == Kind.DOOR) {
                        BarButton("Anschlag spiegeln") {
                            pushUndo()
                            putFloor(fiIdx(), flv.copy(els = flv.els.map { if (it.id == selEl.id) it.copy(sub = it.sub xor 1) else it }))
                        }
                        BarButton("Aufschlag drehen") {
                            pushUndo()
                            putFloor(fiIdx(), flv.copy(els = flv.els.map { if (it.id == selEl.id) it.copy(sub = it.sub xor 2) else it }))
                        }
                    }
                    if (selEl.kind == Kind.DOOR || selEl.kind == Kind.WINDOW) {
                        for (dir in listOf(-1.0, 1.0)) {
                            BarButton(if (dir < 0) "◀ 10 cm" else "10 cm ▶") {
                                pushUndo()
                                val (vx, vy) = toWorldVec(selEl, dir * 0.1, 0.0)
                                val moved = attachToWall(selEl.copy(x = selEl.x + vx, y = selEl.y + vy), p, flv, 0.5)
                                putFloor(fiIdx(), flv.copy(els = flv.els.map { if (it.id == selEl.id) moved else it }))
                            }
                        }
                    }
                    BarButton("Kopieren") {
                        pushUndo()
                        val c = selEl.copy(id = UUID.randomUUID().toString(), x = selEl.x + 0.5, y = selEl.y + 0.5)
                        putFloor(fiIdx(), flv.copy(els = flv.els + c))
                        selId = c.id
                    }
                    BarButton("Löschen") {
                        pushUndo()
                        putFloor(fiIdx(), flv.copy(els = flv.els.filter { it.id != selEl.id }))
                        selId = null
                    }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().background(Panel).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            BarButton("+ Raum") { resetTools(); addRoom() }
            BarButton("Raum zeichnen") {
                resetTools()
                tool = 3
                selId = null
                selVert = null
                Toast.makeText(ctx, "Tippe die Ecken des Raums. Zum Schließen auf den ersten Punkt tippen oder „Fertig“.", Toast.LENGTH_LONG).show()
            }
            BarButton(if (placeKind == Kind.DOOR) "Wand antippen …" else "+ Tür") {
                val on = placeKind == Kind.DOOR
                resetTools()
                if (!on) {
                    placeKind = Kind.DOOR
                    Toast.makeText(ctx, "Tippe dorthin, wo die Tür hin soll (auf eine Wand).", Toast.LENGTH_SHORT).show()
                }
            }
            BarButton(if (placeKind == Kind.WINDOW) "Wand antippen …" else "+ Fenster") {
                val on = placeKind == Kind.WINDOW
                resetTools()
                if (!on) {
                    placeKind = Kind.WINDOW
                    Toast.makeText(ctx, "Tippe dorthin, wo das Fenster hin soll (auf eine Wand).", Toast.LENGTH_SHORT).show()
                }
            }
            BarButton("+ Treppe") { stairDlg = true }
            BarButton("+ Möbel") { furnDlg = true }
            BarButton("Haus") { houseDlg = true }
            BarButton("Dach") { roofDlg = true }
            BarButton("Ansicht") { showView = true }
        }
    }
    if (showView) {
        ViewScreen(p, onBack = { showView = false })
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
                                addEl(Kind.FURN, if (f.name.startsWith("Frei")) "" else f.name, f.w, f.h)
                            }.padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(f.name)
                            Text(fmtM(f.w) + " × " + fmtM(f.h) + " m", color = Muted)
                        }
                    }
                }
            }
        )
    }

    if (stairDlg) {
        val defs = listOf(Pair(1.0, 3.0), Pair(2.1, 3.0), Pair(2.2, 2.2), Pair(1.8, 1.8))
        AlertDialog(
            onDismissRequest = { stairDlg = false },
            confirmButton = { TextButton(onClick = { stairDlg = false }) { Text("Schließen") } },
            title = { Text("Treppe einfügen") },
            text = {
                Column {
                    StairType.values().forEachIndexed { i, t ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                stairDlg = false
                                addEl(Kind.STAIR, "", defs[i].first, defs[i].second, sub = i)
                            }.padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(t.label)
                            Text(fmtM(defs[i].first) + " × " + fmtM(defs[i].second) + " m", color = Muted)
                        }
                    }
                }
            }
        )
    }

    if (addFloorDlg) {
        AlertDialog(
            onDismissRequest = { addFloorDlg = false },
            confirmButton = { TextButton(onClick = { addFloorDlg = false }) { Text("Abbrechen") } },
            title = { Text("Geschoss hinzufügen") },
            text = {
                Column {
                    Text("Die Raumaufteilung des aktuellen Geschosses wird als Vorlage kopiert.", color = Muted, fontSize = 13.sp)
                    Spacer(Modifier.size(8.dp))
                    TextButton(onClick = {
                        pushUndo()
                        val top = p.floors.count { !it.cellar }
                        val nm = if (top == 1) "Obergeschoss" else "${top - 1}. Obergeschoss"
                        val nf = Floor(name = nm, height = 2.5, rooms = fl().rooms.map { it.copy(id = UUID.randomUUID().toString()) })
                        p = p.copy(floors = p.floors + nf)
                        fi = p.floors.lastIndex
                        selId = null
                        addFloorDlg = false
                    }) { Text("Oben anfügen (Obergeschoss)") }
                    TextButton(onClick = {
                        pushUndo()
                        val cn = p.floors.count { it.cellar }
                        val nm = if (cn == 0) "Keller" else "${cn + 1}. Keller"
                        val nf = Floor(name = nm, height = 2.4, cellar = true, rooms = fl().rooms.map { it.copy(id = UUID.randomUUID().toString()) })
                        p = p.copy(floors = listOf(nf) + p.floors)
                        fi = 0
                        selId = null
                        addFloorDlg = false
                    }) { Text("Unten anfügen (Keller)") }
                }
            }
        )
    }

    if (floorDlg) {
        val f0 = fl()
        var nm by remember(f0.id) { mutableStateOf(f0.name) }
        var hh by remember(f0.id) { mutableStateOf(fmtNum(f0.height)) }
        var cel by remember(f0.id) { mutableStateOf(f0.cellar) }
        var confirmDel by remember(f0.id) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { floorDlg = false },
            title = { Text("Geschoss") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(value = nm, onValueChange = { nm = it }, label = { Text("Name") }, singleLine = true)
                    Spacer(Modifier.size(8.dp))
                    OutlinedTextField(
                        value = hh, onValueChange = { hh = it }, label = { Text("Raumhöhe (m)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )
                    Spacer(Modifier.size(8.dp))
                    Chips(listOf("Wohngeschoss", "Keller (unter der Erde)"), if (cel) 1 else 0) { cel = it == 1 }
                    if (p.floors.size > 1) {
                        Spacer(Modifier.size(8.dp))
                        TextButton(onClick = {
                            if (!confirmDel) confirmDel = true
                            else {
                                pushUndo()
                                val gi = fiIdx()
                                p = p.copy(floors = p.floors.filterIndexed { i, _ -> i != gi })
                                fi = min(gi, p.floors.lastIndex)
                                selId = null
                                floorDlg = false
                            }
                        }) { Text(if (confirmDel) "Wirklich löschen? Nochmal tippen" else "Geschoss löschen", color = Color(0xFFE57373)) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pushUndo()
                    putFloor(fiIdx(), f0.copy(name = nm.trim().ifEmpty { f0.name }, height = (num(hh) ?: f0.height).coerceIn(1.8, 6.0), cellar = cel))
                    floorDlg = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { floorDlg = false }) { Text("Abbrechen") } }
        )
    }

    if (houseDlg) {
        val f0 = fl()
        val initial = !plan.setup && !p.setup
        var w by remember { mutableStateOf(fmtNum(p.w)) }
        var d by remember { mutableStateOf(fmtNum(p.d)) }
        var hh by remember { mutableStateOf(fmtNum(f0.height)) }
        var oi by remember { mutableStateOf(max(0, WALL_CM.indexOf((p.outerT * 100).toInt()))) }
        var ii by remember { mutableStateOf(max(0, WALL_CM.indexOf((p.innerT * 100).toInt()))) }
        val kb = KeyboardOptions(keyboardType = KeyboardType.Decimal)
        val done = {
            houseDlg = false
            if (!p.setup) p = p.copy(setup = true)
        }
        AlertDialog(
            onDismissRequest = { done() },
            title = { Text(if (initial) "Haus festlegen" else "Haus bearbeiten") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("Außenmaße des Hauses (mit Außenwänden)", color = Muted, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = w, onValueChange = { w = it }, label = { Text("Breite (m)") }, singleLine = true, keyboardOptions = kb, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = d, onValueChange = { d = it }, label = { Text("Tiefe (m)") }, singleLine = true, keyboardOptions = kb, modifier = Modifier.weight(1f))
                    }
                    Spacer(Modifier.size(8.dp))
                    OutlinedTextField(value = hh, onValueChange = { hh = it }, label = { Text("Raumhöhe " + f0.name + " (m)") }, singleLine = true, keyboardOptions = kb)
                    Spacer(Modifier.size(12.dp))
                    Text("Außenwand (cm)", color = Muted, fontSize = 12.sp)
                    Chips(WALL_CM.map { "$it" }, oi) { oi = it }
                    Spacer(Modifier.size(8.dp))
                    Text("Innenwand (cm)", color = Muted, fontSize = 12.sp)
                    Chips(WALL_CM.map { "$it" }, ii) { ii = it }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pushUndo()
                    val np = resizeHouse(
                        p,
                        (num(w) ?: p.w).coerceIn(3.0, 60.0),
                        (num(d) ?: p.d).coerceIn(3.0, 60.0),
                        WALL_CM[oi] / 100.0,
                        WALL_CM[ii] / 100.0
                    ).copy(setup = true)
                    p = np
                    val gi = fiIdx()
                    putFloor(gi, p.floors[gi].copy(height = (num(hh) ?: f0.height).coerceIn(1.8, 6.0)))
                    houseDlg = false
                    fit()
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { done() }) { Text(if (initial) "Später" else "Abbrechen") } }
        )
    }

    if (roofDlg) {
        var rt by remember { mutableStateOf(p.roof.ordinal) }
        var pitch by remember { mutableStateOf(String.format(Locale.GERMANY, "%.0f", p.pitch)) }
        var ovS by remember { mutableStateOf(fmtNum(p.ovS)) }
        var ovE by remember { mutableStateOf(fmtNum(p.ovE)) }
        var ovN by remember { mutableStateOf(fmtNum(p.ovN)) }
        var ovW by remember { mutableStateOf(fmtNum(p.ovW)) }
        var rx by remember { mutableStateOf(p.ridgeX) }
        val kb = KeyboardOptions(keyboardType = KeyboardType.Decimal)
        val type = RoofType.values()[rt]
        AlertDialog(
            onDismissRequest = { roofDlg = false },
            title = { Text("Dach") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("Dachform", color = Muted, fontSize = 12.sp)
                    Chips(RoofType.values().map { it.label }, rt) { rt = it }
                    Spacer(Modifier.size(8.dp))
                    if (type != RoofType.FLAT) {
                        OutlinedTextField(value = pitch, onValueChange = { pitch = it }, label = { Text("Dachneigung (Grad)") }, singleLine = true, keyboardOptions = kb)
                        Spacer(Modifier.size(8.dp))
                    }
                    Text("Dachüberstand je Seite (m)", color = Muted, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = ovS, onValueChange = { ovS = it }, label = { Text("Vorne") }, singleLine = true, keyboardOptions = kb, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = ovN, onValueChange = { ovN = it }, label = { Text("Hinten") }, singleLine = true, keyboardOptions = kb, modifier = Modifier.weight(1f))
                    }
                    Spacer(Modifier.size(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = ovW, onValueChange = { ovW = it }, label = { Text("Links") }, singleLine = true, keyboardOptions = kb, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = ovE, onValueChange = { ovE = it }, label = { Text("Rechts") }, singleLine = true, keyboardOptions = kb, modifier = Modifier.weight(1f))
                    }
                    if (type == RoofType.GABLE || type == RoofType.SHED) {
                        Spacer(Modifier.size(8.dp))
                        Text(if (type == RoofType.GABLE) "Firstrichtung" else "Hohe Seite (Pult)", color = Muted, fontSize = 12.sp)
                        Chips(
                            if (type == RoofType.GABLE) listOf("Längs zur Breite", "Längs zur Tiefe") else listOf("Oben (Nord)", "Links (West)"),
                            if (rx) 0 else 1
                        ) { rx = it == 0 }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pushUndo()
                    p = p.copy(
                        roof = type,
                        pitch = (num(pitch) ?: p.pitch).coerceIn(5.0, 70.0),
                        ovS = (num(ovS) ?: p.ovS).coerceIn(0.0, 3.0),
                        ovN = (num(ovN) ?: p.ovN).coerceIn(0.0, 3.0),
                        ovW = (num(ovW) ?: p.ovW).coerceIn(0.0, 3.0),
                        ovE = (num(ovE) ?: p.ovE).coerceIn(0.0, 3.0),
                        ridgeX = rx
                    )
                    roofDlg = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { roofDlg = false }) { Text("Abbrechen") } }
        )
    }

    if (cornerDlg && selRoom != null && selVert != null) {
        var cw by remember { mutableStateOf("1,50") }
        var cd by remember { mutableStateOf("1,50") }
        val kb = KeyboardOptions(keyboardType = KeyboardType.Decimal)
        AlertDialog(
            onDismissRequest = { cornerDlg = false },
            title = { Text("Ecke abtrennen") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("Länge entlang der beiden Wände an der gewählten Ecke. Der Rest des Raums behält eine Einbuchtung.", color = Muted, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = cw, onValueChange = { cw = it }, label = { Text("Breite (m)") }, singleLine = true, keyboardOptions = kb, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = cd, onValueChange = { cd = it }, label = { Text("Tiefe (m)") }, singleLine = true, keyboardOptions = kb, modifier = Modifier.weight(1f))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val nf = carveCorner(flv, selRoom.id, selVert!!, num(cw) ?: 1.5, num(cd) ?: 1.5)
                    if (nf == null) Toast.makeText(ctx, "Das passt nicht an diese Ecke (zu groß, zu klein oder keine Innenecke).", Toast.LENGTH_LONG).show()
                    else { pushUndo(); putFloor(fiIdx(), nf); selVert = null; cornerDlg = false }
                }) { Text("Abtrennen") }
            },
            dismissButton = { TextButton(onClick = { cornerDlg = false }) { Text("Abbrechen") } }
        )
    }

    if (renameDlg) {
        var tmp by remember { mutableStateOf(p.name) }
        AlertDialog(
            onDismissRequest = { renameDlg = false },
            title = { Text("Name des Plans") },
            text = { OutlinedTextField(value = tmp, onValueChange = { tmp = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { if (tmp.isNotBlank()) p = p.copy(name = tmp.trim()); renameDlg = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { renameDlg = false }) { Text("Abbrechen") } }
        )
    }

    if (editDlg && selRoom != null) {
        RoomDialog(
            r = selRoom, info = roomNet(p, flv, selRoom),
            onDismiss = { editDlg = false },
            onOk = { nm, color, nw, nd ->
                pushUndo()
                var f1 = flv.copy(rooms = flv.rooms.map { if (it.id == selRoom.id) it.copy(name = nm, color = color) else it })
                f1 = setRectNet(p, f1, selRoom.id, nw, nd)
                putFloor(fiIdx(), f1)
                editDlg = false
            }
        )
    }
    if (editDlg && selEl != null) {
        ElDialog(
            e = selEl, f = flv,
            onDismiss = { editDlg = false },
            onOk = { n ->
                pushUndo()
                putFloor(fiIdx(), flv.copy(els = flv.els.map { if (it.id == n.id) n else it }))
                editDlg = false
            }
        )
    }
}

@Composable
private fun BarButton(text: String, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
        Text(text, fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
private fun Chips(items: List<String>, sel: Int, onSel: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEachIndexed { i, s ->
            Box(
                Modifier.background(if (i == sel) Gold else Panel2, RoundedCornerShape(8.dp))
                    .clickable { onSel(i) }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(s, color = if (i == sel) Color(0xFF1A1400) else Ink, fontSize = 13.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun RoomDialog(r: Room, info: NetInfo, onDismiss: () -> Unit, onOk: (String, Int, Double?, Double?) -> Unit) {
    var nm by remember { mutableStateOf(r.name) }
    var w by remember { mutableStateOf(fmtNum(info.w ?: 0.0)) }
    var d by remember { mutableStateOf(fmtNum(info.d ?: 0.0)) }
    var color by remember { mutableStateOf(r.color) }
    val kb = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Raum bearbeiten") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(value = nm, onValueChange = { nm = it }, label = { Text("Name") }, singleLine = true)
                Spacer(Modifier.size(8.dp))
                Text("Fläche " + fmtArea(info.area), color = Muted, fontSize = 13.sp)
                if (info.w != null && info.d != null) {
                    Spacer(Modifier.size(4.dp))
                    Text("Lichte Maße (zwischen den Wänden). Die rechte und untere Wand wandern mit, Nachbarräume gehen mit.", color = Muted, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = w, onValueChange = { w = it }, label = { Text("Breite (m)") }, singleLine = true, keyboardOptions = kb, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = d, onValueChange = { d = it }, label = { Text("Tiefe (m)") }, singleLine = true, keyboardOptions = kb, modifier = Modifier.weight(1f))
                    }
                } else {
                    Spacer(Modifier.size(4.dp))
                    Text("Die Länge jeder Wand steht an der Wand, wenn der Raum angetippt ist. Ecken lassen sich ziehen.", color = Muted, fontSize = 12.sp)
                }
                Spacer(Modifier.size(12.dp))
                Text("Farbe", color = Muted, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    ROOM_COLORS.forEachIndexed { i, c ->
                        Box(
                            Modifier.size(34.dp).background(c)
                                .border(if (i == color) 3.dp else 1.dp, if (i == color) Gold else Muted)
                                .clickable { color = i }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val nw = if (info.w != null) num(w)?.coerceIn(0.5, 100.0) else null
                val nd = if (info.d != null) num(d)?.coerceIn(0.5, 100.0) else null
                onOk(nm.trim(), color, nw, nd)
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } }
    )
}

@Composable
private fun ElDialog(e: El, f: Floor, onDismiss: () -> Unit, onOk: (El) -> Unit) {
    var nm by remember { mutableStateOf(e.name) }
    var w by remember { mutableStateOf(fmtNum(e.w)) }
    var h by remember { mutableStateOf(fmtNum(e.h)) }
    var vh by remember { mutableStateOf(fmtNum(if (e.vh > 0) e.vh else if (e.kind == Kind.DOOR) 2.01 else 1.2)) }
    var sill by remember { mutableStateOf(fmtNum(if (e.sill > 0 || e.kind != Kind.WINDOW) e.sill else 0.9)) }
    var rot by remember { mutableStateOf(String.format(Locale.GERMANY, "%.0f", e.rot)) }
    var sub by remember { mutableStateOf(e.sub) }
    val kb = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    val isOpening = e.kind == Kind.DOOR || e.kind == Kind.WINDOW
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(e.kind.label + " bearbeiten") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (e.kind == Kind.FURN) {
                    OutlinedTextField(value = nm, onValueChange = { nm = it }, label = { Text("Name") }, singleLine = true)
                    Spacer(Modifier.size(8.dp))
                }
                if (e.kind == Kind.STAIR) {
                    Text("Treppenart", color = Muted, fontSize = 12.sp)
                    val defs = listOf(Pair(1.0, 3.0), Pair(2.1, 3.0), Pair(2.2, 2.2), Pair(1.8, 1.8))
                    Chips(StairType.values().map { it.label }, sub) {
                        sub = it
                        w = fmtNum(defs[it].first)
                        h = fmtNum(defs[it].second)
                    }
                    Spacer(Modifier.size(8.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = w, onValueChange = { w = it }, label = { Text("Breite (m)") }, singleLine = true, keyboardOptions = kb, modifier = Modifier.weight(1f))
                    if (!isOpening) {
                        OutlinedTextField(value = h, onValueChange = { h = it }, label = { Text(if (e.kind == Kind.STAIR) "Länge (m)" else "Tiefe (m)") }, singleLine = true, keyboardOptions = kb, modifier = Modifier.weight(1f))
                    } else {
                        OutlinedTextField(value = vh, onValueChange = { vh = it }, label = { Text("Höhe (m)") }, singleLine = true, keyboardOptions = kb, modifier = Modifier.weight(1f))
                    }
                }
                if (e.kind == Kind.WINDOW) {
                    Spacer(Modifier.size(8.dp))
                    OutlinedTextField(value = sill, onValueChange = { sill = it }, label = { Text("Brüstungshöhe (m)") }, singleLine = true, keyboardOptions = kb)
                }
                if (!isOpening) {
                    Spacer(Modifier.size(8.dp))
                    OutlinedTextField(value = rot, onValueChange = { rot = it }, label = { Text("Drehung (Grad)") }, singleLine = true, keyboardOptions = kb)
                }
                if (e.kind == Kind.STAIR) {
                    val n = stairSteps(f)
                    val rise = (f.height + SLAB) / n * 100
                    Spacer(Modifier.size(8.dp))
                    Text(
                        n.toString() + " Stufen bis zum nächsten Geschoss (Steigung ca. " + String.format(Locale.GERMANY, "%.1f", rise) + " cm, Raumhöhe " + fmtM(f.height) + " m)",
                        color = Muted, fontSize = 12.sp
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val nw = (num(w) ?: e.w).coerceIn(0.1, 50.0)
                val nh = (num(h) ?: e.h).coerceIn(0.1, 50.0)
                val nr = ((num(rot) ?: e.rot) % 360.0 + 360.0) % 360.0
                val maxH = f.height
                val nv = (num(vh) ?: 1.2).coerceIn(0.3, maxH)
                val ns = (num(sill) ?: 0.9).coerceIn(0.0, max(0.0, maxH - 0.3))
                onOk(
                    when (e.kind) {
                        Kind.DOOR -> e.copy(w = nw, vh = nv)
                        Kind.WINDOW -> e.copy(w = nw, vh = nv, sill = ns)
                        Kind.FURN -> e.copy(name = nm.trim(), w = nw, h = nh, rot = nr)
                        Kind.STAIR -> e.copy(w = nw, h = nh, rot = nr, sub = sub)
                    }
                )
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } }
    )
}

@Composable
private fun ViewScreen(p: Plan, onBack: () -> Unit) {
    val density = LocalDensity.current.density
    var side by remember { mutableStateOf(0) }
    BackHandler { onBack() }
    Column(Modifier.fillMaxSize().background(Forest)) {
        Row(
            Modifier.fillMaxWidth().background(Panel).padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück", tint = Ink) }
            Text("Ansicht · " + p.name, color = Ink, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1, modifier = Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().background(Panel2).padding(horizontal = 8.dp, vertical = 4.dp)) {
            Chips(VIEW_NAMES, side) { side = it }
        }
        Canvas(Modifier.weight(1f).fillMaxWidth().background(Color.White)) {
            drawView(p, side, 12f * density)
        }
    }
}
