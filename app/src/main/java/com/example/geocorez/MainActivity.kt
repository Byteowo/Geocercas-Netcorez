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
import com.mapbox.maps.plugin.annotation.generated.createCircleAnnotationManager
import com.mapbox.maps.plugin.annotation.generated.createPointAnnotationManager
import com.mapbox.maps.plugin.annotation.generated.createPolygonAnnotationManager
import com.mapbox.maps.plugin.gestures.OnMapClickListener
import com.mapbox.maps.plugin.gestures.OnMapLongClickListener
import com.mapbox.maps.plugin.gestures.gestures
import com.mapbox.maps.plugin.locationcomponent.location
// Firebase
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
// Utilidades
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Una geocerca circular: centro (lat, lng) y radio en metros. */
data class Geocerca(
    val id: String,
    var nombre: String,
    var lat: Double,
    var lng: Double,
    var radio: Double
)

class MainActivity : AppCompatActivity() {

    // Vistas
    private lateinit var mapView: MapView
    private lateinit var latitudeTextView: TextView
    private lateinit var longitudeTextView: TextView
    private lateinit var geofenceStatusTextView: TextView
    private lateinit var btnMode: Button
    private lateinit var modeTextView: TextView
    private lateinit var locationManager: LocationManager

    // Firebase (null si no se pudo inicializar: la app sigue funcionando sin subir datos)
    private var database: DatabaseReference? = null

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
    private var labelManager: PointAnnotationManager? = null
    private var circleManager: CircleAnnotationManager? = null

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
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager

        // Evita que los paneles queden debajo de las barras del sistema (edge-to-edge)
        val infoPanel = findViewById<View>(R.id.infoPanel)
        ViewCompat.setOnApplyWindowInsetsListener(infoPanel) { view, insets ->
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
            sincronizarGeocercasFirebase()
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

    private fun sincronizarGeocercasFirebase() {
        val ref = database?.child("geocercas") ?: return
        if (geocercas.isEmpty()) {
            ref.removeValue()
            return
        }
        val map = HashMap<String, Any>()
        for (g in geocercas) {
            map[g.id] = mapOf(
                "nombre" to g.nombre,
                "latitude" to g.lat,
                "longitude" to g.lng,
                "radio" to g.radio
            )
        }
        ref.setValue(map)
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

            // Administradores de anotaciones: geocercas (polígonos + etiquetas) y marcador manual
            val annotationApi = mapView.annotations
            polygonManager = annotationApi.createPolygonAnnotationManager()
            labelManager = annotationApi.createPointAnnotationManager()
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
        // Toque corto: en modo manual mueve mi ubicación
        mapView.gestures.addOnMapClickListener(object : OnMapClickListener {
            override fun onMapClick(point: Point): Boolean {
                if (manualMode) {
                    setManualPosition(point)
                    return true
                }
                return false
            }
        })
        // Toque largo: crea una geocerca en ese punto
        mapView.gestures.addOnMapLongClickListener(object : OnMapLongClickListener {
            override fun onMapLongClick(point: Point): Boolean {
                if (!mapReady) return false
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

    private fun evaluarGeocercas(lat: Double, lng: Double) {
        dentroDe.retainAll(geocercas.map { it.id }.toSet())
        val result = FloatArray(1)
        var cambio = false
        for (g in geocercas) {
            Location.distanceBetween(lat, lng, g.lat, g.lng, result)
            val dentro = result[0] <= g.radio
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

    private fun guardarGeocercas() {
        val arr = JSONArray()
        for (g in geocercas) {
            val o = JSONObject()
            o.put("id", g.id)
            o.put("nombre", g.nombre)
            o.put("lat", g.lat)
            o.put("lng", g.lng)
            o.put("radio", g.radio)
            arr.put(o)
        }
        getSharedPreferences("geocorez_prefs", MODE_PRIVATE)
            .edit()
            .putString("geocercas", arr.toString())
            .apply()
        sincronizarGeocercasFirebase()
    }

    private fun cargarGeocercas() {
        val json = getSharedPreferences("geocorez_prefs", MODE_PRIVATE)
            .getString("geocercas", null) ?: return
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                geocercas.add(
                    Geocerca(
                        o.getString("id"),
                        o.getString("nombre"),
                        o.getDouble("lat"),
                        o.getDouble("lng"),
                        o.getDouble("radio")
                    )
                )
            }
        } catch (e: Exception) {
            Log.e("Geocorez", "No se pudieron leer las geocercas guardadas", e)
        }
    }

    private fun refrescarGeocercas() {
        guardarGeocercas()
        val p = currentPoint
        if (p != null) evaluarGeocercas(p.latitude(), p.longitude()) else actualizarEstadoGeocercas()
        dibujarGeocercas()
    }

    // ---------------------------------------------------------------- geocercas: dibujo

    private fun dibujarGeocercas() {
        val pm = polygonManager ?: return
        val lm = labelManager ?: return
        pm.deleteAll()
        lm.deleteAll()
        for (g in geocercas) {
            val color = if (dentroDe.contains(g.id)) "#35B82A" else "#1E9BE8"
            pm.create(
                PolygonAnnotationOptions()
                    .withPoints(listOf(puntosCirculo(g.lat, g.lng, g.radio)))
                    .withFillColor(color)
                    .withFillOpacity(0.25)
                    .withFillOutlineColor(color)
            )
            lm.create(
                PointAnnotationOptions()
                    .withPoint(Point.fromLngLat(g.lng, g.lat))
                    .withTextField("${g.nombre} (${g.radio.toInt()} m)")
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

    // ---------------------------------------------------------------- geocercas: diálogos

    private fun showGeocercaDialog(existing: Geocerca?, point: Point?) {
        val pad = (24 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        container.setPadding(pad, pad / 2, pad, 0)

        val nameInput = EditText(this)
        nameInput.hint = "Nombre de la geocerca"
        nameInput.inputType = InputType.TYPE_CLASS_TEXT
        nameInput.setText(existing?.nombre ?: "Geocerca ${geocercas.size + 1}")

        val radiusInput = EditText(this)
        radiusInput.hint = "Radio en metros"
        radiusInput.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        radiusInput.setText((existing?.radio ?: 100.0).toInt().toString())

        container.addView(nameInput)
        container.addView(radiusInput)

        AlertDialog.Builder(this, R.style.GeocorezDialog)
            .setTitle(if (existing == null) "Nueva geocerca" else "Editar geocerca")
            .setView(container)
            .setPositiveButton("Guardar") { _, _ ->
                val nombre = nameInput.text.toString().trim()
                    .ifEmpty { "Geocerca ${geocercas.size + 1}" }
                val radio = (radiusInput.text.toString().toDoubleOrNull() ?: 100.0)
                    .coerceIn(10.0, 100000.0)
                if (existing != null) {
                    existing.nombre = nombre
                    existing.radio = radio
                } else if (point != null) {
                    geocercas.add(
                        Geocerca(
                            UUID.randomUUID().toString().substring(0, 8),
                            nombre,
                            point.latitude(),
                            point.longitude(),
                            radio
                        )
                    )
                }
                refrescarGeocercas()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showListaGeocercas() {
        if (geocercas.isEmpty()) {
            toast("No hay geocercas. Usa \"+ Geocerca\" o mantén presionado el mapa.")
            return
        }
        val items = geocercas.map {
            val estado = if (dentroDe.contains(it.id)) "  (dentro)" else ""
            "${it.nombre} - ${it.radio.toInt()} m$estado"
        }.toTypedArray()

        AlertDialog.Builder(this, R.style.GeocorezDialog)
            .setTitle("Geocercas")
            .setItems(items) { _, which -> showAccionesGeocerca(geocercas[which]) }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun showAccionesGeocerca(g: Geocerca) {
        val opciones = arrayOf(
            "Ir a la geocerca",
            "Editar nombre y radio",
            "Mover a mi ubicación actual",
            "Eliminar"
        )
        AlertDialog.Builder(this, R.style.GeocorezDialog)
            .setTitle(g.nombre)
            .setItems(opciones) { _, which ->
                when (which) {
                    0 -> moverCamara(Point.fromLngLat(g.lng, g.lat), 16.0)
                    1 -> showGeocercaDialog(g, null)
                    2 -> {
                        val p = currentPoint
                        if (p != null) {
                            g.lat = p.latitude()
                            g.lng = p.longitude()
                            refrescarGeocercas()
                        } else {
                            toast("Aún no hay ubicación")
                        }
                    }
                    3 -> confirmarEliminar(g)
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
                refrescarGeocercas()
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
