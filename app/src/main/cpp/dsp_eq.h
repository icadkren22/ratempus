#pragma once

#include <cmath>
#include <atomic>
#include <mutex>
#include <algorithm>
#include <cstdint>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

namespace tempus {

struct Biquad {
    double b0 = 1.0, b1 = 0.0, b2 = 0.0;
    double a1 = 0.0, a2 = 0.0;
    double d1[2] = {0.0, 0.0};
    double d2[2] = {0.0, 0.0};
    bool bypassed = true;

    void reset() {
        d1[0] = d1[1] = 0.0;
        d2[0] = d2[1] = 0.0;
    }

    inline double process(int ch, double in) {
        if (bypassed) return in;
        double y = b0 * in + d1[ch];
        d1[ch] = b1 * in - a1 * y + d2[ch];
        d2[ch] = b2 * in - a2 * y;
        return y;
    }
};

class DspEqualizer {
public:
    static constexpr int NUM_BANDS = 5;
    static constexpr int BAND_FREQS[NUM_BANDS] = {60, 230, 910, 3600, 14000};
    static constexpr double DEFAULT_BAND_WEIGHTS[NUM_BANDS] = {0.40, 0.60, 0.65, 0.60, 0.40};
    static constexpr double DEFAULT_MAX_ATTENUATION_DB = 8.0;
    static constexpr double DEFAULT_SOFT_KNEE_THRESHOLD = 0.7;
    static constexpr double DEFAULT_Q = 1.414;
    static constexpr bool DEFAULT_AUTO_PREAMP_ENABLED = true;

    std::atomic<bool> enabled{false};
    std::atomic<bool> is_flat{true};

private:
    int band_levels[NUM_BANDS] = {0, 0, 0, 0, 0};
    double band_weights[NUM_BANDS] = {0.40, 0.60, 0.65, 0.60, 0.40};
    double max_attenuation_db = 8.0;
    std::atomic<double> soft_knee_threshold{0.7};
    std::atomic<bool> auto_preamp_enabled{true};
    double manual_preamp_db = 0.0;
    double rg_preamp_db = 0.0;
    Biquad filters[NUM_BANDS];
    uint32_t sample_rate = 44100;
    // Auto pre-amp: frequency-weighted progressive soft-curve to prevent clipping while keeping output loud
    double preamp_gain = 1.0;
    std::mutex mtx;

public:
    DspEqualizer() {
        update_coefficients_locked();
    }

    void set_sample_rate(uint32_t sr) {
        std::lock_guard<std::mutex> lock(mtx);
        if (sample_rate != sr && sr > 0) {
            sample_rate = sr;
            update_coefficients_locked();
        }
    }

    void set_enabled(bool en) {
        enabled.store(en, std::memory_order_relaxed);
        std::lock_guard<std::mutex> lock(mtx);
        update_coefficients_locked();
    }

    void set_band_level(int band, int level_mb) {
        if (band < 0 || band >= NUM_BANDS) return;
        if (level_mb < -1500) level_mb = -1500;
        if (level_mb > 1500) level_mb = 1500;
        std::lock_guard<std::mutex> lock(mtx);
        if (band_levels[band] != level_mb) {
            band_levels[band] = level_mb;
            update_coefficients_locked();
        }
    }

    void set_band_weight(int band, double weight) {
        if (band < 0 || band >= NUM_BANDS) return;
        if (weight < 0.0) weight = 0.0;
        if (weight > 1.0) weight = 1.0;
        std::lock_guard<std::mutex> lock(mtx);
        if (band_weights[band] != weight) {
            band_weights[band] = weight;
            update_coefficients_locked();
        }
    }

    double get_band_weight(int band) const {
        if (band < 0 || band >= NUM_BANDS) return 0.0;
        return band_weights[band];
    }

    void set_max_attenuation(double atten_db) {
        if (atten_db < 0.0) atten_db = 0.0;
        if (atten_db > 24.0) atten_db = 24.0;
        std::lock_guard<std::mutex> lock(mtx);
        if (max_attenuation_db != atten_db) {
            max_attenuation_db = atten_db;
            update_coefficients_locked();
        }
    }

    double get_max_attenuation() const {
        return max_attenuation_db;
    }

    void set_soft_knee_threshold(double threshold) {
        if (threshold < 0.1) threshold = 0.1;
        if (threshold > 1.0) threshold = 1.0;
        soft_knee_threshold.store(threshold, std::memory_order_relaxed);
    }

    double get_soft_knee_threshold() const {
        return soft_knee_threshold.load(std::memory_order_relaxed);
    }

    void set_auto_preamp_enabled(bool en) {
        auto_preamp_enabled.store(en, std::memory_order_relaxed);
        std::lock_guard<std::mutex> lock(mtx);
        update_coefficients_locked();
    }

    bool get_auto_preamp_enabled() const {
        return auto_preamp_enabled.load(std::memory_order_relaxed);
    }

    void set_manual_preamp_db(double db) {
        if (db < -24.0) db = -24.0;
        if (db > 24.0) db = 24.0;
        std::lock_guard<std::mutex> lock(mtx);
        manual_preamp_db = db;
        update_coefficients_locked();
    }

    double get_manual_preamp_db() const {
        return manual_preamp_db;
    }

    void set_rg_preamp_db(double db) {
        if (db < -60.0) db = -60.0;
        if (db > 24.0) db = 24.0;
        std::lock_guard<std::mutex> lock(mtx);
        rg_preamp_db = db;
        update_coefficients_locked();
    }

    double get_rg_preamp_db() const {
        return rg_preamp_db;
    }

    void reset() {
        std::lock_guard<std::mutex> lock(mtx);
        for (int i = 0; i < NUM_BANDS; i++) {
            filters[i].reset();
        }
    }

    void apply_config(bool en, const int* levels, const double* weights,
                      double manual_db, bool auto_preamp_en, double max_atten, double knee) {
        std::lock_guard<std::mutex> lock(mtx);
        enabled.store(en, std::memory_order_relaxed);
        if (levels) {
            for (int i = 0; i < NUM_BANDS; i++) {
                int lvl = levels[i];
                if (lvl < -1500) lvl = -1500;
                if (lvl > 1500) lvl = 1500;
                band_levels[i] = lvl;
            }
        }
        if (weights) {
            for (int i = 0; i < NUM_BANDS; i++) {
                double w = weights[i];
                if (w < 0.0) w = 0.0;
                if (w > 1.0) w = 1.0;
                band_weights[i] = w;
            }
        }
        if (manual_db < -24.0) manual_db = -24.0;
        if (manual_db > 24.0) manual_db = 24.0;
        manual_preamp_db = manual_db;
        auto_preamp_enabled.store(auto_preamp_en, std::memory_order_relaxed);
        if (max_atten < 0.0) max_atten = 0.0;
        if (max_atten > 24.0) max_atten = 24.0;
        max_attenuation_db = max_atten;
        if (knee < 0.1) knee = 0.1;
        if (knee > 1.0) knee = 1.0;
        soft_knee_threshold.store(knee, std::memory_order_relaxed);
        update_coefficients_locked();
    }

    /**
     * Soft-knee analog-style saturation cushion.
     * For |x| <= threshold: perfectly linear and transparent (100% untouched).
     * For |x| > threshold: smoothly curves towards 1.0 using tanh, eliminating harsh digital red-line clipping.
     */
    static inline double soft_knee(double x, double threshold) {
        double abs_x = std::abs(x);
        if (abs_x <= threshold) return x;
        double margin = 1.0 - threshold;
        if (margin < 0.001) margin = 0.001;
        double excess = abs_x - threshold;
        double compressed = threshold + margin * std::tanh(excess / margin);
        return (x > 0.0) ? compressed : -compressed;
    }

    inline double process_sample(int ch, double in) {
        // Skip entirely only when nothing would change (no preamp, no active bands).
        if (is_flat.load(std::memory_order_relaxed)) {
            return in;
        }
        double s = in * preamp_gain;
        // Only run IIR band filters when EQ is enabled.
        if (enabled.load(std::memory_order_relaxed)) {
            for (int i = 0; i < NUM_BANDS; i++) {
                s = filters[i].process(ch, s);
            }
            s = soft_knee(s, soft_knee_threshold.load(std::memory_order_relaxed));
        }
        return s;
    }

private:
    void update_coefficients_locked() {
        bool all_flat = true;
        double sr = (sample_rate > 0) ? static_cast<double>(sample_rate) : 44100.0;
        bool en = enabled.load(std::memory_order_relaxed);
        // Frequency-weighted progressive soft-curve auto pre-amp with multi-band stacking:
        double total_weighted_boost_db = 0.0;

        for (int i = 0; i < NUM_BANDS; i++) {
            double gain_db = static_cast<double>(band_levels[i]) / 100.0;
            if (!en || std::abs(gain_db) < 0.05) {
                filters[i].b0 = 1.0;
                filters[i].b1 = 0.0;
                filters[i].b2 = 0.0;
                filters[i].a1 = 0.0;
                filters[i].a2 = 0.0;
                filters[i].bypassed = true;
            } else {
                all_flat = false;
                filters[i].bypassed = false;
                double freq = std::min(static_cast<double>(BAND_FREQS[i]), sr * 0.49);
                double a = std::pow(10.0, gain_db / 40.0);
                double omega = 2.0 * M_PI * freq / sr;
                double sin_omega = std::sin(omega);
                double cos_omega = std::cos(omega);
                double alpha = sin_omega / (2.0 * DEFAULT_Q);

                double b0 = 1.0 + alpha * a;
                double b1 = -2.0 * cos_omega;
                double b2 = 1.0 - alpha * a;
                double a0 = 1.0 + alpha / a;
                double a1 = -2.0 * cos_omega;
                double a2 = 1.0 - alpha / a;

                filters[i].b0 = b0 / a0;
                filters[i].b1 = b1 / a0;
                filters[i].b2 = b2 / a0;
                filters[i].a1 = a1 / a0;
                filters[i].a2 = a2 / a0;

                if (gain_db > 0.0) {
                    total_weighted_boost_db += gain_db * band_weights[i];
                }
            }
        }
        double total_preamp_db = manual_preamp_db + rg_preamp_db;
        if (std::abs(total_preamp_db) >= 0.05) {
            all_flat = false;
        }
        is_flat.store(all_flat, std::memory_order_relaxed);

        // Pre-amp gain: manual slider (-24 to +24 dB) + ReplayGain
        double combined_preamp_gain = (std::abs(total_preamp_db) >= 0.05)
            ? std::pow(10.0, total_preamp_db / 20.0)
            : 1.0;

        // Auto pre-amp: dynamic headroom attenuation applied AFTER manual pre-amp
        double auto_gain = 1.0;
        if (en && auto_preamp_enabled.load(std::memory_order_relaxed) && total_weighted_boost_db > 0.0 && max_attenuation_db > 0.0) {
            double attenuation_db = max_attenuation_db * (1.0 - std::exp(-total_weighted_boost_db / max_attenuation_db));
            auto_gain = std::pow(10.0, -attenuation_db / 20.0);
        }

        preamp_gain = combined_preamp_gain * auto_gain;
    }
};

} // namespace tempus
