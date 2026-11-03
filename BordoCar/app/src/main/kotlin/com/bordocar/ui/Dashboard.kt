package com.bordocar.ui

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
private val Bg = Brush.radialGradient(listOf(Color(0xFF1B2A4E), Color(0xFF0B1222), Color(0xFF070B16)), center = Offset(300f, 0f), radius = 2200f)
private val CardBg = Brush.linearGradient(listOf(Color(0xC028385C), Color(0xD912192E)))
private val Edge = Color(0x3878A0FF)
private val Txt = Color(0xFFEEF3FF)
private val Sub = Color(0xFF9FB0D0)
private val Lbl = Color(0xFF8FA3C8)
private val Track = Color(0xFF1E2A44)
private val Blue = listOf(Color(0xFF1E6BFF), Color(0xFF5CC8FF))
private val Lime = Color(0xFFB6F35A)
private val BR = Locale("pt", "BR")

/** What the dashboard's buttons do (they need Android Auto's own screens, or hand over to Waze / Google Maps). */
interface DashActions {
    fun home()
    fun work()
    fun fuel()
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
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            try { NowPlaying.refresh(ctx) } catch (e: Exception) { }
            delay(1000L)
        }
    }
    // the weather and the town where you are (every 20 minutes)
    var wx by remember { mutableStateOf<Weather.Now?>(null) }
    var city by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            val p: LL? = Drive.pos
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
            } else delay(5_000L)
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

    Box(Modifier.fillMaxSize().background(Bg)) {
        Column(Modifier.fillMaxSize().padding(start = padL, top = padT, end = padR, bottom = padB), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
            BottomBar(now, bat, charging, wx, city, Modifier.fillMaxWidth().height(74.dp))
        }
    }
}

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
                Column(Modifier.weight(1f).padding(start = 22.dp)) {
                    Label("TOCANDO AGORA")
                    Text(NowPlaying.title.ifBlank { "Nada tocando" }, color = Txt, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                    if (NowPlaying.artist.isNotBlank()) Text(NowPlaying.artist, color = Sub, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                    val d = NowPlaying.duration
                    if (d > 0) {
                        val pos = NowPlaying.position.coerceIn(0L, d)
                        Box(Modifier.padding(top = 20.dp).fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(Track)) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth((pos.toFloat() / d).coerceIn(0f, 1f)).background(Brush.horizontalGradient(Blue)))
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
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
private fun TurnCard(modifier: Modifier) {
    val shape = RoundedCornerShape(22.dp)
    Box(modifier.clip(shape).background(Brush.linearGradient(listOf(Color(0xFF1E6BFF), Color(0xFF0E3FA8)))).border(1.dp, Color(0xFF5CC8FF), shape)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(NavNotice.app.uppercase(BR), color = Color(0xFF04122B), fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFF33CCFF)).padding(horizontal = 6.dp, vertical = 1.dp))
                Text("PRÓXIMA CURVA", color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp, modifier = Modifier.padding(start = 8.dp))
            }
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                val ic = NavNotice.icon
                if (ic != null) Image(ic.asImageBitmap(), null, colorFilter = ColorFilter.tint(Color.White), modifier = Modifier.size(92.dp))
                else Icon(Icons.Rounded.Navigation, null, tint = Color.White, modifier = Modifier.size(80.dp))
                Column(Modifier.padding(start = 20.dp)) {
                    Text(NavNotice.title, color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 44.sp)
                    if (NavNotice.text.isNotBlank()) Text(NavNotice.text, color = Color.White, fontSize = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                    if (NavNotice.sub.isNotBlank()) Text(NavNotice.sub, color = Color.White.copy(alpha = 0.8f), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

// ---------------------------------------------------------------- shortcuts

@Composable
private fun Shortcuts(actions: DashActions, modifier: Modifier) {
    Card(modifier) {
        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 16.dp)) {
            Label("PARA ONDE?")
            Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Shortcut(Icons.Rounded.Home, Color(0xFF2E6BFF), "Casa", "sc-home", Modifier.weight(1f)) { actions.home() }
                Shortcut(Icons.Rounded.Work, Color(0xFFF29900), "Trabalho", "sc-work", Modifier.weight(1f)) { actions.work() }
                Shortcut(Icons.Rounded.LocalGasStation, Color(0xFF12A4B4), "Postos", "sc-fuel", Modifier.weight(1f)) { actions.fuel() }
                Shortcut(Icons.Rounded.Search, Color(0xFF8E5CF0), "Buscar", "sc-search", Modifier.weight(1f)) { actions.search() }
            }
            Text("abre no Waze ou no Google Maps", color = Lbl, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun Shortcut(icon: ImageVector, color: Color, label: String, id: String, modifier: Modifier, action: () -> Unit) {
    Column(modifier.carTap(id, action), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(18.dp)).background(color), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(34.dp))
        }
        Text(label, color = Txt, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
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
        BoxWithConstraints(Modifier.fillMaxSize().padding(top = 30.dp, bottom = 30.dp)) {
            val d = minOf(maxWidth, maxHeight)
            Canvas(Modifier.size(d).align(Alignment.Center)) {
                val sw = size.minDimension * 0.06f
                val tl = Offset(sw / 2, sw / 2); val sz = Size(size.width - sw, size.height - sw)
                drawArc(Track, 135f, 270f, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
                val f = (kmh / 160f).coerceIn(0f, 1f)
                val cols = if (over) listOf(Color(0xFFFF8A4D), Color(0xFFFF3B3B)) else Blue
                if (f > 0.003f) drawArc(Brush.horizontalGradient(cols), 135f, 270f * f, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
            }
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("$kmh", color = if (over) Color(0xFFFF6B5A) else Txt, fontSize = 60.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 60.sp)
                Text("km/h", color = Sub, fontSize = 14.sp)
            }
        }
        Text(if (lim != null) (if (Limits.estimated) "limite $lim km/h (estimado)" else "limite $lim km/h") else "limite --",
            color = Color(0xFFC9D6F2), fontSize = 14.sp, maxLines = 1, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp))
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
                        drawLine(if (major) Color(0xFF5CC8FF) else Color(0xFF3A4A6A),
                            Offset(c.x + (r0 * Math.sin(a)).toFloat(), c.y - (r0 * Math.cos(a)).toFloat()),
                            Offset(c.x + (r * 0.93f * Math.sin(a)).toFloat(), c.y - (r * 0.93f * Math.cos(a)).toFloat()), strokeWidth = if (major) 3f else 1.5f)
                    }
                    val letters = listOf("N" to 0.0, "L" to 90.0, "S" to 180.0, "O" to 270.0)
                    for ((t, deg) in letters) {
                        val a = Math.toRadians(deg)
                        val lay = n.measure(t, androidx.compose.ui.text.TextStyle(color = if (t == "N") Color(0xFFFF5A5A) else Color(0xFF9FB0D0), fontSize = 15.sp, fontWeight = FontWeight.Bold))
                        val p = Offset(c.x + (r * 0.62f * Math.sin(a)).toFloat() - lay.size.width / 2f, c.y - (r * 0.62f * Math.cos(a)).toFloat() - lay.size.height / 2f)
                        drawText(lay, topLeft = p)
                    }
                }
                val arrow = Path().apply {
                    moveTo(c.x, c.y - r * 0.42f); lineTo(c.x + r * 0.16f, c.y + r * 0.18f); lineTo(c.x, c.y + r * 0.06f); lineTo(c.x - r * 0.16f, c.y + r * 0.18f); close()
                }
                drawPath(arrow, Color(0xFF5CC8FF))
            }
        }
        Text(if (hd != null) buildAnnotatedString {
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Txt)); append(cardinal(hd)); pop()
                append(" · ${Math.round(hd)}°")
            } else buildAnnotatedString { append("aguardando o GPS") },
            color = Color(0xFFC9D6F2), fontSize = 14.sp, maxLines = 1, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp))
    }
}

@Composable
private fun TodayCard(modifier: Modifier) {
    @Suppress("UNUSED_VARIABLE") val t = Drive.tick
    val (m, secs) = Drive.today()
    val km = m / 1000.0
    val mins = (secs / 60L).toInt()
    Card(modifier) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            Label("KM HOJE")
            Text(buildAnnotatedString {
                append(if (km < 100) String.format(BR, "%.1f", km) else "${Math.round(km)}")
                pushStyle(SpanStyle(fontSize = 15.sp, color = Sub, fontWeight = FontWeight.Normal)); append(" km"); pop()
            }, color = Txt, fontSize = 40.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 8.dp))
            Text(when { mins <= 0 && m < 50 -> "nenhuma viagem hoje"; mins <= 0 -> "agora há pouco"; mins < 60 -> "$mins min dirigindo"; else -> "${mins / 60} h ${"%02d".format(mins % 60)} min dirigindo" },
                color = Sub, fontSize = 14.sp, modifier = Modifier.padding(top = 2.dp))
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
        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp)) {
            Label("KM DA SEMANA")
            Text(buildAnnotatedString {
                append(if (total < 100) String.format(BR, "%.1f", total) else "${Math.round(total)}")
                pushStyle(SpanStyle(fontSize = 15.sp, color = Sub, fontWeight = FontWeight.Normal)); append(" km"); pop()
            }, color = Txt, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 4.dp))
            val max = (days.maxOrNull() ?: 0.0).coerceAtLeast(1000.0)
            Canvas(Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp)) {
                val gap = size.width * 0.03f
                val bw = (size.width - gap * 6) / 7f
                for (i in 0 until 7) {
                    val x = i * (bw + gap)
                    drawRoundRect(Track, Offset(x, 0f), Size(bw, size.height), CornerRadius(bw * 0.18f))
                    val f = (days[i] / max).toFloat().coerceIn(0f, 1f)
                    if (f > 0f) {
                        val bh = (size.height * f).coerceAtLeast(size.height * 0.08f)
                        drawRoundRect(if (i == today) Lime else Color(0xFF3D8BFF), Offset(x, size.height - bh), Size(bw, bh), CornerRadius(bw * 0.18f))
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

// ---------------------------------------------------------------- bottom bar: time, battery, weather

@Composable
private fun BottomBar(now: Long, bat: Int, charging: Boolean, wx: Weather.Now?, city: String, modifier: Modifier) {
    val d = java.util.Date(now)
    Card(modifier) {
        Row(Modifier.fillMaxSize().padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(java.text.SimpleDateFormat("HH:mm", BR).format(d), color = Txt, fontSize = 46.sp, fontWeight = FontWeight.ExtraBold)
            Column(Modifier.padding(start = 16.dp)) {
                Text(java.text.SimpleDateFormat("EEEE", BR).format(d).replaceFirstChar { it.uppercase() }, color = Sub, fontSize = 15.sp)
                Text(java.text.SimpleDateFormat("d 'de' MMMM", BR).format(d), color = Sub, fontSize = 15.sp)
            }
            if (bat >= 0) {
                Box(Modifier.padding(start = 30.dp).width(1.dp).height(40.dp).background(Edge))
                val col = if (bat < 15) Color(0xFFFF4D4D) else if (bat < 30) Color(0xFFFFA63D) else Lime
                Canvas(Modifier.padding(start = 26.dp).size(34.dp, 17.dp)) {
                    val w = size.width; val h = size.height
                    drawRoundRect(Color(0xFFC9D6F2), Offset(1f, 1f), Size(w * 0.86f - 2f, h - 2f), CornerRadius(h * 0.22f), style = Stroke(2.2f))
                    drawRoundRect(Color(0xFFC9D6F2), Offset(w * 0.88f, h * 0.3f), Size(w * 0.09f, h * 0.4f), CornerRadius(2f))
                    drawRoundRect(col, Offset(4f, 4f), Size((w * 0.86f - 8f) * (bat / 100f), h - 8f), CornerRadius(h * 0.12f))
                }
                Text("$bat%", color = Txt, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp))
                Text(if (charging) "⚡ carregando" else "celular", color = Sub, fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp))
            }
            Spacer(Modifier.weight(1f))
            if (wx != null) {
                Text(Weather.emoji(wx.code), fontSize = 30.sp)
                Column(Modifier.padding(start = 12.dp)) {
                    Text("${Math.round(wx.tempC)}°", color = Txt, fontSize = 28.sp, fontWeight = FontWeight.Bold, lineHeight = 30.sp)
                    Text(listOf(city, Weather.text(wx.code)).filter { it.isNotBlank() }.joinToString(" · "), color = Color(0xFFC9D6F2), fontSize = 15.sp, maxLines = 1)
                }
            }
        }
    }
}
