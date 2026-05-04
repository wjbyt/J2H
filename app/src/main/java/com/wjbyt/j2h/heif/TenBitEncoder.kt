package com.wjbyt.j2h.heif

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.nio.ByteBuffer

/**
 * Encodes a Bitmap (must be RGBA_F16) into a 10-bit HEIC at [outputPath]
 * using HEVC Main10 via MediaCodec (hardware) and MediaMuxer's HEIF muxer.
 *
 * Why this path instead of HeifWriter:
 *   - HeifWriter is hardcoded to HEVC Main profile (8-bit). For DNGs decoded
 *     to 16-bit half-float, we'd be throwing away the extra bit depth.
 *   - This path explicitly requests HEVCProfileMain10 + COLOR_FormatYUVP010
 *     and lets MediaMuxer.MUXER_OUTPUT_HEIF wrap the bitstream into a HEIF
 *     container — no hand-rolled box construction.
 */
object TenBitEncoder {

    private var loaded = false
    private var loadError: String? = null

    init {
        try {
            System.loadLibrary("j2h_10bit")
            loaded = true
        } catch (t: Throwable) {
            loadError = t.message ?: t.javaClass.simpleName
        }
    }

    fun isAvailable(): Boolean = loaded

    /** Returns null on success, or an error string. */
    fun encode(bitmap: Bitmap, outputPath: String, qualityHint: Int = 95): String? {
        if (!loaded) return "10bit native lib not loaded: $loadError"
        if (bitmap.config != Bitmap.Config.RGBA_F16) {
            return "bitmap must be RGBA_F16 (got ${bitmap.config})"
        }
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0 || (w and 1) != 0 || (h and 1) != 0) {
            return "dimensions must be positive and even (got ${w}x${h})"
        }

        // Configure HEVC Main10 encoder.
        val codecMime = MediaFormat.MIMETYPE_VIDEO_HEVC
        val format = MediaFormat.createVideoFormat(codecMime, w, h).apply {
            setInteger(MediaFormat.KEY_PROFILE,
                MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
            setInteger(MediaFormat.KEY_LEVEL,
                MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel62)
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010)
            // Bitrate target — generous so quality 95-equivalent.
            val pixels = w.toLong() * h
            val bitrate = (pixels * 8L).coerceAtMost(200_000_000L) // cap at 200 Mbps
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate.toInt())
            setInteger(MediaFormat.KEY_FRAME_RATE, 30) // dummy for still
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 0) // every frame keyframe
            setInteger(MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            // 10-bit color metadata
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        }

        val codec = try { MediaCodec.createEncoderByType(codecMime) }
            catch (e: Exception) { return "createEncoderByType: ${e.message}" }

        try {
            try {
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            } catch (e: Exception) {
                return "codec.configure: ${e.message}"
            }
            codec.start()

            // Feed input as a P010 buffer.
            val info = MediaCodec.BufferInfo()
            val muxer = try { MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_HEIF) }
                catch (e: Exception) { return "MediaMuxer create: ${e.message}" }
            var trackIndex = -1
            var muxerStarted = false

            try {
                feedInputP010(codec, bitmap, w, h)?.let { return it }

                // Drain output.
                val timeoutUs = 5_000_000L
                val deadline = System.nanoTime() + timeoutUs * 1_000L * 12 // up to ~minute
                while (true) {
                    if (System.nanoTime() > deadline) return "encode timeout"
                    val outIdx = codec.dequeueOutputBuffer(info, timeoutUs)
                    when {
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (muxerStarted) return "encoder produced format twice"
                            trackIndex = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                        outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> continue
                        outIdx >= 0 -> {
                            val outBuf = codec.getOutputBuffer(outIdx)
                            if (outBuf != null && info.size > 0 && muxerStarted &&
                                (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                outBuf.position(info.offset)
                                outBuf.limit(info.offset + info.size)
                                muxer.writeSampleData(trackIndex, outBuf, info)
                            }
                            codec.releaseOutputBuffer(outIdx, false)
                            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                        }
                    }
                }
                return null // success
            } finally {
                if (muxerStarted) {
                    try { muxer.stop() } catch (_: Exception) {}
                }
                try { muxer.release() } catch (_: Exception) {}
            }
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            try { codec.release() } catch (_: Exception) {}
        }
    }

    /** Allocate one input buffer, fill with P010 from the bitmap, queue with EOS. */
    private fun feedInputP010(codec: MediaCodec, bitmap: Bitmap, w: Int, h: Int): String? {
        val inputIdx = codec.dequeueInputBuffer(10_000_000L)
        if (inputIdx < 0) return "no input buffer"
        val inBuf: ByteBuffer = codec.getInputBuffer(inputIdx)
            ?: return "null input buffer"
        inBuf.clear()

        // Use Image API for layout-aware writes — strides may differ from naive WxH.
        val image = codec.getInputImage(inputIdx)
            ?: return runFallbackPackP010(codec, inputIdx, inBuf, bitmap, w, h)

        // P010 via Image: 3 planes (Y, U, V). U and V share the same UV interleaved
        // plane, exposed as two views with pixelStride=4 and offsets 0/2.
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val yRowStride = yPlane.rowStride
        val uRowStride = uPlane.rowStride
        // For interleaved UV, U and V buffers point to the same backing region;
        // U is at offset 0, V at offset 2. Both have rowStride == uRowStride.
        // We write the whole interleaved row through uBuf with absolute positions.

        // Need a backing byte array that encompasses both Y and UV planes contiguously
        // in memory. The Image API gives us views over codec-managed memory; we can
        // write into them directly via JNI.
        // Simpler approach: ask native to write into a single ByteArray, then copy
        // into the Image planes respecting strides.

        val yPlaneBytes = yRowStride * h
        val uvPlaneBytes = uRowStride * (h / 2)
        val tmp = ByteArray(yPlaneBytes + uvPlaneBytes)
        val err = nativeRgbaF16ToP010(bitmap, tmp, yRowStride, uRowStride, yPlaneBytes)
        if (err != null) return err

        yBuf.put(tmp, 0, yPlaneBytes)
        uBuf.put(tmp, yPlaneBytes, uvPlaneBytes)

        // The encoded sample size to queue equals the conceptual buffer size used
        // (codec ignores it for input but we pass plane size for safety).
        val totalBytes = yPlaneBytes + uvPlaneBytes
        codec.queueInputBuffer(
            inputIdx, 0, totalBytes, 0,
            MediaCodec.BUFFER_FLAG_END_OF_STREAM
        )
        return null
    }

    /** Fallback when getInputImage returns null: write to raw input buffer. */
    private fun runFallbackPackP010(
        codec: MediaCodec, inputIdx: Int, inBuf: ByteBuffer,
        bitmap: Bitmap, w: Int, h: Int
    ): String? {
        val yStride = w * 2
        val uvStride = w * 2
        val ySize = yStride * h
        val uvSize = uvStride * (h / 2)
        val tmp = ByteArray(ySize + uvSize)
        val err = nativeRgbaF16ToP010(bitmap, tmp, yStride, uvStride, ySize)
        if (err != null) return err
        inBuf.put(tmp, 0, tmp.size)
        codec.queueInputBuffer(
            inputIdx, 0, tmp.size, 0,
            MediaCodec.BUFFER_FLAG_END_OF_STREAM
        )
        return null
    }

    /**
     * Native side fills the [out] byte array with planar P010:
     *   - Y plane occupies [0, yPlaneRowStride * height) via row-padded writes.
     *   - UV plane (interleaved) occupies [uvPlaneOffsetInBuffer, ...) via
     *     uvPlaneRowStride-wide rows.
     */
    @JvmStatic private external fun nativeRgbaF16ToP010(
        bitmap: Bitmap, out: ByteArray,
        yPlaneRowStrideBytes: Int, uvPlaneRowStrideBytes: Int,
        uvPlaneOffsetInBuffer: Int
    ): String?
}
