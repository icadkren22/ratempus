package com.eddyizm.tempus.audio.usb

import android.util.Log
import androidx.media3.common.DeviceInfo
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player

private const val TAG = "UsbExclusivePlayer"

/**
 * ForwardingPlayer that intercepts device volume and routing for USB Exclusive DAC.
 *
 * When USB Exclusive mode is active, it reports [DeviceInfo.PLAYBACK_TYPE_REMOTE]
 * so Android's System UI and MediaSession display the dedicated remote volume slider
 * on the lock screen, notification panel, and volume rocker.
 */
class UsbExclusiveForwardingPlayer(
    player: Player,
    private val volumeProvider: UsbVirtualCastVolumeProvider
) : ForwardingPlayer(player) {

    override fun addListener(listener: Player.Listener) {
        super.addListener(listener)
        volumeProvider.registerPlayerListener(listener)
    }

    override fun removeListener(listener: Player.Listener) {
        super.removeListener(listener)
        volumeProvider.unregisterPlayerListener(listener)
    }

    override fun getDeviceInfo(): DeviceInfo {
        return volumeProvider.getDeviceInfo()
    }

    override fun getDeviceVolume(): Int {
        if (volumeProvider.isUsbExclusiveActive) {
            return volumeProvider.currentVolumeIndex
        }
        return super.getDeviceVolume()
    }

    override fun setDeviceVolume(volume: Int) {
        if (volumeProvider.isUsbExclusiveActive) {
            Log.d(TAG, "setDeviceVolume: $volume")
            volumeProvider.setVolume(volume)
        } else {
            super.setDeviceVolume(volume)
        }
    }

    override fun setDeviceVolume(volume: Int, flags: Int) {
        if (volumeProvider.isUsbExclusiveActive) {
            Log.d(TAG, "setDeviceVolume (flags=$flags): $volume")
            volumeProvider.setVolume(volume)
        } else {
            super.setDeviceVolume(volume, flags)
        }
    }

    override fun increaseDeviceVolume() {
        if (volumeProvider.isUsbExclusiveActive) {
            Log.d(TAG, "increaseDeviceVolume")
            volumeProvider.adjustVolume(1)
        } else {
            super.increaseDeviceVolume()
        }
    }

    override fun increaseDeviceVolume(flags: Int) {
        if (volumeProvider.isUsbExclusiveActive) {
            Log.d(TAG, "increaseDeviceVolume (flags=$flags)")
            volumeProvider.adjustVolume(1)
        } else {
            super.increaseDeviceVolume(flags)
        }
    }

    override fun decreaseDeviceVolume() {
        if (volumeProvider.isUsbExclusiveActive) {
            Log.d(TAG, "decreaseDeviceVolume")
            volumeProvider.adjustVolume(-1)
        } else {
            super.decreaseDeviceVolume()
        }
    }

    override fun decreaseDeviceVolume(flags: Int) {
        if (volumeProvider.isUsbExclusiveActive) {
            Log.d(TAG, "decreaseDeviceVolume (flags=$flags)")
            volumeProvider.adjustVolume(-1)
        } else {
            super.decreaseDeviceVolume(flags)
        }
    }

    override fun isDeviceMuted(): Boolean {
        if (volumeProvider.isUsbExclusiveActive) {
            return volumeProvider.currentVolumeIndex == 0
        }
        return super.isDeviceMuted()
    }

    override fun setDeviceMuted(muted: Boolean) {
        if (volumeProvider.isUsbExclusiveActive) {
            Log.d(TAG, "setDeviceMuted: $muted")
            if (muted) volumeProvider.setVolume(0)
        } else {
            super.setDeviceMuted(muted)
        }
    }

    override fun setDeviceMuted(muted: Boolean, flags: Int) {
        if (volumeProvider.isUsbExclusiveActive) {
            Log.d(TAG, "setDeviceMuted (flags=$flags): $muted")
            if (muted) volumeProvider.setVolume(0)
        } else {
            super.setDeviceMuted(muted, flags)
        }
    }

    override fun isCommandAvailable(command: Int): Boolean {
        if (volumeProvider.isUsbExclusiveActive) {
            when (command) {
                Player.COMMAND_GET_DEVICE_VOLUME,
                Player.COMMAND_SET_DEVICE_VOLUME,
                Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
                Player.COMMAND_ADJUST_DEVICE_VOLUME,
                Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS -> return true
            }
        }
        return super.isCommandAvailable(command)
    }

    override fun getAvailableCommands(): Player.Commands {
        val base = super.getAvailableCommands()
        if (volumeProvider.isUsbExclusiveActive) {
            return base.buildUpon()
                .add(Player.COMMAND_GET_DEVICE_VOLUME)
                .add(Player.COMMAND_SET_DEVICE_VOLUME)
                .add(Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS)
                .add(Player.COMMAND_ADJUST_DEVICE_VOLUME)
                .add(Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS)
                .build()
        }
        return base
    }
}
