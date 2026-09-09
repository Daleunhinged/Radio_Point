package com.radiopoint.app.data

import android.content.Context
import android.media.*
import android.os.SystemClock
import com.radiopoint.app.dsp.RadioCodec

class ChirpTransmitter(context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    @Volatile var isTransmitting = false
        private set
    private var lastTransmit = -2000L
    @Volatile private var closed = false
    @Synchronized fun triggerChirp(payload: ByteArray, onComplete: (String?) -> Unit): Boolean {
        if(closed || isTransmitting || SystemClock.elapsedRealtime()-lastTransmit < 1500) return false
        isTransmitting=true; lastTransmit=SystemClock.elapsedRealtime()
        Thread {
            var track: AudioTrack? = null
            var error: String? = null
            try {
                check(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)>0) { "Turn up media volume before transmitting" }
                val pcm=RadioCodec.synthesize(payload)
                track=AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(48000).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(pcm.size*2).setTransferMode(AudioTrack.MODE_STATIC).build()
                val speaker=audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).find { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                check(speaker != null && track.setPreferredDevice(speaker)) { "Phone speaker unavailable" }
                check(track.write(pcm,0,pcm.size)==pcm.size) { "Audio write failed" }
                track.play()
                val start=SystemClock.elapsedRealtime()
                while(track.playbackHeadPosition < pcm.size && SystemClock.elapsedRealtime()-start<3000 && !closed) Thread.sleep(10)
                check(!closed && track.playbackHeadPosition >= pcm.size) { "Transmission interrupted" }
                check(track.routedDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) { "Audio was not routed to phone speaker" }
                Thread.sleep(150) // discard the local acoustic tail before receiving again
            } catch(e: Exception) { error=e.message ?: "Audio transmission failed" }
            finally { track?.release(); isTransmitting=false; onComplete(error) }
        }.start()
        return true
    }
    fun release() { closed=true }
}
