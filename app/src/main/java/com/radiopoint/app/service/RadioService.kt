package com.radiopoint.app.service

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.location.*
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.radiopoint.app.R
import com.radiopoint.app.data.*
import com.radiopoint.app.dsp.AudioRecordListener
import com.radiopoint.app.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

data class ReceivedPosition(val packet: TelemetryPacket, val receivedAt: Long = System.currentTimeMillis())
object RadioState {
    val fix=MutableStateFlow<Location?>(null)
    val listening=MutableStateFlow(false)
    val transmitting=MutableStateFlow(false)
    val message=MutableStateFlow("Microphone off • tap ARM to receive")
    val positions=MutableStateFlow<Map<Int,ReceivedPosition>>(emptyMap())
    var loaded=false
    fun load(context:Context) {
        if(loaded)return
        loaded=true
        try {
            val list:List<ReceivedPosition> = Gson().fromJson(File(context.filesDir,"crew.json").readText(),
                object:TypeToken<List<ReceivedPosition>>(){}.type)
            positions.value=list.filter { System.currentTimeMillis()-it.receivedAt in 0..1_800_000 }
                .associateBy { it.packet.unitId }
        }catch(_:Exception){}
    }
    fun freshFix():Location? = fix.value?.takeIf { it.hasAccuracy() && it.accuracy <= 100 &&
        SystemClock.elapsedRealtimeNanos()-it.elapsedRealtimeNanos in 0..30_000_000_000 }
}
class RadioService: Service(), LocationListener {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private lateinit var listener:AudioRecordListener
    private lateinit var transmitter:ChirpTransmitter
    private lateinit var locations:LocationManager
    private lateinit var wake:PowerManager.WakeLock
    private var expiry:Job?=null
    private val diskLock=Any()
    private val sessionEnd=SystemClock.elapsedRealtime()+12*60*60*1000L
    override fun onBind(intent:Intent?)=null
    override fun onCreate() {
        super.onCreate(); RadioState.load(this)
        transmitter=ChirpTransmitter(this)
        locations=getSystemService(LocationManager::class.java)
        wake=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"RadioPoint:receive")
        listener=AudioRecordListener(onPacketReceived={ bytes -> scope.launch { receive(bytes) } },
            onError={ msg -> scope.launch { RadioState.message.value=msg; stopSelf() } })
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("radio","Radio listening",NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action==STOP){stopSelf();return START_NOT_STICKY}
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED ||
           ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){stopSelf();return START_NOT_STICKY}
        try {
            startForeground(17,notification())
            if(!listener.isListening) {
                listener.startListening(scope)
                if(!listener.isListening){stopSelf();return START_NOT_STICKY}
                @Suppress("MissingPermission")
                locations.requestLocationUpdates(LocationManager.GPS_PROVIDER,1000L,0f,this)
                wake.acquire(12*60*60*1000L)
            }
            RadioState.listening.value=true
            RadioState.message.value="Listening • hold radio PTT when sending"
            extend()
            if(intent?.action==SEND) {
                val payload=intent.getByteArrayExtra("payload")
                if(payload!=null && TelemetryPacket.fromPayloadBytes(payload)?.let { it.fixAgeSeconds<=30 && it.accuracyMeters<=100 }==true && RadioState.freshFix()!=null) {
                    listener.muted=true
                    RadioState.transmitting.value=true
                    val accepted=transmitter.triggerChirp(payload) { error -> scope.launch {
                        listener.muted=false; RadioState.transmitting.value=false
                        RadioState.message.value=error ?: "Chirp sent • delivery is not acknowledged"
                    } }
                    if(!accepted){listener.muted=false;RadioState.transmitting.value=false;RadioState.message.value="Wait a moment before sending again"}
                }else RadioState.message.value="Waiting for a fresh GPS fix"
            }
        }catch(e:Exception){RadioState.message.value=e.message ?: "Could not start radio";stopSelf()}
        return START_NOT_STICKY
    }
    private fun extend() {
        expiry?.cancel()
        val always=getSharedPreferences("radio",MODE_PRIVATE).getBoolean("always",false)
        expiry=scope.launch {
            delay(minOf(if(always)12*60*60*1000L else 5*60*1000L, (sessionEnd-SystemClock.elapsedRealtime()).coerceAtLeast(0))); RadioState.message.value="Listening timed out • tap ARM to receive";stopSelf()
        }
    }
    private fun receive(bytes:ByteArray) {
        if(transmitter.isTransmitting)return
        val packet=TelemetryPacket.fromPayloadBytes(bytes)?:return
        val self=getSharedPreferences("radio",MODE_PRIVATE).getInt("unit",0)
        if(packet.unitId==self){RadioState.message.value="Unit $self is also in use • change your unit number";return}
        val old=RadioState.positions.value[packet.unitId]
        if(old?.packet==packet && System.currentTimeMillis()-old.receivedAt<2000)return
        val updated=RadioState.positions.value.filterValues { System.currentTimeMillis()-it.receivedAt<1_800_000 }.toMutableMap()
        updated[packet.unitId]=ReceivedPosition(packet)
        RadioState.positions.value=updated
        val json=Gson().toJson(updated.values)
        scope.launch(Dispatchers.IO) {
            try { synchronized(diskLock) { val atomic=android.util.AtomicFile(File(filesDir,"crew.json")); var out:java.io.FileOutputStream?=null
                try{out=atomic.startWrite();out.write(json.toByteArray());atomic.finishWrite(out)}catch(e:Exception){atomic.failWrite(out)} }
            }catch(_:Exception){}
        }
        RadioState.message.value="Received unit ${packet.unitId} • ±${packet.accuracyMeters} m"
        extend()
    }
    private fun notification():Notification {
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop=PendingIntent.getService(this,1,Intent(this,RadioService::class.java).setAction(STOP),PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this,"radio").setSmallIcon(R.drawable.ic_truck).setContentTitle("RadioPoint is listening")
            .setContentText("Microphone and GPS active • audio is not recorded")
            .setContentIntent(open).setOngoing(true).addAction(0,"Stop listening",stop).build()
    }
    override fun onLocationChanged(location:Location){RadioState.fix.value=location}
    override fun onDestroy() {
        expiry?.cancel();listener.stopListening();transmitter.release()
        locations.removeUpdates(this);if(wake.isHeld)wake.release();scope.cancel()
        RadioState.listening.value=false;RadioState.transmitting.value=false
        if(RadioState.message.value.startsWith("Listening") || RadioState.message.value.startsWith("Received"))RadioState.message.value="Microphone off • tap ARM to receive"
        super.onDestroy()
    }
    companion object { const val ARM="ARM";const val SEND="SEND";const val STOP="STOP" }
}
