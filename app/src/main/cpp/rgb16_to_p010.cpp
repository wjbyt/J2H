// Convert an RGBA_F16 Android Bitmap to a P010 (10-bit YUV 4:2:0) byte buffer
// suitable for MediaCodec HEVC Main10 input.
//
// P010 layout:
//   - Y plane:  W*H samples, 2 bytes each, 10-bit value in upper bits of 16-bit
//   - UV plane: (W/2)*(H/2) chroma positions, each 2 bytes U then 2 bytes V,
//               interleaved (NV12-like), 10-bit value in upper bits.
//
// Color conversion uses BT.2020 non-constant-luminance (NCL) limited range —
// matches the colr/nclx box (primaries=9, matrix=9) we emit. ImageDecoder
// upstream is asked to deliver pixels already in BT.2020 primaries, so the
// YUV math here is straight BT.2020 luma weights.

#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <cstdint>
#include <cstring>
#include <algorithm>

#define LOG_TAG "j2h-10b"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

inline float half_to_float(uint16_t h) {
    uint32_t sign = (h >> 15) & 0x1;
    uint32_t exp  = (h >> 10) & 0x1F;
    uint32_t mantissa = h & 0x3FF;
    uint32_t f;
    if (exp == 0) {
        if (mantissa == 0) {
            f = sign << 31;
        } else {
            int e = -14;
            while (!(mantissa & 0x400)) { mantissa <<= 1; e--; }
            mantissa &= 0x3FF;
            f = (sign << 31) | (uint32_t)((e + 127) << 23) | (mantissa << 13);
        }
    } else if (exp == 31) {
        f = (sign << 31) | 0x7F800000 | (mantissa << 13);
    } else {
        f = (sign << 31) | ((exp + 112) << 23) | (mantissa << 13);
    }
    float r;
    std::memcpy(&r, &f, 4);
    return r;
}

inline int clamp10(int v) {
    return v < 0 ? 0 : (v > 1023 ? 1023 : v);
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_wjbyt_j2h_heif_TenBitEncoder_nativeRgbaF16ToP010(
        JNIEnv* env, jclass /*clazz*/,
        jobject jBitmap,
        jbyteArray jOutBuffer, jint yPlaneRowStrideBytes, jint uvPlaneRowStrideBytes,
        jint uvPlaneOffsetInBuffer)
{
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, jBitmap, &info) < 0)
        return env->NewStringUTF("getInfo failed");
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_F16)
        return env->NewStringUTF("bitmap must be RGBA_F16");

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, jBitmap, &pixels) < 0 || pixels == nullptr)
        return env->NewStringUTF("lockPixels failed");

    const int w = (int)info.width;
    const int h = (int)info.height;
    const int srcStride = (int)info.stride; // bytes per src row

    if ((w & 1) || (h & 1)) {
        AndroidBitmap_unlockPixels(env, jBitmap);
        return env->NewStringUTF("dimensions must be even (4:2:0 subsampling)");
    }

    jsize outLen = env->GetArrayLength(jOutBuffer);
    if (outLen < uvPlaneOffsetInBuffer + uvPlaneRowStrideBytes * (h / 2)) {
        AndroidBitmap_unlockPixels(env, jBitmap);
        return env->NewStringUTF("output buffer too small");
    }

    jbyte* outBuf = env->GetByteArrayElements(jOutBuffer, nullptr);
    auto* yPlane  = reinterpret_cast<uint8_t*>(outBuf);
    auto* uvPlane = reinterpret_cast<uint8_t*>(outBuf) + uvPlaneOffsetInBuffer;
    const auto* src = static_cast<const uint8_t*>(pixels);

    // Process pixel-pairs: for each 2x2 block compute 4 Y values and one averaged UV.
    for (int y = 0; y < h; y += 2) {
        const auto* row0 = reinterpret_cast<const uint16_t*>(src + (size_t)y * srcStride);
        const auto* row1 = reinterpret_cast<const uint16_t*>(src + (size_t)(y + 1) * srcStride);
        auto* y0 = reinterpret_cast<uint16_t*>(yPlane + (size_t)y * yPlaneRowStrideBytes);
        auto* y1 = reinterpret_cast<uint16_t*>(yPlane + (size_t)(y + 1) * yPlaneRowStrideBytes);
        auto* uvRow = reinterpret_cast<uint16_t*>(uvPlane + (size_t)(y / 2) * uvPlaneRowStrideBytes);

        for (int x = 0; x < w; x += 2) {
            float sumCb = 0.f, sumCr = 0.f;
            for (int dy = 0; dy < 2; ++dy) {
                const uint16_t* r = (dy == 0) ? row0 : row1;
                uint16_t* yo     = (dy == 0) ? y0 : y1;
                for (int dx = 0; dx < 2; ++dx) {
                    int xi = x + dx;
                    float R = half_to_float(r[xi * 4 + 0]);
                    float G = half_to_float(r[xi * 4 + 1]);
                    float B = half_to_float(r[xi * 4 + 2]);
                    if (R < 0.f) R = 0.f; else if (R > 1.f) R = 1.f;
                    if (G < 0.f) G = 0.f; else if (G > 1.f) G = 1.f;
                    if (B < 0.f) B = 0.f; else if (B > 1.f) B = 1.f;

                    // BT.2020 NCL luma; chroma in -0.5..+0.5 around 0.
                    float Y  = 0.2627f * R + 0.6780f * G + 0.0593f * B;
                    float Cb = (B - Y) / 1.8814f;
                    float Cr = (R - Y) / 1.4746f;

                    // Limited range 10-bit: Y 64..940, C 64..960.
                    int Y10 = (int)std::lround(Y * 876.0f + 64.0f);
                    yo[xi] = (uint16_t)(clamp10(Y10) << 6);

                    sumCb += Cb;
                    sumCr += Cr;
                }
            }
            // Average chroma over the 2x2 block, then quantize.
            int Cb10 = (int)std::lround((sumCb * 0.25f) * 896.0f + 512.0f);
            int Cr10 = (int)std::lround((sumCr * 0.25f) * 896.0f + 512.0f);
            int chromaIdx = x; // U at even offset, V at odd offset within row of length w
            uvRow[chromaIdx + 0] = (uint16_t)(clamp10(Cb10) << 6);
            uvRow[chromaIdx + 1] = (uint16_t)(clamp10(Cr10) << 6);
        }
    }

    AndroidBitmap_unlockPixels(env, jBitmap);
    env->ReleaseByteArrayElements(jOutBuffer, outBuf, 0);
    return nullptr; // success
}
