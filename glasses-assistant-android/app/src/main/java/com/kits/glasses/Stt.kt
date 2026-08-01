package com.kits.glasses

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * v1 speech-to-text using Android's built-in [SpeechRecognizer].
 *
 * The recognizer captures the microphone ITSELF via an Intent, so for v1 the
 * driver must NOT open a SCO session during recognition — SpeechRecognizer owns
 * the mic for the duration of [listenOnce], then releases it.
 *
 * SpeechRecognizer must be created and driven on the main thread, so the whole
 * callback dance runs under Dispatchers.Main, wrapped in a cancellable
 * coroutine that resumes exactly once with the best transcript (or "").
 */
object Stt {

    suspend fun listenOnce(context: Context): String = withContext(Dispatchers.Main) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            return@withContext ""
        }

        suspendCancellableCoroutine { cont ->
            val recognizer = SpeechRecognizer.createSpeechRecognizer(context)

            var resumed = false
            fun finish(result: String) {
                if (resumed) return
                resumed = true
                try {
                    recognizer.destroy()
                } catch (_: Exception) {
                }
                if (cont.isActive) cont.resume(result)
            }

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val list = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    finish(list?.firstOrNull()?.trim().orEmpty())
                }

                override fun onError(error: Int) = finish("")
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            cont.invokeOnCancellation {
                try {
                    recognizer.destroy()
                } catch (_: Exception) {
                }
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            }
            recognizer.startListening(intent)
        }
    }
}
