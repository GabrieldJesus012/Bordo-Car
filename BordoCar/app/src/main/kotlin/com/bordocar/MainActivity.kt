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
    private var picking by mutableStateOf<Pick?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        Column(Modifier.fillMaxSize().background(Bg).systemBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("Bordo Car", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
                Text("  versão ${BuildConfig.VERSION_NAME}", color = Sub, fontSize = 13.sp, modifier = Modifier.padding(bottom = 4.dp))
            }
            Text("O painel do carro no Android Auto. A navegação fica com o Waze ou o Google Maps.", color = Sub, fontSize = 14.sp)

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

            Section("Permissões") {
                val loc = has(Manifest.permission.ACCESS_FINE_LOCATION)
                val notif = Build.VERSION.SDK_INT < 33 || has(Manifest.permission.POST_NOTIFICATIONS)
                Item("Localização", "Velocidade, bússola e os km do dia (GPS)", loc, "Permitir") {
                    val ps = arrayListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                    if (Build.VERSION.SDK_INT >= 33) ps += Manifest.permission.POST_NOTIFICATIONS
                    requestPermissions(ps.toTypedArray(), 1)
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
                Text(if (pk.key != null) "Escolher ${pk.label}" else "Novo favorito", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                Text("Cancelar", color = Accent, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { done() }.padding(8.dp))
            }
            if (pk.key == null) OutlinedTextField(name, { name = it }, label = { Text("Nome (ex.: Academia, Mãe)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = fieldColors)
            OutlinedTextField(q, { q = it }, label = { Text("Endereço ou nome do lugar") }, modifier = Modifier.fillMaxWidth(), colors = fieldColors, minLines = 2, maxLines = 4,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { search() }))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(if (busy) "Buscando…" else "Buscar") { search() }
                Button("Usar onde estou agora") {
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
