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
    /** Plan als Bild: passt auf [maxPx] Pixel an der längeren Seite, mit Rand. Null bei leerem Plan. */
    fun render(plan: Plan, maxPx: Int = 2400): Bitmap? {
        val b = boundsOf(plan.els) ?: return null
        val margin = 1.0
        val wm = (b[2] - b[0]) + 2 * margin
        val hm = (b[3] - b[1]) + 2 * margin
        val scale = min(maxPx / wm, maxPx / hm).toFloat().coerceAtMost(400f)
        val wp = max(200, (wm * scale).toInt())
        val hp = max(200, (hm * scale).toInt())
        val image = ImageBitmap(wp, hp)
        val canvas = Canvas(image)
        val origin = Offset(((margin - b[0]) * scale).toFloat(), ((margin - b[1]) * scale).toFloat())
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, canvas, Size(wp.toFloat(), hp.toFloat())) {
            drawRect(androidx.compose.ui.graphics.Color.White)
            drawPlan(plan.els, scale, origin, (scale * 0.2f).coerceIn(12f, 40f), null, false, true)
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

    fun sharePng(ctx: Context, plan: Plan): Boolean {
        val bmp = render(plan) ?: return false
        val dir = File(ctx.cacheDir, "export").apply { mkdirs() }
        val f = File(dir, safeName(plan.name) + ".png")
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        share(ctx, f, "image/png", plan.name)
        return true
    }

    fun sharePdf(ctx: Context, plan: Plan): Boolean {
        val bmp = render(plan, 3000) ?: return false
        val landscape = bmp.width >= bmp.height
        val pw = if (landscape) 842 else 595
        val ph = if (landscape) 595 else 842
        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, 1).create())
        val c: AndroidCanvas = page.canvas
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 18f; isFakeBoldText = true; color = 0xFF222222.toInt() }
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10f; color = 0xFF666666.toInt() }
        c.drawText(plan.name, 30f, 36f, titlePaint)
        c.drawText("Raumfläche gesamt: " + fmtArea(plan.roomArea), 30f, 52f, small)
        val areaTop = 64f
        val areaW = pw - 60f
        val areaH = ph - areaTop - 34f
        val f = min(areaW / bmp.width, areaH / bmp.height)
        val dw = bmp.width * f
        val dh = bmp.height * f
        val left = 30f + (areaW - dw) / 2
        val dest = android.graphics.RectF(left, areaTop, left + dw, areaTop + dh)
        c.drawBitmap(bmp, null, dest, Paint(Paint.FILTER_BITMAP_FLAG))
        c.drawText("Erstellt mit Planung · autoknigge.de · Maße ohne Gewähr, keine Bauzeichnung", 30f, ph - 16f, small)
        doc.finishPage(page)
        val dir = File(ctx.cacheDir, "export").apply { mkdirs() }
        val file = File(dir, safeName(plan.name) + ".pdf")
        FileOutputStream(file).use { doc.writeTo(it) }
        doc.close()
        share(ctx, file, "application/pdf", plan.name)
        return true
    }
}
