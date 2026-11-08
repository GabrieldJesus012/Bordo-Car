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
import com.mapbox.maps.extension.style.layers.properties.generated.CirclePitchAlignment
import com.mapbox.maps.extension.style.layers.properties.generated.CirclePitchScale
import com.mapbox.maps.extension.style.layers.properties.generated.IconPitchAlignment
import com.mapbox.maps.extension.style.layers.properties.generated.IconRotationAlignment
import com.mapbox.maps.extension.style.layers.properties.generated.TextPitchAlignment
import com.mapbox.maps.toMapboxImage
import com.mapbox.maps.extension.style.sources.addSource
import com.mapbox.maps.extension.style.sources.generated.GeoJsonSource
import com.mapbox.maps.extension.style.sources.getSourceAs
import com.mapbox.maps.plugin.animation.MapAnimationOptions
import com.mapbox.maps.plugin.animation.easeTo
import com.mapbox.maps.plugin.attribution.attribution
import com.mapbox.maps.plugin.compass.compass
import com.mapbox.maps.plugin.logo.logo
import com.mapbox.maps.plugin.gestures.gestures
import com.mapbox.maps.plugin.scalebar.scalebar
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin

/** What the map of the next turn shows: the turn, the route line around it, the streets to count, and which one it is. */
class JunctionInfo(val turnAt: LL, val line: List<LL>, val numbered: List<LL>, val nth: Int?, val street: String,
                   /** You, put on the route (the GPS wobbles a few metres), and the way the route goes there. */
                   val me: LL, val roadBrg: Double?,
                   /** The turn's way, how far it is along the route, and whether it's kept after the notice moved on to the next one. */
                   val kind: String? = null, val aheadM: Double = 0.0, val held: Boolean = false)

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
    fun start(ctx: android.content.Context) {
        prepare(ctx)
        startedAt = System.currentTimeMillis()
    }
    /** Asks Mapbox for a route near you to drive along in a preview (this one, or the sporty one's). */
    @android.annotation.SuppressLint("MissingPermission")
    fun prepare(ctx: android.content.Context) {
        val here = Drive.pos ?: try {
            val m = ctx.getSystemService(android.location.LocationManager::class.java)
            (m.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER) ?: m.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER))?.let { LL(it.latitude, it.longitude) }
        } catch (e: Exception) { null } ?: LL(-5.0892, -42.8016)
        origin = here
        if (MapRoute.enabled(ctx)) MapRoute.request(ctx.applicationContext, here, ahead(here, Drive.heading ?: 35.0, 900.0), keep = false)
    }
    /** Where the preview's car is now, which way it goes, how far the turn is, which turn and its street. */
    class Sim(val here: LL, val heading: Double, val metres: Double, val kind: String, val street: String, val turn: MapRoute.Turn? = null)
    fun sim(now: Long): Sim = simAt((now - startedAt) / 1000.0)
    /** The same, [t] seconds into a preview: starting [back] metres before the turn at [speed] m/s, going through it. */
    fun simAt(t: Double, back: Double = 380.0, speed: Double = 30.0): Sim {
        val turn = MapRoute.turns.firstOrNull { it.alongM > 200.0 && it.kind in setOf("direita", "esquerda", "leve-direita", "leve-esquerda") } ?: MapRoute.turns.firstOrNull()
        if (turn != null) {
            val m = (turn.alongM - back + t * speed).coerceAtMost(turn.alongM + 100.0).coerceAtLeast(0.0)
            val p = MapRoute.pointAt(m)
            if (p != null) return Sim(p.first, p.second, (turn.alongM - m).coerceAtLeast(0.0), turn.kind, turn.street, turn)
        }
        val o = origin ?: LL(-5.0892, -42.8016)
        return Sim(o, 0.0, (260.0 - t * 14.0).coerceAtLeast(30.0), "direita", "Rua de exemplo")
    }
}

/**
 * Works out the turn on Mapbox's route (the real streets, with the ones to count). Null while there's no route that fits the
 * notice: then the map isn't shown (a turn guessed straight ahead floated off the streets).
 */
fun junctionInfo(here: LL, metres: Double, kind: String?, heading: Double?, forced: MapRoute.Turn? = null, street: String? = null,
                 held: Boolean = false): JunctionInfo? {
    val t = forced ?: MapRoute.next(here, metres, kind, street) ?: return null
    val (pos, off) = MapRoute.position(here)
    val line = MapRoute.slice(pos - 30.0, t.alongM + 120.0)
    if (line.size < 2) return null
    val crossings = t.sideStreets.filter { MapRoute.position(it).first in (pos + 15.0)..(t.alongM - 8.0) }
    val numbered = crossings + t.at
    val onRoad = MapRoute.pointAt(pos)
    val me = if (onRoad != null && off < 35.0) onRoad.first else here
    return JunctionInfo(t.at, line, if (numbered.size >= 2) numbered else emptyList(), if (numbered.size >= 2) numbered.size else null, t.street,
        me, onRoad?.second ?: heading, t.kind, (t.alongM - pos).coerceAtLeast(0.0), held)
}

/** The last turn shown on the map, kept until you've made it (Waze moves on to the next instruction some metres before). */
private object JunctionHold { @Volatile var at: LL? = null; @Volatile var lastAt = 0L }

/**
 * The turn for the map: the one of the notice once it's 500 m away or less; and when the notice already speaks of the next one, the
 * turn you were about to make stays until you've gone past it (or 20 s).
 */
fun junctionFor(here: LL, metres: Double?, kind: String?, heading: Double?, forced: MapRoute.Turn?, street: String?): JunctionInfo? {
    val now = System.currentTimeMillis()
    val ji = if (metres != null && metres <= 500.0) junctionInfo(here, metres, kind, heading, forced, street) else null
    if (ji != null) { JunctionHold.at = ji.turnAt; JunctionHold.lastAt = now; return ji }
    val h = JunctionHold.at ?: return null
    if (now - JunctionHold.lastAt > 20_000L) { JunctionHold.at = null; return null }
    val t = MapRoute.turns.minByOrNull { dist(it.at, h) }?.takeIf { dist(it.at, h) < 30.0 } ?: return null
    val (pos, off) = MapRoute.position(here)
    if (off > 50.0 || pos > t.alongM + 20.0) { JunctionHold.at = null; return null }
    return junctionInfo(here, (t.alongM - pos).coerceAtLeast(0.0), t.kind, heading, t, held = true)
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
 * blue, the bit of the turn in white, and the streets on that side numbered, the one to take in yellow. Always seen from above,
 * following you to the turn.
 */
@Composable
fun JunctionMap(now: Long, info: JunctionInfo, preset: String, here0: LL, heading: Double?, modifier: Modifier, textureView: Boolean = false,
                carAt: Double = 0.88, turnAt: Double = 0.2, carX: Double = 0.5, routeColor: String = "#3D8BFF", caseColor: String = "#0B2A66",
                lineScale: Double = 1.0, minSpanM: Double = 20.0, maxZoom: Double = 18.6, roundBrand: Boolean = false) {
    val ls = lineScale
    val ctx = LocalContext.current
    val token = MapRoute.token(ctx)
    var view by remember { mutableStateOf<MapView?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var first by remember { mutableStateOf(true) }
    var branded by remember { mutableStateOf(false) }
    AndroidView(factory = { c ->
        try { MapboxOptions.accessToken = token } catch (e: Throwable) { }
        val mv = MapView(c, MapInitOptions(c, textureView = textureView, styleUri = null,
            cameraOptions = CameraOptions.Builder().center(pt(here0)).zoom(15.6).pitch(0.0).bearing(heading ?: 0.0).build()))
        try { mv.scalebar.enabled = false } catch (e: Throwable) { }
        try { mv.compass.enabled = false } catch (e: Throwable) { }
        try { mv.gestures.updateSettings { scrollEnabled = false; rotateEnabled = false; pitchEnabled = false; pinchToZoomEnabled = false; doubleTapToZoomInEnabled = false } } catch (e: Throwable) { }
        try {
            // always the night map (it sits under the sky of the hour)
            mv.mapboxMap.loadStyle(styleJson("night")) { st ->
                try {
                    st.addSource(GeoJsonSource.Builder("jr-route").featureCollection(lineFc(emptyList())).build())
                    st.addSource(GeoJsonSource.Builder("jr-turn").featureCollection(lineFc(emptyList())).build())
                    st.addSource(GeoJsonSource.Builder("jr-num").featureCollection(pointsFc(emptyList())).build())
                    st.addSource(GeoJsonSource.Builder("jr-hi").featureCollection(pointsFc(emptyList())).build())
                    st.addSource(GeoJsonSource.Builder("jr-me").featureCollection(pointsFc(emptyList())).build())
                    st.addLayer(LineLayer("jr-route-case", "jr-route").lineColor(caseColor).lineWidth(16.0 * ls).lineCap(LineCap.ROUND).lineJoin(LineJoin.ROUND).lineEmissiveStrength(1.0))
                    st.addLayer(LineLayer("jr-route-line", "jr-route").lineColor(routeColor).lineWidth(10.0 * ls).lineCap(LineCap.ROUND).lineJoin(LineJoin.ROUND).lineEmissiveStrength(1.0))
                    // the turn: a wide white band with an arrowhead at its end, lying on the road
                    st.addSource(GeoJsonSource.Builder("jr-arrow").featureCollection(pointsFc(emptyList())).build())
                    try { st.addStyleImage("jr-arrowhead", 1f, arrowHead().toMapboxImage(), false, emptyList(), emptyList(), null) } catch (e: Throwable) { }
                    st.addLayer(LineLayer("jr-turn-case", "jr-turn").lineColor("#0B1430").lineWidth(20.0 * ls).lineCap(LineCap.BUTT).lineJoin(LineJoin.ROUND).lineEmissiveStrength(1.0))
                    st.addLayer(LineLayer("jr-turn-line", "jr-turn").lineColor("#FFFFFF").lineWidth(14.0 * ls).lineCap(LineCap.BUTT).lineJoin(LineJoin.ROUND).lineEmissiveStrength(1.0))
                    st.addLayer(SymbolLayer("jr-arrow-s", "jr-arrow").iconImage("jr-arrowhead").iconSize(0.42 * ls).iconRotate(Expression.get("b"))
                        .iconRotationAlignment(IconRotationAlignment.MAP).iconPitchAlignment(IconPitchAlignment.MAP).iconAllowOverlap(true).iconIgnorePlacement(true))
                    st.addLayer(CircleLayer("jr-num-c", "jr-num").circlePitchScale(CirclePitchScale.VIEWPORT).circlePitchAlignment(CirclePitchAlignment.VIEWPORT).circleRadius(maxOf(8.0, 12.0 * ls)).circleColor("#151B27").circleStrokeColor("#FFFFFF").circleStrokeWidth(2.0).circleEmissiveStrength(1.0))
                    st.addLayer(SymbolLayer("jr-num-t", "jr-num").textField(Expression.get("n")).textSize(maxOf(10.0, 14.0 * ls)).textPitchAlignment(TextPitchAlignment.VIEWPORT).textColor("#FFFFFF")
                        .textFont(listOf("DIN Pro Bold", "Arial Unicode MS Bold")).textAllowOverlap(true).textIgnorePlacement(true))
                    st.addLayer(CircleLayer("jr-hi-c", "jr-hi").circlePitchScale(CirclePitchScale.VIEWPORT).circlePitchAlignment(CirclePitchAlignment.VIEWPORT).circleRadius(maxOf(9.0, 15.0 * ls)).circleColor("#FFC24E").circleStrokeColor("#FFFFFF").circleStrokeWidth(2.5).circleEmissiveStrength(1.0))
                    st.addLayer(SymbolLayer("jr-hi-t", "jr-hi").textField(Expression.get("n")).textSize(maxOf(11.0, 16.0 * ls)).textPitchAlignment(TextPitchAlignment.VIEWPORT).textColor("#111111")
                        .textFont(listOf("DIN Pro Bold", "Arial Unicode MS Bold")).textAllowOverlap(true).textIgnorePlacement(true))
                    st.addLayer(CircleLayer("jr-me-glow", "jr-me").circleRadius(20.0 * ls).circleColor(routeColor).circleOpacity(0.3).circleEmissiveStrength(1.0))
                    st.addLayer(CircleLayer("jr-me-dot", "jr-me").circleRadius(maxOf(6.0, 10.0 * ls)).circleColor("#FFFFFF").circleStrokeColor(routeColor).circleStrokeWidth(maxOf(2.5, 4.0 * ls)).circleEmissiveStrength(1.0))
                } catch (e: Throwable) { android.util.Log.w("BordoCar", "junction layers", e) }
                style = st
            }
        } catch (e: Throwable) { android.util.Log.w("BordoCar", "junction style", e) }
        view = mv
        mv
    }, modifier = modifier)
    DisposableEffect(Unit) { onDispose { try { view?.onStop(); view?.onDestroy() } catch (e: Throwable) { } } }

    // follows you each second: the data on the map, and the camera between you and the turn (once a second, one smooth move each
    // time, so the map doesn't keep starting over and wobbling)
    val latest by androidx.compose.runtime.rememberUpdatedState(info)
    LaunchedEffect(style, now / 1000L) {
        val st = style ?: return@LaunchedEffect
        val mv = view ?: return@LaunchedEffect
        val info = latest
        val here = info.me
        val hdg = info.roadBrg ?: heading
        try {
            st.getSourceAs<GeoJsonSource>("jr-route")?.featureCollection(lineFc(info.line))
            // the white stretch of the turn itself: from 25 m before to 40 m after it
            val ti = info.line.indices.minByOrNull { dist(info.line[it], info.turnAt) } ?: 0
            var a = ti; while (a > 0 && dist(info.line[a], info.turnAt) < 25.0) a--
            var b = ti; while (b < info.line.size - 1 && dist(info.line[b], info.turnAt) < 35.0) b++
            val band = info.line.subList(a, b + 1)
            st.getSourceAs<GeoJsonSource>("jr-turn")?.featureCollection(lineFc(band))
            // the arrowhead at the end of the band, pointing the way out of the turn
            if (band.size >= 2) {
                val e = band.last(); val f = band[band.size - 2]
                val head = Feature.fromGeometry(pt(e)); head.addNumberProperty("b", bearing(f, e))
                st.getSourceAs<GeoJsonSource>("jr-arrow")?.featureCollection(FeatureCollection.fromFeatures(listOf(head)))
            }
            val nums = info.numbered
            st.getSourceAs<GeoJsonSource>("jr-num")?.featureCollection(pointsFc(nums.dropLast(1)))
            st.getSourceAs<GeoJsonSource>("jr-hi")?.featureCollection(if (nums.isNotEmpty()) pointsFc(listOf(nums.last()), nums.size) else pointsFc(emptyList()))
            st.getSourceAs<GeoJsonSource>("jr-me")?.featureCollection(pointsFc(listOf(here)))
        } catch (e: Throwable) { }
        // far from the turn: tilted, looking down the road at it (like the drawing); close to it (under ~80 m) the camera rises
        // and looks straight down on the junction
        // always seen from above (no tilt, no distortion): the middle between you and the turn, the way to the turn up, zoomed so
        // both fit with some street around them
        val d = dist(here, info.turnAt)
        // you at [carAt] of the map's height, the turn at [turnAt] (from the top), so neither hides under the edges or the panels
        val span = (carAt - turnAt).coerceAtLeast(0.2)
        // at the turn or past it (it's behind you): the map just follows the way you're going
        val toTurn = bearing(here, info.turnAt)
        val behind = hdg != null && Math.abs(((hdg - toTurn + 540.0) % 360.0) - 180.0) > 100.0
        val near = d < 25.0 || behind
        val brg = if (!near) toTurn else (hdg ?: toTurn)
        val hDp = (mv.height / mv.resources.displayMetrics.density).coerceAtLeast(if (roundBrand) 80f else 150f)
        val mpp = (if (near) maxOf(60.0, minSpanM) else d.coerceAtLeast(minSpanM)) / (hDp * span)
        val look0 = MapRoute.ahead(here, brg, (carAt - 0.5) * hDp * mpp)
        // (you and the turn to one side of the screen: the middle of the map moves the other way)
        val wDp = mv.width / mv.resources.displayMetrics.density
        val look = if (carX == 0.5 || wDp <= 0f) look0 else MapRoute.ahead(look0, brg - 90.0, (carX - 0.5) * wDp * mpp)
        val zoom = (ln(40_075_016.686 * cos(Math.toRadians(here.lat)) / (512.0 * mpp)) / ln(2.0)).coerceIn(14.5, maxZoom)
        // (in the round map) the Mapbox logo and its ⓘ at the bottom, in the middle, where the circle is wide enough
        if (roundBrand && !branded && mv.height > 0) {
            branded = true
            val px = mv.resources.displayMetrics.density
            try { mv.logo.updateSettings { position = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL; marginBottom = mv.height * 0.07f; marginRight = 12f * px; marginLeft = 0f } } catch (e: Throwable) { }
            try { mv.attribution.updateSettings { position = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL; marginBottom = mv.height * 0.07f; marginLeft = 46f * px; marginRight = 0f } } catch (e: Throwable) { }
        }
        val cam = CameraOptions.Builder().center(pt(look)).zoom(zoom).pitch(0.0).bearing(brg).build()
        try { mv.mapboxMap.easeTo(cam, MapAnimationOptions.mapAnimationOptions { duration(if (first) 1800L else 1000L); interpolator(android.view.animation.LinearInterpolator()) }) } catch (e: Throwable) { }
        first = false
    }
}

private fun bearing(a: LL, b: LL): Double {
    val la1 = Math.toRadians(a.lat); val la2 = Math.toRadians(b.lat); val dl = Math.toRadians(b.lon - a.lon)
    val y = sin(dl) * cos(la2); val x = cos(la1) * sin(la2) - sin(la1) * cos(la2) * cos(dl)
    return (Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0
}

/** The arrowhead lying on the road at the end of the turn: white with a dark edge, pointing up (turned on the map to the way out). */
private fun arrowHead(): android.graphics.Bitmap {
    val s = 96
    val b = android.graphics.Bitmap.createBitmap(s, s, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(b)
    val path = android.graphics.Path().apply { moveTo(s / 2f, 6f); lineTo(s - 8f, s * 0.78f); lineTo(s / 2f, s * 0.6f); lineTo(8f, s * 0.78f); close() }
    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    p.style = android.graphics.Paint.Style.STROKE; p.strokeWidth = 7f; p.strokeJoin = android.graphics.Paint.Join.ROUND; p.color = 0xFF0B1430.toInt()
    c.drawPath(path, p)
    p.style = android.graphics.Paint.Style.FILL; p.color = 0xFFFFFFFF.toInt()
    c.drawPath(path, p)
    return b
}
