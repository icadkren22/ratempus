package com.eddyizm.tempus.equalizer

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import com.eddyizm.tempus.util.Preferences
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

private const val TAG = "EqualizerAudioProcessor"

/**
 * High-performance standalone 5-band software DSP equalizer implemented as a Media3 [AudioProcessor].
 * Operates directly on PCM samples (both 16-bit integer and 32-bit float) using Direct Form II
 * Transposed Biquad Peaking IIR filters.
 *
 * Runs inside ExoPlayer's AudioSink pipeline before audio is dispatched to Vanilla (AudioTrack),
 * Direct HD (libdirectaudio.so), or USB Exclusive (Userspace UAC2).
 */
@OptIn(markerClass = [UnstableApi::class])
class EqualizerAudioProcessor private constructor() : BaseAudioProcessor() {

    companion object {
        val BAND_FREQUENCIES_HZ = intArrayOf(60, 230, 910, 3600, 14000)
        const val NUM_BANDS = 5
        private const val DEFAULT_Q = 1.414 // sqrt(2), optimal octave bandwidth
        private const val MIN_LEVEL_MB = -1500 // -15.0 dB in millibels
        private const val MAX_LEVEL_MB = 1500  // +15.0 dB in millibels

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
            updateCoefficients()
        }

    private class BiquadCoeffs {
        var b0: Double = 1.0
        var b1: Double = 0.0
        var b2: Double = 0.0
        var a1: Double = 0.0
        var a2: Double = 0.0
        var isBypassed: Boolean = true
    }

    private class ChannelState(numBands: Int) {
        val d1 = DoubleArray(numBands)
        val d2 = DoubleArray(numBands)

        fun reset() {
            d1.fill(0.0)
            d2.fill(0.0)
        }
    }

    private val filterCoeffs = Array(NUM_BANDS) { BiquadCoeffs() }
    private var channelStates: Array<ChannelState> = Array(2) { ChannelState(NUM_BANDS) }
    private var currentSampleRate: Int = 44100
    private var currentChannelCount: Int = 2
    private var isFlat: Boolean = true
    private var isConfigured: Boolean = false

    init {
        updateCoefficients()
    }

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
                updateCoefficients()
            }
        }
    }

    private fun updateCoefficients() {
        synchronized(filterCoeffs) {
            var allBypassed = true
            val sampleRate = if (currentSampleRate > 0) currentSampleRate.toDouble() else 44100.0

            for (i in 0 until NUM_BANDS) {
                val gainDb = bandLevels[i] / 100.0 // convert millibels to dB
                val coeffs = filterCoeffs[i]

                if (!isEnabled || abs(gainDb) < 0.05) {
                    coeffs.b0 = 1.0
                    coeffs.b1 = 0.0
                    coeffs.b2 = 0.0
                    coeffs.a1 = 0.0
                    coeffs.a2 = 0.0
                    coeffs.isBypassed = true
                } else {
                    allBypassed = false
                    coeffs.isBypassed = false

                    // Peaking EQ filter design (Robert Bristow-Johnson Audio EQ Cookbook)
                    val freq = BAND_FREQUENCIES_HZ[i].toDouble().coerceAtMost(sampleRate * 0.49)
                    val a = 10.0.pow(gainDb / 40.0)
                    val omega = 2.0 * Math.PI * freq / sampleRate
                    val sinOmega = sin(omega)
                    val cosOmega = cos(omega)
                    val alpha = sinOmega / (2.0 * DEFAULT_Q)

                    val b0 = 1.0 + alpha * a
                    val b1 = -2.0 * cosOmega
                    val b2 = 1.0 - alpha * a
                    val a0 = 1.0 + alpha / a
                    val a1 = -2.0 * cosOmega
                    val a2 = 1.0 - alpha / a

                    coeffs.b0 = b0 / a0
                    coeffs.b1 = b1 / a0
                    coeffs.b2 = b2 / a0
                    coeffs.a1 = a1 / a0
                    coeffs.a2 = a2 / a0
                }
            }
            isFlat = allBypassed
            Log.d(TAG, "updateCoefficients: isEnabled=$isEnabled isFlat=$isFlat bands=${bandLevels.joinToString()}")
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

        channelStates = Array(currentChannelCount) { ChannelState(NUM_BANDS) }
        updateCoefficients()
        isConfigured = true

        Log.i(TAG, "Configured EqualizerAudioProcessor: sr=$currentSampleRate ch=$currentChannelCount enc=$encoding")
        return inputAudioFormat
    }

    override fun onFlush() {
        super.onFlush()
        for (state in channelStates) {
            state.reset()
        }
    }

    override fun onReset() {
        super.onReset()
        for (state in channelStates) {
            state.reset()
        }
        isConfigured = false
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val isDirectHdOrUsbEx = Preferences.isDirectHdEnabled() || Preferences.isUsbDacExclusiveEnabled()
        if (!isEnabled || isFlat || isDirectHdOrUsbEx) {
            // Fast direct pass-through (Vanilla AudioTrack uses Kotlin DSP; Direct HD & USB Exclusive use native C++ DSP)
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

        synchronized(filterCoeffs) {
            if (encoding == C.ENCODING_PCM_FLOAT) {
                var ch = 0
                while (inputBuffer.remaining() >= 4) {
                    var sample = inputBuffer.getFloat().toDouble()
                    val chState = channelStates.getOrNull(ch)
                    if (chState != null) {
                        for (b in 0 until NUM_BANDS) {
                            val coeff = filterCoeffs[b]
                            if (!coeff.isBypassed) {
                                val y = coeff.b0 * sample + chState.d1[b]
                                chState.d1[b] = coeff.b1 * sample - coeff.a1 * y + chState.d2[b]
                                chState.d2[b] = coeff.b2 * sample - coeff.a2 * y
                                sample = y
                            }
                        }
                    }
                    val clamped = sample.coerceIn(-1.0, 1.0).toFloat()
                    outputBuffer.putFloat(clamped)
                    ch = (ch + 1) % channelCount
                }
            } else {
                var ch = 0
                while (inputBuffer.remaining() >= 2) {
                    var sample = inputBuffer.getShort().toDouble() / 32768.0
                    val chState = channelStates.getOrNull(ch)
                    if (chState != null) {
                        for (b in 0 until NUM_BANDS) {
                            val coeff = filterCoeffs[b]
                            if (!coeff.isBypassed) {
                                val y = coeff.b0 * sample + chState.d1[b]
                                chState.d1[b] = coeff.b1 * sample - coeff.a1 * y + chState.d2[b]
                                chState.d2[b] = coeff.b2 * sample - coeff.a2 * y
                                sample = y
                            }
                        }
                    }
                    val clamped = (sample * 32768.0).coerceIn(-32768.0, 32767.0).toInt().toShort()
                    outputBuffer.putShort(clamped)
                    ch = (ch + 1) % channelCount
                }
            }
        }
        outputBuffer.flip()
    }
}
