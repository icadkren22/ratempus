#include <jni.h>
#include <android/log.h>
#include <elf.h>
#include <link.h>
#include <string.h>
#include <stdint.h>
#include <stdlib.h>
#include <vector>

#define TAG "DirectAudioNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

typedef int32_t audio_stream_type_t;
typedef uint32_t audio_format_t;
typedef uint32_t audio_channel_mask_t;
typedef uint32_t audio_output_flags_t;
typedef int32_t audio_session_t;
typedef int32_t transfer_type_t;

enum {
    AUDIO_STREAM_MUSIC        = 3,
    AUDIO_FORMAT_PCM_16_BIT   = 0x1u,
    AUDIO_FORMAT_PCM_32_BIT   = 0x3u,
    AUDIO_CHANNEL_OUT_MONO    = 0x1u,
    AUDIO_CHANNEL_OUT_STEREO  = 0x3u,
    AUDIO_OUTPUT_FLAG_DIRECT  = 0x1u,
    AUDIO_SESSION_ALLOCATE    = 0,
    TRANSFER_SYNC             = 3
};

struct AttributionSourceState { uint8_t dummy[256]; };
struct wp_callback { void* m_ptr; void* m_refs; };
struct sp_memory   { void* m_ptr; };

typedef void     (*AudioTrack_ctor_t)(void*, const AttributionSourceState&);
typedef void     (*AudioTrack_dtor_t)(void*);
typedef int32_t  (*AudioTrack_set_t)(void*, audio_stream_type_t, uint32_t, audio_format_t,
                    audio_channel_mask_t, size_t, audio_output_flags_t,
                    const wp_callback&, int32_t, const sp_memory&, bool,
                    audio_session_t, transfer_type_t, const void*,
                    const AttributionSourceState&, const void*, bool, float, int32_t);
typedef int32_t  (*AudioTrack_start_t)(void*);
typedef void     (*AudioTrack_stop_t)(void*);
typedef void     (*AudioTrack_pause_t)(void*);
typedef void     (*AudioTrack_flush_t)(void*);
typedef ssize_t  (*AudioTrack_write_t)(void*, const void*, size_t, bool);
typedef int32_t  (*AudioTrack_getPosition_t)(void*, uint32_t*);

static AudioTrack_ctor_t        s_ctor        = nullptr;
static AudioTrack_dtor_t        s_dtor        = nullptr;
static AudioTrack_set_t         s_set         = nullptr;
static AudioTrack_start_t       s_start       = nullptr;
static AudioTrack_stop_t        s_stop        = nullptr;
static AudioTrack_pause_t       s_pause       = nullptr;
static AudioTrack_flush_t       s_flush       = nullptr;
static AudioTrack_write_t       s_write       = nullptr;
static AudioTrack_getPosition_t s_getPosition = nullptr;

static inline uintptr_t resolve_addr(uintptr_t base, uintptr_t addr) {
    if (addr == 0) return 0;
    if (addr >= base) return addr;
    return base + addr;
}

struct FindLibData {
    const char*      targetLib;
    uintptr_t        baseAddr;
    const Elf64_Sym* symTab;
    const char*      strTab;
    size_t           symCount;
};

static int phdr_callback(struct dl_phdr_info* info, size_t, void* data) {
    auto* fld = reinterpret_cast<FindLibData*>(data);
    if (!info->dlpi_name || !strstr(info->dlpi_name, fld->targetLib)) return 0;

    uintptr_t base = (uintptr_t)info->dlpi_addr;
    fld->baseAddr = base;

    for (int i = 0; i < info->dlpi_phnum; i++) {
        const Elf64_Phdr* ph = &info->dlpi_phdr[i];
        if (ph->p_type != PT_DYNAMIC) continue;

        const Elf64_Dyn* dyn = (const Elf64_Dyn*)resolve_addr(base, ph->p_vaddr);
        uintptr_t symtabAddr = 0, strtabAddr = 0, gnuHashAddr = 0;
        size_t strsz = 0;

        for (; dyn->d_tag != DT_NULL; dyn++) {
            switch (dyn->d_tag) {
                case DT_SYMTAB:   symtabAddr  = (uintptr_t)dyn->d_un.d_ptr; break;
                case DT_STRTAB:   strtabAddr  = (uintptr_t)dyn->d_un.d_ptr; break;
                case DT_STRSZ:    strsz       = dyn->d_un.d_val;             break;
                case DT_GNU_HASH: gnuHashAddr = (uintptr_t)dyn->d_un.d_ptr;  break;
            }
        }

        if (!symtabAddr || !strtabAddr) {
            LOGE("symtab or strtab missing in DYNAMIC section");
            break;
        }

        const Elf64_Sym* symtab = (const Elf64_Sym*)resolve_addr(base, symtabAddr);
        const char*      strtab = (const char*)     resolve_addr(base, strtabAddr);

        size_t symCount = 0;
        if (gnuHashAddr) {
            const uint32_t* gh       = (const uint32_t*)resolve_addr(base, gnuHashAddr);
            uint32_t nbuckets        = gh[0];
            uint32_t symoffset       = gh[1];
            uint32_t bloomsz         = gh[2];
            const uint32_t* buckets  = gh + 4 + (bloomsz * 64 / 32);
            uint32_t maxsym          = symoffset;
            for (uint32_t b = 0; b < nbuckets; b++)
                if (buckets[b] > maxsym) maxsym = buckets[b];
            if (maxsym >= symoffset) {
                const uint32_t* chains = buckets + nbuckets;
                uint32_t idx = maxsym - symoffset;
                while (idx < 65536 && !(chains[idx] & 1)) idx++;
                symCount = (size_t)(maxsym - symoffset + idx + 2);
            }
        }
        if (symCount == 0) symCount = strsz / 16;

        fld->symTab   = symtab;
        fld->strTab   = strtab;
        fld->symCount = symCount;
        break;
    }
    return 1;
}

static void* findSymbol(const FindLibData& fld, const char* name) {
    if (!fld.symTab || !fld.strTab) return nullptr;
    for (size_t i = 0; i < fld.symCount; i++) {
        const Elf64_Sym& sym = fld.symTab[i];
        if (ELF64_ST_TYPE(sym.st_info) != STT_FUNC || sym.st_value == 0) continue;
        if (strcmp(fld.strTab + sym.st_name, name) == 0)
            return (void*)resolve_addr(fld.baseAddr, sym.st_value);
    }
    return nullptr;
}

static bool loadSymbols() {
    if (s_ctor && s_set && s_start && s_write) return true;

    FindLibData fld = {};
    fld.targetLib = "libaudioclient.so";
    dl_iterate_phdr(phdr_callback, &fld);

    if (!fld.baseAddr || !fld.symTab) {
        LOGE("libaudioclient.so not found via dl_iterate_phdr");
        return false;
    }

    s_ctor        = (AudioTrack_ctor_t)       findSymbol(fld, "_ZN7android10AudioTrackC1ERKNS_7content22AttributionSourceStateE");
    s_dtor        = (AudioTrack_dtor_t)       findSymbol(fld, "_ZN7android10AudioTrackD1Ev");
    s_set         = (AudioTrack_set_t)        findSymbol(fld, "_ZN7android10AudioTrack3setE19audio_stream_type_tj14audio_format_t20audio_channel_mask_tm20audio_output_flags_tRKNS_2wpINS0_19IAudioTrackCallbackEEEiRKNS_2spINS_7IMemoryEEEb15audio_session_tNS0_13transfer_typeEPK20audio_offload_info_tRKNS_7content22AttributionSourceStateEPK18audio_attributes_tbfi");
    s_start       = (AudioTrack_start_t)      findSymbol(fld, "_ZN7android10AudioTrack5startEv");
    s_stop        = (AudioTrack_stop_t)       findSymbol(fld, "_ZN7android10AudioTrack4stopEv");
    s_pause       = (AudioTrack_pause_t)      findSymbol(fld, "_ZN7android10AudioTrack5pauseEv");
    s_flush       = (AudioTrack_flush_t)      findSymbol(fld, "_ZN7android10AudioTrack5flushEv");
    s_write       = (AudioTrack_write_t)      findSymbol(fld, "_ZN7android10AudioTrack5writeEPKvmb");
    s_getPosition = (AudioTrack_getPosition_t)findSymbol(fld, "_ZN7android10AudioTrack11getPositionEPj");

    if (!s_ctor || !s_set || !s_start || !s_write) {
        LOGE("ELF walk: missing required symbols. ctor=%p set=%p start=%p write=%p",
             s_ctor, s_set, s_start, s_write);
        return false;
    }

    LOGI("Direct HD Audio symbols resolved via ELF walk");
    return true;
}

struct DirectAudioContext {
    void* mem = nullptr;
    uint32_t sampleRate = 0;
    int32_t inputEncoding = 0; // Android AudioFormat: 2=PCM_16, 4=FLOAT, 22=PCM_32
    audio_format_t outputFormat = AUDIO_FORMAT_PCM_32_BIT;
    std::vector<int32_t> convBuf;
};

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_cappielloantonio_tempo_audio_NativeDirectAudioTrack_nativeLoadSymbols(JNIEnv*, jclass) {
    bool ok = loadSymbols();
    LOGI("nativeLoadSymbols: %s", ok ? "SUCCESS" : "FAILED");
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_com_cappielloantonio_tempo_audio_NativeDirectAudioTrack_nativeCreate(
        JNIEnv*, jclass,
        jint sampleRate, jint channelCount, jint encoding, jint) {

    if (!s_ctor || !s_set) {
        LOGE("Symbols not loaded — call nativeLoadSymbols first");
        return 0;
    }

    auto* ctx = new DirectAudioContext();
    ctx->mem = calloc(1, 4096);
    ctx->sampleRate = (uint32_t)sampleRate;
    ctx->inputEncoding = encoding;
    ctx->outputFormat = (encoding == 2) ? AUDIO_FORMAT_PCM_16_BIT : AUDIO_FORMAT_PCM_32_BIT;

    AttributionSourceState attr; memset(&attr, 0, sizeof(attr));
    s_ctor(ctx->mem, attr);

    wp_callback cb;  memset(&cb,  0, sizeof(cb));
    sp_memory   shm; memset(&shm, 0, sizeof(shm));

    audio_channel_mask_t chMask = (channelCount == 1) ? AUDIO_CHANNEL_OUT_MONO : AUDIO_CHANNEL_OUT_STEREO;

    LOGI("Creating Direct AudioTrack sr=%u ch=%d enc=%d outFmt=0x%x flags=0x1",
         ctx->sampleRate, channelCount, encoding, ctx->outputFormat);

    int32_t status = s_set(ctx->mem, AUDIO_STREAM_MUSIC,
        ctx->sampleRate, ctx->outputFormat, chMask, 0,
        AUDIO_OUTPUT_FLAG_DIRECT, cb, 0, shm,
        false, AUDIO_SESSION_ALLOCATE, TRANSFER_SYNC,
        nullptr, attr, nullptr, false, 1.0f, 0);

    if (status != 0) {
        LOGW("set(%u Hz) failed=%d, retrying at 48kHz", ctx->sampleRate, status);
        ctx->sampleRate = 48000;
        s_dtor(ctx->mem);
        s_ctor(ctx->mem, attr);
        status = s_set(ctx->mem, AUDIO_STREAM_MUSIC,
            48000, AUDIO_FORMAT_PCM_32_BIT, chMask, 0,
            AUDIO_OUTPUT_FLAG_DIRECT, cb, 0, shm,
            false, AUDIO_SESSION_ALLOCATE, TRANSFER_SYNC,
            nullptr, attr, nullptr, false, 1.0f, 0);
    }

    if (status != 0) {
        LOGE("AudioTrack::set failed=%d", status);
        s_dtor(ctx->mem);
        free(ctx->mem);
        delete ctx;
        return 0;
    }

    LOGI("Direct AudioTrack created: sr=%u fmt=0x%x", ctx->sampleRate, ctx->outputFormat);
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT jboolean JNICALL
Java_com_cappielloantonio_tempo_audio_NativeDirectAudioTrack_nativeStart(JNIEnv*, jclass, jlong h) {
    auto* ctx = reinterpret_cast<DirectAudioContext*>(h);
    if (!ctx || !ctx->mem) return JNI_FALSE;
    return s_start(ctx->mem) == 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_cappielloantonio_tempo_audio_NativeDirectAudioTrack_nativePause(JNIEnv*, jclass, jlong h) {
    auto* ctx = reinterpret_cast<DirectAudioContext*>(h);
    if (!ctx || !ctx->mem) return JNI_FALSE;
    s_pause(ctx->mem);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_cappielloantonio_tempo_audio_NativeDirectAudioTrack_nativeFlush(JNIEnv*, jclass, jlong h) {
    auto* ctx = reinterpret_cast<DirectAudioContext*>(h);
    if (!ctx || !ctx->mem) return JNI_FALSE;
    s_flush(ctx->mem);
    return JNI_TRUE;
}


JNIEXPORT jboolean JNICALL
Java_com_cappielloantonio_tempo_audio_NativeDirectAudioTrack_nativeStop(JNIEnv*, jclass, jlong h) {
    auto* ctx = reinterpret_cast<DirectAudioContext*>(h);
    if (!ctx || !ctx->mem) return JNI_FALSE;
    s_stop(ctx->mem);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_cappielloantonio_tempo_audio_NativeDirectAudioTrack_nativeClose(JNIEnv*, jclass, jlong h) {
    auto* ctx = reinterpret_cast<DirectAudioContext*>(h);
    if (ctx) {
        if (ctx->mem) { s_stop(ctx->mem); s_dtor(ctx->mem); free(ctx->mem); ctx->mem = nullptr; }
        delete ctx;
    }
}

JNIEXPORT jint JNICALL
Java_com_cappielloantonio_tempo_audio_NativeDirectAudioTrack_nativeWrite(
        JNIEnv* env, jclass, jlong h,
        jobject byteBuffer, jint offset, jint sizeInBytes, jlong) {
    auto* ctx = reinterpret_cast<DirectAudioContext*>(h);
    if (!ctx || !ctx->mem) return -1;

    const uint8_t* srcPtr = nullptr;
    jbyteArray arr = nullptr;
    jbyte* elems = nullptr;

    void* direct = env->GetDirectBufferAddress(byteBuffer);
    if (direct) {
        srcPtr = static_cast<const uint8_t*>(direct) + offset;
    } else {
        jclass cls = env->GetObjectClass(byteBuffer);
        jmethodID mHas = env->GetMethodID(cls, "hasArray", "()Z");
        if (mHas && env->CallBooleanMethod(byteBuffer, mHas)) {
            jmethodID mArr = env->GetMethodID(cls, "array", "()[B");
            jmethodID mOff = env->GetMethodID(cls, "arrayOffset", "()I");
            arr = (jbyteArray)env->CallObjectMethod(byteBuffer, mArr);
            jint ao = env->CallIntMethod(byteBuffer, mOff);
            if (arr) {
                elems = env->GetByteArrayElements(arr, nullptr);
                if (elems) srcPtr = reinterpret_cast<const uint8_t*>(elems) + ao + offset;
            }
        }
    }
    if (!srcPtr) return -1;

    ssize_t written = 0;
    if (ctx->inputEncoding == 4 && ctx->outputFormat == AUDIO_FORMAT_PCM_32_BIT) {
        const int n = sizeInBytes / sizeof(float);
        ctx->convBuf.resize(n);
        const float* __restrict in = reinterpret_cast<const float*>(srcPtr);
        int32_t* __restrict out = ctx->convBuf.data();
        for (int i = 0; i < n; i++) {
            float f = in[i];
            if (f > 1.f) f = 1.f; else if (f < -1.f) f = -1.f;
            out[i] = (int32_t)(f * 2147483647.f);
        }
        ssize_t r = s_write(ctx->mem, out, n * sizeof(int32_t), true);
        written = r > 0 ? (r / sizeof(int32_t)) * sizeof(float) : r;
    } else {
        written = s_write(ctx->mem, srcPtr, sizeInBytes, true);
    }

    if (arr && elems) env->ReleaseByteArrayElements(arr, elems, JNI_ABORT);
    return (jint)written;
}

JNIEXPORT jlong JNICALL
Java_com_cappielloantonio_tempo_audio_NativeDirectAudioTrack_nativeGetPositionUs(JNIEnv*, jclass, jlong h) {
    auto* ctx = reinterpret_cast<DirectAudioContext*>(h);
    if (!ctx || !ctx->mem || ctx->sampleRate == 0) return 0;
    uint32_t pos = 0;
    if (s_getPosition(ctx->mem, &pos) == 0)
        return ((uint64_t)pos * 1000000ULL) / ctx->sampleRate;
    return 0;
}

JNIEXPORT jboolean JNICALL
Java_com_cappielloantonio_tempo_audio_NativeDirectAudioTrack_nativeIsExclusive(JNIEnv*, jclass, jlong) {
    return JNI_TRUE;
}

JNIEXPORT jint JNICALL
Java_com_cappielloantonio_tempo_audio_NativeDirectAudioTrack_nativeGetSampleRate(JNIEnv*, jclass, jlong h) {
    auto* ctx = reinterpret_cast<DirectAudioContext*>(h);
    return ctx ? (jint)ctx->sampleRate : 0;
}

} // extern "C"
