package com.bordocar.ui

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.TurnRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bordocar.Drive
import com.bordocar.NavNotice
import com.bordocar.NowPlaying
import com.bordocar.Prefs
import com.bordocar.Weather
import java.util.Locale

/**
 * The sporty dashboard: above 70 km/h (back to the usual one below 60, so it doesn't keep switching), when it's turned on in the
 * settings (off at first). "Ver prévia" shows it for a few seconds with made-up speed and a sample turn, to see how it looks
 * without having to drive at 70.
 */
object Sport {
    const val ON_KMH = 70
    const val OFF_KMH = 60
    const val PREVIEW_MS = 14_000L
    var enabled by mutableStateOf(false)
        private set
    /** Above 70 (until it drops below 60). */
    var fast by mutableStateOf(false)
        private set
    var previewFrom by mutableLongStateOf(0L)
        private set
    private var loaded = false
    fun load(ctx: Context) { if (loaded) return; loaded = true; enabled = Prefs(ctx).b("sport", false) }
    fun set(ctx: Context, on: Boolean) { enabled = on; Prefs(ctx).put("sport", on); if (on) preview() else previewFrom = 0L }
    fun preview() { previewFrom = System.currentTimeMillis() }
    fun previewing(now: Long) = previewFrom > 0L && now - previewFrom in 0 until PREVIEW_MS
    /** Called once a second with the speed in km/h. */
    fun update(kmh: Int) { if (kmh >= ON_KMH) fast = true else if (kmh < OFF_KMH) fast = false }
    fun showing(now: Long) = previewing(now) || (enabled && fast)
}

private val BRL = Locale("pt", "BR")
private val Red = Color(0xFFE5262E)
private val RedHi = Color(0xFFFF4A3A)
private val Orange = Color(0xFFFF6A4E)
private val Grey = Color(0xFF8A8A90)
private val Soft = Color(0xFFCFCFD4)

@Composable
fun SportDash(now: Long, bat: Int, charging: Boolean, wx: Weather.Now?, city: String, padL: Dp, padT: Dp, padR: Dp, padB: Dp) {
    val preview = Sport.previewing(now)
    val t = if (preview) (now - Sport.previewFrom) / 1000f else 0f
    val real = Math.round((Drive.speed * 3.6).coerceAtLeast(0.0)).toInt()
    // the preview: the speed goes up from 72 to ~110, a sample turn for the first half (when there's no route), then no route
    val kmh = if (preview && real < Sport.ON_KMH) (72 + t * 2.8f).toInt() else real
    val hd = Drive.heading ?: if (preview) (40.0 + t * 6) else null
    val realRoute = NavNotice.active(now)
    val sample = preview && !realRoute && t < Sport.PREVIEW_MS / 2000f
    val route = realRoute || sample
    val turnTitle = if (sample) "${(800 - t * 60).toInt().coerceAtLeast(100) / 10 * 10} m" else NavNotice.title
    val turnText = if (sample) "Vire à direita · exemplo" else NavNotice.text
    val turnSub = if (sample) "Chegada 18:05 · 21 km" else NavNotice.sub

    Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0xFF2A1012), Color(0xFF0E0808), Color(0xFF060404)), center = Offset(750f, 0f), radius = 1900f))
        .drawBehind {
            // faint carbon stripes
            val step = 14f
            var x = -size.height
            while (x < size.width) { drawLine(Color(0x0DFFFFFF), Offset(x, size.height), Offset(x + size.height, 0f), strokeWidth = 5f); x += step }
        }) {
        Column(Modifier.fillMaxSize().padding(start = padL, top = padT, end = padR, bottom = padB)) {
            // the time, battery and weather (Android Auto's buttons sit at the right end)
            Row(Modifier.fillMaxWidth().height(52.dp).padding(start = 12.dp, end = 170.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(java.text.SimpleDateFormat("HH:mm", BRL).format(java.util.Date(now)), color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold)
                // the day and month next to the time: "ter, 3 de novembro"
                Text(java.text.SimpleDateFormat("EEE, d 'de' MMMM", BRL).format(java.util.Date(now)).replace(".", ""), color = Soft, fontSize = 18.sp,
                    maxLines = 1, modifier = Modifier.padding(start = 14.dp))
                if (bat >= 0) Text((if (charging) "⚡ " else "🔋 ") + "$bat%", color = Soft, fontSize = 20.sp, modifier = Modifier.padding(start = 20.dp))
                if (preview) Text("PRÉVIA", color = Color(0xFF1A0A0A), fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 20.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFFFFC24E)).padding(horizontal = 8.dp, vertical = 2.dp))
                Spacer(Modifier.weight(1f))
                if (wx != null) Text("${Weather.emoji(wx.code)} ${Math.round(wx.tempC)}°" + (if (city.isNotBlank()) " $city" else ""), color = Soft, fontSize = 20.sp, maxLines = 1)
            }
            Leds(kmh, Modifier.fillMaxWidth().height(20.dp).padding(horizontal = 12.dp))
            Row(Modifier.fillMaxWidth().weight(1f).padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                // speed
                BoxWithConstraints(Modifier.weight(0.3f).fillMaxHeight().padding(start = 12.dp)) {
                    val big = (minOf(maxHeight.value * 0.42f, maxWidth.value * 0.42f)).coerceIn(40f, 170f)
                    Column(Modifier.align(Alignment.CenterStart)) {
                        Text("VELOCIDADE", color = Grey, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                        Text("$kmh", color = Color.White, fontSize = big.sp, lineHeight = (big * 1.02f).sp, fontWeight = FontWeight.ExtraBold, fontStyle = FontStyle.Italic, maxLines = 1)
                        Text("km/h", color = Grey, fontSize = (big * 0.17f).coerceIn(14f, 26f).sp, fontStyle = FontStyle.Italic)
                    }
                    Music(Modifier.align(Alignment.BottomStart).padding(bottom = 6.dp))
                }
                // the compass ring, with the next turn inside it
                Box(Modifier.weight(0.4f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        val d = minOf(maxWidth, maxHeight)
                        Ring(hd, route, Modifier.size(d))
                        Column(Modifier.size(d * 0.7f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            if (route) {
                                val ic = if (sample) null else NavNotice.icon
                                val isz = d.value * 0.26f
                                if (ic != null) Image(ic.asImageBitmap(), null, colorFilter = ColorFilter.tint(Color.White), modifier = Modifier.size(isz.dp))
                                else Icon(Icons.Rounded.TurnRight, null, tint = Color.White, modifier = Modifier.size(isz.dp))
                                androidx.compose.runtime.key(turnTitle) {
                                    Text(turnTitle, color = Color.White, fontSize = (d.value * 0.115f).coerceIn(18f, 54f).sp, fontWeight = FontWeight.ExtraBold, fontStyle = FontStyle.Italic,
                                        maxLines = 1, modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 2000, repeatDelayMillis = 2000, velocity = 40.dp))
                                }
                                if (turnText.isNotBlank()) androidx.compose.runtime.key(turnText) {
                                    Text(turnText, color = Color(0xFFFFD0C8), fontSize = (d.value * 0.045f).coerceIn(12f, 18f).sp, maxLines = 1,
                                        modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 2000, repeatDelayMillis = 2000, velocity = 40.dp))
                                }
                            } else {
                                Text(if (hd != null) cardinalOf(hd) else "–", color = Color.White, fontSize = (d.value * 0.2f).coerceIn(24f, 90f).sp, fontWeight = FontWeight.ExtraBold, fontStyle = FontStyle.Italic)
                                Text(if (hd != null) "${Math.round(hd) % 360}°" else "aguardando o GPS", color = Soft, fontSize = (d.value * 0.06f).coerceIn(13f, 24f).sp)
                            }
                        }
                    }
                }
                // the route, this trip, today
                Column(Modifier.weight(0.3f).fillMaxHeight().padding(end = 12.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.SpaceEvenly) {
                    if (route && turnSub.isNotBlank()) Stat("ROTA", turnSub, null, small = true)
                    val cur = Drive.current()
                    val tripKm = if (preview && cur == null) 48.3 else (cur?.first ?: 0.0) / 1000.0
                    val tripMin = if (preview && cur == null) 38 else ((cur?.second ?: 0L) / 60L).toInt()
                    Stat("ESTA VIAGEM", km(tripKm), if (tripMin > 0) (if (tripMin < 60) "$tripMin min" else "${tripMin / 60}h ${"%02d".format(tripMin % 60)}") else null)
                    if (!(route && turnSub.isNotBlank())) Stat("KM HOJE", km(Drive.today().first / 1000.0), null)
                }
            }
        }
    }
}

private fun km(v: Double) = (if (v < 100) String.format(BRL, "%.1f", v) else "${Math.round(v)}") + " km"
private fun cardinalOf(deg: Double) = listOf("N", "NE", "L", "SE", "S", "SO", "O", "NO")[(((deg % 360) + 360) % 360 / 45.0 + 0.5).toInt() % 8]

@Composable
private fun Stat(label: String, value: String, sub: String?, small: Boolean = false) {
    Column(horizontalAlignment = Alignment.End) {
        Text(label, color = Grey, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        Text(value, color = Color.White, fontSize = if (small) 22.sp else 38.sp, fontWeight = FontWeight.ExtraBold, fontStyle = FontStyle.Italic,
            maxLines = if (small) 2 else 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End, lineHeight = if (small) 26.sp else 42.sp)
        if (sub != null) Text(sub, color = Soft, fontSize = 16.sp, maxLines = 1)
    }
}

@Composable
private fun Music(modifier: Modifier) {
    if (NowPlaying.title.isBlank()) return
    Text("♪ " + NowPlaying.title + (if (NowPlaying.artist.isNotBlank()) " — " + NowPlaying.artist else ""), color = Soft, fontSize = 16.sp, maxLines = 1,
        overflow = TextOverflow.Ellipsis, modifier = modifier)
}

/** The row of lights: green, then yellow, then red, lit up to the speed (full at 140 km/h). */
@Composable
private fun Leds(kmh: Int, modifier: Modifier) {
    Canvas(modifier) {
        val n = 24; val gap = size.width * 0.006f
        val w = (size.width - gap * (n - 1)) / n
        val lit = (kmh / 140f * n).toInt().coerceIn(0, n)
        for (i in 0 until n) {
            val c = when { i < n * 0.38f -> Color(0xFF2FCB8E); i < n * 0.67f -> Color(0xFFFFC24E); else -> Red }
            val x = i * (w + gap)
            if (i < lit) {
                drawRoundRect(c.copy(alpha = 0.28f), Offset(x - 3f, -3f), androidx.compose.ui.geometry.Size(w + 6f, size.height + 6f), androidx.compose.ui.geometry.CornerRadius(8f))
                drawRoundRect(c, Offset(x, 0f), androidx.compose.ui.geometry.Size(w, size.height), androidx.compose.ui.geometry.CornerRadius(5f))
            } else drawRoundRect(Color(0xFF26262A), Offset(x, 0f), androidx.compose.ui.geometry.Size(w, size.height), androidx.compose.ui.geometry.CornerRadius(5f))
        }
    }
}

/** The compass ring: N at the top, the red marker on the ring is the way you're going; red inside while there's a route. */
@Composable
private fun Ring(hd: Double?, route: Boolean, modifier: Modifier) {
    val m = androidx.compose.ui.text.rememberTextMeasurer()
    Canvas(modifier) {
        val r = size.minDimension / 2f
        val c = Offset(size.width / 2f, size.height / 2f)
        drawCircle(Brush.radialGradient(if (route) listOf(Color(0xFF7A1016), Color(0xFF1A0A0C)) else listOf(Color(0xFF1A1B20), Color(0xFF0C0C0E)), c, r), r * 0.97f, c)
        drawCircle(if (route) Red else Color(0xFF2B2B31), r * 0.97f, c, style = Stroke(r * 0.018f))
        for (i in 0 until 36) {
            val a = Math.toRadians(i * 10.0)
            val major = i % 9 == 0
            val r0 = r * (if (major) 0.80f else 0.86f); val r1 = r * 0.91f
            drawLine(if (major) Color(0xFFAAAAAA) else Color(0xFF55555A),
                Offset(c.x + (r0 * Math.sin(a)).toFloat(), c.y - (r0 * Math.cos(a)).toFloat()),
                Offset(c.x + (r1 * Math.sin(a)).toFloat(), c.y - (r1 * Math.cos(a)).toFloat()), strokeWidth = if (major) r * 0.016f else r * 0.008f)
        }
        val fs = (r * 0.11f).coerceAtLeast(10f)
        for ((t, deg) in listOf("N" to 0.0, "L" to 90.0, "S" to 180.0, "O" to 270.0)) {
            val a = Math.toRadians(deg)
            val lay = m.measure(t, androidx.compose.ui.text.TextStyle(color = if (t == "N") Orange else Grey, fontSize = (fs / density).sp, fontWeight = FontWeight.Bold))
            drawText(lay, topLeft = Offset(c.x + (r * 0.69f * Math.sin(a)).toFloat() - lay.size.width / 2f, c.y - (r * 0.69f * Math.cos(a)).toFloat() - lay.size.height / 2f))
        }
        if (hd != null) rotate(hd.toFloat(), c) {
            val p = Path().apply { moveTo(c.x, c.y - r * 0.99f); lineTo(c.x + r * 0.07f, c.y - r * 0.86f); lineTo(c.x - r * 0.07f, c.y - r * 0.86f); close() }
            drawPath(p, RedHi)
        }
    }
}
