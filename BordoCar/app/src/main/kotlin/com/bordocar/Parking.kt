package com.bordocar

import android.content.Context
import org.json.JSONObject

/**
 * Where you left the car: noted once when the phone disconnects from Android Auto (the last GPS position of the drive; nothing
 * runs afterwards, so it costs no battery). Not noted near Casa or Trabalho, where the car isn't "parked somewhere".
 */
object Parking {
    class Spot(val pos: LL, val at: Long)

    fun get(ctx: Context): Spot? = try {
        val s = Prefs(ctx).s("parked", "")
        if (s.isBlank()) null else JSONObject(s).let { Spot(LL(it.getDouble("la"), it.getDouble("lo")), it.getLong("t")) }
    } catch (e: Exception) { null }

    fun clear(ctx: Context) = Prefs(ctx).put("parked", "")

    /** The car was switched off here. */
    fun note(ctx: Context, p: LL?) {
        if (p == null) return
        for (k in listOf("casa", "trabalho")) {
            val s = Places.slot(ctx, k)
            if (s?.lat != null && s.lon != null && dist(p, LL(s.lat, s.lon)) < 200.0) { clear(ctx); return }
        }
        Prefs(ctx).put("parked", JSONObject().put("la", p.lat).put("lo", p.lon).put("t", System.currentTimeMillis()).toString())
    }
}
