package com.mojo.app.engine.stt

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * MojoSTTEngine — wraps Android's built-in SpeechRecognizer.
 *
 * Phones differ in which languages they can recognise offline, so each
 * listen tries a few setups in order and moves on when the recogniser says
 * the language isn't available (errors 12/13):
 *   1. English (US), offline preferred
 *   2. English (US), online allowed
 *   3. the phone's own default language, online allowed
 */
class MojoSTTEngine(
    private val context: Context,
    private val onResult: (String) -> Unit,
    /** Called (on the main thread) when nothing usable was recognised. */
    private val onError: () -> Unit = {},
) {
    companion object {
        private const val TAG = "MojoSTTEngine"
        // SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED / ERROR_LANGUAGE_UNAVAILABLE (API 31+)
        private const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
        private const val ERROR_LANGUAGE_UNAVAILABLE = 13
        private const val ERROR_SERVER_DISCONNECTED = 11
        /** Give the recognition service time to let go before starting again. */
        private const val RETRY_DELAY_MS = 350L

        private data class Setup(val language: String?, val preferOffline: Boolean)
        private val SETUPS = listOf(
            Setup("en-US", preferOffline = true),
            Setup("en-US", preferOffline = false),
            Setup(null, preferOffline = false),
        )

        fun describe(error: Int): String = when (error) {
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_NETWORK -> "network"
            SpeechRecognizer.ERROR_AUDIO -> "audio"
            SpeechRecognizer.ERROR_SERVER -> "server"
            SpeechRecognizer.ERROR_CLIENT -> "client"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "no speech"
            SpeechRecognizer.ERROR_NO_MATCH -> "no match"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "busy"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "permission"
            ERROR_LANGUAGE_NOT_SUPPORTED -> "language not supported"
            ERROR_LANGUAGE_UNAVAILABLE -> "language unavailable"
            ERROR_SERVER_DISCONNECTED -> "service disconnected"
            else -> "code $error"
        }
    }

    private var recognizer:  SpeechRecognizer? = null
    private var isListening  = false
    private val mainHandler  = Handler(Looper.getMainLooper())
    /** Remembers the first setup that worked, so later listens start there. */
    private var firstSetup   = 0

    fun init(): Boolean {
        val available = SpeechRecognizer.isRecognitionAvailable(context)
        Log.d(TAG, "Speech recognition available: $available")
        return available
    }

    fun startListening() {
        if (isListening) return
        isListening = true
        mainHandler.post { listenWith(firstSetup) }
    }

    private fun listenWith(index: Int) {
        if (!isListening) return
        val setup = SETUPS[index]
        try {
            destroyRecognizer()
            recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { Log.d(TAG, "Ready ($setup)") }
                override fun onBeginningOfSpeech() { Log.d(TAG, "Speaking") }
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    Log.w(TAG, "STT error: ${describe(error)} ($setup)")
                    // Language missing, or the recognition service dropped while we switched
                    // setups (11 = ERROR_SERVER_DISCONNECTED): try the next setup after a short pause.
                    val retryable = error == ERROR_LANGUAGE_NOT_SUPPORTED || error == ERROR_LANGUAGE_UNAVAILABLE ||
                        error == ERROR_SERVER_DISCONNECTED || error == SpeechRecognizer.ERROR_CLIENT ||
                        error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
                    if (retryable && index + 1 < SETUPS.size && isListening) {
                        // A missing language won't appear by itself — skip this setup from now on.
                        if (error == ERROR_LANGUAGE_NOT_SUPPORTED || error == ERROR_LANGUAGE_UNAVAILABLE) {
                            firstSetup = maxOf(firstSetup, index + 1)
                        }
                        destroyRecognizer()
                        mainHandler.postDelayed({ listenWith(index + 1) }, RETRY_DELAY_MS)
                        return
                    }
                    isListening = false
                    this@MojoSTTEngine.onError()
                }
                override fun onResults(results: Bundle?) {
                    isListening = false
                    firstSetup = index
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.trim() ?: ""
                    Log.d(TAG, "STT result: \"$text\"")
                    if (text.isNotBlank()) onResult(text) else this@MojoSTTEngine.onError()
                }
                override fun onPartialResults(partial: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                if (setup.language != null) putExtra(RecognizerIntent.EXTRA_LANGUAGE, setup.language)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, setup.preferOffline)
            }
            recognizer?.startListening(intent)
            Log.d(TAG, "STT listening started ($setup)")
        } catch (e: Exception) {
            Log.e(TAG, "STT start failed: ${e.message}")
            isListening = false
            this@MojoSTTEngine.onError()
        }
    }

    private fun destroyRecognizer() {
        try { recognizer?.cancel(); recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
    }

    fun stopListening() {
        isListening = false
        mainHandler.post {
            try { recognizer?.stopListening(); recognizer?.destroy(); recognizer = null }
            catch (e: Exception) { Log.e(TAG, "STT stop: ${e.message}") }
        }
    }

    fun transcribe(audio: FloatArray): String = ""
}
