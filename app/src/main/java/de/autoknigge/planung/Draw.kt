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
    val xsRaw = mutableListOf(0.0, p.w)
    val ysRaw = mutableListOf(0.0, p.d)
    for (r in f.rooms) {
        if (!eq(r.x0, p.ix0)) xsRaw.add(r.x0)
        if (!eq(r.x1, p.ix1)) xsRaw.add(r.x1)
        if (!eq(r.y0, p.iy0)) ysRaw.add(r.y0)
        if (!eq(r.y1, p.iy1)) ysRaw.add(r.y1)
    }
    val xs = distinct(xsRaw)
    val ys = distinct(ysRaw)
    val ext = Color(0x661F5FA8)
    val o1 = 0.8
    val o2 = 1.5
    // oben
    for (x in xs) drawLine(ext, Offset(t.x(x), t.y(-0.1)), Offset(t.x(x), t.y(-o2 - 0.15)), line * 0.7f)
    for (i in 0 until xs.size - 1) {
        dimLine(Offset(t.x(xs[i]), t.y(-o1)), Offset(t.x(xs[i + 1]), t.y(-o1)), fmtM(xs[i + 1] - xs[i]), font, line)
    }
    dimLine(Offset(t.x(0.0), t.y(-o2)), Offset(t.x(p.w), t.y(-o2)), fmtM(p.w), font, line)
    // links
    for (y in ys) drawLine(ext, Offset(t.x(-0.1), t.y(y)), Offset(t.x(-o2 - 0.15), t.y(y)), line * 0.7f)
    for (i in 0 until ys.size - 1) {
        dimLine(Offset(t.x(-o1), t.y(ys[i + 1])), Offset(t.x(-o1), t.y(ys[i])), fmtM(ys[i + 1] - ys[i]), font, line)
    }
    dimLine(Offset(t.x(-o2), t.y(p.d)), Offset(t.x(-o2), t.y(0.0)), fmtM(p.d), font, line)
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
    outerDims: Boolean
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
    for (r in f.rooms) {
        wrect(t, r.x0, r.y0, r.x1 - r.x0, r.y1 - r.y0, ROOM_COLORS[r.color.coerceIn(0, ROOM_COLORS.size - 1)])
    }
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
    // Außenwände
    val ot = p.outerT
    wrect(t, 0.0, 0.0, p.w, ot, WallColor)
    wrect(t, 0.0, p.d - ot, p.w, ot, WallColor)
    wrect(t, 0.0, 0.0, ot, p.d, WallColor)
    wrect(t, p.w - ot, 0.0, ot, p.d, WallColor)
    // Innenwände
    for (s in wallSegs(p, f).drop(4)) {
        val h = s.t / 2
        if (s.horizontal) wrect(t, s.x0 - h, s.y0 - h, s.x1 - s.x0 + s.t, s.t, WallColor)
        else wrect(t, s.x0 - h, s.y0 - h, s.t, s.y1 - s.y0 + s.t, WallColor)
    }
    // Auswahl Raum
    val selRoom = f.rooms.firstOrNull { it.id == selectedId }
    if (selRoom != null) {
        drawRect(
            SelectColor, Offset(t.x(selRoom.x0), t.y(selRoom.y0)),
            Size(t.s(selRoom.x1 - selRoom.x0), t.s(selRoom.y1 - selRoom.y0)),
            style = Stroke(line * 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(font, font * 0.6f)))
        )
    }
    if (selectedId == "OUTER") {
        drawRect(
            SelectColor, Offset(t.x(0.0) - font * 0.3f, t.y(0.0) - font * 0.3f),
            Size(t.s(p.w) + font * 0.6f, t.s(p.d) + font * 0.6f),
            style = Stroke(line * 1.8f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(font, font * 0.6f)))
        )
        for (c in listOf(Offset(t.x(p.w - ot / 2), t.y(p.d / 2)), Offset(t.x(p.w / 2), t.y(p.d - ot / 2)))) {
            drawCircle(SelectColor, font * 0.85f, c)
            drawCircle(WallColor, font * 0.85f, c, style = Stroke(line))
        }
    }

    // Raumbeschriftung
    for (r in f.rooms) {
        val wp = t.s(r.x1 - r.x0)
        val hp = t.s(r.y1 - r.y0)
        val cx = t.x((r.x0 + r.x1) / 2)
        val cy = t.y((r.y0 + r.y1) / 2)
        val net = p.net(r)
        val title = if (r.name.isBlank()) "Raum" else r.name
        if (wp > font * 3 && hp > font * 2.5f) {
            nc.drawText(title, cx, cy - font * 0.1f, fitted(title, font * 1.1f, wp * 0.85f, InkColor.toArgb(), true))
            val a = fmtArea(net.first * net.second)
            nc.drawText(a, cx, cy + font * 1.2f, fitted(a, font, wp * 0.85f, 0xFF555555.toInt(), false))
        }
        if ((roomDims || r.id == selectedId) && wp > font * 4 && hp > font * 4) {
            val dp = fitted(fmtM(net.first), font * 0.95f, wp * 0.5f, DimColor.toArgb(), true)
            nc.drawText(fmtM(net.first), cx, t.y(r.y0) + (if (eq(r.y0, p.iy0)) 0f else t.s(p.innerT / 2)) + font * 1.3f, dp)
            nc.save()
            nc.translate(t.x(r.x0) + (if (eq(r.x0, p.ix0)) 0f else t.s(p.innerT / 2)) + font * 1.0f, cy)
            nc.rotate(-90f)
            nc.drawText(fmtM(net.second), 0f, 0f, fitted(fmtM(net.second), font * 0.95f, hp * 0.5f, DimColor.toArgb(), true))
            nc.restore()
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
                drawCircle(SelectColor, font * 0.85f, Offset(wp / 2, hp / 2))
                drawCircle(Color(0xFF3F3F3F), font * 0.85f, Offset(wp / 2, hp / 2), style = Stroke(line))
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

/** Dachumriss in Metern: u von links nach rechts, z über Oberkante der Wände. */
private fun roofPoly(p: Plan, side: Int): List<Pair<Double, Double>> {
    val lv = if (side % 2 == 0) p.w else p.d
    val ld = if (side % 2 == 0) p.d else p.w
    val o = p.overhang
    val tn = tan(Math.toRadians(p.pitch))
    val e = o * tn
    val ridgeAlong = if (side % 2 == 0) p.ridgeX else !p.ridgeX
    return when (p.roof) {
        RoofType.FLAT -> listOf(-o to 0.0, lv + o to 0.0, lv + o to 0.3, -o to 0.3)
        RoofType.GABLE -> {
            if (ridgeAlong) {
                val rise = ld / 2 * tn
                listOf(-o to -e, lv + o to -e, lv + o to rise, -o to rise)
            } else {
                listOf(-o to -e, lv / 2 to lv / 2 * tn, lv + o to -e)
            }
        }
        RoofType.HIP -> {
            val rise = min(p.w, p.d) / 2 * tn
            if (lv > ld + 0.01) listOf(-o to -e, lv + o to -e, lv - ld / 2 to rise, ld / 2 to rise)
            else listOf(-o to -e, lv / 2 to rise, lv + o to -e)
        }
        RoofType.TENT -> listOf(-o to -e, lv / 2 to min(p.w, p.d) / 2 * tn, lv + o to -e)
        RoofType.SHED -> {
            val rise = (if (p.ridgeX) p.d else p.w) * tn
            if (ridgeAlong) listOf(-o to -e, lv + o to -e, lv + o to rise, -o to rise)
            else {
                val highRight = if (p.ridgeX) side == 1 else side == 2
                if (highRight) listOf(-o to -e, lv + o to rise, lv + o to -e)
                else listOf(-o to rise, lv + o to -e, -o to -e)
            }
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
    val rise = roofPoly(p, side).maxOf { it.second }
    val maxZ = wallTop + max(rise, 0.3)
    val o = p.overhang
    val mLeft = font * 2.5f
    val mRight = font * 9f
    val mTop = font * 2f
    val mBottom = font * 3f
    val spanX = lv + 2 * o + 1.0
    val spanZ = maxZ - minZ
    val sc = min((size.width - mLeft - mRight) / spanX.toFloat(), (size.height - mTop - mBottom) / spanZ.toFloat())
    val ox = mLeft + (o + 0.5).toFloat() * sc
    val oy = mTop + (maxZ * sc).toFloat()
    fun px(u: Double) = ox + (u * sc).toFloat()
    fun pz(zz: Double) = oy - (zz * sc).toFloat()

    // Erde
    drawRect(Color(0x33A27B4B), Offset(px(-o - 0.5), pz(0.0)), Size(((lv + 2 * o + 1.0) * sc).toFloat(), (-minZ * sc).toFloat()))
    // Geschosse
    for (i in 0 until n) {
        val f = p.floors[i]
        val col = if (f.cellar) Color(0xFFD5CCBA) else Color(0xFFEDE6D6)
        drawRect(col, Offset(px(0.0), pz(zb[i] + f.height + SLAB)), Size((lv * sc).toFloat(), ((f.height + SLAB) * sc).toFloat()))
        drawLine(Color(0xFF8A8A8A), Offset(px(0.0), pz(zb[i] + f.height)), Offset(px(lv), pz(zb[i] + f.height)), line)
        drawRect(Color(0xFF3F3F3F), Offset(px(0.0), pz(zb[i] + f.height + SLAB)), Size((lv * sc).toFloat(), (SLAB * sc).toFloat()), style = Stroke(line))
        // Beschriftung rechts
        val label = f.name + " · " + fmtM(f.height) + " m"
        nc.drawText(label, px(lv + o) + font * 0.8f, pz(zb[i] + f.height / 2) + font * 0.3f, paint(font * 0.9f, 0xFF333333.toInt(), false, Paint.Align.LEFT))
    }
    val houseBottom = if (base > 0) zb[0] else 0.0
    drawRect(Color(0xFF3F3F3F), Offset(px(0.0), pz(wallTop)), Size((lv * sc).toFloat(), ((wallTop - houseBottom) * sc).toFloat()), style = Stroke(line * 1.4f))
    // Öffnungen
    val tw = p.outerT
    for (i in 0 until n) {
        for (e in p.floors[i].els) {
            if (e.kind != Kind.DOOR && e.kind != Kind.WINDOW) continue
            val u: Double? = when (side) {
                0 -> if (abs(e.y - (p.d - tw / 2)) < tw) e.x else null
                1 -> if (abs(e.x - (p.w - tw / 2)) < tw) p.d - e.y else null
                2 -> if (abs(e.y - tw / 2) < tw) p.w - e.x else null
                else -> if (abs(e.x - tw / 2) < tw) e.y else null
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
    val poly = roofPoly(p, side)
    val path = Path()
    poly.forEachIndexed { k, q ->
        val x = px(q.first)
        val y = pz(wallTop + q.second)
        if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    drawPath(path, Color(0xFFB5654A), style = Fill)
    drawPath(path, Color(0xFF6E3A28), style = Stroke(line * 1.4f))
    // Gelände
    drawLine(Color(0xFF4C7A3B), Offset(px(-o - 0.5), pz(0.0)), Offset(px(lv + o + 0.5), pz(0.0)), line * 2.2f)
    // Maße
    val eave = wallTop
    val ridge = wallTop + rise
    val cap = "Traufhöhe " + fmtM(eave) + " m · Firsthöhe " + fmtM(ridge) + " m · " + p.roof.label +
        (if (p.roof != RoofType.FLAT) " " + String.format(java.util.Locale.GERMANY, "%.0f", p.pitch) + "°" else "") +
        " · Überstand " + fmtM(p.overhang) + " m"
    nc.drawText(cap, size.width / 2, size.height - font * 1.0f, fitted(cap, font * 1.0f, size.width - font * 2, 0xFF333333.toInt(), false))
    val lenTxt = fmtM(lv) + " m"
    nc.drawText(lenTxt, px(lv / 2), pz(minZ) + font * 1.0f, paint(font * 0.95f, DimColor.toArgb(), true))
}

private fun DrawScope.drawRoofTop(p: Plan, font: Float) {
    val nc = drawContext.canvas.nativeCanvas
    val line = maxOf(1f, font * 0.12f)
    val o = p.overhang
    val m = font * 3f
    val spanX = p.w + 2 * o
    val spanY = p.d + 2 * o
    val sc = min((size.width - 2 * m) / spanX.toFloat(), (size.height - 2 * m - font * 2) / spanY.toFloat())
    val ox = (size.width - (spanX * sc).toFloat()) / 2 + (o * sc).toFloat()
    val oy = m + (o * sc).toFloat()
    fun px(x: Double) = ox + (x * sc).toFloat()
    fun py(y: Double) = oy + (y * sc).toFloat()
    val roofC = Color(0xFFD99A82)
    drawRect(roofC, Offset(px(-o), py(-o)), Size((spanX * sc).toFloat(), (spanY * sc).toFloat()))
    drawRect(Color(0xFF6E3A28), Offset(px(-o), py(-o)), Size((spanX * sc).toFloat(), (spanY * sc).toFloat()), style = Stroke(line * 1.4f))
    drawRect(
        Color(0xFF3F3F3F), Offset(px(0.0), py(0.0)), Size((p.w * sc).toFloat(), (p.d * sc).toFloat()),
        style = Stroke(line, pathEffect = PathEffect.dashPathEffect(floatArrayOf(font, font * 0.6f)))
    )
    val rc = Color(0xFF6E3A28)
    fun ln(x0: Double, y0: Double, x1: Double, y1: Double) = drawLine(rc, Offset(px(x0), py(y0)), Offset(px(x1), py(y1)), line * 1.6f)
    when (p.roof) {
        RoofType.FLAT -> nc.drawText("Flachdach", px(p.w / 2), py(p.d / 2), paint(font * 1.2f, 0xFF4A2A1C.toInt(), true))
        RoofType.GABLE -> if (p.ridgeX) ln(-o, p.d / 2, p.w + o, p.d / 2) else ln(p.w / 2, -o, p.w / 2, p.d + o)
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
    val tw = fmtM(p.w + 2 * o) + " × " + fmtM(p.d + 2 * o) + " m (mit Überstand " + fmtM(o) + " m)"
    nc.drawText(tw, size.width / 2, size.height - font * 1.0f, fitted(tw, font, size.width - font * 2, 0xFF333333.toInt(), false))
}
