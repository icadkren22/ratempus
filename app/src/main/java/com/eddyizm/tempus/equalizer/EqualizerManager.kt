package com.eddyizm.tempus.equalizer

import android.content.Context

class EqualizerManager(
    private var backend: EqualizerBackend,
    private var context: Context
) {

    fun attach(audioSessionId: Int): Boolean {
        return backend.attach(audioSessionId, context.applicationContext)
    }

    fun release(audioSessionId: Int) {
        backend.release(audioSessionId, context.applicationContext)
    }

    fun setBandLevel(band: Short, level: Short) = backend.setBandLevel(band, level)
    fun getNumberOfBands(): Short = backend.getNumberOfBands()
    fun getBandLevelRange(): ShortArray? = backend.getBandLevelRange()
    fun getCenterFreq(band: Short): Int? = backend.getCenterFreq(band)
    fun getBandLevel(band: Short): Short? = backend.getBandLevel(band)
    fun setEnabled(enabled: Boolean) = backend.setEnabled(enabled)

    fun setBandWeight(band: Short, weight: Float) = backend.setBandWeight(band, weight)
    fun getBandWeight(band: Short): Float = backend.getBandWeight(band)
    fun setMaxAttenuation(attenDb: Float) = backend.setMaxAttenuation(attenDb)
    fun getMaxAttenuation(): Float = backend.getMaxAttenuation()
    fun setSoftKneeThreshold(threshold: Float) = backend.setSoftKneeThreshold(threshold)
    fun getSoftKneeThreshold(): Float = backend.getSoftKneeThreshold()
    fun setAutoPreampEnabled(enabled: Boolean) = backend.setAutoPreampEnabled(enabled)
    fun isAutoPreampEnabled(): Boolean = backend.isAutoPreampEnabled()
    fun setManualPreampDb(db: Float) = backend.setManualPreampDb(db)
    fun getManualPreampDb(): Float = backend.getManualPreampDb()
}