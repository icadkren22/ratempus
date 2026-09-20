package com.eddyizm.tempus.equalizer

import com.eddyizm.tempus.audio.NativeDirectAudioTrack
import com.eddyizm.tempus.audio.usb.UsbExclusiveOutput

/**
 * Dispatches Equalizer parameter updates to all active playback engines:
 * 1. ExoPlayer AudioTrack pipeline via [EqualizerAudioProcessor].
 * 2. Direct HD output via [NativeDirectAudioTrack].
 * 3. USB Exclusive output via [UsbExclusiveOutput].
 */
object EqualizerDispatcher {

    fun setEnabled(enabled: Boolean) {
        EqualizerAudioProcessor.getInstance().isEnabled = enabled
        NativeDirectAudioTrack.getActiveTrack()?.eqPtr?.let { if (it != 0L) NativeEqBridge.nativeSetEnabled(it, enabled) }
        UsbExclusiveOutput.getActiveOutput()?.eqPtr?.let { if (it != 0L) NativeEqBridge.nativeSetEnabled(it, enabled) }
    }

    fun setBandLevel(band: Int, levelMb: Int) {
        EqualizerAudioProcessor.getInstance().setBandLevel(band, levelMb)
        NativeDirectAudioTrack.getActiveTrack()?.eqPtr?.let { if (it != 0L) NativeEqBridge.nativeSetBand(it, band, levelMb) }
        UsbExclusiveOutput.getActiveOutput()?.eqPtr?.let { if (it != 0L) NativeEqBridge.nativeSetBand(it, band, levelMb) }
    }

    fun setBandWeight(band: Int, weight: Double) {
        EqualizerAudioProcessor.getInstance().setBandWeight(band, weight)
        NativeDirectAudioTrack.getActiveTrack()?.eqPtr?.let { if (it != 0L) NativeEqBridge.nativeSetBandWeight(it, band, weight) }
        UsbExclusiveOutput.getActiveOutput()?.eqPtr?.let { if (it != 0L) NativeEqBridge.nativeSetBandWeight(it, band, weight) }
    }

    fun setMaxAttenuation(attenDb: Double) {
        EqualizerAudioProcessor.getInstance().setMaxAttenuation(attenDb)
        NativeDirectAudioTrack.getActiveTrack()?.eqPtr?.let { if (it != 0L) NativeEqBridge.nativeSetMaxAttenuation(it, attenDb) }
        UsbExclusiveOutput.getActiveOutput()?.eqPtr?.let { if (it != 0L) NativeEqBridge.nativeSetMaxAttenuation(it, attenDb) }
    }

    fun setSoftKneeThreshold(threshold: Double) {
        EqualizerAudioProcessor.getInstance().setSoftKneeThreshold(threshold)
        NativeDirectAudioTrack.getActiveTrack()?.eqPtr?.let { if (it != 0L) NativeEqBridge.nativeSetSoftKneeThreshold(it, threshold) }
        UsbExclusiveOutput.getActiveOutput()?.eqPtr?.let { if (it != 0L) NativeEqBridge.nativeSetSoftKneeThreshold(it, threshold) }
    }

    fun setAutoPreampEnabled(enabled: Boolean) {
        EqualizerAudioProcessor.getInstance().setAutoPreampEnabled(enabled)
        NativeDirectAudioTrack.getActiveTrack()?.eqPtr?.let { if (it != 0L) NativeEqBridge.nativeSetAutoPreampEnabled(it, enabled) }
        UsbExclusiveOutput.getActiveOutput()?.eqPtr?.let { if (it != 0L) NativeEqBridge.nativeSetAutoPreampEnabled(it, enabled) }
    }

    @Volatile
    private var currentRgDb: Double = 0.0

    @JvmStatic
    fun setManualPreampDb(db: Double) {
        EqualizerAudioProcessor.getInstance().setManualPreampDb(db)
        NativeDirectAudioTrack.getActiveTrack()?.eqPtr?.let { if (it != 0L) NativeEqBridge.nativeSetManualPreamp(it, db) }
        UsbExclusiveOutput.getActiveOutput()?.eqPtr?.let { if (it != 0L) NativeEqBridge.nativeSetManualPreamp(it, db) }
    }

    @JvmStatic
    fun setReplayGainDb(db: Double) {
        currentRgDb = db
        EqualizerAudioProcessor.getInstance().setReplayGainDb(db)
        NativeDirectAudioTrack.getActiveTrack()?.eqPtr?.let { if (it != 0L) NativeEqBridge.setReplayGainDb(it, db) }
        UsbExclusiveOutput.getActiveOutput()?.eqPtr?.let { if (it != 0L) NativeEqBridge.setReplayGainDb(it, db) }
    }

    @JvmStatic
    fun getReplayGainDb(): Double = currentRgDb

    fun syncAll() {
        EqualizerAudioProcessor.getInstance().syncFromPreferences()
        NativeDirectAudioTrack.getActiveTrack()?.syncEqFromPreferences()
        UsbExclusiveOutput.getActiveOutput()?.syncEqFromPreferences()
        if (currentRgDb != 0.0) {
            setReplayGainDb(currentRgDb)
        }
    }
}
