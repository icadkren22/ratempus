#include <jni.h>
#include <android/log.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/ioctl.h>
#include <linux/usbdevice_fs.h>
#include <pthread.h>
#include <errno.h>
#include <string.h>
#include <stdint.h>
#include <inttypes.h>
#include <stdlib.h>
#include <algorithm>
#include <vector>
#include <atomic>
#include <time.h>
#include <sys/resource.h>
#include <sched.h>
#include "dsp_eq.h"

#define TAG "UsbExclusiveNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ─── USB Audio Class 2.0 (UAC2) Constants ──────────────────────────────────
#define USB_DIR_OUT              0x00
#define USB_DIR_IN               0x80
#define USB_TYPE_CLASS           (0x01 << 5)
#define USB_RECIP_INTERFACE      0x01

#define UAC2_REQUEST_SET_CUR     0x01
#define UAC2_REQUEST_GET_CUR     0x81
#define UAC2_REQUEST_GET_RANGE   0xA2
#define UAC2_CS_CONTROL_SAM_FREQ 0x01
#define UAC2_FU_VOLUME_CONTROL   0x02
#define UAC2_FU_MUTE_CONTROL     0x01

#define JM6PRO2_FU_ID            2
#define JM6PRO2_AC_IFACE         0

// 8 microframe packets per URB = 1 ms per URB for High-Speed (8000 microframes/sec)
#define PACKETS_PER_URB 8
#define NUM_URBS        16

// 256 KB Ring Buffer (~330ms latency at 96kHz stereo 32-bit, frame-aligned)
static const int RING_SIZE = 256 * 1024;
static uint8_t  g_ring[RING_SIZE];
static std::atomic<int> g_ring_write{0};
static std::atomic<int> g_ring_read{0};

static inline int ring_available_bytes() {
    int w = g_ring_write.load(std::memory_order_acquire);
    int r = g_ring_read.load(std::memory_order_relaxed);
    int avail = w - r;
    if (avail < 0) avail += RING_SIZE;
    return avail;
}

static inline int ring_free_bytes() {
    return RING_SIZE - 1 - ring_available_bytes();
}

static inline int ring_write_frames(const uint8_t* data, int frames, int frame_size) {
    if (frames <= 0 || frame_size <= 0) return 0;
    int max_frames = ring_free_bytes() / frame_size;
    int frames_to_write = std::min(frames, max_frames);
    if (frames_to_write <= 0) return 0;

    int bytes_to_write = frames_to_write * frame_size;
    int w = g_ring_write.load(std::memory_order_relaxed);

    for (int i = 0; i < bytes_to_write; i++) {
        g_ring[w] = data[i];
        w = (w + 1) % RING_SIZE;
    }
    g_ring_write.store(w, std::memory_order_release);
    return frames_to_write;
}

static inline int ring_read_frames(uint8_t* buf, int max_frames, int frame_size) {
    if (max_frames <= 0 || frame_size <= 0) return 0;
    int max_frames_avail = ring_available_bytes() / frame_size;
    int frames_to_read = std::min(max_frames, max_frames_avail);
    if (frames_to_read <= 0) return 0;

    int bytes_to_read = frames_to_read * frame_size;
    int r = g_ring_read.load(std::memory_order_relaxed);

    for (int i = 0; i < bytes_to_read; i++) {
        buf[i] = g_ring[r];
        r = (r + 1) % RING_SIZE;
    }
    g_ring_read.store(r, std::memory_order_release);
    return frames_to_read;
}

struct UrbSlot {
    uint8_t* urb_buf;
    struct usbdevfs_urb* urb;
    uint8_t* pcm_buf;
    int pcm_buf_capacity;
};

static UrbSlot* alloc_urb_slot(int max_pkt_size) {
    auto* slot = new UrbSlot();
    size_t urb_size = sizeof(struct usbdevfs_urb) +
                      PACKETS_PER_URB * sizeof(struct usbdevfs_iso_packet_desc);
    slot->urb_buf = new uint8_t[urb_size]();
    slot->urb     = reinterpret_cast<struct usbdevfs_urb*>(slot->urb_buf);
    slot->pcm_buf_capacity = max_pkt_size * PACKETS_PER_URB;
    slot->pcm_buf = new uint8_t[slot->pcm_buf_capacity]();
    return slot;
}

static void free_urb_slot(UrbSlot* slot) {
    if (!slot) return;
    delete[] slot->urb_buf;
    delete[] slot->pcm_buf;
    delete slot;
}

static pthread_mutex_t g_claim_mutex = PTHREAD_MUTEX_INITIALIZER;
static std::atomic<bool> g_interface_claimed{false};

struct UsbAudioCtx {
    int      fd;
    int      iface;
    int      alt;
    int      ep_addr;
    int      max_packet_size;
    uint32_t sample_rate;
    int      channel_count;     // DAC hardware channel count (always 2 for stereo)
    int      src_channel_count; // Source channel count (1 for mono, 2 for stereo)
    int      src_encoding;      // 4 for PCM_FLOAT, 2 for PCM_16BIT
    int      bytes_per_sample;  // DAC bytes per sample: 2 for 16-bit, 3 for 24-bit, 4 for 32-bit
    int      frame_size;        // channel_count * bytes_per_sample (DAC stereo frame size)

    std::atomic<float>    volume{0.005f};            // Software gain (applied in nativeWrite)
    std::atomic<bool>     hw_volume_enabled{true};   // If true: Feature Unit 2 HW volume
    std::atomic<uint64_t> dac_reset_at_frame{0};     // When >0: reset DAC to 0dB once frames_consumed reaches this value
    tempus::DspEqualizer eq;

    uint32_t microframe_accum_q16; // 16.16 fixed point accumulator for 8000 Hz microframes

    std::atomic<uint64_t> frames_consumed{0};
    pthread_mutex_t       ring_mutex = PTHREAD_MUTEX_INITIALIZER;
    pthread_cond_t        ring_cv    = PTHREAD_COND_INITIALIZER;

    std::atomic<bool> running{false};
    pthread_t stream_thread;
    std::vector<UrbSlot*> urb_slots;
    std::vector<uint8_t>  conv_buf;
};

// Calculates exact frames for next High-Speed microframe using 16.16 fixed-point arithmetic
static inline int next_microframe_frames(uint32_t sample_rate, uint32_t& accum_q16) {
    uint32_t frames_per_uframe_q16 = (static_cast<uint64_t>(sample_rate) << 16) / 8000;
    accum_q16 += frames_per_uframe_q16;
    int frames = accum_q16 >> 16;
    accum_q16 &= 0xFFFF;
    return frames;
}

static void set_hardware_volume(int fd, uint8_t fu_id, uint8_t ac_iface, int16_t vol_db_256);
static void set_hardware_mute(int fd, uint8_t fu_id, uint8_t ac_iface, uint8_t mute);

static void fill_urb(UsbAudioCtx* ctx, UrbSlot* slot) {
    struct usbdevfs_urb* u = slot->urb;
    memset(u, 0, sizeof(struct usbdevfs_urb));
    u->type              = USBDEVFS_URB_TYPE_ISO;
    u->endpoint          = ctx->ep_addr;
    u->flags             = USBDEVFS_URB_ISO_ASAP;
    u->buffer            = slot->pcm_buf;
    u->number_of_packets = PACKETS_PER_URB;
    u->usercontext       = slot;

    int total_offset = 0;
    int total_consumed_frames = 0;


    for (int i = 0; i < PACKETS_PER_URB; i++) {
        int req_frames = next_microframe_frames(ctx->sample_rate, ctx->microframe_accum_q16);
        int pkt_len = req_frames * ctx->frame_size;
        if (pkt_len > ctx->max_packet_size) {
            req_frames = ctx->max_packet_size / ctx->frame_size;
            pkt_len = req_frames * ctx->frame_size;
        }

        u->iso_frame_desc[i].length = pkt_len;

        int got_frames = ring_read_frames(slot->pcm_buf + total_offset, req_frames, ctx->frame_size);
        int got_bytes = got_frames * ctx->frame_size;
        total_consumed_frames += got_frames;



        if (got_bytes < pkt_len) {
            // Fill remainder with exact zeroes (silence)
            memset(slot->pcm_buf + total_offset + got_bytes, 0, pkt_len - got_bytes);
        }
        total_offset += pkt_len;
    }
    u->buffer_length = total_offset;

    if (total_consumed_frames > 0) {
        uint64_t consumed = ctx->frames_consumed.fetch_add(total_consumed_frames, std::memory_order_relaxed)
                            + total_consumed_frames;
        pthread_mutex_lock(&ctx->ring_mutex);
        pthread_cond_signal(&ctx->ring_cv);
        pthread_mutex_unlock(&ctx->ring_mutex);

        // Deferred DAC reset: switch DAC to 0dB only once old HW-volume frames have drained
        uint64_t reset_at = ctx->dac_reset_at_frame.load(std::memory_order_relaxed);
        if (reset_at > 0 && consumed >= reset_at) {
            set_hardware_volume(ctx->fd, JM6PRO2_FU_ID, JM6PRO2_AC_IFACE, 0x0000);
            ctx->dac_reset_at_frame.store(0, std::memory_order_relaxed);
            LOGI("fill_urb: deferred DAC reset to 0dB at frame %" PRIu64, consumed);
        }
    }
}

static void* urb_thread(void* arg) {
    auto* ctx = reinterpret_cast<UsbAudioCtx*>(arg);

    // Elevate to Android URGENT_AUDIO priority (nice -19) to match AudioFlinger
    setpriority(PRIO_PROCESS, 0, -19);

    // Attempt SCHED_FIFO real-time priority for lowest possible scheduling jitter
    struct sched_param sp{};
    sp.sched_priority = sched_get_priority_max(SCHED_FIFO);
    if (sched_setscheduler(0, SCHED_FIFO, &sp) != 0) {
        LOGW("SCHED_FIFO not granted (errno=%d), running at nice -19", errno);
    } else {
        LOGI("SCHED_FIFO granted: priority=%d", sp.sched_priority);
    }

    LOGI("URB thread started: ep=0x%02x maxPkt=%d sr=%u frameSz=%d",
         ctx->ep_addr, ctx->max_packet_size, ctx->sample_rate, ctx->frame_size);

    for (auto* slot : ctx->urb_slots) {
        fill_urb(ctx, slot);
        if (ioctl(ctx->fd, USBDEVFS_SUBMITURB, slot->urb) < 0) {
            LOGW("Initial SUBMITURB failed: errno=%d (%s)", errno, strerror(errno));
        }
    }

    while (ctx->running.load(std::memory_order_relaxed)) {
        struct usbdevfs_urb* reaped = nullptr;
        int rc = ioctl(ctx->fd, USBDEVFS_REAPURB, &reaped);
        if (rc < 0) {
            if (errno == EINTR) continue;
            if (!ctx->running.load(std::memory_order_relaxed)) break;
            if (errno == ENODEV || errno == EBADF || errno == ESHUTDOWN || errno == ENOENT) {
                LOGW("USB device disconnected/stopped (errno=%d)", errno);
                break;
            }
            continue;
        }

        UrbSlot* slot = nullptr;
        for (auto* s : ctx->urb_slots) {
            if (s->urb == reaped) { slot = s; break; }
        }
        if (!slot) continue;

        if (!ctx->running.load(std::memory_order_relaxed)) break;

        fill_urb(ctx, slot);
        if (ioctl(ctx->fd, USBDEVFS_SUBMITURB, slot->urb) < 0) {
            if (errno == ENODEV || errno == EBADF || errno == ESHUTDOWN) {
                LOGW("SUBMITURB device gone (errno=%d)", errno);
                break;
            }
        }
    }

    LOGI("URB thread exited");
    return nullptr;
}

static int set_clock_frequency(int fd, uint8_t clock_id, uint32_t rate) {
    struct usbdevfs_ctrltransfer ctrl;
    memset(&ctrl, 0, sizeof(ctrl));
    ctrl.bRequestType = USB_DIR_OUT | USB_TYPE_CLASS | USB_RECIP_INTERFACE;
    ctrl.bRequest     = UAC2_REQUEST_SET_CUR;
    ctrl.wValue       = (uint16_t)(UAC2_CS_CONTROL_SAM_FREQ << 8);
    ctrl.wIndex       = (uint16_t)(clock_id << 8) | JM6PRO2_AC_IFACE;
    ctrl.wLength      = 4;
    uint32_t freq     = rate;
    ctrl.data         = &freq;
    ctrl.timeout      = 1000;

    int rc = ioctl(fd, USBDEVFS_CONTROL, &ctrl);
    LOGI("set_clock_frequency clock_id=%u sr=%u -> %s (rc=%d errno=%d)",
         clock_id, rate, rc >= 0 ? "SUCCESS" : "FAILED", rc, errno);
    return rc;
}

// Set hardware volume on Master (ch=0), Left (ch=1), Right (ch=2) channels.
// vol_db_256 is in UAC2 1/256 dB units: 0x0000 = 0 dB, -20480 = -80 dB, 0x8000 = -128 dB (MUTE).
static void set_hardware_volume(int fd, uint8_t fu_id, uint8_t ac_iface, int16_t vol_db_256) {
    for (uint8_t ch = 0; ch <= 2; ch++) {
        struct usbdevfs_ctrltransfer ctrl;
        memset(&ctrl, 0, sizeof(ctrl));
        ctrl.bRequestType = USB_DIR_OUT | USB_TYPE_CLASS | USB_RECIP_INTERFACE;
        ctrl.bRequest     = UAC2_REQUEST_SET_CUR;
        ctrl.wValue       = (uint16_t)(UAC2_FU_VOLUME_CONTROL << 8) | ch;
        ctrl.wIndex       = (uint16_t)(fu_id << 8) | ac_iface;
        ctrl.wLength      = 2;
        int16_t vol       = vol_db_256;
        ctrl.data         = &vol;
        ctrl.timeout      = 500;
        ioctl(fd, USBDEVFS_CONTROL, &ctrl);
    }
    LOGI("set_hardware_volume fu=%d vol_db=%d (%.2f dB) -> done",
         fu_id, vol_db_256, (float)vol_db_256 / 256.0f);
}

static void set_hardware_mute(int fd, uint8_t fu_id, uint8_t ac_iface, uint8_t mute) {
    for (uint8_t ch = 0; ch <= 2; ch++) {
        struct usbdevfs_ctrltransfer ctrl;
        memset(&ctrl, 0, sizeof(ctrl));
        ctrl.bRequestType = USB_DIR_OUT | USB_TYPE_CLASS | USB_RECIP_INTERFACE;
        ctrl.bRequest     = UAC2_REQUEST_SET_CUR;
        ctrl.wValue       = (uint16_t)(UAC2_FU_MUTE_CONTROL << 8) | ch;
        ctrl.wIndex       = (uint16_t)(fu_id << 8) | ac_iface;
        ctrl.wLength      = 1;
        uint8_t m         = mute;
        ctrl.data         = &m;
        ctrl.timeout      = 500;
        ioctl(fd, USBDEVFS_CONTROL, &ctrl);
    }
    LOGI("set_hardware_mute fu=%d muted=%d -> done", fu_id, mute);
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_eddyizm_tempus_audio_usb_UsbExclusiveOutput_nativeOpen(
        JNIEnv*, jclass,
        jint in_fd, jint iface, jint alt, jint epAddr,
        jint maxPacketSize, jint sampleRate,
        jint channelCount, jint bitDepth,
        jint srcEncoding, jint srcChannelCount) {

    int dac_channels = (channelCount > 0) ? channelCount : 2;
    int src_channels = (srcChannelCount > 0) ? srcChannelCount : 2;

    LOGI("nativeOpen: in_fd=%d iface=%d alt=%d ep=0x%02x maxPkt=%d sr=%d dacCh=%d srcCh=%d bitDepth=%d srcEnc=%d",
         in_fd, iface, alt, epAddr, maxPacketSize, sampleRate, dac_channels, src_channels, bitDepth, srcEncoding);

    int fd = dup(in_fd);
    if (fd < 0) {
        LOGE("dup(in_fd) failed: errno=%d (%s)", errno, strerror(errno));
        return 0;
    }

    pthread_mutex_lock(&g_claim_mutex);

    // AudioControl Interface
    struct usbdevfs_ioctl disconnect_ac;
    disconnect_ac.ifno = JM6PRO2_AC_IFACE;
    disconnect_ac.ioctl_code = USBDEVFS_DISCONNECT;
    disconnect_ac.data = nullptr;
    ioctl(fd, USBDEVFS_IOCTL, &disconnect_ac);

    int claim_ac = JM6PRO2_AC_IFACE;
    if (ioctl(fd, USBDEVFS_CLAIMINTERFACE, &claim_ac) < 0) {
        LOGW("CLAIMINTERFACE(AC=0) returned errno=%d", errno);
    } else {
        LOGI("Claimed AudioControl interface 0");
    }

    // AudioStreaming Interface
    struct usbdevfs_ioctl disconnect_as;
    disconnect_as.ifno = iface;
    disconnect_as.ioctl_code = USBDEVFS_DISCONNECT;
    disconnect_as.data = nullptr;
    ioctl(fd, USBDEVFS_IOCTL, &disconnect_as);

    int claim_as = iface;
    if (ioctl(fd, USBDEVFS_CLAIMINTERFACE, &claim_as) < 0) {
        LOGE("CLAIMINTERFACE(AS=%d) failed: errno=%d (%s)", iface, errno, strerror(errno));
        pthread_mutex_unlock(&g_claim_mutex);
        close(fd);
        return 0;
    }
    LOGI("Claimed AudioStreaming interface %d exclusively", iface);
    g_interface_claimed.store(true);
    pthread_mutex_unlock(&g_claim_mutex);

    // 1. Mute DAC hardware first to eliminate pop during PLL lock / interface switch
    set_hardware_mute(fd, JM6PRO2_FU_ID, JM6PRO2_AC_IFACE, 1);
    set_hardware_volume(fd, JM6PRO2_FU_ID, JM6PRO2_AC_IFACE, -20480);

    // 2. Set clock frequency (Clock ID 9)
    set_clock_frequency(fd, 9, (uint32_t)sampleRate);

    // 3. Switch to Alternate Setting while muted
    struct usbdevfs_setinterface setif;
    setif.interface  = iface;
    setif.altsetting = alt;
    if (ioctl(fd, USBDEVFS_SETINTERFACE, &setif) < 0) {
        LOGE("SETINTERFACE(iface=%d, alt=%d) failed: errno=%d (%s)", iface, alt, errno, strerror(errno));
        ioctl(fd, USBDEVFS_RELEASEINTERFACE, &claim_as);
        close(fd);
        return 0;
    }
    LOGI("Set interface %d alt %d SUCCESS", iface, alt);

    auto* ctx = new UsbAudioCtx();
    ctx->fd                = fd;
    ctx->iface             = iface;
    ctx->alt               = alt;
    ctx->ep_addr           = epAddr;
    ctx->max_packet_size   = maxPacketSize > 0 ? maxPacketSize : 384;
    ctx->sample_rate       = (uint32_t)sampleRate;
    ctx->channel_count     = dac_channels; // Always 2 (stereo DAC)
    ctx->src_channel_count = src_channels; // 1 for mono, 2 for stereo
    ctx->src_encoding      = srcEncoding;
    ctx->bytes_per_sample  = bitDepth / 8;
    ctx->frame_size        = dac_channels * ctx->bytes_per_sample;
    ctx->volume.store(0.005f);
    ctx->hw_volume_enabled.store(true);
    ctx->eq.set_sample_rate(ctx->sample_rate);
    ctx->microframe_accum_q16 = 0;
    ctx->frames_consumed.store(0);

    // Reset ring buffer and memory
    memset(g_ring, 0, sizeof(g_ring));
    g_ring_write.store(0);
    g_ring_read.store(0);

    for (int i = 0; i < NUM_URBS; i++) {
        ctx->urb_slots.push_back(alloc_urb_slot(ctx->max_packet_size));
    }

    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT jboolean JNICALL
Java_com_eddyizm_tempus_audio_usb_UsbExclusiveOutput_nativeStart(
        JNIEnv*, jclass, jlong h) {
    auto* ctx = reinterpret_cast<UsbAudioCtx*>(h);
    if (!ctx) return JNI_FALSE;

    if (!ctx->running.load(std::memory_order_relaxed)) {
        ctx->running.store(true);
        if (pthread_create(&ctx->stream_thread, nullptr, urb_thread, ctx) != 0) {
            LOGE("Failed to create URB thread: errno=%d (%s)", errno, strerror(errno));
            ctx->running.store(false);
            return JNI_FALSE;
        }
        usleep(2000); // 2 ms
        set_hardware_mute(ctx->fd, JM6PRO2_FU_ID, JM6PRO2_AC_IFACE, 0);
    }
    return JNI_TRUE;
}

JNIEXPORT jint JNICALL
Java_com_eddyizm_tempus_audio_usb_UsbExclusiveOutput_nativeWrite(
        JNIEnv* env, jclass, jlong h,
        jobject byteBuffer, jint offset, jint sizeInBytes) {

    auto* ctx = reinterpret_cast<UsbAudioCtx*>(h);
    if (!ctx || sizeInBytes <= 0) return -1;

    const uint8_t* src_bytes = nullptr;
    jbyteArray arr = nullptr;
    jbyte* elems = nullptr;

    void* direct = env->GetDirectBufferAddress(byteBuffer);
    if (direct) {
        src_bytes = reinterpret_cast<const uint8_t*>(direct) + offset;
    } else {
        jclass cls = env->GetObjectClass(byteBuffer);
        jmethodID mHas = env->GetMethodID(cls, "hasArray", "()Z");
        if (mHas && env->CallBooleanMethod(byteBuffer, mHas)) {
            jmethodID mArr = env->GetMethodID(cls, "array", "()[B");
            jmethodID mArrOff = env->GetMethodID(cls, "arrayOffset", "()I");
            if (mArr && mArrOff) {
                arr = (jbyteArray)env->CallObjectMethod(byteBuffer, mArr);
                jint ao = env->CallIntMethod(byteBuffer, mArrOff);
                if (arr) {
                    elems = env->GetByteArrayElements(arr, nullptr);
                    if (elems) {
                        src_bytes = reinterpret_cast<const uint8_t*>(elems) + ao + offset;
                    }
                }
            }
        }
    }

    if (!src_bytes) return -1;

    int src_bytes_per_sample = (ctx->src_encoding == 2) ? 2 : 4;
    int src_frame_size = ctx->src_channel_count * src_bytes_per_sample;
    int total_input_frames = sizeInBytes / src_frame_size;
    if (total_input_frames <= 0) {
        if (arr && elems) env->ReleaseByteArrayElements(arr, elems, JNI_ABORT);
        return 0;
    }

    // DirectHD Synchronized Backpressure: block on ring_cv until space is available
    pthread_mutex_lock(&ctx->ring_mutex);
    while (ring_free_bytes() < total_input_frames * ctx->frame_size && ctx->running.load(std::memory_order_relaxed)) {
        struct timespec ts;
        clock_gettime(CLOCK_REALTIME, &ts);
        ts.tv_nsec += 20000000; // 20 ms
        if (ts.tv_nsec >= 1000000000) {
            ts.tv_sec += 1;
            ts.tv_nsec -= 1000000000;
        }
        int rc = pthread_cond_timedwait(&ctx->ring_cv, &ctx->ring_mutex, &ts);
        if (rc != 0) break;
    }
    pthread_mutex_unlock(&ctx->ring_mutex);

    // Prepare destination buffer in conv_buf (avoids dynamic heap allocation per write)
    int dac_bytes_needed = total_input_frames * ctx->frame_size;
    if (ctx->conv_buf.size() < static_cast<size_t>(dac_bytes_needed)) {
        ctx->conv_buf.resize(dac_bytes_needed);
    }
    uint8_t* dst_bytes = ctx->conv_buf.data();

    bool eq_active  = ctx->eq.enabled.load(std::memory_order_relaxed)
                   && !ctx->eq.is_flat.load(std::memory_order_relaxed);
    bool hw_mode    = ctx->hw_volume_enabled.load(std::memory_order_relaxed);
    float sw_vol    = ctx->volume.load(std::memory_order_relaxed);
    bool vol_active = !hw_mode && sw_vol < 0.999f;
    bool dsp_active = eq_active || vol_active;
    double vol_scale = vol_active ? static_cast<double>(sw_vol) : 1.0;

    int num_src_ch = ctx->src_channel_count;
    bool is_mono = (num_src_ch == 1);
    bool is_float = (ctx->src_encoding != 2);

    const float* src_f = is_float ? reinterpret_cast<const float*>(src_bytes) : nullptr;
    const int16_t* src_s16 = !is_float ? reinterpret_cast<const int16_t*>(src_bytes) : nullptr;

    if (!dsp_active) {
        // ─── FAST BIT-PERFECT BYPASS PATH (NO DSP) ───────────────────────────
        if (is_mono) {
            // Mono Source -> Stereo Expansion
            if (is_float) {
                if (ctx->bytes_per_sample == 4) {
                    int32_t* dst32 = reinterpret_cast<int32_t*>(dst_bytes);
                    for (int i = 0; i < total_input_frames; i++) {
                        float f = src_f[i];
                        if (f > 1.f) f = 1.f; else if (f < -1.f) f = -1.f;
                        int32_t val = static_cast<int32_t>(f * 2147483647.f);
                        dst32[2 * i + 0] = val;
                        dst32[2 * i + 1] = val;
                    }
                } else if (ctx->bytes_per_sample == 3) {
                    for (int i = 0; i < total_input_frames; i++) {
                        float f = src_f[i];
                        if (f > 1.f) f = 1.f; else if (f < -1.f) f = -1.f;
                        int32_t val = static_cast<int32_t>(f * 8388607.f);
                        dst_bytes[6 * i + 0] = static_cast<uint8_t>(val & 0xFF);
                        dst_bytes[6 * i + 1] = static_cast<uint8_t>((val >> 8) & 0xFF);
                        dst_bytes[6 * i + 2] = static_cast<uint8_t>((val >> 16) & 0xFF);
                        dst_bytes[6 * i + 3] = static_cast<uint8_t>(val & 0xFF);
                        dst_bytes[6 * i + 4] = static_cast<uint8_t>((val >> 8) & 0xFF);
                        dst_bytes[6 * i + 5] = static_cast<uint8_t>((val >> 16) & 0xFF);
                    }
                } else {
                    int16_t* dst16 = reinterpret_cast<int16_t*>(dst_bytes);
                    for (int i = 0; i < total_input_frames; i++) {
                        float f = src_f[i];
                        if (f > 1.f) f = 1.f; else if (f < -1.f) f = -1.f;
                        int16_t val = static_cast<int16_t>(f * 32767.f);
                        dst16[2 * i + 0] = val;
                        dst16[2 * i + 1] = val;
                    }
                }
            } else {
                // Int16 Mono
                if (ctx->bytes_per_sample == 4) {
                    int32_t* dst32 = reinterpret_cast<int32_t*>(dst_bytes);
                    for (int i = 0; i < total_input_frames; i++) {
                        int32_t val = static_cast<int32_t>(src_s16[i]) << 16;
                        dst32[2 * i + 0] = val;
                        dst32[2 * i + 1] = val;
                    }
                } else if (ctx->bytes_per_sample == 3) {
                    for (int i = 0; i < total_input_frames; i++) {
                        int32_t val = static_cast<int32_t>(src_s16[i]) << 8;
                        dst_bytes[6 * i + 0] = static_cast<uint8_t>(val & 0xFF);
                        dst_bytes[6 * i + 1] = static_cast<uint8_t>((val >> 8) & 0xFF);
                        dst_bytes[6 * i + 2] = static_cast<uint8_t>((val >> 16) & 0xFF);
                        dst_bytes[6 * i + 3] = static_cast<uint8_t>(val & 0xFF);
                        dst_bytes[6 * i + 4] = static_cast<uint8_t>((val >> 8) & 0xFF);
                        dst_bytes[6 * i + 5] = static_cast<uint8_t>((val >> 16) & 0xFF);
                    }
                } else {
                    int16_t* dst16 = reinterpret_cast<int16_t*>(dst_bytes);
                    for (int i = 0; i < total_input_frames; i++) {
                        int16_t val = src_s16[i];
                        dst16[2 * i + 0] = val;
                        dst16[2 * i + 1] = val;
                    }
                }
            }
        } else {
            // Stereo Source
            int total_samples = total_input_frames * 2;
            if (is_float) {
                if (ctx->bytes_per_sample == 4) {
                    int32_t* dst32 = reinterpret_cast<int32_t*>(dst_bytes);
                    for (int i = 0; i < total_samples; i++) {
                        float f = src_f[i];
                        if (f > 1.f) f = 1.f; else if (f < -1.f) f = -1.f;
                        dst32[i] = static_cast<int32_t>(f * 2147483647.f);
                    }
                } else if (ctx->bytes_per_sample == 3) {
                    for (int i = 0; i < total_samples; i++) {
                        float f = src_f[i];
                        if (f > 1.f) f = 1.f; else if (f < -1.f) f = -1.f;
                        int32_t val = static_cast<int32_t>(f * 8388607.f);
                        dst_bytes[3 * i + 0] = static_cast<uint8_t>(val & 0xFF);
                        dst_bytes[3 * i + 1] = static_cast<uint8_t>((val >> 8) & 0xFF);
                        dst_bytes[3 * i + 2] = static_cast<uint8_t>((val >> 16) & 0xFF);
                    }
                } else {
                    int16_t* dst16 = reinterpret_cast<int16_t*>(dst_bytes);
                    for (int i = 0; i < total_samples; i++) {
                        float f = src_f[i];
                        if (f > 1.f) f = 1.f; else if (f < -1.f) f = -1.f;
                        dst16[i] = static_cast<int16_t>(f * 32767.f);
                    }
                }
            } else {
                // Int16 Stereo
                if (ctx->bytes_per_sample == 4) {
                    int32_t* dst32 = reinterpret_cast<int32_t*>(dst_bytes);
                    for (int i = 0; i < total_samples; i++) {
                        dst32[i] = static_cast<int32_t>(src_s16[i]) << 16;
                    }
                } else if (ctx->bytes_per_sample == 3) {
                    for (int i = 0; i < total_samples; i++) {
                        int32_t val = static_cast<int32_t>(src_s16[i]) << 8;
                        dst_bytes[3 * i + 0] = static_cast<uint8_t>(val & 0xFF);
                        dst_bytes[3 * i + 1] = static_cast<uint8_t>((val >> 8) & 0xFF);
                        dst_bytes[3 * i + 2] = static_cast<uint8_t>((val >> 16) & 0xFF);
                    }
                } else {
                    dst_bytes = const_cast<uint8_t*>(src_bytes); // direct pass-through pointer!
                }
            }
        }
    } else {
        // ─── ACTIVE DSP PATH (64-bit Double EQ + SW Volume in Single Pass) ───
        if (is_mono) {
            // Mono Source -> Stereo Expansion
            for (int i = 0; i < total_input_frames; i++) {
                double s = is_float ? static_cast<double>(src_f[i])
                                    : (static_cast<double>(src_s16[i]) / 32768.0);
                if (eq_active) {
                    s = ctx->eq.process_sample(0, s);
                }
                if (vol_active) {
                    s *= vol_scale;
                }
                if (s > 1.0) s = 1.0; else if (s < -1.0) s = -1.0;

                if (ctx->bytes_per_sample == 4) {
                    int32_t* dst32 = reinterpret_cast<int32_t*>(dst_bytes);
                    int32_t val = static_cast<int32_t>(s * 2147483647.0);
                    dst32[2 * i + 0] = val;
                    dst32[2 * i + 1] = val;
                } else if (ctx->bytes_per_sample == 3) {
                    int32_t val = static_cast<int32_t>(s * 8388607.0);
                    dst_bytes[6 * i + 0] = static_cast<uint8_t>(val & 0xFF);
                    dst_bytes[6 * i + 1] = static_cast<uint8_t>((val >> 8) & 0xFF);
                    dst_bytes[6 * i + 2] = static_cast<uint8_t>((val >> 16) & 0xFF);
                    dst_bytes[6 * i + 3] = static_cast<uint8_t>(val & 0xFF);
                    dst_bytes[6 * i + 4] = static_cast<uint8_t>((val >> 8) & 0xFF);
                    dst_bytes[6 * i + 5] = static_cast<uint8_t>((val >> 16) & 0xFF);
                } else {
                    int16_t* dst16 = reinterpret_cast<int16_t*>(dst_bytes);
                    int16_t val = static_cast<int16_t>(s * 32767.0);
                    dst16[2 * i + 0] = val;
                    dst16[2 * i + 1] = val;
                }
            }
        } else {
            // Stereo Source
            int total_samples = total_input_frames * 2;
            int ch = 0;
            if (ctx->bytes_per_sample == 4) {
                int32_t* dst32 = reinterpret_cast<int32_t*>(dst_bytes);
                for (int i = 0; i < total_samples; i++) {
                    double s = is_float ? static_cast<double>(src_f[i])
                                        : (static_cast<double>(src_s16[i]) / 32768.0);
                    if (eq_active) {
                        s = ctx->eq.process_sample(ch, s);
                    }
                    if (vol_active) {
                        s *= vol_scale;
                    }
                    if (s > 1.0) s = 1.0; else if (s < -1.0) s = -1.0;
                    dst32[i] = static_cast<int32_t>(s * 2147483647.0);
                    ch = (ch + 1) % 2;
                }
            } else if (ctx->bytes_per_sample == 3) {
                for (int i = 0; i < total_samples; i++) {
                    double s = is_float ? static_cast<double>(src_f[i])
                                        : (static_cast<double>(src_s16[i]) / 32768.0);
                    if (eq_active) {
                        s = ctx->eq.process_sample(ch, s);
                    }
                    if (vol_active) {
                        s *= vol_scale;
                    }
                    if (s > 1.0) s = 1.0; else if (s < -1.0) s = -1.0;
                    int32_t val = static_cast<int32_t>(s * 8388607.0);
                    dst_bytes[3 * i + 0] = static_cast<uint8_t>(val & 0xFF);
                    dst_bytes[3 * i + 1] = static_cast<uint8_t>((val >> 8) & 0xFF);
                    dst_bytes[3 * i + 2] = static_cast<uint8_t>((val >> 16) & 0xFF);
                    ch = (ch + 1) % 2;
                }
            } else {
                int16_t* dst16 = reinterpret_cast<int16_t*>(dst_bytes);
                for (int i = 0; i < total_samples; i++) {
                    double s = is_float ? static_cast<double>(src_f[i])
                                        : (static_cast<double>(src_s16[i]) / 32768.0);
                    if (eq_active) {
                        s = ctx->eq.process_sample(ch, s);
                    }
                    if (vol_active) {
                        s *= vol_scale;
                    }
                    if (s > 1.0) s = 1.0; else if (s < -1.0) s = -1.0;
                    dst16[i] = static_cast<int16_t>(s * 32767.0);
                    ch = (ch + 1) % 2;
                }
            }
        }
    }

    int frames_written = ring_write_frames(dst_bytes, total_input_frames, ctx->frame_size);

    if (arr && elems) env->ReleaseByteArrayElements(arr, elems, JNI_ABORT);
    return frames_written * src_frame_size;
}

JNIEXPORT jlong JNICALL
Java_com_eddyizm_tempus_audio_usb_UsbExclusiveOutput_nativeGetPositionUs(
        JNIEnv*, jclass, jlong h) {
    auto* ctx = reinterpret_cast<UsbAudioCtx*>(h);
    if (!ctx || ctx->sample_rate == 0) return 0;
    uint64_t consumed = ctx->frames_consumed.load(std::memory_order_relaxed);
    return (consumed * 1000000ULL) / ctx->sample_rate;
}

JNIEXPORT void JNICALL
Java_com_eddyizm_tempus_audio_usb_UsbExclusiveOutput_nativeSetVolume(
        JNIEnv*, jclass, jlong h, jfloat volume) {
    auto* ctx = reinterpret_cast<UsbAudioCtx*>(h);
    if (!ctx) return;
    if (volume < 0.0f) volume = 0.0f;
    if (volume > 0.40f) volume = 0.40f; // Safe 0.40f max ceiling for software attenuation
    ctx->volume.store(volume, std::memory_order_relaxed);
    LOGI("nativeSetVolume: updated sw gain to %.6f", volume);
}

JNIEXPORT void JNICALL
Java_com_eddyizm_tempus_audio_usb_UsbExclusiveOutput_nativeSetHwVolume(
        JNIEnv*, jclass, jlong h, jboolean enabled, jshort vol_db_256, jfloat sw_gain) {
    auto* ctx = reinterpret_cast<UsbAudioCtx*>(h);
    if (!ctx) return;

    if (enabled) {
        ctx->hw_volume_enabled.store(true, std::memory_order_relaxed);
        set_hardware_volume(ctx->fd, JM6PRO2_FU_ID, JM6PRO2_AC_IFACE, (int16_t)vol_db_256);
        set_hardware_mute(ctx->fd, JM6PRO2_FU_ID, JM6PRO2_AC_IFACE, 0);
        LOGI("nativeSetHwVolume: HW mode ON vol_db_256=%d (%.2f dB)", vol_db_256, (float)vol_db_256 / 256.0f);
    } else {
        // Store SW gain and flip mode — nativeWrite will immediately apply SW volume to new frames
        ctx->volume.store(sw_gain, std::memory_order_relaxed);
        ctx->hw_volume_enabled.store(false, std::memory_order_relaxed);

        // Schedule deferred DAC reset: reset to 0dB only after old HW-volume frames drain from ring buffer
        uint64_t consumed = ctx->frames_consumed.load(std::memory_order_relaxed);
        int ring_used_bytes = RING_SIZE - 1 - ring_free_bytes();
        uint64_t ring_used_frames = (ctx->frame_size > 0) ? (ring_used_bytes / ctx->frame_size) : 0;
        ctx->dac_reset_at_frame.store(consumed + ring_used_frames + 1, std::memory_order_relaxed);

        LOGI("nativeSetHwVolume: HW mode OFF — deferred DAC reset at frame %" PRIu64 " (ring ~%" PRIu64 " frames), SW gain=%.6f",
             consumed + ring_used_frames + 1, ring_used_frames, sw_gain);
    }
}

JNIEXPORT void JNICALL
Java_com_eddyizm_tempus_audio_usb_UsbExclusiveOutput_nativeStop(
        JNIEnv*, jclass, jlong h) {
    auto* ctx = reinterpret_cast<UsbAudioCtx*>(h);
    if (!ctx) return;
    set_hardware_mute(ctx->fd, JM6PRO2_FU_ID, JM6PRO2_AC_IFACE, 1);
    if (ctx->running.exchange(false)) {
        for (auto* slot : ctx->urb_slots) {
            ioctl(ctx->fd, USBDEVFS_DISCARDURB, slot->urb);
        }
        pthread_join(ctx->stream_thread, nullptr);
    }
}

JNIEXPORT void JNICALL
Java_com_eddyizm_tempus_audio_usb_UsbExclusiveOutput_nativeClose(
        JNIEnv*, jclass, jlong h) {
    auto* ctx = reinterpret_cast<UsbAudioCtx*>(h);
    if (!ctx) return;

    if (ctx->running.exchange(false)) {
        for (auto* slot : ctx->urb_slots) {
            ioctl(ctx->fd, USBDEVFS_DISCARDURB, slot->urb);
        }
        pthread_join(ctx->stream_thread, nullptr);
    }

    // Restore DAC hardware to unmuted 0 dB so OS/kernel driver can output sound
    set_hardware_mute(ctx->fd, JM6PRO2_FU_ID, JM6PRO2_AC_IFACE, 0);
    set_hardware_volume(ctx->fd, JM6PRO2_FU_ID, JM6PRO2_AC_IFACE, 0x0000);

    struct usbdevfs_setinterface si;
    si.interface  = ctx->iface;
    si.altsetting = 0;
    ioctl(ctx->fd, USBDEVFS_SETINTERFACE, &si);

    int iface = ctx->iface;
    ioctl(ctx->fd, USBDEVFS_RELEASEINTERFACE, &iface);

    int ac_iface = JM6PRO2_AC_IFACE;
    ioctl(ctx->fd, USBDEVFS_RELEASEINTERFACE, &ac_iface);
    g_interface_claimed.store(false);

    // Re-bind snd-usb-audio kernel driver so Android can resume normal USB audio routing
    struct usbdevfs_ioctl reconnect_as;
    reconnect_as.ifno       = iface;
    reconnect_as.ioctl_code = USBDEVFS_CONNECT;
    reconnect_as.data       = nullptr;
    ioctl(ctx->fd, USBDEVFS_IOCTL, &reconnect_as);

    struct usbdevfs_ioctl reconnect_ac;
    reconnect_ac.ifno       = JM6PRO2_AC_IFACE;
    reconnect_ac.ioctl_code = USBDEVFS_CONNECT;
    reconnect_ac.data       = nullptr;
    ioctl(ctx->fd, USBDEVFS_IOCTL, &reconnect_ac);

    for (auto* slot : ctx->urb_slots) free_urb_slot(slot);
    ctx->urb_slots.clear();

    close(ctx->fd);
    delete ctx;
    LOGI("nativeClose: completed — snd-usb-audio re-bound");
}

JNIEXPORT void JNICALL
Java_com_eddyizm_tempus_audio_usb_UsbExclusiveOutput_nativeSetEqEnabled(
        JNIEnv*, jclass, jlong h, jboolean enabled) {
    auto* ctx = reinterpret_cast<UsbAudioCtx*>(h);
    if (ctx) ctx->eq.set_enabled(enabled == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_eddyizm_tempus_audio_usb_UsbExclusiveOutput_nativeSetEqBand(
        JNIEnv*, jclass, jlong h, jint band, jint level_mb) {
    auto* ctx = reinterpret_cast<UsbAudioCtx*>(h);
    if (ctx) ctx->eq.set_band_level(band, level_mb);
}

} // extern "C"
