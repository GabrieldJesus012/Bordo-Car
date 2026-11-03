package com.bordocar.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.bordocar.Prefs

/** The dashboard's colours: background, cards, text, and the accent (gauges, bars, the next-turn card). */
class Palette(
    val key: String, val name: String,
    val bg: List<Color>, val card: List<Color>, val edge: Color,
    val sub: Color, val label: Color, val soft: Color, val track: Color, val tick: Color,
    val accent: List<Color>, val bar: Color, val turn: List<Color>, val turnEdge: Color, val badge: Color,
)

/** The four looks, chosen on the phone or on the car (Ajustes); the dashboard changes straight away. */
object Themes {
    val all = listOf(
        Palette("azul", "Azul",
            listOf(Color(0xFF1B2A4E), Color(0xFF0B1222), Color(0xFF070B16)), listOf(Color(0xC028385C), Color(0xD912192E)), Color(0x3878A0FF),
            Color(0xFF9FB0D0), Color(0xFF8FA3C8), Color(0xFFC9D6F2), Color(0xFF1E2A44), Color(0xFF3A4A6A),
            listOf(Color(0xFF1E6BFF), Color(0xFF5CC8FF)), Color(0xFF3D8BFF), listOf(Color(0xFF1E6BFF), Color(0xFF0E3FA8)), Color(0xFF5CC8FF), Color(0xFF33CCFF)),
        Palette("grafite", "Grafite",
            listOf(Color(0xFF2A2D33), Color(0xFF141518), Color(0xFF0B0C0E)), listOf(Color(0xC03A3D44), Color(0xE01C1E22)), Color(0x29C8CDD7),
            Color(0xFFA9AEB8), Color(0xFF8C929D), Color(0xFFD0D4DB), Color(0xFF2A2D33), Color(0xFF454951),
            listOf(Color(0xFF9AA4B5), Color(0xFFE6EAF0)), Color(0xFFB5BDCA), listOf(Color(0xFF4A505B), Color(0xFF2A2E35)), Color(0xFFC9CED6), Color(0xFFE6EAF0)),
        Palette("verde", "Verde",
            listOf(Color(0xFF123A2E), Color(0xFF0A1A15), Color(0xFF06100C)), listOf(Color(0xB81E483A), Color(0xE00C1E18)), Color(0x336EE6AA),
            Color(0xFF9CC9B5), Color(0xFF86B5A0), Color(0xFFC6E6D8), Color(0xFF173A2E), Color(0xFF2E5A4A),
            listOf(Color(0xFF16B47A), Color(0xFF7CF2B8)), Color(0xFF2FCB8E), listOf(Color(0xFF109463), Color(0xFF0A5E40)), Color(0xFF7CF2B8), Color(0xFF7CF2B8)),
        Palette("vermelho", "Vermelho esportivo",
            listOf(Color(0xFF3A1416), Color(0xFF170A0B), Color(0xFF0D0606)), listOf(Color(0xB8501E22), Color(0xE01E0C0E)), Color(0x33FF7878),
            Color(0xFFD2A3A3), Color(0xFFBE8E8E), Color(0xFFEBCACA), Color(0xFF3A1A1C), Color(0xFF5A2E30),
            listOf(Color(0xFFE5262E), Color(0xFFFF8A5C)), Color(0xFFFF5A4E), listOf(Color(0xFFC81E28), Color(0xFF7A1016)), Color(0xFFFF8A5C), Color(0xFFFFB199)),
    )
    var cur by mutableStateOf(all[0])
        private set
    private var loaded = false
    fun load(ctx: Context) { if (loaded) return; loaded = true; val k = Prefs(ctx).s("theme", "azul"); cur = all.firstOrNull { it.key == k } ?: all[0] }
    fun set(ctx: Context, p: Palette) { cur = p; Prefs(ctx).put("theme", p.key) }
    fun next(ctx: Context) = set(ctx, all[(all.indexOf(cur) + 1) % all.size])
}
