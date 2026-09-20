#include <jni.h>
#include <cstring>
#include <cstdint>
#include "dsp_eq.h"

// ---------------------------------------------------------------------------
// Standalone DSP Equalizer instance for EqualizerAudioProcessor (AudioTrack)
// ---------------------------------------------------------------------------
static tempus::DspEqualizer g_audio_processor_eq;

extern "C" {

// ---------------------------------------------------------------------------
// EqualizerAudioProcessor (ExoPlayer AudioSink Buffer Processing)
// ---------------------------------------------------------------------------

JNIEXPORT jlong JNICALL
Java_com_eddyizm_tempus_equalizer_EqualizerAudioProcessor_nativeGetEqPtr(JNIEnv*, jclass) {
    return reinterpret_cast<jlong>(&g_audio_processor_eq);
}

JNIEXPORT void JNICALL
Java_com_eddyizm_tempus_equalizer_EqualizerAudioProcessor_nativeConfigure(
        JNIEnv*, jclass, jint sampleRate) {
    g_audio_processor_eq.set_sample_rate(static_cast<uint32_t>(sampleRate));
}

JNIEXPORT void JNICALL
Java_com_eddyizm_tempus_equalizer_EqualizerAudioProcessor_nativeProcessFloat(
        JNIEnv* env, jclass, jobject inBuf, jint inOffset, jobject outBuf, jint outOffset, jint numSamples, jint channelCount) {
    auto* inPtr = reinterpret_cast<const uint8_t*>(env->GetDirectBufferAddress(inBuf));
    auto* outPtr = reinterpret_cast<uint8_t*>(env->GetDirectBufferAddress(outBuf));
    if (!inPtr || !outPtr) return;

    const float* __restrict in = reinterpret_cast<const float*>(inPtr + inOffset);
    float* __restrict out = reinterpret_cast<float*>(outPtr + outOffset);

    if (g_audio_processor_eq.is_flat.load(std::memory_order_relaxed)) {
        std::memcpy(out, in, numSamples * sizeof(float));
        return;
    }

    int ch = 0;
    int numCh = (channelCount <= 1) ? 1 : channelCount;
    for (int i = 0; i < numSamples; i++) {
        out[i] = static_cast<float>(g_audio_processor_eq.process_sample(ch, static_cast<double>(in[i])));
        ch = (ch + 1) % numCh;
    }
}

JNIEXPORT void JNICALL
Java_com_eddyizm_tempus_equalizer_EqualizerAudioProcessor_nativeProcessInt16(
        JNIEnv* env, jclass, jobject inBuf, jint inOffset, jobject outBuf, jint outOffset, jint numSamples, jint channelCount) {
    auto* inPtr = reinterpret_cast<const uint8_t*>(env->GetDirectBufferAddress(inBuf));
    auto* outPtr = reinterpret_cast<uint8_t*>(env->GetDirectBufferAddress(outBuf));
    if (!inPtr || !outPtr) return;

    const int16_t* __restrict in = reinterpret_cast<const int16_t*>(inPtr + inOffset);
    int16_t* __restrict out = reinterpret_cast<int16_t*>(outPtr + outOffset);

    if (g_audio_processor_eq.is_flat.load(std::memory_order_relaxed)) {
        std::memcpy(out, in, numSamples * sizeof(int16_t));
        return;
    }

    int ch = 0;
    int numCh = (channelCount <= 1) ? 1 : channelCount;
    for (int i = 0; i < numSamples; i++) {
        double s = static_cast<double>(in[i]) / 32768.0;
        s = g_audio_processor_eq.process_sample(ch, s);
        out[i] = static_cast<int16_t>(s * 32767.0);
        ch = (ch + 1) % numCh;
    }
}

// ---------------------------------------------------------------------------
// Unified NativeEqBridge JNI: operates directly on any tempus::DspEqualizer*
// ---------------------------------------------------------------------------

JNIEXPORT void JNICALL
Java_com_eddyizm_tempus_equalizer_NativeEqBridge_nativeSetEnabled(
        JNIEnv*, jclass, jlong eqPtr, jboolean enabled) {
    auto* eq = reinterpret_cast<tempus::DspEqualizer*>(eqPtr);
    if (eq) eq->set_enabled(enabled == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_eddyizm_tempus_equalizer_NativeEqBridge_nativeSetBand(
        JNIEnv*, jclass, jlong eqPtr, jint band, jint level_mb) {
    auto* eq = reinterpret_cast<tempus::DspEqualizer*>(eqPtr);
    if (eq) eq->set_band_level(band, level_mb);
}

JNIEXPORT void JNICALL
Java_com_eddyizm_tempus_equalizer_NativeEqBridge_nativeSetBandWeight(
        JNIEnv*, jclass, jlong eqPtr, jint band, jdouble weight) {
    auto* eq = reinterpret_cast<tempus::DspEqualizer*>(eqPtr);
    if (eq) eq->set_band_weight(band, weight);
}

JNIEXPORT void JNICALL
Java_com_eddyizm_tempus_equalizer_NativeEqBridge_nativeSetMaxAttenuation(
        JNIEnv*, jclass, jlong eqPtr, jdouble atten_db) {
    auto* eq = reinterpret_cast<tempus::DspEqualizer*>(eqPtr);
    if (eq) eq->set_max_attenuation(atten_db);
}

JNIEXPORT void JNICALL
Java_com_eddyizm_tempus_equalizer_NativeEqBridge_nativeSetSoftKneeThreshold(
        JNIEnv*, jclass, jlong eqPtr, jdouble threshold) {
    auto* eq = reinterpret_cast<tempus::DspEqualizer*>(eqPtr);
    if (eq) eq->set_soft_knee_threshold(threshold);
}

JNIEXPORT void JNICALL
Java_com_eddyizm_tempus_equalizer_NativeEqBridge_nativeSetAutoPreampEnabled(
        JNIEnv*, jclass, jlong eqPtr, jboolean enabled) {
    auto* eq = reinterpret_cast<tempus::DspEqualizer*>(eqPtr);
    if (eq) eq->set_auto_preamp_enabled(enabled == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_eddyizm_tempus_equalizer_NativeEqBridge_nativeSetManualPreamp(
        JNIEnv*, jclass, jlong eqPtr, jdouble db) {
    auto* eq = reinterpret_cast<tempus::DspEqualizer*>(eqPtr);
    if (eq) eq->set_manual_preamp_db(db);
}

JNIEXPORT void JNICALL
Java_com_eddyizm_tempus_equalizer_NativeEqBridge_nativeSetReplayGainDb(
        JNIEnv*, jclass, jlong eqPtr, jdouble db) {
    auto* eq = reinterpret_cast<tempus::DspEqualizer*>(eqPtr);
    if (eq) eq->set_rg_preamp_db(db);
}

JNIEXPORT void JNICALL
Java_com_eddyizm_tempus_equalizer_NativeEqBridge_nativeReset(
        JNIEnv*, jclass, jlong eqPtr) {
    auto* eq = reinterpret_cast<tempus::DspEqualizer*>(eqPtr);
    if (eq) eq->reset();
}

JNIEXPORT void JNICALL
Java_com_eddyizm_tempus_equalizer_NativeEqBridge_nativeApplyConfig(
        JNIEnv* env, jclass, jlong eqPtr, jboolean enabled,
        jintArray levelsArr, jdoubleArray weightsArr,
        jdouble manualDb, jboolean autoPreamp, jdouble maxAtten, jdouble knee) {
    auto* eq = reinterpret_cast<tempus::DspEqualizer*>(eqPtr);
    if (!eq) return;

    int levels[tempus::DspEqualizer::NUM_BANDS] = {0};
    if (levelsArr) {
        jint* elems = env->GetIntArrayElements(levelsArr, nullptr);
        if (elems) {
            for (int i = 0; i < tempus::DspEqualizer::NUM_BANDS; i++) levels[i] = elems[i];
            env->ReleaseIntArrayElements(levelsArr, elems, JNI_ABORT);
        }
    }

    double weights[tempus::DspEqualizer::NUM_BANDS] = {0.5};
    if (weightsArr) {
        jdouble* elems = env->GetDoubleArrayElements(weightsArr, nullptr);
        if (elems) {
            for (int i = 0; i < tempus::DspEqualizer::NUM_BANDS; i++) weights[i] = elems[i];
            env->ReleaseDoubleArrayElements(weightsArr, elems, JNI_ABORT);
        }
    }

    eq->apply_config(enabled == JNI_TRUE, levels, weights, manualDb, autoPreamp == JNI_TRUE, maxAtten, knee);
}

} // extern "C"
