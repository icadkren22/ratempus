package com.eddyizm.tempus.equalizer

import android.content.Context
import com.eddyizm.tempus.util.Preferences

/**
 * Built-in Equalizer backend powered by our standalone software DSP [EqualizerAudioProcessor].
 * Operates on PCM samples in the audio pipeline, completely free of OS AudioEffect limitations.
 */
class BuiltinBackend : EqualizerBackend {

    private val processor: EqualizerAudioProcessor
        get() = EqualizerAudioProcessor.getInstance()

    override fun attach(audioSessionId: Int, context: Context): Boolean {
        val enabled = Preferences.isEqualizerEnabled()
        setEnabled(enabled)
        val bands = getNumberOfBands()
        val savedLevels = Preferences.getEqualizerBandLevels(bands)
        for (i in 0 until bands) {
            setBandLevel(i.toShort(), savedLevels[i])
        }
        return true
    }

    override fun release(audioSessionId: Int, context: Context) {
        // Standalone processor is persistent in the audio sink
    }

    override fun setBandLevel(band: Short, level: Short) {
        processor.setBandLevel(band.toInt(), level.toInt())
    }

    override fun getNumberOfBands(): Short = processor.numberOfBands.toShort()

    override fun getBandLevelRange(): ShortArray = processor.bandLevelRange

    override fun getCenterFreq(band: Short): Int = processor.getCenterFreq(band.toInt())

    override fun getBandLevel(band: Short): Short = processor.getBandLevel(band.toInt()).toShort()

    override fun setEnabled(enabled: Boolean) {
        processor.isEnabled = enabled
    }
}
