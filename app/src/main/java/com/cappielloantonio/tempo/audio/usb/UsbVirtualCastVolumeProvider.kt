package com.cappielloantonio.tempo.audio.usb

import android.content.Context
import android.media.AudioManager
import android.media.MediaRouter
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.DeviceInfo
import androidx.media3.common.Player
import androidx.preference.PreferenceManager

private const val TAG = "UsbVirtualCastVolume"
private const val PREF_USB_VOLUME_INDEX = "usb_exclusive_volume_index"
private const val DEFAULT_VOLUME_INDEX = 30 // ~30% on slider = ~0.0135 gain (-37 dB)

/**
 * Virtual Cast / Remote Volume Provider.
 *
 * Emulates a remote cast route via Android's [MediaRouter] and Media3 [DeviceInfo.PLAYBACK_TYPE_REMOTE]
 * to intercept hardware volume keys (Vol +/-) and lockscreen/system volume sliders while USB DAC Exclusive
 * mode is active.
 *
 * Maps slider level (0..100) to a cubic perceptual gain curve clamped at 0.50f max:
 *   gain(x) = 0.50f * (x / 100.0f)^3
 */
class UsbVirtualCastVolumeProvider private constructor(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

    private var mediaRouter: MediaRouter? = null
    private var userRoute: MediaRouter.UserRouteInfo? = null

    /** Reference to the active native output — kept for HW volume delivery. */
    private var exclusiveOutput: UsbExclusiveOutput? = null

    var isUsbExclusiveActive: Boolean = false
        private set

    var currentVolumeIndex: Int = prefs.getInt(PREF_USB_VOLUME_INDEX, DEFAULT_VOLUME_INDEX).coerceIn(0, 100)
        private set

    private var gainCallback: ((Float) -> Unit)? = null
    private val playerListeners = mutableListOf<Player.Listener>()

    val currentGain: Float
        get() = computePerceptualGain(currentVolumeIndex)

    fun registerPlayerListener(listener: Player.Listener) {
        if (!playerListeners.contains(listener)) {
            playerListeners.add(listener)
        }
    }

    fun unregisterPlayerListener(listener: Player.Listener) {
        playerListeners.remove(listener)
    }

    fun setActive(active: Boolean, output: UsbExclusiveOutput? = null, callback: ((Float) -> Unit)? = null) {
        isUsbExclusiveActive = active
        if (output != null) exclusiveOutput = output
        if (callback != null) gainCallback = callback

        if (active) {
            // Apply current volume immediately using whichever mode is configured
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

    /**
     * Applies the current [currentVolumeIndex] using either Hardware DAC volume or Software gain,
     * depending on [Preferences.isUsbDacHwVolumeEnabled] and whether [exclusiveOutput] is set.
     */
    private fun applyCurrentVolume() {
        val hwEnabled = com.cappielloantonio.tempo.util.Preferences.isUsbDacHwVolumeEnabled()
        val out = exclusiveOutput

        if (hwEnabled && out != null) {
            // HW mode: send volume directly to DAC Feature Unit 2 (instant, no buffer latency)
            out.setHwVolume(true, currentVolumeIndex)
            // Software gain -> 1.0f (unity / pass-through, avoids double attenuation)
            gainCallback?.invoke(1.0f)
            Log.i(TAG, "USB HW volume: $currentVolumeIndex% -> DAC Feature Unit 2")
        } else {
            // SW mode: disable HW volume if it was previously enabled
            if (hwEnabled && out == null) {
                Log.w(TAG, "HW volume enabled but no active output — falling back to SW gain")
            }
            // Ensure DAC is at 0 dB if we're in SW mode and have an output
            out?.setHwVolume(false)
            val gain = computePerceptualGain(currentVolumeIndex)
            gainCallback?.invoke(gain)
            Log.i(TAG, "USB SW volume: $currentVolumeIndex% -> gain=$gain")
        }
    }

    fun adjustVolume(direction: Int) {
        val step = 2 // 2% per click
        val newVol = (currentVolumeIndex + direction * step).coerceIn(0, 100)
        setVolume(newVol)
    }

    private fun startMediaRouter() {
        mainHandler.post {
            try {
                val router = context.getSystemService(Context.MEDIA_ROUTER_SERVICE) as? MediaRouter ?: return@post
                mediaRouter = router

                val category = router.createRouteCategory("USB DAC Exclusive", false)
                val route = router.createUserRoute(category).apply {
                    name = "USB DAC (Exclusive)"
                    playbackType = MediaRouter.RouteInfo.PLAYBACK_TYPE_REMOTE
                    playbackStream = AudioManager.STREAM_MUSIC
                    volumeHandling = MediaRouter.RouteInfo.PLAYBACK_VOLUME_VARIABLE
                    volumeMax = 100
                    volume = currentVolumeIndex

                    setVolumeCallback(object : MediaRouter.VolumeCallback() {
                        override fun onVolumeUpdateRequest(route: MediaRouter.RouteInfo, direction: Int) {
                            adjustVolume(direction)
                        }

                        override fun onVolumeSetRequest(route: MediaRouter.RouteInfo, volume: Int) {
                            setVolume(volume)
                        }
                    })
                }

                userRoute = route
                router.addUserRoute(route)
                router.selectRoute(MediaRouter.ROUTE_TYPE_USER, route)
                Log.i(TAG, "Virtual cast route registered: volume=$currentVolumeIndex gain=$currentGain")
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to register virtual cast route: ${t.message}")
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
            return 0.50f * fraction * fraction * fraction
        }
    }
}
