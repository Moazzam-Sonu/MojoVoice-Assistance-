package com.mojo.app.engine.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlin.math.*

class AudioEngine {
    companion object {
        const val SAMPLE_RATE  = 16000
        const val FRAME_SIZE   = 512
        const val HOP_SIZE     = 160
        const val NUM_MEL_BINS = 40
        const val NUM_MFCC     = 13
        const val NUM_FFT      = 512
        private const val PRE_EMPHASIS  = 0.97f
        private const val VAD_THRESHOLD = 0.02f
        private const val TAG = "AudioEngine"
    }

    private var audioRecord: AudioRecord? = null
    private var isCapturing = false

    fun startCapture() {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, FRAME_SIZE * 4))
        audioRecord?.startRecording()
        isCapturing = true
    }

    fun stopCapture() {
        isCapturing = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    fun readFrame(): FloatArray {
        val pcm = ShortArray(FRAME_SIZE)
        audioRecord?.read(pcm, 0, FRAME_SIZE)
        return FloatArray(FRAME_SIZE) { i -> pcm[i] / 32768f }
    }

    private fun preEmphasis(samples: FloatArray): FloatArray {
        val out = FloatArray(samples.size)
        out[0] = samples[0]
        for (i in 1 until samples.size) out[i] = samples[i] - PRE_EMPHASIS * samples[i - 1]
        return out
    }

    private fun hammingWindow(frame: FloatArray): FloatArray {
        val n = frame.size
        return FloatArray(n) { i ->
            frame[i] * (0.54f - 0.46f * cos(2.0 * PI * i / (n - 1)).toFloat())
        }
    }

    private fun fft(real: FloatArray): FloatArray {
        val n = real.size
        require(n > 0 && n and (n - 1) == 0) { "FFT size must be power of 2" }
        val re = real.copyOf(); val im = FloatArray(n)
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wRe = cos(ang).toFloat(); val wIm = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var curRe = 1f; var curIm = 0f
                for (k in 0 until len / 2) {
                    val uRe = re[i+k]; val uIm = im[i+k]
                    val vRe = re[i+k+len/2]*curRe - im[i+k+len/2]*curIm
                    val vIm = re[i+k+len/2]*curIm + im[i+k+len/2]*curRe
                    re[i+k] = uRe+vRe; im[i+k] = uIm+vIm
                    re[i+k+len/2] = uRe-vRe; im[i+k+len/2] = uIm-vIm
                    val nRe = curRe*wRe - curIm*wIm; curIm = curRe*wIm + curIm*wRe; curRe = nRe
                }
                i += len
            }
            len = len shl 1
        }
        return FloatArray(n * 2) { i -> if (i % 2 == 0) re[i/2] else im[i/2] }
    }

    private fun powerSpectrum(fftOut: FloatArray): FloatArray {
        val n = fftOut.size / 2
        return FloatArray(n / 2 + 1) { i ->
            val re = fftOut[i*2]; val im = fftOut[i*2+1]; re*re + im*im
        }
    }

    private fun hzToMel(hz: Float) = 2595f * log10(1f + hz / 700f)
    private fun melToHz(mel: Float) = 700f * (10f.pow(mel / 2595f) - 1f)

    private val melFilterbank: Array<FloatArray> by lazy {
        val bins = NUM_FFT / 2 + 1
        val low  = hzToMel(20f); val high = hzToMel(SAMPLE_RATE / 2f)
        val pts  = FloatArray(NUM_MEL_BINS + 2) { i -> melToHz(low + i * (high - low) / (NUM_MEL_BINS + 1)) }
        val bpts = IntArray(pts.size) { i -> ((pts[i] / (SAMPLE_RATE / 2f)) * bins).toInt().coerceIn(0, bins - 1) }
        Array(NUM_MEL_BINS) { m ->
            FloatArray(bins) { k ->
                when {
                    k < bpts[m]    -> 0f
                    k <= bpts[m+1] -> (k - bpts[m]).toFloat() / (bpts[m+1] - bpts[m] + 1)
                    k <= bpts[m+2] -> (bpts[m+2] - k).toFloat() / (bpts[m+2] - bpts[m+1] + 1)
                    else           -> 0f
                }
            }
        }
    }

    private fun melEnergies(powerSpec: FloatArray): FloatArray =
        FloatArray(NUM_MEL_BINS) { m ->
            val energy = melFilterbank[m].indices.sumOf { k ->
                (melFilterbank[m][k] * (if (k < powerSpec.size) powerSpec[k] else 0f)).toDouble()
            }.toFloat()
            ln(maxOf(energy, 1e-10f))
        }

    private fun dct(input: FloatArray, numCoeffs: Int): FloatArray {
        val n = input.size; val scale = sqrt(2f / n)
        return FloatArray(numCoeffs) { k ->
            scale * input.indices.sumOf { m ->
                (input[m] * cos(PI * k * (2 * m + 1) / (2.0 * n))).toDouble()
            }.toFloat()
        }
    }

    fun extractMFCC(frame: FloatArray): FloatArray {
        val padded = FloatArray(NUM_FFT)
        frame.copyInto(padded, 0, 0, minOf(frame.size, NUM_FFT))
        val spectrum = fft(hammingWindow(preEmphasis(padded)))
        val power    = powerSpectrum(spectrum)
        val mel      = melEnergies(power)
        return dct(mel, NUM_MFCC)
    }

    fun extractMFCCSequence(audio: FloatArray): List<FloatArray> {
        val frames = mutableListOf<FloatArray>()
        var offset = 0
        while (offset + FRAME_SIZE <= audio.size) {
            frames.add(extractMFCC(audio.sliceArray(offset until offset + FRAME_SIZE)))
            offset += HOP_SIZE
        }
        return frames
    }

    fun isVoiceActive(frame: FloatArray): Boolean {
        val energy = frame.map { it * it }.average().toFloat()
        return sqrt(energy) > VAD_THRESHOLD
    }
}