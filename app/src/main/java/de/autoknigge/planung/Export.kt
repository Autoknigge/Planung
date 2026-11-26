package de.autoknigge.planung

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

object Export {
    /** Ein Geschoss als Bild, mit Außenmaßen. */
    fun renderFloor(plan: Plan, fi: Int, maxPx: Int = 2400): Bitmap {
        val x0 = -2.2
        val y0 = -2.2
        val wm = plan.w + 0.6 - x0
        val hm = plan.d + 0.6 - y0
        val scale = min(maxPx / wm, maxPx / hm).toFloat().coerceAtMost(400f)
        val wp = max(200, (wm * scale).toInt())
        val hp = max(200, (hm * scale).toInt())
        val image = ImageBitmap(wp, hp)
        val canvas = Canvas(image)
        val origin = Offset((-x0 * scale).toFloat(), (-y0 * scale).toFloat())
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, canvas, Size(wp.toFloat(), hp.toFloat())) {
            drawRect(androidx.compose.ui.graphics.Color.White)
            drawFloor(plan, fi, scale, origin, (scale * 0.2f).coerceIn(12f, 40f), null, false, true, true)
        }
        return image.asAndroidBitmap()
    }

    fun renderView(plan: Plan, side: Int, wPx: Int = 1800, hPx: Int = 1200): Bitmap {
        val image = ImageBitmap(wPx, hPx)
        val canvas = Canvas(image)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, canvas, Size(wPx.toFloat(), hPx.toFloat())) {
            drawRect(androidx.compose.ui.graphics.Color.White)
            drawView(plan, side, wPx / 55f)
        }
        return image.asAndroidBitmap()
    }

    private fun safeName(name: String): String = name.replace(Regex("[^A-Za-z0-9ÄÖÜäöüß _-]"), "").trim().ifEmpty { "Plan" }.replace(' ', '_')

    private fun share(ctx: Context, file: File, mime: String, title: String) {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, "Plan teilen").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Sicherungsdatei: enthält den ganzen Plan und lässt sich in der App wieder öffnen. */
    fun shareBackup(ctx: Context, plan: Plan) {
        val dir = File(ctx.cacheDir, "export").apply { mkdirs() }
        val f = File(dir, safeName(plan.name) + ".planung")
        f.writeText(Store.toJson(listOf(plan)))
        share(ctx, f, "application/octet-stream", plan.name)
    }

    /** Das gerade angezeigte Geschoss als PNG teilen. */
    fun sharePng(ctx: Context, plan: Plan, fi: Int): Boolean {
        val i = fi.coerceIn(0, plan.floors.lastIndex)
        val bmp = renderFloor(plan, i)
        val dir = File(ctx.cacheDir, "export").apply { mkdirs() }
        val f = File(dir, safeName(plan.name) + "_" + safeName(plan.floors[i].name) + ".png")
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        share(ctx, f, "image/png", plan.name)
        return true
    }

    private fun addPage(doc: PdfDocument, no: Int, plan: Plan, title: String, sub: String, draw: (AndroidCanvas, Float, Float, Float, Float) -> Unit, landscape: Boolean) {
        val pw = if (landscape) 842 else 595
        val ph = if (landscape) 595 else 842
        val page = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, no).create())
        val c: AndroidCanvas = page.canvas
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 18f; isFakeBoldText = true; color = 0xFF222222.toInt() }
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10f; color = 0xFF666666.toInt() }
        c.drawText(plan.name + " – " + title, 30f, 36f, titlePaint)
        c.drawText(sub, 30f, 52f, small)
        draw(c, 30f, 64f, pw - 60f, ph - 64f - 34f)
        c.drawText("Erstellt mit Planung · autoknigge.de · Maße ohne Gewähr, keine Bauzeichnung", 30f, ph - 16f, small)
        doc.finishPage(page)
    }

    private fun place(c: AndroidCanvas, bmp: Bitmap, l: Float, t: Float, w: Float, h: Float) {
        val f = min(w / bmp.width, h / bmp.height)
        val dw = bmp.width * f
        val dh = bmp.height * f
        val left = l + (w - dw) / 2
        c.drawBitmap(bmp, null, android.graphics.RectF(left, t, left + dw, t + dh), Paint(Paint.FILTER_BITMAP_FLAG))
    }

    /** Alle Geschosse und die Ansichten als PDF teilen. */
    fun sharePdf(ctx: Context, plan: Plan): Boolean {
        val doc = PdfDocument()
        var no = 1
        plan.floors.forEachIndexed { i, f ->
            val bmp = renderFloor(plan, i, 3000)
            val land = bmp.width >= bmp.height
            val sub = "Raumhöhe " + fmtM(f.height) + " m · Wohnfläche " + fmtArea(plan.areaOf(f)) +
                " · Außenmaße " + fmtM(plan.w) + " × " + fmtM(plan.d) + " m · Wände außen " + (plan.outerT * 100).toInt() + " cm, innen " + (plan.innerT * 100).toInt() + " cm"
            addPage(doc, no++, plan, f.name, sub, { c, l, t, w, h -> place(c, bmp, l, t, w, h) }, land)
        }
        val views = (0..3).map { renderView(plan, it, 1400, 1000) }
        addPage(doc, no++, plan, "Ansichten", "Vorne, rechts, hinten, links", { c, l, t, w, h ->
            val cw = w / 2 - 4f
            val ch = h / 2 - 4f
            for (k in 0..3) place(c, views[k], l + (k % 2) * (cw + 8f), t + (k / 2) * (ch + 8f), cw, ch)
        }, true)
        val roof = renderView(plan, 4, 1600, 1100)
        addPage(doc, no, plan, "Dach", plan.roof.label, { c, l, t, w, h -> place(c, roof, l, t, w, h) }, true)
        val dir = File(ctx.cacheDir, "export").apply { mkdirs() }
        val file = File(dir, safeName(plan.name) + ".pdf")
        FileOutputStream(file).use { doc.writeTo(it) }
        doc.close()
        share(ctx, file, "application/pdf", plan.name)
        return true
    }
}
