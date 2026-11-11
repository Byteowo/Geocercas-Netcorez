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
import android.widget.EditText
import android.widget.LinearLayout
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

class MainActivity : AppCompatActivity() {

    // Vistas
    private lateinit var mapView: MapView
    private lateinit var latitudeTextView: TextView
    private lateinit var longitudeTextView: TextView
    private lateinit var geofenceStatusTextView: TextView
    private lateinit var modeTextView: TextView
    private lateinit var drawHintTextView: TextView
    private lateinit var drawPanel: View
    private lateinit var btnMode: Button
    private lateinit var locationManager: LocationManager

    // Firebase (null si no se pudo inicializar: la app sigue funcionando sin subir datos)
    private var database: DatabaseReference? = null
    private var geocercasListener: ValueEventListener? = null
    private var primeraSincronizacion = true

    private val locationPermissionRequestCode = 1
    private val locationUpdateInterval = 1000L

    // Estado general
    private var isFirstLocationUpdate = true
    private var locationListener: LocationListener? = null
    private var mapReady = false
    private var mapLoading = false
    private var gpsDialog: AlertDialog? = null

    // Modo de ubicación: GPS real o manual (tocando el mapa)
    private var manualMode = false
    private var currentPoint: Point? = null

    // Geocercas
    private val geocercas = mutableListOf<Geocerca>()
    private val dentroDe = mutableSetOf<String>() // ids de las geocercas donde estoy dentro
    private var polygonManager: PolygonAnnotationManager? = null
    private var fenceLineManager: PolylineAnnotationManager? = null
    private var labelManager: PointAnnotationManager? = null
    private var circleManager: CircleAnnotationManager? = null // marcador del modo manual

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
        actualizarEstadoGeocercas()
        escucharGeocercas()
        initializeLocationListener()
        setupButtons()
        setupMapGestures()
        checkLocationPermissions()
    }

    override fun onResume() {
        super.onResume()
        // Cubre el primer arranque y el regreso desde Configuración (GPS activado manualmente)
        if (hasLocationPermission() && !manualMode) {
            checkGpsStatus()
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

    private fun enviarUbicacion(lat: Double, lng: Double) {
        val dentroNombres = geocercas
            .filter { dentroDe.contains(it.id) }
            .joinToString(", ") { it.nombre }
        val data = mapOf(
            "latitude" to lat,
            "longitude" to lng,
            "modo" to (if (manualMode) "manual" else "gps"),
            "geocerca" to dentroNombres,
            "timestamp" to System.currentTimeMillis()
        )
        database?.child("usuario_activo")?.setValue(data)
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

    // ---------------------------------------------------------------- ubicación

    private fun initializeLocationListener() {
        locationListener = LocationListener { location ->
            if (manualMode) return@LocationListener
            onNewPosition(location.latitude, location.longitude)
            if (isFirstLocationUpdate && mapReady) {
                moverCamara(Point.fromLngLat(location.longitude, location.latitude), 14.0)
                isFirstLocationUpdate = false
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (manualMode) return
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

    /** Punto único de entrada para cualquier posición nueva (GPS o manual). */
    private fun onNewPosition(lat: Double, lng: Double) {
        currentPoint = Point.fromLngLat(lng, lat)
        latitudeTextView.text = "Latitud: %.6f".format(lat)
        longitudeTextView.text = "Longitud: %.6f".format(lng)
        evaluarGeocercas(lat, lng)
        enviarUbicacion(lat, lng)
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
            locationComponentPlugin.enabled = !manualMode

            // Capas de anotaciones (el orden de creación es el orden de apilado)
            val annotationApi = mapView.annotations
            polygonManager = annotationApi.createPolygonAnnotationManager()
            fenceLineManager = annotationApi.createPolylineAnnotationManager()
            labelManager = annotationApi.createPointAnnotationManager()
            drawLineManager = annotationApi.createPolylineAnnotationManager()
            drawDotManager = annotationApi.createCircleAnnotationManager()
            circleManager = annotationApi.createCircleAnnotationManager()
            dibujarGeocercas()

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
                    if (isFirstLocationUpdate) {
                        moverCamara(Point.fromLngLat(lastLocation.longitude, lastLocation.latitude), 14.0)
                        isFirstLocationUpdate = false
                    }
                    onNewPosition(lastLocation.latitude, lastLocation.longitude)
                }
            } catch (e: SecurityException) {
                e.printStackTrace()
            }

            startLocationUpdates()
        }
    }

    private fun setupMapGestures() {
        // Toque corto: agrega un punto al dibujar, o mueve mi ubicación en modo manual
        mapView.gestures.addOnMapClickListener(object : OnMapClickListener {
            override fun onMapClick(point: Point): Boolean {
                if (drawing) {
                    agregarVertice(point)
                    return true
                }
                if (manualMode) {
                    setManualPosition(point)
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
            val p = currentPoint
            if (p != null) moverCamara(p, 16.0) else toast("Aún no hay ubicación")
        }
        btnMode.setOnClickListener { alternarModo() }
        findViewById<View>(R.id.btnAddGeofence).setOnClickListener {
            val p = currentPoint
            if (p != null) {
                showGeocercaDialog(null, p)
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

    // ---------------------------------------------------------------- modo manual

    @SuppressLint("MissingPermission")
    private fun alternarModo() {
        if (!mapReady) {
            toast("El mapa aún se está cargando")
            return
        }
        if (!manualMode) {
            manualMode = true
            stopLocationUpdates()
            mapView.location.enabled = false
            btnMode.text = "Modo: MANUAL"
            modeTextView.text = "Ubicación manual"
            toast("Toca el mapa para mover tu ubicación")
            currentPoint?.let { setManualPosition(it) }
        } else {
            manualMode = false
            circleManager?.deleteAll()
            mapView.location.enabled = true
            btnMode.text = "Modo: GPS"
            modeTextView.text = "Ubicación por GPS"
            isFirstLocationUpdate = true
            if (hasLocationPermission()) checkGpsStatus()
        }
    }

    private fun setManualPosition(point: Point) {
        circleManager?.let { cm ->
            cm.deleteAll()
            cm.create(
                CircleAnnotationOptions()
                    .withPoint(point)
                    .withCircleRadius(9.0)
                    .withCircleColor("#FF9800")
                    .withCircleStrokeWidth(3.0)
                    .withCircleStrokeColor("#FFFFFF")
            )
        }
        onNewPosition(point.latitude(), point.longitude())
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

    private fun evaluarGeocercas(lat: Double, lng: Double) {
        dentroDe.retainAll(geocercas.map { it.id }.toSet())
        var cambio = false
        for (g in geocercas) {
            val dentro = estaDentro(g, lat, lng)
            val estabaDentro = dentroDe.contains(g.id)
            if (dentro && !estabaDentro) {
                dentroDe.add(g.id)
                cambio = true
                registrarEvento(g, true)
            } else if (!dentro && estabaDentro) {
                dentroDe.remove(g.id)
                cambio = true
                registrarEvento(g, false)
            }
        }
        actualizarEstadoGeocercas()
        if (cambio) dibujarGeocercas()
    }

    private fun registrarEvento(g: Geocerca, entrada: Boolean) {
        toast(if (entrada) "Entraste a \"${g.nombre}\"" else "Saliste de \"${g.nombre}\"")
        val evento = mapOf(
            "geocerca" to g.nombre,
            "evento" to (if (entrada) "entrada" else "salida"),
            "timestamp" to System.currentTimeMillis()
        )
        database?.child("eventos")?.push()?.setValue(evento)
    }

    private fun actualizarEstadoGeocercas() {
        geofenceStatusTextView.text = when {
            geocercas.isEmpty() -> "Geocercas: ninguna"
            dentroDe.isEmpty() -> "Fuera de las geocercas"
            else -> "Dentro de: " + geocercas
                .filter { dentroDe.contains(it.id) }
                .joinToString(", ") { it.nombre }
        }
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
        getSharedPreferences("geocorez_prefs", MODE_PRIVATE)
            .edit()
            .putString("geocercas", arr.toString())
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

    /** Guarda en el teléfono, vuelve a evaluar dónde estoy y redibuja. */
    private fun commitCambios() {
        guardarLocal()
        val p = currentPoint
        if (p != null) {
            evaluarGeocercas(p.latitude(), p.longitude())
        } else {
            actualizarEstadoGeocercas()
        }
        dibujarGeocercas()
    }

    private fun nuevoId(): String = UUID.randomUUID().toString().substring(0, 8)

    // ---------------------------------------------------------------- geocercas: dibujo en el mapa

    private fun dibujarGeocercas() {
        val pm = polygonManager ?: return
        val lm = labelManager ?: return
        val fm = fenceLineManager ?: return
        pm.deleteAll()
        lm.deleteAll()
        fm.deleteAll()
        for (g in geocercas) {
            val color = if (dentroDe.contains(g.id)) "#35B82A" else "#1E9BE8"
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

        val titulo = when {
            existing != null -> "Editar geocerca"
            puntosNuevos != null -> "Nueva zona"
            else -> "Nueva geocerca"
        }

        AlertDialog.Builder(this, R.style.GeocorezDialog)
            .setTitle(titulo)
            .setView(container)
            .setPositiveButton("Guardar") { _, _ ->
                val nombre = nameInput.text.toString().trim()
                    .ifEmpty { "Geocerca ${geocercas.size + 1}" }
                val radio = (radiusInput.text.toString().toDoubleOrNull() ?: 100.0)
                    .coerceIn(10.0, 100000.0)
                if (existing != null) {
                    existing.nombre = nombre
                    if (!existing.esPoligono) existing.radio = radio
                    subirGeocerca(existing)
                } else if (puntosNuevos != null) {
                    val c = centroide(puntosNuevos)
                    val g = Geocerca(
                        nuevoId(), nombre, "poligono", c.first, c.second, 0.0, ArrayList(puntosNuevos)
                    )
                    geocercas.add(g)
                    subirGeocerca(g)
                    finalizarDibujo()
                } else if (point != null) {
                    val g = Geocerca(
                        nuevoId(), nombre, "circulo", point.latitude(), point.longitude(), radio, mutableListOf()
                    )
                    geocercas.add(g)
                    subirGeocerca(g)
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
        val items = geocercas.map {
            val estado = if (dentroDe.contains(it.id)) "  (dentro)" else ""
            if (it.esPoligono) {
                "${it.nombre} - zona libre (${it.puntos.size} puntos)$estado"
            } else {
                "${it.nombre} - ${it.radio.toInt()} m$estado"
            }
        }.toTypedArray()

        AlertDialog.Builder(this, R.style.GeocorezDialog)
            .setTitle("Geocercas")
            .setItems(items) { _, which -> showAccionesGeocerca(geocercas[which]) }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun showAccionesGeocerca(g: Geocerca) {
        val opciones = if (g.esPoligono) {
            arrayOf("Ir a la geocerca", "Renombrar", "Eliminar")
        } else {
            arrayOf("Ir a la geocerca", "Editar nombre y radio", "Mover a mi ubicación actual", "Eliminar")
        }
        AlertDialog.Builder(this, R.style.GeocorezDialog)
            .setTitle(g.nombre)
            .setItems(opciones) { _, which ->
                when (opciones[which]) {
                    "Ir a la geocerca" -> moverCamara(Point.fromLngLat(g.lng, g.lat), 16.0)
                    "Renombrar", "Editar nombre y radio" -> showGeocercaDialog(g, null)
                    "Mover a mi ubicación actual" -> {
                        val p = currentPoint
                        if (p != null) {
                            g.lat = p.latitude()
                            g.lng = p.longitude()
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

    private fun confirmarEliminar(g: Geocerca) {
        AlertDialog.Builder(this, R.style.GeocorezDialog)
            .setTitle("Eliminar geocerca")
            .setMessage("¿Eliminar \"${g.nombre}\"?")
            .setPositiveButton("Eliminar") { _, _ ->
                geocercas.remove(g)
                dentroDe.remove(g.id)
                borrarGeocercaRemota(g.id)
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
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
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
