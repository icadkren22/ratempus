package com.eddyizm.tempus.equalizer

import android.content.Context
import com.eddyizm.tempus.audio.NativeDirectAudioTrack
import com.eddyizm.tempus.audio.usb.UsbExclusiveOutput
import com.eddyizm.tempus.util.Preferences

/**
 * Built-in Equalizer backend powered by:
 * 1. Software DSP [EqualizerAudioProcessor] for Vanilla (AudioTrack).
 * 2. Native C++ 5-band Biquad IIR DSP for Direct HD (libdirectaudio.so).
 * 3. Native C++ 5-band Biquad IIR DSP for USB Exclusive (Userspace UAC2).
 */
class BuiltinBackend : EqualizerBackend {

    private val processor: EqualizerAudioProcessor
        get() = EqualizerAudioProcessor.getInstance()

    override fun attach(audioSessionId: Int, context: Context): Boolean {
        val enabled = Preferences.isEqualizerEnabled()
        setEnabled(enabled)
        val bands = getNumberOfBands()
        val savedLevels = Preferences.getEqualizerBandLevels(bands)
        val savedWeights = Preferences.getEqualizerBandWeights(bands)
        for (i in 0 until bands) {
            setBandLevel(i.toShort(), savedLevels[i])
            setBandWeight(i.toShort(), savedWeights[i])
        }
        setMaxAttenuation(Preferences.getEqualizerMaxAttenuation())
        setSoftKneeThreshold(Preferences.getEqualizerSoftKneeThreshold())
        setManualPreampMode(Preferences.isEqualizerManualPreampMode())
        setManualPreampDb(Preferences.getEqualizerManualPreampDb())
        return true
    }

    override fun release(audioSessionId: Int, context: Context) {
        // Standalone processor is persistent in the audio sink
    }

    override fun setBandLevel(band: Short, level: Short) {
        processor.setBandLevel(band.toInt(), level.toInt())
        NativeDirectAudioTrack.setNativeEqBand(band.toInt(), level.toInt())
        UsbExclusiveOutput.setNativeEqBand(band.toInt(), level.toInt())
    }

    override fun setBandWeight(band: Short, weight: Float) {
        processor.setBandWeight(band.toInt(), weight.toDouble())
        NativeDirectAudioTrack.setNativeEqBandWeight(band.toInt(), weight.toDouble())
        UsbExclusiveOutput.setNativeEqBandWeight(band.toInt(), weight.toDouble())
    }

    override fun getBandWeight(band: Short): Float = processor.getBandWeight(band.toInt()).toFloat()

    override fun setMaxAttenuation(attenDb: Float) {
        processor.setMaxAttenuation(attenDb.toDouble())
        NativeDirectAudioTrack.setNativeEqMaxAttenuation(attenDb.toDouble())
        UsbExclusiveOutput.setNativeEqMaxAttenuation(attenDb.toDouble())
    }

    override fun getMaxAttenuation(): Float = processor.getMaxAttenuation().toFloat()

    override fun setSoftKneeThreshold(threshold: Float) {
        processor.setSoftKneeThreshold(threshold.toDouble())
        NativeDirectAudioTrack.setNativeEqSoftKneeThreshold(threshold.toDouble())
        UsbExclusiveOutput.setNativeEqSoftKneeThreshold(threshold.toDouble())
    }

    override fun getSoftKneeThreshold(): Float = processor.getSoftKneeThreshold().toFloat()

    override fun setManualPreampMode(manual: Boolean) {
        processor.setManualPreampMode(manual)
        NativeDirectAudioTrack.setNativeEqPreampMode(manual)
        UsbExclusiveOutput.setNativeEqPreampMode(manual)
    }

    override fun isManualPreampMode(): Boolean = processor.isManualPreampMode()

    override fun setManualPreampDb(db: Float) {
        processor.setManualPreampDb(db.toDouble())
        NativeDirectAudioTrack.setNativeEqManualPreamp(db.toDouble())
        UsbExclusiveOutput.setNativeEqManualPreamp(db.toDouble())
    }

    override fun getManualPreampDb(): Float = processor.getManualPreampDb().toFloat()

    override fun getNumberOfBands(): Short = processor.numberOfBands.toShort()

    override fun getBandLevelRange(): ShortArray = processor.bandLevelRange

    override fun getCenterFreq(band: Short): Int = processor.getCenterFreq(band.toInt())

    override fun getBandLevel(band: Short): Short = processor.getBandLevel(band.toInt()).toShort()

    override fun setEnabled(enabled: Boolean) {
        processor.isEnabled = enabled
        NativeDirectAudioTrack.setNativeEqEnabled(enabled)
        UsbExclusiveOutput.setNativeEqEnabled(enabled)
    }
}
