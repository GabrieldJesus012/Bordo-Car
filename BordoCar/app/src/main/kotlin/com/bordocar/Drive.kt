package com.bordocar

import android.content.Context
import android.location.Location
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max

/** One trip: when it started and ended, how far and how long you were moving, and where from and to (when known). */
class Trip(val start: Long, val end: Long, val dist: Double, val moving: Long, val from: LL? = null, val to: LL? = null)

/**
 * Everything the dashboard shows that comes from the GPS: speed, which way you're heading, and the distance driven (today and
 * this week). Fed by [DriveService] once a second while the phone is on Android Auto (and by the phone's screen while it's open).
 *
 * A trip starts when you move off, ends after two minutes standing still (or when the car is switched off), and is saved with
 * its distance. The trip being driven is also kept every 20 seconds, so it isn't lost if Android closes the app.
 */
object Drive {
    var speed by mutableStateOf(0.0)            // m/s
    /** When the last GPS fix came (to notice when the GPS stops). */
    @Volatile var lastFixAt = 0L
    var heading by mutableStateOf<Double?>(null)   // degrees, null until you've moved
    var pos by mutableStateOf<LL?>(null)
    /** Bumped whenever the distance changes, so the cards re-read the totals. */
    var tick by mutableStateOf(0)

    private var prefs: Prefs? = null
    private var tripOn = false
    private var tripT0 = 0L
    private var tripDistance = 0.0
    private var tripMoveSec = 0L
    private var tripLast: LL? = null
    private var tripLastAt = 0L
    private var tripSnapAt = 0L
    private var drove = false
    private var stillSince = 0L
    private var recovered = false
    private var lastHeadingPos: LL? = null
    private var tripFrom: LL? = null

    fun init(ctx: Context) {
        if (prefs != null) return
        prefs = Prefs(ctx)
        // the odometer starts from the trips already saved (versions before it existed)
        if (prefs?.s("odo", "").isNullOrBlank()) prefs?.put("odo", trips().sumOf { it.dist }.toString())
    }

    fun onLocation(ctx: Context, l: Location) {
        init(ctx)
        val p = LL(l.latitude, l.longitude)
        lastFixAt = System.currentTimeMillis()
        val sp = if (l.hasSpeed()) l.speed.toDouble() else 0.0
        speed = sp
        // which way you're going: the GPS's own bearing when moving, otherwise from the last few metres travelled
        if (l.hasBearing() && sp > 1.5) heading = l.bearing.toDouble()
        else lastHeadingPos?.let { a -> if (dist(a, p) > 15 && sp > 1.0) heading = bearing(a, p) }
        if (lastHeadingPos == null || dist(lastHeadingPos!!, p) > 15) lastHeadingPos = p
        pos = p
        track(p, sp)
    }

    private fun bearing(a: LL, b: LL): Double {
        val dy = b.lat - a.lat
        val dx = (b.lon - a.lon) * Math.cos(Math.toRadians(a.lat))
        return (Math.toDegrees(Math.atan2(dx, dy)) + 360.0) % 360.0
    }

    // ---------------------------------------------------------------- trips

    private fun track(p: LL, sp: Double) {
        val now = System.currentTimeMillis()
        if (!recovered) recover(now)
        if (tripOn) {
            val last = tripLast
            if (last == null) { tripLast = p; tripLastAt = now }
            else {
                val d = dist(last, p)
                // measured from the last point counted, so slow traffic (under 2 m between fixes) adds up too; a gap in the GPS
                // (a tunnel) counts when the jump fits the time it took
                if (d >= 2.0 && (sp > 0.8 || d >= 25.0)) {
                    if (d <= max(250.0, (now - tripLastAt) / 1000.0 * 45.0)) { tripDistance += d; tick++ }
                    tripLast = p; tripLastAt = now
                }
            }
            if (sp > 1.0) tripMoveSec += 1
            if (now - tripSnapAt > 20_000L) snap(now)
        } else if (sp > 1.5) {
            tripOn = true; tripT0 = now; tripDistance = 0.0; tripMoveSec = 0; tripLast = p; tripLastAt = now; tripFrom = p
        }
        // the end of a trip: standing still for two minutes
        if (sp > 4.0) { drove = true; stillSince = 0L }
        else if (sp < 0.8 && drove) {
            if (stillSince == 0L) stillSince = now
            else if (now - stillSince > 120_000) {
                if (tripOn && tripDistance > 300) save(Trip(tripT0, stillSince, tripDistance, tripMoveSec, tripFrom, p))
                tripOn = false; drove = false; stillSince = 0L
                prefs?.put("tripcur", "")
                tick++
            }
        }
    }

    /** The car was switched off (or the phone unplugged): the trip being driven ends and is saved now. */
    fun endTrip() {
        try {
            if (tripOn && tripDistance > 300) save(Trip(tripT0, System.currentTimeMillis(), tripDistance, tripMoveSec, tripFrom, pos))
            tripOn = false; drove = false; stillSince = 0L
            prefs?.put("tripcur", "")
        } catch (e: Exception) { }
    }

    private fun snap(now: Long) {
        tripSnapAt = now
        try {
            val o = JSONObject().put("s", tripT0).put("d", tripDistance).put("m", tripMoveSec).put("at", now)
            tripFrom?.let { o.put("fl", it.lat).put("fo", it.lon) }; pos?.let { o.put("tl", it.lat).put("to", it.lon) }
            prefs?.put("tripcur", o.toString())
        } catch (e: Exception) { }
    }

    /** A trip left over from before (the app was closed while driving): carried on when it was moments ago, otherwise saved. */
    private fun recover(now: Long) {
        recovered = true
        val s = prefs?.s("tripcur", "").orEmpty()
        if (s.isBlank()) return
        try {
            val o = JSONObject(s)
            if (now - o.optLong("at") < 5 * 60_000L && !tripOn) {
                tripOn = true; tripT0 = o.getLong("s"); tripDistance = o.getDouble("d"); tripMoveSec = o.getLong("m"); tripLast = null; drove = true
                tripFrom = ll(o, "fl", "fo")
            } else {
                if (o.getDouble("d") > 300) save(Trip(o.getLong("s"), o.optLong("at"), o.getDouble("d"), o.getLong("m"), ll(o, "fl", "fo"), ll(o, "tl", "to")))
                prefs?.put("tripcur", "")
            }
        } catch (e: Exception) { prefs?.put("tripcur", "") }
    }

    fun trips(): List<Trip> {
        val s = prefs?.s("trips", "").orEmpty()
        if (s.isBlank()) return emptyList()
        return try {
            val a = JSONArray(s)
            (0 until a.length()).mapNotNull { i ->
                try { val o = a.getJSONObject(i); Trip(o.getLong("s"), o.getLong("e"), o.getDouble("d"), o.getLong("m"), ll(o, "fl", "fo"), ll(o, "tl", "to")) } catch (e: Exception) { null }
            }
        } catch (e: Exception) { emptyList() }
    }

    private fun ll(o: JSONObject, a: String, b: String): LL? = if (o.has(a) && o.has(b)) LL(o.getDouble(a), o.getDouble(b)) else null

    private fun save(t: Trip) {
        val a = JSONArray()
        (listOf(t) + trips()).take(1500).forEach {
            val o = JSONObject().put("s", it.start).put("e", it.end).put("d", it.dist).put("m", it.moving)
            it.from?.let { f -> o.put("fl", f.lat).put("fo", f.lon) }; it.to?.let { e -> o.put("tl", e.lat).put("to", e.lon) }
            a.put(o)
        }
        prefs?.put("trips", a.toString())
        // the odometer: every km ever counted (for the fuel figures)
        prefs?.put("odo", (odometerSaved() + t.dist).toString())
        tick++
    }

    private fun odometerSaved(): Double = prefs?.s("odo", "0")?.toDoubleOrNull() ?: 0.0
    /** Every metre counted so far, the trip being driven included. */
    fun odometer(): Double = odometerSaved() + (if (tripOn) tripDistance else 0.0)

    /** Distance (m), time moving (s) and trips this month. */
    fun month(): Triple<Double, Long, Int> {
        val c = java.util.Calendar.getInstance().apply { set(java.util.Calendar.DAY_OF_MONTH, 1); set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0) }
        val list = trips().filter { it.start >= c.timeInMillis }
        var d = list.sumOf { it.dist }; var s = list.sumOf { it.moving }; var n = list.size
        if (tripOn && tripT0 >= c.timeInMillis) { d += tripDistance; s += tripMoveSec; n++ }
        return Triple(d, s, n)
    }

    private fun dayStart(): Long = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** Distance (m) and time moving (s) driven today: the trips saved today plus the one being driven now. */
    /** The trip going on now (metres, seconds moving), or null. */
    fun current(): Pair<Double, Long>? = if (tripOn) tripDistance to tripMoveSec else null

    /** How many trips today (the one going on counts). */
    fun todayTrips(): Int { val d0 = dayStart(); return trips().count { it.start >= d0 } + (if (tripOn && tripT0 >= d0) 1 else 0) }

    fun today(): Pair<Double, Long> {
        val d0 = dayStart()
        val list = trips().filter { it.start >= d0 }
        var d = list.sumOf { it.dist }; var s = list.sumOf { it.moving }
        if (tripOn && tripT0 >= d0) { d += tripDistance; s += tripMoveSec }
        return d to s
    }

    /** Metres driven on each day of this week, Monday first (today's includes the trip being driven now). */
    fun week(): DoubleArray {
        val out = DoubleArray(7)
        val cal = java.util.Calendar.getInstance()
        val today = (cal.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7
        val d0 = dayStart()
        val monday = d0 - today * 86_400_000L
        for (t in trips()) {
            if (t.start < monday || t.start >= d0) continue
            val c = java.util.Calendar.getInstance().apply { timeInMillis = t.start }
            val d = (c.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7
            if (d in 0 until today) out[d] += t.dist
        }
        out[today] = today().first
        return out
    }
}
