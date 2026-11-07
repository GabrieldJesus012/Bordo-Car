package com.bordocar.car

import android.app.Presentation
import android.content.Intent
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.net.Uri
import android.widget.FrameLayout
import androidx.car.app.AppManager
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.validation.HostValidator
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.bordocar.Drive
import com.bordocar.DriveService
import com.bordocar.Prefs
import com.bordocar.R
import com.bordocar.ui.CarTaps
import com.bordocar.ui.DashActions
import com.bordocar.ui.Dashboard

/**
 * Bordo Car on Android Auto: a dashboard, not a map. Android Auto only lets navigation apps draw their own screen, so it's listed
 * as one, but it never navigates itself: Casa, Trabalho, Postos and Buscar hand the destination to Waze or Google Maps (whichever
 * Android Auto uses), and the dashboard shows their next turn while they guide you.
 */
class BordoCarService : CarAppService() {
    // only Google's car hosts (Android Auto) may connect
    override fun createHostValidator(): HostValidator =
        HostValidator.Builder(applicationContext).addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample).build()
    override fun onCreateSession(): Session = BordoSession()
}

/** Hands a destination to the navigation app Android Auto uses (Waze or Google Maps): an address, a place name or "lat,lon". */
fun navigateTo(car: CarContext, q: String) {
    val ll = q.trim().replace(" ", "")
    val uri = if (Regex("^-?\\d+(\\.\\d+)?,-?\\d+(\\.\\d+)?$").matches(ll)) "geo:$ll?q=$ll" else "geo:0,0?q=" + Uri.encode(q.trim())
    // remember the destination (when it's a point) to show how much is left even when the app doesn't say it (Waze)
    Regex("^(-?\\d+(?:\\.\\d+)?),(-?\\d+(?:\\.\\d+)?)$").find(ll)?.let { m ->
        val t = com.bordocar.LL(m.groupValues[1].toDouble(), m.groupValues[2].toDouble())
        com.bordocar.NavNotice.target = t; com.bordocar.NavNotice.targetAt = System.currentTimeMillis()
        com.bordocar.NavNotice.targetFromM = com.bordocar.Drive.pos?.let { com.bordocar.dist(it, t) } ?: 0.0
        // the same route from Mapbox, to know where each turn is (the map of the turn in Estrada)
        com.bordocar.MapRoute.request(car.applicationContext, com.bordocar.Drive.pos, t)
    } ?: run { com.bordocar.NavNotice.target = null; com.bordocar.MapRoute.clear() }
    val pm = car.packageManager
    fun has(p: String) = try { pm.getPackageInfo(p, 0); true } catch (e: Exception) { false }
    val choice = Prefs(car).s("navapp", "waze")
    val order = (if (choice == "maps") listOf("com.google.android.apps.maps", "com.waze") else listOf("com.waze", "com.google.android.apps.maps")).filter { has(it) }
    val name = mapOf("com.waze" to "Waze", "com.google.android.apps.maps" to "Google Maps")
    // 1st: start the route in Waze / Google Maps on the phone (they then take over the car screen). Android Auto alone hands
    // "navigate to" to its default navigation app, which can be Bordo Car itself, so nothing happened.
    for (pkg in order) {
        if (openOnPhone(car.applicationContext, pkg, ll, q.trim())) {
            navNote = "abriu no ${name[pkg]} pelo celular"
            try { CarToast.makeText(car, "Abrindo no ${name[pkg]}…", CarToast.LENGTH_SHORT).show() } catch (x: Exception) { }
            try { car.startCarApp(Intent(CarContext.ACTION_NAVIGATE, Uri.parse(uri)).setPackage(pkg)) } catch (e: Exception) { }
            return
        }
    }
    for (pkg in order) {
        try {
            car.startCarApp(Intent(CarContext.ACTION_NAVIGATE, Uri.parse(uri)).setPackage(pkg))
            navNote = "abriu no ${name[pkg]} ($uri)"
            try { CarToast.makeText(car, "Abrindo no ${name[pkg]}…", CarToast.LENGTH_SHORT).show() } catch (x: Exception) { }
            return
        } catch (e: Exception) { navNote = "${name[pkg]} recusou: ${e.message ?: e.javaClass.simpleName}" }
    }
    try { car.startCarApp(Intent(CarContext.ACTION_NAVIGATE, Uri.parse(uri))); navNote = "abriu no app de navegação padrão ($uri)" }
    catch (e: Exception) {
        navNote = "não abriu: ${e.message ?: e.javaClass.simpleName}"
        try { CarToast.makeText(car, "Não consegui abrir o Waze ou o Google Maps", CarToast.LENGTH_LONG).show() } catch (x: Exception) { }
    }
}

/**
 * Opens the route in Waze / Google Maps on the phone. Android only lets an app open another one from the background while it shows
 * something over the other apps, so this needs "Sobrepor a outros apps" (asked on the phone) and puts up a 1-pixel window for a moment.
 */
private fun openOnPhone(ctx: android.content.Context, pkg: String, ll: String, q: String): Boolean {
    if (!android.provider.Settings.canDrawOverlays(ctx)) { navNote = "falta a permissão \"Sobrepor a outros apps\" no celular"; return false }
    val point = Regex("^-?\\d+(\\.\\d+)?,-?\\d+(\\.\\d+)?$").matches(ll)
    val link = if (pkg == "com.waze") (if (point) "https://waze.com/ul?ll=$ll&navigate=yes" else "https://waze.com/ul?q=${Uri.encode(q)}&navigate=yes")
               else "google.navigation:q=" + (if (point) ll else Uri.encode(q))
    val wm = ctx.getSystemService(android.view.WindowManager::class.java)
    val dot = android.view.View(ctx)
    val lp = android.view.WindowManager.LayoutParams(1, 1, android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, android.graphics.PixelFormat.TRANSLUCENT)
    val shown = try { wm.addView(dot, lp); true } catch (e: Exception) { false }
    val ok = try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (e: Exception) { navNote = "o celular não abriu: ${e.javaClass.simpleName}"; false }
    if (shown) android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ try { wm.removeView(dot) } catch (e: Exception) { } }, 2000L)
    return ok
}

/** What happened the last time a destination was handed over (shown on the phone, to check). */
@Volatile var navNote = ""

class BordoSession : Session() {
    private lateinit var surface: DashSurface

    // Android Auto gave a "navigate to" back to Bordo Car (it was its default navigation app): note it, to check on the phone
    override fun onNewIntent(intent: Intent) {
        if (intent.action == CarContext.ACTION_NAVIGATE) navNote = "$navNote · o Android Auto devolveu o destino para o Bordo"
    }

    override fun onCreateScreen(intent: Intent): Screen {
        val app = carContext.applicationContext
        Drive.init(app)
        // back in the car: it's no longer parked anywhere
        com.bordocar.Parking.clear(app)
        // the GPS keeps counting while Waze or Maps is on the car screen
        DriveService.start(app)
        val screen = DashScreen(carContext)
        surface = DashSurface(carContext, object : DashActions {
            override fun home() = place(screen, "casa", "Casa")
            override fun work() = place(screen, "trabalho", "Trabalho")
            override fun fuel() = navigateTo(carContext, "posto de combustível")
            override fun favourites() { screen.screenManager.push(FavouritesScreen(carContext)) }
            override fun search() { screen.screenManager.push(SearchScreen(carContext, null)) }
        })
        try { carContext.getCarService(AppManager::class.java).setSurfaceCallback(surface) } catch (e: Exception) { }
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                // unplugged: the trip being driven is saved and the GPS rests
                DriveService.stop(app)
                surface.release()
            }
        })
        return screen
    }

    private fun place(screen: Screen, key: String, label: String) {
        val p = com.bordocar.Places.slot(carContext, key)
        if (p == null) screen.screenManager.push(SearchScreen(carContext, key to label)) else navigateTo(carContext, p.target)
    }
}

/** The car screen itself: only Android Auto's two small buttons (search and settings); the rest is the dashboard drawn on the surface. */
class DashScreen(carContext: CarContext) : Screen(carContext) {
    private fun icon(res: Int) = CarIcon.Builder(IconCompat.createWithResource(carContext, res)).build()
    override fun onGetTemplate(): Template {
        val strip = ActionStrip.Builder()
            .addAction(Action.Builder().setIcon(icon(R.drawable.ic_car_search)).setOnClickListener { screenManager.push(SearchScreen(carContext, null)) }.build())
            // switches between the dashboard's modes (Normal, Esportivo, Música, Estrada)
            .addAction(Action.Builder().setIcon(icon(R.drawable.ic_car_layout)).setOnClickListener {
                com.bordocar.ui.Modes.load(carContext); com.bordocar.ui.Modes.next(carContext)
                try { CarToast.makeText(carContext, "Painel: " + com.bordocar.ui.Modes.name(), CarToast.LENGTH_SHORT).show() } catch (e: Exception) { }
            }.build())
            .addAction(Action.Builder().setIcon(icon(R.drawable.ic_car_settings)).setOnClickListener { screenManager.push(SettingsScreen(carContext)) }.build())
            .build()
        // (some cars only pass taps on the screen to apps whose layout has the "move" button: it lets the dashboard's shortcuts work)
        val mapStrip = ActionStrip.Builder().addAction(Action.PAN).build()
        return NavigationTemplate.Builder().setActionStrip(strip).setMapActionStrip(mapStrip).build()
    }
}

/**
 * Search with the car's keyboard or voice: what you type goes to Waze / Google Maps, which find it and start the route.
 * With [saveAs] set, the address is kept as your Casa or Trabalho (and the route starts).
 */
class SearchScreen(carContext: CarContext, private val saveAs: Pair<String, String>?) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val hint = if (saveAs != null) "Endereço de ${saveAs.second}" else "Para onde?"
        return SearchTemplate.Builder(object : SearchTemplate.SearchCallback {
            override fun onSearchTextChanged(searchText: String) {}
            override fun onSearchSubmitted(searchText: String) {
                val q = searchText.trim()
                if (q.isEmpty()) return
                screenManager.pop()
                if (saveAs != null) {
                    // kept with its exact spot when Android's address search finds it
                    val car = carContext
                    Thread {
                        val hit = com.bordocar.Places.search(car, q, Drive.pos).firstOrNull()
                        val p = com.bordocar.Place(saveAs.second, hit?.address ?: q, hit?.lat, hit?.lon)
                        com.bordocar.Places.setSlot(car, saveAs.first, p)
                        android.os.Handler(android.os.Looper.getMainLooper()).post { navigateTo(car, p.target) }
                    }.apply { isDaemon = true }.start()
                } else navigateTo(carContext, q)
            }
        }).setHeaderAction(Action.BACK).setSearchHint(hint).setShowKeyboardByDefault(true)
            .setItemList(ItemList.Builder().setNoItemsMessage(if (saveAs != null) "Digite o endereço e confirme: ele fica salvo" else "Digite um lugar ou endereço: abre no Waze ou no Google Maps").build())
            .build()
    }
}

/** The car's own settings: Casa and Trabalho (change them here or on the phone). */
class SettingsScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
        list.addItem(Row.Builder().setTitle("Casa e Trabalho").addText(listOf("casa", "trabalho").count { com.bordocar.Places.slot(carContext, it) != null }.let { if (it == 2) "Definidos" else "Toque para definir" })
            .setBrowsable(true).setOnClickListener { screenManager.push(PlacesScreen(carContext)) }.build())
        com.bordocar.ui.Modes.load(carContext)
        list.addItem(Row.Builder().setTitle("Modo do painel").addText(com.bordocar.ui.Modes.name() + " · toque para trocar")
            .setOnClickListener { com.bordocar.ui.Modes.next(carContext); invalidate() }.build())
        com.bordocar.ui.Themes.load(carContext)
        list.addItem(Row.Builder().setTitle("Tema do painel").addText(com.bordocar.ui.Themes.cur.name + " · toque para trocar")
            .setOnClickListener { com.bordocar.ui.Themes.next(carContext); invalidate() }.build())
        com.bordocar.ui.Sport.load(carContext)
        list.addItem(Row.Builder().setTitle("Esportivo acima de ${com.bordocar.ui.Sport.ON_KMH} km/h")
            .addText(if (com.bordocar.ui.Sport.enabled) "Ligado" else "Desligado")
            .setToggle(androidx.car.app.model.Toggle.Builder { on ->
                com.bordocar.ui.Sport.set(carContext, on); invalidate()
            }.setChecked(com.bordocar.ui.Sport.enabled).build()).build())
        list.addItem(Row.Builder().setTitle("Ver prévias").addText("Painel esportivo, mapa da curva").setBrowsable(true)
            .setOnClickListener { screenManager.push(PreviewsScreen(carContext)) }.build())
        val nav = Prefs(carContext).s("navapp", "waze")
        list.addItem(Row.Builder().setTitle("Navegar com").addText((if (nav == "maps") "Google Maps" else "Waze") + " · toque para trocar")
            .setOnClickListener { Prefs(carContext).put("navapp", if (nav == "maps") "waze" else "maps"); invalidate() }.build())
        return ListTemplate.Builder().setTitle("Bordo Car").setHeaderAction(Action.BACK).setSingleList(list.build()).build()
    }
}

/** The previews: the sporty dashboard, the map of the turn (Estrada). */
class PreviewsScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
        list.addItem(Row.Builder().setTitle("Painel esportivo").addText("Mostra por 14 segundos")
            .setOnClickListener { com.bordocar.ui.Sport.preview(carContext); screenManager.popToRoot() }.build())
        list.addItem(Row.Builder().setTitle("Mapa da curva (modo Estrada)").addText("Anda por uma rota perto de você até a primeira curva, por 16 segundos")
            .setOnClickListener { com.bordocar.ui.JunctionPreview.start(carContext); screenManager.popToRoot() }.build())
        return ListTemplate.Builder().setTitle("Ver prévias").setHeaderAction(Action.BACK).setSingleList(list.build()).build()
    }
}

/** Casa and Trabalho, set from the car. */
class PlacesScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
        for ((key, label) in listOf("casa" to "Casa", "trabalho" to "Trabalho")) {
            val v = com.bordocar.Places.slot(carContext, key)?.address.orEmpty()
            list.addItem(Row.Builder().setTitle(label).addText(v.ifBlank { "Toque para definir" }).setBrowsable(true)
                .setOnClickListener { screenManager.push(SearchScreen(carContext, key to label)) }.build())
        }
        return ListTemplate.Builder().setTitle("Casa e Trabalho").setHeaderAction(Action.BACK).setSingleList(list.build()).build()
    }
}

/** Your favourite places (added on the phone): tap one and Waze / Google Maps take you there. */
class FavouritesScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val list = ItemList.Builder().setNoItemsMessage("Nenhum favorito ainda. Adicione no Bordo Car do celular, em Locais.")
        for (f in com.bordocar.Places.favourites(carContext).take(12)) {
            val r = Row.Builder().setTitle(f.name)
            if (f.address != f.name) r.addText(f.address)
            r.setOnClickListener { screenManager.pop(); navigateTo(carContext, f.target) }
            list.addItem(r.build())
        }
        return ListTemplate.Builder().setTitle("Favoritos").setHeaderAction(Action.BACK).setSingleList(list.build()).build()
    }
}

/**
 * Puts the dashboard on the car screen: Android Auto hands over a surface; a private virtual display shows on it, and the
 * dashboard (Compose) runs on that display. Taps come in as positions and go to whatever card is there.
 */
class DashSurface(private val carContext: CarContext, private val actions: DashActions) : SurfaceCallback {
    private var display: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private val owner = ComposeOwner()
    private val area = mutableStateOf<Rect?>(null)
    private val scale = mutableStateOf(1.5f)
    private var width = 1; private var height = 1
    private var dens = 1f

    /** Sized to about 720 units across the screen's short side (the size the design is drawn for), never below the car's own scale. */
    private fun fit() {
        val short = minOf(width, height).toFloat()
        val perUnit = (short / 720f).coerceIn(dens * 0.95f, dens * 2.4f)
        scale.value = perUnit / dens
    }

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        val s = surfaceContainer.surface ?: return
        width = surfaceContainer.width.coerceAtLeast(1); height = surfaceContainer.height.coerceAtLeast(1)
        val existing = display
        if (existing != null) { existing.surface = s; existing.resize(width, height, surfaceContainer.dpi); fit(); return }
        try {
            val dm = carContext.getSystemService(DisplayManager::class.java)
            val vd = dm.createVirtualDisplay("BordoCar", width, height, surfaceContainer.dpi, s, DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY)
            display = vd
            val p = Presentation(carContext, vd.display)
            dens = p.context.resources.displayMetrics.density
            fit()
            val root = FrameLayout(p.context)
            owner.start()
            root.setViewTreeLifecycleOwner(owner); root.setViewTreeSavedStateRegistryOwner(owner); root.setViewTreeViewModelStoreOwner(owner)
            val view = ComposeView(p.context)
            val appCtx = carContext.applicationContext
            view.setContent {
                val base = LocalDensity.current
                val d = Density(base.density * scale.value, 1f)
                CompositionLocalProvider(LocalDensity provides d) {
                    // keep inside the part Android Auto leaves free (its bar and buttons)
                    val a = area.value
                    fun px(v: Int) = (v.coerceAtLeast(0) / d.density).dp
                    val l = px(a?.left ?: 0); val t = px(a?.top ?: 0)
                    val r = px(width - (a?.right ?: width)); val b = px(height - (a?.bottom ?: height))
                    // (the top: Android Auto counts its two buttons there as covered, but the dashboard's top bar is made to sit under them)
                    CompositionLocalProvider(com.bordocar.ui.LocalOnCar provides true) { Dashboard(appCtx, actions, l + 16.dp, 12.dp, r + 16.dp, b + 12.dp) }
                }
            }
            root.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            p.setContentView(root)
            p.show()
            presentation = p
        } catch (e: Exception) { android.util.Log.w("BordoCar", "couldn't put the dashboard on the car screen", e) }
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) { display?.surface = null }
    override fun onVisibleAreaChanged(visibleArea: Rect) { area.value = Rect(visibleArea) }
    override fun onStableAreaChanged(stableArea: Rect) {}
    override fun onClick(x: Float, y: Float) {
        try { lastTap = "%.0f, %.0f".format(java.util.Locale.US, x, y) + if (CarTaps.hit(x, y)) " (botão)" else " (fora dos botões)" } catch (e: Exception) { }
    }
    companion object { @Volatile var lastTap = "" }

    fun release() {
        try { owner.stop() } catch (e: Exception) { }
        try { presentation?.dismiss() } catch (e: Exception) { }
        try { display?.release() } catch (e: Exception) { }
        presentation = null; display = null
    }
}

/** Lifecycle and saved state for Compose on the car screen (there's no activity there). */
class ComposeOwner : androidx.lifecycle.LifecycleOwner, androidx.savedstate.SavedStateRegistryOwner, androidx.lifecycle.ViewModelStoreOwner {
    private val reg = androidx.lifecycle.LifecycleRegistry(this)
    private val saved = androidx.savedstate.SavedStateRegistryController.create(this)
    private val store = androidx.lifecycle.ViewModelStore()
    override val lifecycle: androidx.lifecycle.Lifecycle get() = reg
    override val savedStateRegistry: androidx.savedstate.SavedStateRegistry get() = saved.savedStateRegistry
    override val viewModelStore: androidx.lifecycle.ViewModelStore get() = store
    private var on = false
    fun start() { if (on) return; on = true; saved.performRestore(null); reg.currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
    fun stop() { if (!on) return; reg.currentState = androidx.lifecycle.Lifecycle.State.DESTROYED; store.clear() }
}
