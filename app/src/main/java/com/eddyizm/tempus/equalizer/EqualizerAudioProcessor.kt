package com.eddyizm.tempus.equalizer

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "EqualizerAudioProcessor"

/**
 * High-performance 5-band DSP Equalizer [AudioProcessor] for ExoPlayer's standard AudioTrack pipeline.
 *
 * Fully unified with Tempus's native C++ DSP engine (dsp_eq.h):
 * - Direct Form II Transposed Biquad Peaking IIR filters
 * - Frequency-weighted progressive soft-curve auto pre-amp
 * - Analog-style soft-knee saturation cushion (tanh)
 * - Accelerated with ARM NEON SIMD instructions
 */
@OptIn(markerClass = [UnstableApi::class])
class EqualizerAudioProcessor private constructor() : BaseAudioProcessor() {

    companion object {
        val BAND_FREQUENCIES_HZ = intArrayOf(60, 230, 910, 3600, 14000)
        const val NUM_BANDS = 5
        private const val MIN_LEVEL_MB = -1500 // -15.0 dB in millibels
        private const val MAX_LEVEL_MB = 1500  // +15.0 dB in millibels

        private var isNativeLoaded = false

        init {
            try {
                System.loadLibrary("directaudio")
                isNativeLoaded = true
                Log.i(TAG, "libdirectaudio.so loaded successfully for EqualizerAudioProcessor (unified dsp_eq.h)")
            } catch (t: Throwable) {
                isNativeLoaded = false
                Log.w(TAG, "libdirectaudio.so not available: ${t.message}")
            }
        }

        @JvmStatic
        private external fun nativeConfigure(sampleRate: Int)

        @JvmStatic
        private external fun nativeSetEnabled(enabled: Boolean)

        @JvmStatic
        private external fun nativeSetBand(band: Int, levelMb: Int)

        @JvmStatic
        private external fun nativeSetBandWeight(band: Int, weight: Double)

        @JvmStatic
        private external fun nativeSetMaxAttenuation(attenDb: Double)

        @JvmStatic
        private external fun nativeSetSoftKneeThreshold(threshold: Double)

        @JvmStatic
        private external fun nativeSetPreampMode(manual: Boolean)

        @JvmStatic
        private external fun nativeSetManualPreamp(db: Double)

        @JvmStatic
        private external fun nativeReset()

        @JvmStatic
        private external fun nativeProcessFloat(
            inBuf: ByteBuffer, inOffset: Int,
            outBuf: ByteBuffer, outOffset: Int,
            numSamples: Int, channelCount: Int
        )

        @JvmStatic
        private external fun nativeProcessInt16(
            inBuf: ByteBuffer, inOffset: Int,
            outBuf: ByteBuffer, outOffset: Int,
            numSamples: Int, channelCount: Int
        )

        @Volatile
        private var instance: EqualizerAudioProcessor? = null

        @JvmStatic
        fun getInstance(): EqualizerAudioProcessor {
            return instance ?: synchronized(this) {
                instance ?: EqualizerAudioProcessor().also { instance = it }
            }
        }
    }

    val numberOfBands: Int get() = NUM_BANDS
    val bandLevelRange: ShortArray get() = shortArrayOf(MIN_LEVEL_MB.toShort(), MAX_LEVEL_MB.toShort())

    private val bandLevels = IntArray(NUM_BANDS) // in millibels (-1500 to +1500)
    private val bandWeights = doubleArrayOf(0.40, 0.60, 0.65, 0.60, 0.40)
    private var maxAttenuationDb: Double = 8.0
    private var softKneeThreshold: Double = 0.70
    private var manualPreampMode: Boolean = false
    private var manualPreampDb: Double = 0.0

    @Volatile
    var isEnabled: Boolean = false
        set(value) {
            field = value
            if (isNativeLoaded) {
                try { nativeSetEnabled(value) } catch (_: Throwable) {}
            }
        }

    private var currentSampleRate: Int = 44100
    private var currentChannelCount: Int = 2
    private var isFlat: Boolean = true
    private var isConfigured: Boolean = false

    fun getCenterFreq(band: Int): Int {
        if (band in 0 until NUM_BANDS) {
            return BAND_FREQUENCIES_HZ[band]
        }
        return 0
    }

    fun getBandLevel(band: Int): Int {
        if (band in 0 until NUM_BANDS) {
            return bandLevels[band]
        }
        return 0
    }

    private fun updateIsFlat() {
        val bandsFlat = bandLevels.all { it == 0 }
        val preampFlat = !manualPreampMode || kotlin.math.abs(manualPreampDb) < 0.05
        isFlat = bandsFlat && preampFlat
    }

    fun setBandLevel(band: Int, levelMb: Int) {
        if (band in 0 until NUM_BANDS) {
            val clamped = levelMb.coerceIn(MIN_LEVEL_MB, MAX_LEVEL_MB)
            if (bandLevels[band] != clamped) {
                bandLevels[band] = clamped
                updateIsFlat()
                if (isNativeLoaded) {
                    try { nativeSetBand(band, clamped) } catch (_: Throwable) {}
                }
            }
        }
    }

    fun getBandWeight(band: Int): Double {
        return if (band in 0 until NUM_BANDS) bandWeights[band] else 0.0
    }

    fun setBandWeight(band: Int, weight: Double) {
        if (band in 0 until NUM_BANDS) {
            val clamped = weight.coerceIn(0.0, 1.0)
            bandWeights[band] = clamped
            if (isNativeLoaded) {
                try { nativeSetBandWeight(band, clamped) } catch (_: Throwable) {}
            }
        }
    }

    fun getMaxAttenuation(): Double = maxAttenuationDb

    fun setMaxAttenuation(attenDb: Double) {
        val clamped = attenDb.coerceIn(0.0, 24.0)
        maxAttenuationDb = clamped
        if (isNativeLoaded) {
            try { nativeSetMaxAttenuation(clamped) } catch (_: Throwable) {}
        }
    }

    fun getSoftKneeThreshold(): Double = softKneeThreshold

    fun setSoftKneeThreshold(threshold: Double) {
        val clamped = threshold.coerceIn(0.1, 1.0)
        softKneeThreshold = clamped
        if (isNativeLoaded) {
            try { nativeSetSoftKneeThreshold(clamped) } catch (_: Throwable) {}
        }
    }

    fun isManualPreampMode(): Boolean = manualPreampMode

    fun setManualPreampMode(manual: Boolean) {
        manualPreampMode = manual
        updateIsFlat()
        if (isNativeLoaded) {
            try { nativeSetPreampMode(manual) } catch (_: Throwable) {}
        }
    }

    fun getManualPreampDb(): Double = manualPreampDb

    fun setManualPreampDb(db: Double) {
        val clamped = db.coerceIn(-24.0, 24.0)
        manualPreampDb = clamped
        updateIsFlat()
        if (isNativeLoaded) {
            try { nativeSetManualPreamp(clamped) } catch (_: Throwable) {}
        }
    }

    override fun isActive(): Boolean {
        return isConfigured
    }

    @Throws(AudioProcessor.UnhandledAudioFormatException::class)
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val encoding = inputAudioFormat.encoding
        if (encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT) {
            isConfigured = false
            return AudioProcessor.AudioFormat.NOT_SET
        }

        currentSampleRate = inputAudioFormat.sampleRate
        currentChannelCount = inputAudioFormat.channelCount

        if (isNativeLoaded) {
            try {
                nativeConfigure(currentSampleRate)
                nativeSetEnabled(isEnabled)
                for (b in 0 until NUM_BANDS) {
                    nativeSetBand(b, bandLevels[b])
                    nativeSetBandWeight(b, bandWeights[b])
                }
                nativeSetMaxAttenuation(maxAttenuationDb)
                nativeSetSoftKneeThreshold(softKneeThreshold)
                nativeSetPreampMode(manualPreampMode)
                nativeSetManualPreamp(manualPreampDb)
            } catch (_: Throwable) {}
        }
        updateIsFlat()
        isConfigured = true

        Log.i(TAG, "Configured EqualizerAudioProcessor: sr=$currentSampleRate ch=$currentChannelCount enc=$encoding native=$isNativeLoaded")
        return inputAudioFormat
    }

    override fun onFlush() {
        super.onFlush()
        if (isNativeLoaded) {
            try { nativeReset() } catch (_: Throwable) {}
        }
    }

    override fun onReset() {
        super.onReset()
        if (isNativeLoaded) {
            try { nativeReset() } catch (_: Throwable) {}
        }
        isConfigured = false
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        if (!isEnabled || isFlat) {
            // Fast direct pass-through when disabled or all bands are flat
            val outputBuffer = replaceOutputBuffer(remaining)
            outputBuffer.put(inputBuffer)
            outputBuffer.flip()
            return
        }

        val encoding = inputAudioFormat.encoding
        val channelCount = inputAudioFormat.channelCount
        val outputBuffer = replaceOutputBuffer(remaining)
        outputBuffer.order(ByteOrder.nativeOrder())
        inputBuffer.order(ByteOrder.nativeOrder())

        // Fast native DSP path using unified dsp_eq.h engine (SIMD NEON accelerated)
        if (isNativeLoaded && inputBuffer.isDirect && outputBuffer.isDirect) {
            val inPos = inputBuffer.position()
            val outPos = outputBuffer.position()
            if (encoding == C.ENCODING_PCM_FLOAT) {
                val numSamples = remaining / 4
                nativeProcessFloat(inputBuffer, inPos, outputBuffer, outPos, numSamples, channelCount)
                inputBuffer.position(inPos + remaining)
                outputBuffer.position(outPos + remaining)
                outputBuffer.flip()
                return
            } else if (encoding == C.ENCODING_PCM_16BIT) {
                val numSamples = remaining / 2
                nativeProcessInt16(inputBuffer, inPos, outputBuffer, outPos, numSamples, channelCount)
                inputBuffer.position(inPos + remaining)
                outputBuffer.position(outPos + remaining)
                outputBuffer.flip()
                return
            }
        }

        // Fallback pass-through (should not be reached with Media3's direct buffers)
        outputBuffer.put(inputBuffer)
        outputBuffer.flip()
    }
}
