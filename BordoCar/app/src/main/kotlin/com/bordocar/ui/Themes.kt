package com.bordocar.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import android.graphics.Bitmap
import com.bordocar.NowPlaying
import com.bordocar.Prefs

/** The dashboard's colours: background, cards, text, and the accent (gauges, bars, the next-turn card). */
class Palette(
    val key: String, val name: String,
    val bg: List<Color>, val card: List<Color>, val edge: Color,
    val sub: Color, val label: Color, val soft: Color, val track: Color, val tick: Color,
    val accent: List<Color>, val bar: Color, val turn: List<Color>, val turnEdge: Color, val badge: Color,
)

/** The looks, chosen on the phone or on the car (Ajustes); the dashboard changes straight away. "Capa do álbum" takes its colours
 *  from the cover of the song that's playing (and looks like Azul when nothing is). */
object Themes {
    private val fixed = listOf(
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
    private val cover = fixed[0].let { a -> Palette("capa", "Capa do álbum", a.bg, a.card, a.edge, a.sub, a.label, a.soft, a.track, a.tick, a.accent, a.bar, a.turn, a.turnEdge, a.badge) }
    val all = fixed + cover

    private var chosen by mutableStateOf(all[0])
    /** The colours in use now (for "Capa do álbum", worked out from the cover that's showing). */
    val cur: Palette get() = if (chosen.key == "capa") fromCover(NowPlaying.art) else chosen
    private var loaded = false
    fun load(ctx: Context) { if (loaded) return; loaded = true; val k = Prefs(ctx).s("theme", "azul"); chosen = all.firstOrNull { it.key == k } ?: all[0] }
    fun set(ctx: Context, p: Palette) { chosen = p; Prefs(ctx).put("theme", p.key) }
    fun next(ctx: Context) = set(ctx, all[(all.indexOfFirst { it.key == chosen.key } + 1) % all.size])

    private var lastArt: Bitmap? = null
    private var lastPal: Palette = cover
    private fun fromCover(art: Bitmap?): Palette {
        if (art == null) return cover
        if (art === lastArt) return lastPal
        lastArt = art
        lastPal = coverColour(art)?.let { make(it[0], it[1]) } ?: cover
        return lastPal
    }

    /** The cover's main colour as hue and saturation: the most common hue among its colourful pixels (grey covers give a grey look). */
    private fun coverColour(art: Bitmap): FloatArray? = try {
        val src = if (art.config == Bitmap.Config.HARDWARE) art.copy(Bitmap.Config.ARGB_8888, false) else art
        val small = Bitmap.createScaledBitmap(src, 32, 32, true)
        val bins = FloatArray(36); val sat = FloatArray(36); val hsv = FloatArray(3); var weightAll = 0f; var count = 0
        for (y in 0 until 32) for (x in 0 until 32) {
            android.graphics.Color.colorToHSV(small.getPixel(x, y), hsv)
            count++
            if (hsv[2] < 0.18f || hsv[1] < 0.2f) continue
            val w = hsv[1] * hsv[2]
            val b = ((hsv[0] / 10f).toInt()).coerceIn(0, 35)
            bins[b] += w; sat[b] += hsv[1] * w; weightAll += w
        }
        if (weightAll < count * 0.04f) floatArrayOf(220f, 0.06f)           // almost no colour: a grey look with a hint of blue
        else {
            // the strongest hue, smoothed with its neighbours so one colour split between two bins still wins
            val best = (0 until 36).maxBy { bins[it] + 0.5f * (bins[(it + 35) % 36] + bins[(it + 1) % 36]) }
            floatArrayOf(best * 10f + 5f, (sat[best] / bins[best]).coerceIn(0.35f, 0.9f))
        }
    } catch (e: Exception) { null }

    private fun hsv(h: Float, s: Float, v: Float, a: Float = 1f) =
        Color(android.graphics.Color.HSVToColor((a * 255).toInt(), floatArrayOf(((h % 360f) + 360f) % 360f, s.coerceIn(0f, 1f), v.coerceIn(0f, 1f))))

    private fun make(h: Float, s: Float): Palette {
        val g = s < 0.2f                      // grey cover
        val cs = if (g) 0.08f else s          // how colourful the look is
        return Palette("capa", "Capa do álbum",
            listOf(hsv(h, cs * 0.75f, 0.32f), hsv(h, cs * 0.7f, 0.13f), hsv(h, cs * 0.6f, 0.07f)),
            listOf(hsv(h, cs * 0.55f, 0.36f, 0.75f), hsv(h, cs * 0.6f, 0.15f, 0.87f)), hsv(h, cs * 0.45f, 0.85f, 0.22f),
            hsv(h, cs * 0.25f, 0.80f), hsv(h, cs * 0.3f, 0.70f), hsv(h, cs * 0.18f, 0.93f), hsv(h, cs * 0.5f, 0.24f), hsv(h, cs * 0.45f, 0.40f),
            if (g) listOf(hsv(h, 0.12f, 0.70f), hsv(h, 0.05f, 0.94f)) else listOf(hsv(h, cs.coerceAtLeast(0.7f), 0.92f), hsv(h + 12f, cs * 0.55f, 1f)),
            if (g) hsv(h, 0.1f, 0.78f) else hsv(h, cs.coerceAtLeast(0.65f), 1f),
            if (g) listOf(hsv(h, 0.1f, 0.36f), hsv(h, 0.1f, 0.2f)) else listOf(hsv(h, cs.coerceAtLeast(0.7f), 0.78f), hsv(h, cs.coerceAtLeast(0.7f), 0.45f)),
            hsv(h, cs * 0.5f, 1f), hsv(h + 12f, cs * 0.5f, 1f))
    }
}
