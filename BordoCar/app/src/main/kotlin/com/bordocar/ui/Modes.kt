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
    fun next(ctx: Context) = set(ctx, all[(all.indexOfFirst { it.first == cur } + 1) % all.size].first)
    fun name(key: String = cur) = all.firstOrNull { it.first == key }?.second ?: "Normal"
}
