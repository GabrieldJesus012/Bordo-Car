package com.bordocar

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/**
 * Opened by the full-screen notice when a route is asked from the car with the phone locked: it shows over the lock screen for an
 * instant (nothing to see) and opens Waze / Google Maps with the route, which then takes over the car screen.
 */
class OpenRoute : Activity() {
    companion object { const val NOTE_ID = 4242 }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else @Suppress("DEPRECATION") window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        val link = intent.getStringExtra("link"); val pkg = intent.getStringExtra("pkg")
        if (link != null) try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).apply { if (pkg != null) setPackage(pkg) }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            com.bordocar.car.navNote = "abriu pela tela de bloqueio"
        } catch (e: Exception) { com.bordocar.car.navNote = "a tela de bloqueio não abriu: ${e.javaClass.simpleName}" }
        try { getSystemService(android.app.NotificationManager::class.java).cancel(NOTE_ID) } catch (e: Exception) { }
        finish()
    }
}
