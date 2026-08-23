package com.eddyizm.tempus.ui.dialog;

import android.app.Dialog;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;
import androidx.media3.common.Format;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaBrowser;

import com.eddyizm.tempus.R;
import com.eddyizm.tempus.databinding.DialogAudioProcessingBinding;
import com.eddyizm.tempus.util.AudioOutputTracker;
import com.eddyizm.tempus.util.MusicUtil;
import com.eddyizm.tempus.util.Preferences;
import com.eddyizm.tempus.util.ReplayGainUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.text.DecimalFormat;
import java.util.Locale;
import java.util.Objects;

@UnstableApi
public class AudioProcessDialog extends DialogFragment {
    private DialogAudioProcessingBinding bind;
    private final MediaMetadata mediaMetadata;
    private final MediaBrowser browser;

    public AudioProcessDialog(MediaMetadata mediaMetadata, MediaBrowser browser) {
        this.mediaMetadata = mediaMetadata;
        this.browser = browser;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        bind = DialogAudioProcessingBinding.inflate(getLayoutInflater());

        return new MaterialAlertDialogBuilder(requireActivity())
                .setView(bind.getRoot())
                .setPositiveButton(R.string.track_info_dialog_positive_button, (dialog, id) -> dialog.cancel())
                .create();
    }

    @Override
    public void onStart() {
        super.onStart();
        populatePipelineData();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        bind = null;
    }

    private void populatePipelineData() {
        if (bind == null || getContext() == null) return;

        Format decoderFormat = MusicUtil.getCurrentAudioFormat(browser);
        boolean isLocal = MusicUtil.isCurrentTrackLocal(browser);
        String decodedLabel = decoderFormat != null ? MusicUtil.audioFormatLabel(decoderFormat.sampleMimeType) : null;
        String originalSuffix = MusicUtil.getCurrentOriginalSuffix(browser);
        boolean isTranscoding = !isLocal && MusicUtil.isTranscodedFormat(decodedLabel, originalSuffix);

        int origBitDepth = 0;
        int origSampleRate = 0;
        int origBitrate = 0;
        String origSuffix = "AUDIO";

        if (mediaMetadata != null && mediaMetadata.extras != null) {
            origSuffix = mediaMetadata.extras.getString("suffix", "AUDIO").toUpperCase(Locale.US);
            origBitDepth = mediaMetadata.extras.getInt("bitDepth", 0);
            origSampleRate = mediaMetadata.extras.getInt("samplingRate", 0);
            origBitrate = mediaMetadata.extras.getInt("bitrate", 0);
        }

        // ==========================================
        // STAGE 1: Source Track / Stream Format
        // ==========================================
        if (isTranscoding) {
            String transcodeCodec = (decodedLabel != null ? decodedLabel : "OPUS").toUpperCase(Locale.US);
            int streamRate = (decoderFormat != null && decoderFormat.sampleRate > 0) ? decoderFormat.sampleRate : 48000;
            String streamRateStr = formatKhz(streamRate);
            int chCount = (decoderFormat != null && decoderFormat.channelCount > 0) ? decoderFormat.channelCount : 2;
            String chStr = chCount == 1 ? "Mono" : "Stereo";

            bind.pipelineSourceFormatKey.setText("Stream (Transcoded)");
            bind.pipelineSourceFormatVal.setText(transcodeCodec + " " + streamRateStr + " " + chStr + "\n(" + origSuffix + (origBitDepth > 0 ? " " + origBitDepth + "b" : "") + (origSampleRate > 0 ? " / " + formatKhz(origSampleRate) : "") + ")");

            int streamBitrate = (decoderFormat != null && decoderFormat.bitrate > 0) ? (decoderFormat.bitrate / 1000) : 0;
            if (streamBitrate == 0) {
                try {
                    streamBitrate = Integer.parseInt(MusicUtil.getBitratePreference());
                } catch (Exception ignored) {}
            }
            bind.pipelineSourceBitrateKey.setText("Transcode Bitrate");
            bind.pipelineSourceBitrateVal.setText(streamBitrate > 0 ? streamBitrate + " kbps" : "Transcoding Active");
        } else {
            String depthStr = origBitDepth > 0 ? origBitDepth + "-bit / " : "";
            int sr = (origSampleRate > 0) ? origSampleRate : ((decoderFormat != null && decoderFormat.sampleRate > 0) ? decoderFormat.sampleRate : 44100);
            String rateStr = formatKhz(sr);
            int chCount = (decoderFormat != null && decoderFormat.channelCount > 0) ? decoderFormat.channelCount : 2;
            String chStr = chCount == 1 ? "Mono" : "Stereo";

            bind.pipelineSourceFormatKey.setText("Track Format");
            bind.pipelineSourceFormatVal.setText(origSuffix + " " + depthStr + rateStr + " " + chStr + (isLocal ? " (Local File)" : ""));

            bind.pipelineSourceBitrateKey.setText("Bitrate");
            if (decoderFormat != null && decoderFormat.bitrate > 0) {
                bind.pipelineSourceBitrateVal.setText((decoderFormat.bitrate / 1000) + " kbps");
            } else if (origBitrate > 0) {
                bind.pipelineSourceBitrateVal.setText(origBitrate + " kbps");
            } else {
                bind.pipelineSourceBitrateVal.setText("Lossless Bit-perfect");
            }
        }

        // ==========================================
        // STAGE 2: Decoder Engine
        // ==========================================
        String mimeType = decoderFormat != null && decoderFormat.sampleMimeType != null ? decoderFormat.sampleMimeType : "audio/" + origSuffix.toLowerCase(Locale.US);
        String decoderName;
        if (mimeType.contains("flac")) {
            decoderName = "Media3 FLAC Decoder";
        } else if (mimeType.contains("opus")) {
            decoderName = "Media3 Opus Decoder";
        } else if (mimeType.contains("vorbis")) {
            decoderName = "Media3 Vorbis Decoder";
        } else if (mimeType.contains("mp4a") || mimeType.contains("aac")) {
            decoderName = "MediaCodec AAC Hardware Decoder";
        } else if (mimeType.contains("mpeg") || mimeType.contains("mp3")) {
            decoderName = "MediaCodec MP3 Decoder";
        } else {
            decoderName = "Media3 Native";
        }
        bind.pipelineDecoderEngineVal.setText(decoderName);

        int decRate = (decoderFormat != null && decoderFormat.sampleRate > 0) ? decoderFormat.sampleRate : ((origSampleRate > 0) ? origSampleRate : 48000);
        int decChannels = (decoderFormat != null && decoderFormat.channelCount > 0) ? decoderFormat.channelCount : 2;

        androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig trackConfig = AudioOutputTracker.getCurrentConfig();
        int activeEncoding = trackConfig != null ? trackConfig.encoding : ((decoderFormat != null && decoderFormat.pcmEncoding != Format.NO_VALUE) ? decoderFormat.pcmEncoding : androidx.media3.common.C.ENCODING_PCM_16BIT);

        String decodedFormatStr;
        if (activeEncoding == androidx.media3.common.C.ENCODING_PCM_FLOAT) {
            decodedFormatStr = "32-bit Float PCM (IEEE 754)";
        } else if (activeEncoding == androidx.media3.common.C.ENCODING_PCM_32BIT) {
            decodedFormatStr = "32-bit Signed Integer PCM (Int32)";
        } else if (activeEncoding == androidx.media3.common.C.ENCODING_PCM_24BIT) {
            decodedFormatStr = "24-bit Signed Integer PCM (Int24)";
        } else {
            decodedFormatStr = "16-bit Linear PCM (Signed 16-bit)";
        }
        bind.pipelineDecoderOutputVal.setText(decodedFormatStr + "\n" + formatKhz(decRate) + " • " + (decChannels == 1 ? "1 Ch (Mono)" : decChannels + " Ch (Stereo)"));

        // ==========================================
        // STAGE 3: DSP & Processing
        // ==========================================
        String rgMode = Preferences.getReplayGainMode();
        float currentGainDb = ReplayGainUtil.getCurrentGainDb();
        String rgString;
        if (Objects.equals(rgMode, "disabled")) {
            rgString = "ReplayGain Disabled";
        } else if (Math.abs(currentGainDb) < 0.01f) {
            rgString = "ReplayGain (" + capitalize(rgMode) + ") • 0.0 dB";
        } else {
            rgString = String.format(Locale.US, "ReplayGain (%s) • %+.2f dB (Float32 Domain)", capitalize(rgMode), currentGainDb);
        }
        bind.pipelineDspProcessingVal.setText(rgString);

        int sinkRate = AudioOutputTracker.getCurrentSampleRate();
        if (sinkRate > 0 && decRate > 0) {
            if (sinkRate == decRate) {
                bind.pipelineDspResamplingVal.setText("Bit-perfect 1:1 (No Resampling)");
            } else {
                bind.pipelineDspResamplingVal.setText("Resampled (" + formatKhz(decRate) + " → " + formatKhz(sinkRate) + ")");
            }
        } else {
            bind.pipelineDspResamplingVal.setText("Bit-perfect Pass-through (1:1)");
        }

        // ==========================================
        // STAGE 4: Output Driver
        // ==========================================
        int rateToDisplay = (sinkRate > 0) ? sinkRate : decRate;
        if (AudioOutputTracker.isDirectAudioSupported()) {
            bind.pipelineDriverNameVal.setText("Hi-Res Direct HD\nNative libdirectaudio.so");
            
            String streamDetails;
            if (activeEncoding == androidx.media3.common.C.ENCODING_PCM_FLOAT) {
                streamDetails = "ARM NEON SIMD Vectorized Float32 → Int32 PCM (Q31 @ " + formatKhz(rateToDisplay) + ")";
            } else if (activeEncoding == androidx.media3.common.C.ENCODING_PCM_32BIT) {
                streamDetails = "Direct 32-bit Integer PCM (Q31 @ " + formatKhz(rateToDisplay) + ")";
            } else {
                streamDetails = "Direct 16-bit Integer PCM (Int16 @ " + formatKhz(rateToDisplay) + ")";
            }
            bind.pipelineDriverStreamVal.setText(streamDetails);
        } else {
            bind.pipelineDriverNameVal.setText("Android AudioTrack");
            bind.pipelineDriverStreamVal.setText("Audio Track PCM Buffer (" + formatKhz(rateToDisplay) + ")");
        }


        // ==========================================
        // STAGE 5: Hardware Endpoint
        // ==========================================
        bind.pipelineHwEndpointVal.setText(AudioOutputTracker.getActiveOutputDeviceString(requireContext()));
        String hwRate = AudioOutputTracker.getHardwareSampleRateString(requireContext());
        String hwBitDepth = AudioOutputTracker.getHardwareBitDepthString();
        String hwChannels = AudioOutputTracker.getOutputChannelsString();
        bind.pipelineHwFormatVal.setText(hwBitDepth + " • " + hwRate + " (" + hwChannels + ")");
    }

    private String formatKhz(int sampleRate) {
        double kHz = sampleRate / 1000.0;
        return new DecimalFormat("0.#").format(kHz) + " kHz";
    }

    private String capitalize(String str) {
        if (str == null || str.isEmpty()) return "";
        return str.substring(0, 1).toUpperCase(Locale.US) + str.substring(1).toLowerCase(Locale.US);
    }
}
