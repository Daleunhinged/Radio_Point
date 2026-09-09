package com.radiopoint.app.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import com.radiopoint.app.data.RemoteTruckState
import com.radiopoint.app.gis.PassPrediction
import java.util.Locale

class VoiceAlertManager(context: Context) : TextToSpeech.OnInitListener {

    private val tag = "VoiceAlertManager"
    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var isTtsReady = false

    var isVoiceEnabled: Boolean = false

    private var lastSpokenTimeMs = 0L
    private val debounceTimeMs = 15000L // 15s between voice callouts for the same event
    private var lastSpokenUnitId = -1

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.CANADA)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.setLanguage(Locale.US)
            }
            tts?.setSpeechRate(1.05f)
            tts?.setPitch(1.0f)
            isTtsReady = true
            Log.i(tag, "TTS initialized successfully")
        } else {
            Log.w(tag, "TTS initialization failed")
        }
    }

    fun announceTrafficMeet(truck: RemoteTruckState, meet: PassPrediction) {
        if (!isVoiceEnabled || !isTtsReady) return

        val now = System.currentTimeMillis()
        if (truck.unitId == lastSpokenUnitId && (now - lastSpokenTimeMs) < debounceTimeMs) {
            return
        }

        lastSpokenTimeMs = now
        lastSpokenUnitId = truck.unitId

        val minutes = Math.round(meet.timeToMeetMinutes).toInt().coerceAtLeast(1)
        val timeStr = if (minutes == 1) "1 minute" else "$minutes minutes"
        val statusStr = truck.status.label.lowercase()
        val dirStr = truck.direction.label.lowercase()

        val locationDescriptor = if (meet.isDesignatedPullout) {
            "KM ${String.format(Locale.US, "%.1f", meet.passKm)} Turnout"
        } else {
            "KM ${String.format(Locale.US, "%.1f", meet.passKm)}"
        }

        val speech = "$statusStr truck ${truck.unitId} $dirStr bound. Meet at $locationDescriptor in $timeStr."
        
        tts?.speak(speech, TextToSpeech.QUEUE_FLUSH, null, "MEET_ALERT_${truck.unitId}")
    }

    fun speak(text: String) {
        if (!isVoiceEnabled || !isTtsReady) return
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "CUSTOM_VOICE")
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isTtsReady = false
    }
}
