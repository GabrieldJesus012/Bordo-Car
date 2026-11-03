package com.bordocar

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A saved place: what you call it, its address, and where it is (lat/lon, when it was picked from a search or your position). */
data class Place(val name: String, val address: String, val lat: Double? = null, val lon: Double? = null) {
    fun toJson(): JSONObject = JSONObject().put("n", name).put("a", address).apply { if (lat != null && lon != null) { put("la", lat); put("lo", lon) } }
    /** What Waze / Google Maps are given: the exact spot when known, otherwise the address to look up. */
    val target: String get() = if (lat != null && lon != null) "%.6f,%.6f".format(java.util.Locale.US, lat, lon) else address
    companion object {
        fun fromJson(o: JSONObject): Place? {
            val a = o.optString("a"); if (a.isBlank()) return null
            return Place(o.optString("n").ifBlank { a }, a, if (o.has("la")) o.optDouble("la") else null, if (o.has("lo")) o.optDouble("lo") else null)
        }
    }
}

/** Casa, Trabalho and your favourites, kept on the phone (the car screen reads the same). */
object Places {
    /** Casa ("casa") or Trabalho ("trabalho"); an older plain address is read too. */
    fun slot(ctx: Context, key: String): Place? {
        val s = Prefs(ctx).s(key, "").trim()
        if (s.isBlank()) return null
        return try { if (s.startsWith("{")) Place.fromJson(JSONObject(s)) else Place(s, s) } catch (e: Exception) { null }
    }
    fun setSlot(ctx: Context, key: String, p: Place?) = Prefs(ctx).put(key, p?.toJson()?.toString() ?: "")

    fun favourites(ctx: Context): List<Place> = try {
        val a = JSONArray(Prefs(ctx).s("favs", "[]"))
        (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(Place::fromJson) }
    } catch (e: Exception) { emptyList() }
    fun saveFavourites(ctx: Context, list: List<Place>) {
        val a = JSONArray(); list.forEach { a.put(it.toJson()) }
        Prefs(ctx).put("favs", a.toString())
    }
    fun addFavourite(ctx: Context, p: Place) = saveFavourites(ctx, favourites(ctx).filterNot { it.address == p.address && it.name == p.name } + p)
    fun removeFavourite(ctx: Context, p: Place) = saveFavourites(ctx, favourites(ctx).filterNot { it == p })

    /** Finds addresses and places for [q] (Android's own address search, online), nearest to [near] first. */
    @Suppress("DEPRECATION")
    fun search(ctx: Context, q: String, near: LL?): List<Place> = try {
        val g = android.location.Geocoder(ctx, java.util.Locale("pt", "BR"))
        val res = if (near != null) g.getFromLocationName(q, 8, near.lat - 1.5, near.lon - 1.5, near.lat + 1.5, near.lon + 1.5)?.takeIf { it.isNotEmpty() } ?: g.getFromLocationName(q, 8)
                  else g.getFromLocationName(q, 8)
        (res ?: emptyList()).map { a -> Place(a.featureName?.takeIf { it.any { c -> c.isLetter() } } ?: q, line(a), a.latitude, a.longitude) }
            .sortedBy { p -> if (near != null && p.lat != null && p.lon != null) dist(near, LL(p.lat, p.lon)) else 0.0 }
    } catch (e: Exception) { emptyList() }

    /** The address of where you are now. */
    @Suppress("DEPRECATION")
    fun here(ctx: Context, p: LL): Place = try {
        val a = android.location.Geocoder(ctx, java.util.Locale("pt", "BR")).getFromLocation(p.lat, p.lon, 1)?.firstOrNull()
        Place(a?.thoroughfare ?: "Minha localização", if (a != null) line(a) else "%.5f, %.5f".format(java.util.Locale.US, p.lat, p.lon), p.lat, p.lon)
    } catch (e: Exception) { Place("Minha localização", "%.5f, %.5f".format(java.util.Locale.US, p.lat, p.lon), p.lat, p.lon) }

    private fun line(a: android.location.Address): String =
        (0..a.maxAddressLineIndex).mapNotNull { a.getAddressLine(it) }.joinToString(", ").ifBlank {
            listOfNotNull(a.thoroughfare, a.subThoroughfare, a.subLocality, a.locality, a.adminArea).joinToString(", ")
        }
}
