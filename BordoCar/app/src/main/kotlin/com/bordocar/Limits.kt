package com.bordocar

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The speed limit of the road you're on, from OpenStreetMap (free, online): the posted limit when the road has one, otherwise the
 * usual one for that kind of road in Brazil (CTB art. 61), marked as estimated. The roads around you are loaded once and reused for
 * the next few hundred metres, so it needs very little data.
 */
object Limits {
    var kmh by mutableStateOf<Int?>(null)
    var estimated by mutableStateOf(false)

    private class Way(val tags: JSONObject, val g: List<LL>)
    @Volatile private var ways: List<Way> = emptyList()
    @Volatile private var loadedAt: LL? = null
    @Volatile private var busy = false
    @Volatile private var failedAt = 0L
    private val endpoints = listOf("https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter", "https://overpass.private.coffee/api/interpreter")
    private val driveable = setOf("motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link", "secondary", "secondary_link",
        "tertiary", "tertiary_link", "unclassified", "residential", "living_street", "service", "road")

    /** Called with each GPS fix (from the GPS thread). */
    fun update(p: LL, heading: Double?) {
        val c = loadedAt
        if ((c == null || dist(c, p) > 150.0) && !busy && System.currentTimeMillis() - failedAt > 30_000L) {
            busy = true
            Thread {
                try { ways = load(p); loadedAt = p } catch (e: Exception) { failedAt = System.currentTimeMillis() } finally { busy = false }
            }.apply { isDaemon = true }.start()
        }
        val w = nearest(p, heading) ?: run { if (ways.isNotEmpty()) { kmh = null; estimated = false }; return }
        val t = w.tags
        val posted = parse(t.optString("maxspeed"))
        if (posted != null) { kmh = posted; estimated = false } else { kmh = estimate(t); estimated = kmh != null }
    }

    private fun load(p: LL): List<Way> {
        val q = "[out:json][timeout:10];way(around:220,${p.lat},${p.lon})[\"highway\"];out tags geom 160;"
        var last: Exception? = null
        for (ep in endpoints) {
            try {
                val c = URL(ep).openConnection() as HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 15000; c.requestMethod = "POST"; c.doOutput = true
                c.setRequestProperty("User-Agent", "BordoCar/1.0 (Android)")
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                c.outputStream.use { it.write(("data=" + URLEncoder.encode(q, "UTF-8")).toByteArray()) }
                if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
                val els = JSONObject(c.inputStream.bufferedReader().readText()).optJSONArray("elements") ?: JSONArray()
                val out = ArrayList<Way>()
                for (i in 0 until els.length()) {
                    val e = els.getJSONObject(i)
                    val tags = e.optJSONObject("tags") ?: continue
                    if (tags.optString("highway") !in driveable) continue
                    val g = e.optJSONArray("geometry") ?: continue
                    val pts = (0 until g.length()).mapNotNull { n -> g.optJSONObject(n)?.let { o -> LL(o.getDouble("lat"), o.getDouble("lon")) } }
                    if (pts.size >= 2) out += Way(tags, pts)
                }
                return out
            } catch (e: Exception) { last = e }
        }
        throw last ?: IOException("no server")
    }

    /** The road under you: the closest one within 30 m, preferring one that runs the way you're heading. */
    private fun nearest(p: LL, heading: Double?): Way? {
        val mLat = 111320.0; val mLon = 111320.0 * Math.cos(Math.toRadians(p.lat))
        var best: Way? = null; var bd = 1e9
        for (w in ways) {
            var d = 1e9; var brg = 0.0
            for (n in 0 until w.g.size - 1) {
                val a = w.g[n]; val b = w.g[n + 1]
                val ax = (a.lon - p.lon) * mLon; val ay = (a.lat - p.lat) * mLat
                val bx = (b.lon - p.lon) * mLon; val by = (b.lat - p.lat) * mLat
                val dx = bx - ax; val dy = by - ay
                val l2 = dx * dx + dy * dy
                val t = if (l2 > 0) ((-ax * dx - ay * dy) / l2).coerceIn(0.0, 1.0) else 0.0
                val dd = Math.hypot(ax + t * dx, ay + t * dy)
                if (dd < d) { d = dd; brg = (Math.toDegrees(Math.atan2(dx, dy)) + 360.0) % 360.0 }
            }
            var pen = if (w.tags.optString("highway") in setOf("service", "living_street")) 15.0 else 0.0
            if (heading != null) {
                val diff = Math.abs(((brg - heading + 540.0) % 360.0) - 180.0)
                val aligned = diff <= 30.0 || diff >= 150.0
                if (!aligned) pen += if (diff in 50.0..130.0) 35.0 else 12.0
            }
            if (d + pen < bd) { bd = d + pen; best = w }
        }
        return if (bd <= 30.0) best else null
    }

    private fun parse(s: String): Int? {
        val m = Regex("^(\\d+)\\s*(mph|km/h|kmh|kph)?$").find(s.trim().lowercase()) ?: return null
        val v = m.groupValues[1].toInt()
        return if (m.groupValues[2] == "mph") Math.round(v * 1.609).toInt() else v
    }

    /** CTB art. 61, where there is no sign: in town 80/60/40/30, on the road 110 (dual carriageway), 100 (single) and 60 (estradas). */
    private fun estimate(tags: JSONObject): Int? {
        val hw = tags.optString("highway")
        if (hw !in driveable) return null
        val link = hw.endsWith("_link"); val base = hw.removeSuffix("_link")
        val nm = tags.optString("name").trim().lowercase()
        val ref = tags.optString("ref").trim().lowercase()
        val oneway = tags.optString("oneway") == "yes"
        val lit = tags.optString("lit") == "yes"
        val zone = (tags.optString("maxspeed:type") + " " + tags.optString("source:maxspeed") + " " + tags.optString("zone:maxspeed")).lowercase()
        val townName = Regex("^(rua|r\\.|avenida|av\\.?|travessa|tv\\.|alameda|al\\.|pra[çc]a|largo|quadra|beco|viela|ladeira|viaduto|ponte|elevado|marginal|via expressa|acesso)\\b").containsMatchIn(nm)
        val roadName = nm.startsWith("rodovia") || Regex("^[a-z]{2}-?\\d{2,3}").containsMatchIn(ref)
        val unpaved = tags.optString("surface") in setOf("unpaved", "dirt", "gravel", "ground", "earth", "sand", "compacted", "grass")
        val town = zone.contains("urban") || (!zone.contains("rural") && (lit || townName || base in setOf("residential", "living_street", "service") || (!roadName && !unpaved)))
        return if (town) when (base) {
            "motorway" -> if (link) 40 else 80
            "trunk", "primary", "secondary" -> if (link) 40 else 60
            "tertiary" -> 40
            "unclassified", "residential", "road" -> 30
            else -> 20
        } else when (base) {
            "motorway" -> if (link) 60 else 110
            "trunk", "primary", "secondary", "tertiary" -> if (link || unpaved) 60 else if (oneway && base != "tertiary") 110 else if (roadName) 100 else 60
            "unclassified", "road" -> 60
            "residential" -> 30
            else -> 20
        }
    }
}
