package com.mojo.app.engine.tts
import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class MojoTTSEngine(private val context: Context) : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var ready = false
    /** onDone callbacks by utterance id; called on a TTS binder thread. */
    private val pending = ConcurrentHashMap<String, () -> Unit>()

    fun init() { tts = TextToSpeech(context, this) }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.setLanguage(Locale("en", "IN"))
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { finish(utteranceId) }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { finish(utteranceId) }
                override fun onError(utteranceId: String?, errorCode: Int) { finish(utteranceId) }
                override fun onStop(utteranceId: String?, interrupted: Boolean) { finish(utteranceId) }
            })
            ready = true
        }
    }

    private fun finish(utteranceId: String?) {
        if (utteranceId != null) pending.remove(utteranceId)?.invoke()
    }

    /**
     * Speaks [text]. [onDone] runs when speech ends (or right away if TTS
     * isn't ready) — possibly on a background thread.
     */
    fun speak(text: String, onDone: (() -> Unit)? = null) {
        val engine = tts
        if (!ready || engine == null) { onDone?.invoke(); return }
        val id = UUID.randomUUID().toString()
        if (onDone != null) pending[id] = onDone
        if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) finish(id)
    }

    fun shutdown() { tts?.stop(); tts?.shutdown(); tts = null; pending.clear() }
    fun recordPhrase(phrase: String, durationMs: Int = 2000, onDone: (Boolean) -> Unit) { onDone(false) }
    companion object { private const val TAG = "MojoTTSEngine" }
}
