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
 * MojoWakeWordEngine — on-device wake word detection.
 *
 * Features: 13 MFCCs every 20 ms over a ~0.8 s window (40 frames), normalised
 * per window (CMVN) so mic loudness doesn't matter. Training and live listening
 * use exactly the same pipeline.
 *
 * Training data from just a few recordings:
 *  - positives: the loudest 0.8 s of each recording (where the word is), small
 *    time shifts, and pitch / speed / noise / volume variants
 *  - negatives: several kinds of noise, the word played backwards, and windows
 *    where the word is cut in half — so MOJO learns the *whole* word, not "any sound"
 *
 * Live listening only runs the network when there is sound, and fires when
 * the averaged confidence of the last 3 predictions passes the threshold.
 */
class MojoWakeWordEngine(
    private val context: Context,
    private val onWakeWordDetected: () -> Unit
) {
    companion object {
        private const val TAG           = "MojoWakeWordEngine"
        private const val SAMPLE_RATE   = 16000
        private const val HOP           = 320            // 20 ms
        private const val WINDOW_FRAMES = 40             // ≈ 0.8 s
        private const val MFCC_SIZE     = 13
        private const val INPUT_SIZE    = WINDOW_FRAMES * MFCC_SIZE
        private val HIDDEN              = listOf(96, 32)
        private const val THRESHOLD     = 0.85f
        private const val SMOOTHING     = 3              // predictions averaged
        private const val PREDICT_EVERY = 2              // hops between predictions (40 ms)
        private const val VAD_RMS       = 0.012f         // skip the network in silence
        private const val COOLDOWN_MS   = 2000L
        const val RECORD_MS             = 2000
        const val MIN_SAMPLES           = 3
        /** v2: new feature pipeline; old v1 models are ignored. */
        const val MODEL_PATH            = "models/wake_model_v2.mojo"
        private const val POS_DIR       = "training/wake/positive"

        fun isTrained(context: Context) = File(context.filesDir, MODEL_PATH).exists()
    }

    private val audioEngine = AudioEngine()
    private var network: NeuralNetwork? = null
    @Volatile private var isRunning = false
    private var listenJob: Job? = null
    private var lastTrigger = 0L

    val isActive: Boolean get() = isRunning

    private fun newNetwork() = NeuralNetwork(NeuralNetwork.NetworkConfig(
        inputSize = INPUT_SIZE, hiddenSizes = HIDDEN, outputSize = 2,
        activation = "relu", outputActivation = "softmax"
    ))

    // ── Init ──────────────────────────────────────────────

    fun init(): Boolean {
        return try {
            val modelFile = File(context.filesDir, MODEL_PATH)
            if (modelFile.exists()) {
                // Only keep a network once real trained weights are loaded —
                // an untrained (random) network would fire randomly.
                network = newNetwork().also { it.loadWeights(modelFile.absolutePath) }
                Log.d(TAG, "Wake model loaded")
                true
            } else {
                network = null
                Log.w(TAG, "No model yet — record $MIN_SAMPLES samples and train")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Init: ${e.message}")
            network = null
            false
        }
    }

    // ── Listen ────────────────────────────────────────────

    fun start() {
        val net = network ?: return
        if (isRunning) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "RECORD_AUDIO not granted — cannot listen")
            return
        }
        isRunning = true
        audioEngine.startCapture()
        listenJob = CoroutineScope(Dispatchers.Default).launch {
            val ring   = FloatArray(AudioEngine.FRAME_SIZE)   // last 512 samples
            val frames = ArrayDeque<FloatArray>(WINDOW_FRAMES)
            val rms    = ArrayDeque<Float>(WINDOW_FRAMES)
            val recent = ArrayDeque<Float>(SMOOTHING)
            var hops = 0
            while (isRunning) {   // stop() ends capture, which also unblocks readSamples()
                val chunk = audioEngine.readSamples(HOP)
                if (!isRunning) break
                // slide the 512-sample analysis frame forward by one hop
                System.arraycopy(ring, HOP, ring, 0, ring.size - HOP)
                System.arraycopy(chunk, 0, ring, ring.size - HOP, HOP)

                frames.addLast(audioEngine.extractMFCC(ring))
                rms.addLast(sqrt(chunk.fold(0f) { a, s -> a + s * s } / HOP))
                if (frames.size > WINDOW_FRAMES) { frames.removeFirst(); rms.removeFirst() }
                if (frames.size < WINDOW_FRAMES) continue
                if (++hops % PREDICT_EVERY != 0) continue

                if ((rms.maxOrNull() ?: 0f) < VAD_RMS) { recent.clear(); continue }

                val conf = net.predict(window(frames))[1]
                recent.addLast(conf)
                if (recent.size > SMOOTHING) recent.removeFirst()
                val avg = recent.average().toFloat()
                val now = System.currentTimeMillis()
                if (recent.size == SMOOTHING && avg >= THRESHOLD && now - lastTrigger > COOLDOWN_MS) {
                    lastTrigger = now
                    recent.clear()
                    Log.d(TAG, "Wake! conf=${"%.2f".format(avg)}")
                    withContext(Dispatchers.Main) { onWakeWordDetected() }
                }
            }
        }
    }

    fun stop() {
        isRunning = false
        listenJob?.cancel()
        listenJob = null
        audioEngine.stopCapture()
    }

    // ── Record one sample ─────────────────────────────────

    /** Deletes earlier recordings so a fresh set can be made. */
    fun clearSamples() {
        File(context.filesDir, POS_DIR).listFiles()?.forEach { it.delete() }
    }

    fun recordTrainingSample(
        isPositive:  Boolean,
        sampleIndex: Int,
        durationMs:  Int = RECORD_MS,
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

                // Reject silent takes (mic busy, or nothing was said)
                val peak = pcm.maxOf { abs(it.toInt()) } / 32768f
                if (peak < 0.02f) {
                    Log.w(TAG, "Sample $sampleIndex too quiet (peak=$peak)")
                    withContext(Dispatchers.Main) { onDone(false) }
                    return@launch
                }

                val dir = File(context.filesDir,
                    if (isPositive) POS_DIR else "training/wake/negative"
                ).also { it.mkdirs() }

                val bytes = ByteArray(pcm.size * 2)
                for (i in pcm.indices) {
                    bytes[i * 2]     = (pcm[i].toInt() and 0xFF).toByte()
                    bytes[i * 2 + 1] = (pcm[i].toInt() shr 8).toByte()
                }
                File(dir, "sample_$sampleIndex.pcm").writeBytes(bytes)
                Log.d(TAG, "Saved sample $sampleIndex (peak=$peak)")
                withContext(Dispatchers.Main) { onDone(true) }
            } catch (e: Exception) {
                Log.e(TAG, "Record error: ${e.message}")
                withContext(Dispatchers.Main) { onDone(false) }
            } finally {
                try { recorder?.release() } catch (_: Exception) {}
            }
        }
    }

    // ── Train ─────────────────────────────────────────────

    fun trainOnDevice(
        epochs:     Int   = 40,
        lr:         Float = 0.001f,
        onProgress: (Int, Float) -> Unit,
        onComplete: (Boolean) -> Unit
    ) {
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val posFiles = File(context.filesDir, POS_DIR).listFiles()
                    ?.filter { it.extension == "pcm" } ?: emptyList()
                if (posFiles.isEmpty()) {
                    withContext(Dispatchers.Main) { onComplete(false) }
                    return@launch
                }

                val pos = mutableListOf<FloatArray>()
                val neg = mutableListOf<FloatArray>()

                for (raw in posFiles.mapNotNull { loadPcm(it) }) {
                    // Positives: original + variants, each at a few small time shifts
                    for (variant in listOf(raw) + augment(raw)) {
                        val f = mfccFrames(variant)
                        val at = loudestWindow(f) ?: continue
                        for (shift in listOf(-3, 0, 3)) windowAt(f, at + shift)?.let { pos += it }
                    }
                    // Hard negatives: the same word backwards, and half-cut words
                    val f = mfccFrames(raw)
                    val at = loudestWindow(f)
                    mfccFrames(raw.reversedArray()).let { r -> loudestWindow(r)?.let { i -> windowAt(r, i)?.let { neg += it } } }
                    if (at != null) {
                        windowAt(f, at - WINDOW_FRAMES / 2)?.let { neg += it }
                        windowAt(f, at + WINDOW_FRAMES / 2)?.let { neg += it }
                    }
                }
                // Noise negatives (white / hum / rumble at different levels)
                val windowSamples = (WINDOW_FRAMES - 1) * HOP + AudioEngine.FRAME_SIZE
                while (neg.size < pos.size * 2) {
                    val noise = generateNoise(windowSamples, neg.size % 3, 0.003f + 0.05f * Random.nextFloat())
                    windowAt(mfccFrames(noise), 0)?.let { neg += it }
                }

                Log.d(TAG, "Dataset: ${pos.size} positive, ${neg.size} negative")
                val data = pos.map { it to floatArrayOf(0f, 1f) } + neg.map { it to floatArrayOf(1f, 0f) }

                val net = newNetwork()
                repeat(epochs) { epoch ->
                    var loss = 0f
                    data.shuffled().forEach { (x, y) ->
                        loss += net.trainStep(Matrix.columnVector(x), Matrix.columnVector(y), lr)
                    }
                    val avg = loss / data.size
                    withContext(Dispatchers.Main) { onProgress(epoch + 1, avg) }
                }

                File(context.filesDir, "models").mkdirs()
                net.saveWeights(File(context.filesDir, MODEL_PATH).absolutePath)
                network = net
                Log.d(TAG, "Training complete")
                withContext(Dispatchers.Main) { onComplete(true) }
            } catch (e: Exception) {
                Log.e(TAG, "Training failed: ${e.message}")
                withContext(Dispatchers.Main) { onComplete(false) }
            }
        }
    }

    // ── Features ──────────────────────────────────────────

    private fun mfccFrames(pcm: FloatArray): List<FloatArray> =
        audioEngine.extractMFCCSequence(pcm, HOP)

    /** Start index of the 40-frame window with the most energy (MFCC c0), i.e. where the word is. */
    private fun loudestWindow(frames: List<FloatArray>): Int? {
        if (frames.size < WINDOW_FRAMES) return null
        var sum = 0f
        for (i in 0 until WINDOW_FRAMES) sum += frames[i][0]
        var best = sum
        var bestAt = 0
        for (i in WINDOW_FRAMES until frames.size) {
            sum += frames[i][0] - frames[i - WINDOW_FRAMES][0]
            if (sum > best) { best = sum; bestAt = i - WINDOW_FRAMES + 1 }
        }
        return bestAt
    }

    private fun windowAt(frames: List<FloatArray>, start: Int): FloatArray? {
        if (start < 0 || start + WINDOW_FRAMES > frames.size) return null
        return window(frames.subList(start, start + WINDOW_FRAMES))
    }

    /** Flattens 40 MFCC frames into the network input, with per-window mean/variance normalisation. */
    private fun window(frames: Collection<FloatArray>): FloatArray {
        val mean = FloatArray(MFCC_SIZE)
        val sq = FloatArray(MFCC_SIZE)
        for (f in frames) for (c in 0 until MFCC_SIZE) { mean[c] += f[c]; sq[c] += f[c] * f[c] }
        val n = frames.size.toFloat()
        val std = FloatArray(MFCC_SIZE) { c ->
            val m = mean[c] / n
            mean[c] = m
            sqrt(max(sq[c] / n - m * m, 1e-6f))
        }
        val out = FloatArray(frames.size * MFCC_SIZE)
        frames.forEachIndexed { i, f ->
            for (c in 0 until MFCC_SIZE) out[i * MFCC_SIZE + c] = (f[c] - mean[c]) / std[c]
        }
        return out
    }

    // ── Data augmentation ─────────────────────────────────

    /** Seven variants per recording: pitch up/down, faster/slower, two noise levels, quieter. */
    private fun augment(pcm: FloatArray): List<FloatArray> = listOf(
        resample(pcm, 1.1f),
        resample(pcm, 0.9f),
        timeStretch(pcm, 1.1f),
        timeStretch(pcm, 0.9f),
        addNoise(pcm, 0.005f),
        addNoise(pcm, 0.015f),
        volumeScale(pcm, 0.6f)
    )

    /** Simple pitch+speed change by resampling. */
    private fun resample(pcm: FloatArray, factor: Float): FloatArray =
        FloatArray(pcm.size) { i -> pcm[(i * factor).toInt().coerceIn(0, pcm.size - 1)] }

    /** Time stretch via linear interpolation, trimmed/padded to the original length. */
    private fun timeStretch(pcm: FloatArray, factor: Float): FloatArray {
        val newLen = (pcm.size * factor).toInt().coerceIn(2, pcm.size * 3)
        val stretched = FloatArray(newLen) { i ->
            val srcPos = i / factor
            val idx    = srcPos.toInt().coerceIn(0, pcm.size - 2)
            val frac   = srcPos - idx
            pcm[idx] * (1f - frac) + pcm[idx + 1] * frac
        }
        return if (stretched.size >= pcm.size) stretched.copyOf(pcm.size)
        else FloatArray(pcm.size).also { stretched.copyInto(it) }
    }

    private fun addNoise(pcm: FloatArray, level: Float): FloatArray =
        FloatArray(pcm.size) { i -> (pcm[i] + (Random.nextFloat() * 2 - 1) * level).coerceIn(-1f, 1f) }

    private fun volumeScale(pcm: FloatArray, scale: Float): FloatArray =
        FloatArray(pcm.size) { i -> (pcm[i] * scale).coerceIn(-1f, 1f) }

    /** kind 0 = white noise, 1 = low hum, 2 = rumble (smoothed noise). */
    private fun generateNoise(length: Int, kind: Int, level: Float): FloatArray {
        val out = FloatArray(length)
        var smooth = 0f
        val hz = 50f + Random.nextFloat() * 250f
        for (i in 0 until length) {
            val white = Random.nextFloat() * 2 - 1
            out[i] = level * when (kind) {
                0 -> white
                1 -> sin(2f * PI.toFloat() * hz * i / SAMPLE_RATE) + 0.2f * white
                else -> { smooth = 0.97f * smooth + 0.03f * white; smooth * 6f }
            }
        }
        return out
    }

    private fun loadPcm(file: File): FloatArray? = try {
        val bytes = file.readBytes()
        FloatArray(bytes.size / 2) { i ->
            val s = (bytes[i * 2].toInt() and 0xFF) or (bytes[i * 2 + 1].toInt() shl 8)
            s.toShort() / 32768f
        }
    } catch (_: Exception) { null }
}

private object Random {
    private val rng = kotlin.random.Random(System.nanoTime())
    fun nextFloat() = rng.nextFloat()
}
