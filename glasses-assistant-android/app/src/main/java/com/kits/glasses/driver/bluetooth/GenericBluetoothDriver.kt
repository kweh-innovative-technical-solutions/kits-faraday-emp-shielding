package com.kits.glasses.driver.bluetooth

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.kits.glasses.core.Cue
import com.kits.glasses.core.GlassesCore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

/**
 * The cross-brand fallback: any A2DP/HFP Bluetooth headset, no vendor SDK.
 *
 * Output goes through Android [TextToSpeech], which the platform routes to the
 * connected Bluetooth audio device automatically. Input is NOT opened here for
 * v1 — [com.kits.glasses.Stt] owns the microphone via SpeechRecognizer, so this
 * driver deliberately does not start a SCO session during recognition (doing so
 * would fight the recognizer for the mic).
 */
class GenericBluetoothDriver(private val context: Context) : GlassesCore {

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var tts: TextToSpeech? = null

    override var isConnected: Boolean = false
        private set

    override suspend fun connect(): Boolean {
        val ready = CompletableDeferred<Boolean>()
        tts = TextToSpeech(context.applicationContext) { status ->
            ready.complete(status == TextToSpeech.SUCCESS)
        }
        val ok = ready.await()
        if (ok) {
            tts?.apply {
                language = Locale.getDefault()
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
            }
        }
        isConnected = ok
        return ok
    }

    /**
     * True if a Bluetooth audio output (A2DP or SCO) is currently connected.
     * The UI uses this to warn the user if audio would come out the phone
     * speaker instead of the glasses. Requires no special permission for
     * output-device enumeration.
     */
    fun hasBluetoothAudioRoute(): Boolean {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return devices.any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }
    }

    override suspend fun speak(text: String) {
        if (text.isBlank()) return
        val engine = tts ?: return
        suspendCancellableCoroutine { cont ->
            val id = "glasses-" + text.hashCode()
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    if (cont.isActive) cont.resume(Unit)
                }

                @Deprecated("Required abstract override; superseded by onError(String, Int).")
                override fun onError(utteranceId: String?) {
                    if (cont.isActive) cont.resume(Unit)
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    if (cont.isActive) cont.resume(Unit)
                }
            })
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        }
    }

    override suspend fun cue(cue: Cue) {
        // Minimal spoken earcons. A vendor driver can override these with tones.
        when (cue) {
            Cue.LISTENING -> speak("Listening")
            Cue.THINKING -> Unit
            Cue.DONE -> Unit
            Cue.ERROR -> speak("Sorry")
        }
    }

    override suspend fun disconnect() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isConnected = false
    }
}
