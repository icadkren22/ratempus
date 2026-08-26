package com.eddyizm.tempus.util

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

    @Volatile
    private var currentUsbConfig: com.eddyizm.tempus.audio.usb.UsbAudioConfig? = null

    @Volatile
    private var currentDecoderName: String? = null

    @JvmStatic
    fun updateDecoderName(name: String?) {
        currentDecoderName = name
    }

    @JvmStatic
    fun getCurrentDecoderName(): String? = currentDecoderName

    @JvmStatic
    fun updateUsbAudioConfig(config: com.eddyizm.tempus.audio.usb.UsbAudioConfig?) {
        currentUsbConfig = config
    }

    @JvmStatic
    fun getCurrentUsbConfig(): com.eddyizm.tempus.audio.usb.UsbAudioConfig? = currentUsbConfig

    @JvmStatic
    fun updateAudioTrackConfig(config: AudioSink.AudioTrackConfig?) {
        currentConfig = config
    }

    @JvmStatic
    fun getCurrentConfig(): AudioSink.AudioTrackConfig? = currentConfig

    @JvmStatic
    fun getCurrentSampleRate(): Int = currentConfig?.sampleRate ?: 0

    @JvmStatic
    fun isUsbExclusiveActive(context: Context): Boolean {
        if (com.eddyizm.tempus.audio.usb.UsbVirtualCastVolumeProvider.getInstance(context).isUsbExclusiveActive) {
            return true
        }
        val usb = currentUsbConfig
        return usb != null && Preferences.isUsbDacExclusiveEnabled() &&
                com.eddyizm.tempus.audio.usb.UsbDacManager.getInstance(context).isUsbDacConnected
    }

    @JvmStatic
    fun isDirectAudioSupported(): Boolean {
        return try {
            val clazz = Class.forName("com.eddyizm.tempus.audio.NativeDirectAudioTrack")
            val method = clazz.getMethod("isSupported")
            method.invoke(null) as? Boolean ?: false
        } catch (_: Throwable) {
            false
        }
    }

    @JvmStatic
    fun getHardwareSampleRate(context: Context): Int {
        if (isUsbExclusiveActive(context)) {
            val usb = currentUsbConfig
            if (usb != null && usb.sampleRate > 0) return usb.sampleRate
        }
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
    fun getHardwareBitDepthString(context: Context? = null): String {
        if (context != null && isUsbExclusiveActive(context)) {
            val usb = currentUsbConfig
            val bits = usb?.bitDepth ?: 32
            return "$bits-bit PCM (Int$bits)"
        }
        return if (isDirectAudioSupported()) {
            val track = com.eddyizm.tempus.audio.NativeDirectAudioTrack.getActiveTrack()
            val bits = track?.actualBitDepth ?: 32
            "$bits-bit PCM (Int$bits)"
        } else {
            "16-bit PCM (Int16)"
        }
    }

    @JvmStatic
    fun getOutputSampleRateString(context: Context): String {
        if (isUsbExclusiveActive(context)) {
            val usb = currentUsbConfig
            if (usb != null && usb.sampleRate > 0) return formatSampleRate(usb.sampleRate)
        }
        val config = currentConfig
        if (config != null && config.sampleRate > 0) {
            return formatSampleRate(config.sampleRate)
        }
        val nativeRate = getHardwareSampleRate(context)
        return formatSampleRate(nativeRate)
    }

    @JvmStatic
    fun getOutputBitDepthString(context: Context): String {
        if (isUsbExclusiveActive(context)) {
            val usb = currentUsbConfig
            val bits = usb?.bitDepth ?: 32
            return "$bits-bit PCM"
        }
        if (isDirectAudioSupported()) {
            val track = com.eddyizm.tempus.audio.NativeDirectAudioTrack.getActiveTrack()
            val bits = track?.actualBitDepth ?: 32
            return "$bits-bit PCM"
        }
        val config = currentConfig
        if (config != null) {
            if (config.offload) {
                return "Direct / Passthrough"
            }
            return when (config.encoding) {
                C.ENCODING_PCM_16BIT -> "16-bit PCM"
                C.ENCODING_PCM_24BIT -> "24-bit PCM"
                C.ENCODING_PCM_32BIT -> "32-bit PCM"
                C.ENCODING_PCM_FLOAT -> "32-bit Float PCM"
                C.ENCODING_PCM_8BIT -> "8-bit PCM"
                else -> "16-bit PCM"
            }
        }
        return "16-bit PCM"
    }

    @JvmStatic
    fun getOutputChannelsString(context: Context? = null): String {
        if (context != null && isUsbExclusiveActive(context)) {
            val usb = currentUsbConfig
            val ch = usb?.channelCount ?: 2
            return if (ch == 1) "Mono (1.0)" else "Stereo (2.0)"
        }
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
    fun getOutputModeString(context: Context? = null): String {
        if (context != null && isUsbExclusiveActive(context)) {
            return "UAC2 usbfs driver (Bit-perfect)"
        }
        val config = currentConfig
        return when {
            config != null && config.offload -> "Direct Audio Offload (Bit-perfect)"
            isDirectAudioSupported() -> "Hi-Res Direct HD (Bit-perfect)"
            else -> "Android AudioTrack"
        }
    }

    @JvmStatic
    fun getActiveOutputDeviceString(context: Context): String {
        if (isUsbExclusiveActive(context)) {
            val usb = currentUsbConfig
            val devName = usb?.usbDevice?.productName
            return if (!devName.isNullOrBlank()) {
                "USB DAC ($devName • Exclusive Mode)"
            } else {
                "USB Audio DAC (Exclusive Mode)"
            }
        }
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
    @JvmOverloads
    fun getShortSummary(context: Context? = null): String {
        if (context != null && isUsbExclusiveActive(context)) {
            val usb = currentUsbConfig
            val rate = if (usb != null && usb.sampleRate > 0) formatKhzShort(usb.sampleRate) else "Direct"
            val bitDepth = "${usb?.bitDepth ?: 32}-bit"
            return "$bitDepth • $rate (UAC2 Exclusive)"
        }
        val config = currentConfig
        return if (config != null && config.sampleRate > 0) {
            val rate = formatKhzShort(config.sampleRate)
            val bitDepth = if (isDirectAudioSupported()) {
                val track = com.eddyizm.tempus.audio.NativeDirectAudioTrack.getActiveTrack()
                "${track?.actualBitDepth ?: 32}-bit"
            } else {
                when (config.encoding) {
                    C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_32BIT -> "32-bit"
                    C.ENCODING_PCM_24BIT -> "24-bit"
                    else -> "16-bit"
                }
            }
            val driver = if (isDirectAudioSupported()) "Direct HD" else "AudioTrack"
            "$bitDepth • $rate ($driver)"
        } else {
            if (isDirectAudioSupported()) "Direct HD" else "AudioTrack"
        }
    }

    @JvmStatic
    fun getShortOutputFormatString(context: Context): String {
        if (isUsbExclusiveActive(context)) {
            val sampleRate = getOutputSampleRateString(context).substringBefore(" (")
            val bitDepth = getOutputBitDepthString(context)
            return "OUT: $bitDepth • $sampleRate (UAC2 Exclusive)"
        }
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
