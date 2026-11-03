package com.bordocar

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class LL(val lat: Double, val lon: Double)

fun dist(a: LL, b: LL): Double {
    fun rad(d: Double) = Math.toRadians(d)
    val dLat = rad(b.lat - a.lat); val dLon = rad(b.lon - a.lon)
    val x = sin(dLat / 2).pow(2) + cos(rad(a.lat)) * cos(rad(b.lat)) * sin(dLon / 2).pow(2)
    return 2 * 6371000.0 * asin(sqrt(x))
}

/** Distance as people read it on a dashboard: "300 m", "1,2 km", "14 km". */
fun fmtDist(m: Double): String = when {
    m < 1000 -> "${(Math.round(m / 10.0) * 10).toInt()} m"
    m < 10_000 -> String.format(java.util.Locale("pt", "BR"), "%.1f km", m / 1000.0)
    else -> "${Math.round(m / 1000.0)} km"
}

/** The app's settings (one small file on the phone). */
class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("bordo", Context.MODE_PRIVATE)
    fun s(k: String, d: String): String = sp.getString(k, d) ?: d
    fun b(k: String, d: Boolean): Boolean = try { sp.getBoolean(k, d) } catch (e: Exception) { d }
    fun put(k: String, v: String) = sp.edit().putString(k, v).apply()
    fun put(k: String, v: Boolean) = sp.edit().putBoolean(k, v).apply()
}

/** HTTPS GET with timeouts; the body as text. */
fun httpGet(url: String, timeoutMs: Int = 10_000): String {
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
    c.setRequestProperty("User-Agent", "BordoCar/1.0 (Android)")
    try { return c.inputStream.bufferedReader().readText() } finally { c.disconnect() }
}

/** The weather where you are (Open-Meteo, no key needed). */
object Weather {
    data class Now(val tempC: Double, val code: Int)
    fun fetch(p: LL): Now? = try {
        val j = org.json.JSONObject(httpGet("https://api.open-meteo.com/v1/forecast?latitude=${p.lat}&longitude=${p.lon}&current=temperature_2m,weather_code"))
        val cur = j.getJSONObject("current")
        Now(cur.getDouble("temperature_2m"), cur.getInt("weather_code"))
    } catch (e: Exception) { null }

    fun text(code: Int): String = when (code) {
        0 -> "Céu limpo"; 1, 2 -> "Poucas nuvens"; 3 -> "Nublado"; 45, 48 -> "Neblina"
        in 51..57 -> "Garoa"; in 61..67 -> "Chuva"; in 71..77 -> "Neve"; in 80..82 -> "Pancadas de chuva"
        in 95..99 -> "Tempestade"; else -> ""
    }
    fun emoji(code: Int): String = when (code) {
        0 -> "☀"; 1, 2 -> "⛅"; 3 -> "☁"; 45, 48 -> "🌫"; in 51..67, in 80..82 -> "🌧"; in 71..77 -> "❄"; in 95..99 -> "⛈"; else -> "🌡"
    }
}
