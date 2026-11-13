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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LocalGasStation
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.WorkOutline
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bordocar.Drive
import com.bordocar.Limits
import com.bordocar.NavNotice
import com.bordocar.NowPlaying
import com.bordocar.Prefs
import com.bordocar.Weather
import java.util.Locale

/**
 * "Adaptar ao carro": the dashboard made to look like part of the car's own screen (a Peugeot 208), not like an app. The graphite
 * with fine diagonal lines of the car's side bars as the background, no boxes or rounded frames (thin lines part the areas), thin
 * white line icons and thin type; the bottom darkens into the black strip of Android Auto. Only Normal and Estrada change;
 * Esportivo and Música stay as they are. Off by default.
 */
object CarLook {
    /** It's the dashboard's only look now (the option "Adaptar ao carro" became the default). */
    val on: Boolean get() = true
    fun load(ctx: Context) { }

    /** The background: pure black, the same as Android Auto's strip and the screen's frame (a grey never matched the car's). */
    val Graphite = Color.Black
    val Line = Color(0xFF26272B)
    val Hair = Color(0x1AFFFFFF)
    val Txt = Color(0xFFF4F4F6)
    val Sub = Color(0xFFA8A8B0)
    val Cap = Color(0xFF8E8E96)
    /** The blue of the 208's 3D cluster (its needle and "ALCANCE"): the dials, the titles, today's bar. */
    val Blue1 = Color(0xFF1E8FD0)
    val Blue2 = Color(0xFF5FD8FF)
    /** Estrada keeps its own look even when adapted. */
    const val road = false
}

private val BR = Locale("pt", "BR")

/** The background of the adapted look: pure black, so the dashboard melts into the screen's frame and Android Auto's strip. */
@Composable
fun GraphiteBg(modifier: Modifier) {
    Box(modifier.background(CarLook.Graphite))
}

/** The same type everywhere in the adapted look: a plain sans (the car's screens use one). */
@Composable
fun CarType(content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = FontFamily.SansSerif), content = content)

@Composable
private fun Cap(text: String, modifier: Modifier = Modifier) =
    Text(text, color = CarLook.Blue2, fontSize = 11.sp, letterSpacing = 3.sp, maxLines = 1, modifier = modifier)

@Composable
private fun VLine(modifier: Modifier = Modifier) = Box(modifier.width(1.dp).fillMaxHeight(0.86f).background(CarLook.Hair))

/**
 * The line along the top: the date and the phone's battery on the left, the town and the weather on the right (no temperature:
 * the car shows it). [clock]: the time first, big (Estrada).
 */
@Composable
fun CarTopLine(now: Long, bat: Int, charging: Boolean, wx: Weather.Now?, city: String, modifier: Modifier, clock: Boolean = false, endPad: Dp = 170.dp) {
    val d = java.util.Date(now)
    Row(modifier.padding(start = 6.dp, end = endPad), verticalAlignment = Alignment.CenterVertically) {
        if (clock) Text(java.text.SimpleDateFormat("HH:mm", BR).format(d), color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Light,
            modifier = Modifier.padding(end = 14.dp))
        Text(java.text.SimpleDateFormat("EEEE, d 'de' MMMM", BR).format(d).replaceFirstChar { it.uppercase() }.replace("-feira", ""),
            color = Color(0xFFC4C4CC), fontSize = 16.sp, fontWeight = FontWeight.Light, maxLines = 1)
        if (bat >= 0) {
            val col = if (bat < 15) Color(0xFFFF4D4D) else if (bat < 30) Color(0xFFFFA63D) else Color(0xFFC4C4CC)
            Canvas(Modifier.padding(start = 18.dp).size(26.dp, 13.dp)) {
                val w = size.width; val h = size.height
                drawRoundRect(Color(0xFFC4C4CC), Offset(1f, 1f), Size(w * 0.86f - 2f, h - 2f), CornerRadius(h * 0.2f), style = Stroke(1.6f))
                drawRoundRect(Color(0xFFC4C4CC), Offset(w * 0.88f, h * 0.3f), Size(w * 0.08f, h * 0.4f), CornerRadius(2f))
                drawRoundRect(col, Offset(3.5f, 3.5f), Size((w * 0.86f - 7f) * (bat / 100f), h - 7f), CornerRadius(h * 0.1f))
            }
            Text("$bat%" + (if (charging) " ⚡" else ""), color = Color(0xFFC4C4CC), fontSize = 16.sp, fontWeight = FontWeight.Light, modifier = Modifier.padding(start = 6.dp))
        }
        Spacer(Modifier.weight(1f))
        if (wx != null) Text(Weather.emoji(wx.code) + "  " + listOf(city, Weather.text(wx.code)).filter { it.isNotBlank() }.joinToString(" · "),
            color = Color(0xFFC4C4CC), fontSize = 16.sp, fontWeight = FontWeight.Light, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Normal, adapted to the car: on top the music, the speed and the way you're going; under a thin line the shortcuts (or the next
 * turn while navigating), today's km and the week's. All straight on the graphite.
 */
@Composable
fun NormalAdapted(now: Long, bat: Int, charging: Boolean, wx: Weather.Now?, city: String, actions: DashActions, navigating: Boolean,
                  padL: Dp, padT: Dp, padR: Dp, padB: Dp) = CarType {
    Box(Modifier.fillMaxSize()) {
        GraphiteBg(Modifier.fillMaxSize())
        // into the black strip of Android Auto
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(padB + 30.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black))))
        Column(Modifier.fillMaxSize().padding(start = padL + 12.dp, top = padT, end = padR + 12.dp, bottom = padB + 6.dp)) {
            // (the weather all the way to the right: Android Auto's buttons cover it only while they show)
            CarTopLine(now, bat, charging, wx, city, Modifier.fillMaxWidth().height(52.dp), clock = true, endPad = 6.dp)
            Row(Modifier.fillMaxWidth().weight(1.15f), verticalAlignment = Alignment.CenterVertically) {
                MusicFlat(Modifier.weight(0.48f).fillMaxHeight())
                VLine(Modifier.padding(horizontal = 14.dp))
                SpeedFlat(Modifier.weight(0.26f).fillMaxHeight())
                HeadingFlat(Modifier.weight(0.26f).fillMaxHeight())
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(CarLook.Hair))
            Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
                if (navigating) TurnFlat(Modifier.weight(0.48f).fillMaxHeight()) else ShortcutsFlat(actions, Modifier.weight(0.48f).fillMaxHeight())
                VLine(Modifier.padding(horizontal = 14.dp))
                // a tap shows the routes driven today / this week on a map
                TodayFlat(Modifier.weight(0.26f).fillMaxHeight().carTap("km-hoje") { RouteView.show = "hoje" })
                WeekFlat(Modifier.weight(0.26f).fillMaxHeight().carTap("km-semana") { RouteView.show = "semana" })
            }
        }
    }
}

private fun mmss(ms: Long): String { val s = (ms / 1000L).coerceAtLeast(0L); return "%d:%02d".format(s / 60, s % 60) }

@Composable
private fun MusicFlat(modifier: Modifier) {
    BoxWithConstraints(modifier.padding(vertical = 10.dp)) {
        val h = maxHeight.value
        if (!NowPlaying.allowed) {
            Column(Modifier.align(Alignment.CenterStart)) {
                Cap("MÚSICA")
                Text("Para mostrar a música e a próxima curva, abra o Bordo Car no celular e toque em \"Permitir acesso às notificações\".",
                    color = CarLook.Sub, fontSize = 15.sp, fontWeight = FontWeight.Light, modifier = Modifier.padding(top = 8.dp))
            }
            return@BoxWithConstraints
        }
        val art = (h * 0.9f).coerceIn(56f, 170f)
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(art.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xFF2A2B30)), contentAlignment = Alignment.Center) {
                val bmp = NowPlaying.art
                if (bmp != null) Image(bmp.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Icon(Icons.Rounded.MusicNote, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size((art * 0.4f).dp))
            }
            Column(Modifier.weight(1f).padding(start = 20.dp), verticalArrangement = Arrangement.Center) {
                androidx.compose.runtime.key(NowPlaying.title) {
                    Text(NowPlaying.title.ifBlank { "Nada tocando" }, color = CarLook.Txt, fontSize = (h * 0.18f).coerceIn(18f, 28f).sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                        modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 2500, repeatDelayMillis = 2500, velocity = 40.dp))
                }
                if (NowPlaying.artist.isNotBlank()) Text(NowPlaying.artist, color = CarLook.Sub, fontSize = (h * 0.11f).coerceIn(13f, 17f).sp, fontWeight = FontWeight.Light,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                val d = NowPlaying.duration
                if (d > 0) {
                    val pos = NowPlaying.position.coerceIn(0L, d)
                    Box(Modifier.padding(top = (h * 0.09f).coerceIn(8f, 18f).dp).fillMaxWidth().height(3.dp).background(Color(0x1FFFFFFF))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth((pos.toFloat() / d).coerceIn(0f, 1f)).background(Brush.horizontalGradient(listOf(CarLook.Blue1, CarLook.Blue2))))
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 5.dp)) {
                        Text(mmss(pos), color = CarLook.Cap, fontSize = 12.sp)
                        Spacer(Modifier.weight(1f))
                        Text("−" + mmss(d - pos), color = CarLook.Cap, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun SpeedFlat(modifier: Modifier) {
    val kmh = Math.round((Drive.speed * 3.6).coerceAtLeast(0.0)).toInt()
    val lim = Limits.kmh
    val over = lim != null && kmh > lim + 3
    BoxWithConstraints(modifier.padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
        val d = minOf(maxWidth, maxHeight)
        Canvas(Modifier.size(d)) {
            val sw = 4.dp.toPx()
            val tl = Offset(sw / 2, sw / 2); val sz = Size(size.width - sw, size.height - sw)
            drawArc(Color(0xFF3A3B40), 135f, 270f, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
            val f = (kmh / 160f).coerceIn(0f, 1f)
            if (f > 0.003f) {
                if (over) drawArc(Color(0xFFFF4D3D), 135f, 270f * f, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
                else {
                    // the blue needle of the cluster, with its glow
                    drawArc(CarLook.Blue2.copy(alpha = 0.3f), 135f, 270f * f, false, tl, sz, style = Stroke(sw * 3f, cap = StrokeCap.Round))
                    drawArc(Brush.sweepGradient(listOf(CarLook.Blue1, CarLook.Blue2, CarLook.Blue1)), 135f, 270f * f, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
                }
            }
        }
        val big = (d.value * 0.32f).coerceIn(18f, 60f)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$kmh", color = if (over) Color(0xFFFF6B5A) else Color.White, fontSize = big.sp, lineHeight = (big * 1.1f).sp, fontWeight = FontWeight.Light)
            Text("km/h", color = CarLook.Cap, fontSize = 12.sp)
        }
    }
}

private fun cardinal(deg: Double): String = listOf("N", "NE", "L", "SE", "S", "SO", "O", "NO")[(((deg % 360) + 360) % 360 / 45.0 + 0.5).toInt() % 8]

@Composable
private fun HeadingFlat(modifier: Modifier) {
    val hd = Drive.heading
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val big = (maxHeight.value * 0.34f).coerceIn(28f, 64f)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Cap("RUMO")
            Text(if (hd != null) cardinal(hd) else "–", color = Color.White, fontSize = big.sp, lineHeight = (big * 1.15f).sp, fontWeight = FontWeight.ExtraLight)
            Text(if (hd != null) "${Math.round(hd)}°" else "aguardando o GPS", color = CarLook.Cap, fontSize = 13.sp)
        }
    }
}

@Composable
private fun ShortcutsFlat(actions: DashActions, modifier: Modifier) {
    BoxWithConstraints(modifier) {
        val h = maxHeight.value
        val withName = h >= 70f
        val sz = (h * 0.34f).coerceIn(26f, 40f)
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            for ((ic, name, id) in listOf(
                Triple(Icons.Outlined.Home, "Casa", "sc-home"), Triple(Icons.Outlined.WorkOutline, "Trabalho", "sc-work"),
                Triple(Icons.Outlined.StarOutline, "Favoritos", "sc-favs"), Triple(Icons.Outlined.LocalGasStation, "Postos", "sc-fuel"),
                Triple(Icons.Outlined.Search, "Buscar", "sc-search"))) {
                ShortcutFlat(ic, name, id, sz, withName, Modifier.weight(1f)) {
                    when (id) { "sc-home" -> actions.home(); "sc-work" -> actions.work(); "sc-favs" -> actions.favourites(); "sc-fuel" -> actions.fuel(); else -> actions.search() }
                }
            }
        }
    }
}

@Composable
private fun ShortcutFlat(icon: ImageVector, label: String, id: String, sz: Float, withName: Boolean, modifier: Modifier, action: () -> Unit) {
    Column(modifier.carTap(id, action), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = Color(0xFFECECF0), modifier = Modifier.size(sz.dp))
        if (withName) Text(label, color = Color(0xFFD0D0D6), fontSize = 13.sp, fontWeight = FontWeight.Light, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
    }
}

/** The next turn of Waze / Google Maps, flat: the arrow, the distance, the street. */
@Composable
private fun TurnFlat(modifier: Modifier) {
    BoxWithConstraints(modifier.padding(vertical = 8.dp)) {
        val h = maxHeight.value
        val big = (h * 0.3f).coerceIn(20f, 44f)
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            val ic = NavNotice.icon
            val isz = (h * 0.62f).coerceIn(34f, 80f)
            if (ic != null) Image(ic.asImageBitmap(), null, colorFilter = ColorFilter.tint(CarLook.Blue2), modifier = Modifier.size(isz.dp))
            else Icon(Icons.Rounded.Navigation, null, tint = CarLook.Blue2, modifier = Modifier.size(isz.dp))
            Column(Modifier.padding(start = 18.dp)) {
                Cap("PRÓXIMA CURVA · " + NavNotice.app.uppercase(BR))
                val (dist, rest) = NavNotice.split(NavNotice.title, NavNotice.text)
                if (dist != null) Text(dist, color = Color.White, fontSize = big.sp, lineHeight = (big * 1.12f).sp, fontWeight = FontWeight.Light, maxLines = 1)
                val line = if (dist != null) rest else NavNotice.title
                if (line.isNotBlank()) androidx.compose.runtime.key(line) {
                    Text(line, color = CarLook.Txt, fontSize = (big * 0.5f).coerceIn(14f, 22f).sp, maxLines = 1,
                        modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 2000, repeatDelayMillis = 2000, velocity = 40.dp))
                }
                if (NavNotice.sub.isNotBlank() && h > 90) Text(NavNotice.sub, color = CarLook.Sub, fontSize = 13.sp, fontWeight = FontWeight.Light, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun TodayFlat(modifier: Modifier) {
    @Suppress("UNUSED_VARIABLE") val t = Drive.tick
    val (m, secs) = Drive.today()
    val km = m / 1000.0
    val mins = (secs / 60L).toInt()
    val n = Drive.todayTrips()
    BoxWithConstraints(modifier, contentAlignment = Alignment.CenterStart) {
        val big = (maxHeight.value * 0.3f).coerceIn(24f, 48f)
        Column {
            Cap("HOJE")
            Text(buildAnnotatedString {
                append(if (km < 100) String.format(BR, "%.1f", km) else "${Math.round(km)}")
                pushStyle(SpanStyle(fontSize = (big * 0.36f).sp, color = CarLook.Cap)); append(" km"); pop()
            }, color = Color.White, fontSize = big.sp, lineHeight = (big * 1.15f).sp, fontWeight = FontWeight.ExtraLight, maxLines = 1)
            val drive = if (mins < 60) "$mins min" else "${mins / 60}h ${"%02d".format(mins % 60)}"
            val avg = if (secs >= 60) "média ${Math.round(km / (secs / 3600.0))} km/h" else null
            Text(listOfNotNull(drive, avg, "$n ${if (n == 1) "viagem" else "viagens"}").joinToString(" · "), color = CarLook.Sub, fontSize = 13.sp,
                fontWeight = FontWeight.Light, maxLines = 2)
        }
    }
}

@Composable
private fun WeekFlat(modifier: Modifier) {
    @Suppress("UNUSED_VARIABLE") val t = Drive.tick
    val days = Drive.week()
    val today = (java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7
    val total = days.sum() / 1000.0
    BoxWithConstraints(modifier.padding(start = 10.dp, top = 12.dp, bottom = 10.dp)) {
        val big = (maxHeight.value * 0.2f).coerceIn(18f, 30f)
        Column(Modifier.fillMaxSize()) {
            Cap("SEMANA")
            Text(buildAnnotatedString {
                append(if (total < 100) String.format(BR, "%.1f", total) else "${Math.round(total)}")
                pushStyle(SpanStyle(fontSize = 13.sp, color = CarLook.Cap)); append(" km"); pop()
            }, color = Color.White, fontSize = big.sp, lineHeight = (big * 1.15f).sp, fontWeight = FontWeight.ExtraLight)
            val max = (days.maxOrNull() ?: 0.0).coerceAtLeast(1000.0)
            Canvas(Modifier.fillMaxWidth().weight(1f).padding(top = 6.dp)) {
                val gap = size.width * 0.06f
                val bw = (size.width - gap * 6) / 7f
                val base = 3.dp.toPx()
                for (i in 0 until 7) {
                    val x = i * (bw + gap)
                    val f = (days[i] / max).toFloat().coerceIn(0f, 1f)
                    val bh = if (f > 0f) (size.height * f).coerceAtLeast(base * 2) else base
                    val col = if (f <= 0f) Color(0xFF3A3B40) else if (i == today) CarLook.Blue2 else Color.White.copy(alpha = 0.55f)
                    drawRoundRect(col, Offset(x, size.height - bh), Size(bw, bh), CornerRadius(2.dp.toPx()))
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 3.dp)) {
                for ((i, l) in listOf("S", "T", "Q", "Q", "S", "S", "D").withIndex())
                    Text(l, color = if (i == today) CarLook.Blue2 else CarLook.Cap, fontSize = 10.sp, fontWeight = if (i == today) FontWeight.Bold else FontWeight.Normal,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** (Estrada) the scene's edges melting into the graphite of the car's side bars, and its bottom into the black strip. */
@Composable
fun RoadEdges(padB: Dp) {
    Box(Modifier.fillMaxSize()) {
        val g = CarLook.Graphite
        Box(Modifier.align(Alignment.CenterStart).fillMaxHeight().width(80.dp).background(Brush.horizontalGradient(listOf(g, g.copy(alpha = 0f)))))
        Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(80.dp).background(Brush.horizontalGradient(listOf(g.copy(alpha = 0f), g))))
        Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(70.dp).background(Brush.verticalGradient(listOf(g, g.copy(alpha = 0f)))))
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(padB + 40.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black))))
    }
}
