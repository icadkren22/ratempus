package com.eddyizm.tempus.audio.usb

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import java.nio.ByteBuffer

private const val TAG = "UsbExclusiveOutput"

data class UsbAudioConfig(
    val usbDevice: UsbDevice,
    val streamingIface: Int,
    val altSetting: Int,
    val endpointAddress: Int,
    val maxPacketSize: Int,
    val sampleRate: Int,
    val channelCount: Int,
    val bitDepth: Int
)

/**
 * Userspace USB Audio Class (UAC2) output driver bridge.
 */
class UsbExclusiveOutput(private val context: Context) {

    private var nativeHandle: Long = 0L
    private var usbConnection: UsbDeviceConnection? = null

    /**
     * Inspects USB descriptors and selects the optimal streaming configuration.
     */
    fun findBestConfig(device: UsbDevice, desiredSampleRate: Int): UsbAudioConfig? {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            // AudioStreaming interface (class=AUDIO (1), subclass=2)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_AUDIO && iface.interfaceSubclass == 2) {
                for (e in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(e)
                    if (ep.direction == UsbConstants.USB_DIR_OUT &&
                        ep.type == UsbConstants.USB_ENDPOINT_XFER_ISOC) {

                        val alt = if (iface.alternateSetting != 0) iface.alternateSetting else 3
                        val maxPkt = if (ep.maxPacketSize > 0) ep.maxPacketSize else 384
                        val bitDepth = if (alt == 1) 16 else if (alt == 2) 24 else 32

                        Log.i(TAG, "Selected UAC streaming config: iface=${iface.id} alt=$alt " +
                                "ep=0x${ep.address.toString(16)} maxPkt=$maxPkt bitDepth=$bitDepth sr=$desiredSampleRate")

                        return UsbAudioConfig(
                            usbDevice = device,
                            streamingIface = iface.id,
                            altSetting = alt,
                            endpointAddress = ep.address,
                            maxPacketSize = maxPkt,
                            sampleRate = desiredSampleRate,
                            channelCount = 2,
                            bitDepth = bitDepth
                        )
                    }
                }

                // If endpoints are on alternate settings not directly returned by getEndpoint(0)
                // Use standard High-Speed UAC2 defaults
                return UsbAudioConfig(
                    usbDevice = device,
                    streamingIface = iface.id,
                    altSetting = 3, // 32-bit linear PCM
                    endpointAddress = 0x01, // Standard isochronous OUT ep 1
                    maxPacketSize = 384,
                    sampleRate = desiredSampleRate,
                    channelCount = 2,
                    bitDepth = 32
                )
            }
        }
        return null
    }

    /**
     * Opens the USB device and starts exclusive native isochronous audio streaming.
     */
    fun open(config: UsbAudioConfig, srcEncoding: Int = 4, srcChannelCount: Int = 2): Boolean {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        if (!usbManager.hasPermission(config.usbDevice)) {
            Log.e(TAG, "No USB permission for ${config.usbDevice.productName}")
            return false
        }
        val conn = usbManager.openDevice(config.usbDevice)
        if (conn == null) {
            Log.e(TAG, "openDevice failed for ${config.usbDevice.productName}")
            return false
        }

        usbConnection = conn
        val usbFd = conn.fileDescriptor
        Log.i(TAG, "Opened USB device fd=$usbFd for ${config.usbDevice.productName} (srcEnc=$srcEncoding srcCh=$srcChannelCount sr=${config.sampleRate})")

        nativeHandle = nativeOpen(
            usbFd,
            config.streamingIface,
            config.altSetting,
            config.endpointAddress,
            config.maxPacketSize,
            config.sampleRate,
            config.channelCount,
            config.bitDepth,
            srcEncoding,
            srcChannelCount
        )

        if (nativeHandle == 0L) {
            Log.e(TAG, "nativeOpen failed")
            conn.close()
            usbConnection = null
            return false
        }

        val started = nativeStart(nativeHandle)
        if (!started) {
            Log.e(TAG, "nativeStart failed")
            close()
            return false
        }

        Log.i(TAG, "USB Exclusive streaming started: ${config.sampleRate}Hz ${config.bitDepth}bit via iface=${config.streamingIface} alt=${config.altSetting}")
        return true
    }

    fun start(): Boolean {
        if (nativeHandle != 0L) {
            return nativeStart(nativeHandle)
        }
        return false
    }

    fun write(buffer: ByteBuffer, offset: Int, size: Int): Int {
        if (nativeHandle == 0L) return -1
        return nativeWrite(nativeHandle, buffer, offset, size)
    }

    fun getPositionUs(): Long {
        if (nativeHandle != 0L) {
            return nativeGetPositionUs(nativeHandle)
        }
        return 0L
    }

    /**
     * Controls DAC Hardware Volume via UAC2 Feature Unit.
     * [volumeIndex]: 0..100
     */
    fun setHwVolume(enabled: Boolean, volumeIndex: Int = 50) {
        if (nativeHandle == 0L) return
        val minDb256 = -20480 // -80 dB in 1/256 dB units
        val volDb256 = if (volumeIndex <= 0) minDb256
                       else (minDb256 * (100 - volumeIndex) / 100)
        nativeSetHwVolume(nativeHandle, enabled, volDb256.toShort())
    }

    fun stop() {
        if (nativeHandle != 0L) nativeStop(nativeHandle)
    }

    fun close() {
        if (nativeHandle != 0L) {
            nativeClose(nativeHandle)
            nativeHandle = 0L
        }
        usbConnection?.close()
        usbConnection = null
    }

    // ─── JNI ────────────────────────────────────────────────────────────────

    private external fun nativeOpen(
        fd: Int, iface: Int, alt: Int, epAddr: Int,
        maxPacketSize: Int, sampleRate: Int,
        channelCount: Int, bitDepth: Int,
        srcEncoding: Int, srcChannelCount: Int
    ): Long

    private external fun nativeStart(handle: Long): Boolean
    private external fun nativeWrite(handle: Long, buffer: ByteBuffer, offset: Int, size: Int): Int
    private external fun nativeGetPositionUs(handle: Long): Long
    private external fun nativeSetHwVolume(handle: Long, enabled: Boolean, volDb256: Short)
    private external fun nativeStop(handle: Long)
    private external fun nativeClose(handle: Long)

    companion object {
        init {
            System.loadLibrary("directaudio")
        }
    }
}
