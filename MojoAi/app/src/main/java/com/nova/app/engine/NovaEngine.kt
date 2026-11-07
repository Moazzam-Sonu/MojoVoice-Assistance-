package com.nova.app.engine

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.nova.app.commands.CommandExecutor
import com.nova.app.core.NovaApplication
import com.nova.app.db.CommandHistoryEntity
import com.nova.app.engine.nlu.NovaIntentEngine
import com.nova.app.engine.stt.NovaSTTEngine
import com.nova.app.engine.tts.NovaTTSEngine
import com.nova.app.engine.wake.NovaWakeWordEngine
import com.nova.app.ui.home.MainActivity
import kotlinx.coroutines.*

class NovaEngine : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var wakeEngine:   NovaWakeWordEngine
    private lateinit var sttEngine:    NovaSTTEngine
    private lateinit var intentEngine: NovaIntentEngine
    private lateinit var ttsEngine:    NovaTTSEngine
    private lateinit var executor:     CommandExecutor

    private var wakeLock: PowerManager.WakeLock? = null
    private var isListening = false

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "NovaEngine starting")
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("NOVA ready — say your wake word"))
        acquireWakeLock()
        initEngines()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> wakeEngine.start()
            ACTION_STOP  -> wakeEngine.stop()
        }
        return START_STICKY
    }

    private fun initEngines() {
        // TTS and intent engine are thread-safe
        ttsEngine    = NovaTTSEngine(this).also { it.init() }
        intentEngine = NovaIntentEngine(this).also { it.init() }
        executor     = CommandExecutor(this)

        // STT result callback — fires on main thread from SpeechRecognizer
        sttEngine = NovaSTTEngine(this) { recognisedText ->
            if (isListening && recognisedText.isNotBlank()) {
                handleText(recognisedText)
            }
        }
        sttEngine.init()

        // Wake engine runs on IO thread
        wakeEngine = NovaWakeWordEngine(this) {
            // Called from IO coroutine — post to main for STT
            mainHandler.post { onWakeWordDetected() }
        }

        val modelReady = wakeEngine.init()
        if (modelReady) {
            wakeEngine.start()
            updateNotification("NOVA listening — say your wake word")
        } else {
            updateNotification("NOVA — open app to train wake word first")
        }
        Log.d(TAG, "All engines initialised. Model ready: $modelReady")
    }

    private fun onWakeWordDetected() {
        Log.d(TAG, "Wake word detected")
        isListening = true
        updateNotification("NOVA listening...")
        ttsEngine.speak("yes")
        sttEngine.startListening()

        // 8 second timeout
        mainHandler.postDelayed({
            if (isListening) {
                isListening = false
                sttEngine.stopListening()
                updateNotification("NOVA listening — say your wake word")
            }
        }, 8000)
    }

    private fun handleText(text: String) {
        isListening = false
        sttEngine.stopListening()
        Log.d(TAG, "Command: $text")

        scope.launch {
            val command = intentEngine.parse(text)
            val result  = withContext(Dispatchers.Main) { executor.execute(command, text) }
            ttsEngine.speak(result.feedback)
            updateNotification("Last: \"${text.take(40)}\"")
            try {
                NovaApplication.instance.database.commandHistoryDao().insert(
                    CommandHistoryEntity(commandText = text, response = result.feedback,
                        timestamp = System.currentTimeMillis(), success = result.success)
                )
            } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        wakeEngine.stop()
        sttEngine.stopListening()
        ttsEngine.shutdown()
        wakeLock?.release()
        scope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel(CHANNEL_ID, "NOVA Engine", NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
                .also { getSystemService(NotificationManager::class.java).createNotificationChannel(it) }
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NOVA").setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi).setOngoing(true).setSilent(true).build()
    }

    private fun updateNotification(text: String) =
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))

    private fun acquireWakeLock() {
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Nova::WakeLock")
            .also { it.acquire(24 * 60 * 60 * 1000L) }
    }

    companion object {
        private const val TAG        = "NovaEngine"
        private const val NOTIF_ID   = 1002
        private const val CHANNEL_ID = "nova_engine_channel"
        const val ACTION_START = "com.nova.app.ENGINE_START"
        const val ACTION_STOP  = "com.nova.app.ENGINE_STOP"
    }
}