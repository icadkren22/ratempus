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
#include <stdlib.h>
#include <algorithm>
#include <vector>
#include <atomic>
#include <time.h>

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
#define NUM_URBS        8

// 128 KB Ring Buffer (~80ms latency at 192kHz stereo 32-bit, frame-aligned)
static const int RING_SIZE = 128 * 1024;
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

    std::atomic<float> volume{0.005f};          // Software gain (applied in fill_urb)
    std::atomic<bool>  hw_volume_enabled{true}; // If true: Feature Unit 2 HW volume

    uint32_t microframe_accum_q16; // 16.16 fixed point accumulator for 8000 Hz microframes

    std::atomic<uint64_t> frames_consumed{0};
    pthread_mutex_t       ring_mutex = PTHREAD_MUTEX_INITIALIZER;
    pthread_cond_t        ring_cv    = PTHREAD_COND_INITIALIZER;

    std::atomic<bool> running{false};
    pthread_t stream_thread;
    std::vector<UrbSlot*> urb_slots;
};

// Calculates exact frames for next High-Speed microframe using 16.16 fixed-point arithmetic
static inline int next_microframe_frames(uint32_t sample_rate, uint32_t& accum_q16) {
    uint32_t frames_per_uframe_q16 = (static_cast<uint64_t>(sample_rate) << 16) / 8000;
    accum_q16 += frames_per_uframe_q16;
    int frames = accum_q16 >> 16;
    accum_q16 &= 0xFFFF;
    return frames;
}

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

    bool hw_mode = ctx->hw_volume_enabled.load(std::memory_order_relaxed);
    float sw_vol = ctx->volume.load(std::memory_order_relaxed);

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

        // Apply software volume right at the 1ms URB stage when in SW mode (eliminates all buffer latency)
        if (got_frames > 0 && !hw_mode && sw_vol < 0.999f) {
            uint8_t* p = slot->pcm_buf + total_offset;
            int total_samples = got_frames * ctx->channel_count;
            if (ctx->bytes_per_sample == 4) {
                int32_t* s32 = reinterpret_cast<int32_t*>(p);
                for (int s = 0; s < total_samples; s++) {
                    s32[s] = static_cast<int32_t>(s32[s] * sw_vol);
                }
            } else if (ctx->bytes_per_sample == 3) {
                for (int s = 0; s < total_samples; s++) {
                    int32_t val = static_cast<int32_t>(p[3 * s] | (p[3 * s + 1] << 8) | (p[3 * s + 2] << 16));
                    if (val & 0x800000) val |= 0xFF000000;
                    val = static_cast<int32_t>(val * sw_vol);
                    p[3 * s + 0] = static_cast<uint8_t>(val & 0xFF);
                    p[3 * s + 1] = static_cast<uint8_t>((val >> 8) & 0xFF);
                    p[3 * s + 2] = static_cast<uint8_t>((val >> 16) & 0xFF);
                }
            } else if (ctx->bytes_per_sample == 2) {
                int16_t* s16 = reinterpret_cast<int16_t*>(p);
                for (int s = 0; s < total_samples; s++) {
                    s16[s] = static_cast<int16_t>(s16[s] * sw_vol);
                }
            }
        }

        if (got_bytes < pkt_len) {
            // Fill remainder with exact zeroes (silence)
            memset(slot->pcm_buf + total_offset + got_bytes, 0, pkt_len - got_bytes);
        }
        total_offset += pkt_len;
    }
    u->buffer_length = total_offset;

    if (total_consumed_frames > 0) {
        ctx->frames_consumed.fetch_add(total_consumed_frames, std::memory_order_relaxed);
        pthread_mutex_lock(&ctx->ring_mutex);
        pthread_cond_signal(&ctx->ring_cv);
        pthread_mutex_unlock(&ctx->ring_mutex);
    }
}

static void* urb_thread(void* arg) {
    auto* ctx = reinterpret_cast<UsbAudioCtx*>(arg);
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
        int rc = ioctl(ctx->fd, USBDEVFS_REAPURBNDELAY, &reaped);
        if (rc < 0) {
            if (errno == EAGAIN) {
                usleep(250);
                continue;
            }
            if (errno == ENODEV || errno == EBADF || errno == ESHUTDOWN) {
                LOGW("USB device disconnected (errno=%d)", errno);
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

    int frames_written = 0;

    // Direct, bit-perfect PCM writing to ring buffer (no scaling here; scaled in fill_urb if SW mode)
    if (ctx->src_channel_count == 1) {
        // ─── MONO SOURCE (1 Channel -> 2 Channels Stereo Expansion) ─────────────
        if (ctx->src_encoding == 2) {
            // Source: Int16 Mono
            const int16_t* s16 = reinterpret_cast<const int16_t*>(src_bytes);
            if (ctx->bytes_per_sample == 4) {
                // DAC: 32-bit Stereo
                std::vector<int32_t> converted(total_input_frames * 2);
                for (int i = 0; i < total_input_frames; i++) {
                    int32_t val = static_cast<int32_t>(s16[i]) << 16;
                    converted[2 * i + 0] = val; // Left
                    converted[2 * i + 1] = val; // Right
                }
                frames_written = ring_write_frames(reinterpret_cast<const uint8_t*>(converted.data()), total_input_frames, ctx->frame_size);
            } else if (ctx->bytes_per_sample == 3) {
                // DAC: 24-bit Stereo
                std::vector<uint8_t> converted(total_input_frames * 2 * 3);
                for (int i = 0; i < total_input_frames; i++) {
                    int32_t val = static_cast<int32_t>(s16[i]) << 8;
                    converted[6 * i + 0] = static_cast<uint8_t>(val & 0xFF);
                    converted[6 * i + 1] = static_cast<uint8_t>((val >> 8) & 0xFF);
                    converted[6 * i + 2] = static_cast<uint8_t>((val >> 16) & 0xFF);
                    converted[6 * i + 3] = static_cast<uint8_t>(val & 0xFF);
                    converted[6 * i + 4] = static_cast<uint8_t>((val >> 8) & 0xFF);
                    converted[6 * i + 5] = static_cast<uint8_t>((val >> 16) & 0xFF);
                }
                frames_written = ring_write_frames(converted.data(), total_input_frames, ctx->frame_size);
            } else {
                // DAC: 16-bit Stereo
                std::vector<int16_t> converted(total_input_frames * 2);
                for (int i = 0; i < total_input_frames; i++) {
                    converted[2 * i + 0] = s16[i];
                    converted[2 * i + 1] = s16[i];
                }
                frames_written = ring_write_frames(reinterpret_cast<const uint8_t*>(converted.data()), total_input_frames, ctx->frame_size);
            }
        } else {
            // Source: Float32 Mono
            const float* src_float = reinterpret_cast<const float*>(src_bytes);
            if (ctx->bytes_per_sample == 4) {
                // DAC: 32-bit Stereo
                std::vector<int32_t> converted(total_input_frames * 2);
                for (int i = 0; i < total_input_frames; i++) {
                    float f = src_float[i];
                    if (f > 1.0f)  f = 1.0f;
                    if (f < -1.0f) f = -1.0f;
                    int32_t val = static_cast<int32_t>(f * 2147483647.0f);
                    converted[2 * i + 0] = val; // Left
                    converted[2 * i + 1] = val; // Right
                }
                frames_written = ring_write_frames(reinterpret_cast<const uint8_t*>(converted.data()), total_input_frames, ctx->frame_size);
            } else if (ctx->bytes_per_sample == 3) {
                // DAC: 24-bit Stereo
                std::vector<uint8_t> converted(total_input_frames * 2 * 3);
                for (int i = 0; i < total_input_frames; i++) {
                    float f = src_float[i];
                    if (f > 1.0f)  f = 1.0f;
                    if (f < -1.0f) f = -1.0f;
                    int32_t val = static_cast<int32_t>(f * 8388607.0f);
                    converted[6 * i + 0] = static_cast<uint8_t>(val & 0xFF);
                    converted[6 * i + 1] = static_cast<uint8_t>((val >> 8) & 0xFF);
                    converted[6 * i + 2] = static_cast<uint8_t>((val >> 16) & 0xFF);
                    converted[6 * i + 3] = static_cast<uint8_t>(val & 0xFF);
                    converted[6 * i + 4] = static_cast<uint8_t>((val >> 8) & 0xFF);
                    converted[6 * i + 5] = static_cast<uint8_t>((val >> 16) & 0xFF);
                }
                frames_written = ring_write_frames(converted.data(), total_input_frames, ctx->frame_size);
            } else {
                // DAC: 16-bit Stereo
                std::vector<int16_t> converted(total_input_frames * 2);
                for (int i = 0; i < total_input_frames; i++) {
                    float f = src_float[i];
                    if (f > 1.0f)  f = 1.0f;
                    if (f < -1.0f) f = -1.0f;
                    int32_t val = static_cast<int32_t>(f * 32767.0f);
                    converted[2 * i + 0] = val;
                    converted[2 * i + 1] = val;
                }
                frames_written = ring_write_frames(reinterpret_cast<const uint8_t*>(converted.data()), total_input_frames, ctx->frame_size);
            }
        }
    } else {
        // ─── STEREO SOURCE (2 Channels -> 2 Channels Pass-through) ──────────────
        int total_samples = total_input_frames * 2;
        if (ctx->src_encoding == 2) {
            // Source: Int16 Stereo
            const int16_t* s16 = reinterpret_cast<const int16_t*>(src_bytes);
            if (ctx->bytes_per_sample == 4) {
                // DAC: 32-bit Stereo
                std::vector<int32_t> converted(total_samples);
                for (int i = 0; i < total_samples; i++) {
                    converted[i] = static_cast<int32_t>(s16[i]) << 16;
                }
                frames_written = ring_write_frames(reinterpret_cast<const uint8_t*>(converted.data()), total_input_frames, ctx->frame_size);
            } else if (ctx->bytes_per_sample == 3) {
                // DAC: 24-bit Stereo
                std::vector<uint8_t> converted(total_samples * 3);
                for (int i = 0; i < total_samples; i++) {
                    int32_t val = static_cast<int32_t>(s16[i]) << 8;
                    converted[i * 3 + 0] = static_cast<uint8_t>(val & 0xFF);
                    converted[i * 3 + 1] = static_cast<uint8_t>((val >> 8) & 0xFF);
                    converted[i * 3 + 2] = static_cast<uint8_t>((val >> 16) & 0xFF);
                }
                frames_written = ring_write_frames(converted.data(), total_input_frames, ctx->frame_size);
            } else {
                // DAC: 16-bit Stereo
                frames_written = ring_write_frames(src_bytes, total_input_frames, ctx->frame_size);
            }
        } else {
            // Source: Float32 Stereo
            const float* src_float = reinterpret_cast<const float*>(src_bytes);
            if (ctx->bytes_per_sample == 4) {
                // DAC: 32-bit Stereo
                std::vector<int32_t> converted(total_samples);
                for (int i = 0; i < total_samples; i++) {
                    float f = src_float[i];
                    if (f > 1.0f)  f = 1.0f;
                    if (f < -1.0f) f = -1.0f;
                    converted[i] = static_cast<int32_t>(f * 2147483647.0f);
                }
                frames_written = ring_write_frames(reinterpret_cast<const uint8_t*>(converted.data()), total_input_frames, ctx->frame_size);
            } else if (ctx->bytes_per_sample == 3) {
                // DAC: 24-bit Stereo
                std::vector<uint8_t> converted(total_samples * 3);
                for (int i = 0; i < total_samples; i++) {
                    float f = src_float[i];
                    if (f > 1.0f)  f = 1.0f;
                    if (f < -1.0f) f = -1.0f;
                    int32_t val = static_cast<int32_t>(f * 8388607.0f);
                    converted[i * 3 + 0] = static_cast<uint8_t>(val & 0xFF);
                    converted[i * 3 + 1] = static_cast<uint8_t>((val >> 8) & 0xFF);
                    converted[i * 3 + 2] = static_cast<uint8_t>((val >> 16) & 0xFF);
                }
                frames_written = ring_write_frames(converted.data(), total_input_frames, ctx->frame_size);
            } else {
                // DAC: 16-bit Stereo
                std::vector<int16_t> converted(total_samples);
                for (int i = 0; i < total_samples; i++) {
                    float f = src_float[i];
                    if (f > 1.0f)  f = 1.0f;
                    if (f < -1.0f) f = -1.0f;
                    converted[i] = static_cast<int32_t>(f * 32767.0f);
                }
                frames_written = ring_write_frames(reinterpret_cast<const uint8_t*>(converted.data()), total_input_frames, ctx->frame_size);
            }
        }
    }

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
        ctx->volume.store(sw_gain, std::memory_order_relaxed);
        ctx->hw_volume_enabled.store(false, std::memory_order_relaxed);
        // Reset DAC hardware to 0 dB — software gain in fill_urb takes over immediately
        set_hardware_mute(ctx->fd, JM6PRO2_FU_ID, JM6PRO2_AC_IFACE, 0);
        set_hardware_volume(ctx->fd, JM6PRO2_FU_ID, JM6PRO2_AC_IFACE, 0x0000);
        LOGI("nativeSetHwVolume: HW mode OFF — DAC reset to 0dB, SW gain active (%.6f)", sw_gain);
    }
}

JNIEXPORT void JNICALL
Java_com_eddyizm_tempus_audio_usb_UsbExclusiveOutput_nativeStop(
        JNIEnv*, jclass, jlong h) {
    auto* ctx = reinterpret_cast<UsbAudioCtx*>(h);
    if (!ctx) return;
    set_hardware_mute(ctx->fd, JM6PRO2_FU_ID, JM6PRO2_AC_IFACE, 1);
    if (ctx->running.exchange(false)) {
        pthread_join(ctx->stream_thread, nullptr);
    }
}

JNIEXPORT void JNICALL
Java_com_eddyizm_tempus_audio_usb_UsbExclusiveOutput_nativeClose(
        JNIEnv*, jclass, jlong h) {
    auto* ctx = reinterpret_cast<UsbAudioCtx*>(h);
    if (!ctx) return;

    if (ctx->running.exchange(false)) {
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

} // extern "C"
