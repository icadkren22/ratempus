/*
 * usb_exclusive_output.cpp
 *
 * Bit-perfect USB Audio Class 2.0 (UAC2) Userspace Driver.
 * Directly communicates with USB DAC via Linux usbfs (/dev/bus/usb/...).
 * Completely bypasses Android Audio HAL, AudioFlinger, and ALSA mixer.
 */

#include <jni.h>
#include <android/log.h>
#include <linux/usbdevice_fs.h>
#include <linux/usb/ch9.h>
#include <sys/ioctl.h>
#include <unistd.h>
#include <errno.h>
#include <string.h>
#include <stdint.h>
#include <stdlib.h>
#include <pthread.h>
#include <atomic>
#include <vector>
#include <algorithm>

#define TAG "UsbExclusiveNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define UAC2_REQUEST_SET_CUR     0x01
#define UAC2_CS_CONTROL_SAM_FREQ 0x01
#define UAC2_FU_VOLUME_CONTROL   0x02

// 8 microframe packets per URB = 1 ms per URB for High-Speed (8000 microframes/sec)
#define PACKETS_PER_URB 8
#define NUM_URBS        8

// 1 MB Ring Buffer — strictly frame-aligned
static const int RING_SIZE = 1024 * 1024;
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

/** Write whole PCM frames to ring buffer. Guaranteed to preserve frame alignment. */
static inline int ring_write_frames(const uint8_t* data, int num_frames, int frame_size) {
    if (num_frames <= 0 || frame_size <= 0) return 0;
    int max_frames_free = ring_free_bytes() / frame_size;
    int frames_to_write = std::min(num_frames, max_frames_free);
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

/** Read whole PCM frames from ring buffer. Guaranteed to preserve frame alignment. */
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
    int      channel_count;
    int      src_encoding;     // 4 for PCM_FLOAT, 2 for PCM_16BIT
    int      bytes_per_sample; // DAC destination bytes per sample: 2 for 16-bit, 3 for 24-bit, 4 for 32-bit
    int      frame_size;       // channel_count * bytes_per_sample
    float    volume;           // Fixed 0.30f (30% volume) to protect IEMs

    uint32_t microframe_accum; // Fractional accumulator for High-Speed microframes (8000 Hz)

    std::atomic<bool> running{false};
    pthread_t stream_thread;
    std::vector<UrbSlot*> urb_slots;
};

static inline int next_microframe_frames(uint32_t sample_rate, uint32_t& accum) {
    accum += sample_rate;
    int frames = accum / 8000;
    accum %= 8000;
    return frames;
}

static void fill_urb(UsbAudioCtx* ctx, UrbSlot* slot) {
    struct usbdevfs_urb* u = slot->urb;
    memset(u, 0, sizeof(struct usbdevfs_urb));
    u->type              = USBDEVFS_URB_TYPE_ISO;
    u->endpoint          = ctx->ep_addr;
    u->flags             = USBDEVFS_URB_ISO_ASAP; // Schedule immediately on next microframe
    u->buffer            = slot->pcm_buf;
    u->number_of_packets = PACKETS_PER_URB;
    u->usercontext       = slot;

    int total_offset = 0;
    for (int i = 0; i < PACKETS_PER_URB; i++) {
        int req_frames = next_microframe_frames(ctx->sample_rate, ctx->microframe_accum);
        int pkt_len = req_frames * ctx->frame_size;
        if (pkt_len > ctx->max_packet_size) {
            req_frames = ctx->max_packet_size / ctx->frame_size;
            pkt_len = req_frames * ctx->frame_size;
        }

        u->iso_frame_desc[i].length = pkt_len;

        // Read strictly whole frames from the ring buffer
        int got_frames = ring_read_frames(slot->pcm_buf + total_offset, req_frames, ctx->frame_size);
        int got_bytes = got_frames * ctx->frame_size;

        if (got_bytes < pkt_len) {
            // Fill remainder with exact zeroes (silence) without altering frame alignment
            memset(slot->pcm_buf + total_offset + got_bytes, 0, pkt_len - got_bytes);
        }
        total_offset += pkt_len;
    }
    u->buffer_length = total_offset;
}

static void* urb_thread(void* arg) {
    UsbAudioCtx* ctx = reinterpret_cast<UsbAudioCtx*>(arg);
    LOGI("URB thread started: ep=0x%02x maxPkt=%d sr=%u frameSz=%d",
         ctx->ep_addr, ctx->max_packet_size, ctx->sample_rate, ctx->frame_size);

    // Initial submission of all URBs
    for (auto* slot : ctx->urb_slots) {
        fill_urb(ctx, slot);
        if (ioctl(ctx->fd, USBDEVFS_SUBMITURB, slot->urb) < 0) {
            LOGW("Initial SUBMITURB failed: errno=%d (%s)", errno, strerror(errno));
        }
    }

    while (ctx->running.load(std::memory_order_relaxed)) {
        struct usbdevfs_urb* reaped = nullptr;
        int rc = ioctl(ctx->fd, USBDEVFS_REAPURBNDELAY, &reaped);
        if (rc < 0) {
            if (errno == EAGAIN) {
                usleep(250); // Micro-sleep 250 µs
                continue;
            }
            if (errno == ENODEV || errno == EBADF || errno == ESHUTDOWN) {
                LOGW("USB device disconnected while streaming (errno=%d)", errno);
                break;
            }
            usleep(500);
            continue;
        }

        UrbSlot* slot = nullptr;
        for (auto* s : ctx->urb_slots) {
            if (s->urb == reaped) { slot = s; break; }
        }
        if (!slot) continue;

        // Refill with aligned frames and resubmit
        fill_urb(ctx, slot);
        if (ioctl(ctx->fd, USBDEVFS_SUBMITURB, slot->urb) < 0) {
            if (errno == ENODEV || errno == EBADF || errno == ESHUTDOWN) {
                LOGW("SUBMITURB device gone (errno=%d)", errno);
                break;
            }
        }
    }

    for (auto* slot : ctx->urb_slots) {
        ioctl(ctx->fd, USBDEVFS_DISCARDURB, slot->urb);
    }
    LOGI("URB thread exited");
    return nullptr;
}

static int set_clock_frequency(int fd, uint8_t clock_id, uint32_t rate) {
    struct usbdevfs_ctrltransfer ctrl;
    memset(&ctrl, 0, sizeof(ctrl));
    ctrl.bRequestType = USB_DIR_OUT | USB_TYPE_CLASS | USB_RECIP_INTERFACE;
    ctrl.bRequest     = UAC2_REQUEST_SET_CUR;
    ctrl.wValue       = (uint16_t)(UAC2_CS_CONTROL_SAM_FREQ << 8); // CS 1: SAM_FREQ_CONTROL
    ctrl.wIndex       = (uint16_t)(clock_id << 8) | 0x00;           // Clock ID in high byte, AC iface 0 in low byte
    ctrl.wLength      = 4;
    uint32_t freq     = rate;
    ctrl.data         = &freq;
    ctrl.timeout      = 1000;

    int rc = ioctl(fd, USBDEVFS_CONTROL, &ctrl);
    if (rc < 0) {
        LOGW("set_clock_frequency clock_id=%u sr=%u failed: errno=%d (%s)", clock_id, rate, errno, strerror(errno));
    } else {
        LOGI("set_clock_frequency clock_id=%u sr=%u -> SUCCESS", clock_id, rate);
    }
    return rc;
}

static void set_hardware_volume(int fd, uint8_t fu_id, int16_t vol_db) {
    for (uint8_t ch = 0; ch <= 2; ch++) {
        struct usbdevfs_ctrltransfer ctrl;
        memset(&ctrl, 0, sizeof(ctrl));
        ctrl.bRequestType = USB_DIR_OUT | USB_TYPE_CLASS | USB_RECIP_INTERFACE;
        ctrl.bRequest     = UAC2_REQUEST_SET_CUR;
        ctrl.wValue       = (uint16_t)(UAC2_FU_VOLUME_CONTROL << 8) | ch;
        ctrl.wIndex       = (uint16_t)(fu_id << 8) | 0x00;
        ctrl.wLength      = 2;
        int16_t val       = vol_db;
        ctrl.data         = &val;
        ctrl.timeout      = 500;
        ioctl(fd, USBDEVFS_CONTROL, &ctrl);
    }
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_cappielloantonio_tempo_audio_usb_UsbExclusiveOutput_nativeOpen(
        JNIEnv*, jclass,
        jint fd, jint iface, jint alt, jint ep_addr,
        jint max_packet_size, jint sample_rate,
        jint channel_count, jint bit_depth, jint src_encoding) {

    LOGI("nativeOpen: in_fd=%d iface=%d alt=%d ep=0x%02x maxPkt=%d sr=%d ch=%d bitDepth=%d srcEnc=%d",
         fd, iface, alt, ep_addr, max_packet_size, sample_rate, channel_count, bit_depth, src_encoding);

    int local_fd = dup(fd);
    if (local_fd < 0) {
        LOGE("dup(fd) failed: errno=%d (%s)", errno, strerror(errno));
        local_fd = fd;
    }

    auto* ctx             = new UsbAudioCtx();
    ctx->fd               = local_fd;
    ctx->iface            = iface;
    ctx->alt              = alt;
    ctx->ep_addr          = ep_addr;
    ctx->max_packet_size  = max_packet_size;
    ctx->sample_rate      = static_cast<uint32_t>(sample_rate);
    ctx->channel_count    = channel_count;
    ctx->src_encoding     = src_encoding;
    ctx->bytes_per_sample = bit_depth / 8;
    ctx->frame_size       = channel_count * ctx->bytes_per_sample;
    ctx->volume           = 0.001f; // Fixed 1% volume


    ctx->microframe_accum = 0;

    pthread_mutex_lock(&g_claim_mutex);

    // 1. Disconnect and claim AudioControl (AC) interface 0
    struct usbdevfs_ioctl disconnect_ac;
    disconnect_ac.ifno       = 0;
    disconnect_ac.ioctl_code = USBDEVFS_DISCONNECT;
    disconnect_ac.data       = nullptr;
    ioctl(local_fd, USBDEVFS_IOCTL, &disconnect_ac);

    int claim_ac = 0;
    if (ioctl(local_fd, USBDEVFS_CLAIMINTERFACE, &claim_ac) < 0) {
        LOGW("CLAIMINTERFACE AC (0) failed: errno=%d (%s)", errno, strerror(errno));
    } else {
        LOGI("Claimed AudioControl interface 0");
    }

    // 2. Disconnect and claim AudioStreaming (AS) interface
    struct usbdevfs_ioctl disconnect_as;
    disconnect_as.ifno       = iface;
    disconnect_as.ioctl_code = USBDEVFS_DISCONNECT;
    disconnect_as.data       = nullptr;
    ioctl(local_fd, USBDEVFS_IOCTL, &disconnect_as);

    int claim_iface = iface;
    if (ioctl(local_fd, USBDEVFS_CLAIMINTERFACE, &claim_iface) < 0) {
        LOGE("CLAIMINTERFACE AS (%d) failed: errno=%d (%s)", iface, errno, strerror(errno));
        pthread_mutex_unlock(&g_claim_mutex);
        if (local_fd != fd) close(local_fd);
        delete ctx;
        return 0;
    }
    g_interface_claimed.store(true);
    pthread_mutex_unlock(&g_claim_mutex);
    LOGI("Claimed AudioStreaming interface %d exclusively", iface);

    // 3. Set interface to Alt 0 (Zero Bandwidth) so Clock Source is unlocked for frequency change
    struct usbdevfs_setinterface si0;
    si0.interface  = iface;
    si0.altsetting = 0;
    ioctl(local_fd, USBDEVFS_SETINTERFACE, &si0);

    // 4. Set UAC2 Clock Source frequency (Clock ID 9 for JM6PRO_2)
    set_clock_frequency(local_fd, 9, ctx->sample_rate);

    // 5. Unmute hardware DAC Feature Unit (ID 2) to 0 dB (full scale)
    set_hardware_volume(local_fd, 2, 0x0000);

    // 6. Set alternate setting for active audio streaming
    struct usbdevfs_setinterface si;
    si.interface  = iface;
    si.altsetting = alt;
    if (ioctl(local_fd, USBDEVFS_SETINTERFACE, &si) < 0) {
        LOGE("SETINTERFACE %d/%d failed: errno=%d (%s)", iface, alt, errno, strerror(errno));
        ioctl(local_fd, USBDEVFS_RELEASEINTERFACE, &claim_iface);
        int iface0 = 0;
        ioctl(local_fd, USBDEVFS_RELEASEINTERFACE, &iface0);
        g_interface_claimed.store(false);
        if (local_fd != fd) close(local_fd);
        delete ctx;
        return 0;
    }
    LOGI("Set interface %d alt %d SUCCESS", iface, alt);

    // 7. Pre-allocate URB slots
    for (int i = 0; i < NUM_URBS; i++) {
        ctx->urb_slots.push_back(alloc_urb_slot(max_packet_size));
    }

    g_ring_write.store(0);
    g_ring_read.store(0);

    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT jboolean JNICALL
Java_com_cappielloantonio_tempo_audio_usb_UsbExclusiveOutput_nativeStart(JNIEnv*, jclass, jlong h) {
    auto* ctx = reinterpret_cast<UsbAudioCtx*>(h);
    if (!ctx) return JNI_FALSE;
    if (ctx->running.load()) return JNI_TRUE;

    ctx->running.store(true, std::memory_order_release);
    if (pthread_create(&ctx->stream_thread, nullptr, urb_thread, ctx) != 0) {
        LOGE("pthread_create stream thread failed");
        ctx->running.store(false);
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

JNIEXPORT jint JNICALL
Java_com_cappielloantonio_tempo_audio_usb_UsbExclusiveOutput_nativeWrite(
        JNIEnv* env, jclass, jlong h,
        jobject byteBuffer, jint offset, jint sizeInBytes) {
    auto* ctx = reinterpret_cast<UsbAudioCtx*>(h);
    if (!ctx || sizeInBytes <= 0) return -1;

    void* direct = env->GetDirectBufferAddress(byteBuffer);
    const uint8_t* src_bytes = nullptr;
    jbyteArray arr = nullptr;
    jbyte* elems   = nullptr;

    if (direct) {
        src_bytes = static_cast<const uint8_t*>(direct) + offset;
    } else {
        jclass cls = env->GetObjectClass(byteBuffer);
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

    if (!src_bytes) return -1;

    float vol = ctx->volume; // Fixed 0.30f (30%)
    int src_bytes_per_sample = (ctx->src_encoding == 2) ? 2 : 4;
    int src_frame_size = ctx->channel_count * src_bytes_per_sample;
    int total_input_frames = sizeInBytes / src_frame_size;
    if (total_input_frames <= 0) {
        if (arr && elems) env->ReleaseByteArrayElements(arr, elems, JNI_ABORT);
        return 0;
    }

    // Wait with backpressure if ring buffer is full (up to 20 ms in 500µs intervals)
    for (int retry = 0; retry < 40; retry++) {
        if (ring_free_bytes() >= ctx->frame_size * 64) break;
        usleep(500);
    }

    int frames_written = 0;

    if (ctx->src_encoding == 2) {
        // Source: 16-bit PCM integer
        const int16_t* s16 = reinterpret_cast<const int16_t*>(src_bytes);
        int total_samples = total_input_frames * ctx->channel_count;

        if (ctx->bytes_per_sample == 4) {
            // DAC: 32-bit PCM integer
            std::vector<int32_t> converted(total_samples);
            for (int i = 0; i < total_samples; i++) {
                float s = (s16[i] / 32768.0f) * vol;
                if (s > 1.0f)  s = 1.0f;
                if (s < -1.0f) s = -1.0f;
                converted[i] = static_cast<int32_t>(s * 2147483647.0f);
            }
            frames_written = ring_write_frames(reinterpret_cast<const uint8_t*>(converted.data()), total_input_frames, ctx->frame_size);
        } else if (ctx->bytes_per_sample == 3) {
            // DAC: 24-bit packed PCM
            std::vector<uint8_t> converted(total_samples * 3);
            for (int i = 0; i < total_samples; i++) {
                float s = (s16[i] / 32768.0f) * vol;
                if (s > 1.0f)  s = 1.0f;
                if (s < -1.0f) s = -1.0f;
                int32_t val = static_cast<int32_t>(s * 8388607.0f);
                converted[i * 3 + 0] = static_cast<uint8_t>(val & 0xFF);
                converted[i * 3 + 1] = static_cast<uint8_t>((val >> 8) & 0xFF);
                converted[i * 3 + 2] = static_cast<uint8_t>((val >> 16) & 0xFF);
            }
            frames_written = ring_write_frames(converted.data(), total_input_frames, ctx->frame_size);
        } else {
            // DAC: 16-bit PCM integer
            std::vector<int16_t> converted(total_samples);
            for (int i = 0; i < total_samples; i++) {
                float s = (s16[i] / 32768.0f) * vol;
                if (s > 1.0f)  s = 1.0f;
                if (s < -1.0f) s = -1.0f;
                converted[i] = static_cast<int16_t>(s * 32767.0f);
            }
            frames_written = ring_write_frames(reinterpret_cast<const uint8_t*>(converted.data()), total_input_frames, ctx->frame_size);
        }
    } else {
        // Source: 32-bit Float PCM
        const float* src_float = reinterpret_cast<const float*>(src_bytes);
        int total_samples = total_input_frames * ctx->channel_count;

        if (ctx->bytes_per_sample == 4) {
            // DAC: 32-bit PCM integer
            std::vector<int32_t> converted(total_samples);
            for (int i = 0; i < total_samples; i++) {
                float s = src_float[i] * vol;
                if (s > 1.0f)  s = 1.0f;
                if (s < -1.0f) s = -1.0f;
                converted[i] = static_cast<int32_t>(s * 2147483647.0f);
            }
            frames_written = ring_write_frames(reinterpret_cast<const uint8_t*>(converted.data()), total_input_frames, ctx->frame_size);
        } else if (ctx->bytes_per_sample == 3) {
            // DAC: 24-bit packed PCM
            std::vector<uint8_t> converted(total_samples * 3);
            for (int i = 0; i < total_samples; i++) {
                float s = src_float[i] * vol;
                if (s > 1.0f)  s = 1.0f;
                if (s < -1.0f) s = -1.0f;
                int32_t val = static_cast<int32_t>(s * 8388607.0f);
                converted[i * 3 + 0] = static_cast<uint8_t>(val & 0xFF);
                converted[i * 3 + 1] = static_cast<uint8_t>((val >> 8) & 0xFF);
                converted[i * 3 + 2] = static_cast<uint8_t>((val >> 16) & 0xFF);
            }
            frames_written = ring_write_frames(converted.data(), total_input_frames, ctx->frame_size);
        } else {
            // DAC: 16-bit PCM integer
            std::vector<int16_t> converted(total_samples);
            for (int i = 0; i < total_samples; i++) {
                float s = src_float[i] * vol;
                if (s > 1.0f)  s = 1.0f;
                if (s < -1.0f) s = -1.0f;
                converted[i] = static_cast<int16_t>(s * 32767.0f);
            }
            frames_written = ring_write_frames(reinterpret_cast<const uint8_t*>(converted.data()), total_input_frames, ctx->frame_size);
        }
    }

    if (arr && elems) env->ReleaseByteArrayElements(arr, elems, JNI_ABORT);

    // Return the exact number of SOURCE bytes consumed
    return frames_written * src_frame_size;
}

JNIEXPORT void JNICALL
Java_com_cappielloantonio_tempo_audio_usb_UsbExclusiveOutput_nativeStop(JNIEnv*, jclass, jlong h) {
    auto* ctx = reinterpret_cast<UsbAudioCtx*>(h);
    if (!ctx) return;
    if (ctx->running.exchange(false)) {
        pthread_join(ctx->stream_thread, nullptr);
    }
}

JNIEXPORT void JNICALL
Java_com_cappielloantonio_tempo_audio_usb_UsbExclusiveOutput_nativeClose(JNIEnv*, jclass, jlong h) {
    auto* ctx = reinterpret_cast<UsbAudioCtx*>(h);
    if (!ctx) return;

    if (ctx->running.exchange(false)) {
        pthread_join(ctx->stream_thread, nullptr);
    }

    struct usbdevfs_setinterface si;
    si.interface  = ctx->iface;
    si.altsetting = 0;
    ioctl(ctx->fd, USBDEVFS_SETINTERFACE, &si);

    int iface = ctx->iface;
    ioctl(ctx->fd, USBDEVFS_RELEASEINTERFACE, &iface);
    int iface0 = 0;
    ioctl(ctx->fd, USBDEVFS_RELEASEINTERFACE, &iface0);

    struct usbdevfs_ioctl reconnect_as;
    reconnect_as.ifno       = iface;
    reconnect_as.ioctl_code = USBDEVFS_CONNECT;
    reconnect_as.data       = nullptr;
    ioctl(ctx->fd, USBDEVFS_IOCTL, &reconnect_as);

    struct usbdevfs_ioctl reconnect_ac;
    reconnect_ac.ifno       = 0;
    reconnect_ac.ioctl_code = USBDEVFS_CONNECT;
    reconnect_ac.data       = nullptr;
    ioctl(ctx->fd, USBDEVFS_IOCTL, &reconnect_ac);

    g_interface_claimed.store(false);

    for (auto* slot : ctx->urb_slots) free_urb_slot(slot);
    ctx->urb_slots.clear();

    close(ctx->fd);
    delete ctx;
    LOGI("nativeClose: completed");
}

} // extern "C"
