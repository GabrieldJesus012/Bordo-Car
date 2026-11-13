package com.bordocar.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bordocar.Prefs

/** Which dashboard the car screen shows, chosen on the phone or on the car (Ajustes). */
object Modes {
    val all = listOf("normal" to "Normal", "esportivo" to "Esportivo", "musica" to "Música", "estrada" to "Estrada")
    var cur by mutableStateOf("normal")
        private set
    private var loaded = false
    fun load(ctx: Context) { if (loaded) return; loaded = true; cur = Prefs(ctx).s("mode", "normal").takeIf { k -> all.any { it.first == k } } ?: "normal" }
    fun set(ctx: Context, key: String) { cur = key; Prefs(ctx).put("mode", key) }
    /** Estrada is offered only while there's a route (Waze / Google Maps navigating). */
    fun routeOn(now: Long = System.currentTimeMillis()) = com.bordocar.NavNotice.active(now)
    fun available(now: Long = System.currentTimeMillis()) = if (routeOn(now)) all else all.filter { it.first != "estrada" }
    /** The mode on the screen: Estrada shows as Normal while there's no route, and comes back by itself with the next route. */
    fun shown(now: Long = System.currentTimeMillis()) = if (cur == "estrada" && !routeOn(now)) "normal" else cur
    fun next(ctx: Context, now: Long = System.currentTimeMillis()) {
        val av = available(now)
        set(ctx, av[(av.indexOfFirst { it.first == shown(now) } + 1) % av.size].first)
    }
    fun name(key: String = shown()) = all.firstOrNull { it.first == key }?.second ?: "Normal"
}
