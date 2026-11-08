package com.bordocar

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Allowed under "notification access", this lets the dashboard show the music playing (from the music app's player) and the next
 * turn from Waze or Google Maps (from their navigation notice). Nothing is stored or sent anywhere; other apps' notifications are
 * ignored.
 */
class Listener : NotificationListenerService() {
    override fun onListenerConnected() {
        try { activeNotifications?.forEach { take(it) } } catch (e: Exception) { }
    }
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.packageName in NavNotice.apps) try { NavLog.add(this, sbn) } catch (e: Exception) { }
        take(sbn)
    }
    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn != null && sbn.packageName in NavNotice.apps) try { NavLog.removed(this, sbn) } catch (e: Exception) { }
        // Maps sometimes takes the notice down and puts it back a moment later: only end the route if it doesn't come back soon
        if (sbn != null && sbn.key == NavNotice.key) NavNotice.removedAt = System.currentTimeMillis()
    }

    private fun take(sbn: StatusBarNotification) {
        val app = NavNotice.apps[sbn.packageName] ?: return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_ONGOING_EVENT == 0) return
        val x = n.extras ?: return
        val title = x.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
        val text = (x.getCharSequence(Notification.EXTRA_TEXT) ?: x.getCharSequence(Notification.EXTRA_BIG_TEXT))?.toString().orEmpty().trim()
        val sub = x.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty().trim()
        NavNotice.lastRaw = "$app · $title · $text · $sub"
        // a route starts with a turn instruction (it has a distance in it), not "Waze is running" and the like; after that, the same
        // notice keeps the card up even when it has no distance (a long straight: "Siga pela BR-343", "Continue em frente")
        if (NavNotice.removedAt > 0L && System.currentTimeMillis() - NavNotice.removedAt > 8_000L) NavNotice.clear()   // that route ended
        val sameRoute = sbn.key == NavNotice.key && NavNotice.title.isNotBlank()
        if (!Regex("\\d").containsMatchIn(title + text) && !sameRoute) return
        if (title.isBlank() && text.isBlank()) { NavNotice.at = System.currentTimeMillis(); NavNotice.removedAt = 0L; return }
        val icon: Bitmap? = try {
            n.getLargeIcon()?.loadDrawable(this)?.let { d ->
                val w = d.intrinsicWidth.coerceIn(1, 256); val h = d.intrinsicHeight.coerceIn(1, 256)
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b -> val c = android.graphics.Canvas(b); d.setBounds(0, 0, w, h); d.draw(c) }
            }
        } catch (e: Exception) { null }
        NavNotice.pkg = sbn.packageName
        NavNotice.key = sbn.key
        NavNotice.removedAt = 0L
        NavNotice.app = app
        if (!sameRoute) NavNotice.startedAt = System.currentTimeMillis()
        // Waze puts the song playing (from Spotify) in that small line: that isn't about the route
        val music = listOf(NowPlaying.artist, NowPlaying.title).filter { it.length >= 3 }
        val subRoute = if (music.any { m -> sub.contains(m, ignoreCase = true) || m.contains(sub.trim(), ignoreCase = true) && sub.trim().length >= 3 }) "" else sub
        NavNotice.title = title.ifBlank { text }; NavNotice.text = if (title.isBlank()) "" else text; NavNotice.sub = subRoute
        if (icon != null || !sameRoute) { NavNotice.icon = icon; NavNotice.iconKind = icon?.let { arrowKind(it) } }
        NavNotice.at = System.currentTimeMillis()
    }
}

/** The next turn, as Waze or Google Maps shows it in its navigation notice (empty when neither is navigating). */
object NavNotice {
    val apps = mapOf("com.waze" to "Waze", "com.google.android.apps.maps" to "Google Maps")
    var pkg = ""
    /** Which notice the route is in, and when it was taken down (0 = it's up). */
    var key = ""
    /** When this route started (first notice of it). */
    @Volatile var startedAt = 0L
    /** Where the route goes, when it was started from Bordo Car (Casa, Trabalho, a favourite, a search), and how far it was then. */
    @Volatile var target: LL? = null
    @Volatile var targetFromM = 0.0
    @Volatile var targetAt = 0L
    @Volatile var removedAt = 0L
    var app by mutableStateOf("")
    var title by mutableStateOf("")
    var text by mutableStateOf("")
    var sub by mutableStateOf("")
    var icon by mutableStateOf<Bitmap?>(null)
    /** The way of the turn read from the notice's arrow picture (Waze only writes the distance and the street, not the way). */
    @Volatile var iconKind: String? = null
    var at by mutableLongStateOf(0L)
    /** The last navigation notice seen, as it came (shown on the phone, to check what Waze / Maps send). */
    var lastRaw by mutableStateOf("")
    fun active(now: Long = System.currentTimeMillis()): Boolean {
        if (removedAt > 0L && now - removedAt > 8_000L) return false
        return title.isNotBlank() && now - at < 30 * 60_000L
    }
    /** The distance to the turn ("350 m", "1,2 km"), to show standing still, and the rest of the instruction (which may scroll). */
    fun split(title: String, text: String): Pair<String?, String> {
        val re = Regex("(\\d+(?:[.,]\\d+)?)\\s*(km|m)\\b", RegexOption.IGNORE_CASE)
        for ((src, other) in listOf(title to text, text to title)) {
            val m = re.find(src) ?: continue
            val dist = m.value.replace(Regex("\\s+"), " ")
            val rest = src.removeRange(m.range).replace(Regex("^[\\s·•,:\\-–]+|[\\s·•,:\\-–]+$"), "").replace(Regex("\\b(em|in)\\s*$"), "").trim()
            return dist to listOf(rest, other).filter { it.isNotBlank() }.joinToString(" · ")
        }
        return null to listOf(title, text).filter { it.isNotBlank() }.joinToString(" · ")
    }
    fun clear() { pkg = ""; key = ""; removedAt = 0L; app = ""; title = ""; text = ""; sub = ""; icon = null; iconKind = null; at = 0L }
}

/** The arrow of a turn picture as a [n]×[n] grid (true = the arrow), or null when it doesn't look like one. */
fun arrowMask(b: Bitmap, n: Int = 24): Array<BooleanArray>? = try {
    val s = Bitmap.createScaledBitmap(b, n, n, true)
    val px = IntArray(n * n); s.getPixels(px, 0, n, 0, 0, n, n)
    // drawn over transparency (the usual): the arrow is what's opaque; otherwise what differs from the corner's colour
    val clear = px.count { android.graphics.Color.alpha(it) < 40 } > n * n / 5
    val c0 = px[0]
    fun diff(c: Int) = Math.abs(android.graphics.Color.red(c) - android.graphics.Color.red(c0)) + Math.abs(android.graphics.Color.green(c) - android.graphics.Color.green(c0)) +
        Math.abs(android.graphics.Color.blue(c) - android.graphics.Color.blue(c0))
    val m = Array(n) { y -> BooleanArray(n) { x -> val c = px[y * n + x]; if (clear) android.graphics.Color.alpha(c) > 120 else diff(c) > 150 } }
    val on = m.sumOf { r -> r.count { it } }
    if (on < n * n / 40 || on > n * n * 7 / 10) null else m
} catch (e: Exception) { null }

/**
 * Which way the arrow turns: for "↱" the top of the arrow lies to the right of its foot, for "↰" to the left; a straight one is even.
 * Only right / left (and slight) are told: a roundabout or a U-turn picture gives null.
 */
fun arrowKind(b: Bitmap): String? {
    val m = arrowMask(b) ?: return null
    val n = m.size
    val ys = (0 until n).filter { y -> m[y].any { it } }; if (ys.size < 4) return null
    val xs = (0 until n).filter { x -> (0 until n).any { y -> m[y][x] } }
    val bw = (xs.last() - xs.first() + 1).toDouble(); val bh = ys.last() - ys.first() + 1
    fun cx(rows: IntRange): Double? {
        var sum = 0.0; var k = 0
        for (y in rows) for (x in 0 until n) if (m[y][x]) { sum += x; k++ }
        return if (k == 0) null else sum / k
    }
    val top = cx(ys.first()..(ys.first() + bh / 3)) ?: return null
    val foot = cx((ys.last() - bh / 3)..ys.last()) ?: return null
    val d = (top - foot) / bw
    // a ring (Waze's roundabout): even, about as wide as tall, half filled (the destination pin is almost all filled, an arrow little)
    val fill = m.sumOf { r -> r.count { it } } / (bw * bh)
    if (Math.abs(d) < 0.12 && bw / bh in 0.8..1.25 && fill in 0.3..0.62) return "rotatoria"
    return when {
        d > 0.28 -> "direita"
        d > 0.12 -> "leve-direita"
        d < -0.28 -> "esquerda"
        d < -0.12 -> "leve-esquerda"
        else -> null
    }
}

/**
 * The test log: everything Waze and Google Maps put up as notices (not only the route's one), to find out whether they tell about
 * radars, holes and the like. The same notice counting down the metres is written once; a new kind of message is written again.
 * Kept on the phone only (a text file), until it's cleared.
 */
object NavLog {
    var lines by androidx.compose.runtime.mutableIntStateOf(0)
    private val lastShape = HashMap<String, String>()
    @Volatile private var lastArt = ""
    private fun file(ctx: Context) = java.io.File(ctx.filesDir, "avisos-nav.txt")
    fun on(ctx: Context) = Prefs(ctx).b("navlog", true)
    private val fmt = java.text.SimpleDateFormat("dd/MM HH:mm:ss", java.util.Locale("pt", "BR"))

    fun add(ctx: Context, sbn: StatusBarNotification) {
        if (!on(ctx)) return
        val n = sbn.notification ?: return
        val x = n.extras ?: android.os.Bundle()
        fun cs(k: String) = x.getCharSequence(k)?.toString()?.replace('\n', ' ')?.trim().orEmpty()
        val lines2 = x.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString(" / ").orEmpty()
        val acts = n.actions?.mapNotNull { it.title?.toString() }?.joinToString(", ").orEmpty()
        val parts = listOf("título" to cs(Notification.EXTRA_TITLE), "texto" to cs(Notification.EXTRA_TEXT), "sub" to cs(Notification.EXTRA_SUB_TEXT),
            "grande" to cs(Notification.EXTRA_BIG_TEXT), "info" to cs(Notification.EXTRA_INFO_TEXT), "resumo" to cs(Notification.EXTRA_SUMMARY_TEXT),
            "linhas" to lines2, "aviso" to n.tickerText?.toString().orEmpty(), "ações" to acts).filter { it.second.isNotBlank() }
        val head = buildString {
            append(NavNotice.apps[sbn.packageName]); append(" · ")
            if (android.os.Build.VERSION.SDK_INT >= 26) append("canal=").append(n.channelId ?: "-").append(' ')
            append("tipo=").append(n.category ?: "-")
            if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) append(" fixa")
            append(" id=").append(sbn.id)
        }
        var body = parts.joinToString(" | ") { "${it.first}: ${it.second}" }
        val pic: Bitmap? = try {
            n.getLargeIcon()?.loadDrawable(ctx)?.let { d ->
                val w = d.intrinsicWidth.coerceIn(1, 256); val h = d.intrinsicHeight.coerceIn(1, 256)
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b -> val c = android.graphics.Canvas(b); d.setBounds(0, 0, w, h); d.draw(c) }
            }
        } catch (e: Exception) { null }
        var art = ""
        if (pic != null) {
            body += " | seta: " + (arrowKind(pic) ?: "?")
            val mk = arrowMask(pic, 16)
            if (mk != null) art = mk.joinToString("\n") { r -> "      " + r.joinToString("") { if (it) "#" else "." } }
        }
        // the same message with other numbers (the metres counting down): already written
        val shape = head + body.replace(Regex("\\d+([.,]\\d+)?"), "#")
        synchronized(lastShape) { if (lastShape[sbn.key] == shape) return; lastShape[sbn.key] = shape }
        val newArt = art.isNotBlank() && art != lastArt
        if (newArt) lastArt = art
        write(ctx, "${fmt.format(java.util.Date())}  $head\n    $body" + (if (newArt) "\n$art" else ""))
    }
    fun removed(ctx: Context, sbn: StatusBarNotification) {
        if (!on(ctx)) return
        synchronized(lastShape) { lastShape.remove(sbn.key) }
        write(ctx, "${fmt.format(java.util.Date())}  ${NavNotice.apps[sbn.packageName]} · saiu id=${sbn.id}")
    }
    @Synchronized private fun write(ctx: Context, entry: String) {
        val f = file(ctx)
        f.appendText(entry + "\n")
        if (f.length() > 400_000L) { val t = f.readText(); f.writeText(t.substring(t.length / 2).substringAfter('\n')) }
        lines = count(ctx)
    }
    fun read(ctx: Context): String = try { file(ctx).readText() } catch (e: Exception) { "" }
    fun count(ctx: Context): Int = read(ctx).lines().count { it.isNotBlank() && !it.startsWith("    ") }
    fun clear(ctx: Context) { try { file(ctx).delete() } catch (e: Exception) { }; synchronized(lastShape) { lastShape.clear() }; lines = 0 }
}

/** What's playing in the music app (Spotify, YouTube Music...), read once a second while the dashboard shows. */
object NowPlaying {
    var allowed by mutableStateOf(false)
    var title by mutableStateOf("")
    var artist by mutableStateOf("")
    var app by mutableStateOf("")
    var art by mutableStateOf<Bitmap?>(null)
    var playing by mutableStateOf(false)
    var position by mutableLongStateOf(0L)
    var duration by mutableLongStateOf(0L)

    private val names = mapOf(
        "com.spotify.music" to "Spotify", "com.google.android.apps.youtube.music" to "YouTube Music", "com.deezer.android.app" to "Deezer",
        "com.amazon.mp3" to "Amazon Music", "com.apple.android.music" to "Apple Music", "com.google.android.youtube" to "YouTube",
        "com.soundcloud.android" to "SoundCloud", "br.com.globo.globoplay" to "Globoplay",
    )

    fun isAllowed(ctx: Context): Boolean = try {
        androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
    } catch (e: Exception) { false }

    private fun controller(ctx: Context): MediaController? = try {
        val m = ctx.getSystemService(MediaSessionManager::class.java)
        val list = m.getActiveSessions(ComponentName(ctx, Listener::class.java))
        list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull()
    } catch (e: Exception) { null }

    /** The buttons on the car screen: "prev", "play" (play / pause) and "next". */
    fun control(ctx: Context, what: String) {
        try {
            val c = controller(ctx) ?: return
            val t = c.transportControls
            when (what) {
                "prev" -> t.skipToPrevious()
                "next" -> t.skipToNext()
                else -> if (c.playbackState?.state == PlaybackState.STATE_PLAYING) t.pause() else t.play()
            }
            refresh(ctx)
        } catch (e: Exception) { }
    }

    fun refresh(ctx: Context) {
        allowed = isAllowed(ctx)
        if (!allowed) return
        val c = controller(ctx)
        if (c == null) { title = ""; artist = ""; art = null; playing = false; app = ""; position = 0L; duration = 0L; return }
        val md = c.metadata
        title = md?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        artist = (md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)).orEmpty()
        val bmp = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        if (bmp !== art) art = bmp
        duration = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val st = c.playbackState
        playing = st?.state == PlaybackState.STATE_PLAYING
        position = if (st == null) 0L else if (playing && st.lastPositionUpdateTime > 0)
            st.position + ((android.os.SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
        else st.position
        app = names[c.packageName] ?: try {
            ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(c.packageName, 0)).toString()
        } catch (e: Exception) { "Música" }
    }
}
