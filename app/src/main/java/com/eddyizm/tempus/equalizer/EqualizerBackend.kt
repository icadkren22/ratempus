package com.eddyizm.tempus.equalizer

import android.content.Context

interface EqualizerBackend {

    fun attach(audioSessionId: Int, context: Context): Boolean

    fun release(audioSessionId: Int, context: Context)

    fun setEnabled(enabled: Boolean)

    fun getNumberOfBands(): Short

    fun getBandLevelRange(): ShortArray?

    fun getCenterFreq(band: Short): Int?

    fun getBandLevel(band: Short): Short?

    fun setBandLevel(band: Short, level: Short)

    fun setBandWeight(band: Short, weight: Float) {}

    fun getBandWeight(band: Short): Float = 0f

    fun setMaxAttenuation(attenDb: Float) {}

    fun getMaxAttenuation(): Float = 8f

    fun setSoftKneeThreshold(threshold: Float) {}

    fun getSoftKneeThreshold(): Float = 0.7f

    fun setAutoPreampEnabled(enabled: Boolean) {}

    fun isAutoPreampEnabled(): Boolean = true

    fun setManualPreampDb(db: Float) {}

    fun getManualPreampDb(): Float = 0f
}