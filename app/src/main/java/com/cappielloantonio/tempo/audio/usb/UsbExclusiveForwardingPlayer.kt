package com.cappielloantonio.tempo.audio.usb

import androidx.media3.common.DeviceInfo
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player

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
            volumeProvider.setVolume(volume)
        } else {
            super.setDeviceVolume(volume)
        }
    }

    override fun increaseDeviceVolume() {
        if (volumeProvider.isUsbExclusiveActive) {
            volumeProvider.adjustVolume(1)
        } else {
            super.increaseDeviceVolume()
        }
    }

    override fun decreaseDeviceVolume() {
        if (volumeProvider.isUsbExclusiveActive) {
            volumeProvider.adjustVolume(-1)
        } else {
            super.decreaseDeviceVolume()
        }
    }

    override fun isDeviceMuted(): Boolean {
        if (volumeProvider.isUsbExclusiveActive) {
            return volumeProvider.currentVolumeIndex == 0
        }
        return super.isDeviceMuted()
    }

    override fun getAvailableCommands(): Player.Commands {
        val base = super.getAvailableCommands()
        if (volumeProvider.isUsbExclusiveActive) {
            return base.buildUpon()
                .add(Player.COMMAND_GET_DEVICE_VOLUME)
                .add(Player.COMMAND_SET_DEVICE_VOLUME)
                .add(Player.COMMAND_ADJUST_DEVICE_VOLUME)
                .build()
        }
        return base
    }
}
