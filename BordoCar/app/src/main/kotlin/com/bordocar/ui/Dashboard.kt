package com.bordocar.ui

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LocalGasStation
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Work
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bordocar.Drive
import com.bordocar.LL
import com.bordocar.Limits
import com.bordocar.NavNotice
import com.bordocar.NowPlaying
import com.bordocar.Weather
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

// ---- the look: deep navy, glassy cards with a soft blue edge ----
// (all from the chosen theme, so changing it repaints the dashboard)
private val P get() = Themes.cur
private val Bg get() = Brush.radialGradient(P.bg, center = Offset(300f, 0f), radius = 2200f)
private val CardBg get() = Brush.linearGradient(P.card)
private val Edge get() = P.edge
private val Txt = Color(0xFFEEF3FF)
private val Sub get() = P.sub
private val Lbl get() = P.label
private val Track get() = P.track
private val Blue get() = P.accent
private val Lime = Color(0xFFB6F35A)
private val BR = Locale("pt", "BR")

/** What the dashboard's buttons do (they need Android Auto's own screens, or hand over to Waze / Google Maps). */
interface DashActions {
    fun home()
    fun work()
    fun fuel()
    fun favourites()
    fun search()
}

/**
 * Taps on the car screen: Android Auto passes a tap's position, and it's matched against where the cards and buttons are drawn.
 */
object CarTaps {
    private val regions = java.util.LinkedHashMap<String, Pair<androidx.compose.ui.geometry.Rect, () -> Unit>>()
    fun set(id: String, r: androidx.compose.ui.geometry.Rect, a: () -> Unit) { synchronized(regions) { regions.remove(id); regions[id] = r to a } }
    fun remove(id: String) { synchronized(regions) { regions.remove(id) } }
    fun hit(x: Float, y: Float): Boolean {
        val a = synchronized(regions) { regions.values.toList().asReversed().firstOrNull { it.first.contains(Offset(x, y)) }?.second } ?: return false
        a(); return true
    }
}

@Composable
fun Modifier.carTap(id: String, action: () -> Unit): Modifier {
    val act = androidx.compose.runtime.rememberUpdatedState(action)
    androidx.compose.runtime.DisposableEffect(id) { onDispose { CarTaps.remove(id) } }
    return this.then(Modifier.onGloballyPositioned { CarTaps.set(id, it.boundsInRoot()) { act.value() } })
}

@Composable
private fun Card(modifier: Modifier, content: @Composable BoxScope.() -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Box(modifier.clip(shape).background(CardBg).border(1.dp, Edge, shape)) { content() }
}

@Composable
private fun Label(text: String, modifier: Modifier = Modifier) =
    Text(text, color = Lbl, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp, maxLines = 1, modifier = modifier)

/**
 * The whole car screen: the music and (with Waze or Google Maps navigating) the next turn or (otherwise) shortcuts on the left;
 * speed, compass and the km driven on the right; the time, the phone's battery and the weather along the bottom.
 * [pad] keeps everything inside the part of the screen Android Auto leaves free.
 */
@Composable
fun Dashboard(ctx: Context, actions: DashActions, padL: Dp, padT: Dp, padR: Dp, padB: Dp) {
    remember { Themes.load(ctx); Sport.load(ctx); Modes.load(ctx); 0 }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            try { NowPlaying.refresh(ctx) } catch (e: Exception) { }
            Sport.update(Math.round((Drive.speed * 3.6).coerceAtLeast(0.0)).toInt())
            delay(if (Sport.previewing(now)) 250L else 1000L)
        }
    }
    // the weather and the town where you are (every 20 minutes)
    var wx by remember { mutableStateOf<Weather.Now?>(null) }
    var city by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            val p: LL? = Drive.pos ?: lastKnown(ctx)
            if (p != null) {
                val w = withContext(Dispatchers.IO) { Weather.fetch(p) }
                withContext(Dispatchers.IO) {
                    try {
                        @Suppress("DEPRECATION")
                        val a = android.location.Geocoder(ctx, BR).getFromLocation(p.lat, p.lon, 1)?.firstOrNull()
                        (a?.locality ?: a?.subAdminArea)?.let { city = it }
                    } catch (e: Exception) { }
                }
                if (w != null) { wx = w; delay(20 * 60_000L) } else delay(60_000L)
            } else { Weather.lastNote = "sem localização ainda"; delay(5_000L) }
        }
    }
    // the phone's battery (every 30 seconds)
    var bat by remember { mutableIntStateOf(-1) }
    var charging by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            try {
                val i: Intent? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val lvl = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val sc = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
                bat = if (lvl >= 0 && sc > 0) lvl * 100 / sc else -1
                val st = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
                charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
            } catch (e: Exception) { }
            delay(30_000L)
        }
    }
    val navigating = NavNotice.active(now)
    if (Modes.cur == "esportivo" || Sport.showing(now)) { SportDash(now, bat, charging, wx, city, padL, padT, padR, padB); return }
    if (Modes.cur == "musica") { MusicDash(now, bat, charging, wx, city, padL, padT, padR, padB); return }
    if (Modes.cur == "estrada") { RoadDash(now, bat, charging, wx, city, padL, padT, padR, padB); return }

    Box(Modifier.fillMaxSize().background(Bg)) {
        Column(Modifier.fillMaxSize().padding(start = padL, top = padT, end = padR, bottom = padB), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // the time, battery and weather along the top: Android Auto's search and settings buttons sit in its right end
            BottomBar(now, bat, charging, wx, city, Modifier.fillMaxWidth().height(64.dp))
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(0.46f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    MediaCard(Modifier.fillMaxWidth().weight(if (navigating) 1f else 1.25f))
                    if (navigating) TurnCard(Modifier.fillMaxWidth().weight(1f))
                    else Shortcuts(actions, Modifier.fillMaxWidth().weight(1f))
                }
                Column(Modifier.weight(0.54f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth().weight(1.25f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SpeedCard(Modifier.weight(1f).fillMaxHeight())
                        CompassCard(Modifier.weight(1f).fillMaxHeight())
                    }
                    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TodayCard(Modifier.weight(1f).fillMaxHeight())
                        WeekCard(Modifier.weight(1f).fillMaxHeight())
                    }
                }
            }
        }
    }
}

@android.annotation.SuppressLint("MissingPermission")
private fun lastKnown(ctx: Context): LL? = try {
    val m = ctx.getSystemService(android.location.LocationManager::class.java)
    (m.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER) ?: m.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER))?.let { LL(it.latitude, it.longitude) }
} catch (e: Exception) { null }

// ---------------------------------------------------------------- music

private fun mmss(ms: Long): String { val s = (ms / 1000L).coerceAtLeast(0L); return "%d:%02d".format(s / 60, s % 60) }

@Composable
private fun MediaCard(modifier: Modifier) {
    Card(modifier) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(18.dp)) {
            val h = maxHeight.value
            if (!NowPlaying.allowed) {
                Column(Modifier.align(Alignment.CenterStart)) {
                    Label("MÚSICA")
                    Text("Para mostrar a música e a próxima curva, abra o Bordo Car no celular e toque em \"Permitir acesso às notificações\".",
                        color = Sub, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp))
                }
                return@BoxWithConstraints
            }
            val art = (h * 0.92f).coerceIn(60f, 180f)
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(art.dp).clip(RoundedCornerShape(20.dp)).background(Brush.linearGradient(listOf(Color(0xFFD2335A), Color(0xFF5B1A3C)))), contentAlignment = Alignment.Center) {
                    val bmp = NowPlaying.art
                    if (bmp != null) Image(bmp.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    else Icon(Icons.Rounded.MusicNote, null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size((art * 0.4f).dp))
                }
                Column(Modifier.weight(1f).padding(start = 22.dp), verticalArrangement = Arrangement.Center) {
                    // a long title slides along slowly (after a pause) instead of being cut off
                    androidx.compose.runtime.key(NowPlaying.title) {
                        Text(NowPlaying.title.ifBlank { "Nada tocando" }, color = Txt, fontSize = (h * 0.2f).coerceIn(18f, 30f).sp, fontWeight = FontWeight.ExtraBold, maxLines = 1,
                            modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 2500, repeatDelayMillis = 2500, velocity = 40.dp))
                    }
                    if (NowPlaying.artist.isNotBlank()) Text(NowPlaying.artist, color = Sub, fontSize = (h * 0.13f).coerceIn(13f, 18f).sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                    val d = NowPlaying.duration
                    if (d > 0) {
                        val pos = NowPlaying.position.coerceIn(0L, d)
                        Box(Modifier.padding(top = (h * 0.09f).coerceIn(6f, 18f).dp).fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(Track)) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth((pos.toFloat() / d).coerceIn(0f, 1f)).background(Brush.horizontalGradient(Blue)))
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                            Text(mmss(pos), color = Sub, fontSize = 13.sp)
                            Spacer(Modifier.weight(1f))
                            Text("faltam " + mmss(d - pos), color = Txt, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- next turn (Waze / Google Maps)

@Composable
private fun TurnCard(modifier: Modifier, music: Boolean = false) {
    val shape = RoundedCornerShape(22.dp)
    Box(modifier.clip(shape).background(Brush.linearGradient(P.turn)).border(1.dp, P.turnEdge, shape)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(NavNotice.app.uppercase(BR), color = Color(0xFF04122B), fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(P.badge).padding(horizontal = 6.dp, vertical = 1.dp))
                Text("PRÓXIMA CURVA", color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp, modifier = Modifier.padding(start = 8.dp))
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val h = maxHeight.value
                val big = (h * 0.34f).coerceIn(18f, 40f)
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    val ic = NavNotice.icon
                    val isz = (h * 0.8f).coerceIn(36f, 92f)
                    if (ic != null) Image(ic.asImageBitmap(), null, colorFilter = ColorFilter.tint(Color.White), modifier = Modifier.size(isz.dp))
                    else Icon(Icons.Rounded.Navigation, null, tint = Color.White, modifier = Modifier.size(isz.dp))
                    Column(Modifier.padding(start = 16.dp)) {
                        // the distance stays put (big); only the street / instruction scrolls when it's long
                        val (dist, rest) = NavNotice.split(NavNotice.title, NavNotice.text)
                        if (dist != null) Text(dist, color = Color.White, fontSize = big.sp, lineHeight = (big * 1.12f).sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                        val line = if (dist != null) rest else NavNotice.title
                        if (line.isNotBlank()) androidx.compose.runtime.key(line) {
                            Text(line, color = Color.White, fontSize = (if (dist != null) (big * 0.6f).coerceIn(14f, 24f) else big).sp, fontWeight = if (dist != null) FontWeight.SemiBold else FontWeight.ExtraBold,
                                lineHeight = ((if (dist != null) (big * 0.6f).coerceIn(14f, 24f) else big) * 1.15f).sp, maxLines = 1,
                                modifier = Modifier.padding(top = 2.dp).basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 2000, repeatDelayMillis = 2000, velocity = 40.dp))
                        }
                        if (dist == null && NavNotice.text.isNotBlank()) androidx.compose.runtime.key(NavNotice.text) {
                            Text(NavNotice.text, color = Color.White, fontSize = (big * 0.5f).coerceIn(12f, 20f).sp, maxLines = 1,
                                modifier = Modifier.padding(top = 2.dp).basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 2000, repeatDelayMillis = 2000, velocity = 40.dp))
                        }
                        if (NavNotice.sub.isNotBlank() && h > 90) Text(NavNotice.sub, color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
            // (Estrada) the song playing, small, under the turn
            if (music && NowPlaying.title.isNotBlank()) androidx.compose.runtime.key(NowPlaying.title) {
                Text("♪ " + NowPlaying.title + (if (NowPlaying.artist.isNotBlank()) " — " + NowPlaying.artist else ""), color = Color.White.copy(alpha = 0.85f), fontSize = 16.sp, maxLines = 1,
                    modifier = Modifier.padding(top = 4.dp).basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 2500, repeatDelayMillis = 2500, velocity = 40.dp))
            }
        }
    }
}

// ---------------------------------------------------------------- shortcuts

@Composable
private fun Shortcuts(actions: DashActions, modifier: Modifier) {
    Card(modifier) {
        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp)) {
            Label("PARA ONDE?", Modifier.carTap("sc-label") { actions.search() })
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val h = maxHeight.value
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Shortcut(Icons.Rounded.Home, Color(0xFF2E6BFF), "Casa", "sc-home", h, Modifier.weight(1f)) { actions.home() }
                Shortcut(Icons.Rounded.Work, Color(0xFFF29900), "Trabalho", "sc-work", h, Modifier.weight(1f)) { actions.work() }
                Shortcut(Icons.Rounded.Star, Color(0xFFE0A800), "Favoritos", "sc-favs", h, Modifier.weight(1f)) { actions.favourites() }
                Shortcut(Icons.Rounded.LocalGasStation, Color(0xFF12A4B4), "Postos", "sc-fuel", h, Modifier.weight(1f)) { actions.fuel() }
                Shortcut(Icons.Rounded.Search, Color(0xFF8E5CF0), "Buscar", "sc-search", h, Modifier.weight(1f)) { actions.search() }
            }
            }
        }
    }
}

@Composable
private fun Shortcut(icon: ImageVector, color: Color, label: String, id: String, h: Float, modifier: Modifier, action: () -> Unit) {
    // with little height the name goes, and the icon fills the space
    val withName = h >= 84f
    val sz = (if (withName) h - 30f else h - 6f).coerceIn(34f, 62f)
    Column(modifier.carTap(id, action), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(sz.dp).clip(RoundedCornerShape((sz * 0.29f).dp)).background(color), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size((sz * 0.54f).dp))
        }
        if (withName) Text(label, color = Txt, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
    }
}

// ---------------------------------------------------------------- speed, compass, km

@Composable
private fun SpeedCard(modifier: Modifier) {
    val kmh = Math.round((Drive.speed * 3.6).coerceAtLeast(0.0)).toInt()
    val lim = Limits.kmh
    val over = lim != null && kmh > lim + 3
    Card(modifier) {
        Label("VELOCIDADE", Modifier.align(Alignment.TopCenter).padding(top = 12.dp))
        BoxWithConstraints(Modifier.fillMaxSize().padding(top = 28.dp, bottom = 8.dp)) {
            val d = minOf(maxWidth, maxHeight)
            Canvas(Modifier.size(d).align(Alignment.Center)) {
                val sw = size.minDimension * 0.06f
                val tl = Offset(sw / 2, sw / 2); val sz = Size(size.width - sw, size.height - sw)
                drawArc(Track, 135f, 270f, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
                val f = (kmh / 160f).coerceIn(0f, 1f)
                val cols = if (over) listOf(Color(0xFFFF8A4D), Color(0xFFFF3B3B)) else Blue
                if (f > 0.003f) drawArc(Brush.horizontalGradient(cols), 135f, 270f * f, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
            }
            val big = (d.value * 0.34f).coerceIn(18f, 64f)
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("$kmh", color = if (over) Color(0xFFFF6B5A) else Txt, fontSize = big.sp, fontWeight = FontWeight.ExtraBold, lineHeight = (big * 1.1f).sp)
                Text("km/h", color = Sub, fontSize = (big * 0.24f).coerceIn(10f, 14f).sp)
            }
        }

    }
}

private fun cardinal(deg: Double): String = listOf("N", "NE", "L", "SE", "S", "SO", "O", "NO")[(((deg % 360) + 360) % 360 / 45.0 + 0.5).toInt() % 8]

@Composable
private fun CompassCard(modifier: Modifier) {
    val hd = Drive.heading
    Card(modifier) {
        Label("BÚSSOLA", Modifier.align(Alignment.TopCenter).padding(top = 12.dp))
        BoxWithConstraints(Modifier.fillMaxSize().padding(top = 34.dp, bottom = 34.dp)) {
            val d = minOf(maxWidth, maxHeight)
            val n = androidx.compose.ui.text.rememberTextMeasurer()
            val tickHi = P.accent.last(); val tickLo = P.tick; val letterCol = P.sub
            Canvas(Modifier.size(d).align(Alignment.Center)) {
                val r = size.minDimension / 2f
                val c = Offset(size.width / 2f, size.height / 2f)
                drawCircle(Track, r - 2f, c, style = Stroke(3f))
                // the dial turns so that north points north (the arrow is the way you're going)
                rotate(-(hd ?: 0.0).toFloat(), c) {
                    for (i in 0 until 24) {
                        val a = Math.toRadians(i * 15.0)
                        val major = i % 6 == 0
                        val r0 = r * (if (major) 0.80f else 0.86f)
                        drawLine(if (major) tickHi else tickLo,
                            Offset(c.x + (r0 * Math.sin(a)).toFloat(), c.y - (r0 * Math.cos(a)).toFloat()),
                            Offset(c.x + (r * 0.93f * Math.sin(a)).toFloat(), c.y - (r * 0.93f * Math.cos(a)).toFloat()), strokeWidth = if (major) 3f else 1.5f)
                    }
                    val letters = listOf("N" to 0.0, "L" to 90.0, "S" to 180.0, "O" to 270.0)
                    for ((t, deg) in letters) {
                        val a = Math.toRadians(deg)
                        val lay = n.measure(t, androidx.compose.ui.text.TextStyle(color = if (t == "N") Color(0xFFFF5A5A) else letterCol, fontSize = 15.sp, fontWeight = FontWeight.Bold))
                        val p = Offset(c.x + (r * 0.62f * Math.sin(a)).toFloat() - lay.size.width / 2f, c.y - (r * 0.62f * Math.cos(a)).toFloat() - lay.size.height / 2f)
                        drawText(lay, topLeft = p)
                    }
                }
                val arrow = Path().apply {
                    moveTo(c.x, c.y - r * 0.42f); lineTo(c.x + r * 0.16f, c.y + r * 0.18f); lineTo(c.x, c.y + r * 0.06f); lineTo(c.x - r * 0.16f, c.y + r * 0.18f); close()
                }
                drawPath(arrow, tickHi)
            }
        }
        Text(if (hd != null) buildAnnotatedString {
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Txt)); append(cardinal(hd)); pop()
                append(" · ${Math.round(hd)}°")
            } else buildAnnotatedString { append("aguardando o GPS") },
            color = P.soft, fontSize = 14.sp, maxLines = 1, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp))
    }
}

@Composable
private fun TodayCard(modifier: Modifier) {
    @Suppress("UNUSED_VARIABLE") val t = Drive.tick
    val (m, secs) = Drive.today()
    val km = m / 1000.0
    val mins = (secs / 60L).toInt()
    val n = Drive.todayTrips()
    Card(modifier) {
      BoxWithConstraints(Modifier.fillMaxSize()) {
        val h = maxHeight.value
        val roomy = h >= 120f                        // room for the line of small numbers underneath
        val big = (h * (if (roomy) 0.26f else 0.40f)).coerceIn(20f, 56f)
        val small = (h * 0.15f).coerceIn(17f, 26f)
        val cap = (h * 0.085f).coerceIn(12f, 16f)
        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Label("KM HOJE")
            Text(buildAnnotatedString {
                append(if (km < 100) String.format(BR, "%.1f", km) else "${Math.round(km)}")
                pushStyle(SpanStyle(fontSize = (big * 0.34f).sp, color = Sub, fontWeight = FontWeight.Normal)); append(" km"); pop()
            }, color = Txt, fontSize = big.sp, lineHeight = (big * 1.12f).sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
            if (roomy) {
                Box(Modifier.padding(top = 4.dp, bottom = 6.dp).fillMaxWidth(0.72f).height(1.dp).background(Edge))
                val drive = if (mins < 60) "$mins min" else "${mins / 60}h ${"%02d".format(mins % 60)}"
                val avg = if (secs >= 60) "${Math.round(km / (secs / 3600.0))} km/h" else "–"
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    for ((v, l) in listOf(drive to "DIRIGINDO", avg to "MÉDIA", "$n" to if (n == 1) "VIAGEM" else "VIAGENS"))
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(v, color = Txt, fontSize = small.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text(l, color = Lbl, fontSize = cap.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        }
                }
            } else Text(if (n == 0) "nenhuma viagem hoje" else "$n ${if (n == 1) "viagem" else "viagens"} · ${if (mins < 60) "$mins min" else "${mins / 60}h ${"%02d".format(mins % 60)}"}",
                color = Sub, fontSize = 13.sp, maxLines = 1)
        }
      }
    }
}

@Composable
private fun WeekCard(modifier: Modifier) {
    @Suppress("UNUSED_VARIABLE") val t = Drive.tick
    val days = Drive.week()
    val today = (java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7
    val total = days.sum() / 1000.0
    Card(modifier) {
      BoxWithConstraints(Modifier.fillMaxSize()) {
        val big = (maxHeight.value * 0.20f).coerceIn(15f, 30f)
        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 10.dp)) {
            Label("KM DA SEMANA")
            Text(buildAnnotatedString {
                append(if (total < 100) String.format(BR, "%.1f", total) else "${Math.round(total)}")
                pushStyle(SpanStyle(fontSize = 13.sp, color = Sub, fontWeight = FontWeight.Normal)); append(" km"); pop()
            }, color = Txt, fontSize = big.sp, lineHeight = (big * 1.15f).sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 2.dp))
            val max = (days.maxOrNull() ?: 0.0).coerceAtLeast(1000.0)
            val barCol = P.bar; val trackCol = Track
            Canvas(Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp)) {
                val gap = size.width * 0.03f
                val bw = (size.width - gap * 6) / 7f
                for (i in 0 until 7) {
                    val x = i * (bw + gap)
                    drawRoundRect(trackCol, Offset(x, 0f), Size(bw, size.height), CornerRadius(bw * 0.18f))
                    val f = (days[i] / max).toFloat().coerceIn(0f, 1f)
                    if (f > 0f) {
                        val bh = (size.height * f).coerceAtLeast(size.height * 0.08f)
                        drawRoundRect(if (i == today) Lime else barCol, Offset(x, size.height - bh), Size(bw, bh), CornerRadius(bw * 0.18f))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                for ((i, l) in listOf("S", "T", "Q", "Q", "S", "S", "D").withIndex())
                    Text(l, color = if (i == today) Lime else Sub, fontSize = 11.sp, fontWeight = if (i == today) FontWeight.Bold else FontWeight.Normal,
                        textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            }
        }
      }
    }
}

// ---------------------------------------------------------------- top bar: time, battery, weather

@Composable
private fun BottomBar(now: Long, bat: Int, charging: Boolean, wx: Weather.Now?, city: String, modifier: Modifier) {
    val d = java.util.Date(now)
    Card(modifier) {
        Row(Modifier.fillMaxSize().padding(start = 22.dp, end = 170.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(java.text.SimpleDateFormat("HH:mm", BR).format(d), color = Txt, fontSize = 40.sp, fontWeight = FontWeight.ExtraBold)
            Column(Modifier.padding(start = 16.dp)) {
                Text(java.text.SimpleDateFormat("EEEE", BR).format(d).replaceFirstChar { it.uppercase() }, color = Sub, fontSize = 15.sp)
                Text(java.text.SimpleDateFormat("d 'de' MMMM", BR).format(d), color = Sub, fontSize = 15.sp)
            }
            if (bat >= 0) {
                Box(Modifier.padding(start = 30.dp).width(1.dp).height(40.dp).background(Edge))
                val col = if (bat < 15) Color(0xFFFF4D4D) else if (bat < 30) Color(0xFFFFA63D) else Lime
                Canvas(Modifier.padding(start = 26.dp).size(34.dp, 17.dp)) {
                    val w = size.width; val h = size.height
                    drawRoundRect(P.soft, Offset(1f, 1f), Size(w * 0.86f - 2f, h - 2f), CornerRadius(h * 0.22f), style = Stroke(2.2f))
                    drawRoundRect(P.soft, Offset(w * 0.88f, h * 0.3f), Size(w * 0.09f, h * 0.4f), CornerRadius(2f))
                    drawRoundRect(col, Offset(4f, 4f), Size((w * 0.86f - 8f) * (bat / 100f), h - 8f), CornerRadius(h * 0.12f))
                }
                Text("$bat%", color = Txt, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp))
                Text(if (charging) "⚡ carregando" else "celular", color = Sub, fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp))
            }
            Spacer(Modifier.weight(1f))
            if (wx != null) {
                // all on one line: ⛅ 37°  Timon · Poucas nuvens
                Text(Weather.emoji(wx.code), fontSize = 30.sp)
                Text("${Math.round(wx.tempC)}°", color = Txt, fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 10.dp))
                Text(listOf(city, Weather.text(wx.code)).filter { it.isNotBlank() }.joinToString(" · "), color = P.soft, fontSize = 18.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp))
            }
        }
    }
}


// ================================================================ Música: the car on the road in the middle, speed on the left, the record on the right

@Composable
private fun MusicDash(now: Long, bat: Int, charging: Boolean, wx: Weather.Now?, city: String, padL: Dp, padT: Dp, padR: Dp, padB: Dp) {
    Box(Modifier.fillMaxSize().background(Bg)) {
        Column(Modifier.fillMaxSize().padding(start = padL, top = padT, end = padR, bottom = padB), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            BottomBar(now, bat, charging, wx, city, Modifier.fillMaxWidth().height(64.dp))
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val dial = minOf(maxHeight * 0.92f, maxWidth * 0.3f)
                // the road and the car, behind everything
                RoadAndCar(Modifier.fillMaxSize())
                RoundSpeed(Modifier.size(dial).align(Alignment.CenterStart).padding(start = 6.dp))
                Vinyl(now, Modifier.size(dial).align(Alignment.CenterEnd).padding(end = 6.dp))
                if (NavNotice.active(now)) TurnChip(Modifier.align(Alignment.TopCenter).padding(top = 2.dp).fillMaxWidth(0.36f))
                // the song under the car
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth(0.34f).clip(RoundedCornerShape(16.dp)).background(P.card.last()).border(1.dp, Edge, RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    androidx.compose.runtime.key(NowPlaying.title) {
                        Text(NowPlaying.title.ifBlank { if (NowPlaying.allowed) "Nada tocando" else "Permita o acesso às notificações no celular" }, color = Txt, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1,
                            modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 2500, repeatDelayMillis = 2500, velocity = 40.dp))
                    }
                    if (NowPlaying.artist.isNotBlank()) Text(NowPlaying.artist, color = Sub, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** The next turn as a strip: the distance stays put, the street scrolls. */
@Composable
private fun TurnChip(modifier: Modifier) {
    val (dist, rest) = NavNotice.split(NavNotice.title, NavNotice.text)
    Row(modifier.clip(RoundedCornerShape(16.dp)).background(Brush.horizontalGradient(P.turn)).border(1.dp, P.turnEdge, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        val ic = NavNotice.icon
        if (ic != null) Image(ic.asImageBitmap(), null, colorFilter = ColorFilter.tint(Color.White), modifier = Modifier.size(34.dp))
        else Icon(Icons.Rounded.Navigation, null, tint = Color.White, modifier = Modifier.size(30.dp))
        if (dist != null) Text(dist, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, modifier = Modifier.padding(start = 10.dp))
        val line = if (dist != null) rest else NavNotice.title
        if (line.isNotBlank()) androidx.compose.runtime.key(line) {
            Text(line, color = Color.White.copy(alpha = 0.9f), fontSize = 17.sp, maxLines = 1,
                modifier = Modifier.padding(start = 10.dp).basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 2000, repeatDelayMillis = 2000, velocity = 40.dp))
        }
    }
}

@Composable
private fun RoundSpeed(modifier: Modifier) {
    val kmh = Math.round((Drive.speed * 3.6).coerceAtLeast(0.0)).toInt()
    val bg = P.card.last(); val track = Track; val grad = Blue; val edge = Edge
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val d = minOf(maxWidth, maxHeight)
        Canvas(Modifier.size(d)) {
            val r = size.minDimension / 2f; val c = Offset(size.width / 2f, size.height / 2f)
            drawCircle(bg, r, c); drawCircle(edge, r - 1f, c, style = Stroke(2f))
            val sw = r * 0.075f; val rr = r - sw * 1.6f
            val tl = Offset(c.x - rr, c.y - rr); val sz = Size(rr * 2, rr * 2)
            drawArc(track, 135f, 270f, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
            val f = (kmh / 160f).coerceIn(0f, 1f)
            if (f > 0.003f) drawArc(Brush.horizontalGradient(grad), 135f, 270f * f, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$kmh", color = Txt, fontSize = (d.value * 0.3f).sp, fontWeight = FontWeight.ExtraBold, lineHeight = (d.value * 0.32f).sp)
            Text("km/h", color = Sub, fontSize = (d.value * 0.06f).coerceIn(11f, 18f).sp)
        }
    }
}

/** The record: the album cover as a spinning disc, the ring around it fills as the song goes, the time in its middle. */
@Composable
private fun Vinyl(now: Long, modifier: Modifier) {
    val d = NowPlaying.duration
    val pos = NowPlaying.position.coerceIn(0L, if (d > 0) d else Long.MAX_VALUE)
    // turns slowly while the song plays and stops where it is when it's paused
    val rot = remember { androidx.compose.animation.core.Animatable(0f) }
    val playing = NowPlaying.playing
    LaunchedEffect(playing) {
        while (playing) {
            rot.animateTo(rot.value + 360f, androidx.compose.animation.core.tween(12_000, easing = androidx.compose.animation.core.LinearEasing))
            rot.snapTo(rot.value % 360f)
        }
    }
    val bg = P.card.last(); val track = Track; val grad = Blue; val edge = Edge
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val size = minOf(maxWidth, maxHeight)
        Canvas(Modifier.size(size)) {
            val r = this.size.minDimension / 2f; val c = Offset(this.size.width / 2f, this.size.height / 2f)
            drawCircle(bg, r, c); drawCircle(edge, r - 1f, c, style = Stroke(2f))
            val sw = r * 0.065f; val rr = r - sw * 1.5f
            val tl = Offset(c.x - rr, c.y - rr); val sz = Size(rr * 2, rr * 2)
            drawArc(track, -90f, 360f, false, tl, sz, style = Stroke(sw))
            if (d > 0) drawArc(Brush.sweepGradient(grad + grad.first(), c), -90f, 360f * (pos.toFloat() / d), false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
        }
        val disc = size * 0.76f
        Box(Modifier.size(disc).rotate(rot.value).clip(androidx.compose.foundation.shape.CircleShape).background(Color(0xFF111111))) {
            val bmp = NowPlaying.art
            if (bmp != null) Image(bmp.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFFD2335A), Color(0xFF5B1A3C)))))
            // the grooves
            Canvas(Modifier.fillMaxSize()) {
                val r = this.size.minDimension / 2f; val c = Offset(this.size.width / 2f, this.size.height / 2f)
                var k = r * 0.97f
                while (k > r * 0.5f) { drawCircle(Color.Black.copy(alpha = 0.22f), k, c, style = Stroke(1.2f)); k -= r * 0.055f }
                drawCircle(Color.Black.copy(alpha = 0.45f), r - 1.5f, c, style = Stroke(3f))
            }
        }
        // the label in the middle: the time
        Box(Modifier.size(disc * 0.5f).clip(androidx.compose.foundation.shape.CircleShape).background(Color(0xDD0B1222)).border(1.dp, Color(0x33FFFFFF), androidx.compose.foundation.shape.CircleShape),
            contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (d > 0) mmss(pos) else "♪", color = Color.White, fontSize = (size.value * 0.12f).coerceIn(16f, 40f).sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                if (d > 0) Text("faltam " + mmss(d - pos), color = Color(0xFFC9D6F2), fontSize = (size.value * 0.05f).coerceIn(10f, 16f).sp, maxLines = 1)
            }
        }
    }
}

/** A hatchback seen from behind, on a road with lines of light running to the horizon. */
@Composable
private fun RoadAndCar(modifier: Modifier) {
    val accent = Blue.last()
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val cx = w / 2f
        // road lines
        val horizon = h * 0.12f
        drawLine(accent.copy(alpha = 0.5f), Offset(cx - w * 0.26f, h), Offset(cx - w * 0.06f, horizon), strokeWidth = 2.5f)
        drawLine(accent.copy(alpha = 0.5f), Offset(cx + w * 0.26f, h), Offset(cx + w * 0.06f, horizon), strokeWidth = 2.5f)
        drawLine(accent.copy(alpha = 0.35f), Offset(cx, h), Offset(cx, horizon), strokeWidth = 2.5f,
            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(18f, 16f)))
        // glow under the car
        val s = minOf(w / 520f, h / 300f)
        val cy = h * 0.47f
        drawOval(Brush.radialGradient(listOf(accent.copy(alpha = 0.35f), Color.Transparent), Offset(cx, cy + 40 * s), 220 * s), Offset(cx - 220 * s, cy - 50 * s), Size(440 * s, 180 * s))
        fun X(x: Float) = cx + x * s
        fun Y(y: Float) = cy + y * s
        drawOval(Color.Black.copy(alpha = 0.55f), Offset(X(-120f), Y(84f)), Size(240 * s, 24 * s))
        val body = Path().apply {
            moveTo(X(-112f), Y(70f)); quadraticBezierTo(X(-116f), Y(40f), X(-96f), Y(30f)); lineTo(X(-70f), Y(-18f))
            quadraticBezierTo(X(-62f), Y(-34f), X(-40f), Y(-36f)); lineTo(X(40f), Y(-36f)); quadraticBezierTo(X(62f), Y(-34f), X(70f), Y(-18f))
            lineTo(X(96f), Y(30f)); quadraticBezierTo(X(116f), Y(40f), X(112f), Y(70f)); lineTo(X(112f), Y(86f))
            quadraticBezierTo(X(112f), Y(94f), X(102f), Y(94f)); lineTo(X(-102f), Y(94f)); quadraticBezierTo(X(-112f), Y(94f), X(-112f), Y(86f)); close()
        }
        drawPath(body, Brush.verticalGradient(listOf(Color(0xFFE9EEF6), Color(0xFFAAB6C8), Color(0xFF55627A)), Y(-36f), Y(94f)))
        val glass = Path().apply {
            moveTo(X(-62f), Y(-14f)); quadraticBezierTo(X(-56f), Y(-26f), X(-38f), Y(-27f)); lineTo(X(38f), Y(-27f))
            quadraticBezierTo(X(56f), Y(-26f), X(62f), Y(-14f)); lineTo(X(76f), Y(20f)); lineTo(X(-76f), Y(20f)); close()
        }
        drawPath(glass, Color(0xFF0D1526)); drawPath(glass, Color(0xFF3A5A8A), style = Stroke(1.5f * s))
        drawRoundRect(Brush.horizontalGradient(listOf(Color(0xFFFF2A2A), Color(0xFFFF7A5A), Color(0xFFFF2A2A)), X(-96f), X(96f)), Offset(X(-96f), Y(34f)), Size(192 * s, 10 * s), CornerRadius(5 * s))
        drawRoundRect(Color(0xFF0B1020), Offset(X(-30f), Y(58f)), Size(60 * s, 16 * s), CornerRadius(3 * s))
        drawRoundRect(Color(0xFF0B1020), Offset(X(-104f), Y(80f)), Size(208 * s, 8 * s), CornerRadius(4 * s))
    }
}

// ================================================================ Estrada: arrival and what's left, the next turn big, the trip in numbers

@Composable
private fun RoadDash(now: Long, bat: Int, charging: Boolean, wx: Weather.Now?, city: String, padL: Dp, padT: Dp, padR: Dp, padB: Dp) {
    @Suppress("UNUSED_VARIABLE") val tk = Drive.tick
    val navigating = NavNotice.active(now)
    val left = if (navigating) Remaining.parse(NavNotice.sub, NavNotice.text, NavNotice.title) else null
    val cur = Drive.current()
    val (todayM, todayS) = Drive.today()
    val doneM = cur?.first ?: todayM
    val doneS = cur?.second ?: todayS
    Box(Modifier.fillMaxSize().background(Bg)) {
        Column(Modifier.fillMaxSize().padding(start = padL, top = padT, end = padR, bottom = padB), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            BottomBar(now, bat, charging, wx, city, Modifier.fillMaxWidth().height(64.dp))
            // arrival
            Card(Modifier.fillMaxWidth().height(76.dp)) {
                Row(Modifier.fillMaxSize().padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (left != null && (left.eta != null || left.km != null || left.min != null)) {
                        Column { Label("CHEGADA"); Text(left.eta ?: "–", color = Txt, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 34.sp) }
                        val leftM = (left.km ?: 0.0) * 1000.0
                        val f = if (leftM > 0) (doneM / (doneM + leftM)).toFloat().coerceIn(0f, 1f) else 0f
                        Box(Modifier.weight(1f).padding(horizontal = 22.dp).height(12.dp).clip(RoundedCornerShape(6.dp)).background(Track)) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth(f).background(Brush.horizontalGradient(Blue)))
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Label("FALTAM")
                            Text(listOfNotNull(left.km?.let { fmtKm(it) }, left.min?.let { fmtMin(it) }).joinToString(" · ").ifBlank { "–" },
                                color = Txt, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 34.sp, maxLines = 1)
                        }
                    } else {
                        Text(if (navigating) "Chegada: o ${NavNotice.app} não mandou a hora de chegada" else "Sem rota · escolha o destino no Waze ou no Google Maps",
                            color = Sub, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (navigating) TurnCard(Modifier.weight(0.46f).fillMaxHeight(), music = true) else MediaCard(Modifier.weight(0.46f).fillMaxHeight())
                Column(Modifier.weight(0.54f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val kmh = Math.round((Drive.speed * 3.6).coerceAtLeast(0.0)).toInt()
                    val avg = if (doneS >= 60) Math.round(doneM / 1000.0 / (doneS / 3600.0)).toInt() else null
                    val mins = (doneS / 60L).toInt()
                    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Stat("VELOCIDADE", "$kmh", "km/h", null, Modifier.weight(1f).fillMaxHeight())
                        Stat("MÉDIA DA VIAGEM", avg?.toString() ?: "–", if (avg != null) "km/h" else "", null, Modifier.weight(1f).fillMaxHeight())
                    }
                    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val km = doneM / 1000.0
                        Stat(if (cur != null) "RODADO NA VIAGEM" else "RODADO HOJE", if (km < 100) String.format(BR, "%.1f", km) else "${Math.round(km)}", "km", null, Modifier.weight(1f).fillMaxHeight())
                        Stat("DIRIGINDO HÁ", fmtMin(mins), "", if (mins >= 120) "☕ hora de uma pausa?" else null, Modifier.weight(1f).fillMaxHeight())
                    }
                }
            }
        }
    }
}

private fun fmtKm(v: Double) = (if (v < 100) String.format(BR, "%.1f", v).removeSuffix(",0") else "${Math.round(v)}") + " km"
private fun fmtMin(m: Int) = if (m < 60) "$m min" else "${m / 60}h ${"%02d".format(m % 60)}"

@Composable
private fun Stat(label: String, value: String, unit: String, note: String?, modifier: Modifier) {
    Card(modifier) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 10.dp)) {
            val big = (maxHeight.value * 0.42f).coerceIn(20f, 52f)
            Column(Modifier.align(Alignment.CenterStart)) {
                Label(label)
                Text(buildAnnotatedString {
                    append(value)
                    if (unit.isNotBlank()) { pushStyle(SpanStyle(fontSize = (big * 0.36f).sp, color = Sub, fontWeight = FontWeight.Normal)); append(" $unit"); pop() }
                }, color = Txt, fontSize = big.sp, lineHeight = (big * 1.12f).sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                if (note != null) Text(note, color = Color(0xFFFFC24E), fontSize = 14.sp, maxLines = 1)
            }
        }
    }
}

/** What Waze / Google Maps say is left of the route ("18:05", "21 km", "35 min"), read from their navigation notice. */
private object Remaining {
    class Left(val eta: String?, val km: Double?, val min: Int?)
    fun parse(sub: String, text: String, title: String): Left? {
        // the turn's own distance is in the title / text; what's left of the route is usually in the small line
        val all = listOf(sub, text).filter { it.isNotBlank() }
        if (all.isEmpty()) return null
        val src = if (sub.isNotBlank()) sub else text
        val eta = Regex("\\b([01]?\\d|2[0-3])[:h]([0-5]\\d)\\b").find(src)?.let { "${it.groupValues[1]}:${it.groupValues[2]}" }
        val km = Regex("(\\d+(?:[.,]\\d+)?)\\s*km\\b", RegexOption.IGNORE_CASE).find(src)?.groupValues?.get(1)?.replace(",", ".")?.toDoubleOrNull()
        val h = Regex("(\\d+)\\s*h(?![:\\d])", RegexOption.IGNORE_CASE).find(src)?.groupValues?.get(1)?.toIntOrNull()
        val m = Regex("(\\d+)\\s*min", RegexOption.IGNORE_CASE).find(src)?.groupValues?.get(1)?.toIntOrNull()
        val min = if (h != null || m != null) (h ?: 0) * 60 + (m ?: 0) else null
        if (eta == null && km == null && min == null) return null
        return Left(eta, if (src == text && title.isNotBlank() && sub.isBlank()) null else km, min)
    }
}
