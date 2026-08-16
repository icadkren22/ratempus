package com.cappielloantonio.tempo.audio.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log

private const val TAG = "UsbDacManager"
private const val ACTION_USB_PERMISSION = "com.cappielloantonio.tempo.USB_PERMISSION"

/**
 * Manages USB DAC connection detection and permission requests.
 */
class UsbDacManager(private val context: Context) {

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    var connectedUsbDevice: UsbDevice? = null
        private set

    val isUsbDacConnected: Boolean
        get() = connectedUsbDevice != null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    if (device != null && isAudioDevice(device)) {
                        Log.i(TAG, "USB Audio DAC attached: ${device.productName} (vid=${device.vendorId}, pid=${device.productId})")
                        connectedUsbDevice = device
                        requestPermission(device)
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    if (device != null && device == connectedUsbDevice) {
                        Log.i(TAG, "USB Audio DAC detached: ${device.productName}")
                        connectedUsbDevice = null
                    }
                }
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    Log.i(TAG, "USB permission result for ${device?.productName}: granted=$granted")
                }
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(usbReceiver, filter)
        }
        scanConnectedDevices()
    }

    fun scanConnectedDevices() {
        for ((_, device) in usbManager.deviceList) {
            if (isAudioDevice(device)) {
                Log.i(TAG, "Found USB Audio DAC: ${device.productName} (${device.vendorId}:${device.productId})")
                connectedUsbDevice = device
                if (!usbManager.hasPermission(device)) {
                    requestPermission(device)
                }
                break
            }
        }
    }

    fun requestPermission(device: UsbDevice) {
        try {
            if (usbManager.hasPermission(device)) return
            val intent = Intent(ACTION_USB_PERMISSION).apply {
                setPackage(context.packageName)
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pi = PendingIntent.getBroadcast(context, 0, intent, flags)
            usbManager.requestPermission(device, pi)
        } catch (e: Throwable) {
            Log.w(TAG, "requestPermission failed: ${e.message}")
        }
    }


    private fun isAudioDevice(device: UsbDevice): Boolean {
        if (device.deviceClass == UsbConstants.USB_CLASS_AUDIO) return true
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_AUDIO) return true
        }
        return false
    }

    fun release() {
        try {
            context.unregisterReceiver(usbReceiver)
        } catch (_: Exception) {}
    }

    companion object {
        @Volatile
        private var instance: UsbDacManager? = null

        fun getInstance(context: Context): UsbDacManager {
            return instance ?: synchronized(this) {
                instance ?: UsbDacManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
