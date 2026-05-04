// Convert an RGBA_F16 Android Bitmap to a P010 (10-bit YUV 4:2:0) byte buffer
// suitable for MediaCodec HEVC Main10 input.
//
// P010 layout:
//   - Y plane:  W*H samples, 2 bytes each, 10-bit value in upper bits of 16-bit
//   - UV plane: (W/2)*(H/2) chroma positions, each 2 bytes U then 2 bytes V,
//               interleaved (NV12-like), 10-bit value in upper bits.
//
// Color conversion uses BT.709 limited range — matches matrix=1 in the colr
// nclx box. The pixel primaries are Display P3 (delivered by ImageDecoder),
// but the YUV math itself uses BT.709 luma weights since that's the standard
// matrix paired with Display P3 SDR HEIC.

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

/**
 * Convert a (possibly partial) tile region of the source bitmap into a P010
 * buffer of size tileW × tileH. Tile region is [srcX..srcX+tileW) × [srcY..srcY+tileH);
 * pixels outside the bitmap bounds are clamped to the nearest valid pixel
 * (edge replication) — used when the tiled grid extends past the image edge.
 *
 * Pass srcX=0, srcY=0, tileW=width, tileH=height to convert the whole bitmap.
 */
extern "C" JNIEXPORT jstring JNICALL
Java_com_wjbyt_j2h_heif_TenBitEncoder_nativeRgbaF16ToP010(
        JNIEnv* env, jclass /*clazz*/,
        jobject jBitmap,
        jbyteArray jOutBuffer, jint yPlaneRowStrideBytes, jint uvPlaneRowStrideBytes,
        jint uvPlaneOffsetInBuffer,
        jint srcX, jint srcY, jint tileW, jint tileH)
{
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, jBitmap, &info) < 0)
        return env->NewStringUTF("getInfo failed");
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_F16)
        return env->NewStringUTF("bitmap must be RGBA_F16");
    if ((tileW & 1) || (tileH & 1))
        return env->NewStringUTF("tile dims must be even");
    if (tileW <= 0 || tileH <= 0)
        return env->NewStringUTF("tile dims must be positive");

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, jBitmap, &pixels) < 0 || pixels == nullptr)
        return env->NewStringUTF("lockPixels failed");

    const int srcW = (int)info.width;
    const int srcH = (int)info.height;
    const int srcStride = (int)info.stride;

    auto clampX = [srcW](int x) { return x < 0 ? 0 : (x >= srcW ? srcW - 1 : x); };
    auto clampY = [srcH](int y) { return y < 0 ? 0 : (y >= srcH ? srcH - 1 : y); };

    jbyte* outBuf = env->GetByteArrayElements(jOutBuffer, nullptr);
    auto* yPlane  = reinterpret_cast<uint8_t*>(outBuf);
    auto* uvPlane = reinterpret_cast<uint8_t*>(outBuf) + uvPlaneOffsetInBuffer;
    const auto* src = static_cast<const uint8_t*>(pixels);

    auto readRGB = [&](int absX, int absY, float& R, float& G, float& B) {
        int sx = clampX(srcX + absX);
        int sy = clampY(srcY + absY);
        const uint16_t* row = reinterpret_cast<const uint16_t*>(src + (size_t)sy * srcStride);
        R = half_to_float(row[sx * 4 + 0]);
        G = half_to_float(row[sx * 4 + 1]);
        B = half_to_float(row[sx * 4 + 2]);
        if (R < 0.f) R = 0.f; else if (R > 1.f) R = 1.f;
        if (G < 0.f) G = 0.f; else if (G > 1.f) G = 1.f;
        if (B < 0.f) B = 0.f; else if (B > 1.f) B = 1.f;
    };

    for (int y = 0; y < tileH; y += 2) {
        auto* y0 = reinterpret_cast<uint16_t*>(yPlane + (size_t)y * yPlaneRowStrideBytes);
        auto* y1 = reinterpret_cast<uint16_t*>(yPlane + (size_t)(y + 1) * yPlaneRowStrideBytes);
        auto* uvRow = reinterpret_cast<uint16_t*>(uvPlane + (size_t)(y / 2) * uvPlaneRowStrideBytes);

        for (int x = 0; x < tileW; x += 2) {
            float sumCb = 0.f, sumCr = 0.f;
            for (int dy = 0; dy < 2; ++dy) {
                uint16_t* yo = (dy == 0) ? y0 : y1;
                for (int dx = 0; dx < 2; ++dx) {
                    float R, G, B;
                    readRGB(x + dx, y + dy, R, G, B);
                    float Y  = 0.2126f * R + 0.7152f * G + 0.0722f * B;
                    float Cb = (B - Y) / 1.8556f;
                    float Cr = (R - Y) / 1.5748f;
                    int Y10 = (int)std::lround(Y * 876.0f + 64.0f);
                    yo[x + dx] = (uint16_t)(clamp10(Y10) << 6);
                    sumCb += Cb; sumCr += Cr;
                }
            }
            int Cb10 = (int)std::lround((sumCb * 0.25f) * 896.0f + 512.0f);
            int Cr10 = (int)std::lround((sumCr * 0.25f) * 896.0f + 512.0f);
            uvRow[x + 0] = (uint16_t)(clamp10(Cb10) << 6);
            uvRow[x + 1] = (uint16_t)(clamp10(Cr10) << 6);
        }
    }

    AndroidBitmap_unlockPixels(env, jBitmap);
    env->ReleaseByteArrayElements(jOutBuffer, outBuf, 0);
    return nullptr;
}
