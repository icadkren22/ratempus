package com.eddyizm.tempus.equalizer

import android.content.Context

/**
 * Built-in Equalizer backend powered by:
 * 1. Software DSP [EqualizerAudioProcessor] for Vanilla (AudioTrack).
 * 2. Native C++ 5-band Biquad IIR DSP for Direct HD (libdirectaudio.so).
 * 3. Native C++ 5-band Biquad IIR DSP for USB Exclusive (Userspace UAC2).
 *
 * All three engines receive synchronized updates via [EqualizerDispatcher].
 */
class BuiltinBackend : EqualizerBackend {

    private val processor: EqualizerAudioProcessor
        get() = EqualizerAudioProcessor.getInstance()

    override fun attach(audioSessionId: Int, context: Context): Boolean {
        EqualizerDispatcher.syncAll()
        return true
    }

    override fun release(audioSessionId: Int, context: Context) {
        // Standalone processor is persistent in the audio sink
    }

    override fun setBandLevel(band: Short, level: Short) {
        EqualizerDispatcher.setBandLevel(band.toInt(), level.toInt())
    }

    override fun setBandWeight(band: Short, weight: Float) {
        EqualizerDispatcher.setBandWeight(band.toInt(), weight.toDouble())
    }

    override fun getBandWeight(band: Short): Float = processor.getBandWeight(band.toInt()).toFloat()

    override fun setMaxAttenuation(attenDb: Float) {
        EqualizerDispatcher.setMaxAttenuation(attenDb.toDouble())
    }

    override fun getMaxAttenuation(): Float = processor.getMaxAttenuation().toFloat()

    override fun setSoftKneeThreshold(threshold: Float) {
        EqualizerDispatcher.setSoftKneeThreshold(threshold.toDouble())
    }

    override fun getSoftKneeThreshold(): Float = processor.getSoftKneeThreshold().toFloat()

    override fun setAutoPreampEnabled(enabled: Boolean) {
        EqualizerDispatcher.setAutoPreampEnabled(enabled)
    }

    override fun isAutoPreampEnabled(): Boolean = processor.isAutoPreampEnabled()

    override fun setManualPreampDb(db: Float) {
        EqualizerDispatcher.setManualPreampDb(db.toDouble())
    }

    override fun getManualPreampDb(): Float = processor.getManualPreampDb().toFloat()

    override fun getNumberOfBands(): Short = processor.numberOfBands.toShort()

    override fun getBandLevelRange(): ShortArray = processor.bandLevelRange

    override fun getCenterFreq(band: Short): Int = processor.getCenterFreq(band.toInt())

    override fun getBandLevel(band: Short): Short = processor.getBandLevel(band.toInt()).toShort()

    override fun setEnabled(enabled: Boolean) {
        EqualizerDispatcher.setEnabled(enabled)
    }
}
