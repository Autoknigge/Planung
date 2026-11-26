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
