package com.radiopoint.app.dsp

import android.annotation.SuppressLint
import android.media.*
import android.media.audiofx.*
import kotlinx.coroutines.*

class AudioRecordListener(private val onPacketReceived: (ByteArray)->Unit, private val onError: (String)->Unit = {}) {
    private var recorder: AudioRecord? = null
    private var job: Job? = null
    val demodulator=AcousticAfskDemodulator(onPacketReceived)
    @Volatile var muted=false
    @Volatile var isListening=false
        private set
    var isHandheldMode: Boolean
        get()=demodulator.isHandheldMode
        set(v){demodulator.isHandheldMode=v}
    @SuppressLint("MissingPermission")
    fun startListening(scope: CoroutineScope) {
        if(isListening)return
        try {
            val min=AudioRecord.getMinBufferSize(48000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
            check(min>0) { "48 kHz microphone input unavailable" }
            val r=AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,48000,AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,maxOf(min*2,8192))
            recorder=r
            check(r.state==AudioRecord.STATE_INITIALIZED) { "Microphone unavailable" }
            val effects=listOfNotNull(
                if(AutomaticGainControl.isAvailable()) AutomaticGainControl.create(r.audioSessionId) else null,
                if(NoiseSuppressor.isAvailable()) NoiseSuppressor.create(r.audioSessionId) else null,
                if(AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(r.audioSessionId) else null)
            effects.forEach { it.enabled=false }
            r.startRecording(); isListening=true
            job=scope.launch(Dispatchers.IO) {
                try {
                    val buffer=ShortArray(1024)
                    while(isActive && isListening) {
                        val n=r.read(buffer,0,buffer.size)
                        if(n<0)error("Microphone read failed ($n)")
                        if(muted)demodulator.reset() else if(n>0)demodulator.processAudioSamples(buffer,n)
                    }
                } catch(e: Exception) { if(isListening)onError(e.message ?: "Microphone stopped") }
                finally { effects.forEach { it.release() }; r.release(); isListening=false }
            }
        } catch(e:Exception) { recorder?.release(); recorder=null; isListening=false; onError(e.message ?: "Microphone unavailable") }
    }
    fun stopListening() {
        isListening=false
        try { recorder?.stop() } catch(_:Exception){}
        job?.cancel(); job=null; recorder=null
    }
}
