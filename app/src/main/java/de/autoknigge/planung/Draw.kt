package de.autoknigge.planung

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

val ROOM_COLORS = listOf(
    Color(0xFFDCEBD3), Color(0xFFF6E2BD), Color(0xFFD3E3F2),
    Color(0xFFF2D7DA), Color(0xFFE4DAF0), Color(0xFFECEAC6)
)
val PaperColor = Color(0xFFFBF8EF)
private val WallColor = Color(0xFF3F3F3F)
private val InkColor = Color(0xFF2B2B2B)
private val DimColor = Color(0xFF1F5FA8)
val SelectColor = Color(0xFFE3B341)

private fun paint(size: Float, color: Int, bold: Boolean = false, align: Paint.Align = Paint.Align.CENTER): Paint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        textAlign = align
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

/** Text so verkleinern, dass er in die Breite [maxW] passt. */
private fun fitted(text: String, base: Float, maxW: Float, color: Int, bold: Boolean): Paint {
    val p = paint(base, color, bold)
    val w = p.measureText(text)
    if (w > maxW && maxW > 0f) p.textSize = base * maxW / w
    return p
}

/** Zeichenfläche des Plans: Welt in Metern, Ursprung = linke obere Außenecke des Hauses. */
private class Tf(val scale: Float, val origin: Offset) {
    fun x(v: Double) = origin.x + (v * scale).toFloat()
    fun y(v: Double) = origin.y + (v * scale).toFloat()
    fun s(v: Double) = (v * scale).toFloat()
}

private fun DrawScope.wrect(t: Tf, x: Double, y: Double, w: Double, h: Double, c: Color) {
    drawRect(c, Offset(t.x(x), t.y(y)), Size(t.s(w), t.s(h)))
}

/** Maßlinie von [a] nach [b] mit Punkten an den Enden; Text über der Linie. */
private fun DrawScope.dimLine(a: Offset, b: Offset, text: String, font: Float, line: Float, showText: Boolean = true) {
    drawLine(DimColor, a, b, line)
    drawCircle(DimColor, line * 1.6f, a)
    drawCircle(DimColor, line * 1.6f, b)
    if (!showText) return
    val len = (b - a).getDistance()
    val maxW = len - font * 0.6f
    if (maxW < font * 1.2f) return
    val ang = Math.toDegrees(atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())).toFloat()
    val nc = drawContext.canvas.nativeCanvas
    nc.save()
    nc.translate((a.x + b.x) / 2, (a.y + b.y) / 2)
    nc.rotate(ang)
    nc.drawText(text, 0f, -font * 0.35f, fitted(text, font * 0.95f, maxW, DimColor.toArgb(), true))
    nc.restore()
}

private fun distinct(vals: List<Double>): List<Double> {
    val out = mutableListOf<Double>()
    for (v in vals.sorted()) if (out.isEmpty() || v - out.last() > 0.01) out.add(v)
    return out
}

/** Außenbemaßung: Teilmaße (Wandachsen) und Gesamtlänge, oben und links. */
private fun DrawScope.drawOuterDims(p: Plan, f: Floor, t: Tf, font: Float, line: Float) {
    val allPts = f.rooms.flatMap { it.pts }
    if (allPts.isEmpty()) return
    val minX = allPts.minOf { it.x }
    val maxX = allPts.maxOf { it.x }
    val minY = allPts.minOf { it.y }
    val maxY = allPts.maxOf { it.y }
    val fx0 = minX - p.outerT / 2
    val fx1 = maxX + p.outerT / 2
    val fy0 = minY - p.outerT / 2
    val fy1 = maxY + p.outerT / 2
    val xs = distinct(listOf(fx0, fx1) + allPts.map { it.x }.filter { it > minX + 0.01 && it < maxX - 0.01 })
    val ys = distinct(listOf(fy0, fy1) + allPts.map { it.y }.filter { it > minY + 0.01 && it < maxY - 0.01 })
    val ext = Color(0x661F5FA8)
    val o1 = 0.8
    val o2 = 1.5
    // oben
    for (x in xs) drawLine(ext, Offset(t.x(x), t.y(fy0 - 0.1)), Offset(t.x(x), t.y(fy0 - o2 - 0.15)), line * 0.7f)
    for (i in 0 until xs.size - 1) {
        dimLine(Offset(t.x(xs[i]), t.y(fy0 - o1)), Offset(t.x(xs[i + 1]), t.y(fy0 - o1)), fmtM(xs[i + 1] - xs[i]), font, line)
    }
    dimLine(Offset(t.x(fx0), t.y(fy0 - o2)), Offset(t.x(fx1), t.y(fy0 - o2)), fmtM(fx1 - fx0), font, line)
    // links
    for (y in ys) drawLine(ext, Offset(t.x(fx0 - 0.1), t.y(y)), Offset(t.x(fx0 - o2 - 0.15), t.y(y)), line * 0.7f)
    for (i in 0 until ys.size - 1) {
        dimLine(Offset(t.x(fx0 - o1), t.y(ys[i + 1])), Offset(t.x(fx0 - o1), t.y(ys[i])), fmtM(ys[i + 1] - ys[i]), font, line)
    }
    dimLine(Offset(t.x(fx0 - o2), t.y(fy1)), Offset(t.x(fx0 - o2), t.y(fy0)), fmtM(fy1 - fy0), font, line)
}

/** Stelle im Raum, an der die Beschriftung nicht von Möbeln oder Treppen verdeckt wird (möglichst nah an der Mitte). */
private fun labelSpot(r: Room, els: List<El>, wm: Double, hm: Double): Pt {
    val c = centroid(r.pts)
    val obst = els.filter { it.kind == Kind.FURN || it.kind == Kind.STAIR }.map { e ->
        var x0 = Double.MAX_VALUE
        var y0 = Double.MAX_VALUE
        var x1 = -Double.MAX_VALUE
        var y1 = -Double.MAX_VALUE
        for (sx in listOf(-1.0, 1.0)) for (sy in listOf(-1.0, 1.0)) {
            val (vx, vy) = toWorldVec(e, sx * e.w / 2, sy * e.h / 2)
            x0 = min(x0, e.x + vx); x1 = max(x1, e.x + vx)
            y0 = min(y0, e.y + vy); y1 = max(y1, e.y + vy)
        }
        doubleArrayOf(x0, y0, x1, y1)
    }
    if (obst.isEmpty()) return c
    fun free(x: Double, y: Double): Boolean {
        val l = x - wm / 2
        val rr = x + wm / 2
        val tp = y - hm / 2
        val b = y + hm / 2
        for (q in listOf(Pair(l, tp), Pair(rr, tp), Pair(l, b), Pair(rr, b), Pair(x, y))) if (!pointIn(r.pts, q.first, q.second)) return false
        return obst.none { l < it[2] && rr > it[0] && tp < it[3] && b > it[1] }
    }
    if (free(c.x, c.y)) return c
    val x0 = r.pts.minOf { it.x }
    val x1 = r.pts.maxOf { it.x }
    val y0 = r.pts.minOf { it.y }
    val y1 = r.pts.maxOf { it.y }
    var best: Pt? = null
    var bd = Double.MAX_VALUE
    var x = x0
    while (x <= x1) {
        var y = y0
        while (y <= y1) {
            val dd = (x - c.x) * (x - c.x) + (y - c.y) * (y - c.y)
            if (dd < bd && free(x, y)) { bd = dd; best = Pt(x, y) }
            y += 0.2
        }
        x += 0.2
    }
    return best ?: c
}

private fun DrawScope.pathOf(t: Tf, pts: List<Pt>): Path {
    val path = Path()
    pts.forEachIndexed { i, q -> if (i == 0) path.moveTo(t.x(q.x), t.y(q.y)) else path.lineTo(t.x(q.x), t.y(q.y)) }
    path.close()
    return path
}

/**
 * Zeichnet ein Geschoss. [scale] sind Pixel je Meter, [origin] die Bildschirmposition des Weltpunkts (0, 0),
 * [font] die Grundschriftgröße in Pixeln.
 */
fun DrawScope.drawFloor(
    p: Plan,
    fi: Int,
    scale: Float,
    origin: Offset,
    font: Float,
    selectedId: String?,
    grid: Boolean,
    roomDims: Boolean,
    outerDims: Boolean,
    selVert: Pt? = null,
    pending: List<Pt> = emptyList()
) {
    val f = p.floors[fi]
    val t = Tf(scale, origin)
    if (grid && scale >= 8f) {
        val x0 = floor(-origin.x / scale).toInt() - 1
        val x1 = ceil((size.width - origin.x) / scale).toInt() + 1
        val y0 = floor(-origin.y / scale).toInt() - 1
        val y1 = ceil((size.height - origin.y) / scale).toInt() + 1
        val minor = Color(0x1A000000)
        val major = Color(0x40000000)
        for (i in x0..x1) {
            val px = origin.x + i * scale
            drawLine(if (i % 5 == 0) major else minor, Offset(px, 0f), Offset(px, size.height), if (i % 5 == 0) 1.5f else 1f)
        }
        for (j in y0..y1) {
            val py = origin.y + j * scale
            drawLine(if (j % 5 == 0) major else minor, Offset(0f, py), Offset(size.width, py), if (j % 5 == 0) 1.5f else 1f)
        }
    }
    val nc = drawContext.canvas.nativeCanvas
    val line = maxOf(1f, font * 0.12f)

    // Räume
    for (r in f.rooms) drawPath(pathOf(t, r.pts), ROOM_COLORS[r.color.coerceIn(0, ROOM_COLORS.size - 1)], style = Fill)
    // Treppe des Geschosses darunter (gestrichelt)
    if (fi > 0) {
        for (e in p.floors[fi - 1].els) {
            if (e.kind != Kind.STAIR) continue
            withTransform({
                translate(t.x(e.x), t.y(e.y))
                rotate(e.rot.toFloat(), Offset.Zero)
            }) {
                drawRect(
                    Color(0xAA6B5B45), Offset(-t.s(e.w) / 2, -t.s(e.h) / 2), Size(t.s(e.w), t.s(e.h)),
                    style = Stroke(line, pathEffect = PathEffect.dashPathEffect(floatArrayOf(font * 0.7f, font * 0.5f)))
                )
            }
        }
    }
    // Wände (Achsen mit Dicke)
    for (s in wallSegs(p, f)) {
        drawLine(WallColor, Offset(t.x(s.x0), t.y(s.y0)), Offset(t.x(s.x1), t.y(s.y1)), t.s(s.t), cap = StrokeCap.Square)
    }
    // Auswahl Raum
    val selRoom = f.rooms.firstOrNull { it.id == selectedId }
    if (selRoom != null) {
        drawPath(
            pathOf(t, selRoom.pts), SelectColor,
            style = Stroke(line * 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(font, font * 0.6f)))
        )
        val m = selRoom.pts.size
        val cen = centroid(selRoom.pts)
        for (i in 0 until m) {
            val a = selRoom.pts[i]
            val b = selRoom.pts[(i + 1) % m]
            val len = kotlin.math.hypot(b.x - a.x, b.y - a.y)
            if (len < 0.2) continue
            var ang = Math.toDegrees(atan2(b.y - a.y, b.x - a.x))
            if (ang > 90 || ang <= -90) ang += 180.0
            val mx = (a.x + b.x) / 2
            val my = (a.y + b.y) / 2
            // zur Rauminnenseite versetzen
            var nx = -(b.y - a.y) / len
            var ny = (b.x - a.x) / len
            if ((cen.x - mx) * nx + (cen.y - my) * ny < 0) { nx = -nx; ny = -ny }
            val px = t.x(mx) + (nx * font * 1.1).toFloat()
            val py = t.y(my) + (ny * font * 1.1).toFloat()
            val txt = fmtM(len)
            nc.save()
            nc.translate(px, py)
            nc.rotate(ang.toFloat())
            nc.drawText(txt, 0f, font * 0.3f, fitted(txt, font * 0.95f, t.s(len) * 0.9f, DimColor.toArgb(), true))
            nc.restore()
        }
        for (q in selRoom.pts) {
            val on = selVert != null && samePt(q, selVert)
            drawCircle(if (on) SelectColor else Color.White, font * 0.8f, Offset(t.x(q.x), t.y(q.y)))
            drawCircle(WallColor, font * 0.8f, Offset(t.x(q.x), t.y(q.y)), style = Stroke(line))
        }
    }

    // Raumbeschriftung
    for (r in f.rooms) {
        val bw = t.s(r.pts.maxOf { it.x } - r.pts.minOf { it.x })
        val bh = t.s(r.pts.maxOf { it.y } - r.pts.minOf { it.y })
        val net = roomNet(p, f, r)
        val title = if (r.name.isBlank()) "Raum" else r.name
        val wMeters = (maxOf(paint(font * 1.1f, 0, true).measureText(title), paint(font, 0, false).measureText(fmtArea(net.area))).coerceAtMost(bw * 0.8f) / scale).toDouble()
        val spot = labelSpot(r, f.els, wMeters + 0.2, (font * (if (roomDims && net.w != null) 3.8f else 2.8f) / scale).toDouble())
        val cx = t.x(spot.x)
        val cy = t.y(spot.y)
        if (bw > font * 3 && bh > font * 2.5f) {
            nc.drawText(title, cx, cy - font * 0.1f, fitted(title, font * 1.1f, bw * 0.8f, InkColor.toArgb(), true))
            val a = fmtArea(net.area)
            nc.drawText(a, cx, cy + font * 1.2f, fitted(a, font, bw * 0.8f, 0xFF555555.toInt(), false))
            if (roomDims && net.w != null && net.d != null && r.id != selectedId) {
                val dt = fmtM(net.w) + " × " + fmtM(net.d)
                nc.drawText(dt, cx, cy + font * 2.4f, fitted(dt, font * 0.9f, bw * 0.8f, DimColor.toArgb(), true))
            }
        }
    }
    if (pending.isNotEmpty()) {
        for (i in 0 until pending.size - 1) {
            drawLine(SelectColor, Offset(t.x(pending[i].x), t.y(pending[i].y)), Offset(t.x(pending[i + 1].x), t.y(pending[i + 1].y)), line * 2f)
        }
        for (q in pending) {
            drawCircle(SelectColor, font * 0.7f, Offset(t.x(q.x), t.y(q.y)))
            drawCircle(WallColor, font * 0.7f, Offset(t.x(q.x), t.y(q.y)), style = Stroke(line))
        }
    }

    // Elemente
    for (e in f.els.sortedBy { rank(it.kind) }) {
        val c = Offset(t.x(e.x), t.y(e.y))
        val wp = t.s(e.w)
        val hp = t.s(e.h)
        val tl = Offset(-wp / 2, -hp / 2)
        val sz = Size(wp, hp)
        val isSel = e.id == selectedId
        withTransform({
            translate(c.x, c.y)
            rotate(e.rot.toFloat(), Offset.Zero)
            if (e.kind == Kind.DOOR) scale(if (e.sub and 1 != 0) -1f else 1f, if (e.sub and 2 != 0) -1f else 1f, Offset.Zero)
        }) {
            when (e.kind) {
                Kind.STAIR -> drawStair(e.sub, wp, hp, stairSteps(f), line, font)
                Kind.FURN -> {
                    drawRoundRect(Color(0xFFEADFC9), tl, sz, CornerRadius(font * 0.25f, font * 0.25f))
                    drawRoundRect(Color(0xFF8A7355), tl, sz, CornerRadius(font * 0.25f, font * 0.25f), style = Stroke(line))
                }
                Kind.DOOR -> {
                    drawRect(Color.White, tl, sz)
                    drawLine(InkColor, Offset(-wp / 2, 0f), Offset(-wp / 2, -wp), line)
                    drawArc(
                        InkColor, -90f, 90f, false,
                        Offset(-wp / 2 - wp, -wp), Size(2 * wp, 2 * wp), style = Stroke(line * 0.8f)
                    )
                }
                Kind.WINDOW -> {
                    drawRect(Color.White, tl, sz)
                    drawRect(InkColor, tl, sz, style = Stroke(line))
                    drawLine(Color(0xFF3F7FBF), Offset(-wp / 2, 0f), Offset(wp / 2, 0f), line * 1.2f)
                }
            }
            if (isSel) {
                val m = font * 0.3f
                drawRect(
                    SelectColor, Offset(tl.x - m, tl.y - m), Size(sz.width + 2 * m, sz.height + 2 * m),
                    style = Stroke(line * 1.6f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(font, font * 0.6f)))
                )
                val flip = (((e.rot % 360.0) + 360.0) % 360.0).let { it > 90.0 && it < 270.0 }
                val dp = paint(font * 0.95f, DimColor.toArgb(), true)
                nc.save()
                if (flip) nc.rotate(180f)
                nc.drawText(fmtM(e.w) + " m", 0f, -hp / 2 - font * 0.9f, dp)
                if (e.kind == Kind.FURN || e.kind == Kind.STAIR) {
                    nc.save()
                    nc.translate(-wp / 2 - font * 0.7f, 0f)
                    nc.rotate(-90f)
                    nc.drawText(fmtM(e.h) + " m", 0f, 0f, dp)
                    nc.restore()
                }
                nc.restore()
                if (e.kind == Kind.FURN || e.kind == Kind.STAIR) {
                    drawCircle(SelectColor, font * 0.85f, Offset(wp / 2, hp / 2))
                    drawCircle(Color(0xFF3F3F3F), font * 0.85f, Offset(wp / 2, hp / 2), style = Stroke(line))
                }
            }
        }
        if (e.kind == Kind.FURN && e.name.isNotBlank() && min(wp, hp) > font * 1.2f) {
            nc.drawText(e.name, c.x, c.y + font * 0.3f, fitted(e.name, font * 0.85f, wp * 0.92f, 0xFF5B4B33.toInt(), false))
        }
    }

    if (outerDims) drawOuterDims(p, f, t, font, line)
}

private fun DrawScope.drawStair(type: Int, wp: Float, hp: Float, n: Int, line: Float, font: Float) {
    val c = Color(0xFF6B5B45)
    val fill = Color(0xFFF2EEE4)
    val thin = line * 0.7f
    val l = -wp / 2
    val r = wp / 2
    val tp = -hp / 2
    val bt = hp / 2
    when (type) {
        StairType.SPIRAL.ordinal -> {
            val rad = min(wp, hp) / 2
            drawCircle(fill, rad, Offset.Zero)
            for (i in 0 until n) {
                val a = 2 * PI * i / n
                drawLine(c, Offset((cos(a) * rad * 0.12).toFloat(), (sin(a) * rad * 0.12).toFloat()), Offset((cos(a) * rad).toFloat(), (sin(a) * rad).toFloat()), thin)
            }
            drawCircle(c, rad, Offset.Zero, style = Stroke(line))
            drawCircle(c, rad * 0.12f, Offset.Zero)
        }
        StairType.QUARTER.ordinal -> {
            val a = min(wp, hp) * 0.5f
            drawRect(Color(0x11000000), Offset(l, tp), Size(wp, hp))
            val path = Path().apply {
                moveTo(l, bt); lineTo(l, tp); lineTo(r, tp); lineTo(r, tp + a); lineTo(l + a, tp + a); lineTo(l + a, bt); close()
            }
            drawPath(path, fill, style = Fill)
            val winders = 3
            val rest = max(2, n - winders)
            val k1 = rest / 2
            val k2 = rest - k1
            // Gerade Stufen im senkrechten Schenkel
            val legLen1 = bt - (tp + a)
            for (i in 1..k1) {
                val y = (tp + a) + legLen1 * i / (k1 + 1)
                drawLine(c, Offset(l, y), Offset(l + a, y), thin)
            }
            // Gerade Stufen im oberen Schenkel
            val legLen2 = r - (l + a)
            for (i in 1..k2) {
                val x = (l + a) + legLen2 * i / (k2 + 1)
                drawLine(c, Offset(x, tp), Offset(x, tp + a), thin)
            }
            // Wendelstufen
            val px = l + a
            val py = tp + a
            val pts = listOf(Offset(l, py - a * 0.5f), Offset(l, tp), Offset(l + a * 0.5f, tp))
            for (q in pts) drawLine(c, Offset(px, py), q, thin)
            drawPath(path, c, style = Stroke(line))
            drawArrow(Offset(l + a / 2, bt - a * 0.2f), Offset(l + a / 2, bt - a * 0.2f - min(hp * 0.25f, a * 1.2f)), c, line, font)
        }
        StairType.HALF.ordinal -> {
            val z = min(wp / 2, hp / 2)
            drawRect(fill, Offset(l, tp), Size(wp, hp))
            val winders = 5
            val rest = max(2, n - winders)
            val k = rest / 2
            val legLen = bt - (tp + z)
            for (i in 1..k) {
                val y = (tp + z) + legLen * i / (k + 1)
                drawLine(c, Offset(l, y), Offset(r, y), thin)
            }
            drawLine(c, Offset(0f, bt), Offset(0f, tp + z), thin)
            val px = 0f
            val py = tp + z
            val pathLen = z + wp + z
            for (i in 1..winders) {
                var d = pathLen * i / (winders + 1)
                val q = if (d <= z) Offset(l, py - d)
                else {
                    d -= z
                    if (d <= wp) Offset(l + d, tp) else Offset(r, tp + (d - wp))
                }
                drawLine(c, Offset(px, py), q, thin)
            }
            drawRect(c, Offset(l, tp), Size(wp, hp), style = Stroke(line))
            drawArrow(Offset(l + wp / 4, bt - font * 0.5f), Offset(l + wp / 4, bt - font * 0.5f - min(hp * 0.3f, font * 4f)), c, line, font)
        }
        else -> {
            drawRect(fill, Offset(l, tp), Size(wp, hp))
            for (i in 1 until n) {
                val y = tp + hp * i / n
                drawLine(c, Offset(l, y), Offset(r, y), thin)
            }
            drawRect(c, Offset(l, tp), Size(wp, hp), style = Stroke(line))
            drawArrow(Offset(0f, bt - font * 0.6f), Offset(0f, tp + hp * 0.3f), c, line, font)
        }
    }
}

private fun DrawScope.drawArrow(from: Offset, to: Offset, c: Color, line: Float, font: Float) {
    drawLine(c, from, to, line)
    val ang = atan2((to.y - from.y).toDouble(), (to.x - from.x).toDouble())
    val h = font * 0.7f
    for (da in listOf(2.6, -2.6)) {
        drawLine(c, to, Offset(to.x + (cos(ang + da) * h).toFloat(), to.y + (sin(ang + da) * h).toFloat()), line)
    }
}

// ---------------------------------------------------------------- Ansichten

fun roofRise(p: Plan): Double {
    val t = tan(Math.toRadians(p.pitch))
    return when (p.roof) {
        RoofType.FLAT -> 0.0
        RoofType.GABLE -> (if (p.ridgeX) p.d else p.w) / 2 * t
        RoofType.HIP, RoofType.TENT -> min(p.w, p.d) / 2 * t
        RoofType.SHED -> (if (p.ridgeX) p.d else p.w) * t
    }
}

/** Überstände für eine Ansicht: links, rechts (im Bild) und zur Tiefe hin. */
private fun ovFor(p: Plan, side: Int): Triple<Double, Double, Double> = when (side) {
    0 -> Triple(p.ovW, p.ovE, max(p.ovN, p.ovS))
    1 -> Triple(p.ovS, p.ovN, max(p.ovE, p.ovW))
    2 -> Triple(p.ovE, p.ovW, max(p.ovN, p.ovS))
    else -> Triple(p.ovN, p.ovS, max(p.ovE, p.ovW))
}

/** Seite des Hauses (Ansicht), auf der das Pultdach hoch ist. */
private fun shedHighSide(p: Plan): Int = if (p.ridgeX) 2 else 3

/** Dachfläche in Metern: u von links nach rechts, z über Oberkante der Wände. Leer, wenn das Dach nur als Platte auf einer Wand gezeichnet wird. */
private fun roofPoly(p: Plan, side: Int): List<Pair<Double, Double>> {
    val lv = if (side % 2 == 0) p.w else p.d
    val ld = if (side % 2 == 0) p.d else p.w
    val (oL, oR, oD) = ovFor(p, side)
    val tn = tan(Math.toRadians(p.pitch))
    val eL = oL * tn
    val eR = oR * tn
    val eD = oD * tn
    val ridgeAlong = if (side % 2 == 0) p.ridgeX else !p.ridgeX
    return when (p.roof) {
        RoofType.FLAT -> listOf(-oL to 0.0, lv + oR to 0.0, lv + oR to 0.3, -oL to 0.3)
        RoofType.GABLE -> if (ridgeAlong) {
            val rise = ld / 2 * tn
            listOf(-oL to -eD, lv + oR to -eD, lv + oR to rise, -oL to rise)
        } else emptyList()
        RoofType.HIP -> {
            val rise = min(p.w, p.d) / 2 * tn
            if (lv > ld + 0.01) listOf(-oL to -eL, lv + oR to -eR, lv - ld / 2 to rise, ld / 2 to rise)
            else listOf(-oL to -eL, lv / 2 to rise, lv + oR to -eR)
        }
        RoofType.TENT -> listOf(-oL to -eL, lv / 2 to min(p.w, p.d) / 2 * tn, lv + oR to -eR)
        RoofType.SHED -> {
            val rise = (if (p.ridgeX) p.d else p.w) * tn
            if (ridgeAlong && side != shedHighSide(p)) listOf(-oL to -eD, lv + oR to -eD, lv + oR to rise, -oL to rise)
            else emptyList()
        }
    }
}

val VIEW_NAMES = listOf("Vorne", "Rechts", "Hinten", "Links", "Dach von oben")

/** Zeichnet eine Ansicht (0 vorne, 1 rechts, 2 hinten, 3 links) oder die Dachaufsicht (4) und passt sie in die Fläche ein. */
fun DrawScope.drawView(p: Plan, side: Int, font: Float) {
    if (side == 4) {
        drawRoofTop(p, font)
        return
    }
    val nc = drawContext.canvas.nativeCanvas
    val line = maxOf(1f, font * 0.12f)
    val lv = if (side % 2 == 0) p.w else p.d
    val n = p.floors.size
    var base = p.floors.indexOfFirst { !it.cellar }
    if (base < 0) base = n
    val zb = DoubleArray(n)
    var z = 0.0
    for (i in base until n) { zb[i] = z; z += p.floors[i].height + SLAB }
    val wallTop = if (n > base) zb[n - 1] + p.floors[n - 1].height else 0.0
    z = 0.0
    for (i in base - 1 downTo 0) { z -= p.floors[i].height + SLAB; zb[i] = z }
    val minZ = if (base > 0) zb[0] - 0.2 else -0.6
    val rise = roofRise(p)
    val tn = tan(Math.toRadians(p.pitch))
    val (oL, oR, _) = ovFor(p, side)
    val maxZ = wallTop + max(rise + max(oL, oR) * tn + 0.4, 0.5)
    val mLeft = font * 2.5f
    val mRight = font * 9f
    val mTop = font * 2f
    val mBottom = font * 3f
    val spanX = lv + oL + oR + 1.0
    val spanZ = maxZ - minZ
    val sc = min((size.width - mLeft - mRight) / spanX.toFloat(), (size.height - mTop - mBottom) / spanZ.toFloat())
    val ox = mLeft + (oL + 0.5).toFloat() * sc
    val oy = mTop + (maxZ * sc).toFloat()
    fun px(u: Double) = ox + (u * sc).toFloat()
    fun pz(zz: Double) = oy - (zz * sc).toFloat()

    // Erde
    drawRect(Color(0x33A27B4B), Offset(px(-oL - 0.5), pz(0.0)), Size(((lv + oL + oR + 1.0) * sc).toFloat(), (-minZ * sc).toFloat()))
    // Geschosse
    for (i in 0 until n) {
        val f = p.floors[i]
        val col = if (f.cellar) Color(0xFFD5CCBA) else Color(0xFFEDE6D6)
        drawRect(col, Offset(px(0.0), pz(zb[i] + f.height + SLAB)), Size((lv * sc).toFloat(), ((f.height + SLAB) * sc).toFloat()))
        drawLine(Color(0xFF8A8A8A), Offset(px(0.0), pz(zb[i] + f.height)), Offset(px(lv), pz(zb[i] + f.height)), line)
        drawRect(Color(0xFF3F3F3F), Offset(px(0.0), pz(zb[i] + f.height + SLAB)), Size((lv * sc).toFloat(), (SLAB * sc).toFloat()), style = Stroke(line))
        // Beschriftung rechts
        val label = f.name + " · " + fmtM(f.height) + " m"
        nc.drawText(label, px(lv + oR) + font * 0.8f, pz(zb[i] + f.height / 2) + font * 0.3f, paint(font * 0.9f, 0xFF333333.toInt(), false, Paint.Align.LEFT))
    }
    val houseBottom = if (base > 0) zb[0] else 0.0
    drawRect(Color(0xFF3F3F3F), Offset(px(0.0), pz(wallTop)), Size((lv * sc).toFloat(), ((wallTop - houseBottom) * sc).toFloat()), style = Stroke(line * 1.4f))
    // Öffnungen
    val tw = p.outerT
    for (i in 0 until n) {
        for (e in p.floors[i].els) {
            if (e.kind != Kind.DOOR && e.kind != Kind.WINDOW) continue
            val rx = e.x - p.bx
            val ry = e.y - p.by
            val u: Double? = when (side) {
                0 -> if (abs(ry - (p.d - tw / 2)) < tw) rx else null
                1 -> if (abs(rx - (p.w - tw / 2)) < tw) p.d - ry else null
                2 -> if (abs(ry - tw / 2) < tw) p.w - rx else null
                else -> if (abs(rx - tw / 2) < tw) ry else null
            }
            if (u == null) continue
            val hh = if (e.vh > 0) e.vh else if (e.kind == Kind.DOOR) 2.01 else 1.2
            val z0 = zb[i] + (if (e.kind == Kind.WINDOW) e.sill else 0.0)
            val tl = Offset(px(u - e.w / 2), pz(z0 + hh))
            val sz = Size((e.w * sc).toFloat(), (hh * sc).toFloat())
            drawRect(if (e.kind == Kind.DOOR) Color(0xFFB98B5E) else Color(0xFFCFE6F5), tl, sz)
            drawRect(InkColor, tl, sz, style = Stroke(line))
            if (e.kind == Kind.WINDOW) {
                drawLine(InkColor, Offset(tl.x + sz.width / 2, tl.y), Offset(tl.x + sz.width / 2, tl.y + sz.height), line * 0.7f)
            }
        }
    }
    // Dach
    val ridgeAlongV = if (side % 2 == 0) p.ridgeX else !p.ridgeX
    val wallC = Color(0xFFEDE6D6)
    val roofC = Color(0xFFB5654A)
    val roofLine = Color(0xFF6E3A28)
    val slab = (0.28 * sc).toFloat()
    fun slabLine(u0: Double, z0: Double, u1: Double, z1: Double) {
        drawLine(roofC, Offset(px(u0), pz(wallTop + z0 + 0.14)), Offset(px(u1), pz(wallTop + z1 + 0.14)), slab)
        drawLine(roofLine, Offset(px(u0), pz(wallTop + z0 + 0.28)), Offset(px(u1), pz(wallTop + z1 + 0.28)), line)
    }
    if (n > base) {
        if (p.roof == RoofType.GABLE && !ridgeAlongV) {
            val gp = Path()
            gp.moveTo(px(0.0), pz(wallTop)); gp.lineTo(px(lv / 2), pz(wallTop + lv / 2 * tn)); gp.lineTo(px(lv), pz(wallTop)); gp.close()
            drawPath(gp, wallC, style = Fill)
            drawPath(gp, Color(0xFF3F3F3F), style = Stroke(line * 1.4f))
            slabLine(-oL, -oL * tn, lv / 2, lv / 2 * tn)
            slabLine(lv + oR, -oR * tn, lv / 2, lv / 2 * tn)
        } else if (p.roof == RoofType.SHED && !ridgeAlongV) {
            val highRight = if (p.ridgeX) side == 1 else side == 2
            val wp = Path()
            if (highRight) { wp.moveTo(px(0.0), pz(wallTop)); wp.lineTo(px(lv), pz(wallTop + lv * tn)); wp.lineTo(px(lv), pz(wallTop)) }
            else { wp.moveTo(px(0.0), pz(wallTop)); wp.lineTo(px(0.0), pz(wallTop + lv * tn)); wp.lineTo(px(lv), pz(wallTop)) }
            wp.close()
            drawPath(wp, wallC, style = Fill)
            drawPath(wp, Color(0xFF3F3F3F), style = Stroke(line * 1.4f))
            if (highRight) slabLine(-oL, -oL * tn, lv + oR, (lv + oR) * tn)
            else slabLine(-oL, (lv + oL) * tn, lv + oR, -oR * tn)
        } else if (p.roof == RoofType.SHED && side == shedHighSide(p)) {
            val hh = (if (p.ridgeX) p.d else p.w) * tn
            drawRect(wallC, Offset(px(0.0), pz(wallTop + hh)), Size((lv * sc).toFloat(), (hh * sc).toFloat()))
            drawRect(Color(0xFF3F3F3F), Offset(px(0.0), pz(wallTop + hh)), Size((lv * sc).toFloat(), (hh * sc).toFloat()), style = Stroke(line * 1.4f))
            slabLine(-oL, hh, lv + oR, hh)
        }
    }
    val poly = if (n > base) roofPoly(p, side) else emptyList()
    val path = Path()
    poly.forEachIndexed { k, q ->
        val x = px(q.first)
        val y = pz(wallTop + q.second)
        if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    if (poly.isNotEmpty()) {
        path.close()
        drawPath(path, roofC, style = Fill)
        drawPath(path, roofLine, style = Stroke(line * 1.4f))
    }
    // Gelände
    drawLine(Color(0xFF4C7A3B), Offset(px(-oL - 0.5), pz(0.0)), Offset(px(lv + oR + 0.5), pz(0.0)), line * 2.2f)
    // Maße
    val eave = wallTop
    val ridge = wallTop + rise
    val cap = "Traufhöhe " + fmtM(eave) + " m · Firsthöhe " + fmtM(ridge) + " m · " + p.roof.label +
        (if (p.roof != RoofType.FLAT) " " + String.format(java.util.Locale.GERMANY, "%.0f", p.pitch) + "°" else "") +
        " · Überstand V " + fmtM(p.ovS) + " / R " + fmtM(p.ovE) + " / H " + fmtM(p.ovN) + " / L " + fmtM(p.ovW) + " m"
    nc.drawText(cap, size.width / 2, size.height - font * 1.0f, fitted(cap, font * 1.0f, size.width - font * 2, 0xFF333333.toInt(), false))
    val lenTxt = fmtM(lv) + " m"
    nc.drawText(lenTxt, px(lv / 2), pz(minZ) + font * 1.0f, paint(font * 0.95f, DimColor.toArgb(), true))
}

private fun DrawScope.drawRoofTop(p: Plan, font: Float) {
    val nc = drawContext.canvas.nativeCanvas
    val line = maxOf(1f, font * 0.12f)
    val m = font * 3f
    val spanX = p.w + p.ovW + p.ovE
    val spanY = p.d + p.ovN + p.ovS
    val sc = min((size.width - 2 * m) / spanX.toFloat(), (size.height - 2 * m - font * 2) / spanY.toFloat())
    val ox = (size.width - (spanX * sc).toFloat()) / 2 + (p.ovW * sc).toFloat()
    val oy = m + (p.ovN * sc).toFloat()
    fun px(x: Double) = ox + (x * sc).toFloat()
    fun py(y: Double) = oy + (y * sc).toFloat()
    val roofC = Color(0xFFD99A82)
    drawRect(roofC, Offset(px(-p.ovW), py(-p.ovN)), Size((spanX * sc).toFloat(), (spanY * sc).toFloat()))
    drawRect(Color(0xFF6E3A28), Offset(px(-p.ovW), py(-p.ovN)), Size((spanX * sc).toFloat(), (spanY * sc).toFloat()), style = Stroke(line * 1.4f))
    drawRect(
        Color(0xFF3F3F3F), Offset(px(0.0), py(0.0)), Size((p.w * sc).toFloat(), (p.d * sc).toFloat()),
        style = Stroke(line, pathEffect = PathEffect.dashPathEffect(floatArrayOf(font, font * 0.6f)))
    )
    val rc = Color(0xFF6E3A28)
    fun ln(x0: Double, y0: Double, x1: Double, y1: Double) = drawLine(rc, Offset(px(x0), py(y0)), Offset(px(x1), py(y1)), line * 1.6f)
    when (p.roof) {
        RoofType.FLAT -> nc.drawText("Flachdach", px(p.w / 2), py(p.d / 2), paint(font * 1.2f, 0xFF4A2A1C.toInt(), true))
        RoofType.GABLE -> if (p.ridgeX) ln(-p.ovW, p.d / 2, p.w + p.ovE, p.d / 2) else ln(p.w / 2, -p.ovN, p.w / 2, p.d + p.ovS)
        RoofType.HIP -> {
            if (p.w >= p.d) {
                val s = p.d / 2
                ln(s, s, p.w - s, s)
                ln(0.0, 0.0, s, s); ln(0.0, p.d, s, s); ln(p.w, 0.0, p.w - s, s); ln(p.w, p.d, p.w - s, s)
            } else {
                val s = p.w / 2
                ln(s, s, s, p.d - s)
                ln(0.0, 0.0, s, s); ln(p.w, 0.0, s, s); ln(0.0, p.d, s, p.d - s); ln(p.w, p.d, s, p.d - s)
            }
        }
        RoofType.TENT -> {
            ln(0.0, 0.0, p.w / 2, p.d / 2); ln(p.w, 0.0, p.w / 2, p.d / 2)
            ln(0.0, p.d, p.w / 2, p.d / 2); ln(p.w, p.d, p.w / 2, p.d / 2)
        }
        RoofType.SHED -> {
            val a: Offset
            val b: Offset
            if (p.ridgeX) { a = Offset(px(p.w / 2), py(p.d * 0.2)); b = Offset(px(p.w / 2), py(p.d * 0.8)) }
            else { a = Offset(px(p.w * 0.2), py(p.d / 2)); b = Offset(px(p.w * 0.8), py(p.d / 2)) }
            drawArrow(a, b, rc, line * 1.4f, font)
            nc.drawText("Gefälle", (a.x + b.x) / 2, (a.y + b.y) / 2 - font * 0.6f, paint(font, 0xFF4A2A1C.toInt(), true))
        }
    }
    val tw = fmtM(spanX) + " × " + fmtM(spanY) + " m mit Dachüberstand"
    nc.drawText(tw, size.width / 2, size.height - font * 1.0f, fitted(tw, font, size.width - font * 2, 0xFF333333.toInt(), false))
}
