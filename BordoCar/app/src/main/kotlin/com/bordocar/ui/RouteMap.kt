package com.bordocar.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bordocar.Drive
import com.bordocar.LL
import com.bordocar.Trip
import com.bordocar.Tracks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tan

/** Which routes are on the car screen: "hoje", "semana", or null (the dashboard). Opened by tapping KM HOJE / KM DA SEMANA. */
object RouteView {
    var show by mutableStateOf<String?>(null)
}

private val TripColors = listOf(Color(0xFF5CC8FF), Color(0xFFFF6A4E), Color(0xFF7CF2B8), Color(0xFFFFC24E), Color(0xFFC08CFF), Color(0xFFFF8AC8), Color(0xFF9FE870))
private val DayNames = listOf("Seg", "Ter", "Qua", "Qui", "Sex", "Sáb", "Dom")

/** The routes driven today or this week, drawn on a map (CARTO's dark map from OpenStreetMap; needs internet for the map itself). */
@Composable
fun RouteMapScreen(now: Long, padL: Dp, padT: Dp, padR: Dp, padB: Dp) {
    val week = RouteView.show == "semana"
    val from = if (week) Drive.weekStart() else Drive.todayStart()
    val trips by produceState(initialValue = emptyList<Pair<Trip, List<LL>>>(), RouteView.show) {
        value = withContext(Dispatchers.IO) {
            val list = Drive.trips().filter { it.start >= from }.sortedBy { it.start }.map { it to Tracks.load(it.start) }.toMutableList()
            val cs = Drive.currentStart(); val cur = Drive.current()
            if (cs >= from && cs > 0 && cur != null) list.add(Trip(cs, now, cur.first, cur.second) to Tracks.load(cs))
            list
        }
    }
    fun dayOf(t: Trip) = (java.util.Calendar.getInstance().apply { timeInMillis = t.start }.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7
    val colorOf: (Int, Trip) -> Color = { i, t -> if (week) TripColors[dayOf(t) % TripColors.size] else TripColors[i % TripColors.size] }
    val drawn = trips.mapIndexedNotNull { i, (t, pts) -> if (pts.size > 1) pts to colorOf(i, t) else null }
    val km = trips.sumOf { it.first.dist } / 1000.0
    val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale("pt", "BR"))

    Box(Modifier.fillMaxSize().background(Color(0xFF0B0F17))) {
        TileMap(drawn.map { it.first }, drawn.map { it.second }, Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize().padding(start = padL + 16.dp, top = padT + 8.dp, end = padR + 16.dp, bottom = padB + 8.dp)) {
            // title, the totals, and the button to go back to the dashboard
            Row(Modifier.fillMaxWidth().padding(end = 154.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(56.dp).clip(CircleShape).background(Color(0xE6101828)).border(1.dp, Color(0x55FFFFFF), CircleShape)
                    .carTap("rota-fechar") { RouteView.show = null }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Close, null, tint = Color.White, modifier = Modifier.size(32.dp))
                }
                Column(Modifier.padding(start = 16.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xCC101828)).padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Text(if (week) "Rotas da semana" else "Rotas de hoje", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                    Text(String.format(java.util.Locale("pt", "BR"), "%.1f km", km) + " · ${trips.size} " + (if (trips.size == 1) "viagem" else "viagens"),
                        color = Color(0xFFC9D6F2), fontSize = 16.sp)
                }
            }
            // which colour is which
            Column(Modifier.padding(top = 12.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xCC101828)).padding(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (week) {
                    val days = trips.groupBy { dayOf(it.first) }.toSortedMap()
                    for ((d, l) in days) LegendRow(TripColors[d % TripColors.size], DayNames[d], String.format(java.util.Locale("pt", "BR"), "%.1f km", l.sumOf { it.first.dist } / 1000.0))
                } else trips.takeLast(8).forEachIndexed { k, (t, _) ->
                    val i = trips.size - minOf(8, trips.size) + k
                    LegendRow(colorOf(i, t), fmt.format(java.util.Date(t.start)), String.format(java.util.Locale("pt", "BR"), "%.1f km", t.dist / 1000.0))
                }
                if (trips.isNotEmpty() && drawn.isEmpty()) Text("As rotas aparecem a partir das próximas viagens", color = Color(0xFFC9D6F2), fontSize = 14.sp)
                if (trips.isEmpty()) Text(if (week) "Nenhuma viagem nesta semana" else "Nenhuma viagem hoje", color = Color(0xFFC9D6F2), fontSize = 14.sp)
            }
        }
        MapCredits(Modifier.align(Alignment.BottomEnd).padding(end = padR + 10.dp, bottom = padB + 6.dp))
    }
}

/** The same map on the phone (Viagens): one trip, or a whole day, with a button to close it. */
@Composable
fun PhoneRouteMap(trips: List<Trip>, title: String, onClose: () -> Unit) {
    val data by produceState(initialValue = emptyList<Pair<Trip, List<LL>>>(), trips) {
        value = withContext(Dispatchers.IO) { trips.sortedBy { it.start }.map { it to Tracks.load(it.start) } }
    }
    var loaded by remember(trips) { mutableStateOf(false) }
    LaunchedEffect(data) { if (data.isNotEmpty()) loaded = true }
    val drawn = data.mapIndexedNotNull { i, (_, pts) -> if (pts.size > 1) pts to TripColors[i % TripColors.size] else null }
    val br = java.util.Locale("pt", "BR")
    val hm = java.text.SimpleDateFormat("HH:mm", br)
    Box(Modifier.fillMaxSize().background(Color(0xFF0B0F17))) {
        TileMap(drawn.map { it.first }, drawn.map { it.second }, Modifier.fillMaxSize(), fitW = 0.8f, fitH = 0.55f, shiftX = 0f)
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(CircleShape).background(Color(0xE6101828)).border(1.dp, Color(0x55FFFFFF), CircleShape)
                    .clickable { onClose() }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Close, null, tint = Color.White, modifier = Modifier.size(28.dp))
                }
                Column(Modifier.padding(start = 12.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xCC101828)).padding(horizontal = 14.dp, vertical = 6.dp)) {
                    Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                    Text(String.format(br, "%.1f km", trips.sumOf { it.dist } / 1000.0) + (if (trips.size > 1) " · ${trips.size} viagens" else " · ${trips.first().moving / 60} min"),
                        color = Color(0xFFC9D6F2), fontSize = 14.sp)
                }
            }
            if (data.size > 1) Column(Modifier.padding(top = 10.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xCC101828)).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)) {
                data.forEachIndexed { i, (t, _) -> LegendRow(TripColors[i % TripColors.size], hm.format(java.util.Date(t.start)), String.format(br, "%.1f km", t.dist / 1000.0)) }
            }
            if (loaded && drawn.isEmpty()) Text("Essa viagem é de antes das rotas serem guardadas: as rotas aparecem a partir das próximas viagens.",
                color = Color(0xFFC9D6F2), fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xCC101828)).padding(10.dp))
        }
        MapCredits(Modifier.align(Alignment.BottomEnd).padding(10.dp))
    }
}

@Composable
private fun LegendRow(c: Color, a: String, b: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 22.dp, height = 6.dp).clip(RoundedCornerShape(3.dp)).background(c))
        Text(a, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 10.dp))
        Text(b, color = Color(0xFFC9D6F2), fontSize = 15.sp, modifier = Modifier.padding(start = 8.dp))
    }
}

/** A map made of map tiles (fetched once and kept on the phone), fitted to the routes, with the routes drawn over it. */
@Composable
private fun TileMap(lines: List<List<LL>>, colors: List<Color>, modifier: Modifier, fitW: Float = 0.62f, fitH: Float = 0.72f, shiftX: Float = 0.1f) {
    val ctx = LocalContext.current.applicationContext
    val tiles = remember { mutableStateMapOf<String, ImageBitmap>() }
    BoxWithConstraints(modifier) {
        val w = constraints.maxWidth.toFloat(); val h = constraints.maxHeight.toFloat()
        val dens = androidx.compose.ui.platform.LocalDensity.current.density
        val tile = 256f * (if (dens > 1.4f) 2f else 1f) * 0.75f      // on-screen size of a tile, in pixels
        val all = lines.flatten()
        val center0 = Drive.pos ?: LL(-5.0892, -42.8016)
        // the closest zoom that fits all the routes (leaving room for the title on the left and top)
        var z = 15
        if (all.isNotEmpty()) {
            z = 17
            while (z > 3) {
                val xs = all.map { mx(it.lon, z, tile) }; val ys = all.map { my(it.lat, z, tile) }
                if ((xs.max() - xs.min()) <= w * fitW && (ys.max() - ys.min()) <= h * fitH) break
                z--
            }
        }
        val cxw: Float; val cyw: Float
        if (all.isNotEmpty()) {
            val xs = all.map { mx(it.lon, z, tile) }; val ys = all.map { my(it.lat, z, tile) }
            cxw = (xs.max() + xs.min()) / 2f - w * shiftX      // (in the car) a little to the left, so the routes sit right of the legend
            cyw = (ys.max() + ys.min()) / 2f - h * 0.04f
        } else { cxw = mx(center0.lon, z, tile); cyw = my(center0.lat, z, tile) }
        val ox = cxw - w / 2f; val oy = cyw - h / 2f
        val n = 1 shl z
        val tx0 = floor(ox / tile).toInt(); val tx1 = floor((ox + w) / tile).toInt()
        val ty0 = floor(oy / tile).toInt().coerceAtLeast(0); val ty1 = floor((oy + h) / tile).toInt().coerceAtMost(n - 1)
        val need = buildList { for (x in tx0..tx1) for (y in ty0..ty1) add(Triple(z, ((x % n) + n) % n, y)) }
        LaunchedEffect(z, tx0, ty0, tx1, ty1) {
            for ((zz, x, y) in need) {
                val k = "$zz/$x/$y"
                if (tiles.containsKey(k)) continue
                val b = withContext(Dispatchers.IO) { loadTile(ctx, zz, x, y) }
                if (b != null) tiles[k] = b
            }
        }
        Canvas(Modifier.fillMaxSize()) {
            for (x in tx0..tx1) for (y in ty0..ty1) {
                val img = tiles["$z/${((x % n) + n) % n}/$y"] ?: continue
                drawImage(img, srcOffset = IntOffset.Zero, srcSize = IntSize(img.width, img.height),
                    dstOffset = IntOffset((x * tile - ox).toInt(), (y * tile - oy).toInt()), dstSize = IntSize(tile.toInt() + 1, tile.toInt() + 1))
            }
            lines.forEachIndexed { i, pts ->
                val path = Path()
                pts.forEachIndexed { j, p -> val px = mx(p.lon, z, tile) - ox; val py = my(p.lat, z, tile) - oy; if (j == 0) path.moveTo(px, py) else path.lineTo(px, py) }
                drawPath(path, Color.Black.copy(alpha = 0.55f), style = Stroke(11f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(path, colors[i], style = Stroke(6.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                val a = pts.first(); val b = pts.last()
                drawCircle(Color.White, 8f, Offset(mx(a.lon, z, tile) - ox, my(a.lat, z, tile) - oy))
                drawCircle(colors[i], 5f, Offset(mx(a.lon, z, tile) - ox, my(a.lat, z, tile) - oy))
                drawCircle(colors[i], 10f, Offset(mx(b.lon, z, tile) - ox, my(b.lat, z, tile) - oy))
                drawCircle(Color.White, 4f, Offset(mx(b.lon, z, tile) - ox, my(b.lat, z, tile) - oy))
            }
        }
    }
}

private fun mx(lon: Double, z: Int, tile: Float): Float = ((lon + 180.0) / 360.0 * 2.0.pow(z) * tile).toFloat()
private fun my(lat: Double, z: Int, tile: Float): Float {
    val r = Math.toRadians(lat.coerceIn(-85.0, 85.0))
    return ((1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * 2.0.pow(z) * tile).toFloat()
}

/**
 * One map tile: from the phone's cache, or downloaded (and kept for next time). Mapbox's dark map with the key of the turn map (CARTO
 * started asking for a key of its own and covers its map with "API KEY REQUIRED"); CARTO only when there's no Mapbox key.
 */
private fun loadTile(ctx: android.content.Context, z: Int, x: Int, y: Int): ImageBitmap? {
    val tok = com.bordocar.MapRoute.token(ctx)
    val mb = tok.startsWith("pk.")
    // the old tiles (CARTO, now with the "API KEY REQUIRED" over them) go away
    if (!oldGone) { oldGone = true; try { File(ctx.cacheDir, "tiles").deleteRecursively() } catch (e: Exception) { } }
    val f = File(File(ctx.cacheDir, if (mb) "tiles-mapbox" else "tiles-carto").apply { mkdirs() }, "${z}_${x}_$y.png")
    try {
        if (!f.exists() || f.length() == 0L) {
            val sub = "abcd"[(x + y) % 4]
            val url = if (mb) "https://api.mapbox.com/styles/v1/mapbox/dark-v11/tiles/256/$z/$x/$y@2x?access_token=$tok"
                      else "https://$sub.basemaps.cartocdn.com/dark_all/$z/$x/$y@2x.png"
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 8000
            c.setRequestProperty("User-Agent", "BordoCar/1.0 (Android)")
            try { if (c.responseCode != 200) return null; f.writeBytes(c.inputStream.readBytes()) } finally { c.disconnect() }
        }
        return BitmapFactory.decodeFile(f.path)?.asImageBitmap()
    } catch (e: Exception) { try { f.delete() } catch (x: Exception) { }; return null }
}

@Volatile private var oldGone = false

/** The credits under the map: Mapbox's (its logo and text) when its map is used, CARTO's otherwise. */
@Composable
private fun MapCredits(modifier: Modifier) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val mb = remember { com.bordocar.MapRoute.token(ctx).startsWith("pk.") }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (mb) {
            val id = remember { ctx.resources.getIdentifier("mapbox_logo_icon", "drawable", ctx.packageName) }
            if (id != 0) androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(id), "Mapbox",
                Modifier.padding(end = 8.dp).height(18.dp))
        }
        Text(if (mb) "© Mapbox · © OpenStreetMap" else "© OpenStreetMap · © CARTO", color = Color(0x99FFFFFF), fontSize = 11.sp)
    }
}
