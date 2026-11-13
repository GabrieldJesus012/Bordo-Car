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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Straight
import androidx.compose.material.icons.rounded.UTurnLeft
import androidx.compose.material.icons.rounded.TurnSlightLeft
import androidx.compose.material.icons.rounded.TurnSlightRight
import androidx.compose.material.icons.rounded.TurnLeft
import androidx.compose.material.icons.rounded.TurnRight
import androidx.compose.material.icons.rounded.North
import androidx.compose.material.icons.rounded.SportsScore
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Pause
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
import androidx.compose.ui.draw.clipToBounds
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
import com.bordocar.MapRoute
import com.bordocar.dist
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
    remember { Themes.load(ctx); Sport.load(ctx); Modes.load(ctx); CarLook.load(ctx); 0 }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            try { NowPlaying.refresh(ctx) } catch (e: Exception) { }
            Sport.update(Math.round((Drive.speed * 3.6).coerceAtLeast(0.0)).toInt())
            delay(if (Sport.previewing(now)) 250L else if (Modes.cur == "esportivo" || Sport.showing(now) || JunctionPreview.active(now)) 500L else 1000L)
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
    // the preview of the map of the turn shows Estrada, whatever the mode
    if (JunctionPreview.active(now)) { RoadDashLook(now, bat, charging, wx, city, padL, padT, padR, padB); return }
    if (RouteView.show != null && Modes.cur == "normal" && !Sport.showing(now)) { RouteMapScreen(now, padL, padT, padR, padB); return }
    if (Modes.cur == "esportivo" || Sport.showing(now)) {
        if (CarLook.on) CarType { SportDash(now, bat, charging, wx, city, padL, padT, padR, padB) } else SportDash(now, bat, charging, wx, city, padL, padT, padR, padB)
        return
    }
    if (Modes.cur == "musica") {
        if (CarLook.on) CarType { MusicDash(now, bat, charging, wx, city, padL, padT, padR, padB) } else MusicDash(now, bat, charging, wx, city, padL, padT, padR, padB)
        return
    }
    if (Modes.cur == "estrada" || JunctionPreview.active(now)) { RoadDashLook(now, bat, charging, wx, city, padL, padT, padR, padB); return }
    // "Adaptar ao carro": the Normal dashboard flat on the graphite of the car's screen
    if (CarLook.on) { NormalAdapted(now, bat, charging, wx, city, actions, navigating, padL, padT, padR, padB); return }

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
                        // a tap shows the routes driven today / this week on a map
                        TodayCard(Modifier.weight(1f).fillMaxHeight().carTap("km-hoje") { RouteView.show = "hoje" })
                        WeekCard(Modifier.weight(1f).fillMaxHeight().carTap("km-semana") { RouteView.show = "semana" })
                    }
                }
            }
        }
    }
}

/** Estrada stays as it is even when adapted to the car (thin text over the bright sky didn't read, the black edges looked odd). */
@Composable
private fun RoadDashLook(now: Long, bat: Int, charging: Boolean, wx: Weather.Now?, city: String, padL: Dp, padT: Dp, padR: Dp, padB: Dp) =
    RoadDash(now, bat, charging, wx, city, padL, padT, padR, padB)

/** The arrival of the route on now (for the sporty dashboard): the time, and what's left. */
internal fun arrivalNow(now: Long): Arrival? {
    if (!NavNotice.active(now)) return null
    val left = Remaining.parse(NavNotice.sub, NavNotice.text, NavNotice.title)
    val cur = Drive.current(); val (tm, ts) = Drive.today()
    return arrival(now, true, left, cur?.first ?: tm, cur?.second ?: ts)
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
private fun BottomBar(now: Long, bat: Int, charging: Boolean, wx: Weather.Now?, city: String, modifier: Modifier, bg: Brush? = null, plain: Boolean = false, endPad: Dp = 170.dp) {
    val d = java.util.Date(now)
    val shape = RoundedCornerShape(22.dp)
    // plain: no box, the text straight over the picture (Estrada, Música), like the sporty one
    Box(if (plain) modifier else modifier.clip(shape).then(if (bg != null) Modifier.background(bg) else Modifier.background(CardBg)).border(1.dp, Edge, shape)) {
        Row(Modifier.fillMaxSize().padding(start = 22.dp, end = endPad), verticalAlignment = Alignment.CenterVertically) {
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
    // (adapted to the car: on the graphite of the car's screen, white dials, no temperature, the song with no box)
    val adapt = CarLook.on
    Box(Modifier.fillMaxSize().then(if (adapt) Modifier else Modifier.background(Bg))) {
        if (adapt) GraphiteBg(Modifier.fillMaxSize())
        // the track and the car over the whole screen, behind everything (the car stays in the middle of the part under the bar)
        RoadAndCar(Modifier.fillMaxSize(), top = padT + 74.dp, adapt = adapt)
        if (adapt) {
            Box(Modifier.fillMaxWidth().height(padT + 80.dp).background(Brush.verticalGradient(listOf(CarLook.Graphite, CarLook.Graphite.copy(alpha = 0f)))))
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(padB + 34.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black))))
        }
        Column(Modifier.fillMaxSize().padding(start = padL, top = padT, end = padR, bottom = padB), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // the weather all the way to the right (Android Auto's buttons cover it only while they show)
            if (adapt) CarTopLine(now, bat, charging, wx, city, Modifier.fillMaxWidth().height(64.dp).padding(start = 16.dp), clock = true, endPad = 22.dp)
            else BottomBar(now, bat, charging, wx, city, Modifier.fillMaxWidth().height(64.dp), plain = true, endPad = 22.dp)
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val dial = minOf(maxHeight * 0.92f, maxWidth * 0.3f)
                GaugeSpeed(wx, Modifier.size(dial).align(Alignment.CenterStart).padding(start = 6.dp), adapt)
                Vinyl(now, Modifier.size(dial).align(Alignment.CenterEnd).padding(end = 6.dp), adapt)
                if (NavNotice.active(now)) TurnChip(Modifier.align(Alignment.TopCenter).padding(top = 2.dp).fillMaxWidth(0.36f))
                // the song under the car, in a box of fixed width (between the two dials): a long name scrolls inside it
                val cardW = minOf(maxWidth - dial * 2 - 32.dp, maxWidth * 0.34f)
                Column(Modifier.align(Alignment.BottomCenter).width(cardW).clipToBounds()
                    .then(if (adapt) Modifier else Modifier.clip(RoundedCornerShape(16.dp)).background(P.card.last()).border(1.dp, Edge, RoundedCornerShape(16.dp)))
                    .padding(horizontal = 14.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    androidx.compose.runtime.key(NowPlaying.title) {
                        Text(NowPlaying.title.ifBlank { if (NowPlaying.allowed) "Nada tocando" else "Permita o acesso às notificações no celular" }, color = Txt, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1,
                            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 2500, repeatDelayMillis = 2500, velocity = 40.dp))
                    }
                    if (NowPlaying.artist.isNotBlank()) Text(NowPlaying.artist, color = Sub, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
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

/** The speedometer of the Música mode: a numbered scale (0 to 180), the band lit up to the speed, the number in a glowing circle. */
@Composable
private fun GaugeSpeed(wx: Weather.Now?, modifier: Modifier, adapt: Boolean = false) {
    val kmh = Math.round((Drive.speed * 3.6).coerceAtLeast(0.0)).toInt()
    // (adapted to the car: white on the graphite)
    val bg = if (adapt) Color(0xD9141518) else P.card.last(); val track = if (adapt) Color(0xFF3A3B40) else Track
    val grad = if (adapt) listOf(Color(0xFFBDBDC4), Color.White) else Blue; val edge = if (adapt) Color(0x33FFFFFF) else Edge
    val sub = if (adapt) Color(0xFFC4C4CC) else Sub; val hi = Txt
    val m = androidx.compose.ui.text.rememberTextMeasurer()
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val d = minOf(maxWidth, maxHeight)
        Canvas(Modifier.size(d)) {
            val r = size.minDimension / 2f; val c = Offset(size.width / 2f, size.height / 2f)
            drawCircle(bg, r, c); drawCircle(edge, r - 1f, c, style = Stroke(2f))
            val sw = r * 0.085f; val rr = r - sw * 0.9f
            val tl = Offset(c.x - rr, c.y - rr); val sz = Size(rr * 2, rr * 2)
            // the band in segments
            for (i in 0 until 36) drawArc(track, 135f + i * 7.5f, 5.5f, false, tl, sz, style = Stroke(sw))
            val f = (kmh / 180f).coerceIn(0f, 1f)
            if (f > 0.003f) {
                val br = Brush.sweepGradient(listOf(grad.first(), grad.last(), grad.first()), c)
                drawArc(grad.last().copy(alpha = 0.25f), 135f, 270f * f, false, Offset(tl.x - sw * 0.4f, tl.y - sw * 0.4f), Size(sz.width + sw * 0.8f, sz.height + sw * 0.8f), style = Stroke(sw * 1.8f))
                drawArc(br, 135f, 270f * f, false, tl, sz, style = Stroke(sw * 1.15f))
            }
            // ticks every 10, numbers every 20
            val fs = (r * 0.085f / density).coerceAtLeast(8f)
            for (v in 0..180 step 10) {
                val a = Math.toRadians((135.0 + 270.0 * v / 180.0))
                val big = v % 20 == 0
                val r0 = r * 0.80f; val r1 = r * (if (big) 0.71f else 0.75f)
                drawLine(if (big) hi else track, Offset(c.x + (r0 * Math.cos(a)).toFloat(), c.y + (r0 * Math.sin(a)).toFloat()),
                    Offset(c.x + (r1 * Math.cos(a)).toFloat(), c.y + (r1 * Math.sin(a)).toFloat()), strokeWidth = if (big) r * 0.018f else r * 0.01f)
                if (big) {
                    val lay = m.measure("$v", androidx.compose.ui.text.TextStyle(color = sub, fontSize = fs.sp, fontWeight = FontWeight.SemiBold))
                    val rt = r * 0.6f
                    drawText(lay, topLeft = Offset(c.x + (rt * Math.cos(a)).toFloat() - lay.size.width / 2f, c.y + (rt * Math.sin(a)).toFloat() - lay.size.height / 2f))
                }
            }
            // the glowing circle in the middle
            val ri = r * 0.42f
            drawCircle(grad.last().copy(alpha = 0.18f), ri + r * 0.06f, c)
            drawCircle(if (adapt) Color(0xFF0E0F11) else Color(0xFF070D1A), ri, c)
            drawCircle(Brush.sweepGradient(listOf(grad.first(), grad.last(), grad.first()), c), ri, c, style = Stroke(r * 0.025f))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$kmh", color = Txt, fontSize = (d.value * 0.2f).sp, fontWeight = FontWeight.ExtraBold, lineHeight = (d.value * 0.22f).sp)
            Text("km/h", color = Sub, fontSize = (d.value * 0.05f).coerceIn(10f, 16f).sp)
        }
        if (wx != null && !adapt) Text("${Weather.emoji(wx.code)} ${Math.round(wx.tempC)}°", color = Txt, fontSize = (d.value * 0.06f).coerceIn(12f, 20f).sp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = (d.value * 0.08f).dp))
    }
}

/** The record: the album cover as a spinning disc with the back / play-pause / forward buttons; the ring fills as the song goes. */
@Composable
private fun Vinyl(now: Long, modifier: Modifier, adapt: Boolean = false) {
    val ctx = androidx.compose.ui.platform.LocalContext.current.applicationContext
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
    val bg = if (adapt) Color(0xD9141518) else P.card.last(); val track = if (adapt) Color(0xFF3A3B40) else Track
    val grad = if (adapt) listOf(Color(0xFFBDBDC4), Color.White) else Blue; val edge = if (adapt) Color(0x33FFFFFF) else Edge
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
        val disc = size * 0.56f
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
        // play / pause in the middle
        val bsz = disc * 0.42f
        Box(Modifier.size(bsz).clip(androidx.compose.foundation.shape.CircleShape).background(if (adapt) Color(0xCC141518) else Color(0xCC0B1222)).border(1.5.dp, Color(0x66FFFFFF), androidx.compose.foundation.shape.CircleShape)
            .carTap("mu-play") { NowPlaying.control(ctx, "play") }, contentAlignment = Alignment.Center) {
            Icon(if (NowPlaying.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(bsz * 0.62f))
        }
        // back and forward on the sides
        val ssz = size * 0.15f
        for ((what, al) in listOf("prev" to Alignment.CenterStart, "next" to Alignment.CenterEnd)) {
            Box(Modifier.align(al).padding(horizontal = size * 0.06f).size(ssz).clip(androidx.compose.foundation.shape.CircleShape).background(if (adapt) Color(0xFF1C1D20) else Color(0xFF13203A))
                .border(1.dp, if (adapt) Color(0x33FFFFFF) else Color(0xFF2C3E63), androidx.compose.foundation.shape.CircleShape).carTap("mu-$what") { NowPlaying.control(ctx, what) }, contentAlignment = Alignment.Center) {
                Icon(if (what == "prev") Icons.Rounded.SkipPrevious else Icons.Rounded.SkipNext, null, tint = Color.White, modifier = Modifier.size(ssz * 0.62f))
            }
        }
        if (d > 0) Text(mmss(pos) + " · faltam " + mmss(d - pos), color = Color(0xFFC9D6F2), fontSize = (size.value * 0.05f).coerceIn(11f, 17f).sp, maxLines = 1,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = (size.value * 0.12f).dp))
    }
}

/** A Fórmula 1 car seen from behind, in the colour of the album cover, on a track with red and white kerbs. */
@Composable
private fun RoadAndCar(modifier: Modifier, top: Dp = 0.dp, adapt: Boolean = false) {
    val accent = Blue.last()
    val carCol = Themes.coverAccent(NowPlaying.art)
    val blink = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(Unit) { while (true) { blink.animateTo(0.35f, androidx.compose.animation.core.tween(500)); blink.animateTo(1f, androidx.compose.animation.core.tween(500)) } }
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val topPx = top.toPx()
        val cx = w / 2f
        val horizon = h * 0.1f
        // where each edge of the track is at height y (perspective: wide at the bottom, narrow at the horizon)
        fun half(y: Float) = w * 0.03f + (w * 0.33f - w * 0.03f) * ((y - horizon) / (h - horizon)).coerceIn(0f, 1f)
        // the glow on the horizon
        drawOval(Brush.radialGradient(listOf(carCol.copy(alpha = 0.35f), Color.Transparent), Offset(cx, horizon), w * 0.25f), Offset(cx - w * 0.25f, horizon - h * 0.2f), Size(w * 0.5f, h * 0.4f))
        // asphalt
        val road = Path().apply { moveTo(cx - half(horizon), horizon); lineTo(cx + half(horizon), horizon); lineTo(cx + half(h), h); lineTo(cx - half(h), h); close() }
        drawPath(road, Brush.verticalGradient(if (adapt) listOf(Color(0xFF1E1F23), Color(0xFF2A2B30), Color(0xFF141518)) else listOf(Color(0xFF0E1424), Color(0xFF1A2236)), horizon, h))
        // kerbs: red and white blocks along both edges, shorter towards the horizon
        var y = h; var i = 0
        while (y > horizon + 2f) {
            val step = ((y - horizon) / (h - horizon)).coerceIn(0.05f, 1f) * h * 0.09f
            val y2 = (y - step).coerceAtLeast(horizon)
            val kw0 = half(y) * 0.12f; val kw1 = half(y2) * 0.12f
            val col = if (i % 2 == 0) Color(0xFFE5262E) else Color(0xFFF2F2F2)
            for (sgn in listOf(-1f, 1f)) {
                val k = Path().apply {
                    moveTo(cx + sgn * half(y), y); lineTo(cx + sgn * (half(y) + kw0), y); lineTo(cx + sgn * (half(y2) + kw1), y2); lineTo(cx + sgn * half(y2), y2); close()
                }
                drawPath(k, col)
            }
            y = y2; i++
        }
        // neon edges and the dashed middle line
        if (!adapt) for (sgn in listOf(-1f, 1f)) {
            drawLine(carCol.copy(alpha = 0.35f), Offset(cx + sgn * half(h) * 0.9f, h), Offset(cx + sgn * half(horizon) * 0.9f, horizon), strokeWidth = 9f)
            drawLine(carCol, Offset(cx + sgn * half(h) * 0.9f, h), Offset(cx + sgn * half(horizon) * 0.9f, horizon), strokeWidth = 3f)
        }
        drawLine(if (adapt) Color.White.copy(alpha = 0.35f) else accent.copy(alpha = 0.7f), Offset(cx, h), Offset(cx, horizon), strokeWidth = 3f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(22f, 18f)))

        // the car (drawn on a 300 × 190 grid, centred)
        val s = minOf(w / 520f, (h - topPx) / 300f)
        val cy = topPx + (h - topPx) * 0.5f
        fun X(x: Float) = cx + x * s
        fun Y(y: Float) = cy + y * s
        fun rect(x: Float, y: Float, ww: Float, hh: Float, c: Color, rad: Float = 0f) = drawRoundRect(c, Offset(X(x), Y(y)), Size(ww * s, hh * s), CornerRadius(rad * s))
        drawOval(Brush.radialGradient(listOf(carCol.copy(alpha = 0.4f), Color.Transparent), Offset(cx, Y(40f)), 230 * s), Offset(X(-230f), Y(-40f)), Size(460 * s, 170 * s))
        drawOval(Color.Black.copy(alpha = 0.6f), Offset(X(-150f), Y(62f)), Size(300 * s, 24 * s))
        // rear tyres
        for (x in listOf(-138f, 86f)) {
            rect(x, -6f, 52f, 80f, Color(0xFF111111), 14f)
            rect(x + 4f, 4f, 44f, 6f, Color(0xFF2A2A2A), 3f)
            rect(x + 8f, 30f, 36f, 3f, if (adapt) Color(0xFF3A3A3A) else Color(0xFFFFD400))
            // (more real: the sidewalls)
            if (adapt) { rect(x + 2f, 56f, 48f, 3f, Color(0xFF2A2A2A)); rect(x, -6f, 6f, 80f, Color(0xFF1C1C1C), 3f) }
        }
        // suspension
        for (sg in listOf(-1f, 1f)) {
            drawLine(Color(0xFF333333), Offset(X(sg * 86f), Y(14f)), Offset(X(sg * 40f), Y(8f)), 4f * s)
            drawLine(Color(0xFF333333), Offset(X(sg * 86f), Y(44f)), Offset(X(sg * 46f), Y(36f)), 4f * s)
        }
        // body, with a lighter engine cover
        val body = Path().apply {
            moveTo(X(-80f), Y(46f)); quadraticBezierTo(X(-78f), Y(12f), X(-52f), Y(4f)); lineTo(X(-30f), Y(-22f))
            quadraticBezierTo(X(-16f), Y(-42f), X(0f), Y(-44f)); quadraticBezierTo(X(16f), Y(-42f), X(30f), Y(-22f)); lineTo(X(52f), Y(4f))
            quadraticBezierTo(X(78f), Y(12f), X(80f), Y(46f)); close()
        }
        val darkCol = carCol.copy(red = carCol.red * 0.55f, green = carCol.green * 0.55f, blue = carCol.blue * 0.55f)
        drawPath(body, if (adapt) Brush.horizontalGradient(listOf(darkCol, carCol, carCol, darkCol), X(-80f), X(80f))
            else Brush.verticalGradient(listOf(carCol, carCol.copy(red = carCol.red * 0.6f, green = carCol.green * 0.6f, blue = carCol.blue * 0.6f)), Y(-44f), Y(46f)))
        // (more real: the halo over the cockpit, seen from behind)
        if (adapt) drawArc(Color(0xFF1B1B1B), 200f, 140f, false, Offset(X(-46f), Y(-60f)), Size(92f * s, 70f * s), style = Stroke(6f * s, cap = StrokeCap.Round))
        val cover = Path().apply {
            moveTo(X(-30f), Y(-22f)); quadraticBezierTo(X(-16f), Y(-42f), X(0f), Y(-44f)); quadraticBezierTo(X(16f), Y(-42f), X(30f), Y(-22f))
            lineTo(X(18f), Y(-6f)); lineTo(X(-18f), Y(-6f)); close()
        }
        drawPath(cover, Color.White.copy(alpha = 0.85f))
        // shark fin, airbox
        rect(-3f, -72f, 6f, 28f, carCol)
        val airbox = Path().apply { moveTo(X(-14f), Y(-84f)); quadraticBezierTo(X(0f), Y(-98f), X(14f), Y(-84f)); lineTo(X(10f), Y(-72f)); lineTo(X(-10f), Y(-72f)); close() }
        drawPath(airbox, Color(0xFF1A1A1A))
        // rear wing
        rect(-92f, -70f, 184f, 12f, Color(0xFF161616), 3f)
        rect(-92f, -82f, 184f, 8f, carCol, 3f)
        rect(-98f, -92f, 9f, 62f, Color(0xFF1F1F1F), 2f); rect(89f, -92f, 9f, 62f, Color(0xFF1F1F1F), 2f)
        rect(-4f, -58f, 8f, 18f, Color(0xFF222222))
        // diffuser with its strakes
        val diff = Path().apply { moveTo(X(-80f), Y(46f)); lineTo(X(80f), Y(46f)); lineTo(X(70f), Y(70f)); lineTo(X(-70f), Y(70f)); close() }
        drawPath(diff, Color(0xFF141414))
        for (k in -60..60 step 20) drawLine(Color(0xFF2C2C2C), Offset(X(k.toFloat()), Y(48f)), Offset(X(k * 0.88f), Y(69f)), 2f * s)
        // the red rain light, blinking
        rect(-14f, 31f, 28f, 15f, Color(0xFFFF2A2A).copy(alpha = 0.3f * blink.value), 4f)
        rect(-10f, 34f, 20f, 9f, Color(0xFFFF2A2A).copy(alpha = blink.value), 2f)
    }
}

// ================================================================ Estrada: arrival and what's left, the next turn big, the trip in numbers

@Composable
private fun RoadDash(now: Long, bat: Int, charging: Boolean, wx: Weather.Now?, city: String, padL: Dp, padT: Dp, padR: Dp, padB: Dp) {
    @Suppress("UNUSED_VARIABLE") val tk = Drive.tick
    // "Ver prévia" of the map of the turn: a drive along a route near you, up to its first turn
    val pv = JunctionPreview.active(now)
    val sim = if (pv) JunctionPreview.sim(now) else null
    val navigating = NavNotice.active(now) || pv
    val left = if (navigating && !pv) Remaining.parse(NavNotice.sub, NavNotice.text, NavNotice.title) else null
    val cur = Drive.current()
    val (todayM, todayS) = Drive.today()
    val doneM = cur?.first ?: todayM
    val doneS = cur?.second ?: todayS
    // (in the preview: a made-up arrival, 4 minutes from now)
    val a = if (pv) Arrival(java.text.SimpleDateFormat("HH:mm", BR).format(java.util.Date(now + 4 * 60_000L)), "1,2 km · 4 min", 0.7f, 1.2)
        else arrival(now, navigating, left, doneM, doneS)
    val (dist, rest) = if (sim != null) ("${(Math.round(sim.metres / 10.0) * 10)} m" to sim.street)
        else if (navigating) NavNotice.split(NavNotice.title, NavNotice.text) else (null to "")
    val kind0 = if (sim != null) sim.kind else (maneuver(NavNotice.title + " " + NavNotice.text) ?: NavNotice.iconKind)
    val sky = skyAt(now, Drive.pos)
    val jctx = androidx.compose.ui.platform.LocalContext.current
    val onCar = LocalOnCar.current
    val here = sim?.here ?: Drive.pos
    val hd = sim?.heading ?: Drive.heading
    val jm = if (sim != null) sim.metres else dist?.let { turnMetres(it) }
    if (here != null && !pv) MapRoute.check(jctx.applicationContext, here)
    // a route started in Waze / Maps: Mapbox's way to just past the turn, to place it on the real streets and count them
    if (here != null && !pv && navigating && onCar && jm != null && jm <= 700.0)
        MapRoute.requestNear(jctx.applicationContext, here, hd, jm, kind0, NavNotice.key + NavNotice.text, rest)
    val jinfo = if (navigating && (onCar || pv) && here != null && MapRoute.enabled(jctx))
        junctionFor(here, jm, kind0, hd, sim?.turn, rest) else null
    // (a turn kept on the map after the notice moved on: its own way and distance)
    val kindNow = if (jinfo != null && (jinfo.held || kind0 == null)) jinfo.kind ?: kind0 else kind0
    val jdist = jinfo?.takeIf { it.held }?.let { "${Math.round(it.aheadM / 10.0) * 10} m" } ?: dist
    val jmNow = jinfo?.takeIf { it.held }?.aheadM ?: jm

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // ---- the left: the road (the sky of the hour), or the map of the turn from 500 m; the speed and the limit in its corner
        Box(Modifier.fillMaxHeight().fillMaxWidth(0.6f).padding(top = padT + 56.dp)) {
            if (jinfo != null && here != null) JunctionMap(now, jinfo, sky.preset, here, hd, Modifier.fillMaxSize(), textureView = !onCar, carAt = 0.78, turnAt = 0.28)
            else Highway(navigating && !pv, now, Modifier.fillMaxSize())
            // into the black of the panel on the right
            Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(56.dp).background(Brush.horizontalGradient(listOf(Color.Transparent, Color.Black))))
            Box(Modifier.align(Alignment.BottomStart).size(300.dp, 170.dp).background(Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent),
                center = Offset(0f, 520f), radius = 560f)))
            val kmh = Math.round((Drive.speed * 3.6).coerceAtLeast(0.0)).toInt()
            val avg = if (doneS >= 60) Math.round(doneM / 1000.0 / (doneS / 3600.0)).toInt() else null
            val lim = if (pv) 60 else com.bordocar.Limits.kmh
            Row(Modifier.align(Alignment.BottomStart).padding(start = padL + 16.dp, bottom = padB + 8.dp), verticalAlignment = Alignment.Bottom) {
                Column {
                    Text("VELOCIDADE", color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp, letterSpacing = 3.sp)
                    Text("$kmh", color = if (lim != null && kmh > lim + 3) Color(0xFFFF6B5A) else Color.White, fontSize = 64.sp, lineHeight = 66.sp, fontWeight = FontWeight.Light, maxLines = 1)
                    Text("km/h" + (if (avg != null) " · média $avg" else ""), color = Color.White.copy(alpha = 0.9f), fontSize = 13.sp, maxLines = 1)
                }
                if (lim != null) Box(Modifier.padding(start = 14.dp, bottom = 22.dp).size(48.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Color.White)
                    .border(6.dp, Color(0xFFE5262E), androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
                    Text("$lim", color = Color(0xFF111111), fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
            if (pv) Text("PRÉVIA", color = Color(0xFF1A0A0A), fontSize = 13.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopStart).padding(start = padL + 12.dp, top = 10.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFFFFC24E)).padding(horizontal = 8.dp, vertical = 2.dp))
        }
        // ---- the bar on top, solid black: the time, the date, the battery, the town and the weather (no temperature: the car shows it)
        Box(Modifier.fillMaxWidth().padding(start = padL, top = padT, end = padR)) {
            CarTopLine(now, bat, charging, wx, city, Modifier.fillMaxWidth().height(56.dp).padding(start = 12.dp), clock = true, endPad = 6.dp)
        }
        // ---- the right: the trip
        Column(Modifier.align(Alignment.TopEnd).fillMaxWidth(0.4f).fillMaxHeight().padding(start = 14.dp, top = padT + 62.dp, end = padR + 16.dp, bottom = padB + 8.dp)) {
            TurnCard(navigating, jdist, jmNow, rest, kindNow, jinfo?.nth, if (sim != null || jinfo?.held == true) null else NavNotice.icon, city)
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val tight = maxHeight < 220.dp
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
                    // the arrival, and the day as an arc next to it
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                        Column(Modifier.weight(1f)) {
                            if (a?.eta != null) {
                                Text("CHEGADA", color = CarLook.Blue2, fontSize = 11.sp, letterSpacing = 3.sp)
                                Row(verticalAlignment = Alignment.Bottom) {
                                    Text(a.eta, color = Color.White, fontSize = if (tight) 36.sp else 46.sp, fontWeight = FontWeight.ExtraLight, maxLines = 1)
                                    val m = Regex("(\\d+h \\d+|\\d+ min)").find(a.left)?.value
                                    if (m != null) Text("em $m", color = Color(0xFFA8A8B0), fontSize = 15.sp, maxLines = 1, modifier = Modifier.padding(start = 10.dp, bottom = 8.dp))
                                }
                            } else if (navigating) {
                                Text("ROTA", color = CarLook.Blue2, fontSize = 11.sp, letterSpacing = 3.sp)
                                Text("há " + fmtMin(((now - NavNotice.startedAt).coerceAtLeast(0L) / 60_000L).toInt()), color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.ExtraLight)
                            }
                        }
                        SunArc(now, Drive.pos, a?.eta, Modifier.size(118.dp, 74.dp))
                    }
                    // the trip as a line: blue up to where you are, the flag at the end
                    if (a != null) TripLine(a, doneM)
                    // the trip in numbers, each with its icon and its name
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        val avg = if (doneS >= 60) Math.round(doneM / 1000.0 / (doneS / 3600.0)).toInt() else null
                        TripNum("avg", avg?.toString() ?: "–", "km/h", "MÉDIA")
                        TripNum("max", (if (pv) 62 else Drive.tripMax).toString(), "km/h", "MÁXIMA")
                        val mins = (doneS / 60L).toInt()
                        TripNum("time", if (mins < 60) "$mins" else "${mins / 60}h${"%02d".format(mins % 60)}", if (mins < 60) "min" else "", "DIRIGINDO")
                    }
                    // the song: only its bar and how long until it ends
                    val d = NowPlaying.duration
                    if (d > 0) {
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x14FFFFFF)))
                        val pos = NowPlaying.position.coerceIn(0L, d)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.MusicNote, null, tint = CarLook.Blue2, modifier = Modifier.size(22.dp))
                            Box(Modifier.weight(1f).padding(horizontal = 12.dp).height(5.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFF2A2B30))) {
                                Box(Modifier.fillMaxHeight().fillMaxWidth((pos.toFloat() / d).coerceIn(0f, 1f)).clip(RoundedCornerShape(3.dp))
                                    .background(Brush.horizontalGradient(listOf(CarLook.Blue1, CarLook.Blue2))))
                            }
                            Text("−" + mmss(d - pos), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Light, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

/** The green card of the next turn: the arrow, "3ª à direita" (or the distance), the street, the distance, and a bar that empties up to
 *  the turn. Without a route: the way you're heading and the town. */
@Composable
private fun TurnCard(navigating: Boolean, dist: String?, metres: Double?, rest: String, kind: String?, nth: Int?, icon: android.graphics.Bitmap?, city: String) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF0E6B3A)).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        val ic = if (navigating) icon else null
        if (ic != null && kind == null) Image(ic.asImageBitmap(), null, colorFilter = ColorFilter.tint(Color.White), modifier = Modifier.size(48.dp))
        else Icon(when (kind) {
                "direita" -> Icons.Rounded.TurnRight; "esquerda" -> Icons.Rounded.TurnLeft
                "leve-direita" -> Icons.Rounded.TurnSlightRight; "leve-esquerda" -> Icons.Rounded.TurnSlightLeft
                "retorno" -> Icons.Rounded.UTurnLeft; "frente" -> Icons.Rounded.Straight
                else -> if (navigating) Icons.Rounded.Navigation else Icons.Rounded.North
            }, null, tint = Color.White, modifier = Modifier.size(48.dp))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            val side = when (kind) { "direita", "leve-direita" -> "direita"; "esquerda", "leve-esquerda" -> "esquerda"; else -> null }
            val title = when {
                !navigating -> Drive.heading?.let { "Sentido ${cardinalName(it)}" } ?: "Bordo Car"
                nth != null && side != null -> "${nth}ª à $side"
                kind == "direita" -> "Vire à direita"; kind == "esquerda" -> "Vire à esquerda"
                kind == "leve-direita" -> "Leve à direita"; kind == "leve-esquerda" -> "Leve à esquerda"
                kind == "retorno" -> "Faça o retorno"; kind == "rotatoria" -> "Rotatória"
                dist == null -> NavNotice.title
                else -> "Siga"
            }
            val sub = if (!navigating) city else rest
            Text(title, color = Color.White, fontSize = 22.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub.isNotBlank()) androidx.compose.runtime.key(sub) {
                Text(sub, color = Color(0xFFD8F0E0), fontSize = 14.sp, maxLines = 1,
                    modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 2000, repeatDelayMillis = 2000, velocity = 40.dp))
            }
            // the bar that empties as the turn comes closer (full from 1 km)
            if (navigating && metres != null) Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x33FFFFFF))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth((metres / 1000.0).toFloat().coerceIn(0.02f, 1f)).clip(RoundedCornerShape(2.dp)).background(Color(0xFFFFE27A)))
            }
        }
        if (navigating && dist != null) Text(dist, color = Color(0xFFFFE27A), fontSize = 28.sp, fontWeight = FontWeight.Light, maxLines = 1, modifier = Modifier.padding(start = 10.dp))
    }
}

/** The trip as a line: blue up to where you are, the flag at the end; the start time and what's left under it. */
@Composable
private fun TripLine(a: Arrival, doneM: Double) {
    val fmt = java.text.SimpleDateFormat("HH:mm", BR)
    Column(Modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(24.dp)) {
            val w = maxWidth - 26.dp
            val p = a.progress.coerceIn(0f, 1f)
            Box(Modifier.align(Alignment.CenterStart).width(w).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF2A2B30)))
            Box(Modifier.align(Alignment.CenterStart).width(w * p).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Brush.horizontalGradient(listOf(CarLook.Blue1, CarLook.Blue2))))
            Box(Modifier.align(Alignment.CenterStart).offset(x = (w * p - 10.dp).coerceAtLeast(0.dp)).size(20.dp).clip(androidx.compose.foundation.shape.CircleShape)
                .background(Color.White).border(3.dp, CarLook.Blue2.copy(alpha = 0.5f), androidx.compose.foundation.shape.CircleShape))
            Icon(Icons.Rounded.SportsScore, null, tint = Color.White, modifier = Modifier.align(Alignment.CenterEnd).size(24.dp))
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            val start = if (NavNotice.startedAt > 0) "saída " + fmt.format(java.util.Date(NavNotice.startedAt)) else fmtKm(doneM / 1000.0) + " rodados"
            Text(start, color = Color(0xFF8E8E96), fontSize = 12.sp, maxLines = 1)
            Spacer(Modifier.weight(1f))
            val km = a.km
            if (km != null) Text("faltam " + fmtKm(km), color = Color.White, fontSize = 13.sp, maxLines = 1)
        }
    }
}

/** One number of the trip: its blue icon, the number big, the unit, and its name under it. */
@Composable
private fun TripNum(icon: String, value: String, unit: String, name: String) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(24.dp)) {
                val s = size.width / 24f; val st = Stroke(2f * s, cap = StrokeCap.Round)
                val c = CarLook.Blue2
                when (icon) {
                    "avg" -> { drawCircle(c, 8f * s, Offset(12f * s, 12f * s), style = st); drawLine(c, Offset(6f * s, 18f * s), Offset(18f * s, 6f * s), 2f * s, StrokeCap.Round) }
                    "max" -> { drawLine(c, Offset(12f * s, 20f * s), Offset(12f * s, 5f * s), 2f * s, StrokeCap.Round)
                               drawLine(c, Offset(6f * s, 10f * s), Offset(12f * s, 4f * s), 2f * s, StrokeCap.Round); drawLine(c, Offset(18f * s, 10f * s), Offset(12f * s, 4f * s), 2f * s, StrokeCap.Round) }
                    else -> { drawCircle(c, 8f * s, Offset(12f * s, 13f * s), style = st); drawLine(c, Offset(12f * s, 9f * s), Offset(12f * s, 13f * s), 2f * s, StrokeCap.Round)
                              drawLine(c, Offset(12f * s, 13f * s), Offset(15f * s, 15f * s), 2f * s, StrokeCap.Round); drawLine(c, Offset(9f * s, 3f * s), Offset(15f * s, 3f * s), 2f * s, StrokeCap.Round) }
                }
            }
            Text(buildAnnotatedString {
                append(value)
                if (unit.isNotBlank()) { pushStyle(SpanStyle(fontSize = 12.sp, color = Color(0xFF8E8E96))); append(" $unit"); pop() }
            }, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Light, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
        }
        Text(name, color = CarLook.Blue2, fontSize = 11.sp, letterSpacing = 2.sp, modifier = Modifier.padding(start = 32.dp))
    }
}

/** The day as an arc from sunrise to sunset: the sun where it is now and a flag at the arrival; at night, the arc waits for sunrise. */
@Composable
private fun SunArc(now: Long, pos: LL?, eta: String?, modifier: Modifier) {
    val (rise, set) = sunTimes(now, pos)
    val c = java.util.Calendar.getInstance().apply { timeInMillis = now }
    val m = c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE).toDouble()
    val day = m in rise..set
    val etaMin = eta?.let { Regex("(\\d{1,2}):(\\d{2})").find(it) }?.let { it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt().toDouble() }
    fun f(x: Double) = ((x - rise) / (set - rise)).coerceIn(0.0, 1.0).toFloat()
    val hhmm = { x: Double -> "%02d:%02d".format(((x / 60).toInt() + 24) % 24, (x % 60).toInt().coerceAtLeast(0)) }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            val r = minOf(size.width / 2f, size.height) - 8f
            val cx = size.width / 2f; val cy = size.height - 2f
            val tl = Offset(cx - r, cy - r); val sz = Size(r * 2, r * 2)
            drawArc(Color(0xFF3A3B40), 180f, 180f, false, tl, sz, style = Stroke(3f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8f, 9f))))
            drawLine(Color(0xFF3A3B40), Offset(cx - r - 6f, cy), Offset(cx + r + 6f, cy), 1.5f)
            fun pt(fr: Float): Offset { val ang = Math.PI * (1 - fr); return Offset(cx + (r * Math.cos(ang)).toFloat(), cy - (r * Math.sin(ang)).toFloat()) }
            if (day) {
                val fs = f(m)
                drawArc(Color(0xFFFFB070), 180f, 180f * fs, false, tl, sz, style = Stroke(3f))
                val p = pt(fs)
                drawCircle(Color(0x40FFD27A), 16f, p); drawCircle(Color(0xFFFFD27A), 9f, p)
            }
            if (etaMin != null) {
                val p = pt(f(etaMin))
                // the arrival: a small flag on its pole
                drawLine(Color.White, Offset(p.x, p.y - 2f), Offset(p.x, p.y - 22f), 2f)
                drawRect(Color.White, Offset(p.x, p.y - 22f), Size(12f, 8f))
            }
        }
        Text(if (day) "pôr do sol " + hhmm(set) else "nascer " + hhmm(rise), color = Color(0xFFA8A8B0), fontSize = 12.sp, maxLines = 1)
    }
}

/** Sunrise and sunset today where you are, in minutes of the day (Teresina while the GPS hasn't answered). */
private fun sunTimes(now: Long, pos: LL?): Pair<Double, Double> {
    val lat = pos?.lat ?: -5.09; val lon = pos?.lon ?: -42.80
    val c = java.util.Calendar.getInstance().apply { timeInMillis = now }
    val tz = c.timeZone.getOffset(now) / 60_000.0
    val g = 2 * Math.PI / 365.0 * (c.get(java.util.Calendar.DAY_OF_YEAR) - 1)
    val eq = 229.18 * (0.000075 + 0.001868 * Math.cos(g) - 0.032077 * Math.sin(g) - 0.014615 * Math.cos(2 * g) - 0.040849 * Math.sin(2 * g))
    val decl = 0.006918 - 0.399912 * Math.cos(g) + 0.070257 * Math.sin(g) - 0.006758 * Math.cos(2 * g) + 0.000907 * Math.sin(2 * g) - 0.002697 * Math.cos(3 * g) + 0.00148 * Math.sin(3 * g)
    val la = Math.toRadians(lat)
    val ha = Math.toDegrees(Math.acos((Math.cos(Math.toRadians(90.833)) / (Math.cos(la) * Math.cos(decl)) - Math.tan(la) * Math.tan(decl)).coerceIn(-1.0, 1.0)))
    return (720 - 4 * (lon + ha) - eq + tz) to (720 - 4 * (lon - ha) - eq + tz)
}

/** The trip as a line: where it started, the car where you are, the next turn, the chequered flag at the end. */
@Composable
private fun Timeline(now: Long, navigating: Boolean, a: Arrival?, doneM: Double, dist: String?, modifier: Modifier, flat: Boolean = false) {
    val fmt = java.text.SimpleDateFormat("HH:mm", BR)
    // flat (adapted to the car): no box, the line straight over the scene
    Box((if (flat) modifier else modifier.clip(RoundedCornerShape(16.dp)).background(Color(0x990B1222)).border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(16.dp))).padding(horizontal = 22.dp, vertical = 8.dp)) {
        if (a == null) {
            Text(if (navigating) "${NavNotice.app.ifBlank { "O app" }} não informa a chegada · rota há ${fmtMin(((now - NavNotice.startedAt).coerceAtLeast(0L) / 60_000L).toInt())}"
                else "Sem rota · escolha o destino no Waze ou no Google Maps", color = Sub, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.CenterStart))
            return@Box
        }
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Row(Modifier.fillMaxWidth()) {
                val start = if (NavNotice.startedAt > 0) "SAÍDA " + fmt.format(java.util.Date(NavNotice.startedAt)) else ""
                Text(listOf(start, "rodado " + fmtKm(doneM / 1000.0)).filter { it.isNotBlank() }.joinToString(" · "), color = Lbl, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, maxLines = 1)
                Spacer(Modifier.weight(1f))
                Text(listOf(if (a.left.isNotBlank()) "faltam ${a.left}" else "", if (a.eta != null) "CHEGADA ${a.eta}" else "").filter { it.isNotBlank() }.joinToString(" · "),
                    color = Lbl, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, maxLines = 1)
            }
            BoxWithConstraints(Modifier.fillMaxWidth().height(36.dp)) {
                val w = maxWidth
                val p = a.progress
                Box(Modifier.align(Alignment.CenterStart).padding(end = 34.dp).fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Track))
                Box(Modifier.align(Alignment.CenterStart).width((w - 34.dp) * p).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Brush.horizontalGradient(Blue)))
                Box(Modifier.align(Alignment.CenterStart).size(18.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Blue.last()))
                // the next turn, when how much is left of the route is known
                val tm = dist?.let { turnMetres(it) }
                if (tm != null && a.km != null && a.km > 0.05) {
                    val total = doneM + a.km * 1000.0
                    val q = ((doneM + tm) / total).toFloat().coerceIn(p, 1f)
                    Box(Modifier.align(Alignment.CenterStart).offset(x = (w - 34.dp) * q).width(4.dp).height(26.dp).background(Color(0xFFFFC24E)))
                }
                Icon(Icons.Rounded.DirectionsCar, null, tint = Color(0xFFFF4A3A), modifier = Modifier.align(Alignment.CenterStart).offset(x = ((w - 34.dp) * p - 14.dp).coerceAtLeast(0.dp)).size(30.dp))
                Icon(Icons.Rounded.SportsScore, null, tint = Color.White, modifier = Modifier.align(Alignment.CenterEnd).size(30.dp))
            }
        }
    }
}

internal fun turnMetres(s: String): Double? {
    val m = Regex("(\\d+(?:[.,]\\d+)?)\\s*(km|m)", RegexOption.IGNORE_CASE).find(s) ?: return null
    val v = m.groupValues[1].replace(",", ".").toDoubleOrNull() ?: return null
    return if (m.groupValues[2].lowercase() == "km") v * 1000.0 else v
}

/** The green road sign on its gantry: the turn and its distance, or, without a route, the way you're heading. */
@Composable
private fun RoadSign(navigating: Boolean, dist: String?, rest: String, wx: Weather.Now?, city: String, modifier: Modifier, h: Float, nth: Int? = null, kind: String? = null) {
    val green = Color(0xFF0B7A3E)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(green).border(3.dp, Color.White, RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            val isz = (h * 0.15f).coerceIn(30f, 60f)
            val ic = if (navigating) NavNotice.icon else null
            if (ic != null) Image(ic.asImageBitmap(), null, colorFilter = ColorFilter.tint(Color.White), modifier = Modifier.size(isz.dp))
            else Icon(when (kind) {
                    "direita" -> Icons.Rounded.TurnRight; "esquerda" -> Icons.Rounded.TurnLeft
                    "leve-direita" -> Icons.Rounded.TurnSlightRight; "leve-esquerda" -> Icons.Rounded.TurnSlightLeft
                    "retorno" -> Icons.Rounded.UTurnLeft; "frente" -> Icons.Rounded.Straight
                    else -> if (navigating) Icons.Rounded.Navigation else Icons.Rounded.North
                }, null, tint = Color.White, modifier = Modifier.size(isz.dp))
            Column(Modifier.padding(start = 12.dp)) {
                val top: String; val bottom: String
                if (!navigating) {
                    val hd = Drive.heading
                    top = if (hd != null) "Sentido ${cardinalName(hd)}" else "Bordo Car"
                    bottom = city.ifBlank { "" }
                } else if (nth != null && dist != null && (kind == "direita" || kind == "esquerda" || kind == "leve-direita" || kind == "leve-esquerda")) {
                    // counted on Mapbox's route: "3ª à direita", the distance and the street underneath
                    top = "${nth}ª à " + (if (kind.endsWith("direita")) "direita" else "esquerda"); bottom = listOf(dist, rest).filter { it.isNotBlank() }.joinToString(" · ")
                } else if (dist != null) { top = dist; bottom = rest } else { top = NavNotice.title; bottom = NavNotice.text }
                val f = (h * 0.12f).coerceIn(20f, 44f)
                androidx.compose.runtime.key(top) {
                    Text(top, color = Color.White, fontSize = f.sp, lineHeight = (f * 1.1f).sp, fontWeight = FontWeight.ExtraBold, maxLines = 1,
                        modifier = if (dist != null) Modifier else Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 2000, repeatDelayMillis = 2000, velocity = 40.dp))
                }
                if (bottom.isNotBlank()) androidx.compose.runtime.key(bottom) {
                    Text(bottom, color = Color.White, fontSize = (f * 0.42f).coerceIn(13f, 19f).sp, maxLines = 1,
                        modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 2000, repeatDelayMillis = 2000, velocity = 40.dp))
                }
            }
        }
        // the gantry's posts (not over the map of the turn)
        if (kind == null) Row(Modifier.fillMaxWidth(0.66f), horizontalArrangement = Arrangement.SpaceBetween) {
            for (k in 0..1) Box(Modifier.width(7.dp).height((h * 0.16f).coerceIn(20f, 70f).dp).background(Brush.verticalGradient(listOf(Color(0xFF9AA1AA), Color(0xFF5D636B)))))
        }
    }
}

private class Sky(val colors: List<Color>, val ground: List<Color>, val hills: Color, val stars: Float, val sun: Float, val preset: String = "day")

/** Whether this composition is the car screen (the map of the turn is drawn only there). */
val LocalOnCar = androidx.compose.runtime.staticCompositionLocalOf { false }

/** The sky for the hour, from the sunrise and sunset where you are (Teresina when the GPS hasn't answered yet). */
private fun skyAt(now: Long, pos: LL?): Sky {
    val lat = pos?.lat ?: -5.09; val lon = pos?.lon ?: -42.80
    val c = java.util.Calendar.getInstance().apply { timeInMillis = now }
    val tz = c.timeZone.getOffset(now) / 60_000.0
    val g = 2 * Math.PI / 365.0 * (c.get(java.util.Calendar.DAY_OF_YEAR) - 1)
    val eq = 229.18 * (0.000075 + 0.001868 * Math.cos(g) - 0.032077 * Math.sin(g) - 0.014615 * Math.cos(2 * g) - 0.040849 * Math.sin(2 * g))
    val decl = 0.006918 - 0.399912 * Math.cos(g) + 0.070257 * Math.sin(g) - 0.006758 * Math.cos(2 * g) + 0.000907 * Math.sin(2 * g) - 0.002697 * Math.cos(3 * g) + 0.00148 * Math.sin(3 * g)
    val la = Math.toRadians(lat)
    val ha = Math.toDegrees(Math.acos((Math.cos(Math.toRadians(90.833)) / (Math.cos(la) * Math.cos(decl)) - Math.tan(la) * Math.tan(decl)).coerceIn(-1.0, 1.0)))
    val rise = 720 - 4 * (lon + ha) - eq + tz
    val set = 720 - 4 * (lon - ha) - eq + tz
    val m = c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE) + c.get(java.util.Calendar.SECOND) / 60.0
    // how close to sunrise / sunset (1 at the moment itself, 0 from 45 minutes away)
    val glow = maxOf(1 - Math.abs(m - rise) / 45.0, 1 - Math.abs(m - set) / 45.0).coerceIn(0.0, 1.0).toFloat()
    val day = m > rise && m < set
    val dayS = Sky(listOf(Color(0xFF1E5FC4), Color(0xFF4F95E6), Color(0xFFA8D4FF)), listOf(Color(0xFF3B4655), Color(0xFF1C222C)), Color(0xFF34506E), 0f, 0f, "day")
    val nightS = Sky(listOf(Color(0xFF03060E), Color(0xFF0A1428), Color(0xFF16264A)), listOf(Color(0xFF1E2633), Color(0xFF0E1218)), Color(0xFF0B1424), 1f, 0f, "night")
    val duskS = Sky(listOf(Color(0xFF1D2B57), Color(0xFFB0507A), Color(0xFFFF9A4D)), listOf(Color(0xFF33343F), Color(0xFF17181F)), Color(0xFF3A2440), 0.3f, 1f, "dusk")
    fun mix(a: Sky, b: Sky, f: Float) = Sky(a.colors.zip(b.colors) { x, y -> androidx.compose.ui.graphics.lerp(x, y, f) },
        a.ground.zip(b.ground) { x, y -> androidx.compose.ui.graphics.lerp(x, y, f) }, androidx.compose.ui.graphics.lerp(a.hills, b.hills, f),
        a.stars + (b.stars - a.stars) * f, a.sun + (b.sun - a.sun) * f, if (f > 0.35f) b.preset else a.preset)
    return mix(if (day) dayS else nightS, duskS, glow)
}

/** Which manoeuvre the navigation notice speaks of (Portuguese / English), or null when it's not clear. */
internal fun maneuver(s0: String): String? {
    val s = s0.lowercase()
    return when {
        "retorno" in s || "u-turn" in s || "make a u" in s -> "retorno"
        "rotatória" in s || "rotatoria" in s || "rotunda" in s || "roundabout" in s -> "rotatoria"
        ("leve" in s || "mantenha" in s || "slight" in s || "keep" in s) && ("direita" in s || "right" in s) -> "leve-direita"
        ("leve" in s || "mantenha" in s || "slight" in s || "keep" in s) && ("esquerda" in s || "left" in s) -> "leve-esquerda"
        "direita" in s || "right" in s -> "direita"
        "esquerda" in s || "left" in s -> "esquerda"
        "siga" in s || "continue" in s || "em frente" in s || "straight" in s || "head" in s -> "frente"
        else -> null
    }
}

/** The arrows painted on the road, as points on the road (0..1 across, 0..1 from far to near). */
private fun arrowShape(kind: String): List<Pair<Float, Float>> {
    val right = listOf(0.32f to 1f, 0.32f to 0.45f, 0.335f to 0.36f, 0.38f to 0.29f, 0.45f to 0.255f, 0.52f to 0.25f, 0.64f to 0.25f, 0.64f to 0.06f,
        0.96f to 0.34f, 0.64f to 0.62f, 0.64f to 0.43f, 0.54f to 0.43f, 0.51f to 0.445f, 0.50f to 0.48f, 0.50f to 1f)
    val straight = listOf(0.42f to 1f, 0.42f to 0.38f, 0.26f to 0.38f, 0.5f to 0.04f, 0.74f to 0.38f, 0.58f to 0.38f, 0.58f to 1f)
    val slight = listOf(0.41f to 1f, 0.41f to 0.6f, 0.43f to 0.5f, 0.55f to 0.32f, 0.47f to 0.26f, 0.82f to 0.08f, 0.76f to 0.47f, 0.68f to 0.40f, 0.58f to 0.56f, 0.57f to 0.62f, 0.57f to 1f)
    val uturn = listOf(0.74f to 1f, 0.74f to 0.40f, 0.72f to 0.27f, 0.66f to 0.17f, 0.58f to 0.11f, 0.5f to 0.09f, 0.42f to 0.11f, 0.34f to 0.17f, 0.28f to 0.27f, 0.26f to 0.40f,
        0.26f to 0.60f, 0.14f to 0.60f, 0.34f to 0.92f, 0.54f to 0.60f, 0.42f to 0.60f, 0.42f to 0.40f, 0.44f to 0.31f, 0.47f to 0.27f, 0.5f to 0.26f,
        0.53f to 0.27f, 0.56f to 0.31f, 0.58f to 0.40f, 0.58f to 1f)
    fun mirror(l: List<Pair<Float, Float>>) = l.map { (u, v) -> (1f - u) to v }.reversed()
    return when (kind) {
        "direita" -> right
        "esquerda" -> mirror(right)
        "leve-direita" -> slight
        "leve-esquerda" -> mirror(slight)
        "retorno" -> uturn
        else -> straight
    }
}

private fun cardinalName(deg: Double) = listOf("Norte", "Nordeste", "Leste", "Sudeste", "Sul", "Sudoeste", "Oeste", "Noroeste")[(((deg % 360) + 360) % 360 / 45.0 + 0.5).toInt() % 8]

/** A real-looking highway: grey asphalt with its shoulders, white edge lines, the double yellow line in the middle, and the arrow of
 *  the next manoeuvre painted on it (yellow). */
@Composable
private fun Highway(navigating: Boolean, now: Long, modifier: Modifier) {
    val icon = if (navigating) NavNotice.icon else null
    val sky = skyAt(now, Drive.pos)
    Canvas(modifier) {
        val w = size.width; val hh = size.height
        val cx = w / 2f
        val horizon = hh * 0.44f
        // the sky of the hour: day, sunrise / sunset, night with stars
        drawRect(Brush.verticalGradient(sky.colors, 0f, horizon), Offset.Zero, Size(w, horizon + 1f))
        if (sky.stars > 0f) {
            val rnd = java.util.Random(7L)
            repeat(60) {
                val x = rnd.nextFloat() * w; val y = rnd.nextFloat() * horizon * 0.95f; val r = 0.8f + rnd.nextFloat() * 1.6f
                drawCircle(Color.White.copy(alpha = sky.stars * (0.35f + rnd.nextFloat() * 0.6f)), r, Offset(x, y))
            }
        }
        if (sky.sun > 0f) {
            // the sun sitting on the horizon
            val sc = Offset(w * 0.72f, horizon)
            drawCircle(Brush.radialGradient(listOf(Color(0xFFFFB25A).copy(alpha = 0.55f * sky.sun), Color.Transparent), sc, w * 0.16f), w * 0.16f, sc)
            drawCircle(Color(0xFFFFC46B).copy(alpha = sky.sun), w * 0.035f, sc)
        }
        // ground and the hills on the horizon (a darker shade of the sky)
        drawRect(Brush.verticalGradient(sky.ground, horizon, hh), Offset(0f, horizon), Size(w, hh - horizon))
        val hills = Path().apply {
            moveTo(0f, horizon); quadraticBezierTo(w * 0.12f, horizon - hh * 0.04f, w * 0.25f, horizon - hh * 0.015f)
            quadraticBezierTo(w * 0.4f, horizon - hh * 0.05f, w * 0.55f, horizon - hh * 0.02f); quadraticBezierTo(w * 0.75f, horizon - hh * 0.045f, w, horizon - hh * 0.01f)
            lineTo(w, horizon + 2f); lineTo(0f, horizon + 2f); close()
        }
        drawPath(hills, sky.hills)
        // shoulders, asphalt
        val sTop = w * 0.03f; val sBot = w * 0.40f
        val aTop = w * 0.023f; val aBot = w * 0.34f
        drawPath(Path().apply { moveTo(cx - sTop, horizon); lineTo(cx + sTop, horizon); lineTo(cx + sBot, hh); lineTo(cx - sBot, hh); close() }, Color(0xFF2A2F36))
        drawPath(Path().apply { moveTo(cx - aTop, horizon); lineTo(cx + aTop, horizon); lineTo(cx + aBot, hh); lineTo(cx - aBot, hh); close() },
            Brush.verticalGradient(listOf(Color(0xFF3A3F47), Color(0xFF23272E)), horizon, hh))
        // white edges
        for (sg in listOf(-1f, 1f)) drawLine(Color(0xFFE8E8E8), Offset(cx + sg * aTop * 0.92f, horizon), Offset(cx + sg * aBot * 0.92f, hh), strokeWidth = 4f)
        // the double yellow line
        for (sg in listOf(-1f, 1f)) drawLine(Color(0xFFF2C230), Offset(cx + sg * 2f, horizon), Offset(cx + sg * w * 0.011f, hh), strokeWidth = 4f)
        // the arrow painted on the asphalt: drawn by Bordo Car for the manoeuvre the app's notice speaks of, in perspective
        if (navigating) {
            val kind = maneuver(NavNotice.title + " " + NavNotice.text) ?: NavNotice.iconKind
            val v0 = hh * 0.58f; val vh = hh * 0.38f
            val topW = w * 0.15f; val botW = w * 0.24f
            fun pt(u: Float, v: Float) = Offset(cx + (u - 0.5f) * (topW + (botW - topW) * v), v0 + v * vh)
            val yellow = Color(0xFFFFC24E)
            if ((kind == null || kind == "rotatoria") && icon != null) {
                // a manoeuvre Bordo Car doesn't know, or a roundabout (the app's icon shows which exit): the app's own arrow, squashed as if lying on the road
                val img = icon.asImageBitmap(); val aw = w * 0.16f; val ah = aw * 0.75f
                drawImage(img, srcOffset = androidx.compose.ui.unit.IntOffset.Zero, srcSize = androidx.compose.ui.unit.IntSize(img.width, img.height),
                    dstOffset = androidx.compose.ui.unit.IntOffset((cx - aw / 2f).toInt(), (v0 + vh * 0.2f).toInt()), dstSize = androidx.compose.ui.unit.IntSize(aw.toInt(), ah.toInt()),
                    alpha = 0.95f, colorFilter = ColorFilter.tint(yellow))
            } else {
                val shape = arrowShape(kind ?: "frente")
                val path = Path().apply { shape.forEachIndexed { i, (u, v) -> val o = pt(u, v); if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) }; close() }
                drawPath(path, Color.Black.copy(alpha = 0.25f), style = Stroke(6f))
                drawPath(path, yellow.copy(alpha = 0.95f))
                if (kind == "rotatoria") {
                    // the roundabout's ring
                    val c = pt(0.5f, 0.42f); val rw = (topW + (botW - topW) * 0.42f) * 0.26f
                    drawOval(yellow.copy(alpha = 0.95f), Offset(c.x - rw, c.y - rw * 0.55f), Size(rw * 2, rw * 1.1f), style = Stroke(w * 0.012f))
                }
            }
        }
    }
}

internal class Arrival(val eta: String?, val left: String, val progress: Float, val km: Double? = null)

/**
 * The arrival bar of Estrada. What's left comes from Waze / Google Maps when they say it ("Chegada: 16:26", "12 km", "21 min"); the
 * minutes left are worked out from the arrival time when only that comes (Google Maps); and when the route was started from Bordo
 * Car (Casa, Trabalho, a favourite), the distance to the destination as the crow flies fills in what's missing (Waze), marked "≈".
 */
internal fun arrival(now: Long, navigating: Boolean, left: Remaining.Left?, doneM: Double, doneS: Long): Arrival? {
    if (!navigating) return null
    val fmt = java.text.SimpleDateFormat("HH:mm", BR)
    var eta = left?.eta
    var min = left?.min
    var km = left?.km
    var approx = false
    if (min == null && eta != null) {
        // minutes from now to the arrival time (it may be after midnight)
        val p = eta.split(":"); val c = java.util.Calendar.getInstance()
        val nowMin = c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE)
        val m = ((p[0].toInt() * 60 + p[1].toInt()) - nowMin + 1440) % 1440
        if (m < 12 * 60) min = m
    }
    val tgt = NavNotice.target
    val pos = Drive.pos
    val fresh = tgt != null && NavNotice.targetAt > 0 && now - NavNotice.targetAt < 6 * 3600_000L
    if (km == null && fresh && pos != null) { km = dist(pos, tgt!!) / 1000.0; approx = true }
    if (eta == null && km != null) {
        // no arrival time: what's left at the trip's average speed (30 km/h at the start)
        val avg = if (doneS >= 120 && doneM > 500) (doneM / doneS * 3.6).coerceIn(15.0, 110.0) else 30.0
        val m = Math.round(km * (if (approx) 1.3 else 1.0) / avg * 60.0).toInt()
        if (min == null) min = m
        eta = "≈ " + fmt.format(java.util.Date(now + m * 60_000L))
    }
    if (eta == null && min == null && km == null) return null
    val progress = when {
        left?.km != null -> { val lm = left.km * 1000.0; (doneM / (doneM + lm)).toFloat() }
        approx && NavNotice.targetFromM > 0 -> (1.0 - km!! * 1000.0 / NavNotice.targetFromM).toFloat()
        min != null && NavNotice.startedAt > 0 -> { val gone = (now - NavNotice.startedAt) / 60_000f; gone / (gone + min) }
        else -> 0f
    }.coerceIn(0f, 1f)
    val parts = listOfNotNull(km?.let { (if (approx) "≈ " else "") + fmtKm(it) }, min?.let { fmtMin(it) })
    return Arrival(eta, parts.joinToString(" · "), progress, km)
}

internal fun fmtKm(v: Double) = (if (v < 100) String.format(BR, "%.1f", v).removeSuffix(",0") else "${Math.round(v)}") + " km"
internal fun fmtMin(m: Int) = if (m < 60) "$m min" else "${m / 60}h ${"%02d".format(m % 60)}"

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
internal object Remaining {
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
