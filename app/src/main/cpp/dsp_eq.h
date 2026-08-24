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
    static constexpr double DEFAULT_Q = 1.414;

    std::atomic<bool> enabled{false};
    std::atomic<bool> is_flat{true};

private:
    int band_levels[NUM_BANDS] = {0, 0, 0, 0, 0};
    Biquad filters[NUM_BANDS];
    uint32_t sample_rate = 44100;
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

    void reset() {
        std::lock_guard<std::mutex> lock(mtx);
        for (int i = 0; i < NUM_BANDS; i++) {
            filters[i].reset();
        }
    }

    inline double process_sample(int ch, double in) {
        if (!enabled.load(std::memory_order_relaxed) || is_flat.load(std::memory_order_relaxed)) {
            return in;
        }
        double s = in;
        for (int i = 0; i < NUM_BANDS; i++) {
            s = filters[i].process(ch, s);
        }
        return s;
    }

private:
    void update_coefficients_locked() {
        bool all_flat = true;
        double sr = (sample_rate > 0) ? static_cast<double>(sample_rate) : 44100.0;
        bool en = enabled.load(std::memory_order_relaxed);

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
            }
        }
        is_flat.store(all_flat, std::memory_order_relaxed);
    }
};

} // namespace tempus
