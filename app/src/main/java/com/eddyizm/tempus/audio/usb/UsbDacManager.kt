package com.eddyizm.tempus.audio.usb

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
private const val ACTION_USB_PERMISSION = "com.eddyizm.tempus.USB_PERMISSION"

/**
 * Manages USB DAC discovery, attachment/detachment lifecycle, and USB permissions.
 */
class UsbDacManager private constructor(private val context: Context) {

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var cachedDac: UsbDevice? = null
    private var permissionCallback: ((Boolean) -> Unit)? = null

    val isUsbDacConnected: Boolean
        get() = getUsbAudioDevice() != null

    val connectedUsbDevice: UsbDevice?
        get() = getUsbAudioDevice()

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    if (device != null && isAudioDevice(device)) {
                        Log.i(TAG, "USB Audio Device attached: ${device.productName} (${device.vendorId}:${device.productId})")
                        cachedDac = device
                        if (!usbManager.hasPermission(device)) {
                            requestPermission(device)
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    if (device != null && device == cachedDac) {
                        Log.i(TAG, "USB Audio Device detached: ${device.productName}")
                        cachedDac = null
                    }
                }
                ACTION_USB_PERMISSION -> {
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    Log.i(TAG, "USB Permission result for ${device?.productName}: granted=$granted")
                    permissionCallback?.invoke(granted)
                    permissionCallback = null
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
    }

    /**
     * Finds the first connected USB Audio Device.
     */
    fun getUsbAudioDevice(): UsbDevice? {
        cachedDac?.let { return it }
        for (device in usbManager.deviceList.values) {
            if (isAudioDevice(device)) {
                cachedDac = device
                Log.i(TAG, "Found USB Audio Device: ${device.productName} (${device.vendorId}:${device.productId})")
                return device
            }
        }
        return null
    }

    /**
     * Requests USB permission for the specified DAC device.
     */
    fun requestPermission(device: UsbDevice, onResult: ((Boolean) -> Unit)? = null) {
        if (usbManager.hasPermission(device)) {
            onResult?.invoke(true)
            return
        }
        permissionCallback = onResult
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val permissionIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
            flags
        )
        Log.i(TAG, "Requesting USB permission for ${device.productName}...")
        usbManager.requestPermission(device, permissionIntent)
    }

    private fun isAudioDevice(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_AUDIO) {
                return true
            }
        }
        return false
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
