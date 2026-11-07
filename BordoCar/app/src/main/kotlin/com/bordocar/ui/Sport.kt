package com.bordocar.ui

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.rounded.RoundaboutRight
import androidx.compose.material.icons.rounded.Straight
import androidx.compose.material.icons.rounded.TurnLeft
import androidx.compose.material.icons.rounded.TurnRight
import androidx.compose.material.icons.rounded.TurnSlightLeft
import androidx.compose.material.icons.rounded.TurnSlightRight
import androidx.compose.material.icons.rounded.UTurnLeft
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
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bordocar.Drive
import com.bordocar.MapRoute
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
    fun set(ctx: Context, on: Boolean) { enabled = on; Prefs(ctx).put("sport", on) }
    /** Shows the preview; with [ctx], also asks for a route near you so the preview's turn shows on the map inside the circle. */
    fun preview(ctx: Context? = null) {
        if (ctx != null) try { JunctionPreview.prepare(ctx) } catch (e: Exception) { }
        previewFrom = System.currentTimeMillis()
    }
    /** For how long the preview shows its sample turn (longer with the map, to see it come closer). */
    const val SAMPLE_S = 7f
    const val SAMPLE_MAP_S = 10f
    fun previewing(now: Long) = previewFrom > 0L && now - previewFrom in 0 until PREVIEW_MS
    /** Called once a second with the speed in km/h. */
    fun update(kmh: Int) {
        if (kmh >= ON_KMH) fast = true else if (kmh < OFF_KMH) fast = false
        // the average speed of each minute, for the bars (the last 6 minutes)
        val m = System.currentTimeMillis() / 60_000L
        val b = buckets.getOrPut(m) { longArrayOf(0L, 0L) }
        b[0] += kmh.toLong(); b[1]++
        buckets.keys.filter { it < m - 5 }.forEach { buckets.remove(it) }
    }
    private val buckets = java.util.TreeMap<Long, LongArray>()
    /** Average km/h of the last 6 minutes, oldest first (null when there's nothing for that minute); the last one is now. */
    fun minutes(now: Long): List<Int?> {
        val m = now / 60_000L
        return (5 downTo 0).map { k -> buckets[m - k]?.let { if (it[1] > 0) (it[0] / it[1]).toInt() else null } }
    }
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
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val onCar = LocalOnCar.current
    val mapOk = MapRoute.enabled(ctx)
    val sample = preview && !realRoute && t < (if (mapOk) Sport.SAMPLE_MAP_S else Sport.SAMPLE_S)
    val route = realRoute || sample
    // (the preview with the map: a drive along a route near you up to its first turn)
    val sim = if (sample && mapOk) JunctionPreview.simAt(t.toDouble()) else null
    val turnTitle = if (sim != null) "${Math.round(sim.metres / 10.0) * 10} m" else if (sample) "${(800 - t * 60).toInt().coerceAtLeast(100) / 10 * 10} m" else NavNotice.title
    val turnText = if (sim != null) sim.street else if (sample) "Vire à direita · exemplo" else NavNotice.text
    val turnSub = if (sample) "Chegada 18:05 · 21 km" else NavNotice.sub
    // the map of the next turn inside the circle, once it's 500 m away or less
    val here = sim?.here ?: Drive.pos
    val mapHd = sim?.heading ?: Drive.heading
    val (tDist, tRest) = NavNotice.split(turnTitle, turnText)
    val jm = if (sim != null) sim.metres else tDist?.let { turnMetres(it) }
    val jKind = if (sim != null) sim.kind else if (sample) "direita" else maneuver(NavNotice.title + " " + NavNotice.text)
    if (here != null && realRoute && !preview) MapRoute.check(ctx.applicationContext, here)
    if (here != null && realRoute && !preview && onCar && jm != null && jm <= 700.0)
        MapRoute.requestNear(ctx.applicationContext, here, mapHd, jm, jKind, NavNotice.key + NavNotice.text)
    val jinfo = if (mapOk && here != null && jm != null && jm <= 500.0 && ((realRoute && (onCar || preview)) || sim != null))
        junctionInfo(here, jm, jKind, mapHd) else null

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
            // the lights stay as they are above the limit: it's the limit's circle that blinks
            Leds(kmh, false, true, Modifier.fillMaxWidth().height(20.dp).padding(horizontal = 12.dp))
            Row(Modifier.fillMaxWidth().weight(1f).padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                // speed (as big as it fits), the limit of the road, the top speed of the trip, and the bars of the last minutes
                val lim = if (preview) 80 else com.bordocar.Limits.kmh
                val over = lim != null && kmh > lim
                val blinkOn = (now / 500L) % 2L == 0L
                BoxWithConstraints(Modifier.weight(0.3f).fillMaxHeight().padding(start = 12.dp)) {
                    val digits = "$kmh".length.coerceAtLeast(2)
                    val big = minOf(maxHeight.value * 0.52f, maxWidth.value * (if (lim != null) 0.8f else 0.95f) / (digits * 0.56f)).coerceIn(40f, 260f)
                    val colH = maxHeight.value
                    Column(Modifier.fillMaxSize()) {
                        Text("VELOCIDADE", color = Grey, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                        Text("$kmh", color = if (over) Color(0xFFFF5A4A) else Color.White, fontSize = big.sp, lineHeight = (big * 0.98f).sp, fontWeight = FontWeight.ExtraBold,
                            fontStyle = FontStyle.Italic, maxLines = 1, letterSpacing = (-big * 0.03f).sp)
                        val mx = if (preview) 112 else Drive.tripMax
                        Text(buildString { append("km/h"); if (mx > 0) append(" · MÁX $mx") }, color = Soft, fontSize = (big * 0.1f).coerceIn(14f, 22f).sp, maxLines = 1)
                        Spacer(Modifier.weight(1f))
                        val mins = if (preview) listOf(76, 88, 93, 108, 96, kmh) else Sport.minutes(now)
                        Bars(mins, Modifier.fillMaxWidth().height((colH * 0.25f).coerceIn(64f, 150f).dp).padding(bottom = 4.dp))
                    }
                    // above the limit the circle blinks (red / white)
                    val flash = over && blinkOn
                    if (lim != null) Box(Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 4.dp).size((colH * 0.11f).coerceIn(40f, 64f).dp)
                        .clip(androidx.compose.foundation.shape.CircleShape).background(if (flash) Red else Color.White).border((colH * 0.012f).coerceIn(4f, 7f).dp, Red, androidx.compose.foundation.shape.CircleShape),
                        contentAlignment = Alignment.Center) {
                        Text("$lim", color = if (flash) Color.White else Color(0xFF111111), fontSize = (colH * 0.04f).coerceIn(15f, 24f).sp, fontWeight = FontWeight.ExtraBold)
                    }
                }
                // the compass ring, with the next turn inside it
                Column(Modifier.weight(0.4f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                    BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        val d = minOf(maxWidth, maxHeight)
                        // close to the turn: the map seen from above in the middle of the circle (route in red), the ring around it
                        if (jinfo != null && here != null) JunctionMap(now, jinfo, "night", here, mapHd,
                            Modifier.size(d * HOLE).clip(androidx.compose.foundation.shape.CircleShape), textureView = true,
                            carAt = 0.80, turnAt = 0.28, routeColor = "#FF4A3A", caseColor = "#7A1016")
                        Ring(if (jinfo != null) mapHd else hd, route, Modifier.size(d), hole = jinfo != null)
                        if (jinfo == null) Column(Modifier.size(d * 0.7f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            if (route) {
                                val ic = if (sample) null else NavNotice.icon
                                val isz = d.value * 0.26f
                                if (ic != null) Image(ic.asImageBitmap(), null, colorFilter = ColorFilter.tint(Color.White), modifier = Modifier.size(isz.dp))
                                else Icon(Icons.Rounded.TurnRight, null, tint = Color.White, modifier = Modifier.size(isz.dp))
                                // the distance stays put; only the rest scrolls when it's long
                                val (dist, rest) = NavNotice.split(turnTitle, turnText)
                                val bigT = (d.value * 0.115f).coerceIn(18f, 54f)
                                Text(dist ?: rest, color = Color.White, fontSize = bigT.sp, fontWeight = FontWeight.ExtraBold, fontStyle = FontStyle.Italic, maxLines = 1,
                                    modifier = if (dist != null) Modifier else Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 2000, repeatDelayMillis = 2000, velocity = 40.dp))
                                if (dist != null && rest.isNotBlank()) androidx.compose.runtime.key(rest) {
                                    Text(rest, color = Color(0xFFFFD0C8), fontSize = (d.value * 0.055f).coerceIn(13f, 22f).sp, maxLines = 1,
                                        modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 2000, repeatDelayMillis = 2000, velocity = 40.dp))
                                }
                            } else {
                                Text(if (hd != null) cardinalOf(hd) else "–", color = Color.White, fontSize = (d.value * 0.2f).coerceIn(24f, 90f).sp, fontWeight = FontWeight.ExtraBold, fontStyle = FontStyle.Italic)
                                Text(if (hd != null) "${Math.round(hd) % 360}°" else "aguardando o GPS", color = Soft, fontSize = (d.value * 0.06f).coerceIn(13f, 24f).sp)
                            }
                        }
                    }
                }
                // the route, this trip and today side by side, the g-force
                BoxWithConstraints(Modifier.weight(0.3f).fillMaxHeight().padding(end = 12.dp)) {
                    val gsz = (maxHeight.value * 0.48f).coerceAtMost(maxWidth.value * 0.7f)
                    val g2 = minOf(maxHeight.value * 0.4f, maxWidth.value * 0.36f)
                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.End) {
                        if (route && turnSub.isNotBlank()) Stat("ROTA", turnSub, null, small = true)
                        val cur = Drive.current()
                        val tripKm = if (preview && cur == null) 48.3 else (cur?.first ?: 0.0) / 1000.0
                        val tripMin = if (preview && cur == null) 38 else ((cur?.second ?: 0L) / 60L).toInt()
                        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                            Stat("ESTA VIAGEM", km(tripKm), if (tripMin > 0) (if (tripMin < 60) "$tripMin min" else "${tripMin / 60}h ${"%02d".format(tripMin % 60)}") else null)
                            Stat("KM HOJE", km(if (preview && cur == null) 16.7 else Drive.today().first / 1000.0), null)
                        }
                        val gx = if (preview) (kotlin.math.sin(now / 900.0) * 0.3).toFloat() else Drive.gLat
                        val gy = if (preview) (kotlin.math.cos(now / 1300.0) * 0.2).toFloat() else Drive.gLon
                        if (jinfo != null) {
                            // the map's turn: the arrow of where to turn and its caption, next to a smaller g-force
                            val side = when (jKind) { "direita", "leve-direita" -> "direita"; "esquerda", "leve-esquerda" -> "esquerda"; else -> null }
                            val title = when {
                                jinfo.nth != null && side != null -> "${jinfo.nth}ª à $side"
                                jKind == "direita" -> "Vire à direita"
                                jKind == "esquerda" -> "Vire à esquerda"
                                jKind == "leve-direita" -> "Leve à direita"
                                jKind == "leve-esquerda" -> "Leve à esquerda"
                                jKind == "retorno" -> "Faça o retorno"
                                jKind == "rotatoria" -> "Rotatória"
                                jKind == "frente" -> "Siga em frente"
                                else -> tRest
                            }
                            val street = jinfo.street.ifBlank { if (title != tRest) tRest else "" }
                            Row(Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp), verticalAlignment = Alignment.Bottom) {
                                TurnBlock(jKind, if (sim != null || sample) null else NavNotice.icon, title, tDist, street,
                                    Modifier.weight(1f).fillMaxHeight().padding(end = 10.dp))
                                GMeter(gx, gy, Modifier.size(g2.dp))
                            }
                        } else {
                            Spacer(Modifier.weight(1f))
                            GMeter(gx, gy, Modifier.size(gsz.dp))
                        }
                    }
                }
            }
        }
    }
}

/** How much of the circle the map of the turn takes (its diameter, as a part of the circle's). */
private const val HOLE = 0.74f

/** The turn of the map: the arrow of where to turn in a red square, "3ª à esquerda", the distance and the street. */
@Composable
private fun TurnBlock(kind: String?, icon: android.graphics.Bitmap?, title: String, dist: String?, street: String, modifier: Modifier) {
    BoxWithConstraints(modifier) {
        val w = maxWidth.value
        val box = minOf(w * 0.45f, maxHeight.value * 0.42f, 90f).coerceAtLeast(36f)
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
            Box(Modifier.size(box.dp).clip(RoundedCornerShape((box * 0.2f).dp)).background(Red), contentAlignment = Alignment.Center) {
                val iv = turnIcon(kind)
                if (iv == null && icon != null) Image(icon.asImageBitmap(), null, colorFilter = ColorFilter.tint(Color.White), modifier = Modifier.size((box * 0.78f).dp))
                else Icon(iv ?: Icons.Rounded.TurnRight, null, tint = Color.White, modifier = Modifier.size((box * 0.8f).dp))
            }
            val tf = (w * 0.12f).coerceIn(15f, 30f)
            androidx.compose.runtime.key(title) {
                Text(title, color = Color.White, fontSize = tf.sp, lineHeight = (tf * 1.1f).sp, fontWeight = FontWeight.ExtraBold, fontStyle = FontStyle.Italic,
                    maxLines = 1, modifier = Modifier.padding(top = 6.dp).basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 2000, repeatDelayMillis = 2000, velocity = 40.dp))
            }
            if (dist != null) {
                val df = (w * 0.16f).coerceIn(18f, 40f)
                Text(dist, color = Orange, fontSize = df.sp, lineHeight = (df * 1.05f).sp, fontWeight = FontWeight.ExtraBold, fontStyle = FontStyle.Italic, maxLines = 1)
            }
            if (street.isNotBlank()) androidx.compose.runtime.key(street) {
                Text(street, color = Color(0xFFFFD0C8), fontSize = (w * 0.065f).coerceIn(12f, 16f).sp, maxLines = 1,
                    modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 2000, repeatDelayMillis = 2000, velocity = 40.dp))
            }
        }
    }
}

private fun turnIcon(kind: String?): androidx.compose.ui.graphics.vector.ImageVector? = when (kind) {
    "direita" -> Icons.Rounded.TurnRight
    "esquerda" -> Icons.Rounded.TurnLeft
    "leve-direita" -> Icons.Rounded.TurnSlightRight
    "leve-esquerda" -> Icons.Rounded.TurnSlightLeft
    "retorno" -> Icons.Rounded.UTurnLeft
    "rotatoria" -> Icons.Rounded.RoundaboutRight
    "frente" -> Icons.Rounded.Straight
    else -> null
}

/** The g-force: the dot goes to the side of the turn, up when braking and down when speeding up (full circle = 0,5 G). */
@Composable
private fun GMeter(gx: Float, gy: Float, modifier: Modifier) {
    val g = kotlin.math.sqrt(gx * gx + gy * gy)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            val r = size.minDimension / 2f; val c = Offset(size.width / 2f, size.height / 2f)
            drawCircle(Color(0xFF0E0E11), r, c); drawCircle(Color(0xFF333338), r - 1.5f, c, style = Stroke(3f))
            drawCircle(Color(0xFF2A2A30), r * 0.66f, c, style = Stroke(1.5f)); drawCircle(Color(0xFF2A2A30), r * 0.33f, c, style = Stroke(1.5f))
            drawLine(Color(0xFF2A2A30), Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1.5f); drawLine(Color(0xFF2A2A30), Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1.5f)
            val k = r * 0.9f / 0.5f
            val p = Offset(c.x + (gx * k).coerceIn(-r * 0.9f, r * 0.9f), c.y - (-gy * k).coerceIn(-r * 0.9f, r * 0.9f))
            drawCircle(RedHi.copy(alpha = 0.3f), r * 0.16f, p); drawCircle(RedHi, r * 0.1f, p)
        }
        Text(String.format(BRL, "%.1f G", g), color = Soft, fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

/** One bar per minute (the last 5 and this one), with its average speed on top. */
@Composable
private fun Bars(mins: List<Int?>, modifier: Modifier) {
    BoxWithConstraints(modifier) {
        val h = maxHeight.value
        val num = (h * 0.15f).coerceIn(12f, 20f)
        val cap = (h * 0.1f).coerceIn(10f, 14f)
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
            mins.forEachIndexed { i, v ->
                val nowBar = i == mins.lastIndex
                Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                    Text(v?.toString() ?: "–", color = Color.White, fontSize = num.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                    val f = if (v == null) 0.04f else ((v - 30f) / 110f).coerceIn(0.06f, 1f)
                    Box(Modifier.padding(top = 2.dp).fillMaxWidth().height(((h - num * 1.4f - cap * 1.6f - 8f) * f).coerceAtLeast(3f).dp).clip(RoundedCornerShape(5.dp))
                        .background(if (nowBar) RedHi else Color(0xFF7A2A2A)))
                    Text(if (nowBar) "agora" else "-${mins.lastIndex - i} min", color = Grey, fontSize = cap.sp, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
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
private fun Leds(kmh: Int, over: Boolean, blinkOn: Boolean, modifier: Modifier) {
    Canvas(modifier) {
        val n = 24; val gap = size.width * 0.006f
        val w = (size.width - gap * (n - 1)) / n
        // above the road's limit: all red, blinking
        val lit = if (over) (if (blinkOn) n else 0) else (kmh / 140f * n).toInt().coerceIn(0, n)
        for (i in 0 until n) {
            val c = if (over) Red else when { i < n * 0.38f -> Color(0xFF2FCB8E); i < n * 0.67f -> Color(0xFFFFC24E); else -> Red }
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
private fun Ring(hd: Double?, route: Boolean, modifier: Modifier, hole: Boolean = false) {
    val m = androidx.compose.ui.text.rememberTextMeasurer()
    Canvas(modifier) {
        val r = size.minDimension / 2f
        val c = Offset(size.width / 2f, size.height / 2f)
        if (hole) {
            // the map shows in the middle: only the band of the ring is filled (it also covers the map's corners), with a dark edge
            val hr = r * HOLE
            drawCircle(Color(0xFF1A0A0C), (hr + r * 0.97f) / 2f, c, style = Stroke(r * 0.97f - hr + 2f))
            drawCircle(Color.Black.copy(alpha = 0.55f), hr + r * 0.015f, c, style = Stroke(r * 0.04f))
        } else
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
        if (!hole) for ((t, deg) in listOf("N" to 0.0, "L" to 90.0, "S" to 180.0, "O" to 270.0)) {
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
