package com.eddyizm.tempus.equalizer

import com.eddyizm.tempus.util.Preferences

/**
 * Unified JNI Bridge to the native C++ [DspEqualizer] (libdirectaudio.so).
 *
 * Rather than duplicating JNI bindings across NativeDirectAudioTrack,
 * UsbExclusiveOutput, and EqualizerAudioProcessor, all native EQ instances
 * share this single unified bridge using their native memory pointer ([eqPtr]).
 */
object NativeEqBridge {

    init {
        try {
            System.loadLibrary("directaudio")
        } catch (_: Throwable) {}
    }

    @JvmStatic external fun nativeSetEnabled(eqPtr: Long, enabled: Boolean)
    @JvmStatic external fun nativeSetBand(eqPtr: Long, band: Int, levelMb: Int)
    @JvmStatic external fun nativeSetBandWeight(eqPtr: Long, band: Int, weight: Double)
    @JvmStatic external fun nativeSetMaxAttenuation(eqPtr: Long, attenDb: Double)
    @JvmStatic external fun nativeSetSoftKneeThreshold(eqPtr: Long, threshold: Double)
    @JvmStatic external fun nativeSetAutoPreampEnabled(eqPtr: Long, enabled: Boolean)
    @JvmStatic external fun nativeSetManualPreamp(eqPtr: Long, db: Double)
    @JvmStatic external fun nativeSetReplayGainDb(eqPtr: Long, db: Double)
    @JvmStatic external fun nativeReset(eqPtr: Long)

    fun setReplayGainDb(eqPtr: Long, db: Double) {
        if (eqPtr != 0L) {
            try {
                nativeSetReplayGainDb(eqPtr, db)
            } catch (_: Throwable) {}
        }
    }
    @JvmStatic external fun nativeApplyConfig(
        eqPtr: Long,
        enabled: Boolean,
        levels: IntArray,
        weights: DoubleArray,
        manualDb: Double,
        autoPreamp: Boolean,
        maxAtten: Double,
        knee: Double
    )

    /**
     * Atomically syncs the native DSP equalizer instance at [eqPtr] with current user Preferences.
     */
    fun syncFromPreferences(eqPtr: Long) {
        if (eqPtr == 0L) return
        val enabled = Preferences.isEqualizerEnabled()
        val bands: Short = 5
        val savedLevels = Preferences.getEqualizerBandLevels(bands)
        val levels = IntArray(bands.toInt()) { savedLevels[it].toInt() }
        val savedWeights = Preferences.getEqualizerBandWeights(bands)
        val weights = DoubleArray(bands.toInt()) { savedWeights[it].toDouble() }
        val manualDb = Preferences.getEqualizerManualPreampDb().toDouble()
        val autoPreamp = Preferences.isEqualizerAutoPreampEnabled()
        val maxAtten = Preferences.getEqualizerMaxAttenuation().toDouble()
        val knee = Preferences.getEqualizerSoftKneeThreshold().toDouble()
        try {
            nativeApplyConfig(eqPtr, enabled, levels, weights, manualDb, autoPreamp, maxAtten, knee)
        } catch (_: Throwable) {}
    }
}
