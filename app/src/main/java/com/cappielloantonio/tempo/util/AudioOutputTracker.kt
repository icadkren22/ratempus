package com.cappielloantonio.tempo.util

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Build
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import java.text.DecimalFormat

@UnstableApi
object AudioOutputTracker {

    @Volatile
    private var currentConfig: AudioSink.AudioTrackConfig? = null

    @JvmStatic
    fun updateAudioTrackConfig(config: AudioSink.AudioTrackConfig?) {
        currentConfig = config
    }

    @JvmStatic
    fun getCurrentConfig(): AudioSink.AudioTrackConfig? = currentConfig

    @JvmStatic
    fun getCurrentSampleRate(): Int = currentConfig?.sampleRate ?: 0

    @JvmStatic
    fun isDirectAudioSupported(): Boolean {
        return try {
            val clazz = Class.forName("com.cappielloantonio.tempo.audio.NativeDirectAudioTrack")
            val method = clazz.getMethod("isSupported")
            method.invoke(null) as? Boolean ?: false
        } catch (_: Throwable) {
            false
        }
    }

    @JvmStatic
    fun getHardwareSampleRate(context: Context): Int {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val nativeRate = audioManager?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48000
        return if (isDirectAudioSupported()) {
            val config = currentConfig
            if (config != null && config.sampleRate > 0) config.sampleRate else nativeRate
        } else {
            nativeRate
        }
    }

    @JvmStatic
    fun getHardwareSampleRateString(context: Context): String {
        return formatSampleRate(getHardwareSampleRate(context))
    }

    @JvmStatic
    fun getHardwareBitDepthString(): String {
        return if (isDirectAudioSupported()) {
            val config = currentConfig
            when (config?.encoding) {
                C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_32BIT -> "32-bit PCM (Int32)"
                C.ENCODING_PCM_24BIT -> "24-bit PCM (Int24)"
                else -> "16-bit PCM (Int16)"
            }
        } else {
            "16-bit PCM (Int16)"
        }
    }

    @JvmStatic
    fun getOutputSampleRateString(context: Context): String {
        val config = currentConfig
        if (config != null && config.sampleRate > 0) {
            return formatSampleRate(config.sampleRate)
        }

        val nativeRate = getHardwareSampleRate(context)
        return formatSampleRate(nativeRate)
    }

    @JvmStatic
    fun getOutputBitDepthString(context: Context): String {
        val config = currentConfig
        if (config != null) {
            if (config.offload) {
                return "Direct / Passthrough"
            }
            return when (config.encoding) {
                C.ENCODING_PCM_16BIT -> "16-bit PCM"
                C.ENCODING_PCM_24BIT -> "24-bit PCM"
                C.ENCODING_PCM_32BIT -> "32-bit PCM"
                C.ENCODING_PCM_FLOAT -> if (isDirectAudioSupported()) "32-bit PCM" else "32-bit Float PCM"
                C.ENCODING_PCM_8BIT -> "8-bit PCM"
                else -> "16-bit PCM"
            }
        }
        return "16-bit PCM"
    }

    @JvmStatic
    fun getOutputChannelsString(): String {
        val config = currentConfig
        if (config != null) {
            return when (config.channelConfig) {
                AudioFormat.CHANNEL_OUT_MONO -> "Mono (1.0)"
                AudioFormat.CHANNEL_OUT_STEREO -> "Stereo (2.0)"
                AudioFormat.CHANNEL_OUT_QUAD -> "Quad (4.0)"
                AudioFormat.CHANNEL_OUT_5POINT1 -> "5.1 Surround"
                AudioFormat.CHANNEL_OUT_7POINT1_SURROUND -> "7.1 Surround"
                else -> {
                    val count = Integer.bitCount(config.channelConfig)
                    if (count > 0) "$count channels" else "Stereo (2.0)"
                }
            }
        }
        return "Stereo (2.0)"
    }

    @JvmStatic
    fun getOutputModeString(): String {
        val config = currentConfig
        return when {
            config != null && config.offload -> "Direct Audio Offload (Bit-perfect)"
            isDirectAudioSupported() -> "Hi-Res Direct HD (Bit-perfect)"
            else -> "Android AudioTrack"
        }
    }

    @JvmStatic
    fun getActiveOutputDeviceString(context: Context): String {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return "Default Output"
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

        // Priority 1: USB Audio / DAC
        for (device in devices) {
            when (device.type) {
                AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_USB_ACCESSORY -> {
                    val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && !device.productName.isNullOrBlank()) {
                        "USB Audio (${device.productName})"
                    } else {
                        "USB DAC / Audio"
                    }
                    return name
                }
            }
        }

        // Priority 2: Bluetooth
        for (device in devices) {
            when (device.type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_BLE_SPEAKER -> {
                    val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && !device.productName.isNullOrBlank()) {
                        "Bluetooth (${device.productName})"
                    } else {
                        "Bluetooth Audio (A2DP)"
                    }
                    return name
                }
            }
        }

        // Priority 3: Wired Headset / Line Out / HDMI
        for (device in devices) {
            when (device.type) {
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> return "Wired Headphones / Headset"
                AudioDeviceInfo.TYPE_LINE_ANALOG,
                AudioDeviceInfo.TYPE_LINE_DIGITAL -> return "Line Out"
                AudioDeviceInfo.TYPE_HDMI,
                AudioDeviceInfo.TYPE_HDMI_ARC,
                AudioDeviceInfo.TYPE_HDMI_EARC -> return "HDMI Output"
            }
        }

        // Priority 4: Built-in Speaker / Earpiece
        for (device in devices) {
            when (device.type) {
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> return "Built-in Speaker"
                AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> return "Built-in Earpiece"
            }
        }

        return "Default Audio Output"
    }

    @JvmStatic
    fun getShortOutputFormatString(context: Context): String {
        return if (isDirectAudioSupported()) {
            val sampleRate = getOutputSampleRateString(context).substringBefore(" (")
            val bitDepth = getOutputBitDepthString(context)
            val mode = if (currentConfig?.offload == true) "Offload" else "Direct HD"
            "OUT: $bitDepth • $sampleRate ($mode)"
        } else {
            val hwRate = formatKhzShort(getHardwareSampleRate(context))
            "OUT: 16-bit PCM • $hwRate (AudioTrack)"
        }
    }

    private fun formatKhzShort(sampleRate: Int): String {
        val kHz = sampleRate / 1000.0
        return DecimalFormat("0.#").format(kHz) + " kHz"
    }

    private fun formatSampleRate(sampleRate: Int): String {
        val kHz = sampleRate / 1000.0
        return DecimalFormat("0.#").format(kHz) + " kHz (" + sampleRate + " Hz)"
    }
}
