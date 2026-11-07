package com.bordocar

import android.content.Context
import java.io.File

/**
 * The way each trip went: a point every ~20 m, kept in a small file per trip (named by the trip's start), for the map of the routes
 * (tap KM HOJE or KM DA SEMANA on the car). Kept for four months.
 */
object Tracks {
    private var dir: File? = null
    private val pending = StringBuilder()
    private var pendingFor = 0L
    private var last: LL? = null

    fun init(ctx: Context) {
        if (dir != null) return
        dir = File(ctx.filesDir, "tracks").apply { mkdirs() }
        // forget routes older than four months
        val old = System.currentTimeMillis() - 120L * 86_400_000L
        try { dir?.listFiles()?.forEach { f -> f.nameWithoutExtension.toLongOrNull()?.let { if (it < old) f.delete() } } } catch (e: Exception) { }
    }

    /** A point of the trip that started at [start]; kept when it's at least 20 m from the last one kept. */
    @Synchronized fun add(start: Long, p: LL, force: Boolean = false) {
        if (pendingFor != start) { flush(); pendingFor = start; last = null }
        val l = last
        if (!force && l != null && dist(l, p) < 20.0) return
        last = p
        pending.append(String.format(java.util.Locale.US, "%.5f,%.5f\n", p.lat, p.lon))
        if (pending.length > 2000) flush()
    }

    /** Writes the points not saved yet (every 20 s while driving, and at the end of the trip). */
    @Synchronized fun flush() {
        if (pending.isEmpty() || pendingFor == 0L) return
        try { File(dir ?: return, "$pendingFor.trk").appendText(pending.toString()) } catch (e: Exception) { }
        pending.setLength(0)
    }

    /** A trip too short to be kept: its points go too. */
    @Synchronized fun drop(start: Long) {
        if (pendingFor == start) { pending.setLength(0); last = null }
        try { File(dir ?: return, "$start.trk").delete() } catch (e: Exception) { }
    }

    /** The points of the trip that started at [start] (empty when it was before the routes were kept). */
    @Synchronized fun load(start: Long): List<LL> {
        if (pendingFor == start) flush()
        val f = File(dir ?: return emptyList(), "$start.trk")
        if (!f.exists()) return emptyList()
        return try {
            f.readLines().mapNotNull { line -> val p = line.split(","); if (p.size == 2) LL(p[0].toDouble(), p[1].toDouble()) else null }
        } catch (e: Exception) { emptyList() }
    }
}
