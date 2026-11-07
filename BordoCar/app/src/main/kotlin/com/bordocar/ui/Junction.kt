package com.bordocar.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.bordocar.Drive
import com.bordocar.LL
import com.bordocar.MapRoute
import com.bordocar.dist
import com.mapbox.common.MapboxOptions
import com.mapbox.geojson.Feature
import com.mapbox.geojson.FeatureCollection
import com.mapbox.geojson.LineString
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.MapInitOptions
import com.mapbox.maps.MapView
import com.mapbox.maps.Style
import com.mapbox.maps.extension.style.expressions.generated.Expression
import com.mapbox.maps.extension.style.layers.addLayer
import com.mapbox.maps.extension.style.layers.generated.CircleLayer
import com.mapbox.maps.extension.style.layers.generated.LineLayer
import com.mapbox.maps.extension.style.layers.generated.SymbolLayer
import com.mapbox.maps.extension.style.layers.properties.generated.LineCap
import com.mapbox.maps.extension.style.layers.properties.generated.LineJoin
import com.mapbox.maps.extension.style.sources.addSource
import com.mapbox.maps.extension.style.sources.generated.GeoJsonSource
import com.mapbox.maps.extension.style.sources.getSourceAs
import com.mapbox.maps.plugin.animation.MapAnimationOptions
import com.mapbox.maps.plugin.animation.easeTo
import com.mapbox.maps.plugin.compass.compass
import com.mapbox.maps.plugin.gestures.gestures
import com.mapbox.maps.plugin.scalebar.scalebar
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin

/** What the map of the next turn shows: the turn, the route line around it, the streets to count, and which one it is. */
class JunctionInfo(val turnAt: LL, val line: List<LL>, val numbered: List<LL>, val nth: Int?, val street: String)

private fun ahead(p: LL, deg: Double, m: Double) = MapRoute.ahead(p, deg, m)

/**
 * "Ver prévia" of the map of the turn: for 16 s the car screen (and the phone's preview) shows Estrada with the map, driving along a
 * real route near you (asked of Mapbox) up to its first turn, without having to drive.
 */
object JunctionPreview {
    const val MS = 16_000L
    var startedAt by androidx.compose.runtime.mutableLongStateOf(0L)
        private set
    var origin: LL? = null; private set
    fun active(now: Long) = startedAt > 0L && now - startedAt in 0 until MS
    @android.annotation.SuppressLint("MissingPermission")
    fun start(ctx: android.content.Context) {
        val here = Drive.pos ?: try {
            val m = ctx.getSystemService(android.location.LocationManager::class.java)
            (m.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER) ?: m.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER))?.let { LL(it.latitude, it.longitude) }
        } catch (e: Exception) { null } ?: LL(-5.0892, -42.8016)
        origin = here
        if (MapRoute.enabled(ctx)) MapRoute.request(ctx.applicationContext, here, ahead(here, Drive.heading ?: 35.0, 900.0), keep = false)
        startedAt = System.currentTimeMillis()
    }
    /** Where the preview's car is now, which way it goes, how far the turn is, which turn and its street. */
    class Sim(val here: LL, val heading: Double, val metres: Double, val kind: String, val street: String)
    fun sim(now: Long): Sim {
        val t = (now - startedAt) / 1000.0
        val turn = MapRoute.turns.firstOrNull { it.alongM > 200.0 && it.kind in setOf("direita", "esquerda", "leve-direita", "leve-esquerda") } ?: MapRoute.turns.firstOrNull()
        if (turn != null) {
            val m = (turn.alongM - 380.0 + t * 24.0).coerceAtMost(turn.alongM - 12.0).coerceAtLeast(0.0)
            val p = MapRoute.pointAt(m)
            if (p != null) return Sim(p.first, p.second, turn.alongM - m, turn.kind, turn.street)
        }
        val o = origin ?: LL(-5.0892, -42.8016)
        return Sim(o, 0.0, (260.0 - t * 14.0).coerceAtLeast(30.0), "direita", "Rua de exemplo")
    }
}

/**
 * Works out the turn: on Mapbox's route when there is one (exact, with the streets to count), otherwise straight ahead at the
 * distance of the navigation notice.
 */
fun junctionInfo(here: LL, metres: Double, kind: String?, heading: Double?): JunctionInfo {
    val t = MapRoute.next(here, metres, kind)
    if (t != null) {
        val pos = MapRoute.position(here).first
        val line = MapRoute.slice(pos - 30.0, t.alongM + 120.0)
        val crossings = t.sideStreets.filter { MapRoute.position(it).first in (pos + 3.0)..(t.alongM - 8.0) }
        val numbered = crossings + t.at
        return JunctionInfo(t.at, if (line.size >= 2) line else listOf(here, t.at), if (numbered.size >= 2) numbered else emptyList(),
            if (numbered.size >= 2) numbered.size else null, t.street)
    }
    val hd = heading ?: 0.0
    val at = ahead(here, hd, metres)
    val turnDeg = when (kind) { "direita" -> 90.0; "leve-direita" -> 40.0; "esquerda" -> -90.0; "leve-esquerda" -> -40.0; "retorno" -> 180.0; else -> 0.0 }
    return JunctionInfo(at, listOf(here, at, ahead(at, hd + turnDeg, 70.0)), emptyList(), null, "")
}

private fun pt(p: LL) = Point.fromLngLat(p.lon, p.lat)
private fun lineFc(pts: List<LL>) = FeatureCollection.fromFeatures(if (pts.size >= 2) listOf(Feature.fromGeometry(LineString.fromLngLats(pts.map(::pt)))) else emptyList())
private fun pointsFc(pts: List<LL>, from: Int = 1) = FeatureCollection.fromFeatures(pts.mapIndexed { i, p -> Feature.fromGeometry(pt(p)).also { it.addStringProperty("n", "${i + from}") } })

private fun styleJson(preset: String) = org.json.JSONObject()
    .put("version", 8)
    .put("glyphs", "mapbox://fonts/mapbox/{fontstack}/{range}.pbf")
    .put("imports", org.json.JSONArray().put(org.json.JSONObject().put("id", "basemap").put("url", Style.STANDARD)
        .put("config", org.json.JSONObject().put("lightPreset", preset).put("showPointOfInterestLabels", false).put("showTransitLabels", false))))
    .put("sources", org.json.JSONObject()).put("layers", org.json.JSONArray()).toString()

/**
 * The Mapbox map of the next turn, seen from above (the map turns so the way you're going is up): your position, the route in
 * blue, the bit of the turn in white, and the streets on that side numbered, the one to take in yellow. It comes in from a tilted
 * view, like Horizon Nav, then flattens out over the junction and follows you to it.
 */
@Composable
fun JunctionMap(now: Long, info: JunctionInfo, preset: String, here0: LL, heading: Double?, modifier: Modifier, textureView: Boolean = false) {
    val ctx = LocalContext.current
    val token = MapRoute.token(ctx)
    var view by remember { mutableStateOf<MapView?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var first by remember { mutableStateOf(true) }
    AndroidView(factory = { c ->
        try { MapboxOptions.accessToken = token } catch (e: Throwable) { }
        val mv = MapView(c, MapInitOptions(c, textureView = textureView, styleUri = null,
            cameraOptions = CameraOptions.Builder().center(pt(here0)).zoom(15.2).pitch(60.0).bearing(heading ?: 0.0).build()))
        try { mv.scalebar.enabled = false } catch (e: Throwable) { }
        try { mv.compass.enabled = false } catch (e: Throwable) { }
        try { mv.gestures.updateSettings { scrollEnabled = false; rotateEnabled = false; pitchEnabled = false; pinchToZoomEnabled = false; doubleTapToZoomInEnabled = false } } catch (e: Throwable) { }
        try {
            mv.mapboxMap.loadStyle(styleJson(preset)) { st ->
                try {
                    st.addSource(GeoJsonSource.Builder("jr-route").featureCollection(lineFc(emptyList())).build())
                    st.addSource(GeoJsonSource.Builder("jr-turn").featureCollection(lineFc(emptyList())).build())
                    st.addSource(GeoJsonSource.Builder("jr-num").featureCollection(pointsFc(emptyList())).build())
                    st.addSource(GeoJsonSource.Builder("jr-hi").featureCollection(pointsFc(emptyList())).build())
                    st.addSource(GeoJsonSource.Builder("jr-me").featureCollection(pointsFc(emptyList())).build())
                    st.addLayer(LineLayer("jr-route-case", "jr-route").lineColor("#0B2A66").lineWidth(16.0).lineCap(LineCap.ROUND).lineJoin(LineJoin.ROUND).lineEmissiveStrength(1.0))
                    st.addLayer(LineLayer("jr-route-line", "jr-route").lineColor("#3D8BFF").lineWidth(10.0).lineCap(LineCap.ROUND).lineJoin(LineJoin.ROUND).lineEmissiveStrength(1.0))
                    st.addLayer(LineLayer("jr-turn-line", "jr-turn").lineColor("#FFFFFF").lineWidth(6.0).lineCap(LineCap.ROUND).lineJoin(LineJoin.ROUND).lineEmissiveStrength(1.0))
                    st.addLayer(CircleLayer("jr-num-c", "jr-num").circleRadius(13.0).circleColor("#151B27").circleStrokeColor("#FFFFFF").circleStrokeWidth(2.0).circleEmissiveStrength(1.0))
                    st.addLayer(SymbolLayer("jr-num-t", "jr-num").textField(Expression.get("n")).textSize(15.0).textColor("#FFFFFF")
                        .textFont(listOf("DIN Pro Bold", "Arial Unicode MS Bold")).textAllowOverlap(true).textIgnorePlacement(true))
                    st.addLayer(CircleLayer("jr-hi-c", "jr-hi").circleRadius(16.0).circleColor("#FFC24E").circleStrokeColor("#FFFFFF").circleStrokeWidth(2.5).circleEmissiveStrength(1.0))
                    st.addLayer(SymbolLayer("jr-hi-t", "jr-hi").textField(Expression.get("n")).textSize(17.0).textColor("#111111")
                        .textFont(listOf("DIN Pro Bold", "Arial Unicode MS Bold")).textAllowOverlap(true).textIgnorePlacement(true))
                    st.addLayer(CircleLayer("jr-me-glow", "jr-me").circleRadius(20.0).circleColor("#3D8BFF").circleOpacity(0.3).circleEmissiveStrength(1.0))
                    st.addLayer(CircleLayer("jr-me-dot", "jr-me").circleRadius(10.0).circleColor("#FFFFFF").circleStrokeColor("#3D8BFF").circleStrokeWidth(4.0).circleEmissiveStrength(1.0))
                } catch (e: Throwable) { android.util.Log.w("BordoCar", "junction layers", e) }
                style = st
            }
        } catch (e: Throwable) { android.util.Log.w("BordoCar", "junction style", e) }
        view = mv
        mv
    }, modifier = modifier)
    DisposableEffect(Unit) { onDispose { try { view?.onStop(); view?.onDestroy() } catch (e: Throwable) { } } }

    // follows you each second: the data on the map, and the camera between you and the turn
    LaunchedEffect(style, now / 500L, info) {
        val st = style ?: return@LaunchedEffect
        val mv = view ?: return@LaunchedEffect
        val here = here0
        try {
            st.getSourceAs<GeoJsonSource>("jr-route")?.featureCollection(lineFc(info.line))
            // the white stretch of the turn itself: from 25 m before to 40 m after it
            val ti = info.line.indices.minByOrNull { dist(info.line[it], info.turnAt) } ?: 0
            var a = ti; while (a > 0 && dist(info.line[a], info.turnAt) < 25.0) a--
            var b = ti; while (b < info.line.size - 1 && dist(info.line[b], info.turnAt) < 40.0) b++
            st.getSourceAs<GeoJsonSource>("jr-turn")?.featureCollection(lineFc(info.line.subList(a, b + 1)))
            val nums = info.numbered
            st.getSourceAs<GeoJsonSource>("jr-num")?.featureCollection(pointsFc(nums.dropLast(1)))
            st.getSourceAs<GeoJsonSource>("jr-hi")?.featureCollection(if (nums.isNotEmpty()) pointsFc(listOf(nums.last()), nums.size) else pointsFc(emptyList()))
            st.getSourceAs<GeoJsonSource>("jr-me")?.featureCollection(pointsFc(listOf(here)))
        } catch (e: Throwable) { }
        // seen from above: the middle between you and the turn, turned so the way to the turn is up, zoomed to fit both
        val d = dist(here, info.turnAt).coerceAtLeast(40.0)
        val mid = LL((here.lat + info.turnAt.lat) / 2, (here.lon + info.turnAt.lon) / 2)
        val brg = if (d > 15.0) bearing(here, info.turnAt) else (heading ?: 0.0)
        val hDp = (mv.height / mv.resources.displayMetrics.density).coerceAtLeast(200f)
        val mpp = d / (hDp * 0.42)
        val zoom = (ln(40_075_016.686 * cos(Math.toRadians(here.lat)) / (512.0 * mpp)) / ln(2.0)).coerceIn(14.0, 18.6)
        val cam = CameraOptions.Builder().center(pt(mid)).zoom(zoom).pitch(0.0).bearing(brg).build()
        try { mv.mapboxMap.easeTo(cam, MapAnimationOptions.mapAnimationOptions { duration(if (first) 1800L else 950L) }) } catch (e: Throwable) { }
        first = false
    }
}

private fun bearing(a: LL, b: LL): Double {
    val la1 = Math.toRadians(a.lat); val la2 = Math.toRadians(b.lat); val dl = Math.toRadians(b.lon - a.lon)
    val y = sin(dl) * cos(la2); val x = cos(la1) * sin(la2) - sin(la1) * cos(la2) * cos(dl)
    return (Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0
}
