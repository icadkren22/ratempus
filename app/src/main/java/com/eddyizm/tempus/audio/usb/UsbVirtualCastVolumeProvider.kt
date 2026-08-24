package com.eddyizm.tempus.audio.usb

import android.content.Context
import android.media.AudioManager
import android.media.MediaRouter
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.DeviceInfo
import androidx.media3.common.Player
import androidx.preference.PreferenceManager
import com.eddyizm.tempus.util.Preferences

private const val TAG = "UsbVirtualCastVolume"
private const val PREF_USB_VOLUME_INDEX = "usb_exclusive_volume_index"
private const val DEFAULT_VOLUME_INDEX = 40 // Default 40%

/**
 * Manages volume control when USB Exclusive DAC is active.
 * Emulates a remote cast route via [MediaRouter] and Media3 [DeviceInfo.PLAYBACK_TYPE_REMOTE]
 * to intercept hardware volume keys and lockscreen sliders, forwarding level updates directly
 * to DAC Hardware Volume (Feature Unit 2).
 */
class UsbVirtualCastVolumeProvider private constructor(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

    private var mediaRouter: MediaRouter? = null
    private var userRoute: MediaRouter.UserRouteInfo? = null
    private var exclusiveOutput: UsbExclusiveOutput? = null
    private val playerListeners = mutableListOf<Player.Listener>()

    private val prefListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when {
            key == Preferences.USB_DAC_HW_VOLUME_ENABLED && isUsbExclusiveActive -> {
                Log.i(TAG, "USB DAC HW Volume preference toggled -> re-applying volume mode")
                applyCurrentVolume()
            }
            key == Preferences.USB_DAC_EXCLUSIVE_ENABLED && !Preferences.isUsbDacExclusiveEnabled() && isUsbExclusiveActive -> {
                Log.i(TAG, "USB Exclusive disabled in settings -> stopping and releasing DAC")
                val out = exclusiveOutput
                if (out != null) {
                    out.stop()
                    out.close()
                }
                setActive(false)
            }
        }
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
    }

    var isUsbExclusiveActive: Boolean = false
        private set

    var currentVolumeIndex: Int = prefs.getInt(PREF_USB_VOLUME_INDEX, DEFAULT_VOLUME_INDEX).coerceIn(0, 100)
        private set

    fun registerPlayerListener(listener: Player.Listener) {
        if (!playerListeners.contains(listener)) {
            playerListeners.add(listener)
        }
    }

    fun unregisterPlayerListener(listener: Player.Listener) {
        playerListeners.remove(listener)
    }

    fun setActive(active: Boolean, output: UsbExclusiveOutput? = null) {
        isUsbExclusiveActive = active
        if (output != null) exclusiveOutput = output

        if (active) {
            applyCurrentVolume()
            startMediaRouter()
        } else {
            exclusiveOutput = null
            stopMediaRouter()
        }

        notifyDeviceInfoChanged()
    }

    fun setVolume(volumeIndex: Int) {
        val clamped = volumeIndex.coerceIn(0, 100)
        currentVolumeIndex = clamped
        prefs.edit().putInt(PREF_USB_VOLUME_INDEX, clamped).apply()

        mainHandler.post {
            userRoute?.volume = clamped
        }

        applyCurrentVolume()
        notifyVolumeChanged(clamped)
    }

    fun adjustVolume(direction: Int) {
        val step = 2 // 2% per volume key click
        val newVol = (currentVolumeIndex + direction * step).coerceIn(0, 100)
        setVolume(newVol)
    }

    private fun applyCurrentVolume() {
        val hwEnabled = Preferences.isUsbDacHwVolumeEnabled()
        val out = exclusiveOutput
        val gain = computePerceptualGain(currentVolumeIndex)
        if (hwEnabled && out != null) {
            out.setHwVolume(true, currentVolumeIndex, gain)
            Log.i(TAG, "USB HW volume: $currentVolumeIndex% -> DAC Feature Unit 2")
        } else if (out != null) {
            out.setHwVolume(false, currentVolumeIndex, gain)
            Log.i(TAG, "USB SW volume: $currentVolumeIndex% -> gain=$gain")
        }
    }

    private fun startMediaRouter() {
        mainHandler.post {
            try {
                val router = context.getSystemService(Context.MEDIA_ROUTER_SERVICE) as? MediaRouter ?: return@post
                mediaRouter = router

                if (userRoute == null) {
                    val category = router.createRouteCategory("USB DAC Exclusive", false)
                    val route = router.createUserRoute(category).apply {
                        name = "USB DAC (Exclusive)"
                        playbackType = MediaRouter.RouteInfo.PLAYBACK_TYPE_REMOTE
                        playbackStream = AudioManager.STREAM_MUSIC
                        volumeHandling = MediaRouter.RouteInfo.PLAYBACK_VOLUME_VARIABLE
                        volumeMax = 100
                        volume = currentVolumeIndex

                        setVolumeCallback(object : MediaRouter.VolumeCallback() {
                            override fun onVolumeUpdateRequest(r: MediaRouter.RouteInfo, direction: Int) {
                                adjustVolume(direction)
                            }

                            override fun onVolumeSetRequest(r: MediaRouter.RouteInfo, volume: Int) {
                                setVolume(volume)
                            }
                        })
                    }

                    userRoute = route
                    router.addUserRoute(route)
                    router.selectRoute(MediaRouter.ROUTE_TYPE_USER, route)
                    Log.i(TAG, "Virtual cast route registered: volume=$currentVolumeIndex")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to start MediaRouter virtual route: ${t.message}")
            }
        }
    }

    private fun stopMediaRouter() {
        mainHandler.post {
            try {
                val router = mediaRouter
                val route = userRoute
                if (router != null && route != null) {
                    router.removeUserRoute(route)
                }
                userRoute = null
                mediaRouter = null
                Log.i(TAG, "Virtual cast route unregistered")
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to unregister virtual cast route: ${t.message}")
            }
        }
    }

    private fun notifyDeviceInfoChanged() {
        val info = getDeviceInfo()
        mainHandler.post {
            for (listener in playerListeners) {
                listener.onDeviceInfoChanged(info)
            }
        }
    }

    private fun notifyVolumeChanged(volume: Int) {
        mainHandler.post {
            for (listener in playerListeners) {
                listener.onDeviceVolumeChanged(volume, volume == 0)
            }
        }
    }

    fun getDeviceInfo(): DeviceInfo {
        if (isUsbExclusiveActive) {
            return DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE)
                .setMinVolume(0)
                .setMaxVolume(100)
                .build()
        }
        return DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_LOCAL).build()
    }

    companion object {
        @Volatile
        private var instance: UsbVirtualCastVolumeProvider? = null

        fun getInstance(context: Context): UsbVirtualCastVolumeProvider {
            return instance ?: synchronized(this) {
                instance ?: UsbVirtualCastVolumeProvider(context.applicationContext).also { instance = it }
            }
        }

        fun computePerceptualGain(volumeIndex: Int): Float {
            val fraction = volumeIndex.coerceIn(0, 100) / 100.0f
            return 0.40f * fraction * fraction * fraction
        }
    }
}
