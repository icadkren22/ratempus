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
        NativeDirectAudioTrack.setNativeEqBand(band.toInt(), level.toInt())
        UsbExclusiveOutput.setNativeEqBand(band.toInt(), level.toInt())
    }

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
