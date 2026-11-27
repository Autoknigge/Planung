package de.autoknigge.planung

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

const val EPS = 0.003
const val MIN_AREA = 0.4

fun eq(a: Double, b: Double): Boolean = abs(a - b) < EPS
fun samePt(a: Pt, b: Pt): Boolean = eq(a.x, b.x) && eq(a.y, b.y)

// ------------------------------------------------------------------ Geometrie

fun signedArea(pts: List<Pt>): Double {
    var s = 0.0
    for (i in pts.indices) {
        val a = pts[i]
        val b = pts[(i + 1) % pts.size]
        s += a.x * b.y - b.x * a.y
    }
    return s / 2
}

fun polyArea(pts: List<Pt>): Double = abs(signedArea(pts))

fun pointIn(pts: List<Pt>, x: Double, y: Double): Boolean {
    var inside = false
    var j = pts.size - 1
    for (i in pts.indices) {
        val a = pts[i]
        val b = pts[j]
        if ((a.y > y) != (b.y > y) && x < (b.x - a.x) * (y - a.y) / (b.y - a.y) + a.x) inside = !inside
        j = i
    }
    return inside
}

/** Abstand eines Punktes von einer Strecke und die Lage (0..1) des nächsten Punktes darauf. */
fun segProj(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Pair<Double, Double> {
    val dx = bx - ax
    val dy = by - ay
    val l2 = dx * dx + dy * dy
    val t = if (l2 < 1e-12) 0.0 else clampD(((px - ax) * dx + (py - ay) * dy) / l2, 0.0, 1.0)
    return Pair(hypot(px - (ax + dx * t), py - (ay + dy * t)), t)
}

fun centroid(pts: List<Pt>): Pt {
    val a = signedArea(pts)
    if (abs(a) < 1e-9) return Pt(pts.sumOf { it.x } / pts.size, pts.sumOf { it.y } / pts.size)
    var cx = 0.0
    var cy = 0.0
    for (i in pts.indices) {
        val p = pts[i]
        val q = pts[(i + 1) % pts.size]
        val f = p.x * q.y - q.x * p.y
        cx += (p.x + q.x) * f
        cy += (p.y + q.y) * f
    }
    val c = Pt(cx / (6 * a), cy / (6 * a))
    if (pointIn(pts, c.x, c.y)) return c
    val x0 = pts.minOf { it.x }
    val x1 = pts.maxOf { it.x }
    val y0 = pts.minOf { it.y }
    val y1 = pts.maxOf { it.y }
    val m = Pt((x0 + x1) / 2, (y0 + y1) / 2)
    return if (pointIn(pts, m.x, m.y)) m else pts[0]
}

/** Ecken, die mitten auf der Kante eines anderen Raums liegen, dort als Ecke einfügen. So teilen sich Räume ganze Wände. */
fun normalize(rooms: List<Room>): List<Room> {
    val all = rooms.flatMap { it.pts }
    return rooms.map { r ->
        val out = mutableListOf<Pt>()
        val n = r.pts.size
        for (i in 0 until n) {
            val a = r.pts[i]
            val b = r.pts[(i + 1) % n]
            out.add(a)
            val ins = mutableListOf<Pair<Double, Pt>>()
            for (v in all) {
                if (samePt(v, a) || samePt(v, b)) continue
                val (dd, t) = segProj(v.x, v.y, a.x, a.y, b.x, b.y)
                if (dd < EPS && t > 1e-6 && t < 1 - 1e-6) ins.add(Pair(t, v))
            }
            for (q in ins.sortedBy { it.first }) if (!samePt(out.last(), q.second)) out.add(q.second)
        }
        val clean = out.filterIndexed { i, q -> !samePt(q, out[(i + 1) % out.size]) }
        r.copy(pts = if (clean.size >= 3) clean else r.pts)
    }
}

private fun key(p: Pt) = Pair(Math.round(p.x * 500), Math.round(p.y * 500))

private fun ekey(a: Pt, b: Pt): Pair<Pair<Long, Long>, Pair<Long, Long>> {
    val ka = key(a)
    val kb = key(b)
    return if (ka.first < kb.first || (ka.first == kb.first && ka.second <= kb.second)) Pair(ka, kb) else Pair(kb, ka)
}

/** Alle verschiedenen Wände: Anfang, Ende und wie viele Räume sie nutzen. */
fun uniqueEdges(rooms: List<Room>): List<Triple<Pt, Pt, Int>> {
    val map = LinkedHashMap<Pair<Pair<Long, Long>, Pair<Long, Long>>, Triple<Pt, Pt, Int>>()
    for (r in rooms) {
        val n = r.pts.size
        for (i in 0 until n) {
            val a = r.pts[i]
            val b = r.pts[(i + 1) % n]
            if (samePt(a, b)) continue
            val k = ekey(a, b)
            val ex = map[k]
            map[k] = if (ex == null) Triple(a, b, 1) else Triple(ex.first, ex.second, ex.third + 1)
        }
    }
    return map.values.toList()
}

/** Wandachse mit Dicke. Die Richtung zeigt immer nach rechts oder (bei senkrechten Wänden) nach unten. */
data class Seg(val x0: Double, val y0: Double, val x1: Double, val y1: Double, val t: Double) {
    val len: Double get() = hypot(x1 - x0, y1 - y0)
    val angle: Double get() = Math.toDegrees(atan2(y1 - y0, x1 - x0))
}

fun wallSegs(p: Plan, f: Floor): List<Seg> =
    uniqueEdges(f.rooms).map { (a, b, c) ->
        val flip = b.x < a.x - EPS || (abs(b.x - a.x) < EPS && b.y < a.y)
        val s = if (flip) Pair(b, a) else Pair(a, b)
        Seg(s.first.x, s.first.y, s.second.x, s.second.y, if (c >= 2) p.innerT else p.outerT)
    }

/** Wandflächen (x- und y-Lagen) senkrechter und waagerechter Wände, an denen Möbel einrasten. */
fun wallFaces(p: Plan, f: Floor): Pair<List<Double>, List<Double>> {
    val xs = mutableListOf<Double>()
    val ys = mutableListOf<Double>()
    for (s in wallSegs(p, f)) {
        if (abs(s.x1 - s.x0) < EPS) { xs.add(s.x0 - s.t / 2); xs.add(s.x0 + s.t / 2) }
        if (abs(s.y1 - s.y0) < EPS) { ys.add(s.y0 - s.t / 2); ys.add(s.y0 + s.t / 2) }
    }
    return Pair(xs, ys)
}

/** Tür oder Fenster in die nächste Wand setzen (Lage, Drehung und Wanddicke übernehmen). */
fun attachToWall(e: El, p: Plan, f: Floor, tol: Double): El {
    var best: Seg? = null
    var bd = tol
    var bt = 0.0
    for (s in wallSegs(p, f)) {
        val (d, t) = segProj(e.x, e.y, s.x0, s.y0, s.x1, s.y1)
        if (d < bd) { bd = d; best = s; bt = t }
    }
    val s = best ?: return e
    val len = s.len
    val half = e.w / 2
    val lo = s.t / 2 + half
    val hi = len - s.t / 2 - half
    val along = if (lo > hi) len / 2 else clampD(bt * len, lo, hi)
    val ux = (s.x1 - s.x0) / len
    val uy = (s.y1 - s.y0) / len
    return e.copy(x = s.x0 + ux * along, y = s.y0 + uy * along, rot = s.angle, h = s.t + 0.02)
}

fun reattachAll(p: Plan, tol: Double): Plan = p.copy(
    floors = p.floors.map { f ->
        f.copy(els = f.els.map { if (it.kind == Kind.DOOR || it.kind == Kind.WINDOW) attachToWall(it, p, f, tol) else it })
    }
)

// ------------------------------------------------------------------ Flächen

data class NetInfo(val area: Double, val w: Double?, val d: Double?)

/** Lichte Fläche eines Raums (halbe Wanddicke abgezogen). Bei Rechtecken auch lichte Breite und Tiefe. */
fun roomNet(p: Plan, f: Floor, r: Room): NetInfo {
    val counts = HashMap<Pair<Pair<Long, Long>, Pair<Long, Long>>, Int>()
    for (o in f.rooms) {
        val n = o.pts.size
        for (i in 0 until n) {
            val k = ekey(o.pts[i], o.pts[(i + 1) % n])
            counts[k] = (counts[k] ?: 0) + 1
        }
    }
    val n = r.pts.size
    fun th(a: Pt, b: Pt): Double = if ((counts[ekey(a, b)] ?: 1) >= 2) p.innerT else p.outerT
    val x0 = r.pts.minOf { it.x }
    val x1 = r.pts.maxOf { it.x }
    val y0 = r.pts.minOf { it.y }
    val y1 = r.pts.maxOf { it.y }
    var axis = true
    for (i in 0 until n) {
        val a = r.pts[i]
        val b = r.pts[(i + 1) % n]
        if (abs(a.x - b.x) > EPS && abs(a.y - b.y) > EPS) axis = false
    }
    val gross = polyArea(r.pts)
    if (axis && abs(gross - (x1 - x0) * (y1 - y0)) < 0.01) {
        var tl = 0.0
        var tr = 0.0
        var tt = 0.0
        var tb = 0.0
        for (i in 0 until n) {
            val a = r.pts[i]
            val b = r.pts[(i + 1) % n]
            val t = th(a, b)
            if (eq(a.x, b.x)) { if (eq(a.x, x0)) tl = max(tl, t) else tr = max(tr, t) }
            else { if (eq(a.y, y0)) tt = max(tt, t) else tb = max(tb, t) }
        }
        val w = max(0.0, x1 - x0 - tl / 2 - tr / 2)
        val d = max(0.0, y1 - y0 - tt / 2 - tb / 2)
        return NetInfo(w * d, w, d)
    }
    var s = 0.0
    for (i in 0 until n) {
        val a = r.pts[i]
        val b = r.pts[(i + 1) % n]
        s += hypot(b.x - a.x, b.y - a.y) * th(a, b) / 2
    }
    return NetInfo(max(0.0, gross - s), null, null)
}

// ------------------------------------------------------------------ Trefferprüfung

/** Nächste Wand (Anfang, Ende) zu einem Punkt. */
fun findEdge(rooms: List<Room>, wx: Double, wy: Double, tol: Double): Pair<Pt, Pt>? {
    var best: Pair<Pt, Pt>? = null
    var bd = tol
    for ((a, b, _) in uniqueEdges(rooms)) {
        val (d, _) = segProj(wx, wy, a.x, a.y, b.x, b.y)
        if (d < bd) { bd = d; best = Pair(a, b) }
    }
    return best
}

fun findVertex(r: Room, wx: Double, wy: Double, tol: Double): Pt? {
    var best: Pt? = null
    var bd = tol
    for (q in r.pts) {
        val d = hypot(wx - q.x, wy - q.y)
        if (d < bd) { bd = d; best = q }
    }
    return best
}

/** Index der Kante eines Raums (von Ecke i zu i+1), die dem Punkt am nächsten liegt. */
fun nearestEdgeIndex(r: Room, wx: Double, wy: Double, tol: Double): Int? {
    var best: Int? = null
    var bd = tol
    for (i in r.pts.indices) {
        val a = r.pts[i]
        val b = r.pts[(i + 1) % r.pts.size]
        val (d, _) = segProj(wx, wy, a.x, a.y, b.x, b.y)
        if (d < bd) { bd = d; best = i }
    }
    return best
}

// ------------------------------------------------------------------ Verschieben

/** Alle Wände, die mit der angegebenen in gerader Linie durchlaufen. */
fun chainEdges(rooms: List<Room>, a: Pt, b: Pt): List<Pair<Pt, Pt>> {
    val edges = uniqueEdges(rooms).map { Pair(it.first, it.second) }
    val dx = b.x - a.x
    val dy = b.y - a.y
    val len = hypot(dx, dy)
    val res = mutableListOf(Pair(a, b))
    var changed = true
    while (changed) {
        changed = false
        for (e in edges) {
            if (res.any { samePt(it.first, e.first) && samePt(it.second, e.second) || samePt(it.first, e.second) && samePt(it.second, e.first) }) continue
            val touches = res.any { samePt(it.first, e.first) || samePt(it.first, e.second) || samePt(it.second, e.first) || samePt(it.second, e.second) }
            if (!touches) continue
            val ex = e.second.x - e.first.x
            val ey = e.second.y - e.first.y
            val el = hypot(ex, ey)
            if (el < 1e-9) continue
            if (abs(dx * ey - dy * ex) / (len * el) < 0.003) { res.add(e); changed = true }
        }
    }
    return res
}

fun chainPoints(chain: List<Pair<Pt, Pt>>): List<Pt> {
    val out = mutableListOf<Pt>()
    for ((a, b) in chain) {
        if (out.none { samePt(it, a) }) out.add(a)
        if (out.none { samePt(it, b) }) out.add(b)
    }
    return out
}

/**
 * Wie weit sich ein Eckpunkt bewegen darf, wenn die Außenwand fest bleibt: Ecken und Enden der Außenwand
 * bleiben stehen, Punkte mitten auf einer geraden Außenwand gleiten nur an ihr entlang.
 */
fun shellDelta(rooms: List<Room>, q: Pt, dx: Double, dy: Double): Pair<Double, Double> {
    val inc = uniqueEdges(rooms).filter { it.third == 1 && (samePt(it.first, q) || samePt(it.second, q)) }
    if (inc.isEmpty()) return Pair(dx, dy)
    if (inc.size == 2) {
        val a = inc[0]
        val b = inc[1]
        val ax = a.second.x - a.first.x
        val ay = a.second.y - a.first.y
        val bx = b.second.x - b.first.x
        val by = b.second.y - b.first.y
        val la = hypot(ax, ay)
        val lb = hypot(bx, by)
        if (la > 1e-9 && lb > 1e-9 && abs(ax * by - ay * bx) / (la * lb) < 0.003) {
            val ux = ax / la
            val uy = ay / la
            val s = dx * ux + dy * uy
            return Pair(ux * s, uy * s)
        }
    }
    return Pair(0.0, 0.0)
}

/** Verschiebt die genannten Eckpunkte in allen Räumen (oder nur in [only]). Mit [shell] bleibt die Außenwand fest. */
fun movePoints(rooms: List<Room>, pts: List<Pt>, dx: Double, dy: Double, only: String?, shell: Boolean = false): List<Room> {
    val deltas = pts.map { if (shell) shellDelta(rooms, it, dx, dy) else Pair(dx, dy) }
    return normalize(rooms.map { r ->
        if (only != null && r.id != only) r
        else r.copy(pts = r.pts.map { q ->
            val i = pts.indexOfFirst { samePt(it, q) }
            if (i >= 0) Pt(q.x + deltas[i].first, q.y + deltas[i].second) else q
        })
    })
}

/** Das ganze Haus (alle Geschosse) verschieben. */
fun translatePlan(p: Plan, dx: Double, dy: Double): Plan = p.copy(
    floors = p.floors.map { f ->
        f.copy(
            rooms = f.rooms.map { r -> r.copy(pts = r.pts.map { Pt(it.x + dx, it.y + dy) }) },
            els = f.els.map { it.copy(x = it.x + dx, y = it.y + dy) }
        )
    }
)

/** Türen und Fenster, die an den verschobenen Wänden sitzen, mitnehmen. */
fun shiftOpenings(els: List<El>, chain: List<Pair<Pt, Pt>>, dx: Double, dy: Double): List<El> =
    els.map { e ->
        if (e.kind != Kind.DOOR && e.kind != Kind.WINDOW) e
        else if (chain.any { segProj(e.x, e.y, it.first.x, it.first.y, it.second.x, it.second.y).first < 0.5 }) e.copy(x = e.x + dx, y = e.y + dy)
        else e
    }

// ------------------------------------------------------------------ Räume ändern

private fun clip(pts: List<Pt>, f: (Pt) -> Double): List<Pt> {
    val out = mutableListOf<Pt>()
    for (i in pts.indices) {
        val a = pts[i]
        val b = pts[(i + 1) % pts.size]
        val fa = f(a)
        val fb = f(b)
        if (fa >= 0) out.add(a)
        if ((fa >= 0) != (fb >= 0)) {
            val t = fa / (fa - fb)
            out.add(Pt(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t))
        }
    }
    return out
}

/** Raum teilen. [vertical]: true = senkrechte Trennwand (links/rechts), false = waagerecht, null = entlang der längeren Seite. */
fun splitRoom(f: Floor, id: String, vertical: Boolean?): Floor? {
    val r = f.rooms.firstOrNull { it.id == id } ?: return null
    val x0 = r.pts.minOf { it.x }
    val x1 = r.pts.maxOf { it.x }
    val y0 = r.pts.minOf { it.y }
    val y1 = r.pts.maxOf { it.y }
    val v = vertical ?: ((x1 - x0) >= (y1 - y0))
    val c = snapTo(if (v) (x0 + x1) / 2 else (y0 + y1) / 2, 0.05)
    val a = clip(r.pts) { if (v) c - it.x else c - it.y }
    val b = clip(r.pts) { if (v) it.x - c else it.y - c }
    if (a.size < 3 || b.size < 3 || polyArea(a) < MIN_AREA || polyArea(b) < MIN_AREA) return null
    val rb = Room(name = "Raum " + (f.rooms.size + 1), pts = b, color = f.rooms.size % ROOM_COLORS.size)
    val rooms = f.rooms.flatMap { if (it.id == id) listOf(it.copy(pts = a), rb) else listOf(it) }
    return f.copy(rooms = normalize(rooms))
}

private fun ccw(pts: List<Pt>): List<Pt> = if (signedArea(pts) < 0) pts.reversed() else pts

/** Zwei Räume mit gemeinsamer Wand zu einem Raum vereinen (Name und Farbe von [a] bleiben). */
fun mergeRooms(f0: Floor, a: String, b: String): Floor? {
    val f = f0.copy(rooms = normalize(f0.rooms))
    val ra = f.rooms.firstOrNull { it.id == a } ?: return null
    val rb = f.rooms.firstOrNull { it.id == b } ?: return null
    val ea = mutableListOf<Pair<Pt, Pt>>()
    val eb = mutableListOf<Pair<Pt, Pt>>()
    val pa = ccw(ra.pts)
    val pb = ccw(rb.pts)
    for (i in pa.indices) ea.add(Pair(pa[i], pa[(i + 1) % pa.size]))
    for (i in pb.indices) eb.add(Pair(pb[i], pb[(i + 1) % pb.size]))
    val keepA = ea.filter { x -> eb.none { y -> samePt(x.first, y.second) && samePt(x.second, y.first) } }
    val keepB = eb.filter { y -> ea.none { x -> samePt(x.first, y.second) && samePt(x.second, y.first) } }
    if (keepA.size == ea.size) return null
    val rest = (keepA + keepB).toMutableList()
    if (rest.size < 3) return null
    val loop = mutableListOf<Pt>()
    var cur = rest.removeAt(0)
    loop.add(cur.first)
    while (true) {
        val idx = rest.indexOfFirst { samePt(it.first, cur.second) }
        if (idx < 0) break
        cur = rest.removeAt(idx)
        loop.add(cur.first)
    }
    if (rest.isNotEmpty() || !samePt(cur.second, loop[0])) return null
    val merged = ra.copy(pts = loop)
    val rooms = f.rooms.filter { it.id != b }.map { if (it.id == a) merged else it }
    return f.copy(rooms = normalize(rooms))
}

fun deleteRoom(f: Floor, id: String): Floor? = if (f.rooms.size < 2) null else f.copy(rooms = f.rooms.filter { it.id != id })

/** Neue Ecke auf der Kante [edge] des Raums einfügen. */
fun insertVertex(f: Floor, id: String, edge: Int, at: Pt): Floor {
    val rooms = f.rooms.map { r ->
        if (r.id != id) r
        else r.copy(pts = r.pts.take(edge + 1) + at + r.pts.drop(edge + 1))
    }
    return f.copy(rooms = normalize(rooms))
}

/** Ecke in allen Räumen entfernen. Null, wenn dabei ein Raum weniger als 3 Ecken hätte. */
fun deleteVertex(f: Floor, v: Pt): Floor? {
    val rooms = f.rooms.map { r ->
        if (r.pts.none { samePt(it, v) }) r
        else {
            if (r.pts.size <= 3) return null
            r.copy(pts = r.pts.filter { !samePt(it, v) })
        }
    }
    return f.copy(rooms = normalize(rooms))
}

/**
 * Ecke [v] eines Raums abtrennen: ein Bereich mit den Kantenlängen [a] (die eher waagerechte Kante)
 * und [b] entsteht, der Rest bleibt ein Raum mit Einbuchtung.
 */
fun carveCorner(f: Floor, id: String, v: Pt, a: Double, b: Double): Floor? {
    val r = f.rooms.firstOrNull { it.id == id } ?: return null
    val n = r.pts.size
    val i = r.pts.indexOfFirst { samePt(it, v) }
    if (i < 0) return null
    val prev = r.pts[(i + n - 1) % n]
    val next = r.pts[(i + 1) % n]
    val l1 = hypot(prev.x - v.x, prev.y - v.y)
    val l2 = hypot(next.x - v.x, next.y - v.y)
    if (l1 < 1e-6 || l2 < 1e-6) return null
    val u1 = Pt((prev.x - v.x) / l1, (prev.y - v.y) / l1)
    val u2 = Pt((next.x - v.x) / l2, (next.y - v.y) / l2)
    val firstHoriz = abs(u1.x) >= abs(u2.x)
    val a1 = if (firstHoriz) a else b
    val a2 = if (firstHoriz) b else a
    if (a1 > l1 - 0.1 || a2 > l2 - 0.1 || a1 < 0.3 || a2 < 0.3) return null
    val p1 = Pt(v.x + u1.x * a1, v.y + u1.y * a1)
    val p3 = Pt(v.x + u2.x * a2, v.y + u2.y * a2)
    val q2 = Pt(v.x + u1.x * a1 + u2.x * a2, v.y + u1.y * a1 + u2.y * a2)
    val mid = Pt((v.x + q2.x) / 2, (v.y + q2.y) / 2)
    if (!pointIn(r.pts, mid.x, mid.y)) return null
    val mainPts = r.pts.take(i) + listOf(p1, q2, p3) + r.pts.drop(i + 1)
    val piece = Room(name = "Bereich", pts = listOf(v, p1, q2, p3), color = f.rooms.size % ROOM_COLORS.size)
    val rooms = f.rooms.flatMap { if (it.id == id) listOf(it.copy(pts = mainPts), piece) else listOf(it) }
    return f.copy(rooms = normalize(rooms))
}

/** Anbau: rechteckiger Raum außen an der Kante [edge], [depth] Meter tief. */
fun addAnnex(f: Floor, id: String, edge: Int, depth: Double): Floor? {
    val r = f.rooms.firstOrNull { it.id == id } ?: return null
    val a = r.pts[edge]
    val b = r.pts[(edge + 1) % r.pts.size]
    val len = hypot(b.x - a.x, b.y - a.y)
    if (len < 0.3) return null
    var nx = -(b.y - a.y) / len
    var ny = (b.x - a.x) / len
    val mx = (a.x + b.x) / 2
    val my = (a.y + b.y) / 2
    if (f.rooms.any { pointIn(it.pts, mx + nx * 0.1, my + ny * 0.1) }) { nx = -nx; ny = -ny }
    if (f.rooms.any { pointIn(it.pts, mx + nx * 0.1, my + ny * 0.1) }) return null
    val pts = listOf(a, Pt(a.x + nx * depth, a.y + ny * depth), Pt(b.x + nx * depth, b.y + ny * depth), b)
    val room = Room(name = "Anbau", pts = pts, color = f.rooms.size % ROOM_COLORS.size)
    return f.copy(rooms = normalize(f.rooms + room))
}

/** Neuen Raum aus gezeichneten Eckpunkten anlegen. */
fun addDrawn(f: Floor, pts: List<Pt>): Floor? {
    if (pts.size < 3 || polyArea(pts) < MIN_AREA) return null
    val room = Room(name = "Raum " + (f.rooms.size + 1), pts = pts, color = f.rooms.size % ROOM_COLORS.size)
    return f.copy(rooms = normalize(f.rooms + room))
}

/** Lichte Breite oder Tiefe eines rechteckigen Raums setzen: die rechte bzw. untere Wand wandert. */
fun setRectNet(p: Plan, f: Floor, id: String, netW: Double?, netD: Double?): Floor {
    var floor = f
    for (axis in 0..1) {
        val target = (if (axis == 0) netW else netD) ?: continue
        val r = floor.rooms.firstOrNull { it.id == id } ?: return floor
        val info = roomNet(p, floor, r)
        val cur = (if (axis == 0) info.w else info.d) ?: return floor
        val delta = target - cur
        if (abs(delta) < 0.001) continue
        val hi = if (axis == 0) r.pts.maxOf { it.x } else r.pts.maxOf { it.y }
        val onEdge = r.pts.filter { if (axis == 0) eq(it.x, hi) else eq(it.y, hi) }
        if (onEdge.size < 2) continue
        val a = onEdge.minByOrNull { if (axis == 0) it.y else it.x } ?: continue
        val b = onEdge.maxByOrNull { if (axis == 0) it.y else it.x } ?: continue
        val chain = chainEdges(floor.rooms, a, b)
        val dx = if (axis == 0) delta else 0.0
        val dy = if (axis == 1) delta else 0.0
        floor = floor.copy(
            rooms = movePoints(floor.rooms, chainPoints(chain), dx, dy, null),
            els = shiftOpenings(floor.els, chain, dx, dy)
        )
    }
    return floor
}

/** Gesamtgröße des Hauses ändern: alles wird gleichmäßig gestreckt. Außenmaße inkl. Wände. */
fun resizeHouse(p: Plan, w: Double, d: Double, outerT: Double, innerT: Double): Plan {
    val ow = p.w
    val od = p.d
    val bx = p.bx
    val by = p.by
    val nw = max(w, 2 * outerT + 1.5)
    val nd = max(d, 2 * outerT + 1.5)
    val sx = if (ow - p.outerT > 0.1) (nw - outerT) / (ow - p.outerT) else 1.0
    val sy = if (od - p.outerT > 0.1) (nd - outerT) / (od - p.outerT) else 1.0
    val ax = bx + p.outerT / 2
    val ay = by + p.outerT / 2
    val nax = bx + outerT / 2
    val nay = by + outerT / 2
    fun mx(v: Double) = nax + (v - ax) * sx
    fun my(v: Double) = nay + (v - ay) * sy
    val floors = p.floors.map { f ->
        f.copy(
            rooms = f.rooms.map { r -> r.copy(pts = r.pts.map { Pt(mx(it.x), my(it.y)) }) },
            els = f.els.map { e -> e.copy(x = mx(e.x), y = my(e.y)) }
        )
    }
    return reattachAll(p.copy(outerT = outerT, innerT = innerT, floors = floors), 0.8)
}

/** Anzahl Treppenstufen für ein Geschoss. */
fun stairSteps(f: Floor): Int = max(3, ((f.height + SLAB) / 0.18).roundToInt())
