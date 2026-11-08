package com.bordocar

import android.content.Context
import org.json.JSONObject

/**
 * The map of the next turn (Estrada): the Mapbox key, and the route Mapbox gives for the destination chosen in Bordo Car (Casa,
 * Trabalho, a favourite, a search). Waze / Google Maps don't say where their turns are, so with that route Bordo Car knows the exact
 * spot of each turn and can count the streets before it ("3ª à direita"). Without it (a route started in Waze / Maps itself) the
 * turn is placed from the distance in their notice, straight ahead.
 */
object MapRoute {
    /** The Mapbox public key (pk.…), pasted in the phone's settings (not kept in the code). */
    private const val DEFAULT_TOKEN = ""
    fun token(ctx: Context): String = Prefs(ctx).s("mapbox", DEFAULT_TOKEN).trim()
    fun enabled(ctx: Context): Boolean = Prefs(ctx).b("junction", true) && token(ctx).startsWith("pk.")

    /** A turn of the route: where it is, which way, the street, and the crossings with a street on that side before it. */
    class Turn(val at: LL, val kind: String, val street: String, val sideStreets: List<LL>, val alongM: Double)

    @Volatile var points: List<LL> = emptyList(); private set
    private var cum = DoubleArray(0)
    @Volatile var turns: List<Turn> = emptyList(); private set
    @Volatile private var target: LL? = null
    @Volatile private var askedAt = 0L
    @Volatile var note = ""

    /** Asks Mapbox for the route from where you are to [to] (in the background). [keep]: it's the destination chosen in Bordo Car. */
    fun request(ctx: Context, from: LL?, to: LL, keep: Boolean = true) {
        if (keep) target = to
        if (from == null || !enabled(ctx)) { points = emptyList(); turns = emptyList(); return }
        askedAt = System.currentTimeMillis()
        val tok = token(ctx)
        Thread {
            try {
                val url = "https://api.mapbox.com/directions/v5/mapbox/driving/${from.lon},${from.lat};${to.lon},${to.lat}" +
                    "?steps=true&geometries=geojson&overview=full&language=pt-BR&access_token=$tok"
                parse(JSONObject(httpGet(url, 12_000)))
                note = "rota do Mapbox: ${turns.size} curvas"
            } catch (e: Exception) { note = "rota do Mapbox não veio: ${e.javaClass.simpleName}"; points = emptyList(); turns = emptyList() }
        }.start()
    }

    /** When you've left Mapbox's route (Waze took another way): ask again from here, at most once a minute. */
    fun check(ctx: Context, here: LL) {
        val t = target ?: return
        if (points.isEmpty() || System.currentTimeMillis() - askedAt < 60_000L) return
        if (distToRoute(here) > 80.0) request(ctx, here, t)
    }

    fun clear() { target = null; points = emptyList(); turns = emptyList() }

    private var miniKey = ""
    private var miniAt = 0L
    /**
     * A route started in Waze / Google Maps (Bordo Car doesn't know the destination): asks Mapbox for the way from here to just past
     * the turn the notice speaks of (straight ahead [metres], then a bit to its side). Mapbox follows the real streets, so its route
     * gives the real spot of the turn and the streets before it, to count them. Once per notice, at most every 20 s.
     */
    fun requestNear(ctx: Context, here: LL, heading: Double?, metres: Double, kind: String?, key: String, street: String? = null) {
        if (!enabled(ctx) || heading == null) return
        if (target != null && points.isNotEmpty() && position(here).second < 60.0) return      // the Bordo route is good
        // the route there already has this turn: keep it (asking again near the turn gave a worse one, and the map went away)
        if (points.isNotEmpty() && position(here).second < 40.0 && next(here, metres, kind, street) != null) { miniKey = key; return }
        val now = System.currentTimeMillis()
        if (key == miniKey && now - miniAt < 20_000L) return
        if (now - miniAt < 8_000L) return
        miniKey = key; miniAt = now
        val turnDeg = when (kind) { "direita" -> 90.0; "leve-direita" -> 40.0; "esquerda" -> -90.0; "leve-esquerda" -> -40.0; else -> 0.0 }
        val at = ahead(here, heading, metres)
        request(ctx, here, ahead(at, heading + turnDeg, 90.0), keep = false)
    }

    /** A point [m] metres from [p] heading [deg]. */
    fun ahead(p: LL, deg: Double, m: Double): LL {
        val r = Math.toRadians(deg)
        return LL(p.lat + m * Math.cos(r) / 111_320.0, p.lon + m * Math.sin(r) / (111_320.0 * Math.cos(Math.toRadians(p.lat))))
    }

    /** The point [m] metres along the route, and the way the route goes there. */
    fun pointAt(m: Double): Pair<LL, Double>? {
        val pts = points; if (pts.size < 2) return null
        var i = cum.indexOfFirst { it >= m }; if (i < 1) i = 1; if (i > pts.size - 1) i = pts.size - 1
        val a = pts[i - 1]; val b = pts[i]
        val seg = (cum[i] - cum[i - 1]).coerceAtLeast(0.01); val f = ((m - cum[i - 1]) / seg).coerceIn(0.0, 1.0)
        val p = LL(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f)
        val y = Math.sin(Math.toRadians(b.lon - a.lon)) * Math.cos(Math.toRadians(b.lat))
        val x = Math.cos(Math.toRadians(a.lat)) * Math.sin(Math.toRadians(b.lat)) - Math.sin(Math.toRadians(a.lat)) * Math.cos(Math.toRadians(b.lat)) * Math.cos(Math.toRadians(b.lon - a.lon))
        return p to (Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0
    }

    private fun parse(o: JSONObject) {
        val r = o.getJSONArray("routes").getJSONObject(0)
        val g = r.getJSONObject("geometry").getJSONArray("coordinates")
        val pts = (0 until g.length()).map { val c = g.getJSONArray(it); LL(c.getDouble(1), c.getDouble(0)) }
        val c = DoubleArray(pts.size)
        for (i in 1 until pts.size) c[i] = c[i - 1] + dist(pts[i - 1], pts[i])
        val out = ArrayList<Turn>()
        val steps = r.getJSONArray("legs").getJSONObject(0).getJSONArray("steps")
        for (i in 1 until steps.length()) {
            val st = steps.getJSONObject(i)
            val m = st.getJSONObject("maneuver")
            val type = m.optString("type"); val mod = m.optString("modifier")
            if (type == "arrive" || type == "depart") continue
            val loc = m.getJSONArray("location"); val at = LL(loc.getDouble(1), loc.getDouble(0))
            val kind = when {
                type == "roundabout" || type == "rotary" -> "rotatoria"
                mod == "uturn" -> "retorno"
                mod == "right" || mod == "sharp right" -> "direita"
                mod == "left" || mod == "sharp left" -> "esquerda"
                mod == "slight right" -> "leve-direita"
                mod == "slight left" -> "leve-esquerda"
                else -> "frente"
            }
            // the crossings on the way to this turn (the previous step's) with a street to the same side
            val side = when (kind) { "direita", "leve-direita" -> 1; "esquerda", "leve-esquerda" -> -1; else -> 0 }
            val crossings = ArrayList<LL>()
            if (side != 0) {
                val prev = steps.getJSONObject(i - 1).optJSONArray("intersections")
                if (prev != null) for (k in 1 until prev.length()) {
                    val x = prev.getJSONObject(k)
                    val b = x.optJSONArray("bearings") ?: continue
                    val inI = x.optInt("in", -1); if (inI < 0) continue
                    val travel = (b.getDouble(inI) + 180.0) % 360.0
                    var has = false
                    for (j in 0 until b.length()) {
                        if (j == inI) continue
                        val rel = ((b.getDouble(j) - travel) + 360.0) % 360.0
                        if ((side > 0 && rel in 30.0..150.0) || (side < 0 && rel in 210.0..330.0)) has = true
                    }
                    if (has) { val l = x.getJSONArray("location"); crossings.add(LL(l.getDouble(1), l.getDouble(0))) }
                }
            }
            out.add(Turn(at, kind, st.optString("name"), crossings, along(pts, c, at)))
        }
        points = pts; cum = c; turns = out
    }

    private fun along(pts: List<LL>, c: DoubleArray, p: LL): Double {
        var best = Double.MAX_VALUE; var at = 0.0
        for (i in pts.indices) { val d = dist(pts[i], p); if (d < best) { best = d; at = c[i] } }
        return at
    }
    /** How far along the route you are (metres), and how far from it. */
    fun position(here: LL): Pair<Double, Double> {
        val pts = points; if (pts.isEmpty()) return 0.0 to Double.MAX_VALUE
        var best = Double.MAX_VALUE; var at = 0.0
        for (i in pts.indices) { val d = dist(pts[i], here); if (d < best) { best = d; at = cum.getOrElse(i) { 0.0 } } }
        return at to best
    }
    private fun distToRoute(here: LL) = position(here).second

    /**
     * The turn the navigation notice speaks of: on Mapbox's route, the next turn ahead of you about [metres] away (and of the same
     * kind when it's known); null when there's no route, you're off it, or none fits.
     */
    fun next(here: LL, metres: Double?, kind: String?, street: String? = null): Turn? {
        val (pos, off) = position(here)
        if (points.isEmpty() || off > 60.0) return null
        val ahead = turns.filter { it.alongM > pos + 5.0 }
        if (ahead.isEmpty()) return null
        if (metres == null) return ahead.first()
        // the street the notice names (Waze only says the street, not the way): the turn into it, even if the distance is a bit off
        if (!street.isNullOrBlank()) {
            val s = streetKey(street)
            ahead.filter { val n = streetKey(it.street); n.length >= 3 && (s.contains(n) || n.contains(s) && s.length >= 3) }
                .minByOrNull { Math.abs((it.alongM - pos) - metres) }
                ?.takeIf { Math.abs((it.alongM - pos) - metres) < maxOf(150.0, metres * 0.5) }
                ?.let { return it }
        }
        return ahead.filter { kind == null || it.kind == kind || kind == "frente" }
            .minByOrNull { Math.abs((it.alongM - pos) - metres) }
            ?.takeIf { Math.abs((it.alongM - pos) - metres) < maxOf(80.0, metres * 0.35) }
    }

    /** A street's name for comparing: no accents, no "Rua", "Av." and the like. */
    fun streetKey(s: String): String {
        val a = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        return a.replace(Regex("\\b(r|rua|av|avenida|tv|travessa|rod|rodovia|pc|praca|al|alameda|estr|estrada|vire|a|na|no|em|para|direita|esquerda|siga|pela|pelo)\\b\\.?"), " ")
            .replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
    }

    /** The route from about [backM] before to [aheadM] after the place [fromM] along it. */
    fun slice(fromM: Double, toM: Double): List<LL> {
        val pts = points; if (pts.size < 2) return emptyList()
        return pts.indices.filter { cum[it] in fromM..toM }.map { pts[it] }
    }
}
