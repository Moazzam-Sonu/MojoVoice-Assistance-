package com.mojo.app.engine.wake

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import com.mojo.app.engine.audio.AudioEngine
import com.mojo.app.engine.math.Matrix
import com.mojo.app.engine.nn.NeuralNetwork
import kotlinx.coroutines.*
import java.io.File
import kotlin.math.*

/**
 * MojoWakeWordEngine
 *
 * Only 3 recordings needed.
 * Data augmentation generates 20+ synthetic variants automatically:
 *   - pitch shift up/down
 *   - speed stretch/compress
 *   - background noise injection
 *   - volume variation
 *   - time shift
 */
class MojoWakeWordEngine(
    private val context: Context,
    private val onWakeWordDetected: () -> Unit
) {
    companion object {
        private const val TAG            = "MojoWakeWordEngine"
        private const val SAMPLE_RATE    = 16000
        private const val WINDOW_FRAMES  = 30
        private const val MFCC_SIZE      = 13
        private const val INPUT_SIZE     = WINDOW_FRAMES * MFCC_SIZE
        private const val THRESHOLD      = 0.78f
        private const val COOLDOWN_MS    = 1500L
        const val MIN_SAMPLES = 3   // Only 3 recordings needed
    }

    private val audioEngine = AudioEngine()
    private var network: NeuralNetwork? = null
    private var isRunning   = false
    private var listenJob: Job? = null
    private val frameWindow = ArrayDeque<FloatArray>(WINDOW_FRAMES)
    private var lastTrigger = 0L

    // ── Init ──────────────────────────────────────────────

    fun init(): Boolean {
        return try {
            val modelFile = File(context.filesDir, "models/wake_model.mojo")
            if (modelFile.exists()) {
                // Only keep a network once real trained weights are loaded —
                // an untrained (random) network would fire randomly.
                network = NeuralNetwork(NeuralNetwork.NetworkConfig(
                    inputSize        = INPUT_SIZE,
                    hiddenSizes      = listOf(128, 64),
                    outputSize       = 2,
                    activation       = "relu",
                    outputActivation = "softmax"
                )).also { it.loadWeights(modelFile.absolutePath) }
                Log.d(TAG, "Wake model loaded")
                true
            } else {
                Log.w(TAG, "No model yet — record $MIN_SAMPLES samples and train")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Init: ${e.message}")
            false
        }
    }

    // ── Listen ────────────────────────────────────────────

    val isActive: Boolean get() = isRunning

    fun start() {
        if (isRunning || network == null) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "RECORD_AUDIO not granted — cannot listen")
            return
        }
        isRunning = true
        audioEngine.startCapture()
        listenJob = CoroutineScope(Dispatchers.IO).launch {
            while (isRunning) {
                val frame = audioEngine.readFrame()
                val mfcc  = audioEngine.extractMFCC(frame)
                frameWindow.addLast(mfcc)
                if (frameWindow.size > WINDOW_FRAMES) frameWindow.removeFirst()
                if (frameWindow.size < WINDOW_FRAMES) continue
                val features = FloatArray(INPUT_SIZE)
                frameWindow.forEachIndexed { i, f -> f.copyInto(features, i * MFCC_SIZE) }
                val conf = network?.predictConfidence(features) ?: continue
                val now  = System.currentTimeMillis()
                if (conf >= THRESHOLD && (now - lastTrigger) > COOLDOWN_MS) {
                    lastTrigger = now
                    Log.d(TAG, "Wake! conf=${"%.2f".format(conf)}")
                    withContext(Dispatchers.Main) { onWakeWordDetected() }
                }
            }
        }
    }

    fun stop() {
        isRunning = false
        listenJob?.cancel()
        audioEngine.stopCapture()
    }

    // ── Record one sample ─────────────────────────────────

    fun recordTrainingSample(
        isPositive:  Boolean,
        sampleIndex: Int,
        durationMs:  Int = 1500,
        onDone:      (Boolean) -> Unit
    ) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            onDone(false); return
        }

        CoroutineScope(Dispatchers.IO).launch {
            val bufSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(SAMPLE_RATE * 2)

            var recorder: AudioRecord? = null
            try {
                recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize
                )
                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    withContext(Dispatchers.Main) { onDone(false) }
                    return@launch
                }

                val totalSamples = SAMPLE_RATE * durationMs / 1000
                val pcm = ShortArray(totalSamples)
                recorder.startRecording()
                var offset = 0
                while (offset < totalSamples) {
                    val read = recorder.read(pcm, offset, totalSamples - offset)
                    if (read < 0) break
                    offset += read
                }
                recorder.stop()

                val dir = File(context.filesDir,
                    if (isPositive) "training/wake/positive" else "training/wake/negative"
                ).also { it.mkdirs() }

                val bytes = ByteArray(pcm.size * 2)
                for (i in pcm.indices) {
                    bytes[i * 2]     = (pcm[i].toInt() and 0xFF).toByte()
                    bytes[i * 2 + 1] = (pcm[i].toInt() shr 8).toByte()
                }
                File(dir, "sample_$sampleIndex.pcm").writeBytes(bytes)
                Log.d(TAG, "Saved sample $sampleIndex")
                withContext(Dispatchers.Main) { onDone(true) }
            } catch (e: Exception) {
                Log.e(TAG, "Record error: ${e.message}")
                withContext(Dispatchers.Main) { onDone(false) }
            } finally {
                try { recorder?.release() } catch (_: Exception) {}
            }
        }
    }

    // ── Train with augmentation ───────────────────────────

    fun trainOnDevice(
        epochs:     Int   = 60,
        lr:         Float = 0.001f,
        onProgress: (Int, Float) -> Unit,
        onComplete: (Boolean) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val posDir   = File(context.filesDir, "training/wake/positive")
                val posFiles = posDir.listFiles()?.filter { it.extension == "pcm" } ?: emptyList()

                if (posFiles.isEmpty()) {
                    withContext(Dispatchers.Main) { onComplete(false) }
                    return@launch
                }

                Log.d(TAG, "Training with ${posFiles.size} recording(s) + augmentation")

                // Load real recordings
                val rawSamples = posFiles.mapNotNull { loadPcm(it) }

                // Build dataset with augmentation
                val trainX = mutableListOf<FloatArray>()
                val trainY = mutableListOf<FloatArray>()

                // Positive: original + augmented variants
                for (pcm in rawSamples) {
                    // Original
                    toFeatures(pcm)?.let { trainX.add(it); trainY.add(floatArrayOf(0f, 1f)) }

                    // Augmented variants — 7 per recording
                    augment(pcm).forEach { aug ->
                        toFeatures(aug)?.let { trainX.add(it); trainY.add(floatArrayOf(0f, 1f)) }
                    }
                }

                // Negatives: synthetic noise (2× positive count)
                val negCount = trainX.size * 2
                repeat(negCount) {
                    val noise = generateNoise(SAMPLE_RATE)
                    toFeatures(noise)?.let { trainX.add(it); trainY.add(floatArrayOf(1f, 0f)) }
                }

                Log.d(TAG, "Dataset: ${trainX.size} samples (${trainX.size - negCount} pos, $negCount neg)")

                // Train
                val net = NeuralNetwork(NeuralNetwork.NetworkConfig(
                    inputSize = INPUT_SIZE, hiddenSizes = listOf(128, 64),
                    outputSize = 2, activation = "relu", outputActivation = "softmax"
                ))

                repeat(epochs) { epoch ->
                    var loss = 0f
                    trainX.indices.shuffled().forEach { i ->
                        loss += net.trainStep(Matrix.columnVector(trainX[i]), Matrix.columnVector(trainY[i]), lr)
                    }
                    withContext(Dispatchers.Main) { onProgress(epoch + 1, loss / trainX.size) }
                }

                // Save
                File(context.filesDir, "models").mkdirs()
                net.saveWeights(File(context.filesDir, "models/wake_model.mojo").absolutePath)
                network = net
                Log.d(TAG, "Training complete")
                withContext(Dispatchers.Main) { onComplete(true) }

            } catch (e: Exception) {
                Log.e(TAG, "Training failed: ${e.message}")
                withContext(Dispatchers.Main) { onComplete(false) }
            }
        }
    }

    // ── Data augmentation ─────────────────────────────────

    /**
     * Generates 7 augmented variants from one recording.
     * So 3 recordings → 3 + (3×7) = 24 positive training samples.
     */
    private fun augment(pcm: FloatArray): List<FloatArray> = listOf(
        pitchShift(pcm, 1.1f),       // pitch up slightly
        pitchShift(pcm, 0.9f),       // pitch down slightly
        timeStretch(pcm, 1.1f),      // speak slightly slower
        timeStretch(pcm, 0.9f),      // speak slightly faster
        addNoise(pcm, 0.005f),       // light background noise
        addNoise(pcm, 0.015f),       // medium background noise
        volumeScale(pcm, 0.6f)       // quieter version
    )

    /** Simple pitch shift via resampling */
    private fun pitchShift(pcm: FloatArray, factor: Float): FloatArray {
        val newLen = (pcm.size / factor).toInt().coerceIn(1, pcm.size * 2)
        return FloatArray(pcm.size) { i ->
            val srcIdx = (i * factor).toInt().coerceIn(0, pcm.size - 1)
            pcm[srcIdx]
        }
    }

    /** Time stretch via linear interpolation */
    private fun timeStretch(pcm: FloatArray, factor: Float): FloatArray {
        val newLen = (pcm.size * factor).toInt().coerceIn(1, pcm.size * 3)
        return FloatArray(newLen) { i ->
            val srcPos = i / factor
            val idx    = srcPos.toInt().coerceIn(0, pcm.size - 2)
            val frac   = srcPos - idx
            pcm[idx] * (1f - frac) + pcm[idx + 1] * frac
        }.let { stretched ->
            // Trim or pad back to original length
            if (stretched.size >= pcm.size)
                stretched.copyOf(pcm.size)
            else
                FloatArray(pcm.size).also { stretched.copyInto(it) }
        }
    }

    /** Add Gaussian noise */
    private fun addNoise(pcm: FloatArray, level: Float): FloatArray =
        FloatArray(pcm.size) { i ->
            (pcm[i] + (Math.random() * 2 - 1).toFloat() * level).coerceIn(-1f, 1f)
        }

    /** Scale volume */
    private fun volumeScale(pcm: FloatArray, scale: Float): FloatArray =
        FloatArray(pcm.size) { i -> (pcm[i] * scale).coerceIn(-1f, 1f) }

    /** Generate random noise sample (used as negative training data) */
    private fun generateNoise(length: Int): FloatArray =
        FloatArray(length) { (Math.random() * 0.1 - 0.05).toFloat() }

    // ── Helpers ───────────────────────────────────────────

    private fun loadPcm(file: File): FloatArray? = try {
        val bytes = file.readBytes()
        FloatArray(bytes.size / 2) { i ->
            val s = (bytes[i * 2].toInt() and 0xFF) or (bytes[i * 2 + 1].toInt() shl 8)
            s.toShort() / 32768f
        }
    } catch (_: Exception) { null }

    private fun toFeatures(pcm: FloatArray): FloatArray? {
        return try {
            val frames = audioEngine.extractMFCCSequence(pcm)
            if (frames.size < WINDOW_FRAMES) return null
            val window = frames.takeLast(WINDOW_FRAMES)
            FloatArray(INPUT_SIZE).also { feat ->
                window.forEachIndexed { i, f -> f.copyInto(feat, i * MFCC_SIZE) }
            }
        } catch (_: Exception) { null }
    }
}