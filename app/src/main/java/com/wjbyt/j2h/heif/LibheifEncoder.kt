package com.wjbyt.j2h.heif

import android.graphics.Bitmap

/**
 * Encodes Bitmap → HEIC via libheif (with x265). Unlike Android's HeifWriter,
 * libheif's container output is structured in a way that AOSP MediaMetadataRetriever,
 * AndroidX ExifInterface, and vendor gallery apps can read embedded EXIF from —
 * even for tile-encoded large images.
 */
object LibheifEncoder {

    private var loaded = false
    private var loadError: String? = null

    init {
        try {
            System.loadLibrary("j2h_heif")
            // Probe a real symbol — the stub library loads but doesn't define
            // nativeVersion, so this throws UnsatisfiedLinkError on stub builds.
            val v = nativeVersion()
            loaded = v.isNotEmpty()
            if (!loaded) loadError = "nativeVersion returned empty"
        } catch (t: UnsatisfiedLinkError) {
            loadError = "stub or missing symbol: ${t.message}"
        } catch (t: Throwable) {
            loadError = t.message ?: t.javaClass.simpleName
        }
    }

    fun isAvailable(): Boolean = loaded

    fun loadError(): String? = loadError

    /**
     * Encode a bitmap to a HEIC file at [outputPath].
     *
     * @param bitmap     ARGB_8888-format bitmap. Will be converted to RGBA inside libheif.
     * @param exifTiff   Raw EXIF data — either bare TIFF (II*\0… / MM\0*…) or
     *                   "Exif\0\0"-prefixed APP1 payload. libheif handles both.
     *                   Pass null to skip EXIF embedding.
     * @param outputPath File path the encoder writes to. Parent directory must exist.
     * @param quality    0–100 lossy quality (higher = larger / better).
     * @return Null on success, or an error description on failure.
     */
    fun encode(bitmap: Bitmap, exifTiff: ByteArray?, outputPath: String, quality: Int): String? {
        if (!loaded) return "libheif not loaded: $loadError"
        // libheif accepts both ARGB_8888 and RGBA_8888 — Android stores ARGB_8888 as
        // R,G,B,A bytes in memory, which IS RGBA, so the native side can interpret
        // it as RGBA directly. (No swizzle needed; both are 4-byte-per-pixel R,G,B,A.)
        if (bitmap.config != Bitmap.Config.ARGB_8888) {
            return "bitmap config must be ARGB_8888 (got ${bitmap.config})"
        }
        return nativeEncode(bitmap, exifTiff, outputPath, quality.coerceIn(1, 100))
    }

    fun version(): String? = if (loaded) try { nativeVersion() } catch (_: Throwable) { null } else null

    @JvmStatic private external fun nativeEncode(
        bitmap: Bitmap, exifTiff: ByteArray?, outputPath: String, quality: Int
    ): String?
    @JvmStatic private external fun nativeVersion(): String
}
