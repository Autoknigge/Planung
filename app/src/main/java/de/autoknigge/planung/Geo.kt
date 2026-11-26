package de.autoknigge.planung

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Weltpunkt in das Koordinatensystem des Elements (Mittelpunkt, gedreht). */
fun toLocal(e: El, wx: Double, wy: Double): Pair<Double, Double> {
    val r = Math.toRadians(e.rot)
    val dx = wx - e.x
    val dy = wy - e.y
    return Pair(cos(r) * dx + sin(r) * dy, -sin(r) * dx + cos(r) * dy)
}

/** Lokaler Vektor (ohne Verschiebung) in die Welt drehen. */
fun toWorldVec(e: El, lx: Double, ly: Double): Pair<Double, Double> {
    val r = Math.toRadians(e.rot)
    return Pair(cos(r) * lx - sin(r) * ly, sin(r) * lx + cos(r) * ly)
}

fun rank(k: Kind): Int = when (k) {
    Kind.ROOM -> 0
    Kind.WALL -> 1
    Kind.FURN -> 2
    Kind.DOOR -> 3
    Kind.WINDOW -> 3
}

fun hits(e: El, wx: Double, wy: Double, margin: Double): Boolean {
    val (lx, ly) = toLocal(e, wx, wy)
    val hw = max(e.w, if (e.kind == Kind.ROOM) 0.0 else 0.5) / 2 + margin
    val hh = max(e.h, if (e.kind == Kind.ROOM) 0.0 else 0.5) / 2 + margin
    return abs(lx) <= hw && abs(ly) <= hh
}

/** Auf das Raster einrasten. */
fun snapTo(v: Double, step: Double): Double = Math.round(v / step) * step

/** Achsparallele Ausdehnung (halbe Breite/Höhe) bei 0/90/180/270 Grad, sonst null. */
private fun axisHalf(e: El): Pair<Double, Double>? {
    val r = ((e.rot % 180.0) + 180.0) % 180.0
    return when {
        abs(r) < 0.01 -> Pair(e.w / 2, e.h / 2)
        abs(r - 90.0) < 0.01 -> Pair(e.h / 2, e.w / 2)
        else -> null
    }
}

/** Rastet Kanten des bewegten Elements an Kanten anderer Elemente ein (nur achsparallel). */
fun magnet(moving: El, nx: Double, ny: Double, others: List<El>, thr: Double): Pair<Double, Double> {
    val mh = axisHalf(moving) ?: return Pair(nx, ny)
    var bx = nx
    var by = ny
    var dxBest = thr
    var dyBest = thr
    for (o in others) {
        if (o.id == moving.id) continue
        val oh = axisHalf(o) ?: continue
        val oxs = listOf(o.x - oh.first, o.x + oh.first)
        val oys = listOf(o.y - oh.second, o.y + oh.second)
        for (edge in oxs) {
            for (mine in listOf(nx - mh.first, nx + mh.first)) {
                val d = abs(edge - mine)
                if (d < dxBest) { dxBest = d; bx = nx + (edge - mine) }
            }
        }
        for (edge in oys) {
            for (mine in listOf(ny - mh.second, ny + mh.second)) {
                val d = abs(edge - mine)
                if (d < dyBest) { dyBest = d; by = ny + (edge - mine) }
            }
        }
    }
    return Pair(bx, by)
}

/** Tür oder Fenster in die nächste Wand setzen (Lage und Drehung der Wand übernehmen). */
fun attachToWall(e: El, all: List<El>): El {
    var best: El? = null
    var bestV = 0.45
    var bestU = 0.0
    for (w in all) {
        if (w.kind != Kind.WALL) continue
        val (u, v) = toLocal(w, e.x, e.y)
        if (abs(v) < bestV && abs(u) <= w.w / 2) {
            best = w; bestV = abs(v); bestU = u
        }
    }
    val wall = best ?: return e
    val halfLeft = e.w / 2
    val uu = min(max(bestU, -wall.w / 2 + halfLeft), wall.w / 2 - halfLeft)
    val (vx, vy) = toWorldVec(wall, uu, 0.0)
    return e.copy(x = wall.x + vx, y = wall.y + vy, rot = wall.rot, h = wall.h + 0.02)
}

/** Umgebendes Rechteck aller Elemente (minX, minY, maxX, maxY) unter Berücksichtigung der Drehung. */
fun boundsOf(els: List<El>): DoubleArray? {
    if (els.isEmpty()) return null
    var x0 = Double.MAX_VALUE
    var y0 = Double.MAX_VALUE
    var x1 = -Double.MAX_VALUE
    var y1 = -Double.MAX_VALUE
    for (e in els) {
        for (sx in listOf(-1.0, 1.0)) for (sy in listOf(-1.0, 1.0)) {
            val (vx, vy) = toWorldVec(e, sx * e.w / 2, sy * e.h / 2)
            x0 = min(x0, e.x + vx); x1 = max(x1, e.x + vx)
            y0 = min(y0, e.y + vy); y1 = max(y1, e.y + vy)
        }
    }
    return doubleArrayOf(x0, y0, x1, y1)
}
