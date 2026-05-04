package com.wjbyt.j2h.heif

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Encodes a Bitmap (RGBA_F16) into a 10-bit HEIC at [outputPath] using
 * MediaCodec for HEVC Main10 (hardware) and a hand-rolled HEIF container
 * writer.
 *
 * Why hand-roll the container: Android's MediaMuxer.MUXER_OUTPUT_HEIF only
 * supports HEVC Main profile (8-bit). When given Main10 it silently falls
 * back to writing an MP4 video. So we capture the raw HEVC bitstream from
 * MediaCodec and wrap it ourselves per ISO/IEC 23008-12.
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
    fun encode(bitmap: Bitmap, outputPath: String, qualityHint: Int = 95): String? = try {
        encodeImpl(bitmap, outputPath, qualityHint)
    } catch (t: Throwable) {
        "[${t.javaClass.simpleName}] ${t.message ?: "(no message)"}"
    }

    private fun encodeImpl(bitmap: Bitmap, outputPath: String, qualityHint: Int): String? {
        if (!loaded) return "10bit native lib not loaded: $loadError"
        if (bitmap.config != Bitmap.Config.RGBA_F16) {
            return "bitmap must be RGBA_F16 (got ${bitmap.config})"
        }
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0 || (w and 1) != 0 || (h and 1) != 0) {
            return "dimensions must be positive and even (got ${w}x${h})"
        }

        val codecMime = MediaFormat.MIMETYPE_VIDEO_HEVC
        val format = MediaFormat.createVideoFormat(codecMime, w, h).apply {
            setInteger(MediaFormat.KEY_PROFILE,
                MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
            setInteger(MediaFormat.KEY_LEVEL,
                MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel62)
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010)
            val pixels = w.toLong() * h
            val bitrate = (pixels * 8L).coerceAtMost(200_000_000L)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate.toInt())
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 0)
            setInteger(MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
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

            val payloadAccum = ByteArrayOutputStream() // raw Annex-B image NAL units
            var csdBytes: ByteArray? = null

            feedInputP010(codec, bitmap, w, h)?.let { return it }

            val info = MediaCodec.BufferInfo()
            val timeoutUs = 5_000_000L
            val deadlineNanos = System.nanoTime() + 60_000_000_000L
            while (true) {
                if (System.nanoTime() > deadlineNanos) return "encode timeout"
                val outIdx = codec.dequeueOutputBuffer(info, timeoutUs)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = codec.outputFormat
                        // csd-0 contains VPS+SPS+PPS as Annex-B.
                        of.getByteBuffer("csd-0")?.let { bb ->
                            val arr = ByteArray(bb.remaining())
                            bb.get(arr)
                            csdBytes = arr
                        }
                    }
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> continue
                    outIdx >= 0 -> {
                        val ob = codec.getOutputBuffer(outIdx)
                        if (ob != null && info.size > 0) {
                            ob.position(info.offset)
                            ob.limit(info.offset + info.size)
                            val arr = ByteArray(info.size)
                            ob.get(arr)
                            if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                // Some encoders emit CSD here too; prefer this if we didn't get it
                                // via outputFormat.
                                if (csdBytes == null) csdBytes = arr
                            } else {
                                payloadAccum.write(arr)
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                    }
                }
            }

            val csd = csdBytes ?: return "encoder did not provide csd-0 (VPS/SPS/PPS)"
            val payload = payloadAccum.toByteArray()
            if (payload.isEmpty()) return "encoder produced no image data"

            return assembleHeif(csd, payload, w, h, outputPath)
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            try { codec.release() } catch (_: Exception) {}
        }
    }

    private fun assembleHeif(
        csdAnnexB: ByteArray, payloadAnnexB: ByteArray, w: Int, h: Int, outputPath: String
    ): String? {
        // Split CSD into VPS/SPS/PPS.
        val csdNals = HevcParser.splitNalUnits(csdAnnexB)
        val vps = csdNals.firstOrNull { it.type == HevcParser.NAL_VPS }
            ?: return "no VPS in csd"
        val sps = csdNals.firstOrNull { it.type == HevcParser.NAL_SPS }
            ?: return "no SPS in csd"
        val pps = csdNals.firstOrNull { it.type == HevcParser.NAL_PPS }
            ?: return "no PPS in csd"

        // hvcC body
        val hvcC = HevcParser.buildHvcC(vps.data, sps.data, pps.data)

        // Image NAL units (length-prefixed for HEIF mdat). Filter out any param sets
        // that the encoder may have inlined into the payload.
        val imageNals = HevcParser.splitNalUnits(payloadAnnexB)
            .filter { it.type !in listOf(HevcParser.NAL_VPS, HevcParser.NAL_SPS, HevcParser.NAL_PPS) }
        if (imageNals.isEmpty()) return "no image NAL units in encoded payload"
        val mdatPayload = HevcParser.toLengthPrefixed(imageNals)

        // Build container.
        val container = HeifContainerWriter(
            width = w, height = h,
            hvcCBody = hvcC,
            imageData = mdatPayload,
            bitDepth = 10,
            colourPrimaries = 1, transferCharacteristics = 1, matrixCoefficients = 1,
            fullRange = false
        ).build()

        java.io.File(outputPath).writeBytes(container)
        return null
    }

    /** Pack the bitmap into the codec's raw input buffer as P010 + EOS. */
    private fun feedInputP010(codec: MediaCodec, bitmap: Bitmap, w: Int, h: Int): String? {
        val inputIdx = codec.dequeueInputBuffer(10_000_000L)
        if (inputIdx < 0) return "no input buffer"
        val inBuf: ByteBuffer = codec.getInputBuffer(inputIdx)
            ?: return "null input buffer"
        inBuf.clear()

        val yStride = w * 2
        val uvStride = w * 2
        val ySize = yStride * h
        val uvSize = uvStride * (h / 2)
        val totalBytes = ySize + uvSize

        if (inBuf.capacity() < totalBytes) {
            return "input buffer too small: cap=${inBuf.capacity()} need=$totalBytes"
        }

        val tmp = ByteArray(totalBytes)
        val err = nativeRgbaF16ToP010(bitmap, tmp, yStride, uvStride, ySize)
        if (err != null) return err

        inBuf.put(tmp, 0, totalBytes)
        codec.queueInputBuffer(
            inputIdx, 0, totalBytes, 0,
            MediaCodec.BUFFER_FLAG_END_OF_STREAM
        )
        return null
    }

    @JvmStatic private external fun nativeRgbaF16ToP010(
        bitmap: Bitmap, out: ByteArray,
        yPlaneRowStrideBytes: Int, uvPlaneRowStrideBytes: Int,
        uvPlaneOffsetInBuffer: Int
    ): String?
}
