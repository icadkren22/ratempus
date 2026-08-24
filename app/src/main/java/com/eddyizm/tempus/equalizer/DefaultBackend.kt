package com.eddyizm.tempus.equalizer

import android.content.Context

class DefaultBackend : EqualizerBackend {

    override fun attach(audioSessionId: Int, context: Context): Boolean {
        EqualizerAudioProcessor.getInstance().isEnabled = false
        return false
    }

    override fun release(audioSessionId: Int, context: Context) {
        EqualizerAudioProcessor.getInstance().isEnabled = false
    }

    override fun setEnabled(enabled: Boolean) {}

    override fun getNumberOfBands(): Short { return 0 }

    override fun getBandLevelRange(): ShortArray? { return null }

    override fun getCenterFreq(band: Short): Int? { return null }

    override fun getBandLevel(band: Short): Short? { return null }

    override fun setBandLevel(band: Short, level: Short) {}
}