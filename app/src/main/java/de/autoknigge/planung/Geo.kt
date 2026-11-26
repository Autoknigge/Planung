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
    Kind.STAIR -> 0
    Kind.FURN -> 1
    Kind.DOOR -> 2
    Kind.WINDOW -> 2
}

fun hits(e: El, wx: Double, wy: Double, margin: Double): Boolean {
    val (lx, ly) = toLocal(e, wx, wy)
    val hw = max(e.w, 0.5) / 2 + margin
    val hh = max(e.h, 0.5) / 2 + margin
    return abs(lx) <= hw && abs(ly) <= hh
}

/** Auf das Raster einrasten. */
fun snapTo(v: Double, step: Double): Double = Math.round(v / step) * step

/** Achsparallele Ausdehnung (halbe Breite/Höhe) bei 0/90/180/270 Grad, sonst null. */
private fun axisHalf(e: El): Pair<Double, Double>? {
    val r = ((e.rot % 180.0) + 180.0) % 180.0
    return when {
        abs(r) < 0.01 || abs(r - 180.0) < 0.01 -> Pair(e.w / 2, e.h / 2)
        abs(r - 90.0) < 0.01 -> Pair(e.h / 2, e.w / 2)
        else -> null
    }
}

/** Rastet Kanten des bewegten Elements an Kanten anderer Elemente und an Wandflächen ein (nur achsparallel). */
fun magnet(moving: El, nx: Double, ny: Double, others: List<El>, xs: List<Double>, ys: List<Double>, thr: Double): Pair<Double, Double> {
    val mh = axisHalf(moving) ?: return Pair(nx, ny)
    var bx = nx
    var by = ny
    var dxBest = thr
    var dyBest = thr
    val ex = ArrayList<Double>(xs)
    val ey = ArrayList<Double>(ys)
    for (o in others) {
        if (o.id == moving.id) continue
        val oh = axisHalf(o) ?: continue
        ex.add(o.x - oh.first); ex.add(o.x + oh.first)
        ey.add(o.y - oh.second); ey.add(o.y + oh.second)
    }
    for (edge in ex) for (mine in listOf(nx - mh.first, nx + mh.first)) {
        val d = abs(edge - mine)
        if (d < dxBest) { dxBest = d; bx = nx + (edge - mine) }
    }
    for (edge in ey) for (mine in listOf(ny - mh.second, ny + mh.second)) {
        val d = abs(edge - mine)
        if (d < dyBest) { dyBest = d; by = ny + (edge - mine) }
    }
    return Pair(bx, by)
}

fun clampD(v: Double, lo: Double, hi: Double): Double = min(max(v, lo), hi)
