package com.bordocar

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper

/**
 * The GPS while the phone is on Android Auto: speed, compass and km keep counting even with Waze or Google Maps on the car screen
 * (Android lets an app use the GPS in the background only from a service like this, shown as a small notification).
 * Started when the car connects, stopped (and the trip saved) when it disconnects.
 */
class DriveService : Service(), LocationListener {
    private var lm: LocationManager? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        running = true
        startForegroundNotice()
        startGps()
        watch.removeCallbacks(check); watch.postDelayed(check, 10_000L)
        return START_STICKY
    }

    // if the GPS goes quiet (the phone cut it off), the speed isn't left showing an old number, and the GPS is asked again
    private val watch = android.os.Handler(Looper.getMainLooper())
    private val check = object : Runnable {
        override fun run() {
            val quiet = System.currentTimeMillis() - Drive.lastFixAt
            if (quiet > 6_000L && Drive.speed != 0.0) Drive.speed = 0.0
            if (quiet > 20_000L) { try { lm?.removeUpdates(this@DriveService) } catch (e: Exception) { }; lm = null; startGps() }
            watch.postDelayed(this, 10_000L)
        }
    }

    private fun startForegroundNotice() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CH, "Contagem de km", NotificationManager.IMPORTANCE_MIN).apply { setShowBadge(false) })
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CH).setSmallIcon(R.drawable.ic_stat).setContentTitle("Bordo Car")
            .setContentText("Contando seus km enquanto o celular está no carro").setOngoing(true).setContentIntent(open).build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) else startForeground(1, n)
        } catch (e: Exception) { stopSelf() }
    }

    @SuppressLint("MissingPermission")
    private fun startGps() {
        if (lm != null) return
        try {
            val m = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
            gpsNote = "pedido ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())}"
            lm = m
        } catch (e: Exception) { gpsNote = "recusado: ${e.javaClass.simpleName}" }
    }

    override fun onLocationChanged(l: Location) {
        try {
            Drive.onLocation(this, l)
            Drive.pos?.let { Limits.update(it, Drive.heading) }
        } catch (e: Exception) { }
    }
    override fun onProviderDisabled(provider: String) {}
    override fun onProviderEnabled(provider: String) {}
    @Deprecated("Needed on Android 10 and older")
    @Suppress("DEPRECATION")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}

    override fun onDestroy() {
        watch.removeCallbacks(check)
        try { lm?.removeUpdates(this) } catch (e: Exception) { }
        lm = null
        running = false
        // the car was switched off: where it was left (not near Casa / Trabalho), and the trip ends
        try { Parking.note(this, Drive.pos) } catch (e: Exception) { }
        Drive.endTrip()
        super.onDestroy()
    }

    companion object {
        private const val CH = "drive"
        /** True while the car is connected (the phone's own screen then leaves the GPS to this service). */
        @Volatile var running = false
        /** How the last GPS request went (shown on the phone). */
        @Volatile var gpsNote = ""
        fun start(ctx: Context) {
            try {
                val i = Intent(ctx, DriveService::class.java)
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            } catch (e: Exception) { }
        }
        fun stop(ctx: Context) { try { ctx.stopService(Intent(ctx, DriveService::class.java)) } catch (e: Exception) { } }
    }
}
