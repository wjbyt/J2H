// JNI bridge: encode an Android Bitmap (RGBA_8888) to HEIC via libheif,
// optionally embedding EXIF metadata. Output is written to a file path.
//
// libheif handles the HEIF container details and EXIF item structure correctly
// (including for tile-based encodes from the underlying x265 encoder), so unlike
// Android's HeifWriter the resulting file's EXIF is readable by AOSP MMR /
// AndroidX ExifInterface / vendor gallery apps.

#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <cstring>

#include "libheif/heif.h"

#define LOG_TAG "j2h-heif"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)

namespace {

struct Cleanup {
    heif_context*       ctx     = nullptr;
    heif_encoder*       enc     = nullptr;
    heif_image*         img     = nullptr;
    heif_image_handle*  handle  = nullptr;
    ~Cleanup() {
        if (handle) heif_image_handle_release(handle);
        if (img)    heif_image_release(img);
        if (enc)    heif_encoder_release(enc);
        if (ctx)    heif_context_free(ctx);
    }
};

// Build a Java string describing a heif_error, or empty if no error.
jstring heifErrorToJava(JNIEnv* env, const heif_error& e) {
    if (e.code == heif_error_Ok) return nullptr;
    char buf[512];
    snprintf(buf, sizeof(buf), "heif error %d.%d: %s",
             (int)e.code, (int)e.subcode, e.message ? e.message : "(null)");
    return env->NewStringUTF(buf);
}

} // namespace

extern "C"
JNIEXPORT jstring JNICALL
Java_com_wjbyt_j2h_heif_LibheifEncoder_nativeEncode(
        JNIEnv* env, jclass /*clazz*/,
        jobject jBitmap,
        jbyteArray jExifTiff,    // raw TIFF data (or "Exif\0\0"+TIFF) or null
        jstring jOutputPath,
        jint jQuality)
{
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, jBitmap, &info) < 0) {
        return env->NewStringUTF("AndroidBitmap_getInfo failed");
    }
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        return env->NewStringUTF("bitmap must be RGBA_8888");
    }
    if (info.width <= 0 || info.height <= 0) {
        return env->NewStringUTF("bitmap has invalid size");
    }

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, jBitmap, &pixels) < 0 || pixels == nullptr) {
        return env->NewStringUTF("AndroidBitmap_lockPixels failed");
    }

    Cleanup g;
    g.ctx = heif_context_alloc();
    if (!g.ctx) {
        AndroidBitmap_unlockPixels(env, jBitmap);
        return env->NewStringUTF("heif_context_alloc failed");
    }

    {
        heif_error err = heif_context_get_encoder_for_format(g.ctx, heif_compression_HEVC, &g.enc);
        if (err.code != heif_error_Ok) {
            AndroidBitmap_unlockPixels(env, jBitmap);
            return heifErrorToJava(env, err);
        }
    }

    {
        heif_error err = heif_encoder_set_lossy_quality(g.enc, jQuality);
        if (err.code != heif_error_Ok) {
            AndroidBitmap_unlockPixels(env, jBitmap);
            return heifErrorToJava(env, err);
        }
    }

    // Encode as RGB (3 channels, no alpha) — JPG sources have no alpha and
    // libheif would otherwise emit a separate auxiliary alpha image item, which
    // some HEIF parsers (notably AOSP MediaMetadataRetriever and the vivo gallery)
    // appear to choke on, failing to surface EXIF.
    {
        heif_error err = heif_image_create(
                (int)info.width, (int)info.height,
                heif_colorspace_RGB, heif_chroma_interleaved_RGB,
                &g.img);
        if (err.code != heif_error_Ok) {
            AndroidBitmap_unlockPixels(env, jBitmap);
            return heifErrorToJava(env, err);
        }
    }

    {
        heif_error err = heif_image_add_plane(
                g.img, heif_channel_interleaved,
                (int)info.width, (int)info.height, 8);
        if (err.code != heif_error_Ok) {
            AndroidBitmap_unlockPixels(env, jBitmap);
            return heifErrorToJava(env, err);
        }
    }

    int dstStride = 0;
    uint8_t* dst = heif_image_get_plane(g.img, heif_channel_interleaved, &dstStride);
    if (!dst) {
        AndroidBitmap_unlockPixels(env, jBitmap);
        return env->NewStringUTF("heif_image_get_plane returned null");
    }

    // Bitmap is ARGB_8888 in memory order R,G,B,A (Android quirk). Drop alpha as
    // we copy: pack 3 bytes per pixel into the libheif RGB plane.
    const uint8_t* src = static_cast<const uint8_t*>(pixels);
    const uint32_t srcStride = info.stride;
    const uint32_t w = info.width;
    for (uint32_t y = 0; y < info.height; ++y) {
        const uint8_t* sRow = src + (size_t)y * srcStride;
        uint8_t* dRow = dst + (size_t)y * dstStride;
        for (uint32_t x = 0; x < w; ++x) {
            dRow[x * 3 + 0] = sRow[x * 4 + 0]; // R
            dRow[x * 3 + 1] = sRow[x * 4 + 1]; // G
            dRow[x * 3 + 2] = sRow[x * 4 + 2]; // B
        }
    }
    AndroidBitmap_unlockPixels(env, jBitmap);

    {
        heif_error err = heif_context_encode_image(g.ctx, g.img, g.enc, nullptr, &g.handle);
        if (err.code != heif_error_Ok) return heifErrorToJava(env, err);
    }

    if (jExifTiff != nullptr) {
        jsize n = env->GetArrayLength(jExifTiff);
        if (n > 0) {
            std::vector<uint8_t> buf(n);
            env->GetByteArrayRegion(jExifTiff, 0, n, reinterpret_cast<jbyte*>(buf.data()));
            heif_error err = heif_context_add_exif_metadata(g.ctx, g.handle, buf.data(), (int)buf.size());
            if (err.code != heif_error_Ok) return heifErrorToJava(env, err);
        }
    }

    const char* outPath = env->GetStringUTFChars(jOutputPath, nullptr);
    if (!outPath) return env->NewStringUTF("GetStringUTFChars(outputPath) failed");
    heif_error werr = heif_context_write_to_file(g.ctx, outPath);
    env->ReleaseStringUTFChars(jOutputPath, outPath);
    if (werr.code != heif_error_Ok) return heifErrorToJava(env, werr);

    return nullptr; // null = success
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_wjbyt_j2h_heif_LibheifEncoder_nativeVersion(JNIEnv* env, jclass /*clazz*/) {
    return env->NewStringUTF(heif_get_version());
}
