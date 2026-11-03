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
    override fun onNotificationPosted(sbn: StatusBarNotification?) { sbn?.let { take(it) } }
    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn != null && sbn.packageName == NavNotice.pkg) NavNotice.clear()
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
        // only a turn instruction (it has a distance in it), not "Waze is running" and the like
        if (!Regex("\\d").containsMatchIn(title + text)) { if (NavNotice.pkg == sbn.packageName) NavNotice.clear(); return }
        val icon: Bitmap? = try {
            n.getLargeIcon()?.loadDrawable(this)?.let { d ->
                val w = d.intrinsicWidth.coerceIn(1, 256); val h = d.intrinsicHeight.coerceIn(1, 256)
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b -> val c = android.graphics.Canvas(b); d.setBounds(0, 0, w, h); d.draw(c) }
            }
        } catch (e: Exception) { null }
        NavNotice.pkg = sbn.packageName
        NavNotice.app = app
        NavNotice.title = title; NavNotice.text = text; NavNotice.sub = sub; NavNotice.icon = icon
        NavNotice.at = System.currentTimeMillis()
    }
}

/** The next turn, as Waze or Google Maps shows it in its navigation notice (empty when neither is navigating). */
object NavNotice {
    val apps = mapOf("com.waze" to "Waze", "com.google.android.apps.maps" to "Google Maps")
    var pkg = ""
    var app by mutableStateOf("")
    var title by mutableStateOf("")
    var text by mutableStateOf("")
    var sub by mutableStateOf("")
    var icon by mutableStateOf<Bitmap?>(null)
    var at by mutableLongStateOf(0L)
    /** The last navigation notice seen, as it came (shown on the phone, to check what Waze / Maps send). */
    var lastRaw by mutableStateOf("")
    fun active(now: Long = System.currentTimeMillis()) = title.isNotBlank() && now - at < 10 * 60_000L
    fun clear() { pkg = ""; app = ""; title = ""; text = ""; sub = ""; icon = null; at = 0L }
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
