package com.eddyizm.tempus.audio

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
import com.eddyizm.tempus.audio.usb.UsbAudioConfig
import com.eddyizm.tempus.audio.usb.UsbDacManager
import com.eddyizm.tempus.audio.usb.UsbExclusiveOutput
import com.eddyizm.tempus.audio.usb.UsbVirtualCastVolumeProvider
import com.eddyizm.tempus.util.AudioOutputTracker
import com.eddyizm.tempus.util.Preferences
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList

private const val TAG = "NativeDirectAudio"

/**
 * Media3 [AudioOutputProvider] that feeds uncompressed decoded PCM directly to either:
 * 1. USB DAC Exclusive userspace driver (via libdirectaudio.so UAC2 isochronous engine)
 * 2. Native Direct AudioTrack (via libaudioclient.so AUDIO_OUTPUT_FLAG_DIRECT)
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
                        val chCount = if (outputConfig.channelMask == android.media.AudioFormat.CHANNEL_OUT_MONO) 1 else 2
                        val exclusiveOutput = UsbExclusiveOutput(context)
                        val config = exclusiveOutput.findBestConfig(usbDevice, outputConfig.sampleRate)
                        if (config != null) {
                            Log.i(TAG, "Routing to USB Exclusive (userspace URB): " +
                                    "${config.sampleRate}Hz ${config.bitDepth}bit srcCh=$chCount via iface=${config.streamingIface}")
                            return UsbExclusiveAudioOutput(context, exclusiveOutput, config, outputConfig, chCount)
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
 * Uses DirectHD-style synchronous condition variable pacing and accurate hardware position reporting.
 */
@UnstableApi
private class UsbExclusiveAudioOutput(
    private val context: Context,
    private val exclusiveOutput: UsbExclusiveOutput,
    private val usbConfig: UsbAudioConfig,
    private val outputConfig: OutputConfig,
    private val srcChannelCount: Int
) : AudioOutput {

    private val listeners = CopyOnWriteArrayList<AudioOutput.Listener>()
    private var playbackParams = PlaybackParameters.DEFAULT

    private var isPlaying = false
    private var opened = false
    private var lastNotifiedAdvancingMs = 0L
    private var playEpochNanos = 0L
    private var pausedPositionUs = 0L

    private val silentTracker = SilentAudioTracker()
    private val virtualCastVolume = UsbVirtualCastVolumeProvider.getInstance(context)

    init {
        opened = exclusiveOutput.open(usbConfig, outputConfig.encoding, srcChannelCount)
        if (!opened) {
            Log.e(TAG, "UsbExclusiveOutput.open() failed at init")
        } else {
            AudioOutputTracker.updateUsbAudioConfig(usbConfig)
        }
    }

    override fun play() {
        if (!isPlaying) {
            Log.i(TAG, "USB Exclusive playback started: ${usbConfig.sampleRate}Hz ${usbConfig.bitDepth}bit srcCh=$srcChannelCount (HW volume active)")
            AudioOutputTracker.updateUsbAudioConfig(usbConfig)
            playEpochNanos = System.nanoTime() - pausedPositionUs * 1_000L
            silentTracker.play()
            virtualCastVolume.setActive(true, output = exclusiveOutput)
        }
        isPlaying = true
        exclusiveOutput.start()
        val nowMs = SystemClock.elapsedRealtime()
        listeners.forEach { it.onPositionAdvancing(nowMs) }
    }

    override fun pause() {
        if (isPlaying) {
            pausedPositionUs = (System.nanoTime() - playEpochNanos) / 1_000L
        }
        isPlaying = false
        silentTracker.pause()
        virtualCastVolume.setActive(false)
        exclusiveOutput.stop()
    }

    override fun write(byteBuffer: ByteBuffer, sizeInBytes: Int, presentationTimeUs: Long): Boolean {
        if (!byteBuffer.hasRemaining()) return true
        if (!opened) return false

        val srcBytesPerSample = if (outputConfig.encoding == android.media.AudioFormat.ENCODING_PCM_16BIT) 2 else 4
        val srcFrameSize = srcChannelCount * srcBytesPerSample
        val bytesToWrite = (byteBuffer.remaining() / srcFrameSize) * srcFrameSize
        if (bytesToWrite <= 0) return true

        val written = exclusiveOutput.write(byteBuffer, byteBuffer.position(), bytesToWrite)
        if (written > 0) {
            byteBuffer.position(byteBuffer.position() + written)

            val nowMs = SystemClock.elapsedRealtime()
            if (nowMs - lastNotifiedAdvancingMs > 50) {
                lastNotifiedAdvancingMs = nowMs
                listeners.forEach { it.onPositionAdvancing(nowMs) }
            }
            return byteBuffer.remaining() < srcFrameSize
        }
        return false
    }

    override fun flush() {
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
        virtualCastVolume.setActive(false)
        exclusiveOutput.stop()
    }

    override fun release() {
        isPlaying = false
        silentTracker.release()
        virtualCastVolume.setActive(false)
        exclusiveOutput.close()
        AudioOutputTracker.updateUsbAudioConfig(null)
        listeners.forEach { it.onReleased() }
        listeners.clear()
    }

    override fun setVolume(volume: Float) {
        // HW Volume is controlled directly via UAC Feature Unit 2
    }

    override fun isOffloadedPlayback(): Boolean = false
    override fun getAudioSessionId(): Int = outputConfig.audioSessionId
    override fun getSampleRate(): Int = usbConfig.sampleRate
    override fun getBufferSizeInFrames(): Long = 4096L

    override fun getPositionUs(): Long {
        val hwPos = exclusiveOutput.getPositionUs()
        if (hwPos > 0) return hwPos
        if (isPlaying && playEpochNanos > 0) {
            return (System.nanoTime() - playEpochNanos) / 1_000L
        }
        return pausedPositionUs
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
 * Media3 [AudioOutput] implementation wrapping [NativeDirectAudioTrack].
 */
@UnstableApi
private class NativeDirectAudioOutput(
    private val nativeTrack: NativeDirectAudioTrack,
    private val outputConfig: OutputConfig
) : AudioOutput {

    private val listeners = CopyOnWriteArrayList<AudioOutput.Listener>()
    private var playbackParams = PlaybackParameters.DEFAULT
    private var volume = 1.0f

    private val srcBytesPerSample = if (outputConfig.encoding == android.media.AudioFormat.ENCODING_PCM_16BIT) 2 else 4
    private val srcChannels = if (outputConfig.channelMask == android.media.AudioFormat.CHANNEL_OUT_MONO) 1 else 2
    private val srcFrameSize = srcChannels * srcBytesPerSample

    private var isPlaying = false
    private var lastNotifiedAdvancingMs = 0L
    private var playEpochNanos = 0L
    private var pausedPositionUs = 0L
    private var totalWrittenFrames = 0L
    private var endOfStreamSignaled = false
    private val silentTracker = SilentAudioTracker()

    override fun play() {
        if (!isPlaying) {
            playEpochNanos = System.nanoTime() - pausedPositionUs * 1_000L
            silentTracker.play()
        }
        isPlaying = true
        nativeTrack.play()
        val nowMs = SystemClock.elapsedRealtime()
        listeners.forEach { it.onPositionAdvancing(nowMs) }
    }

    override fun pause() {
        if (isPlaying) {
            pausedPositionUs = ((System.nanoTime() - playEpochNanos) / 1_000L).coerceAtLeast(0L)
        }
        isPlaying = false
        silentTracker.pause()
        nativeTrack.pause()
    }

    override fun write(byteBuffer: ByteBuffer, sizeInBytes: Int, presentationTimeUs: Long): Boolean {
        if (!byteBuffer.hasRemaining()) return true
        val bytesToWrite = (byteBuffer.remaining() / srcFrameSize) * srcFrameSize
        if (bytesToWrite <= 0) return true
        val written = nativeTrack.write(byteBuffer, bytesToWrite, 0L)
        if (written > 0) {
            totalWrittenFrames += (written / srcFrameSize).toLong()
            byteBuffer.position(byteBuffer.position() + written)
            val nowMs = SystemClock.elapsedRealtime()
            if (nowMs - lastNotifiedAdvancingMs > 50) {
                lastNotifiedAdvancingMs = nowMs
                listeners.forEach { it.onPositionAdvancing(nowMs) }
            }
            return byteBuffer.remaining() < srcFrameSize
        }
        return false
    }

    override fun flush() {
        pausedPositionUs = 0L
        playEpochNanos = System.nanoTime()
        totalWrittenFrames = 0L
        endOfStreamSignaled = false
        nativeTrack.flush()
        if (isPlaying) {
            nativeTrack.play()
        }
    }

    override fun stop() {
        if (isPlaying) {
            pausedPositionUs = ((System.nanoTime() - playEpochNanos) / 1_000L).coerceAtLeast(0L)
        }
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

    override fun getSampleRate(): Int = outputConfig.sampleRate

    override fun getBufferSizeInFrames(): Long = (outputConfig.bufferSize / srcFrameSize).toLong()

    override fun getPositionUs(): Long {
        val sampleRate = outputConfig.sampleRate
        val maxWrittenUs = if (sampleRate > 0) {
            (totalWrittenFrames * 1_000_000L) / sampleRate
        } else {
            0L
        }

        // If EOS was signaled and we have written frames, immediately report
        // maxWrittenUs so DefaultAudioSink.hasPendingData() returns false.
        // This is the primary path for clean track-end in both foreground and background.
        if (endOfStreamSignaled && totalWrittenFrames > 0) {
            return maxWrittenUs
        }

        val elapsedUs = if (isPlaying && playEpochNanos > 0) {
            ((System.nanoTime() - playEpochNanos) / 1_000L).coerceAtLeast(0L)
        } else {
            pausedPositionUs
        }

        // Fallback: wall-clock caught up with (or exceeded) total written audio duration.
        // Add 300ms grace period to tolerate background CPU scheduling jitter.
        val graceUs = 300_000L
        if (totalWrittenFrames > 0 && elapsedUs >= maxWrittenUs + graceUs) {
            return maxWrittenUs
        }

        val hwPos = nativeTrack.getPositionUs()
        if (hwPos > 0 && hwPos <= maxWrittenUs) {
            return maxOf(hwPos, elapsedUs.coerceAtMost(maxWrittenUs))
        }

        return elapsedUs.coerceAtMost(maxWrittenUs)
    }

    override fun getPlaybackParameters(): PlaybackParameters = playbackParams

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        this.playbackParams = playbackParameters
    }

    override fun isStalled(): Boolean = false

    override fun addListener(listener: AudioOutput.Listener) {
        listeners.addIfAbsent(listener)
    }

    override fun removeListener(listener: AudioOutput.Listener) {
        listeners.remove(listener)
    }

    override fun setOffloadDelayPadding(delayInFrames: Int, paddingInFrames: Int) {}

    // ExoPlayer calls this when it has finished writing all frames for the current track.
    // Use it as the authoritative end-of-stream signal instead of relying on wall clock alone.
    override fun setOffloadEndOfStream() {
        endOfStreamSignaled = true
    }

    override fun setPlayerId(playerId: PlayerId) {}

    override fun attachAuxEffect(effectId: Int) {}

    override fun setAuxEffectSendLevel(level: Float) {}

    override fun setPreferredDevice(deviceInfo: AudioDeviceInfo?) {}
}

/**
 * Companion silent [android.media.AudioTrack] that maintains active audio playback registration
 * with Android's `AudioService` (via `PlayerBase` / `IAudioService.trackPlayer()`).
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
                audioTrack = track
            }
            audioTrack?.play()
        } catch (t: Throwable) {
            Log.w(TAG, "SilentAudioTracker.play() failed: ${t.message}")
        }
    }

    fun pause() {
        try {
            audioTrack?.pause()
        } catch (_: Throwable) {}
    }

    fun stop() {
        try {
            audioTrack?.stop()
        } catch (_: Throwable) {}
    }

    fun release() {
        try {
            audioTrack?.release()
            audioTrack = null
        } catch (_: Throwable) {}
    }
}
