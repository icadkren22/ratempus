package com.cappielloantonio.tempo.audio.usb

import android.content.Context
import android.media.AudioManager
import android.media.MediaRouter
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.preference.PreferenceManager

private const val TAG = "UsbVirtualCastVolume"
private const val PREF_USB_VOLUME_INDEX = "usb_exclusive_volume_index"
private const val DEFAULT_VOLUME_INDEX = 30 // ~30% on slider = ~0.0135 gain (-37 dB)

/**
 * Virtual Cast / Remote Volume Provider.
 *
 * Emulates a remote cast route via Android's [MediaRouter] to intercept hardware
 * volume keys (Vol +/-) and lockscreen/system volume sliders while USB DAC Exclusive
 * mode is active.
 *
 * Maps slider level (0..100) to a cubic perceptual gain curve clamped at 0.50f max:
 *   gain(x) = 0.50f * (x / 100.0f)^3
 */
class UsbVirtualCastVolumeProvider(
    private val context: Context,
    private val onGainChanged: (Float) -> Unit
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

    private var mediaRouter: MediaRouter? = null
    private var userRoute: MediaRouter.UserRouteInfo? = null
    private var currentVolumeIndex: Int = prefs.getInt(PREF_USB_VOLUME_INDEX, DEFAULT_VOLUME_INDEX).coerceIn(0, 100)

    val currentGain: Float
        get() = computePerceptualGain(currentVolumeIndex)

    fun start() {
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
                            // direction: +1 for Vol Up, -1 for Vol Down
                            val step = 2 // 2% per volume step
                            val nextVol = (currentVolumeIndex + direction * step).coerceIn(0, 100)
                            setVolume(nextVol)
                        }

                        override fun onVolumeSetRequest(route: MediaRouter.RouteInfo, volume: Int) {
                            setVolume(volume.coerceIn(0, 100))
                        }
                    })
                }

                userRoute = route
                router.addUserRoute(route)
                router.selectRoute(MediaRouter.ROUTE_TYPE_USER, route)

                val gain = computePerceptualGain(currentVolumeIndex)
                onGainChanged(gain)
                Log.i(TAG, "Virtual cast route registered: volume=$currentVolumeIndex gain=$gain")
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to register virtual cast route: ${t.message}")
            }
        }
    }

    fun setVolume(volumeIndex: Int) {
        val clamped = volumeIndex.coerceIn(0, 100)
        currentVolumeIndex = clamped
        prefs.edit().putInt(PREF_USB_VOLUME_INDEX, clamped).apply()

        mainHandler.post {
            userRoute?.volume = clamped
        }

        val gain = computePerceptualGain(clamped)
        onGainChanged(gain)
        Log.i(TAG, "USB Exclusive volume updated: $clamped% -> gain=$gain")
    }

    fun stop() {
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

    companion object {
        /**
         * Maps integer volume (0..100) to linear amplitude gain (0.0 .. 0.50) using a cubic curve.
         */
        fun computePerceptualGain(volumeIndex: Int): Float {
            val fraction = volumeIndex.coerceIn(0, 100) / 100.0f
            // Cubic curve with 0.5f max safe ceiling
            return 0.50f * fraction * fraction * fraction
        }
    }
}
