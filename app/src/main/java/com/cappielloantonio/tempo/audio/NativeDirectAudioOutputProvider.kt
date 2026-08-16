package com.cappielloantonio.tempo.audio

import android.content.Context
import android.hardware.usb.UsbManager
import android.media.AudioDeviceInfo
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioOutputProvider.ConfigurationException
import androidx.media3.exoplayer.audio.AudioOutputProvider.FormatConfig
import androidx.media3.exoplayer.audio.AudioOutputProvider.FormatSupport
import androidx.media3.exoplayer.audio.AudioOutputProvider.InitializationException
import androidx.media3.exoplayer.audio.AudioOutputProvider.OutputConfig
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import com.cappielloantonio.tempo.audio.usb.UsbAudioConfig
import com.cappielloantonio.tempo.audio.usb.UsbDacManager
import com.cappielloantonio.tempo.audio.usb.UsbExclusiveOutput
import com.cappielloantonio.tempo.util.Preferences
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList

private const val TAG = "NativeDirectAudio"

/**
 * Media3 [AudioOutputProvider] that routes audio based on user preferences and active hardware:
 * 1. USB DAC Exclusive Mode (if enabled and USB DAC connected with permission) -> raw userspace UAC2 driver.
 * 2. Direct HD Mode (if enabled) -> native AudioTrack with AUDIO_OUTPUT_FLAG_DIRECT (Speaker / 3.5mm Jack).
 * 3. Fallback -> standard Media3 AudioTrackAudioOutputProvider.
 */
@UnstableApi
class NativeDirectAudioOutputProvider(private val context: Context) : AudioOutputProvider {

    private val fallback: AudioTrackAudioOutputProvider =
        AudioTrackAudioOutputProvider.Builder(context).build()
    private val listeners = CopyOnWriteArrayList<AudioOutputProvider.Listener>()
    private val usbDacManager = UsbDacManager.getInstance(context)

    override fun getFormatSupport(formatConfig: FormatConfig): FormatSupport {
        return fallback.getFormatSupport(formatConfig)
    }

    @Throws(ConfigurationException::class)
    override fun getOutputConfig(formatConfig: FormatConfig): OutputConfig {
        return fallback.getOutputConfig(formatConfig)
    }

    @Throws(InitializationException::class)
    override fun getAudioOutput(outputConfig: OutputConfig): AudioOutput {
        val isUsbExclusiveEnabled = Preferences.isUsbDacExclusiveEnabled()
        val isDirectHdEnabled = Preferences.isDirectHdEnabled()

        // ── Path 1: USB DAC Exclusive (Userspace UAC2 Driver) ──────────────
        if (isUsbExclusiveEnabled && usbDacManager.isUsbDacConnected) {
            val usbDevice = usbDacManager.connectedUsbDevice
            if (usbDevice != null) {
                val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
                if (usbManager.hasPermission(usbDevice)) {
                    try {
                        val exclusiveOutput = UsbExclusiveOutput(context)
                        val config = exclusiveOutput.findBestConfig(usbDevice, outputConfig.sampleRate)
                        if (config != null) {
                            Log.i(TAG, "Routing to USB Exclusive (userspace URB): " +
                                    "${config.sampleRate}Hz ${config.bitDepth}bit via iface=${config.streamingIface}")
                            return UsbExclusiveAudioOutput(context, exclusiveOutput, config, outputConfig)
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "UsbExclusiveOutput init failed: ${t.message}, falling through")
                    }
                } else {
                    Log.w(TAG, "No USB permission yet for ${usbDevice.productName}, requesting...")
                    usbDacManager.requestPermission(usbDevice)
                }
            }
        }

        // ── Path 2: Direct HD (Speaker / Jack via Native AudioTrack DIRECT) ──
        if (isDirectHdEnabled && !outputConfig.isOffload && NativeDirectAudioTrack.isSupported()) {
            try {
                val chCount = if (outputConfig.channelMask == android.media.AudioFormat.CHANNEL_OUT_MONO) 1 else 2
                val track = NativeDirectAudioTrack(
                    sampleRate = outputConfig.sampleRate,
                    channelCount = chCount,
                    encoding = outputConfig.encoding,
                    bufferCapacityFrames = outputConfig.bufferSize / (chCount * 4)
                )
                if (track.isValid) {
                    Log.i(TAG, "Created NativeDirectAudioOutput: sr=${track.actualSampleRate} exclusive=${track.isExclusive}")
                    return NativeDirectAudioOutput(track, outputConfig)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "NativeDirectAudioTrack creation failed, falling back: ${t.message}")
            }
        }

        return fallback.getAudioOutput(outputConfig)
    }

    override fun addListener(listener: AudioOutputProvider.Listener) {
        listeners.addIfAbsent(listener)
        fallback.addListener(listener)
    }

    override fun removeListener(listener: AudioOutputProvider.Listener) {
        listeners.remove(listener)
        fallback.removeListener(listener)
    }

    override fun release() {
        fallback.release()
        listeners.clear()
    }
}

/**
 * Media3 [AudioOutput] implementation for USB DAC Exclusive userspace streaming.
 * Fixed to 5% volume to protect IEMs.
 *
 * Position tracking uses a wall-clock anchor (playEpochNanos) instead of
 * framesWritten / sampleRate. This is necessary because:
 * - framesWritten counts bytes pushed into the ring buffer, NOT bytes consumed by the DAC.
 * - The ring buffer introduces ~50-200ms of latency before audio reaches the DAC.
 * - Using framesWritten makes the position "stuck" at 00:00 for the duration of
 *   the initial ring-buffer fill, causing the UI timer to freeze on startup.
 * - A wall-clock anchor (elapsed real time since play()) matches exactly what a
 *   hardware AudioTrack would report, and advances immediately on play().
 */
@UnstableApi
private class UsbExclusiveAudioOutput(
    private val context: Context,
    private val exclusiveOutput: UsbExclusiveOutput,
    private val usbConfig: UsbAudioConfig,
    private val outputConfig: OutputConfig
) : AudioOutput {

    private val listeners = CopyOnWriteArrayList<AudioOutput.Listener>()
    private var playbackParams = PlaybackParameters.DEFAULT
    private var isPlaying = false
    private var lastNotifiedAdvancingMs = 0L
    private var opened = false

    // Wall-clock position tracking
    // playEpochNanos: System.nanoTime() when play() was last called (after a pause, adjusted)
    // pausedPositionUs: accumulated position when paused
    private var playEpochNanos = 0L
    private var pausedPositionUs = 0L

    private val silentTracker = SilentAudioTracker()

    init {
        opened = exclusiveOutput.open(usbConfig, outputConfig.encoding)
        if (!opened) {
            Log.e(TAG, "UsbExclusiveOutput.open() failed at init")
        }
    }

    override fun play() {
        if (!isPlaying) {
            Log.i(TAG, "USB Exclusive playback started: ${usbConfig.sampleRate}Hz ${usbConfig.bitDepth}bit (5% volume)")
            // Anchor wall clock so position resumes from where it paused
            playEpochNanos = System.nanoTime() - pausedPositionUs * 1_000L
            silentTracker.play()
        }
        isPlaying = true
        exclusiveOutput.start()
        val nowMs = SystemClock.elapsedRealtime()
        listeners.forEach { it.onPositionAdvancing(nowMs) }
    }

    override fun pause() {
        if (isPlaying) {
            // Snapshot position before stopping
            pausedPositionUs = (System.nanoTime() - playEpochNanos) / 1_000L
        }
        isPlaying = false
        silentTracker.pause()
        exclusiveOutput.stop()
    }

    override fun write(byteBuffer: ByteBuffer, sizeInBytes: Int, presentationTimeUs: Long): Boolean {
        if (!byteBuffer.hasRemaining()) return true
        if (!opened) return false

        val remaining = byteBuffer.remaining()
        val written = exclusiveOutput.write(byteBuffer, byteBuffer.position(), remaining)
        if (written > 0) {
            byteBuffer.position(byteBuffer.position() + written)

            val nowMs = SystemClock.elapsedRealtime()
            if (nowMs - lastNotifiedAdvancingMs > 50) {
                lastNotifiedAdvancingMs = nowMs
                listeners.forEach { it.onPositionAdvancing(nowMs) }
            }
            return byteBuffer.remaining() == 0
        }
        return false
    }

    override fun flush() {
        // On flush (seek), reset position to 0
        pausedPositionUs = 0L
        playEpochNanos = System.nanoTime()
        if (isPlaying) {
            exclusiveOutput.start()
        }
    }

    override fun stop() {
        if (isPlaying) {
            pausedPositionUs = (System.nanoTime() - playEpochNanos) / 1_000L
        }
        isPlaying = false
        silentTracker.stop()
        exclusiveOutput.stop()
    }

    override fun release() {
        isPlaying = false
        silentTracker.release()
        exclusiveOutput.close()
        listeners.forEach { it.onReleased() }
        listeners.clear()
    }

    override fun setVolume(volume: Float) {
        // Fixed at 5% volume to protect IEMs
    }

    override fun isOffloadedPlayback(): Boolean = false
    override fun getAudioSessionId(): Int = outputConfig.audioSessionId
    override fun getSampleRate(): Int = usbConfig.sampleRate
    override fun getBufferSizeInFrames(): Long = 4096L

    override fun getPositionUs(): Long {
        // Wall-clock based position: starts advancing immediately at play()
        // This mirrors how a hardware AudioTrack reports position — based on
        // elapsed time since playback started, not ring buffer fill level.
        if (!isPlaying) return pausedPositionUs
        val elapsedUs = (System.nanoTime() - playEpochNanos) / 1_000L
        return elapsedUs.coerceAtLeast(0L)
    }

    override fun getPlaybackParameters(): PlaybackParameters = playbackParams
    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        this.playbackParams = playbackParameters
    }

    override fun isStalled(): Boolean = false
    override fun addListener(listener: AudioOutput.Listener) { listeners.addIfAbsent(listener) }
    override fun removeListener(listener: AudioOutput.Listener) { listeners.remove(listener) }
    override fun setOffloadDelayPadding(delayInFrames: Int, paddingInFrames: Int) {}
    override fun setOffloadEndOfStream() {}
    override fun setPlayerId(playerId: PlayerId) {}
    override fun attachAuxEffect(effectId: Int) {}
    override fun setAuxEffectSendLevel(level: Float) {}
    override fun setPreferredDevice(deviceInfo: AudioDeviceInfo?) {}
}

/**
 * Media3 [AudioOutput] implementation wrapping [NativeDirectAudioTrack] for Speaker / Jack Direct HD.
 */
@UnstableApi
private class NativeDirectAudioOutput(
    private val nativeTrack: NativeDirectAudioTrack,
    private val outputConfig: OutputConfig
) : AudioOutput {

    private val listeners = CopyOnWriteArrayList<AudioOutput.Listener>()
    private var playbackParams = PlaybackParameters.DEFAULT
    private var volume = 1.0f

    private var isPlaying = false
    private var startSystemTimeUs = 0L
    private var lastNotifiedAdvancingMs = 0L
    private val silentTracker = SilentAudioTracker()

    override fun play() {
        isPlaying = true
        if (startSystemTimeUs == 0L) {
            startSystemTimeUs = SystemClock.elapsedRealtimeNanos() / 1000L
        }
        silentTracker.play()
        nativeTrack.play()
        val nowMs = SystemClock.elapsedRealtime()
        listeners.forEach { it.onPositionAdvancing(nowMs) }
    }

    override fun pause() {
        isPlaying = false
        silentTracker.pause()
        nativeTrack.pause()
    }

    override fun write(byteBuffer: ByteBuffer, sizeInBytes: Int, presentationTimeUs: Long): Boolean {
        if (!byteBuffer.hasRemaining()) return true
        val frameSize = if (outputConfig.channelMask == android.media.AudioFormat.CHANNEL_OUT_MONO) 4 else 8
        val bytesToWrite = (byteBuffer.remaining() / frameSize) * frameSize
        if (bytesToWrite <= 0) return true

        val written = nativeTrack.write(byteBuffer, bytesToWrite, 0L)
        if (written > 0) {
            byteBuffer.position(byteBuffer.position() + written)
            val nowMs = SystemClock.elapsedRealtime()
            if (nowMs - lastNotifiedAdvancingMs > 50) {
                lastNotifiedAdvancingMs = nowMs
                listeners.forEach { it.onPositionAdvancing(nowMs) }
            }
            return byteBuffer.remaining() < frameSize
        }
        return false
    }

    override fun flush() {
        startSystemTimeUs = SystemClock.elapsedRealtimeNanos() / 1000L
        nativeTrack.flush()
        if (isPlaying) {
            nativeTrack.play()
        }
    }

    override fun stop() {
        isPlaying = false
        silentTracker.stop()
        nativeTrack.stop()
    }

    override fun release() {
        isPlaying = false
        silentTracker.release()
        nativeTrack.release()
        listeners.forEach { it.onReleased() }
        listeners.clear()
    }

    override fun setVolume(volume: Float) {
        this.volume = volume
    }

    override fun isOffloadedPlayback(): Boolean = false
    override fun getAudioSessionId(): Int = outputConfig.audioSessionId
    override fun getSampleRate(): Int = nativeTrack.actualSampleRate
    override fun getBufferSizeInFrames(): Long = (outputConfig.bufferSize / 8).toLong()

    override fun getPositionUs(): Long {
        val hwPos = nativeTrack.getPositionUs()
        if (hwPos > 0) return hwPos
        if (isPlaying && startSystemTimeUs > 0) {
            return (SystemClock.elapsedRealtimeNanos() / 1000L) - startSystemTimeUs
        }
        return 0L
    }

    override fun getPlaybackParameters(): PlaybackParameters = playbackParams
    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        this.playbackParams = playbackParameters
    }

    override fun isStalled(): Boolean = false
    override fun addListener(listener: AudioOutput.Listener) { listeners.addIfAbsent(listener) }
    override fun removeListener(listener: AudioOutput.Listener) { listeners.remove(listener) }
    override fun setOffloadDelayPadding(delayInFrames: Int, paddingInFrames: Int) {}
    override fun setOffloadEndOfStream() {}
    override fun setPlayerId(playerId: PlayerId) {}
    override fun attachAuxEffect(effectId: Int) {}
    override fun setAuxEffectSendLevel(level: Float) {}
    override fun setPreferredDevice(deviceInfo: AudioDeviceInfo?) {}
}

/**
 * Silent companion AudioTrack to maintain MediaSession and headset button event active registration.
 */
private class SilentAudioTracker {
    private var audioTrack: android.media.AudioTrack? = null

    fun play() {
        try {
            if (audioTrack == null) {
                val sampleRate = 48000
                val channelConfig = android.media.AudioFormat.CHANNEL_OUT_STEREO
                val encoding = android.media.AudioFormat.ENCODING_PCM_16BIT
                val minBuf = android.media.AudioTrack.getMinBufferSize(sampleRate, channelConfig, encoding).coerceAtLeast(4096)

                val attributes = android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
                val format = android.media.AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .setEncoding(encoding)
                    .build()

                val track = android.media.AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(minBuf)
                    .setTransferMode(android.media.AudioTrack.MODE_STATIC)
                    .build()

                val silence = ByteArray(minBuf)
                track.write(silence, 0, silence.size)
                track.setLoopPoints(0, minBuf / 4, -1)
                track.setVolume(0f)
                audioTrack = track
            }
            if (audioTrack?.playState != android.media.AudioTrack.PLAYSTATE_PLAYING) {
                audioTrack?.play()
            }
        } catch (t: Throwable) {
            Log.w("NativeDirectAudio", "SilentAudioTracker play failed: ${t.message}")
        }
    }

    fun pause() {
        try {
            if (audioTrack?.playState == android.media.AudioTrack.PLAYSTATE_PLAYING) {
                audioTrack?.pause()
            }
        } catch (t: Throwable) {
            Log.w("NativeDirectAudio", "SilentAudioTracker pause failed: ${t.message}")
        }
    }

    fun stop() {
        try {
            audioTrack?.stop()
        } catch (t: Throwable) {
            Log.w("NativeDirectAudio", "SilentAudioTracker stop failed: ${t.message}")
        }
    }

    fun release() {
        try {
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
        } catch (t: Throwable) {
            Log.w("NativeDirectAudio", "SilentAudioTracker release failed: ${t.message}")
        }
    }
}
