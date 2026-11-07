package com.nova.app.engine.stt

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
 * NovaSTTEngine — uses Android built-in SpeechRecognizer.
 * 100% offline on phones that have offline speech models (most modern phones).
 * No Vosk, no third-party SDK needed.
 */
class NovaSTTEngine(
    private val context: Context,
    private val onResult: (String) -> Unit
) {
    companion object { private const val TAG = "NovaSTTEngine" }

    private var recognizer:  SpeechRecognizer? = null
    private var isListening  = false
    private val mainHandler  = Handler(Looper.getMainLooper())

    fun init(): Boolean {
        val available = SpeechRecognizer.isRecognitionAvailable(context)
        Log.d(TAG, "Speech recognition available: $available")
        return available
    }

    fun startListening() {
        if (isListening) return
        isListening = true
        mainHandler.post {
            try {
                recognizer?.destroy()
                recognizer = SpeechRecognizer.createSpeechRecognizer(context)
                recognizer?.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) { Log.d(TAG, "Ready") }
                    override fun onBeginningOfSpeech() { Log.d(TAG, "Speaking") }
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() { isListening = false }
                    override fun onError(error: Int) {
                        isListening = false
                        Log.w(TAG, "STT error code: $error")
                    }
                    override fun onResults(results: Bundle?) {
                        isListening = false
                        val text = results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()?.trim() ?: ""
                        Log.d(TAG, "STT result: \"$text\"")
                        if (text.isNotBlank()) onResult(text)
                    }
                    override fun onPartialResults(partial: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                }
                recognizer?.startListening(intent)
                Log.d(TAG, "STT listening started")
            } catch (e: Exception) {
                Log.e(TAG, "STT start failed: ${e.message}")
                isListening = false
            }
        }
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