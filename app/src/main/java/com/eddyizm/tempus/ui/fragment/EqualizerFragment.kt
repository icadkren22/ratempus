package com.eddyizm.tempus.ui.fragment

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import android.util.TypedValue
import android.widget.*
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.media3.common.util.UnstableApi
import com.eddyizm.tempus.R
import com.eddyizm.tempus.equalizer.EqualizerManager
import com.eddyizm.tempus.service.BaseMediaService
import com.eddyizm.tempus.service.MediaService
import com.eddyizm.tempus.ui.activity.MainActivity
import com.eddyizm.tempus.util.Preferences
import com.google.android.material.appbar.MaterialToolbar

@UnstableApi
class EqualizerFragment : Fragment() {

    private lateinit var activity: MainActivity
    private lateinit var root: View
    private lateinit var settingsToolbar: MaterialToolbar
    private var equalizerManager: EqualizerManager? = null
    private lateinit var eqBandsContainer: LinearLayout
    private lateinit var eqAdvancedContainer: LinearLayout
    private lateinit var eqPreampContainer: LinearLayout
    private lateinit var eqSwitch: Switch
    private lateinit var resetButton: Button
    private lateinit var safeSpace: Space
    private val bandSeekBars = mutableListOf<SeekBar>()
    private val bandDbLabels = mutableListOf<TextView>()
    private val bandResetButtons = mutableListOf<ImageView>()
    private val weightSeekBars = mutableListOf<SeekBar>()
    private val weightLabels = mutableListOf<TextView>()
    private val weightResetButtons = mutableListOf<ImageView>()
    private var maxAttenSeekBar: SeekBar? = null
    private var maxAttenLabel: TextView? = null
    private var maxAttenResetBtn: ImageView? = null
    private var softKneeSeekBar: SeekBar? = null
    private var softKneeLabel: TextView? = null
    private var softKneeResetBtn: ImageView? = null
    private var autoPreampSwitch: Switch? = null
    private var autoPreampContainer: LinearLayout? = null
    private var manualPreampSeekBar: SeekBar? = null
    private var manualPreampLabel: TextView? = null
    private var manualPreampResetBtn: ImageView? = null
    private var minLevelDb = -15
    private var receiverRegistered = false

    @OptIn(UnstableApi::class)
    override fun onAttach(context: Context) {
        super.onAttach(context)
        activity = requireActivity() as MainActivity
    }



    private val equalizerUpdatedReceiver = object : BroadcastReceiver() {
        @OptIn(UnstableApi::class)
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BaseMediaService.ACTION_EQUALIZER_UPDATED) {
                initUI()
                restoreEqualizerPreferences()
            }
        }
    }

    private val connection = object : ServiceConnection {
        @OptIn(UnstableApi::class)
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as BaseMediaService.LocalBinder
            equalizerManager = binder.getEqualizerManager()
            initUI()
            restoreEqualizerPreferences()
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            equalizerManager = null
        }
    }

    @OptIn(UnstableApi::class)
    override fun onStart() {
        super.onStart()
        Intent(requireContext(), MediaService::class.java).also { intent ->
            intent.action = BaseMediaService.ACTION_BIND_EQUALIZER
            requireActivity().bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                requireContext(),
                equalizerUpdatedReceiver,
                IntentFilter(BaseMediaService.ACTION_EQUALIZER_UPDATED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        }
    }

    @OptIn(UnstableApi::class)
    override fun onStop() {
        super.onStop()
        requireActivity().unbindService(connection)
        equalizerManager = null
        if (receiverRegistered) {
            try {
                requireContext().unregisterReceiver(equalizerUpdatedReceiver)
            } catch (_: Exception) {
                // ignore if not registered
            }
            receiverRegistered = false
        }

        activity.setBottomSheetVisibility(true);
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        root = inflater.inflate(R.layout.fragment_equalizer, container, false)
        eqSwitch = root.findViewById(R.id.equalizer_switch)
        eqSwitch.isChecked = Preferences.isEqualizerEnabled()
        eqSwitch.jumpDrawablesToCurrentState()

        initAppBar()

        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        eqBandsContainer = view.findViewById(R.id.eq_bands_container)
        eqAdvancedContainer = view.findViewById(R.id.eq_advanced_container)
        eqPreampContainer = view.findViewById(R.id.eq_preamp_container)
        resetButton = view.findViewById(R.id.equalizer_reset_button)
        safeSpace = view.findViewById(R.id.equalizer_bottom_space)
    }

    private fun initUI() {
        val manager = equalizerManager
        val notSupportedView = view?.findViewById<LinearLayout>(R.id.equalizer_managed_externally_container)
        val switchRow = view?.findViewById<View>(R.id.equalizer_switch_row)

        if (manager == null || manager.getNumberOfBands().toInt() == 0) {
            switchRow?.visibility = View.GONE
            resetButton.visibility = View.GONE
            eqBandsContainer.visibility = View.GONE
            eqAdvancedContainer.visibility = View.GONE
            safeSpace.visibility = View.GONE
            notSupportedView?.visibility = View.VISIBLE
            return
        }

        notSupportedView?.visibility = View.GONE
        switchRow?.visibility = View.VISIBLE
        resetButton.visibility = View.VISIBLE
        eqBandsContainer.visibility = View.VISIBLE
        safeSpace.visibility = View.VISIBLE

        eqSwitch.setOnCheckedChangeListener(null)
        updateUiEnabledState(eqSwitch.isChecked)
        eqSwitch.setOnCheckedChangeListener { _, isChecked ->
            manager.setEnabled(isChecked)
            Preferences.setEqualizerEnabled(isChecked)
            updateUiEnabledState(isChecked)
        }

        createManualPreampSlider()
        createBandSliders()
        if (Preferences.getSelectedEqualizer() == 1) {
            eqAdvancedContainer.visibility = View.VISIBLE
            createTuningSliders()
        } else {
            eqAdvancedContainer.visibility = View.GONE
        }

        resetButton.setOnClickListener {
            resetEqualizer()
            saveBandLevelsToPreferences()
        }
    }

    private fun updateUiEnabledState(isEnabled: Boolean) {
        resetButton.isEnabled = isEnabled
        bandSeekBars.forEach { it.isEnabled = isEnabled }
        bandResetButtons.forEachIndexed { i, btn ->
            val level = (bandSeekBars.getOrNull(i)?.progress ?: 0) + minLevelDb
            btn.isEnabled = isEnabled && level != 0
            btn.alpha = if (isEnabled && level != 0) 1.0f else 0.35f
        }

        maxAttenSeekBar?.isEnabled = isEnabled
        maxAttenResetBtn?.let { btn ->
            val curVal = maxAttenSeekBar?.progress?.toFloat() ?: 8f
            val isDiff = abs(curVal - Preferences.DEFAULT_MAX_ATTENUATION) > 0.1f
            btn.isEnabled = isEnabled && isDiff
            btn.alpha = if (isEnabled && isDiff) 1.0f else 0.35f
        }

        softKneeSeekBar?.isEnabled = isEnabled
        softKneeResetBtn?.let { btn ->
            val curVal = ((softKneeSeekBar?.progress ?: 60) + 10) / 100f
            val isDiff = abs(curVal - Preferences.DEFAULT_SOFT_KNEE_THRESHOLD) > 0.02f
            btn.isEnabled = isEnabled && isDiff
            btn.alpha = if (isEnabled && isDiff) 1.0f else 0.35f
        }

        weightSeekBars.forEach { it.isEnabled = isEnabled }
        weightResetButtons.forEachIndexed { i, btn ->
            val curVal = (weightSeekBars.getOrNull(i)?.progress ?: 50) / 100f
            val defVal = Preferences.DEFAULT_BAND_WEIGHTS.getOrElse(i) { 0.5f }
            val isDiff = abs(curVal - defVal) > 0.02f
            btn.isEnabled = isEnabled && isDiff
            btn.alpha = if (isEnabled && isDiff) 1.0f else 0.35f
        }


        autoPreampSwitch?.isEnabled = isEnabled
    }

    private fun formatDb(value: Int): String = if (value > 0) "+$value dB" else "$value dB"

    private fun createManualPreampSlider() {
        val manager = equalizerManager ?: return
        eqPreampContainer.removeAllViews()
        manualPreampSeekBar = null
        manualPreampLabel = null
        manualPreampResetBtn = null

        val savedManualDb = Preferences.getEqualizerManualPreampDb().roundToInt().coerceIn(-24, 24)

        val manualRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 4.dpToPx(context)
                bottomMargin = 8.dpToPx(context)
            }
            setPadding(0, 4.dpToPx(context), 0, 4.dpToPx(context))
        }

        val manualTextCol = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(110.dpToPx(context), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val manualTitle = TextView(requireContext(), null, 0, R.style.LabelSmall).apply {
            text = getString(R.string.equalizer_manual_preamp_title)
        }
        val manualHint = TextView(requireContext(), null, 0, R.style.LabelSmall).apply {
            text = getString(R.string.equalizer_manual_preamp_hint)
            alpha = 0.6f
        }
        manualTextCol.addView(manualTitle)
        manualTextCol.addView(manualHint)
        manualRow.addView(manualTextCol)

        val manualLbl = TextView(requireContext(), null, 0, R.style.LabelSmall).apply {
            text = formatDb(savedManualDb)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(54.dpToPx(context), LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val manualReset = ImageView(requireContext()).apply {
            setImageResource(R.drawable.ic_replay)
            contentDescription = getString(R.string.equalizer_reset)
            val outValue = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
            setBackgroundResource(outValue.resourceId)
            val sizePx = 36.dpToPx(context)
            val padPx = 6.dpToPx(context)
            layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply {
                marginStart = 4.dpToPx(context)
            }
            setPadding(padPx, padPx, padPx, padPx)
            val isDiff = savedManualDb != 0
            isEnabled = isDiff
            alpha = if (isDiff) 1.0f else 0.35f
        }

        val manualSb = SeekBar(requireContext()).apply {
            max = 48 // 0..48 maps to -24..+24 dB (progress - 24)
            progress = savedManualDb + 24
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    val db = progress - 24
                    if (fromUser) {
                        manager.setManualPreampDb(db.toFloat())
                        Preferences.setEqualizerManualPreampDb(db.toFloat())
                    }
                    manualLbl.text = formatDb(db)
                    val isDiff = db != 0
                    manualReset.isEnabled = isDiff
                    manualReset.alpha = if (isDiff) 1.0f else 0.35f
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) {}
                override fun onStopTrackingTouch(seekBar: SeekBar) {}
            })
        }

        manualReset.setOnClickListener {
            manualSb.progress = 24
            manager.setManualPreampDb(0f)
            Preferences.setEqualizerManualPreampDb(0f)
            manualLbl.text = formatDb(0)
            manualReset.isEnabled = false
            manualReset.alpha = 0.35f
        }
        manualLbl.setOnClickListener {
            if (manualReset.isEnabled) manualReset.performClick()
        }

        manualPreampSeekBar = manualSb
        manualPreampLabel = manualLbl
        manualPreampResetBtn = manualReset
        manualRow.addView(manualSb)
        manualRow.addView(manualLbl)
        manualRow.addView(manualReset)
        eqPreampContainer.addView(manualRow)
    }

    private fun createBandSliders() {
        val manager = equalizerManager ?: return
        eqBandsContainer.removeAllViews()
        bandSeekBars.clear()
        bandDbLabels.clear()
        bandResetButtons.clear()
        val bands = manager.getNumberOfBands()
        val bandLevelRange = manager.getBandLevelRange() ?: shortArrayOf(-1500, 1500)
        minLevelDb = bandLevelRange[0] / 100
        val maxLevelDb = bandLevelRange[1] / 100

        val savedLevels = Preferences.getEqualizerBandLevels(bands)
        val isEqActive = eqSwitch.isChecked
        for (i in 0 until bands) {
            val band = i.toShort()
            val freq = manager.getCenterFreq(band) ?: 0

            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    val topBottomMarginDp = 12
                    topMargin = topBottomMarginDp.dpToPx(context)
                    bottomMargin = topBottomMarginDp.dpToPx(context)
                }
                setPadding(0, 4.dpToPx(context), 0, 4.dpToPx(context))
            }

            val freqLabel = TextView(requireContext(), null, 0, R.style.LabelSmall).apply {
                text = if (freq >= 1000) {
                    if (freq % 1000 == 0) {
                        "${freq / 1000} kHz"
                    } else {
                        String.format(Locale.US, "%.1f kHz", freq / 1000f)
                    }
                } else {
                    "$freq Hz"
                }
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(56.dpToPx(context), LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            row.addView(freqLabel)

            val initialLevelDb = (savedLevels.getOrNull(i) ?: (manager.getBandLevel(band) ?: 0)) / 100
            val dbLabel = TextView(requireContext(), null, 0, R.style.LabelSmall).apply {
                text = formatDb(initialLevelDb)
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(54.dpToPx(context), LinearLayout.LayoutParams.WRAP_CONTENT)
            }

            val resetBandBtn = ImageView(requireContext()).apply {
                setImageResource(R.drawable.ic_replay)
                contentDescription = getString(R.string.equalizer_reset)
                val outValue = TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
                setBackgroundResource(outValue.resourceId)
                val sizePx = 36.dpToPx(context)
                val padPx = 6.dpToPx(context)
                layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply {
                    marginStart = 4.dpToPx(context)
                }
                setPadding(padPx, padPx, padPx, padPx)
                isEnabled = isEqActive && initialLevelDb != 0
                alpha = if (isEqActive && initialLevelDb != 0) 1.0f else 0.35f
            }

            val seekBar = SeekBar(requireContext()).apply {
                max = maxLevelDb - minLevelDb
                progress = initialLevelDb - minLevelDb
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                        val thisLevelDb = progress + minLevelDb
                        if (fromUser) {
                            manager.setBandLevel(band, (thisLevelDb * 100).toShort())
                            saveBandLevelsToPreferences()
                        }
                        dbLabel.text = formatDb(thisLevelDb)
                        val active = eqSwitch.isChecked
                        resetBandBtn.isEnabled = active && thisLevelDb != 0
                        resetBandBtn.alpha = if (active && thisLevelDb != 0) 1.0f else 0.35f
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar) {}
                })
            }

            resetBandBtn.setOnClickListener {
                val midProgress = 0 - minLevelDb
                seekBar.progress = midProgress
                manager.setBandLevel(band, 0)
                saveBandLevelsToPreferences()
                dbLabel.text = formatDb(0)
                resetBandBtn.isEnabled = false
                resetBandBtn.alpha = 0.35f
            }

            dbLabel.setOnClickListener {
                if (resetBandBtn.isEnabled) {
                    resetBandBtn.performClick()
                }
            }

            bandSeekBars.add(seekBar)
            bandDbLabels.add(dbLabel)
            bandResetButtons.add(resetBandBtn)

            row.addView(seekBar)
            row.addView(dbLabel)
            row.addView(resetBandBtn)
            eqBandsContainer.addView(row)
        }
    }

    private fun createTuningSliders() {
        val manager = equalizerManager ?: return
        eqAdvancedContainer.removeAllViews()
        weightSeekBars.clear()
        weightLabels.clear()
        weightResetButtons.clear()
        maxAttenSeekBar = null
        maxAttenLabel = null
        maxAttenResetBtn = null
        softKneeSeekBar = null
        softKneeLabel = null
        softKneeResetBtn = null
        autoPreampSwitch = null
        autoPreampContainer = null

        val isEqActive = eqSwitch.isChecked
        val bands = manager.getNumberOfBands()

        // ── Auto Pre-Amp Settings ──
        val isAutoPreamp = Preferences.isEqualizerAutoPreampEnabled()

        val autoPreampRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 20.dpToPx(context)
                bottomMargin = 8.dpToPx(context)
            }
        }

        val autoPreampTextCol = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val autoPreampTitle = TextView(requireContext(), null, 0, R.style.LabelMedium).apply {
            text = getString(R.string.equalizer_auto_preamp_title)
        }
        val autoPreampSubtitle = TextView(requireContext(), null, 0, R.style.LabelSmall).apply {
            text = getString(R.string.equalizer_auto_preamp_summary)
            alpha = 0.7f
        }
        autoPreampTextCol.addView(autoPreampTitle)
        autoPreampTextCol.addView(autoPreampSubtitle)
        autoPreampRow.addView(autoPreampTextCol)

        val autoSwitch = Switch(requireContext()).apply {
            isChecked = isAutoPreamp
            isEnabled = isEqActive
        }
        autoPreampRow.addView(autoSwitch)
        eqAdvancedContainer.addView(autoPreampRow)

        autoPreampSwitch = autoSwitch

        val autoContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            visibility = if (isAutoPreamp) View.VISIBLE else View.GONE
        }
        autoPreampContainer = autoContainer
        eqAdvancedContainer.addView(autoContainer)

        autoSwitch.setOnCheckedChangeListener { _, isChecked ->
            Preferences.setEqualizerAutoPreampEnabled(isChecked)
            manager.setAutoPreampEnabled(isChecked)
            autoContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        // ── 2.1 Dynamics & Headroom ──
        val dynamicsHeader = TextView(requireContext(), null, 0, R.style.LabelMedium).apply {
            text = getString(R.string.equalizer_dynamics_title)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 16.dpToPx(context)
                bottomMargin = 8.dpToPx(context)
            }
        }
        autoContainer.addView(dynamicsHeader)

        // 2.2 Max Attenuation row (0 to 24 dB, default 8 dB)
        val savedMaxAtten = Preferences.getEqualizerMaxAttenuation()
        val defaultMaxAtten = Preferences.DEFAULT_MAX_ATTENUATION
        val maxAttenRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                val margin = 12.dpToPx(context)
                topMargin = margin
                bottomMargin = margin
            }
            setPadding(0, 4.dpToPx(context), 0, 4.dpToPx(context))
        }

        val maxAttenTitle = TextView(requireContext(), null, 0, R.style.LabelSmall).apply {
            text = getString(R.string.equalizer_max_attenuation)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(110.dpToPx(context), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        maxAttenRow.addView(maxAttenTitle)

        val maxAttenLbl = TextView(requireContext(), null, 0, R.style.LabelSmall).apply {
            text = "${savedMaxAtten.roundToInt()} dB"
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(54.dpToPx(context), LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val maxAttenReset = ImageView(requireContext()).apply {
            setImageResource(R.drawable.ic_replay)
            contentDescription = getString(R.string.equalizer_reset)
            val outValue = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
            setBackgroundResource(outValue.resourceId)
            val sizePx = 36.dpToPx(context)
            val padPx = 6.dpToPx(context)
            layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply {
                marginStart = 4.dpToPx(context)
            }
            setPadding(padPx, padPx, padPx, padPx)
            val isDiff = abs(savedMaxAtten - defaultMaxAtten) > 0.1f
            isEnabled = isEqActive && isDiff
            alpha = if (isEqActive && isDiff) 1.0f else 0.35f
        }

        val maxAttenSb = SeekBar(requireContext()).apply {
            max = 24
            progress = savedMaxAtten.roundToInt().coerceIn(0, 24)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        manager.setMaxAttenuation(progress.toFloat())
                        Preferences.setEqualizerMaxAttenuation(progress.toFloat())
                    }
                    maxAttenLbl.text = "$progress dB"
                    val active = eqSwitch.isChecked
                    val isDiff = abs(progress.toFloat() - defaultMaxAtten) > 0.1f
                    maxAttenReset.isEnabled = active && isDiff
                    maxAttenReset.alpha = if (active && isDiff) 1.0f else 0.35f
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) {}
                override fun onStopTrackingTouch(seekBar: SeekBar) {}
            })
        }

        maxAttenReset.setOnClickListener {
            val defVal = defaultMaxAtten.roundToInt()
            maxAttenSb.progress = defVal
            manager.setMaxAttenuation(defaultMaxAtten)
            Preferences.setEqualizerMaxAttenuation(defaultMaxAtten)
            maxAttenLbl.text = "$defVal dB"
            maxAttenReset.isEnabled = false
            maxAttenReset.alpha = 0.35f
        }
        maxAttenLbl.setOnClickListener {
            if (maxAttenReset.isEnabled) maxAttenReset.performClick()
        }

        maxAttenSeekBar = maxAttenSb
        maxAttenLabel = maxAttenLbl
        maxAttenResetBtn = maxAttenReset
        maxAttenRow.addView(maxAttenSb)
        maxAttenRow.addView(maxAttenLbl)
        maxAttenRow.addView(maxAttenReset)
        autoContainer.addView(maxAttenRow)

        // 1.2 Cushion Zone Cut-Off row (0.10 to 1.00, default 0.70)
        val savedKnee = Preferences.getEqualizerSoftKneeThreshold()
        val defaultKnee = Preferences.DEFAULT_SOFT_KNEE_THRESHOLD
        val kneeRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                val margin = 12.dpToPx(context)
                topMargin = margin
                bottomMargin = margin
            }
            setPadding(0, 4.dpToPx(context), 0, 4.dpToPx(context))
        }

        val kneeTitle = TextView(requireContext(), null, 0, R.style.LabelSmall).apply {
            text = getString(R.string.equalizer_soft_knee_threshold)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(110.dpToPx(context), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        kneeRow.addView(kneeTitle)

        val kneeLbl = TextView(requireContext(), null, 0, R.style.LabelSmall).apply {
            text = if (savedKnee >= 1.0f) "OFF" else String.format(Locale.US, "%.2f", savedKnee)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(54.dpToPx(context), LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val kneeReset = ImageView(requireContext()).apply {
            setImageResource(R.drawable.ic_replay)
            contentDescription = getString(R.string.equalizer_reset)
            val outValue = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
            setBackgroundResource(outValue.resourceId)
            val sizePx = 36.dpToPx(context)
            val padPx = 6.dpToPx(context)
            layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply {
                marginStart = 4.dpToPx(context)
            }
            setPadding(padPx, padPx, padPx, padPx)
            val isDiff = abs(savedKnee - defaultKnee) > 0.02f
            isEnabled = isEqActive && isDiff
            alpha = if (isEqActive && isDiff) 1.0f else 0.35f
        }

        val kneeSb = SeekBar(requireContext()).apply {
            max = 90 // 0..90 maps to 0.10..1.00
            progress = ((savedKnee - 0.10f) * 100f).roundToInt().coerceIn(0, 90)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    val threshold = (progress + 10) / 100f
                    if (fromUser) {
                        manager.setSoftKneeThreshold(threshold)
                        Preferences.setEqualizerSoftKneeThreshold(threshold)
                    }
                    kneeLbl.text = if (threshold >= 1.0f) "OFF" else String.format(Locale.US, "%.2f", threshold)
                    val active = eqSwitch.isChecked
                    val isDiff = abs(threshold - defaultKnee) > 0.02f
                    kneeReset.isEnabled = active && isDiff
                    kneeReset.alpha = if (active && isDiff) 1.0f else 0.35f
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) {}
                override fun onStopTrackingTouch(seekBar: SeekBar) {}
            })
        }

        kneeReset.setOnClickListener {
            val defProgress = ((defaultKnee - 0.10f) * 100f).roundToInt()
            kneeSb.progress = defProgress
            manager.setSoftKneeThreshold(defaultKnee)
            Preferences.setEqualizerSoftKneeThreshold(defaultKnee)
            kneeLbl.text = if (defaultKnee >= 1.0f) "OFF" else String.format(Locale.US, "%.2f", defaultKnee)
            kneeReset.isEnabled = false
            kneeReset.alpha = 0.35f
        }
        kneeLbl.setOnClickListener {
            if (kneeReset.isEnabled) kneeReset.performClick()
        }

        softKneeSeekBar = kneeSb
        softKneeLabel = kneeLbl
        softKneeResetBtn = kneeReset
        kneeRow.addView(kneeSb)
        kneeRow.addView(kneeLbl)
        kneeRow.addView(kneeReset)
        autoContainer.addView(kneeRow)

        // ── 2. Auto Pre-Amp Band Weights ──
        val weightsHeader = TextView(requireContext(), null, 0, R.style.LabelMedium).apply {
            text = getString(R.string.equalizer_weights_title)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 28.dpToPx(context)
                bottomMargin = 8.dpToPx(context)
            }
        }
        autoContainer.addView(weightsHeader)

        val savedWeights = Preferences.getEqualizerBandWeights(bands)
        val defaultWeights = Preferences.DEFAULT_BAND_WEIGHTS

        for (i in 0 until bands) {
            val band = i.toShort()
            val freq = manager.getCenterFreq(band) ?: 0
            val savedWeight = savedWeights.getOrElse(i) { defaultWeights.getOrElse(i) { 0.5f } }
            val defaultWeight = defaultWeights.getOrElse(i) { 0.5f }

            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    val margin = 12.dpToPx(context)
                    topMargin = margin
                    bottomMargin = margin
                }
                setPadding(0, 4.dpToPx(context), 0, 4.dpToPx(context))
            }

            val freqLabel = TextView(requireContext(), null, 0, R.style.LabelSmall).apply {
                text = if (freq >= 1000) {
                    if (freq % 1000 == 0) {
                        "${freq / 1000} kHz"
                    } else {
                        String.format(Locale.US, "%.1f kHz", freq / 1000f)
                    }
                } else {
                    "$freq Hz"
                }
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(56.dpToPx(context), LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            row.addView(freqLabel)

            val weightPercent = (savedWeight * 100f).roundToInt().coerceIn(0, 100)
            val weightLbl = TextView(requireContext(), null, 0, R.style.LabelSmall).apply {
                text = "$weightPercent%"
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(54.dpToPx(context), LinearLayout.LayoutParams.WRAP_CONTENT)
            }

            val resetBtn = ImageView(requireContext()).apply {
                setImageResource(R.drawable.ic_replay)
                contentDescription = getString(R.string.equalizer_reset)
                val outValue = TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
                setBackgroundResource(outValue.resourceId)
                val sizePx = 36.dpToPx(context)
                val padPx = 6.dpToPx(context)
                layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply {
                    marginStart = 4.dpToPx(context)
                }
                setPadding(padPx, padPx, padPx, padPx)
                val isDiff = abs(savedWeight - defaultWeight) > 0.02f
                isEnabled = isEqActive && isDiff
                alpha = if (isEqActive && isDiff) 1.0f else 0.35f
            }

            val sb = SeekBar(requireContext()).apply {
                max = 100
                progress = weightPercent
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                        val weight = progress / 100f
                        if (fromUser) {
                            manager.setBandWeight(band, weight)
                            saveBandWeightsToPreferences()
                        }
                        weightLbl.text = "$progress%"
                        val active = eqSwitch.isChecked
                        val isDiff = abs(weight - defaultWeight) > 0.02f
                        resetBtn.isEnabled = active && isDiff
                        resetBtn.alpha = if (active && isDiff) 1.0f else 0.35f
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar) {}
                })
            }

            resetBtn.setOnClickListener {
                val defPercent = (defaultWeight * 100f).roundToInt()
                sb.progress = defPercent
                manager.setBandWeight(band, defaultWeight)
                saveBandWeightsToPreferences()
                weightLbl.text = "$defPercent%"
                resetBtn.isEnabled = false
                resetBtn.alpha = 0.35f
            }
            weightLbl.setOnClickListener {
                if (resetBtn.isEnabled) resetBtn.performClick()
            }

            weightSeekBars.add(sb)
            weightLabels.add(weightLbl)
            weightResetButtons.add(resetBtn)

            row.addView(sb)
            row.addView(weightLbl)
            row.addView(resetBtn)
            autoContainer.addView(row)
        }
    }

    private fun saveBandWeightsToPreferences() {
        val manager = equalizerManager ?: return
        val bands = manager.getNumberOfBands()
        val weights = FloatArray(bands.toInt()) { i ->
            val prog = weightSeekBars.getOrNull(i)?.progress
            if (prog != null) prog / 100f else Preferences.DEFAULT_BAND_WEIGHTS.getOrElse(i) { 0.5f }
        }
        Preferences.setEqualizerBandWeights(weights)
    }

    private fun resetEqualizer() {
        val manager = equalizerManager ?: return
        val bands = manager.getNumberOfBands()
        val midLevelDb = 0

        // Reset EQ bands
        for (i in 0 until bands) {
            manager.setBandLevel(i.toShort(), (0).toShort())
            bandSeekBars.getOrNull(i)?.progress = midLevelDb - minLevelDb
            bandDbLabels.getOrNull(i)?.text = formatDb(0)
            bandResetButtons.getOrNull(i)?.let { btn ->
                btn.isEnabled = false
                btn.alpha = 0.35f
            }
        }
        Preferences.setEqualizerBandLevels(ShortArray(bands.toInt()))

        // Reset Max Attenuation to default 8 dB
        val defaultMaxAtten = Preferences.DEFAULT_MAX_ATTENUATION
        manager.setMaxAttenuation(defaultMaxAtten)
        Preferences.setEqualizerMaxAttenuation(defaultMaxAtten)
        maxAttenSeekBar?.progress = defaultMaxAtten.roundToInt()
        maxAttenLabel?.text = "${defaultMaxAtten.roundToInt()} dB"
        maxAttenResetBtn?.let {
            it.isEnabled = false
            it.alpha = 0.35f
        }

        // Reset Soft-Knee Threshold to default 0.70
        val defaultKnee = Preferences.DEFAULT_SOFT_KNEE_THRESHOLD
        manager.setSoftKneeThreshold(defaultKnee)
        Preferences.setEqualizerSoftKneeThreshold(defaultKnee)
        softKneeSeekBar?.progress = ((defaultKnee - 0.10f) * 100f).roundToInt()
        softKneeLabel?.text = if (defaultKnee >= 1.0f) "OFF" else String.format(Locale.US, "%.2f", defaultKnee)
        softKneeResetBtn?.let {
            it.isEnabled = false
            it.alpha = 0.35f
        }

        // Reset Band Weights to default
        val defaultWeights = Preferences.DEFAULT_BAND_WEIGHTS
        for (i in 0 until bands) {
            val defW = defaultWeights.getOrElse(i) { 0.5f }
            manager.setBandWeight(i.toShort(), defW)
            val defPercent = (defW * 100f).roundToInt()
            weightSeekBars.getOrNull(i)?.progress = defPercent
            weightLabels.getOrNull(i)?.text = "$defPercent%"
            weightResetButtons.getOrNull(i)?.let {
                it.isEnabled = false
                it.alpha = 0.35f
            }
        }
        // Reset Manual Pre-Amp (0 dB)
        val defaultManualDb = Preferences.DEFAULT_MANUAL_PREAMP_DB
        manager.setManualPreampDb(defaultManualDb)
        Preferences.setEqualizerManualPreampDb(defaultManualDb)
        manualPreampSeekBar?.progress = (defaultManualDb + 24).roundToInt()
        manualPreampLabel?.text = formatDb(defaultManualDb.roundToInt())
        manualPreampResetBtn?.let {
            it.isEnabled = false
            it.alpha = 0.35f
        }

        // Reset Auto Pre-Amp (Enabled = true)
        val defaultAutoPreamp = Preferences.DEFAULT_AUTO_PREAMP_ENABLED
        manager.setAutoPreampEnabled(defaultAutoPreamp)
        Preferences.setEqualizerAutoPreampEnabled(defaultAutoPreamp)
        autoPreampSwitch?.isChecked = defaultAutoPreamp
        autoPreampContainer?.visibility = if (defaultAutoPreamp) View.VISIBLE else View.GONE
    }

    private fun saveBandLevelsToPreferences() {
        val manager = equalizerManager ?: return
        val bands = manager.getNumberOfBands()
        val levels = ShortArray(bands.toInt()) { i -> manager.getBandLevel(i.toShort()) ?: 0 }
        Preferences.setEqualizerBandLevels(levels)
    }

    private fun restoreEqualizerPreferences() {
        val manager = equalizerManager ?: return
        eqSwitch.isChecked = Preferences.isEqualizerEnabled()
        updateUiEnabledState(eqSwitch.isChecked)

        val bands = manager.getNumberOfBands()
        val bandLevelRange = manager.getBandLevelRange() ?: shortArrayOf(-1500, 1500)
        minLevelDb = bandLevelRange[0] / 100

        val savedLevels = Preferences.getEqualizerBandLevels(bands)
        val isEqActive = eqSwitch.isChecked
        for (i in 0 until bands) {
            val savedDb = savedLevels[i] / 100
            manager.setBandLevel(i.toShort(), (savedDb * 100).toShort())
            bandSeekBars.getOrNull(i)?.progress = savedDb - minLevelDb
            bandDbLabels.getOrNull(i)?.text = formatDb(savedDb)
            bandResetButtons.getOrNull(i)?.let { btn ->
                btn.isEnabled = isEqActive && savedDb != 0
                btn.alpha = if (isEqActive && savedDb != 0) 1.0f else 0.35f
            }
        }

        // Restore Max Attenuation
        val savedMaxAtten = Preferences.getEqualizerMaxAttenuation()
        manager.setMaxAttenuation(savedMaxAtten)
        maxAttenSeekBar?.progress = savedMaxAtten.roundToInt()
        maxAttenLabel?.text = "${savedMaxAtten.roundToInt()} dB"
        maxAttenResetBtn?.let {
            val isDiff = abs(savedMaxAtten - Preferences.DEFAULT_MAX_ATTENUATION) > 0.1f
            it.isEnabled = isEqActive && isDiff
            it.alpha = if (isEqActive && isDiff) 1.0f else 0.35f
        }

        // Restore Soft-Knee Threshold
        val savedKnee = Preferences.getEqualizerSoftKneeThreshold()
        manager.setSoftKneeThreshold(savedKnee)
        softKneeSeekBar?.progress = ((savedKnee - 0.10f) * 100f).roundToInt()
        softKneeLabel?.text = if (savedKnee >= 1.0f) "OFF" else String.format(Locale.US, "%.2f", savedKnee)
        softKneeResetBtn?.let {
            val isDiff = abs(savedKnee - Preferences.DEFAULT_SOFT_KNEE_THRESHOLD) > 0.02f
            it.isEnabled = isEqActive && isDiff
            it.alpha = if (isEqActive && isDiff) 1.0f else 0.35f
        }

        // Restore Band Weights
        val savedWeights = Preferences.getEqualizerBandWeights(bands)
        val defaultWeights = Preferences.DEFAULT_BAND_WEIGHTS
        for (i in 0 until bands) {
            val w = savedWeights.getOrElse(i) { defaultWeights.getOrElse(i) { 0.5f } }
            manager.setBandWeight(i.toShort(), w)
            val percent = (w * 100f).roundToInt()
            weightSeekBars.getOrNull(i)?.progress = percent
            weightLabels.getOrNull(i)?.text = "$percent%"
            weightResetButtons.getOrNull(i)?.let {
                val isDiff = abs(w - defaultWeights.getOrElse(i) { 0.5f }) > 0.02f
                it.isEnabled = isEqActive && isDiff
                it.alpha = if (isEqActive && isDiff) 1.0f else 0.35f
            }
        }

        // Restore Manual Pre-Amp
        val savedManualDb = Preferences.getEqualizerManualPreampDb().roundToInt().coerceIn(-24, 24)
        manager.setManualPreampDb(savedManualDb.toFloat())
        manualPreampSeekBar?.progress = savedManualDb + 24
        manualPreampLabel?.text = formatDb(savedManualDb)
        manualPreampResetBtn?.let {
            val isDiff = savedManualDb != 0
            it.isEnabled = isDiff
            it.alpha = if (isDiff) 1.0f else 0.35f
        }

        // Restore Auto Pre-Amp Toggle
        val isAutoPreamp = Preferences.isEqualizerAutoPreampEnabled()
        manager.setAutoPreampEnabled(isAutoPreamp)
        autoPreampSwitch?.isChecked = isAutoPreamp
        autoPreampContainer?.visibility = if (isAutoPreamp) View.VISIBLE else View.GONE
    }

    private fun initAppBar() {
        settingsToolbar = root.findViewById(R.id.equalizer_toolbar)
        settingsToolbar.setNavigationOnClickListener { v ->
            activity.navController.navigateUp()
        }
    }

}

private fun Int.dpToPx(context: Context): Int =
    (this * context.resources.displayMetrics.density).toInt()
