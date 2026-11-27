package de.autoknigge.planung

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

enum class Kind(val label: String) { DOOR("Tür"), WINDOW("Fenster"), FURN("Möbel"), STAIR("Treppe") }

enum class StairType(val label: String) {
    STRAIGHT("Gerade"), HALF("Halbgewendelt"), QUARTER("Viertelgewendelt"), SPIRAL("Ganz gewendelt")
}

enum class RoofType(val label: String) {
    FLAT("Flachdach"), SHED("Pultdach"), GABLE("Satteldach"), HIP("Walmdach"), TENT("Zeltdach")
}

/** Wählbare Wandstärken in Zentimetern. */
val WALL_CM = listOf(15, 20, 25, 30, 40, 45, 50)

/** Dicke der Geschossdecke in Metern (nur für Ansicht und Treppensteigung). */
const val SLAB = 0.25

/** Ein Planelement (Tür, Fenster, Möbel, Treppe): Mittelpunkt (x, y) in Metern, Größe w × h, Drehung in Grad (im Uhrzeigersinn). */
data class El(
    val id: String = UUID.randomUUID().toString(),
    val kind: Kind,
    val name: String = "",
    val x: Double,
    val y: Double,
    val w: Double,
    val h: Double,
    val rot: Double = 0.0,
    val color: Int = 0,
    /** Höhe in Metern (Tür, Fenster). */
    val vh: Double = 0.0,
    /** Brüstungshöhe in Metern (Fenster). */
    val sill: Double = 0.0,
    /** Untertyp: bei Treppen die Ordnungszahl von [StairType]. */
    val sub: Int = 0
)

data class Pt(val x: Double, val y: Double)

/** Raum als Vieleck; die Ecken liegen auf den Wandachsen (Meter). Räume teilen sich Ecken und Wände. */
data class Room(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Raum",
    val pts: List<Pt>,
    val color: Int = 0
)

fun rectPts(x0: Double, y0: Double, x1: Double, y1: Double): List<Pt> =
    listOf(Pt(x0, y0), Pt(x1, y0), Pt(x1, y1), Pt(x0, y1))

data class Floor(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Erdgeschoss",
    /** Lichte Raumhöhe in Metern. */
    val height: Double = 2.5,
    val cellar: Boolean = false,
    val rooms: List<Room> = emptyList(),
    val els: List<El> = emptyList()
)

fun defaultFloors(w: Double, d: Double, t: Double): List<Floor> =
    listOf(Floor(rooms = listOf(Room(pts = rectPts(t / 2, t / 2, w - t / 2, d - t / 2)))))

data class Plan(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val outerT: Double = 0.30,
    val innerT: Double = 0.15,
    val roof: RoofType = RoofType.GABLE,
    val pitch: Double = 35.0,
    /** Dachüberstand je Seite (Nord = oben im Plan, Süd = vorne). */
    val ovN: Double = 0.5,
    val ovE: Double = 0.5,
    val ovS: Double = 0.5,
    val ovW: Double = 0.5,
    /** First parallel zur Breite (x-Richtung) bei Sattel- und Pultdach. */
    val ridgeX: Boolean = true,
    val floors: List<Floor> = defaultFloors(10.0, 8.0, outerT),
    val setup: Boolean = false,
    val updated: Long = System.currentTimeMillis()
) {
    /** Umgebendes Rechteck der Wandachsen aller Geschosse: minX, minY, maxX, maxY. */
    private val box: DoubleArray
        get() {
            var x0 = Double.MAX_VALUE
            var y0 = Double.MAX_VALUE
            var x1 = -Double.MAX_VALUE
            var y1 = -Double.MAX_VALUE
            for (f in floors) for (r in f.rooms) for (q in r.pts) {
                x0 = min(x0, q.x); x1 = max(x1, q.x); y0 = min(y0, q.y); y1 = max(y1, q.y)
            }
            return if (x0 > x1) doubleArrayOf(outerT / 2, outerT / 2, 10.0 - outerT / 2, 8.0 - outerT / 2) else doubleArrayOf(x0, y0, x1, y1)
        }

    /** Linke und obere Außenkante des Hauses (Außenfläche der Wand). */
    val bx: Double get() = box[0] - outerT / 2
    val by: Double get() = box[1] - outerT / 2

    /** Außenmaße des Hauses. */
    val w: Double get() = box[2] - box[0] + outerT
    val d: Double get() = box[3] - box[1] + outerT

    /** Wohnfläche (lichte Raummaße) aller Geschosse. */
    val roomArea: Double get() = floors.sumOf { areaOf(it) }

    fun areaOf(f: Floor): Double = f.rooms.sumOf { roomNet(this, f, it).area }
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
    FurnType("Kamin", 0.80, 0.50),
    FurnType("Frei (Rechteck)", 1.00, 1.00)
)

fun fmtM(v: Double): String = String.format(Locale.GERMANY, "%.2f", v)
fun fmtArea(v: Double): String = String.format(Locale.GERMANY, "%.2f m²", v)

object Store {
    private fun file(ctx: Context) = File(ctx.filesDir, "plans.json")

    private fun elFrom(e: JSONObject, kind: Kind): El = El(
        id = e.optString("id", UUID.randomUUID().toString()),
        kind = kind,
        name = e.optString("name", ""),
        x = e.optDouble("x", 0.0),
        y = e.optDouble("y", 0.0),
        w = e.optDouble("w", 1.0),
        h = e.optDouble("h", 1.0),
        rot = e.optDouble("rot", 0.0),
        color = e.optInt("color", 0),
        vh = e.optDouble("vh", 0.0),
        sill = e.optDouble("sill", 0.0),
        sub = e.optInt("sub", 0)
    )

    private inline fun <reified T : Enum<T>> enumOr(s: String, def: T): T =
        try { enumValueOf<T>(s) } catch (ex: Exception) { def }

    /** Alte Pläne (nur freie Elemente) in die neue Struktur mit Außenhülle überführen. */
    private fun legacy(o: JSONObject): Plan {
        val old = mutableListOf<El>()
        val ea = o.optJSONArray("els") ?: JSONArray()
        var x0 = Double.MAX_VALUE
        var y0 = Double.MAX_VALUE
        var x1 = -Double.MAX_VALUE
        var y1 = -Double.MAX_VALUE
        for (j in 0 until ea.length()) {
            val e = ea.getJSONObject(j)
            val ks = e.optString("kind", "ROOM")
            val ex = e.optDouble("x", 0.0)
            val ey = e.optDouble("y", 0.0)
            val hw = e.optDouble("w", 1.0) / 2
            val hh = e.optDouble("h", 1.0) / 2
            x0 = min(x0, ex - hw); x1 = max(x1, ex + hw)
            y0 = min(y0, ey - hh); y1 = max(y1, ey + hh)
            val kind = when (ks) {
                "DOOR" -> Kind.DOOR
                "WINDOW" -> Kind.WINDOW
                "FURN" -> Kind.FURN
                else -> null
            } ?: continue
            old.add(elFrom(e, kind))
        }
        val t = 0.30
        val hasAny = x0 <= x1
        val w = if (hasAny) max(4.0, x1 - x0 + 2 * t) else 10.0
        val d = if (hasAny) max(4.0, y1 - y0 + 2 * t) else 8.0
        val sx = if (hasAny) t - x0 else 0.0
        val sy = if (hasAny) t - y0 else 0.0
        val els = old.map { e ->
            var n = e.copy(x = e.x + sx, y = e.y + sy)
            if (n.kind == Kind.FURN && n.name == "Treppe") n = n.copy(kind = Kind.STAIR)
            n
        }
        val floor = Floor(rooms = listOf(Room(pts = rectPts(t / 2, t / 2, w - t / 2, d - t / 2))), els = els)
        return Plan(
            id = o.optString("id", UUID.randomUUID().toString()),
            name = o.optString("name", "Plan"),
            outerT = t, floors = listOf(floor), setup = true,
            updated = o.optLong("updated", 0L)
        )
    }

    fun load(ctx: Context): MutableList<Plan> {
        val f = file(ctx)
        if (!f.exists()) return mutableListOf()
        return try { parse(f.readText()) } catch (e: Exception) { mutableListOf() }
    }

    /** Pläne aus Text (interne Datei oder Sicherungsdatei) lesen. Ein einzelner Plan als Objekt ist auch erlaubt. */
    fun parse(text: String): MutableList<Plan> {
        val out = mutableListOf<Plan>()
        try {
            val t = text.trim()
            val arr = if (t.startsWith("[")) JSONArray(t) else JSONArray().put(JSONObject(t))
            for (i in 0 until arr.length()) {
                try {
                    val o = arr.getJSONObject(i)
                    if (!o.has("floors")) {
                        out.add(legacy(o))
                        continue
                    }
                    val floors = mutableListOf<Floor>()
                    val fa = o.getJSONArray("floors")
                    for (k in 0 until fa.length()) {
                        val fo = fa.getJSONObject(k)
                        val rooms = mutableListOf<Room>()
                        val ra = fo.optJSONArray("rooms") ?: JSONArray()
                        val oT = o.optDouble("outerT", 0.30)
                        val oW = o.optDouble("w", 10.0)
                        val oD = o.optDouble("d", 8.0)
                        for (j in 0 until ra.length()) {
                            val r = ra.getJSONObject(j)
                            val pa = r.optJSONArray("pts")
                            val pts: List<Pt> = if (pa != null) {
                                List(pa.length()) { k -> pa.getJSONArray(k).let { q -> Pt(q.getDouble(0), q.getDouble(1)) } }
                            } else {
                                // älteres Format: Rechteck an der Innenseite der Außenwände -> Wandachsen
                                fun cx(v: Double) = if (kotlin.math.abs(v - oT) < 0.003) oT / 2 else if (kotlin.math.abs(v - (oW - oT)) < 0.003) oW - oT / 2 else v
                                fun cy(v: Double) = if (kotlin.math.abs(v - oT) < 0.003) oT / 2 else if (kotlin.math.abs(v - (oD - oT)) < 0.003) oD - oT / 2 else v
                                rectPts(cx(r.optDouble("x0")), cy(r.optDouble("y0")), cx(r.optDouble("x1")), cy(r.optDouble("y1")))
                            }
                            rooms.add(
                                Room(
                                    id = r.optString("id", UUID.randomUUID().toString()),
                                    name = r.optString("name", "Raum"),
                                    pts = pts,
                                    color = r.optInt("color", 0)
                                )
                            )
                        }
                        val els = mutableListOf<El>()
                        val ea = fo.optJSONArray("els") ?: JSONArray()
                        for (j in 0 until ea.length()) {
                            val e = ea.getJSONObject(j)
                            els.add(elFrom(e, enumOr(e.optString("kind", "FURN"), Kind.FURN)))
                        }
                        floors.add(
                            Floor(
                                id = fo.optString("id", UUID.randomUUID().toString()),
                                name = fo.optString("name", "Geschoss"),
                                height = fo.optDouble("height", 2.5),
                                cellar = fo.optBoolean("cellar", false),
                                rooms = rooms,
                                els = els
                            )
                        )
                    }
                    if (floors.isEmpty()) continue
                    out.add(
                        Plan(
                            id = o.optString("id", UUID.randomUUID().toString()),
                            name = o.optString("name", "Plan"),
                            outerT = o.optDouble("outerT", 0.30),
                            innerT = o.optDouble("innerT", 0.15),
                            roof = enumOr(o.optString("roof", "GABLE"), RoofType.GABLE),
                            pitch = o.optDouble("pitch", 35.0),
                            ovN = o.optDouble("ovN", o.optDouble("overhang", 0.5)),
                            ovE = o.optDouble("ovE", o.optDouble("overhang", 0.5)),
                            ovS = o.optDouble("ovS", o.optDouble("overhang", 0.5)),
                            ovW = o.optDouble("ovW", o.optDouble("overhang", 0.5)),
                            ridgeX = o.optBoolean("ridgeX", true),
                            floors = floors,
                            setup = o.optBoolean("setup", true),
                            updated = o.optLong("updated", 0L)
                        )
                    )
                } catch (e: Exception) {
                }
            }
        } catch (e: Exception) {
        }
        return out
    }

    fun toJson(plans: List<Plan>): String = toArray(plans).toString()

    fun save(ctx: Context, plans: List<Plan>) {
        try {
            val arr = toArray(plans)
            val tmp = File(ctx.filesDir, "plans.json.tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(file(ctx))) {
                file(ctx).writeText(arr.toString())
                tmp.delete()
            }
        } catch (e: Exception) {
        }
    }

    private fun toArray(plans: List<Plan>): JSONArray {
        val arr = JSONArray()
        try {
            for (p in plans) {
                val o = JSONObject()
                o.put("id", p.id)
                o.put("name", p.name)
                o.put("updated", p.updated)
                o.put("outerT", p.outerT)
                o.put("innerT", p.innerT)
                o.put("roof", p.roof.name)
                o.put("pitch", p.pitch)
                o.put("ovN", p.ovN)
                o.put("ovE", p.ovE)
                o.put("ovS", p.ovS)
                o.put("ovW", p.ovW)
                o.put("ridgeX", p.ridgeX)
                o.put("setup", p.setup)
                val fa = JSONArray()
                for (f in p.floors) {
                    val fo = JSONObject()
                    fo.put("id", f.id)
                    fo.put("name", f.name)
                    fo.put("height", f.height)
                    fo.put("cellar", f.cellar)
                    val ra = JSONArray()
                    for (r in f.rooms) {
                        val j = JSONObject()
                        j.put("id", r.id)
                        j.put("name", r.name)
                        j.put("color", r.color)
                        val pa = JSONArray()
                        for (q in r.pts) pa.put(JSONArray().put(q.x).put(q.y))
                        j.put("pts", pa)
                        ra.put(j)
                    }
                    fo.put("rooms", ra)
                    val ea = JSONArray()
                    for (e in f.els) {
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
                        j.put("vh", e.vh)
                        j.put("sill", e.sill)
                        j.put("sub", e.sub)
                        ea.put(j)
                    }
                    fo.put("els", ea)
                    fa.put(fo)
                }
                o.put("floors", fa)
                arr.put(o)
            }
        } catch (e: Exception) {
        }
        return arr
    }
}
