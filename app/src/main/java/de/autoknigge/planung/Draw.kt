package de.autoknigge.planung

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

val ROOM_COLORS = listOf(
    Color(0xFFDCEBD3), Color(0xFFF6E2BD), Color(0xFFD3E3F2),
    Color(0xFFF2D7DA), Color(0xFFE4DAF0), Color(0xFFECEAC6)
)
val PaperColor = Color(0xFFFBF8EF)
private val WallColor = Color(0xFF3F3F3F)
private val InkColor = Color(0xFF2B2B2B)
val SelectColor = Color(0xFFE3B341)

private fun paint(size: Float, color: Int, bold: Boolean = false): Paint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        textAlign = Paint.Align.CENTER
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

/** Text so verkleinern, dass er in die Breite [maxW] passt. */
private fun fitted(text: String, base: Float, maxW: Float, color: Int, bold: Boolean): Paint {
    val p = paint(base, color, bold)
    val w = p.measureText(text)
    if (w > maxW && maxW > 0f) p.textSize = base * maxW / w
    return p
}

/**
 * Zeichnet den Plan. [scale] sind Pixel je Meter, [origin] die Bildschirmposition des Weltpunkts (0, 0),
 * [font] die Grundschriftgröße in Pixeln.
 */
fun DrawScope.drawPlan(
    els: List<El>,
    scale: Float,
    origin: Offset,
    font: Float,
    selectedId: String?,
    grid: Boolean,
    dims: Boolean
) {
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
    for (e in els.sortedBy { rank(it.kind) }) {
        val c = Offset(origin.x + (e.x * scale).toFloat(), origin.y + (e.y * scale).toFloat())
        val wp = (e.w * scale).toFloat()
        val hp = (e.h * scale).toFloat()
        val tl = Offset(-wp / 2, -hp / 2)
        val sz = Size(wp, hp)
        withTransform({
            translate(c.x, c.y)
            rotate(e.rot.toFloat(), Offset.Zero)
        }) {
            when (e.kind) {
                Kind.ROOM -> {
                    drawRect(ROOM_COLORS[e.color.coerceIn(0, ROOM_COLORS.size - 1)], tl, sz)
                    drawRect(Color(0xFF8A8A8A), tl, sz, style = Stroke(line))
                }
                Kind.WALL -> drawRect(WallColor, tl, sz)
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
            val isSel = e.id == selectedId
            if (isSel) {
                val m = font * 0.3f
                drawRect(
                    SelectColor, Offset(tl.x - m, tl.y - m), Size(sz.width + 2 * m, sz.height + 2 * m),
                    style = Stroke(line * 1.6f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(font, font * 0.6f)))
                )
            }
            val showDims = isSel || (dims && e.kind == Kind.ROOM)
            if (showDims) {
                val flip = (((e.rot % 360.0) + 360.0) % 360.0).let { it > 90.0 && it < 270.0 }
                val p = paint(font * 0.95f, 0xFF1F5FA8.toInt(), true)
                nc.save()
                if (flip) nc.rotate(180f)
                nc.drawText(fmtM(e.w) + " m", 0f, -hp / 2 - font * 0.7f, p)
                if (e.kind == Kind.ROOM || e.kind == Kind.FURN) {
                    nc.save()
                    nc.translate(-wp / 2 - font * 0.7f, 0f)
                    nc.rotate(-90f)
                    nc.drawText(fmtM(e.h) + " m", 0f, 0f, p)
                    nc.restore()
                }
                nc.restore()
            }
            if (isSel) {
                drawCircle(SelectColor, font * 0.85f, Offset(wp / 2, hp / 2))
                drawCircle(Color(0xFF3F3F3F), font * 0.85f, Offset(wp / 2, hp / 2), style = Stroke(line))
            }
        }
        // Beschriftung aufrecht
        when (e.kind) {
            Kind.ROOM -> {
                val title = if (e.name.isBlank()) "Raum" else e.name
                val avail = wp * 0.9f
                if (wp > font * 3 && hp > font * 2.5f) {
                    nc.drawText(title, c.x, c.y - font * 0.1f, fitted(title, font * 1.1f, avail, InkColor.toArgb(), true))
                    val a = fmtArea(e.w * e.h)
                    nc.drawText(a, c.x, c.y + font * 1.2f, fitted(a, font, avail, 0xFF555555.toInt(), false))
                }
            }
            Kind.FURN -> {
                if (e.name.isNotBlank() && min(wp, hp) > font * 1.2f) {
                    nc.drawText(e.name, c.x, c.y + font * 0.3f, fitted(e.name, font * 0.85f, wp * 0.92f, 0xFF5B4B33.toInt(), false))
                }
            }
            else -> {}
        }
    }
}
