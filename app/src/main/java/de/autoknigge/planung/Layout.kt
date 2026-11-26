package de.autoknigge.planung

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

const val EPS = 0.003
const val MIN_ROOM = 0.8

fun eq(a: Double, b: Double): Boolean = abs(a - b) < EPS

/** Wandachse mit Dicke. */
data class Seg(val x0: Double, val y0: Double, val x1: Double, val y1: Double, val t: Double) {
    val horizontal: Boolean get() = abs(y1 - y0) < EPS
}

fun wallSegs(p: Plan, f: Floor): List<Seg> {
    val t = p.outerT
    val l = mutableListOf(
        Seg(t / 2, t / 2, p.w - t / 2, t / 2, t),
        Seg(t / 2, p.d - t / 2, p.w - t / 2, p.d - t / 2, t),
        Seg(t / 2, t / 2, t / 2, p.d - t / 2, t),
        Seg(p.w - t / 2, t / 2, p.w - t / 2, p.d - t / 2, t)
    )
    for (r in f.rooms) {
        if (!eq(r.x0, p.ix0)) l.add(Seg(r.x0, r.y0, r.x0, r.y1, p.innerT))
        if (!eq(r.x1, p.ix1)) l.add(Seg(r.x1, r.y0, r.x1, r.y1, p.innerT))
        if (!eq(r.y0, p.iy0)) l.add(Seg(r.x0, r.y0, r.x1, r.y0, p.innerT))
        if (!eq(r.y1, p.iy1)) l.add(Seg(r.x0, r.y1, r.x1, r.y1, p.innerT))
    }
    return l
}

/** Wandflächen (x- und y-Lagen), an denen Möbel einrasten. */
fun wallFaces(p: Plan, f: Floor): Pair<List<Double>, List<Double>> {
    val xs = mutableListOf(p.ix0, p.ix1)
    val ys = mutableListOf(p.iy0, p.iy1)
    val h = p.innerT / 2
    for (r in f.rooms) {
        if (!eq(r.x0, p.ix0)) { xs.add(r.x0 - h); xs.add(r.x0 + h) }
        if (!eq(r.x1, p.ix1)) { xs.add(r.x1 - h); xs.add(r.x1 + h) }
        if (!eq(r.y0, p.iy0)) { ys.add(r.y0 - h); ys.add(r.y0 + h) }
        if (!eq(r.y1, p.iy1)) { ys.add(r.y1 - h); ys.add(r.y1 + h) }
    }
    return Pair(xs, ys)
}

/** Tür oder Fenster in die nächste Wand setzen (Lage, Drehung und Wanddicke übernehmen). */
fun attachToWall(e: El, p: Plan, f: Floor, tol: Double): El {
    var best: Seg? = null
    var bd = tol
    for (s in wallSegs(p, f)) {
        val px = clampD(e.x, min(s.x0, s.x1), max(s.x0, s.x1))
        val py = clampD(e.y, min(s.y0, s.y1), max(s.y0, s.y1))
        val d = sqrt((e.x - px) * (e.x - px) + (e.y - py) * (e.y - py))
        if (d < bd) { bd = d; best = s }
    }
    val s = best ?: return e
    val half = e.w / 2
    return if (s.horizontal) {
        val lo = s.x0 + s.t / 2 + half
        val hi = s.x1 - s.t / 2 - half
        val cx = if (lo > hi) (s.x0 + s.x1) / 2 else clampD(e.x, lo, hi)
        e.copy(x = cx, y = s.y0, rot = 0.0, h = s.t + 0.02)
    } else {
        val lo = s.y0 + s.t / 2 + half
        val hi = s.y1 - s.t / 2 - half
        val cy = if (lo > hi) (s.y0 + s.y1) / 2 else clampD(e.y, lo, hi)
        e.copy(x = s.x0, y = cy, rot = 90.0, h = s.t + 0.02)
    }
}

fun reattachAll(p: Plan, tol: Double): Plan = p.copy(
    floors = p.floors.map { f ->
        f.copy(els = f.els.map { if (it.kind == Kind.DOOR || it.kind == Kind.WINDOW) attachToWall(it, p, f, tol) else it })
    }
)

/** Treffer auf eine innere Trennwand: senkrecht oder waagerecht, Lage und Ausdehnung. */
data class EdgeHit(val vertical: Boolean, val pos: Double, val lo: Double, val hi: Double)

fun findEdge(p: Plan, f: Floor, wx: Double, wy: Double, tol: Double): EdgeHit? {
    var best: EdgeHit? = null
    var bd = tol
    for (r in f.rooms) {
        for (x in listOf(r.x0, r.x1)) {
            if (eq(x, p.ix0) || eq(x, p.ix1)) continue
            if (wy >= r.y0 - tol / 2 && wy <= r.y1 + tol / 2) {
                val d = abs(wx - x)
                if (d < bd) { bd = d; best = EdgeHit(true, x, r.y0, r.y1) }
            }
        }
        for (y in listOf(r.y0, r.y1)) {
            if (eq(y, p.iy0) || eq(y, p.iy1)) continue
            if (wx >= r.x0 - tol / 2 && wx <= r.x1 + tol / 2) {
                val d = abs(wy - y)
                if (d < bd) { bd = d; best = EdgeHit(false, y, r.x0, r.x1) }
            }
        }
    }
    return best
}

/** Gesamte zusammenhängende Wandlinie, zu der der getroffene Abschnitt gehört. */
fun chainOf(rooms: List<Room>, e: EdgeHit): Pair<Double, Double> {
    val spans = mutableListOf<Pair<Double, Double>>()
    for (r in rooms) {
        if (e.vertical) {
            if (eq(r.x0, e.pos) || eq(r.x1, e.pos)) spans.add(Pair(r.y0, r.y1))
        } else {
            if (eq(r.y0, e.pos) || eq(r.y1, e.pos)) spans.add(Pair(r.x0, r.x1))
        }
    }
    var a = e.lo
    var b = e.hi
    var changed = true
    while (changed) {
        changed = false
        for (s in spans) {
            if (s.first < b - EPS && s.second > a + EPS) {
                if (s.first < a - EPS) { a = s.first; changed = true }
                if (s.second > b + EPS) { b = s.second; changed = true }
            }
        }
    }
    return Pair(a, b)
}

data class LineMove(val rooms: List<Room>, val pos: Double)

/** Verschiebt die Wandlinie; Räume auf der einen Seite wachsen, auf der anderen schrumpfen sie. */
fun moveLine(rooms: List<Room>, e: EdgeHit, chain: Pair<Double, Double>, newPos: Double): LineMove {
    val a = chain.first
    val b = chain.second
    var lowLimit = -Double.MAX_VALUE
    var highLimit = Double.MAX_VALUE
    for (r in rooms) {
        val s0 = if (e.vertical) r.y0 else r.x0
        val s1 = if (e.vertical) r.y1 else r.x1
        if (!(s0 < b - EPS && s1 > a + EPS)) continue
        val lo = if (e.vertical) r.x0 else r.y0
        val hi = if (e.vertical) r.x1 else r.y1
        if (eq(hi, e.pos)) lowLimit = max(lowLimit, lo + MIN_ROOM)
        if (eq(lo, e.pos)) highLimit = min(highLimit, hi - MIN_ROOM)
    }
    val np = if (lowLimit > highLimit) e.pos else clampD(newPos, lowLimit, highLimit)
    val out = rooms.map { r ->
        val s0 = if (e.vertical) r.y0 else r.x0
        val s1 = if (e.vertical) r.y1 else r.x1
        if (!(s0 < b - EPS && s1 > a + EPS)) r
        else if (e.vertical) {
            var n = r
            if (eq(r.x1, e.pos)) n = n.copy(x1 = np)
            if (eq(r.x0, e.pos)) n = n.copy(x0 = np)
            n
        } else {
            var n = r
            if (eq(r.y1, e.pos)) n = n.copy(y1 = np)
            if (eq(r.y0, e.pos)) n = n.copy(y0 = np)
            n
        }
    }
    return LineMove(out, np)
}

/** Türen und Fenster auf der verschobenen Wandlinie mitnehmen. */
fun shiftOpenings(els: List<El>, e: EdgeHit, chain: Pair<Double, Double>, delta: Double): List<El> =
    els.map {
        if (it.kind != Kind.DOOR && it.kind != Kind.WINDOW) it
        else if (e.vertical && abs(it.x - e.pos) < 0.5 && it.y >= chain.first - 0.1 && it.y <= chain.second + 0.1) it.copy(x = it.x + delta)
        else if (!e.vertical && abs(it.y - e.pos) < 0.5 && it.x >= chain.first - 0.1 && it.x <= chain.second + 0.1) it.copy(y = it.y + delta)
        else it
    }

private val NEW_NAMES = "Raum"

/** Raum teilen. [vertical]: true = senkrechte Trennwand (links/rechts), false = waagerecht, null = entlang der längeren Seite. */
fun splitRoom(f: Floor, id: String, vertical: Boolean?): Floor? {
    val r = f.rooms.firstOrNull { it.id == id } ?: return null
    val v = vertical ?: ((r.x1 - r.x0) >= (r.y1 - r.y0))
    val len = if (v) r.x1 - r.x0 else r.y1 - r.y0
    if (len < 2 * MIN_ROOM + 0.1) return null
    val pos = snapTo((if (v) r.x0 else r.y0) + len / 2, 0.05)
    val a = if (v) r.copy(x1 = pos) else r.copy(y1 = pos)
    val b = (if (v) r.copy(x0 = pos) else r.copy(y0 = pos)).copy(
        id = java.util.UUID.randomUUID().toString(),
        name = NEW_NAMES + " " + (f.rooms.size + 1),
        color = f.rooms.size % ROOM_COLORS.size
    )
    return f.copy(rooms = f.rooms.flatMap { if (it.id == id) listOf(a, b) else listOf(it) })
}

/** Raum entfernen: ein Nachbar mit gleicher Kante übernimmt die Fläche. */
fun mergeRoom(f: Floor, id: String): Floor? {
    val r = f.rooms.firstOrNull { it.id == id } ?: return null
    if (f.rooms.size < 2) return null
    for (o in f.rooms) {
        if (o.id == id) continue
        val sameY = eq(o.y0, r.y0) && eq(o.y1, r.y1)
        val sameX = eq(o.x0, r.x0) && eq(o.x1, r.x1)
        val grown: Room? = when {
            sameY && eq(o.x1, r.x0) -> o.copy(x1 = r.x1)
            sameY && eq(o.x0, r.x1) -> o.copy(x0 = r.x0)
            sameX && eq(o.y1, r.y0) -> o.copy(y1 = r.y1)
            sameX && eq(o.y0, r.y1) -> o.copy(y0 = r.y0)
            else -> null
        }
        if (grown != null) {
            return f.copy(rooms = f.rooms.filter { it.id != id }.map { if (it.id == o.id) grown else it })
        }
    }
    return null
}

/** Lichte Breite oder Tiefe eines Raums auf einen Wert setzen; der Nachbar gibt nach. */
fun setRoomNet(p: Plan, f: Floor, id: String, netW: Double?, netD: Double?): Floor {
    var floor = f
    for (axis in 0..1) {
        val target = (if (axis == 0) netW else netD) ?: continue
        val r = floor.rooms.firstOrNull { it.id == id } ?: return floor
        val cur = p.net(r).let { if (axis == 0) it.first else it.second }
        val delta = target - cur
        if (abs(delta) < 0.001) continue
        val vertical = axis == 0
        val lo = if (vertical) r.x0 else r.y0
        val hi = if (vertical) r.x1 else r.y1
        val limLo = if (vertical) p.ix0 else p.iy0
        val limHi = if (vertical) p.ix1 else p.iy1
        val hit: EdgeHit
        val newPos: Double
        if (!eq(hi, limHi)) {
            hit = EdgeHit(vertical, hi, if (vertical) r.y0 else r.x0, if (vertical) r.y1 else r.x1)
            newPos = hi + delta
        } else if (!eq(lo, limLo)) {
            hit = EdgeHit(vertical, lo, if (vertical) r.y0 else r.x0, if (vertical) r.y1 else r.x1)
            newPos = lo - delta
        } else continue
        val chain = chainOf(floor.rooms, hit)
        val mv = moveLine(floor.rooms, hit, chain, newPos)
        floor = floor.copy(rooms = mv.rooms, els = shiftOpenings(floor.els, hit, chain, mv.pos - hit.pos))
    }
    return floor
}

private fun pieceMap(v: Double, oldT: Double, oldLen: Double, newT: Double, newLen: Double): Double {
    val k = newT / oldT
    return when {
        v <= oldT -> v * k
        v >= oldLen - oldT -> newLen - newT + (v - (oldLen - oldT)) * k
        else -> newT + (v - oldT) * ((newLen - 2 * newT) / (oldLen - 2 * oldT))
    }
}

/** Außenmaße und Wandstärken ändern. Räume und Elemente werden mit umgerechnet. */
fun resizeHouse(p: Plan, w: Double, d: Double, outerT: Double, innerT: Double): Plan {
    val nw = max(w, 2 * outerT + 1.5)
    val nd = max(d, 2 * outerT + 1.5)
    fun mx(v: Double) = pieceMap(v, p.outerT, p.w, outerT, nw)
    fun my(v: Double) = pieceMap(v, p.outerT, p.d, outerT, nd)
    val floors = p.floors.map { f ->
        f.copy(
            rooms = f.rooms.map { r -> r.copy(x0 = mx(r.x0), x1 = mx(r.x1), y0 = my(r.y0), y1 = my(r.y1)) },
            els = f.els.map { e -> e.copy(x = mx(e.x), y = my(e.y)) }
        )
    }
    val np = p.copy(w = nw, d = nd, outerT = outerT, innerT = innerT, floors = floors)
    return reattachAll(np, 0.8)
}

/** Anzahl Treppenstufen für ein Geschoss. */
fun stairSteps(f: Floor): Int = max(3, ((f.height + SLAB) / 0.18).roundToInt())

/** Zwei Räume mit gemeinsamer Kante gleicher Länge zu einem verbinden. Name und Farbe von [a] bleiben. */
fun mergeTwo(f: Floor, a: String, b: String): Floor? {
    val r = f.rooms.firstOrNull { it.id == a } ?: return null
    val o = f.rooms.firstOrNull { it.id == b } ?: return null
    val sameY = eq(o.y0, r.y0) && eq(o.y1, r.y1)
    val sameX = eq(o.x0, r.x0) && eq(o.x1, r.x1)
    val m: Room = when {
        sameY && (eq(o.x1, r.x0) || eq(o.x0, r.x1)) -> r.copy(x0 = min(r.x0, o.x0), x1 = max(r.x1, o.x1))
        sameX && (eq(o.y1, r.y0) || eq(o.y0, r.y1)) -> r.copy(y0 = min(r.y0, o.y0), y1 = max(r.y1, o.y1))
        else -> return null
    }
    return f.copy(rooms = f.rooms.filter { it.id != b }.map { if (it.id == a) m else it })
}

/**
 * Eine Ecke des Raums als eigenen Bereich abtrennen (lichte Maße [netW] × [netD]).
 * Der Rest besteht aus zwei Rechtecken. Null, wenn der Rest zu klein würde.
 */
fun carveCorner(p: Plan, f: Floor, id: String, netW: Double, netD: Double, left: Boolean, top: Boolean): Floor? {
    val r = f.rooms.firstOrNull { it.id == id } ?: return null
    val h = p.innerT / 2
    val sideX = if (left) (if (eq(r.x0, p.ix0)) 0.0 else h) else (if (eq(r.x1, p.ix1)) 0.0 else h)
    val sideY = if (top) (if (eq(r.y0, p.iy0)) 0.0 else h) else (if (eq(r.y1, p.iy1)) 0.0 else h)
    val cw = snapTo(netW + sideX + h, 0.05)
    val ch = snapTo(netD + sideY + h, 0.05)
    val w = r.x1 - r.x0
    val d = r.y1 - r.y0
    if (cw < MIN_ROOM || ch < MIN_ROOM || cw > w - MIN_ROOM || ch > d - MIN_ROOM) return null
    val cx0 = if (left) r.x0 else r.x1 - cw
    val cx1 = cx0 + cw
    val cy0 = if (top) r.y0 else r.y1 - ch
    val cy1 = cy0 + ch
    val piece = Room(name = "Bereich", color = f.rooms.size % ROOM_COLORS.size, x0 = cx0, y0 = cy0, x1 = cx1, y1 = cy1)
    val main = if (left) r.copy(x0 = cx1) else r.copy(x1 = cx0)
    val under = Room(
        name = "Raum " + (f.rooms.size + 2), color = r.color,
        x0 = cx0, x1 = cx1,
        y0 = if (top) cy1 else r.y0, y1 = if (top) r.y1 else cy0
    )
    return f.copy(rooms = f.rooms.flatMap { if (it.id == id) listOf(main, piece, under) else listOf(it) })
}

/** Einzelne Kante eines Raums: senkrecht/waagerecht und ob es die hintere (rechte/untere) Kante ist. */
data class OwnEdge(val vertical: Boolean, val high: Boolean, val pos: Double)

/** Trifft der Punkt eine Kante genau dieses Raums (auch die zur Außenwand)? */
fun findOwnEdge(p: Plan, r: Room, wx: Double, wy: Double, tol: Double): OwnEdge? {
    var best: OwnEdge? = null
    var bd = tol
    if (wy >= r.y0 - tol / 2 && wy <= r.y1 + tol / 2) {
        run { val d = abs(wx - r.x0); if (d < bd) { bd = d; best = OwnEdge(true, false, r.x0) } }
        run { val d = abs(wx - r.x1); if (d < bd) { bd = d; best = OwnEdge(true, true, r.x1) } }
    }
    if (wx >= r.x0 - tol / 2 && wx <= r.x1 + tol / 2) {
        run { val d = abs(wy - r.y0); if (d < bd) { bd = d; best = OwnEdge(false, false, r.y0) } }
        run { val d = abs(wy - r.y1); if (d < bd) { bd = d; best = OwnEdge(false, true, r.y1) } }
    }
    return best
}

/** Verschiebt nur die Kante eines Raums. Der Nachbar bleibt; es darf eine Lücke entstehen, aber keine Überlappung. */
fun moveSingle(p: Plan, rooms: List<Room>, id: String, e: OwnEdge, newPos: Double): List<Room> {
    val r = rooms.firstOrNull { it.id == id } ?: return rooms
    val lo = if (e.vertical) r.x0 else r.y0
    val hi = if (e.vertical) r.x1 else r.y1
    var minV = if (e.high) lo + MIN_ROOM else (if (e.vertical) p.ix0 else p.iy0)
    var maxV = if (e.high) (if (e.vertical) p.ix1 else p.iy1) else hi - MIN_ROOM
    val s0 = if (e.vertical) r.y0 else r.x0
    val s1 = if (e.vertical) r.y1 else r.x1
    for (o in rooms) {
        if (o.id == id) continue
        val o0 = if (e.vertical) o.y0 else o.x0
        val o1 = if (e.vertical) o.y1 else o.x1
        if (!(o0 < s1 - EPS && o1 > s0 + EPS)) continue
        val olo = if (e.vertical) o.x0 else o.y0
        val ohi = if (e.vertical) o.x1 else o.y1
        if (e.high && olo >= e.pos - EPS) maxV = min(maxV, olo)
        if (!e.high && ohi <= e.pos + EPS) minV = max(minV, ohi)
    }
    val np = if (minV > maxV) e.pos else clampD(newPos, minV, maxV)
    return rooms.map {
        if (it.id != id) it
        else if (e.vertical) (if (e.high) it.copy(x1 = np) else it.copy(x0 = np))
        else (if (e.high) it.copy(y1 = np) else it.copy(y0 = np))
    }
}

/** Freies Rechteck (Lücke zwischen den Räumen) an einem Punkt, oder null wenn dort ein Raum liegt. */
fun freeRectAt(p: Plan, f: Floor, wx: Double, wy: Double): Room? {
    val xs = distinct2(listOf(p.ix0, p.ix1) + f.rooms.flatMap { listOf(it.x0, it.x1) })
    val ys = distinct2(listOf(p.iy0, p.iy1) + f.rooms.flatMap { listOf(it.y0, it.y1) })
    fun free(i: Int, j: Int): Boolean {
        if (i < 0 || j < 0 || i >= xs.size - 1 || j >= ys.size - 1) return false
        val cx = (xs[i] + xs[i + 1]) / 2
        val cy = (ys[j] + ys[j + 1]) / 2
        return f.rooms.none { cx > it.x0 && cx < it.x1 && cy > it.y0 && cy < it.y1 }
    }
    var ci = -1
    var cj = -1
    for (i in 0 until xs.size - 1) if (wx >= xs[i] && wx <= xs[i + 1]) ci = i
    for (j in 0 until ys.size - 1) if (wy >= ys[j] && wy <= ys[j + 1]) cj = j
    if (!free(ci, cj)) return null
    var i0 = ci
    var i1 = ci
    while (free(i0 - 1, cj)) i0--
    while (free(i1 + 1, cj)) i1++
    var j0 = cj
    var j1 = cj
    while ((i0..i1).all { free(it, j0 - 1) }) j0--
    while ((i0..i1).all { free(it, j1 + 1) }) j1++
    return Room(name = "Raum", x0 = xs[i0], x1 = xs[i1 + 1], y0 = ys[j0], y1 = ys[j1 + 1])
}

private fun distinct2(v: List<Double>): List<Double> {
    val out = mutableListOf<Double>()
    for (x in v.sorted()) if (out.isEmpty() || x - out.last() > 0.002) out.add(x)
    return out
}

/** Räume, die genau an der Lücke anliegen (gleiche Kantenlänge) und sich dorthin ausdehnen können. */
fun gapNeighbours(f: Floor, g: Room): List<Room> = f.rooms.filter { r ->
    val sameY = eq(r.y0, g.y0) && eq(r.y1, g.y1)
    val sameX = eq(r.x0, g.x0) && eq(r.x1, g.x1)
    (sameY && (eq(r.x1, g.x0) || eq(r.x0, g.x1))) || (sameX && (eq(r.y1, g.y0) || eq(r.y0, g.y1)))
}

fun growInto(r: Room, g: Room): Room = r.copy(
    x0 = min(r.x0, g.x0), x1 = max(r.x1, g.x1), y0 = min(r.y0, g.y0), y1 = max(r.y1, g.y1)
)
