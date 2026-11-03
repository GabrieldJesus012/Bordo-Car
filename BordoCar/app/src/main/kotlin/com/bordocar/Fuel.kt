package com.bordocar

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Fill-ups: litres, what you paid, the fuel and whether the tank was filled, with the odometer (the km the app counts) at that
 * moment. Consumption is worked out from one full tank to the next: the km driven between them over the litres put in since.
 */
object Fuel {
    class Fill(val at: Long, val litres: Double, val paid: Double, val kind: String, val full: Boolean, val odo: Double) {
        fun json(): JSONObject = JSONObject().put("t", at).put("l", litres).put("v", paid).put("k", kind).put("f", full).put("o", odo)
    }

    fun all(ctx: Context): List<Fill> = try {
        val a = JSONArray(Prefs(ctx).s("fuel", "[]"))
        (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { o -> Fill(o.getLong("t"), o.getDouble("l"), o.getDouble("v"), o.optString("k", "Gasolina"), o.optBoolean("f", true), o.optDouble("o", 0.0)) } }
            .sortedBy { it.at }
    } catch (e: Exception) { emptyList() }

    private fun save(ctx: Context, list: List<Fill>) { val a = JSONArray(); list.forEach { a.put(it.json()) }; Prefs(ctx).put("fuel", a.toString()) }
    fun add(ctx: Context, f: Fill) = save(ctx, all(ctx) + f)
    fun remove(ctx: Context, f: Fill) = save(ctx, all(ctx).filterNot { it.at == f.at })

    /** km per litre for each fill-up (by its time), where there's a full tank before it and it's a full tank too. */
    fun consumption(list: List<Fill>): Map<Long, Double> {
        val out = HashMap<Long, Double>()
        var lastFull: Fill? = null
        var litres = 0.0
        for (f in list) {
            if (lastFull != null) litres += f.litres
            if (f.full) {
                val lf = lastFull
                if (lf != null && litres > 0.5) { val km = (f.odo - lf.odo) / 1000.0; if (km > 5) out[f.at] = km / litres }
                lastFull = f; litres = 0.0
            }
        }
        return out
    }

    /** This month: money spent, the latest km per litre, and cost per km (spent over km driven this month). */
    fun month(ctx: Context): Triple<Double, Double?, Double?> {
        val list = all(ctx)
        val c = java.util.Calendar.getInstance().apply { set(java.util.Calendar.DAY_OF_MONTH, 1); set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0) }
        val spent = list.filter { it.at >= c.timeInMillis }.sumOf { it.paid }
        val kml = consumption(list).entries.maxByOrNull { it.key }?.value
        val km = Drive.month().first / 1000.0
        // cost per km: from the consumption and the latest price when known, otherwise this month's spend over its km
        val last = list.lastOrNull()
        val perKm = if (kml != null && last != null && last.litres > 0) (last.paid / last.litres) / kml else if (km > 20 && spent > 0) spent / km else null
        return Triple(spent, kml, perKm)
    }
}
