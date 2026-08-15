package com.cappielloantonio.tempo.audio

import android.content.Context
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
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList

private const val TAG = "NativeDirectAudio"

/**
 * Media3 [AudioOutputProvider] that feeds pure decoded PCM directly to Android's internal
 * native `AudioTrack` with `AUDIO_OUTPUT_FLAG_DIRECT`, bypassing the system 48 kHz mixer.
 */
@UnstableApi
class NativeDirectAudioOutputProvider(private val context: Context) : AudioOutputProvider {

    private val fallback: AudioTrackAudioOutputProvider =
        AudioTrackAudioOutputProvider.Builder(context).build()
    private val listeners = CopyOnWriteArrayList<AudioOutputProvider.Listener>()

    override fun getFormatSupport(formatConfig: FormatConfig): FormatSupport {
        return fallback.getFormatSupport(formatConfig)
    }

    @Throws(ConfigurationException::class)
    override fun getOutputConfig(formatConfig: FormatConfig): OutputConfig {
        return fallback.getOutputConfig(formatConfig)
    }

    @Throws(InitializationException::class)
    override fun getAudioOutput(outputConfig: OutputConfig): AudioOutput {
        if (!outputConfig.isOffload && NativeDirectAudioTrack.isSupported()) {
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

    override fun addListener(listener: AudioOutput.Listener) {
        listeners.addIfAbsent(listener)
    }

    override fun removeListener(listener: AudioOutput.Listener) {
        listeners.remove(listener)
    }

    override fun setOffloadDelayPadding(delayInFrames: Int, paddingInFrames: Int) {}

    override fun setOffloadEndOfStream() {}

    override fun setPlayerId(playerId: PlayerId) {}

    override fun attachAuxEffect(effectId: Int) {}

    override fun setAuxEffectSendLevel(level: Float) {}

    override fun setPreferredDevice(deviceInfo: AudioDeviceInfo?) {}
}

/**
 * Companion silent [android.media.AudioTrack] that maintains active audio playback registration
 * with Android's `AudioService` (via `PlayerBase` / `IAudioService.trackPlayer()`).
 *
 * When `NativeDirectAudioTrack` feeds PCM directly to hardware via `AUDIO_OUTPUT_FLAG_DIRECT`,
 * the standard Java `AudioTrack` is bypassed. Without an active Java `AudioTrack`, Android's
 * `AudioPlaybackMonitor` and `MediaSessionService` have no record that this app is outputting
 * audio, causing the system to route headset button events (`KEYCODE_HEADSETHOOK`, `KEYCODE_MEDIA_*`)
 * away to whatever background app last played standard audio.
 *
 * This lightweight static-buffer track runs silently (volume = 0) in the background whenever
 * native playback is active, ensuring proper media button routing and system audio state tracking.
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

