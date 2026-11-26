package de.autoknigge.planung

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID

enum class Kind(val label: String) { ROOM("Raum"), WALL("Wand"), DOOR("Tür"), WINDOW("Fenster"), FURN("Möbel") }

/** Ein Planelement: Mittelpunkt (x, y) in Metern, Größe w × h in Metern, Drehung in Grad (im Uhrzeigersinn). */
data class El(
    val id: String = UUID.randomUUID().toString(),
    val kind: Kind,
    val name: String = "",
    val x: Double,
    val y: Double,
    val w: Double,
    val h: Double,
    val rot: Double = 0.0,
    val color: Int = 0
)

data class Plan(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val els: List<El> = emptyList(),
    val updated: Long = System.currentTimeMillis()
) {
    val roomArea: Double get() = els.filter { it.kind == Kind.ROOM }.sumOf { it.w * it.h }
}

data class FurnType(val name: String, val w: Double, val h: Double)

val FURNITURE = listOf(
    FurnType("Doppelbett", 1.80, 2.00),
    FurnType("Bett", 0.90, 2.00),
    FurnType("Sofa", 2.00, 0.90),
    FurnType("Sessel", 0.85, 0.85),
    FurnType("Esstisch", 1.60, 0.90),
    FurnType("Stuhl", 0.45, 0.45),
    FurnType("Schreibtisch", 1.40, 0.70),
    FurnType("Kleiderschrank", 2.00, 0.60),
    FurnType("Regal", 0.80, 0.30),
    FurnType("Kommode", 1.00, 0.45),
    FurnType("Küchenzeile", 2.40, 0.60),
    FurnType("Herd", 0.60, 0.60),
    FurnType("Spüle", 0.80, 0.60),
    FurnType("Kühlschrank", 0.60, 0.65),
    FurnType("Waschmaschine", 0.60, 0.60),
    FurnType("Badewanne", 1.70, 0.75),
    FurnType("Dusche", 0.90, 0.90),
    FurnType("WC", 0.40, 0.70),
    FurnType("Waschbecken", 0.60, 0.50),
    FurnType("Treppe", 1.00, 3.00),
    FurnType("Kamin", 0.80, 0.50),
    FurnType("Frei (Rechteck)", 1.00, 1.00)
)

fun fmtM(v: Double): String = String.format(Locale.GERMANY, "%.2f", v)
fun fmtArea(v: Double): String = String.format(Locale.GERMANY, "%.2f m²", v)

object Store {
    private fun file(ctx: Context) = File(ctx.filesDir, "plans.json")

    fun load(ctx: Context): MutableList<Plan> {
        val out = mutableListOf<Plan>()
        try {
            val f = file(ctx)
            if (!f.exists()) return out
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val els = mutableListOf<El>()
                val ea = o.optJSONArray("els") ?: JSONArray()
                for (j in 0 until ea.length()) {
                    val e = ea.getJSONObject(j)
                    val kind = try { Kind.valueOf(e.optString("kind", "ROOM")) } catch (ex: Exception) { Kind.ROOM }
                    els.add(
                        El(
                            id = e.optString("id", UUID.randomUUID().toString()),
                            kind = kind,
                            name = e.optString("name", ""),
                            x = e.optDouble("x", 0.0),
                            y = e.optDouble("y", 0.0),
                            w = e.optDouble("w", 1.0),
                            h = e.optDouble("h", 1.0),
                            rot = e.optDouble("rot", 0.0),
                            color = e.optInt("color", 0)
                        )
                    )
                }
                out.add(Plan(o.optString("id", UUID.randomUUID().toString()), o.optString("name", "Plan"), els, o.optLong("updated", 0L)))
            }
        } catch (e: Exception) {
        }
        return out
    }

    fun save(ctx: Context, plans: List<Plan>) {
        try {
            val arr = JSONArray()
            for (p in plans) {
                val o = JSONObject()
                o.put("id", p.id)
                o.put("name", p.name)
                o.put("updated", p.updated)
                val ea = JSONArray()
                for (e in p.els) {
                    val j = JSONObject()
                    j.put("id", e.id)
                    j.put("kind", e.kind.name)
                    j.put("name", e.name)
                    j.put("x", e.x)
                    j.put("y", e.y)
                    j.put("w", e.w)
                    j.put("h", e.h)
                    j.put("rot", e.rot)
                    j.put("color", e.color)
                    ea.put(j)
                }
                o.put("els", ea)
                arr.put(o)
            }
            val tmp = File(ctx.filesDir, "plans.json.tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(file(ctx))) {
                file(ctx).writeText(arr.toString())
                tmp.delete()
            }
        } catch (e: Exception) {
        }
    }
}
