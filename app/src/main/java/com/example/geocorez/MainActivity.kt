package com.example.geocorez

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
// Mapbox
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.ImageHolder
import com.mapbox.maps.MapView
import com.mapbox.maps.Style
import com.mapbox.maps.plugin.LocationPuck2D
import com.mapbox.maps.plugin.annotation.annotations
import com.mapbox.maps.plugin.annotation.generated.CircleAnnotationManager
import com.mapbox.maps.plugin.annotation.generated.CircleAnnotationOptions
import com.mapbox.maps.plugin.annotation.generated.PointAnnotationManager
import com.mapbox.maps.plugin.annotation.generated.PointAnnotationOptions
import com.mapbox.maps.plugin.annotation.generated.PolygonAnnotationManager
import com.mapbox.maps.plugin.annotation.generated.PolygonAnnotationOptions
import com.mapbox.maps.plugin.annotation.generated.PolylineAnnotationManager
import com.mapbox.maps.plugin.annotation.generated.PolylineAnnotationOptions
import com.mapbox.maps.plugin.annotation.generated.createCircleAnnotationManager
import com.mapbox.maps.plugin.annotation.generated.createPointAnnotationManager
import com.mapbox.maps.plugin.annotation.generated.createPolygonAnnotationManager
import com.mapbox.maps.plugin.annotation.generated.createPolylineAnnotationManager
import com.mapbox.maps.plugin.gestures.OnMapClickListener
import com.mapbox.maps.plugin.gestures.OnMapLongClickListener
import com.mapbox.maps.plugin.gestures.gestures
import com.mapbox.maps.plugin.locationcomponent.location
// Firebase
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
// Utilidades
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Una geocerca. Puede ser:
 *  - "circulo": centro (lat, lng) y radio en metros.
 *  - "poligono": zona de forma libre definida por [puntos] (lat, lng de cada vértice).
 * En los polígonos, (lat, lng) guarda el centro aproximado y sirve para etiquetas y para "Ir a".
 */
data class Geocerca(
    val id: String,
    var nombre: String,
    var tipo: String,
    var lat: Double,
    var lng: Double,
    var radio: Double,
    var puntos: MutableList<Point>
) {
    val esPoligono: Boolean get() = tipo == "poligono"
}

/**
 * Un puntero. Todos los punteros tienen la misma configuración (nombre, color, modo GPS/manual)
 * y cada uno tiene su propio conjunto de geocercas asignadas: relación muchos a muchos
 * (un puntero puede tener varias geocercas y una geocerca puede estar en varios punteros).
 */
data class Puntero(
    val id: String,
    var nombre: String,
    var color: String,
    var modo: String, // "gps" | "manual"
    var lat: Double,
    var lng: Double,
    var tienePos: Boolean,
    val geocercas: MutableSet<String> // ids de las geocercas asignadas
) {
    val esManual: Boolean get() = modo == "manual"
}

class MainActivity : AppCompatActivity() {

    // Vistas
    private lateinit var mapView: MapView
    private lateinit var latitudeTextView: TextView
    private lateinit var longitudeTextView: TextView
    private lateinit var geofenceStatusTextView: TextView
    private lateinit var pointersStatusTextView: TextView
    private lateinit var modeTextView: TextView
    private lateinit var drawHintTextView: TextView
    private lateinit var drawPanel: View
    private lateinit var btnMode: Button
    private lateinit var locationManager: LocationManager

    // Firebase (null si no se pudo inicializar: la app sigue funcionando sin subir datos)
    private var database: DatabaseReference? = null
    private var geocercasListener: ValueEventListener? = null
    private var punterosListener: ValueEventListener? = null
    private var primeraSincronizacion = true
    private var primeraSincPunteros = true

    private val locationPermissionRequestCode = 1
    private val locationUpdateInterval = 1000L

    // Estado general
    private var isFirstLocationUpdate = true
    private var locationListener: LocationListener? = null
    private var mapReady = false
    private var mapLoading = false
    private var gpsDialog: AlertDialog? = null

    // Última ubicación real del teléfono (la usan los punteros en modo GPS)
    private var ultimaGps: Point? = null

    // Punteros
    private val paleta = listOf(
        "#E53935", "#8E24AA", "#FB8C00", "#6D4C41", "#D81B60", "#F9A825", "#546E7A", "#AD1457"
    )
    private val punteros = mutableListOf<Puntero>()
    private var seleccionadoId: String? = null
    private var pointerCircleManager: CircleAnnotationManager? = null
    private var pointerLabelManager: PointAnnotationManager? = null

    // Geocercas
    private val geocercas = mutableListOf<Geocerca>()
    // id de puntero -> ids de las geocercas donde ese puntero está dentro
    private val dentroDe = mutableMapOf<String, MutableSet<String>>()
    private var polygonManager: PolygonAnnotationManager? = null
    private var fenceLineManager: PolylineAnnotationManager? = null
    private var labelManager: PointAnnotationManager? = null

    // Dibujo de zonas
    private var drawing = false
    private val drawPoints = mutableListOf<Point>()
    private var drawLineManager: PolylineAnnotationManager? = null
    private var drawDotManager: CircleAnnotationManager? = null

    // ---------------------------------------------------------------- ciclo de vida

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.mapbox.common.MapboxOptions.accessToken = getString(R.string.mapbox_access_token)

        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        mapView = findViewById(R.id.mapView)
        latitudeTextView = findViewById(R.id.latitudeTextView)
        longitudeTextView = findViewById(R.id.longitudeTextView)
        geofenceStatusTextView = findViewById(R.id.geofenceStatusTextView)
        pointersStatusTextView = findViewById(R.id.pointersStatusTextView)
        btnMode = findViewById(R.id.btnMode)
        modeTextView = findViewById(R.id.modeTextView)
        drawHintTextView = findViewById(R.id.drawHintTextView)
        drawPanel = findViewById(R.id.drawPanel)
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager

        // Evita que los paneles queden debajo de las barras del sistema (edge-to-edge)
        val infoPanel = findViewById<View>(R.id.infoPanel)
        ViewCompat.setOnApplyWindowInsetsListener(infoPanel) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, bars.bottom)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(drawPanel) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, bars.bottom)
            insets
        }
        val titlePanel = findViewById<View>(R.id.titlePanel)
        ViewCompat.setOnApplyWindowInsetsListener(titlePanel) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, bars.top, view.paddingRight, view.paddingBottom)
            insets
        }
        val controlsPanel = findViewById<View>(R.id.controlsPanel)
        ViewCompat.setOnApplyWindowInsetsListener(controlsPanel) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, bars.top, view.paddingRight, view.paddingBottom)
            insets
        }

        initializeFirebase()
        cargarGeocercas()
        cargarPunteros()
        actualizarUI()
        escucharGeocercas()
        escucharPunteros()
        initializeLocationListener()
        setupButtons()
        setupMapGestures()
        checkLocationPermissions()
    }

    override fun onResume() {
        super.onResume()
        // Cubre el primer arranque y el regreso desde Configuración (GPS activado manualmente)
        if (hasLocationPermission()) {
            checkGpsStatus()
        } else if (!hayPunteroGps()) {
            initializeMap()
        }
    }

    override fun onPause() {
        super.onPause()
        stopLocationUpdates()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLocationUpdates()
        val l = geocercasListener
        if (l != null) {
            database?.child("geocercas")?.removeEventListener(l)
        }
        geocercasListener = null
        val pl = punterosListener
        if (pl != null) {
            database?.child("punteros")?.removeEventListener(pl)
        }
        punterosListener = null
        locationListener = null
        gpsDialog?.dismiss()
        gpsDialog = null
    }

    // ---------------------------------------------------------------- Firebase

    private fun initializeFirebase() {
        try {
            val url = getString(R.string.firebase_database_url).trim()
            val instance = if (url.isNotEmpty()) {
                FirebaseDatabase.getInstance(url)
            } else {
                FirebaseDatabase.getInstance()
            }
            database = instance.reference
        } catch (e: Exception) {
            Log.e("Geocorez", "Firebase no se pudo inicializar: ${e.message}", e)
            toast("Firebase no configurado: no se enviará la ubicación")
        }
    }

    private fun nombresDentro(p: Puntero): String {
        val set = dentroDe[p.id]
        return geocercas
            .filter { set?.contains(it.id) == true }
            .joinToString(", ") { it.nombre }
    }

    /** Envía solo la posición de un puntero (no pisa nombre, modo ni geocercas asignadas). */
    private fun enviarUbicacion(p: Puntero) {
        val data = mapOf(
            "latitude" to p.lat,
            "longitude" to p.lng,
            "geocerca" to nombresDentro(p),
            "timestamp" to System.currentTimeMillis()
        )
        database?.child("punteros")?.child(p.id)?.updateChildren(data)
    }

    private fun actualizarGeocercaRemota(p: Puntero) {
        database?.child("punteros")?.child(p.id)
            ?.updateChildren(mapOf("geocerca" to nombresDentro(p)))
    }

    /** Sube la configuración completa de un puntero (nombre, color, modo, posición, geocercas). */
    private fun subirPuntero(p: Puntero) {
        val ref = database?.child("punteros")?.child(p.id) ?: return
        val m = HashMap<String, Any?>()
        m["nombre"] = p.nombre
        m["color"] = p.color
        m["modo"] = p.modo
        if (p.tienePos) {
            m["latitude"] = p.lat
            m["longitude"] = p.lng
            m["timestamp"] = System.currentTimeMillis()
        }
        val gm = HashMap<String, Any>()
        for (id in p.geocercas) gm[id] = true
        m["geocercas"] = if (gm.isEmpty()) null else gm
        ref.updateChildren(m)
    }

    /** Sube solo la lista de geocercas asignadas a un puntero. */
    private fun subirAsignaciones(p: Puntero) {
        val ref = database?.child("punteros")?.child(p.id)?.child("geocercas") ?: return
        if (p.geocercas.isEmpty()) {
            ref.removeValue()
        } else {
            val gm = HashMap<String, Any>()
            for (id in p.geocercas) gm[id] = true
            ref.setValue(gm)
        }
    }

    private fun borrarPunteroRemoto(id: String) {
        database?.child("punteros")?.child(id)?.removeValue()
    }

    /** Convierte una geocerca al formato que se guarda en Firebase (lo mismo que lee la página web). */
    private fun aMapa(g: Geocerca): Map<String, Any> {
        val m = HashMap<String, Any>()
        m["nombre"] = g.nombre
        m["tipo"] = g.tipo
        m["latitude"] = g.lat
        m["longitude"] = g.lng
        m["radio"] = g.radio
        if (g.esPoligono) {
            m["puntos"] = g.puntos.map {
                mapOf("latitude" to it.latitude(), "longitude" to it.longitude())
            }
        }
        return m
    }

    private fun subirGeocerca(g: Geocerca) {
        database?.child("geocercas")?.child(g.id)?.setValue(aMapa(g))
    }

    private fun borrarGeocercaRemota(id: String) {
        database?.child("geocercas")?.child(id)?.removeValue()
    }

    private fun numero(s: DataSnapshot, clave: String): Double? {
        return (s.child(clave).value as? Number)?.toDouble()
    }

    private fun parseGeocercas(snapshot: DataSnapshot): MutableList<Geocerca> {
        val lista = mutableListOf<Geocerca>()
        for (child in snapshot.children) {
            val id = child.key ?: continue
            val nombre = (child.child("nombre").value as? String) ?: "Geocerca"
            val tipo = (child.child("tipo").value as? String) ?: "circulo"
            if (tipo == "poligono") {
                val pts = mutableListOf<Point>()
                for (p in child.child("puntos").children) {
                    val la = numero(p, "latitude")
                    val lo = numero(p, "longitude")
                    if (la != null && lo != null) pts.add(Point.fromLngLat(lo, la))
                }
                if (pts.size < 3) continue
                val c = centroide(pts)
                lista.add(Geocerca(id, nombre, "poligono", c.first, c.second, 0.0, pts))
            } else {
                val la = numero(child, "latitude")
                val lo = numero(child, "longitude")
                val r = numero(child, "radio")
                if (la == null || lo == null || r == null) continue
                lista.add(Geocerca(id, nombre, "circulo", la, lo, r, mutableListOf()))
            }
        }
        return lista
    }

    private fun parsePunteros(snapshot: DataSnapshot): MutableList<Puntero> {
        val lista = mutableListOf<Puntero>()
        for (child in snapshot.children) {
            val id = child.key ?: continue
            // Sin "modo" es un resto sin configurar (por ejemplo una posición que llegó tarde): se ignora
            val modoRaw = (child.child("modo").value as? String) ?: continue
            val nombre = (child.child("nombre").value as? String) ?: "Puntero"
            val color = (child.child("color").value as? String) ?: colorPara(lista.size)
            val la = numero(child, "latitude")
            val lo = numero(child, "longitude")
            val asign = mutableSetOf<String>()
            for (a in child.child("geocercas").children) {
                val k = a.key
                if (k != null) asign.add(k)
            }
            lista.add(
                Puntero(
                    id, nombre, color,
                    if (modoRaw == "manual") "manual" else "gps",
                    la ?: 0.0, lo ?: 0.0, la != null && lo != null, asign
                )
            )
        }
        return lista
    }

    /** Las geocercas se comparten con la página web: cualquier cambio en Firebase llega aquí en vivo. */
    private fun escucharGeocercas() {
        val ref = database?.child("geocercas") ?: return
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val remotas = parseGeocercas(snapshot)
                if (primeraSincronizacion && remotas.isEmpty() && geocercas.isNotEmpty()) {
                    // Firebase está vacío pero este teléfono ya tenía geocercas: se suben, no se borran
                    primeraSincronizacion = false
                    for (g in geocercas) subirGeocerca(g)
                    return
                }
                primeraSincronizacion = false
                geocercas.clear()
                geocercas.addAll(remotas)
                commitCambios()
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("Geocorez", "No se pudieron leer las geocercas: ${error.message}")
            }
        }
        ref.addValueEventListener(listener)
        geocercasListener = listener
    }

    private fun firma(): String = punteros.joinToString(";") { p ->
        p.id + "|" + p.nombre + "|" + p.color + "|" + p.modo + "|" +
                p.geocercas.sorted().joinToString("+") +
                (if (p.esManual) "|${p.lat},${p.lng},${p.tienePos}" else "")
    }

    /**
     * Los punteros también se comparten con la página web. La posición de un puntero en modo GPS la manda
     * este teléfono (por eso no se toma de Firebase); la de un puntero manual sí, porque la web la puede mover.
     */
    private fun escucharPunteros() {
        val ref = database?.child("punteros") ?: return
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val remotos = parsePunteros(snapshot)
                if (primeraSincPunteros && remotos.isEmpty() && punteros.isNotEmpty()) {
                    // Firebase todavía no tiene punteros: se suben los de este teléfono
                    primeraSincPunteros = false
                    for (p in punteros) subirPuntero(p)
                    return
                }
                primeraSincPunteros = false
                val antes = firma()
                val nuevos = mutableListOf<Puntero>()
                val pasanAGps = mutableListOf<Puntero>()
                for (r in remotos) {
                    val local = punteros.firstOrNull { it.id == r.id }
                    if (local == null) {
                        if (r.esManual && r.tienePos) sembrar(r)
                        nuevos.add(r)
                    } else {
                        val eraManual = local.esManual
                        local.nombre = r.nombre
                        local.color = r.color
                        local.modo = r.modo
                        local.geocercas.clear()
                        local.geocercas.addAll(r.geocercas)
                        if (local.esManual && r.tienePos) {
                            local.lat = r.lat
                            local.lng = r.lng
                            local.tienePos = true
                        }
                        if (eraManual && !local.esManual) pasanAGps.add(local)
                        nuevos.add(local)
                    }
                }
                punteros.clear()
                punteros.addAll(nuevos)
                if (punteros.isEmpty()) {
                    crearPunteroPorDefecto()
                    for (p in punteros) subirPuntero(p)
                }
                if (punteros.none { it.id == seleccionadoId }) seleccionadoId = punteros.first().id
                if (firma() != antes) {
                    commitCambios()
                    val g = ultimaGps
                    if (g != null) {
                        for (p in pasanAGps) actualizarPosicion(p, g.latitude(), g.longitude())
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("Geocorez", "No se pudieron leer los punteros: ${error.message}")
            }
        }
        ref.addValueEventListener(listener)
        punterosListener = listener
    }

    // ---------------------------------------------------------------- punteros: lógica

    private fun colorPara(i: Int): String = paleta[i % paleta.size]

    private fun colorNuevo(): String =
        paleta.firstOrNull { c -> punteros.none { it.color == c } } ?: colorPara(punteros.size)

    private fun seleccionado(): Puntero? =
        punteros.firstOrNull { it.id == seleccionadoId } ?: punteros.firstOrNull()

    private fun hayPunteroGps(): Boolean = punteros.any { !it.esManual }

    /** Puntero inicial: en modo GPS y con todas las geocercas que ya existían (así nada cambia para quien ya usaba la app). */
    private fun crearPunteroPorDefecto() {
        val p = Puntero(
            nuevoId(), "Puntero ${punteros.size + 1}", colorNuevo(), "gps",
            0.0, 0.0, false, geocercas.map { it.id }.toMutableSet()
        )
        punteros.add(p)
        seleccionadoId = p.id
    }

    /** Punto único de entrada para cualquier posición nueva de un puntero (GPS o manual). */
    private fun actualizarPosicion(p: Puntero, lat: Double, lng: Double) {
        p.lat = lat
        p.lng = lng
        p.tienePos = true
        val cambio = evaluarPuntero(p)
        enviarUbicacion(p)
        if (cambio) dibujarGeocercas()
        dibujarPunteros()
        actualizarUI()
    }

    /** Llega una lectura del GPS real: la reciben todos los punteros que estén en modo GPS. */
    private fun recibirGps(lat: Double, lng: Double) {
        ultimaGps = Point.fromLngLat(lng, lat)
        for (p in punteros.filter { !it.esManual }) actualizarPosicion(p, lat, lng)
    }

    /** Cambia el modo de un puntero. Solo uno puede usar el GPS (el teléfono tiene un único GPS). */
    private fun cambiarModo(p: Puntero, nuevo: String) {
        if (p.modo == nuevo) return
        val ref = database?.child("punteros")
        p.modo = nuevo
        ref?.child(p.id)?.child("modo")?.setValue(nuevo)
        if (nuevo == "gps") {
            for (o in punteros) {
                if (o !== p && !o.esManual) {
                    o.modo = "manual"
                    ref?.child(o.id)?.child("modo")?.setValue("manual")
                }
            }
            isFirstLocationUpdate = true
            val g = ultimaGps
            if (g != null) actualizarPosicion(p, g.latitude(), g.longitude())
        }
        guardarLocal()
        actualizarPuck()
        dibujarPunteros()
        actualizarUI()
        if (nuevo == "gps" && hasLocationPermission()) checkGpsStatus()
    }

    @SuppressLint("MissingPermission")
    private fun actualizarPuck() {
        if (mapReady) {
            mapView.location.enabled = hayPunteroGps() && hasLocationPermission()
        }
    }

    private fun sembrar(p: Puntero) {
        // Marca silenciosamente dónde está ya el puntero (sin generar eventos de "entrada")
        if (!p.tienePos) return
        val set = dentroDe.getOrPut(p.id) { mutableSetOf() }
        set.clear()
        for (g in geocercas) {
            if (p.geocercas.contains(g.id) && estaDentro(g, p.lat, p.lng)) set.add(g.id)
        }
    }

    private fun actualizarUI() {
        val sel = seleccionado()
        if (sel == null) {
            latitudeTextView.text = "Latitud: 0.000000"
            longitudeTextView.text = "Longitud: 0.000000"
            modeTextView.text = "Sin punteros"
            geofenceStatusTextView.text = "Geocercas: ninguna"
            pointersStatusTextView.visibility = View.GONE
            return
        }
        if (sel.tienePos) {
            latitudeTextView.text = "Latitud: %.6f".format(sel.lat)
            longitudeTextView.text = "Longitud: %.6f".format(sel.lng)
        } else {
            latitudeTextView.text = "Latitud: 0.000000"
            longitudeTextView.text = "Longitud: 0.000000"
        }
        modeTextView.text = sel.nombre + " · " + (if (sel.esManual) "Manual" else "GPS")
        btnMode.text = if (sel.esManual) "Modo: MANUAL" else "Modo: GPS"

        val asignadas = geocercas.filter { sel.geocercas.contains(it.id) }
        val dentro = nombresDentro(sel)
        geofenceStatusTextView.text = when {
            geocercas.isEmpty() -> "Geocercas: ninguna"
            asignadas.isEmpty() -> "Sin geocercas asignadas"
            dentro.isEmpty() -> "Fuera de sus geocercas"
            else -> "Dentro de: $dentro"
        }

        if (punteros.size > 1) {
            pointersStatusTextView.visibility = View.VISIBLE
            pointersStatusTextView.text = punteros.joinToString("\n") { p ->
                val d = nombresDentro(p)
                "• ${p.nombre}: " + (if (d.isEmpty()) "fuera" else d)
            }
        } else {
            pointersStatusTextView.visibility = View.GONE
        }
    }

    // ---------------------------------------------------------------- ubicación

    private fun initializeLocationListener() {
        locationListener = LocationListener { location ->
            recibirGps(location.latitude, location.longitude)
            if (isFirstLocationUpdate && mapReady && hayPunteroGps()) {
                moverCamara(Point.fromLngLat(location.longitude, location.latitude), 14.0)
                isFirstLocationUpdate = false
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        val listener = locationListener ?: return
        stopLocationUpdates() // evita registrar el listener dos veces
        try {
            if (hasFinePermission() && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, locationUpdateInterval, 0f, listener
                )
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER, locationUpdateInterval, 0f, listener
                )
            }
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopLocationUpdates() {
        val listener = locationListener
        if (::locationManager.isInitialized && listener != null) {
            locationManager.removeUpdates(listener)
        }
    }

    private fun moverCamara(point: Point, zoom: Double) {
        val cameraOptions = CameraOptions.Builder()
            .center(point)
            .zoom(zoom)
            .build()
        mapView.getMapboxMap().setCamera(cameraOptions)
    }

    // ---------------------------------------------------------------- mapa

    @SuppressLint("MissingPermission")
    private fun initializeMap() {
        if (mapReady) {
            startLocationUpdates()
            return
        }
        if (mapLoading) return
        mapLoading = true

        mapView.getMapboxMap().loadStyleUri(Style.MAPBOX_STREETS) {
            mapLoading = false
            mapReady = true

            val locationComponentPlugin = mapView.location

            // Icono personalizado del puntero (con respaldo si no se puede decodificar)
            val desiredSizePx = (48 * resources.displayMetrics.density).toInt()
            val originalBitmap = BitmapFactory.decodeResource(resources, R.drawable.pointermap)
            if (originalBitmap != null) {
                val resizedBitmap = Bitmap.createScaledBitmap(
                    originalBitmap, desiredSizePx, desiredSizePx, true
                )
                val pointerImageHolder = ImageHolder.from(resizedBitmap)
                locationComponentPlugin.locationPuck = LocationPuck2D(
                    topImage = pointerImageHolder,
                    bearingImage = pointerImageHolder
                )
            }
            // El icono del GPS real se muestra mientras algún puntero esté en modo GPS
            locationComponentPlugin.enabled = hayPunteroGps() && hasLocationPermission()

            // Capas de anotaciones (el orden de creación es el orden de apilado)
            val annotationApi = mapView.annotations
            polygonManager = annotationApi.createPolygonAnnotationManager()
            fenceLineManager = annotationApi.createPolylineAnnotationManager()
            labelManager = annotationApi.createPointAnnotationManager()
            drawLineManager = annotationApi.createPolylineAnnotationManager()
            drawDotManager = annotationApi.createCircleAnnotationManager()
            pointerCircleManager = annotationApi.createCircleAnnotationManager()
            pointerLabelManager = annotationApi.createPointAnnotationManager()
            dibujarGeocercas()
            dibujarPunteros()

            // Última ubicación conocida (protegido: con permiso "aproximado" el GPS lanza excepción)
            try {
                var lastLocation: Location? = null
                if (hasFinePermission() && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    lastLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                }
                if (lastLocation == null && locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    lastLocation = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                }
                if (lastLocation != null) {
                    if (isFirstLocationUpdate && hayPunteroGps()) {
                        moverCamara(Point.fromLngLat(lastLocation.longitude, lastLocation.latitude), 14.0)
                        isFirstLocationUpdate = false
                    }
                    recibirGps(lastLocation.latitude, lastLocation.longitude)
                }
            } catch (e: SecurityException) {
                e.printStackTrace()
            }

            // Sin ningún puntero en GPS, la vista arranca sobre el puntero seleccionado
            if (!hayPunteroGps()) {
                val s = seleccionado()
                if (s != null && s.tienePos) moverCamara(Point.fromLngLat(s.lng, s.lat), 14.0)
            }

            startLocationUpdates()
        }
    }

    private fun setupMapGestures() {
        // Toque corto: agrega un punto al dibujar, o mueve el puntero seleccionado si está en modo manual
        mapView.gestures.addOnMapClickListener(object : OnMapClickListener {
            override fun onMapClick(point: Point): Boolean {
                if (drawing) {
                    agregarVertice(point)
                    return true
                }
                val sel = seleccionado()
                if (sel != null && sel.esManual) {
                    actualizarPosicion(sel, point.latitude(), point.longitude())
                    guardarLocal()
                    return true
                }
                return false
            }
        })
        // Toque largo: crea una geocerca circular en ese punto
        mapView.gestures.addOnMapLongClickListener(object : OnMapLongClickListener {
            override fun onMapLongClick(point: Point): Boolean {
                if (!mapReady || drawing) return false
                showGeocercaDialog(null, point)
                return true
            }
        })
    }

    private fun setupButtons() {
        findViewById<View>(R.id.btnCenter).setOnClickListener {
            val p = seleccionado()
            if (p != null && p.tienePos) moverCamara(Point.fromLngLat(p.lng, p.lat), 16.0)
            else toast("Aún no hay ubicación")
        }
        btnMode.setOnClickListener { alternarModo() }
        findViewById<View>(R.id.btnPointers).setOnClickListener { showListaPunteros() }
        findViewById<View>(R.id.btnAddGeofence).setOnClickListener {
            val p = seleccionado()
            if (p != null && p.tienePos) {
                showGeocercaDialog(null, Point.fromLngLat(p.lng, p.lat))
            } else {
                toast("Aún no hay ubicación. También puedes mantener presionado el mapa.")
            }
        }
        findViewById<View>(R.id.btnGeofences).setOnClickListener { showListaGeocercas() }
        findViewById<View>(R.id.btnDraw).setOnClickListener { iniciarDibujo() }
        findViewById<View>(R.id.btnDrawUndo).setOnClickListener { deshacerVertice() }
        findViewById<View>(R.id.btnDrawFinish).setOnClickListener { terminarDibujo() }
        findViewById<View>(R.id.btnDrawCancel).setOnClickListener { cancelarDibujo() }
    }

    // ---------------------------------------------------------------- modo del puntero seleccionado

    private fun alternarModo() {
        if (!mapReady) {
            toast("El mapa aún se está cargando")
            return
        }
        val p = seleccionado() ?: return
        if (!p.esManual) {
            cambiarModo(p, "manual")
            toast("Toca el mapa para mover a ${p.nombre}")
        } else {
            cambiarModo(p, "gps")
        }
    }

    // ---------------------------------------------------------------- geocercas: lógica

    private fun centroide(pts: List<Point>): Pair<Double, Double> {
        var la = 0.0
        var lo = 0.0
        for (p in pts) {
            la += p.latitude()
            lo += p.longitude()
        }
        return Pair(la / pts.size, lo / pts.size)
    }

    /** Algoritmo de "rayo": cuenta cuántas veces un rayo desde el punto cruza los bordes. */
    private fun puntoEnPoligono(lat: Double, lng: Double, pts: List<Point>): Boolean {
        var dentro = false
        var j = pts.size - 1
        for (i in pts.indices) {
            val yi = pts[i].latitude()
            val xi = pts[i].longitude()
            val yj = pts[j].latitude()
            val xj = pts[j].longitude()
            val cruza = (yi > lat) != (yj > lat) &&
                    lng < (xj - xi) * (lat - yi) / (yj - yi) + xi
            if (cruza) dentro = !dentro
            j = i
        }
        return dentro
    }

    private fun estaDentro(g: Geocerca, lat: Double, lng: Double): Boolean {
        if (g.esPoligono) return puntoEnPoligono(lat, lng, g.puntos)
        val result = FloatArray(1)
        Location.distanceBetween(lat, lng, g.lat, g.lng, result)
        return result[0] <= g.radio
    }

    /**
     * Un punto que de verdad esté dentro de la geocerca. En un círculo es el centro; en una zona libre es
     * el centro aproximado, y si ese centro cae fuera (zona con forma cóncava) se busca el punto interior más cercano.
     */
    private fun puntoInterior(g: Geocerca): Point {
        if (!g.esPoligono) return Point.fromLngLat(g.lng, g.lat)
        if (puntoEnPoligono(g.lat, g.lng, g.puntos)) return Point.fromLngLat(g.lng, g.lat)
        var minLa = Double.MAX_VALUE
        var maxLa = -Double.MAX_VALUE
        var minLo = Double.MAX_VALUE
        var maxLo = -Double.MAX_VALUE
        for (p in g.puntos) {
            if (p.latitude() < minLa) minLa = p.latitude()
            if (p.latitude() > maxLa) maxLa = p.latitude()
            if (p.longitude() < minLo) minLo = p.longitude()
            if (p.longitude() > maxLo) maxLo = p.longitude()
        }
        val n = 30
        var mejor: Point? = null
        var mejorD = Double.MAX_VALUE
        for (i in 0..n) {
            for (j in 0..n) {
                val la = minLa + (maxLa - minLa) * i / n
                val lo = minLo + (maxLo - minLo) * j / n
                if (puntoEnPoligono(la, lo, g.puntos)) {
                    val d = (la - g.lat) * (la - g.lat) + (lo - g.lng) * (lo - g.lng)
                    if (d < mejorD) {
                        mejorD = d
                        mejor = Point.fromLngLat(lo, la)
                    }
                }
            }
        }
        return mejor ?: g.puntos.first()
    }

    /**
     * Evalúa solo las geocercas asignadas a este puntero. Devuelve true si entró o salió de alguna.
     */
    private fun evaluarPuntero(p: Puntero): Boolean {
        if (!p.tienePos) return false
        val set = dentroDe.getOrPut(p.id) { mutableSetOf() }
        var cambio = false
        val validas = geocercas.filter { p.geocercas.contains(it.id) }
        // Geocercas que ya no existen o que se le quitaron al puntero
        if (set.retainAll(validas.map { it.id }.toSet())) cambio = true
        for (g in validas) {
            val dentro = estaDentro(g, p.lat, p.lng)
            val estabaDentro = set.contains(g.id)
            if (dentro && !estabaDentro) {
                set.add(g.id)
                cambio = true
                registrarEvento(p, g, true)
            } else if (!dentro && estabaDentro) {
                set.remove(g.id)
                cambio = true
                registrarEvento(p, g, false)
            }
        }
        return cambio
    }

    private fun evaluarTodos() {
        dentroDe.keys.retainAll(punteros.map { it.id }.toSet())
        for (p in punteros) {
            if (evaluarPuntero(p)) actualizarGeocercaRemota(p)
        }
    }

    private fun registrarEvento(p: Puntero, g: Geocerca, entrada: Boolean) {
        toast(
            if (entrada) "${p.nombre} entró a \"${g.nombre}\""
            else "${p.nombre} salió de \"${g.nombre}\""
        )
        val evento = mapOf(
            "puntero" to p.nombre,
            "geocerca" to g.nombre,
            "evento" to (if (entrada) "entrada" else "salida"),
            "timestamp" to System.currentTimeMillis()
        )
        database?.child("eventos")?.push()?.setValue(evento)
    }

    private fun guardarLocal() {
        val arr = JSONArray()
        for (g in geocercas) {
            val o = JSONObject()
            o.put("id", g.id)
            o.put("nombre", g.nombre)
            o.put("tipo", g.tipo)
            o.put("lat", g.lat)
            o.put("lng", g.lng)
            o.put("radio", g.radio)
            if (g.esPoligono) {
                val pa = JSONArray()
                for (p in g.puntos) {
                    val po = JSONObject()
                    po.put("lat", p.latitude())
                    po.put("lng", p.longitude())
                    pa.put(po)
                }
                o.put("puntos", pa)
            }
            arr.put(o)
        }
        val parr = JSONArray()
        for (p in punteros) {
            val o = JSONObject()
            o.put("id", p.id)
            o.put("nombre", p.nombre)
            o.put("color", p.color)
            o.put("modo", p.modo)
            o.put("lat", p.lat)
            o.put("lng", p.lng)
            o.put("tienePos", p.tienePos)
            val ga = JSONArray()
            for (id in p.geocercas) ga.put(id)
            o.put("geocercas", ga)
            parr.put(o)
        }
        getSharedPreferences("geocorez_prefs", MODE_PRIVATE)
            .edit()
            .putString("geocercas", arr.toString())
            .putString("punteros", parr.toString())
            .putString("seleccionado", seleccionadoId)
            .apply()
    }

    private fun cargarGeocercas() {
        val json = getSharedPreferences("geocorez_prefs", MODE_PRIVATE)
            .getString("geocercas", null) ?: return
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val tipo = o.optString("tipo", "circulo")
                val pts = mutableListOf<Point>()
                val pa = o.optJSONArray("puntos")
                if (pa != null) {
                    for (k in 0 until pa.length()) {
                        val po = pa.getJSONObject(k)
                        pts.add(Point.fromLngLat(po.getDouble("lng"), po.getDouble("lat")))
                    }
                }
                if (tipo == "poligono" && pts.size < 3) continue
                geocercas.add(
                    Geocerca(
                        o.getString("id"),
                        o.getString("nombre"),
                        tipo,
                        o.getDouble("lat"),
                        o.getDouble("lng"),
                        o.getDouble("radio"),
                        pts
                    )
                )
            }
        } catch (e: Exception) {
            Log.e("Geocorez", "No se pudieron leer las geocercas guardadas", e)
        }
    }

    private fun cargarPunteros() {
        val prefs = getSharedPreferences("geocorez_prefs", MODE_PRIVATE)
        val json = prefs.getString("punteros", null)
        if (json != null) {
            try {
                val arr = JSONArray(json)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val manual = o.optString("modo", "gps") == "manual"
                    val asign = mutableSetOf<String>()
                    val ga = o.optJSONArray("geocercas")
                    if (ga != null) {
                        for (k in 0 until ga.length()) asign.add(ga.getString(k))
                    }
                    punteros.add(
                        Puntero(
                            o.getString("id"),
                            o.optString("nombre", "Puntero"),
                            o.optString("color", colorPara(i)),
                            if (manual) "manual" else "gps",
                            o.optDouble("lat", 0.0),
                            o.optDouble("lng", 0.0),
                            manual && o.optBoolean("tienePos", false), // la posición GPS guardada ya no sirve
                            asign
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e("Geocorez", "No se pudieron leer los punteros guardados", e)
            }
        }
        if (punteros.isEmpty()) crearPunteroPorDefecto()
        seleccionadoId = prefs.getString("seleccionado", null)
        if (punteros.none { it.id == seleccionadoId }) seleccionadoId = punteros.first().id
        for (p in punteros) {
            if (p.esManual && p.tienePos) sembrar(p)
        }
    }

    /** Guarda en el teléfono, vuelve a evaluar dónde está cada puntero y redibuja. */
    private fun commitCambios() {
        guardarLocal()
        evaluarTodos()
        actualizarPuck()
        dibujarGeocercas()
        dibujarPunteros()
        actualizarUI()
    }

    private fun nuevoId(): String = UUID.randomUUID().toString().substring(0, 8)

    // ---------------------------------------------------------------- dibujo en el mapa

    private fun dibujarGeocercas() {
        val pm = polygonManager ?: return
        val lm = labelManager ?: return
        val fm = fenceLineManager ?: return
        pm.deleteAll()
        lm.deleteAll()
        fm.deleteAll()
        for (g in geocercas) {
            // Verde si cualquier puntero asignado está dentro
            val algunoDentro = dentroDe.values.any { it.contains(g.id) }
            val color = if (algunoDentro) "#35B82A" else "#1E9BE8"
            val anillo: List<Point> = if (g.esPoligono) {
                g.puntos + g.puntos.first()
            } else {
                puntosCirculo(g.lat, g.lng, g.radio)
            }
            pm.create(
                PolygonAnnotationOptions()
                    .withPoints(listOf(anillo))
                    .withFillColor(color)
                    .withFillOpacity(0.25)
            )
            fm.create(
                PolylineAnnotationOptions()
                    .withPoints(anillo)
                    .withLineColor(color)
                    .withLineWidth(3.0)
            )
            val etiqueta = if (g.esPoligono) g.nombre else "${g.nombre} (${g.radio.toInt()} m)"
            lm.create(
                PointAnnotationOptions()
                    .withPoint(Point.fromLngLat(g.lng, g.lat))
                    .withTextField(etiqueta)
                    .withTextSize(13.0)
                    .withTextColor(color)
                    .withTextHaloColor("#FFFFFF")
                    .withTextHaloWidth(2.0)
            )
        }
    }

    /**
     * Dibuja los punteros. Los manuales son un círculo del color del puntero; el que está en modo GPS
     * usa el icono personalizado del GPS real y aquí solo lleva su nombre. El seleccionado se ve más grande y con "»".
     */
    private fun dibujarPunteros() {
        val cm = pointerCircleManager ?: return
        val tm = pointerLabelManager ?: return
        cm.deleteAll()
        tm.deleteAll()
        val selId = seleccionado()?.id
        for (p in punteros) {
            if (!p.tienePos) continue
            val pt = Point.fromLngLat(p.lng, p.lat)
            val esSel = p.id == selId
            if (p.esManual) {
                cm.create(
                    CircleAnnotationOptions()
                        .withPoint(pt)
                        .withCircleRadius(if (esSel) 11.0 else 8.0)
                        .withCircleColor(p.color)
                        .withCircleStrokeWidth(3.0)
                        .withCircleStrokeColor("#FFFFFF")
                )
            }
            tm.create(
                PointAnnotationOptions()
                    .withPoint(pt)
                    .withTextField((if (esSel) "» " else "") + p.nombre)
                    .withTextSize(12.0)
                    .withTextColor(p.color)
                    .withTextHaloColor("#FFFFFF")
                    .withTextHaloWidth(2.0)
                    .withTextOffset(listOf(0.0, if (p.esManual) -1.8 else -3.2))
            )
        }
    }

    /** Aproxima un círculo de [radioM] metros con un polígono de 64 lados (anillo cerrado). */
    private fun puntosCirculo(lat: Double, lng: Double, radioM: Double): List<Point> {
        val earthR = 6371000.0
        val d = radioM / earthR
        val latR = Math.toRadians(lat)
        val lngR = Math.toRadians(lng)
        val puntos = ArrayList<Point>()
        val pasos = 64
        for (i in 0..pasos) {
            val bearing = 2.0 * Math.PI * i / pasos
            val lat2 = asin(sin(latR) * cos(d) + cos(latR) * sin(d) * cos(bearing))
            val lng2 = lngR + atan2(
                sin(bearing) * sin(d) * cos(latR),
                cos(d) - sin(latR) * sin(lat2)
            )
            puntos.add(Point.fromLngLat(Math.toDegrees(lng2), Math.toDegrees(lat2)))
        }
        return puntos
    }

    // ---------------------------------------------------------------- dibujar una zona de forma libre

    private fun iniciarDibujo() {
        if (!mapReady) {
            toast("El mapa aún se está cargando")
            return
        }
        drawing = true
        drawPoints.clear()
        drawPanel.visibility = View.VISIBLE
        refrescarDibujo()
        toast("Toca el mapa para marcar los puntos de la zona")
    }

    private fun agregarVertice(p: Point) {
        drawPoints.add(p)
        refrescarDibujo()
    }

    private fun deshacerVertice() {
        if (drawPoints.isNotEmpty()) {
            drawPoints.removeAt(drawPoints.size - 1)
            refrescarDibujo()
        }
    }

    private fun terminarDibujo() {
        if (drawPoints.size < 3) {
            toast("Marca al menos 3 puntos")
            return
        }
        showGeocercaDialog(null, null, ArrayList(drawPoints))
    }

    private fun cancelarDibujo() {
        finalizarDibujo()
    }

    private fun finalizarDibujo() {
        drawing = false
        drawPoints.clear()
        drawPanel.visibility = View.GONE
        drawDotManager?.deleteAll()
        drawLineManager?.deleteAll()
    }

    private fun refrescarDibujo() {
        val dm = drawDotManager
        val lm = drawLineManager
        dm?.deleteAll()
        lm?.deleteAll()
        for (p in drawPoints) {
            dm?.create(
                CircleAnnotationOptions()
                    .withPoint(p)
                    .withCircleRadius(6.0)
                    .withCircleColor("#FF9800")
                    .withCircleStrokeWidth(2.0)
                    .withCircleStrokeColor("#FFFFFF")
            )
        }
        if (drawPoints.size >= 2) {
            val linea: List<Point> = if (drawPoints.size >= 3) {
                drawPoints + drawPoints.first()
            } else {
                drawPoints.toList()
            }
            lm?.create(
                PolylineAnnotationOptions()
                    .withPoints(linea)
                    .withLineColor("#FF9800")
                    .withLineWidth(3.0)
            )
        }
        drawHintTextView.text = "Puntos: ${drawPoints.size} (mínimo 3)"
    }

    // ---------------------------------------------------------------- geocercas: diálogos

    /**
     * Crea o edita una geocerca.
     *  - existing != null: edita (círculo: nombre y radio; polígono: solo nombre).
     *  - puntosNuevos != null: guarda una zona dibujada.
     *  - point != null: crea un círculo en ese punto.
     * En todos los casos se eligen qué punteros la evalúan (relación muchos a muchos).
     */
    private fun showGeocercaDialog(existing: Geocerca?, point: Point?, puntosNuevos: List<Point>? = null) {
        val esPoligono = (existing != null && existing.esPoligono) || puntosNuevos != null
        val pad = (24 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        container.setPadding(pad, pad / 2, pad, 0)

        val nameInput = EditText(this)
        nameInput.hint = "Nombre de la geocerca"
        nameInput.inputType = InputType.TYPE_CLASS_TEXT
        nameInput.setText(existing?.nombre ?: "Geocerca ${geocercas.size + 1}")
        container.addView(nameInput)

        val radiusInput = EditText(this)
        radiusInput.hint = "Radio en metros"
        radiusInput.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        radiusInput.setText((existing?.radio ?: 100.0).toInt().toString())
        if (!esPoligono) container.addView(radiusInput)

        // Punteros que evalúan esta geocerca
        val checks = ArrayList<Pair<Puntero, CheckBox>>()
        if (punteros.isNotEmpty()) {
            val tv = TextView(this)
            tv.text = "Punteros que la evalúan:"
            tv.setPadding(0, pad / 2, 0, 0)
            container.addView(tv)
            val selId = seleccionado()?.id
            for (pt in punteros) {
                val cb = CheckBox(this)
                cb.text = pt.nombre
                cb.isChecked = if (existing != null) pt.geocercas.contains(existing.id) else pt.id == selId
                container.addView(cb)
                checks.add(Pair(pt, cb))
            }
        }
        val scroll = ScrollView(this)
        scroll.addView(container)

        val titulo = when {
            existing != null -> "Editar geocerca"
            puntosNuevos != null -> "Nueva zona"
            else -> "Nueva geocerca"
        }

        AlertDialog.Builder(this, R.style.GeocorezDialog)
            .setTitle(titulo)
            .setView(scroll)
            .setPositiveButton("Guardar") { _, _ ->
                val nombre = nameInput.text.toString().trim()
                    .ifEmpty { "Geocerca ${geocercas.size + 1}" }
                val radio = (radiusInput.text.toString().toDoubleOrNull() ?: 100.0)
                    .coerceIn(10.0, 100000.0)
                var destino: Geocerca? = null
                if (existing != null) {
                    existing.nombre = nombre
                    if (!existing.esPoligono) existing.radio = radio
                    subirGeocerca(existing)
                    destino = existing
                } else if (puntosNuevos != null) {
                    val c = centroide(puntosNuevos)
                    val g = Geocerca(
                        nuevoId(), nombre, "poligono", c.first, c.second, 0.0, ArrayList(puntosNuevos)
                    )
                    geocercas.add(g)
                    subirGeocerca(g)
                    finalizarDibujo()
                    destino = g
                } else if (point != null) {
                    val g = Geocerca(
                        nuevoId(), nombre, "circulo", point.latitude(), point.longitude(), radio, mutableListOf()
                    )
                    geocercas.add(g)
                    subirGeocerca(g)
                    destino = g
                }
                if (destino != null) {
                    for ((pt, cb) in checks) {
                        val tenia = pt.geocercas.contains(destino.id)
                        if (cb.isChecked && !tenia) {
                            pt.geocercas.add(destino.id)
                            subirAsignaciones(pt)
                        } else if (!cb.isChecked && tenia) {
                            pt.geocercas.remove(destino.id)
                            subirAsignaciones(pt)
                        }
                    }
                }
                commitCambios()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showListaGeocercas() {
        if (geocercas.isEmpty()) {
            toast("No hay geocercas. Usa \"+ Círculo\", \"Dibujar zona\" o mantén presionado el mapa.")
            return
        }
        val items = geocercas.map { g ->
            val estado = if (dentroDe.values.any { s -> s.contains(g.id) }) "  (dentro)" else ""
            val asignados = punteros.filter { p -> p.geocercas.contains(g.id) }
            val quienes = if (asignados.isEmpty()) "sin punteros" else asignados.joinToString(", ") { p -> p.nombre }
            val detalle = if (g.esPoligono) {
                "${g.nombre} - zona libre (${g.puntos.size} puntos)$estado"
            } else {
                "${g.nombre} - ${g.radio.toInt()} m$estado"
            }
            "$detalle\n→ $quienes"
        }.toTypedArray()

        AlertDialog.Builder(this, R.style.GeocorezDialog)
            .setTitle("Geocercas")
            .setItems(items) { _, which -> showAccionesGeocerca(geocercas[which]) }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun showAccionesGeocerca(g: Geocerca) {
        val opciones = if (g.esPoligono) {
            arrayOf("Ir a la geocerca", "Renombrar / punteros", "Eliminar")
        } else {
            arrayOf(
                "Ir a la geocerca", "Editar nombre, radio y punteros",
                "Mover a la posición del puntero", "Eliminar"
            )
        }
        AlertDialog.Builder(this, R.style.GeocorezDialog)
            .setTitle(g.nombre)
            .setItems(opciones) { _, which ->
                when (opciones[which]) {
                    "Ir a la geocerca" -> irAGeocerca(g)
                    "Renombrar / punteros", "Editar nombre, radio y punteros" -> showGeocercaDialog(g, null)
                    "Mover a la posición del puntero" -> {
                        val p = seleccionado()
                        if (p != null && p.tienePos) {
                            g.lat = p.lat
                            g.lng = p.lng
                            subirGeocerca(g)
                            commitCambios()
                        } else {
                            toast("Aún no hay ubicación")
                        }
                    }
                    "Eliminar" -> confirmarEliminar(g)
                }
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    /** Puntero que debe ir a la geocerca: el seleccionado si la tiene asignada, si no el primero que la tenga. */
    private fun punteroParaIr(g: Geocerca): Puntero? {
        val sel = seleccionado()
        if (sel != null && sel.geocercas.contains(g.id)) return sel
        return punteros.firstOrNull { it.geocercas.contains(g.id) } ?: sel
    }

    /**
     * "Ir a la geocerca": además de la cámara, el puntero también va a la geocerca.
     * El GPS real no se puede mover, así que si el puntero estaba en modo GPS pasa a modo manual.
     */
    private fun irAGeocerca(g: Geocerca) {
        val destino = puntoInterior(g)
        val p = punteroParaIr(g)
        if (p != null) {
            seleccionadoId = p.id
            if (!p.esManual) cambiarModo(p, "manual")
            actualizarPosicion(p, destino.latitude(), destino.longitude())
            guardarLocal()
            if (p.geocercas.contains(g.id)) {
                toast("${p.nombre} fue a \"${g.nombre}\"")
            } else {
                toast("${p.nombre} fue a \"${g.nombre}\" (no la tiene asignada)")
            }
        }
        moverCamara(destino, 16.0)
    }

    private fun confirmarEliminar(g: Geocerca) {
        AlertDialog.Builder(this, R.style.GeocorezDialog)
            .setTitle("Eliminar geocerca")
            .setMessage("¿Eliminar \"${g.nombre}\"?")
            .setPositiveButton("Eliminar") { _, _ ->
                geocercas.remove(g)
                for (s in dentroDe.values) s.remove(g.id)
                for (p in punteros) {
                    if (p.geocercas.remove(g.id)) {
                        database?.child("punteros")?.child(p.id)?.child("geocercas")?.child(g.id)?.removeValue()
                    }
                }
                borrarGeocercaRemota(g.id)
                commitCambios()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------------------------------------------------------------- punteros: diálogos

    /** Crea o edita un puntero: nombre y qué geocercas evalúa. */
    private fun showPunteroDialog(existing: Puntero?) {
        if (existing == null && !mapReady) {
            toast("El mapa aún se está cargando")
            return
        }
        val pad = (24 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        container.setPadding(pad, pad / 2, pad, 0)

        val nameInput = EditText(this)
        nameInput.hint = "Nombre del puntero"
        nameInput.inputType = InputType.TYPE_CLASS_TEXT
        nameInput.setText(existing?.nombre ?: "Puntero ${punteros.size + 1}")
        container.addView(nameInput)

        val checks = ArrayList<Pair<Geocerca, CheckBox>>()
        if (geocercas.isNotEmpty()) {
            val tv = TextView(this)
            tv.text = "Geocercas que evalúa este puntero:"
            tv.setPadding(0, pad / 2, 0, 0)
            container.addView(tv)
            for (g in geocercas) {
                val cb = CheckBox(this)
                cb.text = g.nombre
                cb.isChecked = existing?.geocercas?.contains(g.id) ?: false
                container.addView(cb)
                checks.add(Pair(g, cb))
            }
        }
        val scroll = ScrollView(this)
        scroll.addView(container)

        AlertDialog.Builder(this, R.style.GeocorezDialog)
            .setTitle(if (existing != null) "Editar puntero" else "Nuevo puntero")
            .setView(scroll)
            .setPositiveButton("Guardar") { _, _ ->
                val nombre = nameInput.text.toString().trim()
                    .ifEmpty { "Puntero ${punteros.size + 1}" }
                val elegidas = checks.filter { it.second.isChecked }.map { it.first.id }
                if (existing != null) {
                    existing.nombre = nombre
                    existing.geocercas.clear()
                    existing.geocercas.addAll(elegidas)
                    subirPuntero(existing)
                } else {
                    // El puntero nuevo aparece en el centro del mapa, en modo manual
                    val centro = mapView.getMapboxMap().cameraState.center
                    val p = Puntero(
                        nuevoId(), nombre, colorNuevo(), "manual",
                        centro.latitude(), centro.longitude(), true, elegidas.toMutableSet()
                    )
                    punteros.add(p)
                    seleccionadoId = p.id
                    subirPuntero(p)
                }
                commitCambios()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showListaPunteros() {
        val items = punteros.map { p ->
            val marca = if (p.id == seleccionado()?.id) "▶ " else ""
            val modo = if (p.esManual) "Manual" else "GPS"
            val n = p.geocercas.count { id -> geocercas.any { it.id == id } }
            val d = nombresDentro(p)
            val dentro = if (d.isEmpty()) "" else "\n   Dentro de: $d"
            "$marca${p.nombre} · $modo · $n geocerca(s)$dentro"
        }.toTypedArray()

        AlertDialog.Builder(this, R.style.GeocorezDialog)
            .setTitle("Punteros")
            .setItems(items) { _, which -> showAccionesPuntero(punteros[which]) }
            .setPositiveButton("+ Nuevo") { _, _ -> showPunteroDialog(null) }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun seleccionarPuntero(p: Puntero, centrar: Boolean) {
        seleccionadoId = p.id
        guardarLocal()
        dibujarPunteros()
        actualizarUI()
        if (centrar && p.tienePos && mapReady) moverCamara(Point.fromLngLat(p.lng, p.lat), 16.0)
    }

    private fun showAccionesPuntero(p: Puntero) {
        val esSel = p.id == seleccionado()?.id
        val opciones = mutableListOf<String>()
        if (!esSel) opciones.add("Seleccionar")
        opciones.add("Ir al puntero")
        opciones.add("Editar nombre y geocercas")
        opciones.add(if (p.esManual) "Cambiar a modo GPS" else "Cambiar a modo manual")
        if (punteros.size > 1) opciones.add("Eliminar")

        AlertDialog.Builder(this, R.style.GeocorezDialog)
            .setTitle(p.nombre)
            .setItems(opciones.toTypedArray()) { _, which ->
                when (opciones[which]) {
                    "Seleccionar" -> seleccionarPuntero(p, false)
                    "Ir al puntero" -> seleccionarPuntero(p, true)
                    "Editar nombre y geocercas" -> showPunteroDialog(p)
                    "Cambiar a modo GPS" -> {
                        seleccionarPuntero(p, false)
                        cambiarModo(p, "gps")
                    }
                    "Cambiar a modo manual" -> {
                        seleccionarPuntero(p, false)
                        cambiarModo(p, "manual")
                        toast("Toca el mapa para mover a ${p.nombre}")
                    }
                    "Eliminar" -> confirmarEliminarPuntero(p)
                }
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun confirmarEliminarPuntero(p: Puntero) {
        AlertDialog.Builder(this, R.style.GeocorezDialog)
            .setTitle("Eliminar puntero")
            .setMessage("¿Eliminar \"${p.nombre}\"? Las geocercas no se borran.")
            .setPositiveButton("Eliminar") { _, _ ->
                punteros.remove(p)
                dentroDe.remove(p.id)
                borrarPunteroRemoto(p.id)
                if (seleccionadoId == p.id) seleccionadoId = punteros.firstOrNull()?.id
                commitCambios()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------------------------------------------------------------- permisos y GPS

    private fun checkLocationPermissions() {
        if (!hasLocationPermission()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                locationPermissionRequestCode
            )
        }
    }

    private fun checkGpsStatus() {
        // El GPS solo es obligatorio mientras algún puntero lo use
        if (hayPunteroGps() && !locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            showGpsDisabledDialog()
        } else {
            gpsDialog?.dismiss()
            initializeMap()
        }
    }

    private fun showGpsDisabledDialog() {
        if (gpsDialog?.isShowing == true) return
        gpsDialog = AlertDialog.Builder(this, R.style.GeocorezDialog)
            .setTitle("GPS Desactivado")
            .setMessage("Para usar esta aplicación necesitas activar el GPS. ¿Deseas activarlo ahora?")
            .setPositiveButton("Activar GPS") { _, _ ->
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
            .setNegativeButton("Cancelar") { dialog, _ ->
                dialog.dismiss()
                toast("La aplicación necesita GPS para funcionar correctamente")
            }
            .setCancelable(false)
            .show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == locationPermissionRequestCode) {
            if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
                checkGpsStatus()
            } else {
                latitudeTextView.text = "Permisos de ubicación denegados"
                longitudeTextView.text = ""
            }
        }
    }

    private fun hasFinePermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasLocationPermission(): Boolean {
        return hasFinePermission() || ActivityCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}