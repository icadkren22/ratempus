package com.eddyizm.tempus.audio

import android.util.Log
import com.eddyizm.tempus.util.Preferences
import java.nio.ByteBuffer

/**
 * Kotlin wrapper around native C++ Direct AudioTrack (libaudioclient.so / AUDIO_OUTPUT_FLAG_DIRECT).
 * Routes audio directly to the hardware DAC / direct_pcm profile without mixer downsampling.
 *
 * Uses the same namespace bridge trick as Poweramp: opens libandroid_runtime.so with
 * RTLD_GLOBAL first so that the "android" linker namespace is promoted into our scope,
 * then dlopen-s libaudioclient.so successfully.
 */
class NativeDirectAudioTrack(
    val sampleRate: Int,
    val channelCount: Int,
    val encoding: Int,
    bufferCapacityFrames: Int = 0
) {
    companion object {
        private const val TAG = "NativeDirectAudioTrack"
        private var isLibraryLoaded = false
        private var symbolsLoaded = false

        init {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                try {
                    System.loadLibrary("directaudio")
                    isLibraryLoaded = true
                    Log.i(TAG, "libdirectaudio.so loaded")
                    // Load system symbols via runtime namespace bridge (Poweramp technique)
                    symbolsLoaded = nativeLoadSymbols()
                    Log.i(TAG, "nativeLoadSymbols: $symbolsLoaded")
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to load libdirectaudio.so: ${t.message}")
                }
            } else {
                Log.i(TAG, "Direct HD native driver requires Android 8+ (API 26+). Bypassed on API ${android.os.Build.VERSION.SDK_INT}.")
            }
        }

        @JvmStatic
        fun isSupported(): Boolean = isLibraryLoaded && symbolsLoaded && Preferences.isDirectHdEnabled()

        @JvmStatic
        fun isHardwareSupported(): Boolean = isLibraryLoaded && symbolsLoaded

        @JvmStatic
        external fun nativeLoadSymbols(): Boolean

        @JvmStatic
        external fun nativeCreate(
            sampleRate: Int, channelCount: Int, encoding: Int, bufferCapacityFrames: Int
        ): Long

        @JvmStatic
        external fun nativeStart(handle: Long): Boolean

        @JvmStatic
        external fun nativePause(handle: Long): Boolean

        @JvmStatic
        external fun nativeFlush(handle: Long): Boolean

        @JvmStatic
        external fun nativeStop(handle: Long): Boolean

        @JvmStatic
        external fun nativeClose(handle: Long)

        @JvmStatic
        external fun nativeWrite(
            handle: Long, byteBuffer: ByteBuffer, offset: Int,
            sizeInBytes: Int, timeoutNanos: Long
        ): Int

        @JvmStatic
        external fun nativeGetPositionUs(handle: Long): Long

        @JvmStatic
        external fun nativeIsExclusive(handle: Long): Boolean

        @JvmStatic
        external fun nativeGetSampleRate(handle: Long): Int

        @JvmStatic
        external fun nativeSetEqEnabled(handle: Long, enabled: Boolean)

        @JvmStatic
        external fun nativeSetEqBand(handle: Long, band: Int, levelMb: Int)

        @Volatile
        private var activeTrack: NativeDirectAudioTrack? = null

        @JvmStatic
        fun setNativeEqEnabled(enabled: Boolean) {
            activeTrack?.let { if (it.isValid) nativeSetEqEnabled(it.nativeHandle, enabled) }
        }

        @JvmStatic
        fun setNativeEqBand(band: Int, levelMb: Int) {
            activeTrack?.let { if (it.isValid) nativeSetEqBand(it.nativeHandle, band, levelMb) }
        }
    }

    private var nativeHandle: Long = 0L

    init {
        if (isSupported()) {
            nativeHandle = nativeCreate(sampleRate, channelCount, encoding, bufferCapacityFrames)
            if (isValid) {
                activeTrack = this
                val enabled = Preferences.isEqualizerEnabled()
                nativeSetEqEnabled(nativeHandle, enabled)
                val savedLevels = Preferences.getEqualizerBandLevels(5)
                for (i in 0 until 5) {
                    nativeSetEqBand(nativeHandle, i, savedLevels[i].toInt())
                }
            }
        }
    }

    val isValid: Boolean get() = nativeHandle != 0L
    val isExclusive: Boolean get() = if (isValid) nativeIsExclusive(nativeHandle) else false
    val actualSampleRate: Int get() = if (isValid) nativeGetSampleRate(nativeHandle) else sampleRate

    fun play(): Boolean = if (isValid) nativeStart(nativeHandle) else false
    fun pause(): Boolean = if (isValid) nativePause(nativeHandle) else false
    fun flush(): Boolean = if (isValid) nativeFlush(nativeHandle) else false
    fun stop(): Boolean = if (isValid) nativeStop(nativeHandle) else false

    fun release() {
        if (isValid) {
            if (activeTrack === this) activeTrack = null
            nativeClose(nativeHandle)
            nativeHandle = 0L
        }
    }

    fun write(byteBuffer: ByteBuffer, sizeInBytes: Int, timeoutNanos: Long = 0L): Int {
        if (!isValid) return -1
        return nativeWrite(nativeHandle, byteBuffer, byteBuffer.position(), sizeInBytes, timeoutNanos)
    }

    fun getPositionUs(): Long = if (isValid) nativeGetPositionUs(nativeHandle) else 0L
}
