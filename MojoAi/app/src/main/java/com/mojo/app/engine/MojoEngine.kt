package com.mojo.app.engine

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.mojo.app.commands.CommandExecutor
import com.mojo.app.commands.CommandType
import com.mojo.app.commands.ParsedCommand
import com.mojo.app.core.MojoApplication
import com.mojo.app.core.MojoStatus
import com.mojo.app.ui.mascot.MojoState
import com.mojo.app.db.CommandHistoryEntity
import com.mojo.app.engine.nlu.MojoIntentEngine
import com.mojo.app.engine.stt.MojoSTTEngine
import com.mojo.app.engine.tts.MojoTTSEngine
import com.mojo.app.engine.wake.MojoWakeWordEngine
import com.mojo.app.ui.home.MainActivity
import kotlinx.coroutines.*

class MojoEngine : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var wakeEngine:   MojoWakeWordEngine
    private lateinit var sttEngine:    MojoSTTEngine
    private lateinit var intentEngine: MojoIntentEngine
    private lateinit var ttsEngine:    MojoTTSEngine
    private lateinit var executor:     CommandExecutor

    private var wakeLock: PowerManager.WakeLock? = null
    private var isListening = false
    private var canRun = true   // false when started without mic permission
    private var wakePaused = false   // true while another screen is recording
    private var conversationId = 0   // ignores stale timeouts from earlier conversations

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "MojoEngine starting")
        createNotificationChannel()
        // Android 14+: a microphone foreground service may only start once
        // RECORD_AUDIO is granted, otherwise startForeground() throws.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "RECORD_AUDIO not granted — stopping engine")
            canRun = false
            stopSelf()
            return
        }
        try {
            ServiceCompat.startForeground(this, NOTIF_ID,
                buildNotification("MOJO ready — say your wake word"),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        } catch (e: Exception) {
            // e.g. started from the background on newer Android versions
            Log.e(TAG, "startForeground failed: ${e.message}")
            canRun = false
            stopSelf()
            return
        }
        acquireWakeLock()
        initEngines()
        MojoStatus.running.value = true
        setMascot(MojoState.IDLE)
    }

    /** Tells the UI what MOJO is doing (mascot state). Safe from any thread. */
    private fun setMascot(state: MojoState) { MojoStatus.state.value = state }

    /** Shows [state] for [ms], then returns to IDLE unless something else happened meanwhile. */
    private fun flashMascot(state: MojoState, ms: Long) {
        setMascot(state)
        mainHandler.postDelayed({ if (MojoStatus.state.value == state) setMascot(MojoState.IDLE) }, ms)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!canRun) return START_NOT_STICKY
        when (intent?.action) {
            ACTION_STOP -> {
                wakeEngine.stop()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PAUSE_WAKE -> {
                // Another screen needs the mic (wake word recording)
                wakePaused = true
                wakeEngine.stop()
                updateNotification("MOJO paused while you record")
            }
            else -> {                       // ACTION_START, or a sticky restart (null intent)
                wakePaused = false
                startWakeListening()
            }
        }
        return START_STICKY
    }

    /** (Re)loads the wake model — it may have just been trained — and starts listening. */
    private fun startWakeListening() {
        if (wakePaused || isListening || wakeEngine.isActive) return
        if (wakeEngine.init()) {
            wakeEngine.start()
            updateNotification("MOJO listening — say your wake word")
        } else {
            updateNotification("MOJO — open app to train wake word first")
        }
    }

    private fun initEngines() {
        // TTS and intent engine are thread-safe
        ttsEngine    = MojoTTSEngine(this).also { it.init() }
        intentEngine = MojoIntentEngine(this).also { it.init() }
        executor     = CommandExecutor(this)

        // STT result callback — fires on main thread from SpeechRecognizer
        sttEngine = MojoSTTEngine(this,
            onResult = { alternatives ->
                if (isListening && alternatives.isNotEmpty()) handleText(alternatives)
            },
            onError = { if (isListening) endConversation("Sorry, I didn't catch that.") },
        )
        sttEngine.init()

        // Wake engine runs on IO thread
        wakeEngine = MojoWakeWordEngine(this) {
            // Called from IO coroutine — post to main for STT
            mainHandler.post { onWakeWordDetected() }
        }

        startWakeListening()
        Log.d(TAG, "All engines initialised. Wake listening: ${wakeEngine.isActive}")
    }

    private fun onWakeWordDetected() {
        Log.d(TAG, "Wake word detected")
        if (isListening) return
        isListening = true
        val conversation = ++conversationId
        // Hand the mic over: the wake engine must let go before speech recognition can hear.
        wakeEngine.stop()
        updateNotification("MOJO listening...")
        setMascot(MojoState.GREETING)        // waves hello…
        mainHandler.postDelayed({            // …then closes its eyes to listen
            if (isListening) setMascot(MojoState.LISTENING)
        }, 700)
        // Start recognising after "yes" so MOJO doesn't hear itself
        ttsEngine.speak("yes") {
            mainHandler.post { if (isListening && conversation == conversationId) sttEngine.startListening() }
        }

        // Safety timeout. Generous: "yes", recogniser fallbacks and online recognition
        // can take several seconds before the user's words come back.
        mainHandler.postDelayed({
            if (isListening && conversation == conversationId) endConversation("I didn't hear a command.")
        }, 15_000)
    }

    /**
     * Stops listening for a command and goes back to waiting for the wake word.
     * With [apology], MOJO says it out loud first so the user isn't left guessing.
     */
    private fun endConversation(apology: String? = null) {
        isListening = false
        sttEngine.stopListening()
        updateNotification("MOJO listening — say your wake word")
        if (apology == null) {
            setMascot(MojoState.IDLE)
            resumeWake()
            return
        }
        MojoStatus.reply.value = apology
        setMascot(MojoState.SPEAKING)
        ttsEngine.speak(apology) {
            mainHandler.post {
                flashMascot(MojoState.FOCUS, 1_500)
                resumeWake()
            }
        }
    }

    /** Gives the mic back to the wake word engine once speech recognition has released it. */
    private fun resumeWake() {
        mainHandler.postDelayed({ startWakeListening() }, 400)
    }

    /**
     * The recogniser returns a few guesses ("open the teams", "open the calls", …).
     * Use the first one that is a real command — for "open X", one whose app is installed.
     */
    private fun pickCommand(alternatives: List<String>): Pair<String, ParsedCommand> {
        val parsed = alternatives.map { it to intentEngine.parse(it) }
        return parsed.firstOrNull { (_, c) ->
            c.type != CommandType.UNKNOWN && (c.type != CommandType.OPEN_APP || executor.findApp(c.param) != null)
        } ?: parsed.firstOrNull { (_, c) -> c.type != CommandType.UNKNOWN }
        ?: parsed.first()
    }

    private fun handleText(alternatives: List<String>) {
        isListening = false
        sttEngine.stopListening()
        val (text, command) = pickCommand(alternatives)
        Log.d(TAG, "Command: $text (from $alternatives)")
        MojoStatus.heard.value = text
        setMascot(MojoState.THINKING)

        scope.launch {
            val result  = withContext(Dispatchers.Main) { executor.execute(command, text) }
            Log.d(TAG, "Parsed: ${command.type} '${command.param}' → ${if (result.success) "OK" else "FAIL"}: ${result.feedback}")
            MojoStatus.reply.value = result.feedback
            setMascot(MojoState.SPEAKING)
            ttsEngine.speak(result.feedback) {
                // Speech finished: celebrate a success, look focused on a failure
                mainHandler.post {
                    flashMascot(if (result.success) MojoState.SUCCESS else MojoState.FOCUS, 2_400)
                    resumeWake()
                }
            }
            updateNotification("Last: \"${text.take(40)}\"")
            try {
                MojoApplication.instance.database.commandHistoryDao().insert(
                    CommandHistoryEntity(commandText = text, response = result.feedback,
                        timestamp = System.currentTimeMillis(), success = result.success)
                )
            } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        MojoStatus.running.value = false
        setMascot(MojoState.SLEEP)
        if (canRun) {
            wakeEngine.stop()
            sttEngine.stopListening()
            ttsEngine.shutdown()
        }
        wakeLock?.let { if (it.isHeld) it.release() }
        scope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel(CHANNEL_ID, "MOJO Engine", NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
                .also { getSystemService(NotificationManager::class.java).createNotificationChannel(it) }
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MOJO").setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi).setOngoing(true).setSilent(true).build()
    }

    private fun updateNotification(text: String) =
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))

    private fun acquireWakeLock() {
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Mojo::WakeLock")
            .also { it.acquire(24 * 60 * 60 * 1000L) }
    }

    companion object {
        private const val TAG        = "MojoEngine"
        private const val NOTIF_ID   = 1002
        private const val CHANNEL_ID = "mojo_engine_channel"
        const val ACTION_START = "com.mojo.app.ENGINE_START"
        const val ACTION_STOP  = "com.mojo.app.ENGINE_STOP"
        const val ACTION_PAUSE_WAKE = "com.mojo.app.ENGINE_PAUSE_WAKE"

        /** Frees the mic (e.g. while recording a wake word). [start] resumes. No-op if not running. */
        fun pauseWake(context: Context) {
            if (!MojoStatus.running.value) return
            context.startService(Intent(context, MojoEngine::class.java).apply { action = ACTION_PAUSE_WAKE })
        }

        /** Starts the engine (or reloads the wake model if it's already running). */
        fun start(context: Context) {
            val i = Intent(context, MojoEngine::class.java).apply { action = ACTION_START }
            ContextCompat.startForegroundService(context, i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MojoEngine::class.java))
        }
    }
}