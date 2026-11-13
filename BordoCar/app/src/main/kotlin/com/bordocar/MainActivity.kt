package com.bordocar

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.bordocar.ui.DashActions
import com.bordocar.ui.Dashboard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val Bg = Color(0xFF0B1222)
private val CardC = Color(0xFF16213A)
private val Edge = Color(0x3878A0FF)
private val Sub = Color(0xFF9FB0D0)
private val Accent = Color(0xFF3D8BFF)
private val Ok = Color(0xFF34C759)

/** The phone screen: a preview of the dashboard, the permissions it needs, your Casa and Trabalho, and the download link. */
class MainActivity : ComponentActivity() {
    private var refresh = mutableIntStateOf(0)
    /** Choosing a place: for Casa / Trabalho ([key]) or a new favourite (key null). */
    private class Pick(val key: String?, val label: String)
    private val GO = "__go"
    /** Starts the route to [p] in Waze / Google Maps from the phone. */
    private fun go(p: Place) {
        val app = com.bordocar.car.startRouteFromPhone(this, p.target, Drive.pos ?: lastKnown())
        android.widget.Toast.makeText(this, if (app != null) "Abrindo no $app…" else "Não achei o Waze nem o Google Maps", android.widget.Toast.LENGTH_SHORT).show()
    }
    private var picking by mutableStateOf<Pick?>(null)
    /** The trips shown on the map (Viagens), and its title. */
    private var mapTrips by mutableStateOf<List<Trip>?>(null)
    private var mapTitle = ""
    private var tab by mutableIntStateOf(0)
    /** The spreadsheet waiting to be written where you chose to save it. */
    private var pendingCsv = ""
    private val saveCsv = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) try {
            contentResolver.openOutputStream(uri)?.use { it.write(("\uFEFF" + pendingCsv).toByteArray(Charsets.UTF_8)) }
            android.widget.Toast.makeText(this, "Planilha salva", android.widget.Toast.LENGTH_SHORT).show()
        } catch (e: Exception) { android.widget.Toast.makeText(this, "Não consegui salvar a planilha", android.widget.Toast.LENGTH_SHORT).show() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // (the old test log of Waze's notices, no longer kept)
        try { java.io.File(filesDir, "avisos-nav.txt").delete() } catch (e: Exception) { }
        Drive.init(this)
        setContent { Screen() }
    }

    override fun onResume() { super.onResume(); refresh.intValue++; startGps() }
    override fun onPause() { super.onPause(); stopGps() }

    // with the phone's screen open (and the car not connected), the preview shows your speed and compass live
    private var lm: android.location.LocationManager? = null
    private val gps = object : android.location.LocationListener {
        override fun onLocationChanged(l: android.location.Location) {
            if (DriveService.running) return
            try { Drive.onLocation(this@MainActivity, l); Drive.pos?.let { Limits.update(it, Drive.heading) } } catch (e: Exception) { }
        }
        override fun onProviderDisabled(provider: String) {}
        override fun onProviderEnabled(provider: String) {}
        @Deprecated("Needed on Android 10 and older")
        @Suppress("DEPRECATION")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }
    @android.annotation.SuppressLint("MissingPermission")
    private fun startGps() {
        if (lm != null || !has(Manifest.permission.ACCESS_FINE_LOCATION)) return
        try {
            val m = getSystemService(android.location.LocationManager::class.java)
            m.requestLocationUpdates(android.location.LocationManager.GPS_PROVIDER, 1000L, 0f, gps, mainLooper)
            lm = m
        } catch (e: Exception) { }
    }
    private fun stopGps() { try { lm?.removeUpdates(gps) } catch (e: Exception) { }; lm = null }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh.intValue++; startGps()
    }

    private fun has(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    @Composable
    private fun Screen() {
        @Suppress("UNUSED_VARIABLE") val r = refresh.intValue
        val prefs = remember { Prefs(this) }
        var tick by remember { mutableIntStateOf(0) }
        LaunchedEffect(Unit) { while (true) { try { NowPlaying.refresh(this@MainActivity) } catch (e: Exception) { }; tick++; delay(1000) } }
        val pk = picking
        if (pk != null) { Picker(pk) { picking = null; refresh.intValue++ }; return }
        // a trip (or a whole day) on the map
        val mt = mapTrips
        if (mt != null) {
            androidx.activity.compose.BackHandler { mapTrips = null }
            Box(Modifier.fillMaxSize().systemBarsPadding()) { com.bordocar.ui.PhoneRouteMap(mt, mapTitle) { mapTrips = null } }
            return
        }
        val scroll = rememberScrollState()
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        Column(Modifier.fillMaxSize().background(Bg).systemBarsPadding().verticalScroll(scroll).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("Bordo Car", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
                Text("  versão ${BuildConfig.VERSION_NAME}", color = Sub, fontSize = 13.sp, modifier = Modifier.padding(bottom = 4.dp))
            }
            // three tabs: the dashboard and settings, the trips, the fill-ups
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CardC).padding(4.dp)) {
                for ((i, label) in listOf("Início", "Viagens", "Abastecimento").withIndex())
                    Box(Modifier.weight(1f).clip(RoundedCornerShape(11.dp)).background(if (tab == i) Accent else Color.Transparent).clickable { tab = i }.padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center) { Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
            }
            if (tab == 1) { Trips(); return@Column }
            if (tab == 2) { FuelTab(); return@Column }
            ParkingCard()
            Text("O painel do carro no Android Auto. A navegação fica com o Waze ou o Google Maps.", color = Sub, fontSize = 14.sp)

            // start a route here and Bordo Car knows where it goes (arrival, the map of the turn counting the streets)
            Section("Para onde?") {
                val places = listOfNotNull(Places.slot(this@MainActivity, "casa")?.copy(name = "🏠 Casa"), Places.slot(this@MainActivity, "trabalho")?.copy(name = "💼 Trabalho")) +
                    Places.favourites(this@MainActivity).map { it.copy(name = "★ " + it.name) }
                if (places.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (p in places) Box(Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xFF0F1830)).clickable { go(p) }.padding(horizontal = 14.dp, vertical = 9.dp)) {
                        Text(p.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                }
                Button("🔍 Buscar um lugar") { picking = Pick(GO, "Para onde?") }
                Text("Abre a rota no ${if (prefs.s("navapp", "waze") == "maps") "Google Maps" else "Waze"} (o escolhido em Navegar com) e o painel já sabe o destino: " +
                    "a chegada e o mapa da curva contando as ruas.", color = Sub, fontSize = 12.sp)
            }

            // a live preview of the car screen
            Section("Prévia do painel") {
                BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(1500f / 560f).clip(RoundedCornerShape(14.dp))) {
                    val base = LocalDensity.current
                    val k = constraints.maxWidth / 1500f
                    CompositionLocalProvider(LocalDensity provides Density(k, 1f)) {
                        Dashboard(applicationContext, object : DashActions { override fun home() {}; override fun work() {}; override fun fuel() {}; override fun favourites() {}; override fun search() {} }, 12.dp, 12.dp, 12.dp, 12.dp)
                    }
                    @Suppress("UNUSED_VARIABLE") val b = base
                }
            }

            Section("Navegar com") {
                var nav by remember { mutableStateOf(prefs.s("navapp", "waze")) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((k, label) in listOf("waze" to "Waze", "maps" to "Google Maps"))
                        Box(Modifier.clip(RoundedCornerShape(10.dp)).background(if (nav == k) Accent else Color(0xFF0F1830)).clickable { nav = k; prefs.put("navapp", k) }
                            .padding(horizontal = 16.dp, vertical = 9.dp)) { Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
                }
                Text("Casa, Trabalho, Favoritos, Postos e Buscar abrem a rota neste app.", color = Sub, fontSize = 12.sp)
            }

            Section("Modo do painel") {
                com.bordocar.ui.Modes.load(this@MainActivity)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    for ((k, label) in com.bordocar.ui.Modes.all) {
                        val on = com.bordocar.ui.Modes.cur == k
                        Box(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (on) Accent else Color(0xFF0F1830)).clickable {
                                com.bordocar.ui.Modes.set(this@MainActivity, k); scope.launch { scroll.animateScrollTo(0) } }
                            .padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                            Text(label, color = Color.White, fontSize = 14.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold, maxLines = 1)
                        }
                    }
                }
                Text(when (com.bordocar.ui.Modes.cur) {
                    "esportivo" -> "Esportivo: luzes, velocidade grande e a próxima curva dentro da bússola."
                    "musica" -> "Música: o carro na estrada no meio, a velocidade e o disco da música com o tempo."
                    "estrada" -> "Estrada: chegada e quanto falta, a próxima curva grande, média e há quanto tempo está dirigindo."
                    else -> "Normal: música, próxima curva ou atalhos, velocidade, bússola e os km."
                } + " A prévia lá em cima já mostra.", color = Sub, fontSize = 12.sp)
            }

            Section("Mapa da curva (modo Estrada)") {
                var on by remember { mutableStateOf(prefs.b("junction", true)) }
                var tok by remember { mutableStateOf(MapRoute.token(this@MainActivity)) }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("Mostrar o mapa da curva", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text("Faltando 500 m para a curva, o modo Estrada mostra o mapa de cima do cruzamento (Mapbox). Com a rota começada " +
                            "pelos atalhos do Bordo, conta as ruas: \"3ª à direita\". Usa internet.", color = Sub, fontSize = 12.sp)
                    }
                    androidx.compose.material3.Switch(on, { on = it; prefs.put("junction", it) })
                }
                OutlinedTextField(tok, { tok = it.trim(); prefs.put("mapbox", tok) }, label = { Text("Chave do Mapbox (pk.…)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                        focusedBorderColor = Accent, unfocusedBorderColor = Edge, focusedLabelColor = Sub, unfocusedLabelColor = Sub))
                Box(Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xFF0F1830)).clickable { com.bordocar.ui.JunctionPreview.start(this@MainActivity); scope.launch { scroll.animateScrollTo(0) } }
                    .padding(horizontal = 16.dp, vertical = 9.dp)) { Text("Ver prévia (16 s)", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
                Text("A prévia anda por uma rota perto de você até a primeira curva, no painel aqui em cima e na tela do carro, se estiver conectado.", color = Sub, fontSize = 12.sp)
                if (MapRoute.note.isNotBlank()) Text("Última rota: ${MapRoute.note}", color = Sub, fontSize = 12.sp)
            }

            Section("Painel esportivo") {
                com.bordocar.ui.Sport.load(this@MainActivity)
                val on = com.bordocar.ui.Sport.enabled
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("Trocar para o esportivo acima de ${com.bordocar.ui.Sport.ON_KMH} km/h", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text("Vale para qualquer modo: volta ao modo escolhido abaixo de ${com.bordocar.ui.Sport.OFF_KMH} km/h.", color = Sub, fontSize = 12.sp)
                    }
                    androidx.compose.material3.Switch(on, { com.bordocar.ui.Sport.set(this@MainActivity, it) })
                }
                Box(Modifier.padding(top = 8.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF0F1830)).clickable { com.bordocar.ui.Sport.preview(this@MainActivity); scope.launch { scroll.animateScrollTo(0) } }
                    .padding(horizontal = 16.dp, vertical = 9.dp)) { Text("Ver prévia (14 s)", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
                Text("A prévia aparece no painel aqui em cima e na tela do carro, se estiver conectado.", color = Sub, fontSize = 12.sp)
            }

            Section("Tema do painel") {
                com.bordocar.ui.CarLook.load(this@MainActivity)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()
                    .clickable { com.bordocar.ui.CarLook.set(this@MainActivity, !com.bordocar.ui.CarLook.on); scope.launch { scroll.animateScrollTo(0) } }) {
                    Column(Modifier.weight(1f)) {
                        Text("Adaptar ao carro", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text("Normal e Estrada com o fundo grafite da tela do 208, sem caixas, ícones finos e sem a temperatura (o carro já mostra). Esportivo e Música ficam como estão.",
                            color = Sub, fontSize = 12.sp)
                    }
                    androidx.compose.material3.Switch(com.bordocar.ui.CarLook.on, { com.bordocar.ui.CarLook.set(this@MainActivity, it) })
                }
                com.bordocar.ui.Themes.load(this@MainActivity)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    for (th in com.bordocar.ui.Themes.all) {
                        val on = com.bordocar.ui.Themes.cur.key == th.key
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(androidx.compose.ui.graphics.Brush.linearGradient(th.bg))
                            .border(if (on) 2.dp else 1.dp, if (on) th.accent.last() else Edge, RoundedCornerShape(12.dp))
                            .clickable { com.bordocar.ui.Themes.set(this@MainActivity, th) }.padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(22.dp).clip(RoundedCornerShape(11.dp)).background(androidx.compose.ui.graphics.Brush.linearGradient(
                                if (th.key == "capa") listOf(Color(0xFFFF5A4E), Color(0xFFFFC24E), Color(0xFF2FCB8E), Color(0xFF3D8BFF), Color(0xFFB45CFF)) else th.accent)))
                            Text(th.name.substringBefore(' '), color = Color.White, fontSize = 12.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                }
                Text("Capa: as cores acompanham a capa da música que está tocando. Também dá para trocar no carro, em Ajustes (a engrenagem).", color = Sub, fontSize = 12.sp)
            }

            Section("Permissões") {
                val loc = has(Manifest.permission.ACCESS_FINE_LOCATION)
                val notif = Build.VERSION.SDK_INT < 33 || has(Manifest.permission.POST_NOTIFICATIONS)
                Item("Localização", "Velocidade, bússola e os km do dia (GPS)", loc, "Permitir") {
                    val ps = arrayListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                    if (Build.VERSION.SDK_INT >= 33) ps += Manifest.permission.POST_NOTIFICATIONS
                    requestPermissions(ps.toTypedArray(), 1)
                }
                if (loc && Build.VERSION.SDK_INT >= 29) {
                    val bg = has(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    Item("Localização o tempo todo", "Para a velocidade e os km continuarem com o app do celular fechado (escolha \"Permitir o tempo todo\")", bg, "Permitir") {
                        requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 3)
                    }
                }
                val over = android.provider.Settings.canDrawOverlays(this@MainActivity)
                Item("Sobrepor a outros apps", "Para Casa, Trabalho, Favoritos, Postos e Buscar conseguirem abrir o Waze ou o Google Maps", over, "Permitir") {
                    try { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) } catch (e: Exception) { }
                }
                if (Build.VERSION.SDK_INT >= 34) {
                    val fsi = getSystemService(android.app.NotificationManager::class.java).canUseFullScreenIntent()
                    Item("Notificações em tela cheia", "Para Casa, Trabalho, Favoritos e Buscar abrirem a rota mesmo com o celular bloqueado", fsi, "Permitir") {
                        try { startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName"))) } catch (e: Exception) { }
                    }
                }
                if (!notif) Item("Notificações", "O aviso discreto enquanto conta os km no carro", false, "Permitir") {
                    if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
                }
                Item("Acesso às notificações", "A música tocando e a próxima curva do Waze / Google Maps", NowPlaying.isAllowed(this@MainActivity), "Abrir") {
                    try { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) } catch (e: Exception) { }
                }
                if (!NowPlaying.isAllowed(this@MainActivity)) Text("Se o Android disser que é uma \"configuração restrita\": em Configurações › Apps › Bordo Car, toque nos três pontinhos (⋮) › \"Permitir configurações restritas\" e tente de novo.",
                    color = Sub, fontSize = 12.sp)
            }

            Section("Locais") {
                for ((key, label) in listOf("casa" to "Casa", "trabalho" to "Trabalho")) {
                    val p = Places.slot(this@MainActivity, key)
                    PlaceRow(label, p?.address ?: "Ainda não definido", if (p == null) "Definir" else "Alterar") { picking = Pick(key, label) }
                }
                Text("FAVORITOS", color = Sub, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, modifier = Modifier.padding(top = 6.dp))
                val favs = Places.favourites(this@MainActivity)
                if (favs.isEmpty()) Text("Nenhum ainda. No carro, o atalho Favoritos mostra a lista para abrir no Waze ou no Maps.", color = Sub, fontSize = 13.sp)
                for (f in favs) PlaceRow(f.name, f.address, "Remover") { Places.removeFavourite(this@MainActivity, f); refresh.intValue++ }
                Button("+ Adicionar favorito") { picking = Pick(null, "Favorito") }
            }

            Section("Próxima curva do Waze / Maps") {
                val raw = NavNotice.lastRaw
                val tap = com.bordocar.car.DashSurface.lastTap
                if (tap.isNotBlank()) Text("Último toque na tela do carro: $tap", color = Sub, fontSize = 12.sp)
                val hist = com.bordocar.car.navHistory.toList()
                if (hist.isNotEmpty()) Text("Destinos enviados pelo carro (o mais novo em cima):\n" + hist.take(8).joinToString("\n"), color = Sub, fontSize = 12.sp)
                if (Weather.lastNote.isNotBlank()) Text("Clima: ${Weather.lastNote}", color = Sub, fontSize = 12.sp)
                if (DriveService.running || Drive.lastFixAt > 0L) {
                    val ago = if (Drive.lastFixAt > 0L) "${(System.currentTimeMillis() - Drive.lastFixAt) / 1000} s" else "nenhum ainda"
                    Text("GPS do carro: último sinal há $ago" + (if (DriveService.gpsNote.isNotBlank()) " (${DriveService.gpsNote})" else ""), color = Sub, fontSize = 12.sp)
                }
                Text(if (raw.isBlank()) "Ainda nada lido. Comece uma rota no Waze ou no Google Maps e volte aqui para ver o que eles mostram." else "Última lida: $raw",
                    color = Sub, fontSize = 13.sp)
            }

            Section("Android Auto") {
                Text("Para o Bordo Car aparecer no carro: no Android Auto, ative as configurações de desenvolvedor (toque 10× em \"Versão\") e ligue \"Fontes desconhecidas\".",
                    color = Sub, fontSize = 13.sp)
                if (BuildConfig.UPDATE_REPO.isNotBlank()) Button("Baixar a versão mais nova") {
                    try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/${BuildConfig.UPDATE_REPO}/releases/download/app/BordoCar.apk"))) } catch (e: Exception) { }
                }
            }
            @Suppress("UNUSED_VARIABLE") val t = tick
            Spacer(Modifier.size(20.dp))
        }
    }

    // ---------------------------------------------------------------- where the car is parked

    @Composable
    private fun ParkingCard() {
        @Suppress("UNUSED_VARIABLE") val r = refresh.intValue
        val spot = Parking.get(this) ?: return
        var addr by remember(spot.at) { mutableStateOf("") }
        LaunchedEffect(spot.at) { addr = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Places.here(this@MainActivity, spot.pos).address } }
        val mins = ((System.currentTimeMillis() - spot.at) / 60_000L).toInt()
        val ago = when { mins < 1 -> "agora"; mins < 60 -> "há $mins min"; mins < 1440 -> "há ${mins / 60} h ${mins % 60} min"; else -> "há ${mins / 1440} dia(s)" }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(CardC).border(1.dp, Accent, RoundedCornerShape(18.dp)).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("🚗  ONDE ESTACIONEI", color = Sub, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
            Text("Seu carro está aqui", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(listOf(addr, ago).filter { it.isNotBlank() }.joinToString(" · "), color = Sub, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
                Button("Ir até o carro a pé") {
                    val ll = "%.6f,%.6f".format(java.util.Locale.US, spot.pos.lat, spot.pos.lon)
                    val walk = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$ll&mode=w")).setPackage("com.google.android.apps.maps")
                    try { startActivity(walk) } catch (e: Exception) {
                        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$ll?q=$ll(Meu carro)"))) } catch (x: Exception) { }
                    }
                }
                Box(Modifier.clip(RoundedCornerShape(12.dp)).border(1.dp, Accent, RoundedCornerShape(12.dp)).clickable { Parking.clear(this@MainActivity); refresh.intValue++ }
                    .padding(horizontal = 12.dp, vertical = 8.dp)) { Text("Já peguei", color = Color(0xFF7FB3FF), fontSize = 14.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }

    // ---------------------------------------------------------------- trips

    /** "Casa", "Trabalho" or a favourite's name when the spot is one of them, otherwise the street (looked up once). */
    private val names = HashMap<String, String>()
    private fun placeName(p: LL?): String {
        if (p == null) return ""
        val saved = listOfNotNull(Places.slot(this, "casa"), Places.slot(this, "trabalho")) + Places.favourites(this)
        for (s in saved) if (s.lat != null && s.lon != null && dist(p, LL(s.lat, s.lon)) < 200.0) return s.name
        val k = "%.3f,%.3f".format(java.util.Locale.US, p.lat, p.lon)
        return names.getOrPut(k) { try { @Suppress("DEPRECATION") android.location.Geocoder(this, java.util.Locale("pt", "BR")).getFromLocation(p.lat, p.lon, 1)?.firstOrNull()?.let { a ->
            a.thoroughfare ?: a.subLocality ?: a.locality } ?: "" } catch (e: Exception) { "" } }
    }

    @Composable
    private fun Trips() {
        @Suppress("UNUSED_VARIABLE") val r = refresh.intValue
        val (m, s, n) = Drive.month()
        val br = java.util.Locale("pt", "BR")
        Section(java.text.SimpleDateFormat("MMMM", br).format(java.util.Date())) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat(String.format(br, "%.0f km", m / 1000.0), "no mês", Modifier.weight(1f))
                Stat("$n", "viagens", Modifier.weight(1f))
                Stat("${s / 3600} h ${"%02d".format((s % 3600) / 60)}", "dirigindo", Modifier.weight(1f))
            }
            Button("Exportar planilha") {
                val f = java.text.SimpleDateFormat("dd/MM/yyyy;HH:mm", br)
                val sb = StringBuilder("Data;Saída;Chegada;De;Para;Km;Minutos\n")
                for (tr in Drive.trips()) {
                    val a = f.format(java.util.Date(tr.start)).split(";")
                    sb.append(a[0]).append(';').append(a[1]).append(';').append(java.text.SimpleDateFormat("HH:mm", br).format(java.util.Date(tr.end))).append(';')
                        .append(names["%.3f,%.3f".format(java.util.Locale.US, tr.from?.lat ?: 0.0, tr.from?.lon ?: 0.0)] ?: "").append(';')
                        .append(names["%.3f,%.3f".format(java.util.Locale.US, tr.to?.lat ?: 0.0, tr.to?.lon ?: 0.0)] ?: "").append(';')
                        .append(String.format(br, "%.1f", tr.dist / 1000.0)).append(';').append(tr.moving / 60).append('\n')
                }
                pendingCsv = sb.toString()
                saveCsv.launch("BordoCar-viagens.csv")
            }
        }
        val trips = Drive.trips().take(80)
        if (trips.isEmpty()) Text(if (n > 0) "Viagem em andamento: ela aparece aqui quando terminar (2 minutos parado ou ao desligar o carro)."
            else "Nenhuma viagem ainda. Elas aparecem aqui quando você dirige com o celular no Android Auto.", color = Sub, fontSize = 14.sp)
        val day = java.text.SimpleDateFormat("yyyyMMdd", br)
        val today = day.format(java.util.Date()); val yest = day.format(java.util.Date(System.currentTimeMillis() - 86_400_000L))
        for ((d, list) in trips.groupBy { day.format(java.util.Date(it.start)) }) {
            val title = when (d) { today -> "Hoje"; yest -> "Ontem"; else -> java.text.SimpleDateFormat("EEEE, d 'de' MMMM", br).format(java.util.Date(list.first().start)) }
            Section(title) {
                if (list.size > 1) Text("Ver o dia no mapa ›", color = Accent, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { mapTitle = title; mapTrips = list })
                for (tr in list) {
                    val from = remember(tr.start) { mutableStateOf("") }; val to = remember(tr.start) { mutableStateOf("") }
                    LaunchedEffect(tr.start) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { from.value = placeName(tr.from); to.value = placeName(tr.to) }
                    }
                    val hm = java.text.SimpleDateFormat("HH:mm", br)
                    // a tap shows this trip on the map
                    Row(Modifier.clickable { mapTitle = "$title · ${hm.format(java.util.Date(tr.start))}"; mapTrips = listOf(tr) }, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${hm.format(java.util.Date(tr.start))} → ${hm.format(java.util.Date(tr.end))}", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            val route = listOf(from.value, to.value).filter { it.isNotBlank() }
                            if (route.size == 2) Text("${route[0]} → ${route[1]}", color = Sub, fontSize = 12.sp)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(String.format(br, "%.1f km", tr.dist / 1000.0), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Text("${tr.moving / 60} min", color = Sub, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Stat(big: String, small: String, modifier: Modifier) {
        Column(modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFF0F1830)).padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(big, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(small, color = Sub, fontSize = 11.sp)
        }
    }

    // ---------------------------------------------------------------- fill-ups

    @Composable
    private fun FuelTab() {
        @Suppress("UNUSED_VARIABLE") val r = refresh.intValue
        val br = java.util.Locale("pt", "BR")
        fun money(v: Double) = "R$ " + String.format(br, "%.2f", v)
        val (spent, kml, perKm) = Fuel.month(this)
        Section("Este mês · " + java.text.SimpleDateFormat("MMMM", br).format(java.util.Date())) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat(kml?.let { String.format(br, "%.1f", it) } ?: "--", "km por litro", Modifier.weight(1f))
                Stat(perKm?.let { money(it) } ?: "--", "por km", Modifier.weight(1f))
                Stat(money(spent).replace(",00", ""), "gasto no mês", Modifier.weight(1f))
            }
            if (kml == null) Text("O km por litro aparece a partir do segundo abastecimento com o tanque cheio.", color = Sub, fontSize = 12.sp)
        }
        // a new fill-up
        var litres by remember { mutableStateOf("") }
        var paid by remember { mutableStateOf("") }
        var kind by remember { mutableStateOf(Prefs(this).s("fuelkind", "Gasolina")) }
        var full by remember { mutableStateOf(true) }
        val fieldColors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Accent, unfocusedBorderColor = Edge,
            focusedLabelColor = Accent, unfocusedLabelColor = Sub, cursorColor = Accent)
        fun num(s: String) = s.trim().replace("R$", "").replace(" ", "").let { if (it.contains(',')) it.replace(".", "").replace(',', '.') else it }.toDoubleOrNull()
        Section("Novo abastecimento") {
            val dec = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal)
            OutlinedTextField(litres, { litres = it }, label = { Text("Litros") }, singleLine = true, keyboardOptions = dec, modifier = Modifier.fillMaxWidth(), colors = fieldColors)
            OutlinedTextField(paid, { paid = it }, label = { Text("Valor pago (R$)") }, singleLine = true, keyboardOptions = dec, modifier = Modifier.fillMaxWidth(), colors = fieldColors)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (k in listOf("Gasolina", "Etanol", "Diesel", "GNV"))
                    Box(Modifier.clip(RoundedCornerShape(10.dp)).background(if (kind == k) Accent else Color(0xFF0F1830)).clickable { kind = k }.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text(k, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { full = !full }) {
                androidx.compose.material3.Checkbox(full, { full = it }, colors = androidx.compose.material3.CheckboxDefaults.colors(checkedColor = Accent, uncheckedColor = Sub))
                Text("Enchi o tanque", color = Color.White, fontSize = 15.sp)
            }
            val l = num(litres); val v = num(paid)
            val last = Fuel.all(this@MainActivity).lastOrNull()
            if (l != null && v != null && l > 0) Text(money(v / l) + " por litro" + (last?.let { " · " + String.format(br, "%.0f km", (Drive.odometer() - it.odo) / 1000.0) + " rodados desde o último" } ?: ""), color = Sub, fontSize = 12.sp)
            Button("Salvar") {
                if (l == null || v == null || l <= 0 || v <= 0) { android.widget.Toast.makeText(this@MainActivity, "Preencha os litros e o valor", android.widget.Toast.LENGTH_SHORT).show(); return@Button }
                Fuel.add(this@MainActivity, Fuel.Fill(System.currentTimeMillis(), l, v, kind, full, Drive.odometer()))
                Prefs(this@MainActivity).put("fuelkind", kind)
                litres = ""; paid = ""; refresh.intValue++
            }
        }
        val list = Fuel.all(this).reversed()
        if (list.isNotEmpty()) Section("Últimos") {
            val cons = Fuel.consumption(Fuel.all(this@MainActivity))
            val df = java.text.SimpleDateFormat("dd/MM", br)
            for (f in list.take(30)) Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${df.format(java.util.Date(f.at))} · " + String.format(br, "%.1f L", f.litres), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(listOfNotNull(f.kind, money(f.paid), cons[f.at]?.let { String.format(br, "%.1f km/l", it) }, if (!f.full) "parcial" else null).joinToString(" · "), color = Sub, fontSize = 12.sp)
                }
                Text("Remover", color = Color(0xFF7FB3FF), fontSize = 13.sp, modifier = Modifier.clickable { Fuel.remove(this@MainActivity, f); refresh.intValue++ }.padding(6.dp))
            }
            Button("Exportar planilha") {
                val dfx = java.text.SimpleDateFormat("dd/MM/yyyy", br)
                val sb = StringBuilder("Data;Combustível;Litros;Valor;Preço por litro;Tanque cheio;Km por litro\n")
                for (f in Fuel.all(this@MainActivity)) sb.append(dfx.format(java.util.Date(f.at))).append(';').append(f.kind).append(';')
                    .append(String.format(br, "%.2f", f.litres)).append(';').append(String.format(br, "%.2f", f.paid)).append(';')
                    .append(String.format(br, "%.3f", f.paid / f.litres)).append(';').append(if (f.full) "sim" else "não").append(';')
                    .append(cons[f.at]?.let { String.format(br, "%.1f", it) } ?: "").append('\n')
                pendingCsv = sb.toString()
                saveCsv.launch("BordoCar-abastecimentos.csv")
            }
        }
    }

    @Composable
    private fun PlaceRow(title: String, address: String, action: String, onClick: () -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 10.dp)) {
                Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(address, color = Sub, fontSize = 13.sp)
            }
            Button(action, onClick)
        }
    }

    /**
     * Picking a place: type and search (Android's address search, the same places Google Maps knows), or use where you are now;
     * the results show their full address, and a tap keeps it. A favourite also gets a name of your choice.
     */
    @Composable
    private fun Picker(pk: Pick, done: () -> Unit) {
        var name by remember { mutableStateOf("") }
        var q by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var results by remember { mutableStateOf<List<Place>?>(null) }
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        fun keep(p: Place) {
            if (pk.key == GO) { done(); go(p); return }
            if (pk.key != null) Places.setSlot(this@MainActivity, pk.key, p.copy(name = pk.label))
            else Places.addFavourite(this@MainActivity, p.copy(name = name.trim().ifBlank { p.name }))
            done()
        }
        fun search() {
            if (q.isBlank()) return
            busy = true
            scope.launch {
                val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Places.search(this@MainActivity, q.trim(), Drive.pos ?: lastKnown()) }
                results = r; busy = false
            }
        }
        val fieldColors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Accent, unfocusedBorderColor = Edge,
            focusedLabelColor = Accent, unfocusedLabelColor = Sub, cursorColor = Accent)
        androidx.activity.compose.BackHandler { done() }
        Column(Modifier.fillMaxSize().background(Bg).systemBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (pk.key == GO) pk.label else if (pk.key != null) "Escolher ${pk.label}" else "Novo favorito", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                Text("Cancelar", color = Accent, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { done() }.padding(8.dp))
            }
            if (pk.key == null) OutlinedTextField(name, { name = it }, label = { Text("Nome (ex.: Academia, Mãe)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = fieldColors)
            OutlinedTextField(q, { q = it }, label = { Text("Endereço ou nome do lugar") }, modifier = Modifier.fillMaxWidth(), colors = fieldColors, minLines = 2, maxLines = 4,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { search() }))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(if (busy) "Buscando…" else "Buscar") { search() }
                if (pk.key != GO) Button("Usar onde estou agora") {
                    val here = Drive.pos ?: lastKnown()
                    if (here != null) scope.launch {
                        val p = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Places.here(this@MainActivity, here) }
                        results = listOf(p)
                    } else android.widget.Toast.makeText(this@MainActivity, "Ainda sem sinal do GPS", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            val r = results
            if (r != null && r.isEmpty()) Text("Nada encontrado. Tente com o nome da rua e a cidade (ex.: Rua Dois, 120, Teresina).", color = Sub, fontSize = 14.sp)
            if (r != null && r.isNotEmpty()) Text("Toque no lugar certo:", color = Sub, fontSize = 13.sp)
            for (p in r.orEmpty()) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CardC).border(1.dp, Edge, RoundedCornerShape(14.dp)).clickable { keep(p) }.padding(14.dp)) {
                Text(p.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(p.address, color = Sub, fontSize = 13.sp)
            }
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun lastKnown(): LL? = try {
        val m = getSystemService(android.location.LocationManager::class.java)
        (m.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER) ?: m.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER))?.let { LL(it.latitude, it.longitude) }
    } catch (e: Exception) { null }

    @Composable
    private fun Section(title: String, content: @Composable () -> Unit) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(CardC).border(1.dp, Edge, RoundedCornerShape(18.dp)).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title.uppercase(), color = Sub, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
            content()
        }
    }

    @Composable
    private fun Item(title: String, sub: String, ok: Boolean, action: String, onClick: () -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(sub, color = Sub, fontSize = 12.sp)
            }
            if (ok) Text("✓ ok", color = Ok, fontSize = 15.sp, fontWeight = FontWeight.Bold) else Button(action, onClick)
        }
    }

    @Composable
    private fun Button(text: String, onClick: () -> Unit) {
        Box(Modifier.clip(RoundedCornerShape(12.dp)).background(Accent).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp)) {
            Text(text, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}
