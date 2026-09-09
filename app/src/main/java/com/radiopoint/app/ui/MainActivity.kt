package com.radiopoint.app.ui

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.location.*
import android.net.Uri
import android.os.*
import android.provider.OpenableColumns
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.radiopoint.app.R
import com.radiopoint.app.data.*
import com.radiopoint.app.databinding.ActivityMainBinding
import com.radiopoint.app.gis.*
import com.radiopoint.app.map.CustomMapLoader
import com.radiopoint.app.map.TrackImporter
import com.radiopoint.app.service.*
import kotlinx.coroutines.*
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.*
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity:AppCompatActivity(),LocationListener {
    private lateinit var binding:ActivityMainBinding
    private lateinit var roadManager:RoadManager
    private lateinit var activeMaps:ActiveMapManager
    private lateinit var mapLoader:CustomMapLoader
    private lateinit var gps:LocationManager
    private val prefs by lazy { getSharedPreferences("radio",MODE_PRIVATE) }
    private var unit=0
    private var status=VehicleStatus.EMPTY
    private var direction=Direction.PARKED
    private var onFoot=false
    // -1 selects the local saved truck; positive values select a received unit.
    private var fieldTarget=-1
    private data class TruckPin(val lat:Double,val lon:Double,val accuracy:Double,val savedAt:Long)
    private fun truckPin():TruckPin? = try {
        val pin=TruckPin(prefs.getString("truck_lat",null)!!.toDouble(),prefs.getString("truck_lon",null)!!.toDouble(),
            prefs.getFloat("truck_accuracy",-1f).toDouble(),prefs.getLong("truck_saved",0))
        pin.takeIf { it.lat.isFinite() && it.lon.isFinite() && it.lat in -90.0..90.0 && it.lon in -180.0..180.0 && it.accuracy.isFinite() && it.accuracy>=0 && it.savedAt>0 }
    }catch(_:Exception){null}
    private var sequence=0
    private var uiJob:Job?=null
    private var pendingAction:(()->Unit)?=null
    private val predictor=RoadPredictorEngine()
    private lateinit var voice:com.radiopoint.app.audio.VoiceAlertManager
    private var lastAnnounced=0L
    private val routeFiles by lazy { File(filesDir,"routes").apply { mkdirs() } }
    private var mapName="Online / cached OpenStreetMap"
    private var staticMapKey=""
    private val markerCache=mutableMapOf<String,Marker>()
    private val permissionLauncher=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if(hasPermissions()){startGps();pendingAction?.invoke()}else toast("Precise location and microphone access are needed to use the radio")
        pendingAction=null
    }
    private val mapImport=registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null)lifecycleScope.launch {
            val name=fileName(uri)
            val pack=withContext(Dispatchers.IO){mapLoader.importMapArchiveFromUri(uri,name)}
            if(pack!=null && mapLoader.applyMapArchiveToMapView(binding.mapView,pack)) {
                mapName=pack.fileName;prefs.edit().putString("map",pack.fileName).apply()
                toast("Offline map loaded")
            }else toast("Could not load that archive")
        }
    }
    private val routeImport=registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null)lifecycleScope.launch {
            try {
                val name=fileName(uri)
                require(name.endsWith(".gpx",true)||name.endsWith(".kml",true)){"Choose a GPX or KML track"}
                val result=withContext(Dispatchers.IO) {
                    val bytes=contentResolver.openInputStream(uri)?.use { input ->
                        val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                        while(true){val n=input.read(buffer);if(n<0)break;require(out.size()+n<=10_000_000){"Track is larger than 10 MB"};out.write(buffer,0,n)}
                        out.toByteArray()
                    }?:error("Cannot open track")
                    val parsed=bytes.inputStream().use { if(name.endsWith(".kml",true))TrackImporter.parseKml(it,name.substringBeforeLast('.')) else TrackImporter.parseGpx(it,name.substringBeforeLast('.')) }?:error("No road geometry found")
                    val saved = File(routeFiles,"${parsed.road.roadCode}.${if(name.endsWith(".kml",true))"kml" else "gpx"}")
                    saved.writeBytes(bytes)
                    prefs.edit().putString("route_title_${saved.name}",name.substringBeforeLast('.')).apply()
                    parsed
                }
                roadManager.addRoad(result.road);activeMaps.loadRoadCatalog(listOf(result.road));selectRoad(result.road)
                toast("Imported ${result.road.name}. Route km starts at the first track point.")
            }catch(e:Exception){toast(e.message?:"Import failed")}
        }
    }
    private val export=registerForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.google-earth.kml+xml")) { uri ->
        if(uri!=null)try {
            val entries=RadioState.positions.value.values
            val xml=buildString {
                append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document>")
                entries.forEach { r-> val p=r.packet
                    append("<Placemark><name>Unit ${p.unitId}</name><description>Last heard ${java.util.Date(r.receivedAt)}; GPS accuracy ${p.accuracyMeters} m; ${p.status.label}. Historical position.</description><Point><coordinates>${p.longitude},${p.latitude},0</coordinates></Point></Placemark>")
                };append("</Document></kml>")
            }
            contentResolver.openOutputStream(uri)?.use { it.write(xml.toByteArray()) }?:error("Cannot write export")
            toast("Crew positions exported")
        }catch(e:Exception){toast(e.message?:"Export failed")}
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this,getSharedPreferences("osmdroid",MODE_PRIVATE))
        Configuration.getInstance().userAgentValue=packageName
        binding=ActivityMainBinding.inflate(layoutInflater);setContentView(binding.root)
        window.statusBarColor=0xff101b1b.toInt();window.navigationBarColor=0xff101b1b.toInt()
        gps=getSystemService(LocationManager::class.java)
        voice=com.radiopoint.app.audio.VoiceAlertManager(this).apply { isVoiceEnabled=prefs.getBoolean("voice",false) }
        unit=prefs.getInt("unit",0);status=VehicleStatus.fromCode(prefs.getInt("status",0))
        onFoot=prefs.getBoolean("on_foot",false)
        fieldTarget=prefs.getInt("field_target",-1)
        RadioState.load(this)
        mapLoader=CustomMapLoader(this);activeMaps=ActiveMapManager(this)
        val imported=routeFiles.listFiles()?.mapNotNull { file->try { file.inputStream().use { if(file.extension=="kml")TrackImporter.parseKml(it,prefs.getString("route_title_${file.name}",null)?:file.nameWithoutExtension)?.road else TrackImporter.parseGpx(it,prefs.getString("route_title_${file.name}",null)?:file.nameWithoutExtension)?.road } }catch(_:Exception){null} }?:emptyList()
        roadManager=RoadManager(imported+RoadCatalog.defaultRoads);activeMaps.loadRoadCatalog(roadManager.availableRoads)
        roadManager.selectRoad(prefs.getString("road",null)?:roadManager.selectedRoad.name)
        binding.mapView.setTileSource(TileSourceFactory.MAPNIK);binding.mapView.setMultiTouchControls(true)
        binding.mapView.controller.setZoom(13.0)
        mapLoader.getImportedMaps().find { it.fileName==prefs.getString("map",null) }?.let {
            if(mapLoader.applyMapArchiveToMapView(binding.mapView,it))mapName=it.fileName
        }
        binding.btnMode.setOnClickListener {
            onFoot=!onFoot;direction=Direction.PARKED
            prefs.edit().putBoolean("on_foot",onFoot).apply()
            refresh()
            if(onFoot && truckPin()==null)toast("Before leaving the truck, tap SAVE TRUCK to mark its parking spot")
        }
        binding.btnSettings.setOnClickListener { settings() }
        binding.txtRoadSelector.setOnClickListener { chooseRoad() }
        binding.btnCustomMaps.setOnClickListener { maps() }
        binding.btnCrew.setOnClickListener { crew() }
        binding.btnArm.setOnClickListener {
            if(RadioState.listening.value)stopService(Intent(this,RadioService::class.java))
            else withPermissions { startRadio(RadioService.ARM) }
        }
        binding.btnToggleLoadStatus.setOnClickListener {if(onFoot){fieldTarget=-1;prefs.edit().putInt("field_target",fieldTarget).apply();refresh();centerFieldTarget();return@setOnClickListener};status=VehicleStatus.entries[(status.ordinal+1)%VehicleStatus.entries.size];prefs.edit().putInt("status",status.code).apply();refresh()}
        binding.btnDirection.setOnClickListener {
            if(onFoot){chooseFieldTarget();return@setOnClickListener}
            AlertDialog.Builder(this).setTitle("Direction along imported track")
                .setItems(arrayOf("UP · toward track end","DOWN · toward track start","PARKED / unknown")){_,which->direction=Direction.entries[which];refresh()}.show()
        }
        binding.btnMarkPullout.setOnClickListener { if(onFoot)saveTruck() else markPullout() }
        binding.txtFieldGuide.setOnClickListener { centerFieldTarget() }
        binding.btnChirp.setOnClickListener { chirp() }
        selectRoad(roadManager.selectedRoad)
        if(unit==0)settings()
    }
    private fun settings() {
        val layout=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(40,12,40,0)}
        val number=EditText(this).apply {inputType=android.text.InputType.TYPE_CLASS_NUMBER;hint="Unit number (1–255)";if(unit>0)setText(unit.toString());contentDescription="Unique crew unit number"}
        val always=CheckBox(this).apply {text="Continuous listening (up to 12 hours)";isChecked=prefs.getBoolean("always",false)}
        val spoken=CheckBox(this).apply {text="Spoken encounter alerts while app is open";isChecked=prefs.getBoolean("voice",false)}
        val text=TextView(this).apply {text="Use a different number on each phone.\n\nDefault listening lasts five minutes and extends after a received chirp. Continuous mode runs until stopped, up to 12 hours.\n\nThis field-test build uses protocol v2. Both phones must use this build. Radio delivery is not acknowledged.";setPadding(0,16,0,12)}
        layout.addView(number);layout.addView(always);layout.addView(spoken);layout.addView(text)
        val dialog=AlertDialog.Builder(this).setTitle("RadioPoint · crew setup").setView(layout).setPositiveButton("Save",null).setNegativeButton("Cancel",null).create()
        dialog.setOnShowListener {dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val n=number.text.toString().toIntOrNull()
            if(n==null||n !in 1..255)number.error="Choose 1–255"
            else {unit=n;prefs.edit().putInt("unit",unit).putBoolean("always",always.isChecked).putBoolean("voice",spoken.isChecked).apply();voice.isVoiceEnabled=spoken.isChecked;if(RadioState.listening.value)startRadio(RadioService.ARM);refresh();dialog.dismiss()}
        }};dialog.show()
    }
    private fun chooseRoad() {
        val roads=roadManager.availableRoads
        AlertDialog.Builder(this).setTitle("Road · same track on both phones")
            .setItems((roads.map { if(it.isDemo)"Sample corridor · demo only" else it.name }+"Import GPX / KML…").toTypedArray()){_,i->
                if(i==roads.size)routeImport.launch(arrayOf("*/*"))else selectRoad(roads[i])
            }.show()
    }
    private fun selectRoad(road:RoadDefinition) {
        roadManager.selectRoad(road.name);prefs.edit().putString("road",road.name).apply()
        direction=Direction.PARKED
        val fix=RadioState.fix.value
        val (lat,lon)=if(fix!=null)fix.latitude to fix.longitude else UtmConverter.utmToLatLon(road.originUtm)
        binding.mapView.controller.setCenter(GeoPoint(lat,lon));refresh()
    }
    private fun maps() {
        val packs=mapLoader.getImportedMaps()
        AlertDialog.Builder(this).setTitle("Maps & tracks").setItems((listOf("Import offline map archive","Import road GPX / KML","Use online / cached OpenStreetMap")+packs.map {it.fileName}).toTypedArray()){_,i->
            when(i) {
                0->mapImport.launch(arrayOf("*/*"))
                1->routeImport.launch(arrayOf("*/*"))
                2->{mapLoader.applyMapArchiveToMapView(binding.mapView,null);mapName="Online / cached OpenStreetMap";prefs.edit().remove("map").apply()}
                else->{val p=packs[i-3];if(mapLoader.applyMapArchiveToMapView(binding.mapView,p)){mapName=p.fileName;prefs.edit().putString("map",p.fileName).apply()}else toast("Unable to load archive")}
            }
        }.show()
    }
    private fun crew() { chooseFieldTarget() }
    private fun chooseFieldTarget() {
        val rows=RadioState.positions.value.values.filter {it.packet.unitId!=unit && age(it)<1800}.sortedBy {it.packet.unitId}
        val labels=listOf(if(truckPin()==null)"Saved truck · not marked yet" else "Saved truck · parking snapshot")+rows.map {r->
            "Unit ${r.packet.unitId} · ${ageLabel(age(r))} · ±${r.packet.accuracyMeters} m"
        }
        AlertDialog.Builder(this).setTitle("Find crew or truck")
            .setItems(labels.toTypedArray()){_,i->
                fieldTarget=if(i==0)-1 else rows[i-1].packet.unitId
                onFoot=true;direction=Direction.PARKED
                prefs.edit().putBoolean("on_foot",true).putInt("field_target",fieldTarget).apply()
                refresh();centerFieldTarget()
            }.setNegativeButton("Close",null)
            .setNeutralButton("Export crew KML"){_,_->export.launch("RadioPoint-crew.kml")}.show()
    }
    private fun saveTruck() {
        if(RadioState.freshFix()==null){withPermissions { startGps() };toast("Wait for a fresh GPS fix before saving the truck");return}
        AlertDialog.Builder(this).setTitle(if(truckPin()==null)"Mark truck here?" else "Replace saved truck location?")
            .setMessage("Save your phone's current GPS position as the truck's parking spot. Do this while standing at the truck. This marker stays on this phone and does not follow a moving truck.")
            .setPositiveButton("Save here"){_,_->
                val fix=RadioState.freshFix()
                if(fix==null)toast("GPS is no longer fresh; truck location was not changed")
                else {
                    prefs.edit().putString("truck_lat",fix.latitude.toString()).putString("truck_lon",fix.longitude.toString())
                        .putFloat("truck_accuracy",fix.accuracy).putLong("truck_saved",System.currentTimeMillis()).putInt("field_target",-1).apply()
                    fieldTarget=-1;refresh();centerFieldTarget();toast("Truck parking location saved on this phone")
                }
            }.setNegativeButton("Cancel",null).show()
    }
    private fun centerFieldTarget() {
        val point=if(fieldTarget==-1)truckPin()?.let {GeoPoint(it.lat,it.lon)}
            else RadioState.positions.value[fieldTarget]?.takeIf {age(it)<1800 && it.packet.unitId!=unit}?.packet?.let {GeoPoint(it.latitude,it.longitude)}
        if(point==null){toast("No target position available yet");return}
        val fix=RadioState.freshFix()
        if(fix!=null && kotlin.math.abs(fix.longitude-point.longitude)<180) {
            val bounds=org.osmdroid.util.BoundingBox(
                maxOf(point.latitude,fix.latitude)+0.0005,maxOf(point.longitude,fix.longitude)+0.0005,
                minOf(point.latitude,fix.latitude)-0.0005,minOf(point.longitude,fix.longitude)-0.0005)
            binding.mapView.post { binding.mapView.zoomToBoundingBox(bounds,true,80,17.0,null) }
        }else binding.mapView.controller.animateTo(point)
    }
    private fun refreshFieldGuide() {
        val pin=truckPin()
        if(pin!=null)marker(pin.lat,pin.lon,"Saved truck","Parking snapshot · ${java.util.Date(pin.savedAt)} · ±${fmt(pin.accuracy,0)} m",R.drawable.ic_truck)
        binding.txtFieldGuide.visibility=if(onFoot)View.VISIBLE else View.GONE
        if(!onFoot)return
        val r=RadioState.positions.value[fieldTarget]?.takeIf {it.packet.unitId!=unit && age(it)<1800}
        val lat:Double;val lon:Double;val accuracy:Double;val title:String;val detail:String
        if(fieldTarget==-1) {
            if(pin==null){binding.txtFieldGuide.text="Find your truck · tap SAVE TRUCK while at its parking spot";return}
            lat=pin.lat;lon=pin.lon;accuracy=pin.accuracy;title="SAVED TRUCK"
            detail="Parking snapshot · ${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT).format(java.util.Date(pin.savedAt))}"
        }else {
            if(r==null){binding.txtFieldGuide.text="UNIT $fieldTarget · no recent report\nAsk them by voice to chirp their location";return}
            lat=r.packet.latitude;lon=r.packet.longitude;accuracy=r.packet.accuracyMeters.toDouble();title="UNIT $fieldTarget"
            detail=if(age(r)>30)"STALE · ${ageLabel(age(r))} · ask for a new chirp" else "Last position · ${ageLabel(age(r))}"
        }
        val fix=RadioState.freshFix()
        val guidance=if(fix==null)"Your GPS is stale or unavailable · waiting for a fresh fix" else {
            val g=FieldNavigation.between(fix.latitude,fix.longitude,lat,lon,fix.accuracy.toDouble(),accuracy)
            val distance=if(g.meters<1000)"${fmt(g.meters,0)} m" else "${fmt(g.meters/1000,2)} km"
            val bearing=g.bearingTrue?.let { "${it.roundToInt()%360}° true" } ?: "Within GPS uncertainty · bearing unreliable"
            "$distance · $bearing"
        }
        binding.txtFieldGuide.text="$title · $guidance\n$detail\nTarget ±${fmt(accuracy,0)} m · straight line, not a walking route · tap to view"
    }
    private fun markPullout() {
        val fix=RadioState.freshFix()?:return toast("Wait for a GPS fix less than 30 seconds old with accuracy within 100 m")
        val road=roadManager.selectedRoad
        if(road.isDemo)return toast("Import your actual road track before marking pullouts")
        val added=activeMaps.addPulloutToLoadedMap(UtmConverter.latLonToUtm(fix.latitude,fix.longitude),fix.latitude to fix.longitude,road.name)
        if(added!=null){toast("Saved pullout at route km ${fmt(added.kmMarker)}");refresh()}else toast("GPS is more than 150 m from the selected road")
    }
    private fun chirp() {
        if(unit==0){settings();return}
        val fix=RadioState.freshFix()?:return toast("Wait for a fresh GPS fix (≤30 seconds, accuracy ≤100 m)")
        if(RadioState.transmitting.value)return
        withPermissions {
            val road=roadManager.selectedRoad
            var payload=TelemetryPacket(unit,status,if(fix.hasSpeed()&&fix.speed>=0.84)direction else Direction.PARKED,
                if(road.isDemo)0 else road.roadCode,fix.latitude,fix.longitude,
                if(fix.hasSpeed())(fix.speed*3.6).coerceIn(0.0,254.0) else 0.0,if(fix.hasBearing())fix.bearing.toDouble() else 0.0,
                kotlin.math.ceil(fix.accuracy.toDouble()).toInt(),((SystemClock.elapsedRealtimeNanos()-fix.elapsedRealtimeNanos)/1_000_000_000L).toInt().coerceIn(0,255),sequence++ and 255)
            if(onFoot)payload=payload.forOnFoot()
            startRadio(RadioService.SEND,payload.toPayloadBytes())
        }
    }
    private fun startRadio(action:String,payload:ByteArray?=null) {
        try {ContextCompat.startForegroundService(this,Intent(this,RadioService::class.java).setAction(action).putExtra("payload",payload))}
        catch(e:Exception){toast(e.message?:"Could not start listening")}
    }
    private fun hasPermissions()=listOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.RECORD_AUDIO).all {ContextCompat.checkSelfPermission(this,it)==PackageManager.PERMISSION_GRANTED}
    private fun withPermissions(action:()->Unit) {
        if(hasPermissions()){action();return}
        pendingAction=action
        val requested=mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.RECORD_AUDIO)
        if(Build.VERSION.SDK_INT>=33)requested.add(Manifest.permission.POST_NOTIFICATIONS)
        permissionLauncher.launch(requested.toTypedArray())
    }
    private fun startGps() {
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)return
        try {@Suppress("MissingPermission") gps.requestLocationUpdates(LocationManager.GPS_PROVIDER,1000L,0f,this)}catch(e:Exception){RadioState.message.value="Enable GPS to send a position"}
    }
    override fun onLocationChanged(location:Location){RadioState.fix.value=location}
    override fun onResume() {
        super.onResume();binding.mapView.onResume();startGps()
        uiJob=lifecycleScope.launch {while(isActive){refresh();delay(1000)}}
    }
    override fun onPause(){uiJob?.cancel();gps.removeUpdates(this);binding.mapView.onPause();super.onPause()}
    override fun onDestroy(){voice.shutdown();binding.mapView.onDetach();super.onDestroy()}
    override fun onKeyDown(keyCode:Int,event:KeyEvent?):Boolean {
        // Volume keys keep their ordinary purpose, including setting chirp volume.
        return super.onKeyDown(keyCode,event)
    }
    private fun refresh() {
        if(!::roadManager.isInitialized)return
        val road=roadManager.selectedRoad;val fix=RadioState.fix.value;val fresh=RadioState.freshFix()
        binding.btnSettings.text=if(unit==0)"SET UNIT" else "UNIT $unit"
        binding.btnMode.text=if(onFoot)"ON FOOT · SWITCH TO TRUCK" else "TRUCK MODE · SWITCH TO ON FOOT"
        binding.txtRoadSelector.visibility=if(onFoot)View.GONE else View.VISIBLE
        binding.btnToggleLoadStatus.text=if(onFoot)"FIND TRUCK" else status.label
        binding.btnDirection.text=if(onFoot)"FIND CREW" else direction.label
        binding.btnMarkPullout.text=if(onFoot)"SAVE TRUCK" else "+ PULLOUT"
        binding.btnArm.text=if(RadioState.listening.value)"DISARM" else if(prefs.getBoolean("always",false))"ARM · ALWAYS" else "ARM · 5 MIN"
        binding.btnChirp.text=if(RadioState.transmitting.value)"SENDING… HOLD PTT" else if(fresh==null)"WAITING FOR GPS" else "CHIRP LOCATION · HOLD PTT"
        binding.btnChirp.isEnabled=fresh!=null && !RadioState.transmitting.value
        binding.txtLink.text=RadioState.message.value
        binding.txtRoadSelector.text=if(road.isDemo)"Import a road track › · sample map only" else "${road.name} › · verify posted channel"
        val rows=RadioState.positions.value.values.filter {age(it)<1800 && it.packet.unitId!=unit}
        binding.btnCrew.text="CREW · ${rows.size}"
        binding.txtMapNote.text="$mapName\n"+if(onFoot)"Positions update only when a chirp is received." else "No reports does not mean the road is clear."
        val mapKey="${road.roadCode}:${activeMaps.activePullouts.size}:$onFoot"
        val rebuild=mapKey!=staticMapKey
        if(rebuild){binding.mapView.overlays.clear();markerCache.clear();staticMapKey=mapKey}
        val activeUnits=rows.map {"Unit ${it.packet.unitId}"}.toSet()+"Unit $unit"
        markerCache.keys.filter {it.startsWith("Unit ") && it !in activeUnits}.toList().forEach {key->binding.mapView.overlays.remove(markerCache.remove(key))}
        if(rebuild && !onFoot && !road.isDemo) {
            val line=Polyline().apply {setPoints(road.geometry.coordinates.map {val p=UtmConverter.utmToLatLon(it);GeoPoint(p.first,p.second) });outlinePaint.color=0xff2d775d.toInt();outlinePaint.strokeWidth=7f}
            binding.mapView.overlays.add(line)
            activeMaps.activePullouts.filter {it.roadName==road.name}.forEach {p->marker(p.lat,p.lon,"Recorded pullout · route km ${fmt(p.kmMarker)}","User/imported point; suitability unverified",R.drawable.ic_pullout)}
        }
        var localUtm:org.locationtech.jts.geom.Coordinate?=null
        var nearRoad=false
        if(fix!=null) {
            localUtm=UtmConverter.latLonToUtm(fix.latitude,fix.longitude)
            nearRoad=!onFoot && !road.isDemo && roadManager.isPointNearRoad(localUtm,150.0)
            marker(fix.latitude,fix.longitude,"Unit $unit · you","GPS accuracy ±${fix.accuracy.roundToInt()} m",if(onFoot)R.drawable.ic_position else R.drawable.ic_truck,if(fresh==null)0.4f else 1f)
            val km=if(nearRoad)"Route km ${fmt(roadManager.snapToSelectedRoad(localUtm).second)}" else "Off route"
            binding.txtCurrentKmBadge.text=if(fresh==null)"GPS stale / inaccurate · ±${fix.accuracy.roundToInt()} m" else "$km · ±${fix.accuracy.roundToInt()} m\n${fmt(fix.speed*3.6,0)} km/h · ${direction.label}"
        }else binding.txtCurrentKmBadge.text="Waiting for GPS · tap ARM to begin"
        var best:Pair<ReceivedPosition,PassPrediction>?=null
        rows.forEach {r ->val p=r.packet;val age=age(r)
            marker(p.latitude,p.longitude,"Unit ${p.unitId} · ${p.status.label}","${ageLabel(age)} · ±${p.accuracyMeters} m",if(onFoot)R.drawable.ic_position else R.drawable.ic_truck,if(age>30)0.35f else 1f)
            if(!onFoot && fresh!=null && nearRoad && localUtm!=null && p.roadCode==road.roadCode && age<=30 && p.accuracyMeters<=100) {
                val remote=UtmConverter.latLonToUtm(p.latitude,p.longitude)
                if(roadManager.isPointNearRoad(remote,150.0)) {
                    val prediction=predictor.predictMeet(localUtm,remote,fix!!.speed*3.6,p.speedKmh,road,activeMaps.activePullouts,direction,p.direction)
                    if(prediction!=null && (best==null || prediction.timeToMeetMinutes<best!!.second.timeToMeetMinutes))best=r to prediction
                }
            }
        }
        binding.cardMeetHud.visibility=if(best==null)View.GONE else View.VISIBLE
        if(best==null)markerCache.remove("Estimated encounter")?.let {binding.mapView.overlays.remove(it)}
        best?.let {(r,p)->
            if (r.receivedAt != lastAnnounced && voice.isVoiceEnabled && !RadioState.transmitting.value) {
                lastAnnounced=r.receivedAt
                voice.speak("Unit ${r.packet.unitId} approaching. Estimated encounter at route kilometre ${fmt(p.passKm)} in ${fmt(p.timeToMeetMinutes)} minutes. Coordinate by voice.")
            }
            binding.txtAlertHeader.text="APPROACHING · UNIT ${r.packet.unitId} · ${ageLabel(age(r))}"
            binding.txtMeetDetails.text="Estimated encounter: route km ${fmt(p.passKm)} · ${fmt(p.timeToMeetMinutes)} min"+
                (p.nearbyPulloutKm?.let {"\nRecorded pullout near km ${fmt(it)} — coordinate by voice"} ?: "\nNo nearby recorded pullout; coordinate by voice")
            val geo=UtmConverter.utmToLatLon(p.passCoordinate);marker(geo.first,geo.second,"Estimated encounter","Constant-speed estimate; not a passing instruction",R.drawable.ic_meet)
        }
        if(onFoot && fix!=null)binding.txtCurrentKmBadge.text=
            "${fmt(fix.latitude,6)}, ${fmt(fix.longitude,6)}\n"+
            if(fresh==null)"GPS stale / inaccurate" else "Your GPS ±${fix.accuracy.roundToInt()} m"
        refreshFieldGuide()
        binding.mapView.invalidate()
    }
    private fun marker(lat:Double,lon:Double,title:String,note:String,icon:Int,opacity:Float=1f) {
        val key=if(title.startsWith("Unit "))title.substringBefore(" · ") else title
        val item=markerCache.getOrPut(key){Marker(binding.mapView).also {binding.mapView.overlays.add(it)}}
        item.apply {position=GeoPoint(lat,lon);setAnchor(0.5f,0.5f);this.title=title;snippet=note;this.icon=ContextCompat.getDrawable(this@MainActivity,icon);alpha=opacity}

    }
    private fun age(r:ReceivedPosition)=FieldNavigation.reportAgeSeconds(System.currentTimeMillis(),r.receivedAt,r.packet.fixAgeSeconds)?:Long.MAX_VALUE
    private fun ageLabel(s:Long)=if(s==Long.MAX_VALUE)"Age unknown" else if(s<=30)"${s}s ago" else if(s<60)"${s}s ago · stale" else "${s/60}m ago · stale"
    private fun fmt(n:Double,digits:Int=1)=String.format(Locale.US,"%.${digits}f",n)
    private fun fileName(uri:Uri):String=contentResolver.query(uri,null,null,null,null)?.use {if(it.moveToFirst()){val i=it.getColumnIndex(OpenableColumns.DISPLAY_NAME);if(i>=0)it.getString(i) else null}else null}?:"import"
    private fun toast(message:String){Toast.makeText(this,message,Toast.LENGTH_LONG).show()}
}
