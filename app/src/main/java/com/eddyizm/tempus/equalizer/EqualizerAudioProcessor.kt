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

    fun setBandLevel(band: Int, levelMb: Int) {
        if (band in 0 until NUM_BANDS) {
            val clamped = levelMb.coerceIn(MIN_LEVEL_MB, MAX_LEVEL_MB)
            if (bandLevels[band] != clamped) {
                bandLevels[band] = clamped
                isFlat = bandLevels.all { it == 0 }
                if (isNativeLoaded) {
                    try { nativeSetBand(band, clamped) } catch (_: Throwable) {}
                }
            }
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
                }
            } catch (_: Throwable) {}
        }
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
