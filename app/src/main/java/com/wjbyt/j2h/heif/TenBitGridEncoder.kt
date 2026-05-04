package com.wjbyt.j2h.heif

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import java.nio.ByteBuffer

/**
 * Encodes a Bitmap (RGBA_F16) larger than the encoder's single-frame capacity into a
 * grid of HEVC Main10 tiles, then assembles them into a HEIF grid container.
 *
 * Tile layout:
 *   - Image is divided into rows × cols tiles, all of size [tileW × tileH].
 *   - When image dims aren't multiples of tile size, the last column/row is
 *     padded by edge-replicating native-side; the grid item records the actual
 *     output dimensions so decoders crop correctly.
 *
 * Encoder strategy:
 *   - Single MediaCodec session, configured at tileW × tileH.
 *   - Feed N tiles with monotonically increasing PTS; output buffers come back
 *     with matching PTS so we can index per-tile bitstream.
 *   - VPS/SPS/PPS captured from outputFormat['csd-0'] and shared by all tiles.
 */
object TenBitGridEncoder {

    fun isAvailable(): Boolean = TenBitEncoder.isAvailable()

    /**
     * Encode bitmap as a grid HEIC at [outputPath]. tileW/tileH must be even and
     * fit within the encoder's single-frame capability (default 2048×2048 is safe
     * for any HEVC Main10 hardware encoder).
     */
    fun encode(
        bitmap: Bitmap, outputPath: String, qualityHint: Int = 95,
        tileW: Int = 2048, tileH: Int = 2048,
        colourPrimaries: Int = 12, transferCharacteristics: Int = 1, matrixCoefficients: Int = 1
    ): String? = try {
        encodeImpl(bitmap, outputPath, qualityHint, tileW, tileH,
                   colourPrimaries, transferCharacteristics, matrixCoefficients)
    } catch (e: MediaCodec.CodecException) {
        "[CodecException] err=${e.errorCode} diag=${e.diagnosticInfo} msg=${e.message ?: "?"}"
    } catch (t: Throwable) {
        "[${t.javaClass.simpleName}] ${t.message ?: "(no message)"}"
    }

    private fun encodeImpl(
        bitmap: Bitmap, outputPath: String, qualityHint: Int,
        tileW: Int, tileH: Int,
        colourPrimaries: Int, transferCharacteristics: Int, matrixCoefficients: Int
    ): String? {
        if (bitmap.config != Bitmap.Config.RGBA_F16) return "bitmap must be RGBA_F16"
        val outputW = bitmap.width
        val outputH = bitmap.height
        val cols = (outputW + tileW - 1) / tileW
        val rows = (outputH + tileH - 1) / tileH
        val totalTiles = rows * cols

        com.wjbyt.j2h.work.ConversionForegroundService.appendLog(
            "  · 切瓦片 ${cols}x${rows} = $totalTiles 块（每块 ${tileW}x${tileH}）"
        )

        // Configure codec at TILE size (not image size).
        val codecMime = MediaFormat.MIMETYPE_VIDEO_HEVC
        val format = MediaFormat.createVideoFormat(codecMime, tileW, tileH).apply {
            setInteger(MediaFormat.KEY_PROFILE,
                MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
            setInteger(MediaFormat.KEY_LEVEL,
                MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel51)
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010)
            val tilePixels = tileW.toLong() * tileH
            val bitrate = (tilePixels * 8L).coerceAtMost(80_000_000L)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate.toInt())
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 0)
            setInteger(MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        }

        val codec = MediaCodec.createEncoderByType(codecMime)
        try {
            // Verify tile size supported.
            try {
                val v = codec.codecInfo.getCapabilitiesForType(codecMime).videoCapabilities
                if (!v.isSizeSupported(tileW, tileH)) {
                    return "硬件编码器不支持 ${tileW}x${tileH} 瓦片"
                }
            } catch (_: Exception) {}

            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            val tileBitstreams = arrayOfNulls<ByteArray>(totalTiles)
            var csd: ByteArray? = null
            var tilesIn = 0
            var tilesOut = 0
            val info = MediaCodec.BufferInfo()
            val deadlineNanos = System.nanoTime() + 5L * 60 * 1_000_000_000L // 5 min budget
            val tileBufBytes = (tileW * 2) * tileH + (tileW * 2) * (tileH / 2)
            val ySize = (tileW * 2) * tileH
            val tilePcmBuffer = ByteArray(tileBufBytes)

            while (tilesOut < totalTiles) {
                if (System.nanoTime() > deadlineNanos) return "encode timeout"

                // Feed input until all tiles queued.
                if (tilesIn < totalTiles) {
                    val inIdx = codec.dequeueInputBuffer(50_000L) // 50ms
                    if (inIdx >= 0) {
                        val inBuf: ByteBuffer = codec.getInputBuffer(inIdx)
                            ?: return "null input buffer"
                        inBuf.clear()

                        val tileIdx = tilesIn
                        val srcX = (tileIdx % cols) * tileW
                        val srcY = (tileIdx / cols) * tileH
                        val err = TenBitEncoder.nativeRgbaF16ToP010Public(
                            bitmap, tilePcmBuffer,
                            yPlaneRowStrideBytes = tileW * 2,
                            uvPlaneRowStrideBytes = tileW * 2,
                            uvPlaneOffsetInBuffer = ySize,
                            srcX = srcX, srcY = srcY,
                            tileW = tileW, tileH = tileH
                        )
                        if (err != null) return "tile $tileIdx: $err"

                        if (inBuf.capacity() < tilePcmBuffer.size) {
                            return "input buffer too small (${inBuf.capacity()} < ${tilePcmBuffer.size})"
                        }
                        inBuf.put(tilePcmBuffer, 0, tilePcmBuffer.size)

                        val isLast = tilesIn == totalTiles - 1
                        val flags = if (isLast) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                        val ptsUs = tilesIn.toLong() * 33_333L
                        codec.queueInputBuffer(inIdx, 0, tilePcmBuffer.size, ptsUs, flags)
                        tilesIn++
                    }
                }

                // Drain output (don't block long when we still have inputs to feed).
                val drainTimeout = if (tilesIn < totalTiles) 1_000L else 100_000L
                val outIdx = codec.dequeueOutputBuffer(info, drainTimeout)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        codec.outputFormat.getByteBuffer("csd-0")?.let { bb ->
                            val arr = ByteArray(bb.remaining()); bb.get(arr); csd = arr
                        }
                    }
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {}
                    outIdx >= 0 -> {
                        val ob = codec.getOutputBuffer(outIdx)
                        if (ob != null && info.size > 0) {
                            ob.position(info.offset); ob.limit(info.offset + info.size)
                            val arr = ByteArray(info.size); ob.get(arr)
                            if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                if (csd == null) csd = arr
                            } else {
                                val tileIdx = (info.presentationTimeUs / 33_333L).toInt()
                                if (tileIdx in 0 until totalTiles) {
                                    tileBitstreams[tileIdx] = arr
                                    tilesOut++
                                }
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            // No more output coming.
                            break
                        }
                    }
                }
            }

            val csdBytes = csd ?: return "encoder did not provide csd"
            val tileData = tileBitstreams.mapIndexed { i, b -> b ?: return "tile $i missing" }

            return assembleGridHeif(
                csdBytes, tileData,
                outputW = outputW, outputH = outputH,
                tileW = tileW, tileH = tileH, rows = rows, cols = cols,
                outputPath = outputPath,
                colourPrimaries = colourPrimaries,
                transferCharacteristics = transferCharacteristics,
                matrixCoefficients = matrixCoefficients
            )
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            try { codec.release() } catch (_: Exception) {}
        }
    }

    private fun assembleGridHeif(
        csdAnnexB: ByteArray, tileBitstreamsAnnexB: List<ByteArray>,
        outputW: Int, outputH: Int, tileW: Int, tileH: Int, rows: Int, cols: Int,
        outputPath: String,
        colourPrimaries: Int, transferCharacteristics: Int, matrixCoefficients: Int
    ): String? {
        val csdNals = HevcParser.splitNalUnits(csdAnnexB)
        val vps = csdNals.firstOrNull { it.type == HevcParser.NAL_VPS } ?: return "no VPS"
        val sps = csdNals.firstOrNull { it.type == HevcParser.NAL_SPS } ?: return "no SPS"
        val pps = csdNals.firstOrNull { it.type == HevcParser.NAL_PPS } ?: return "no PPS"
        val hvcCBody = HevcParser.buildHvcC(vps.data, sps.data, pps.data)

        // Per-tile NAL units (length-prefixed, with parameter sets stripped).
        val tileMdatPayloads = tileBitstreamsAnnexB.map { annexB ->
            val nals = HevcParser.splitNalUnits(annexB).filter {
                it.type !in listOf(HevcParser.NAL_VPS, HevcParser.NAL_SPS, HevcParser.NAL_PPS)
            }
            HevcParser.toLengthPrefixed(nals)
        }

        val container = HeifGridContainerWriter(
            outputW = outputW, outputH = outputH,
            tileW = tileW, tileH = tileH, rows = rows, cols = cols,
            hvcCBody = hvcCBody,
            tileData = tileMdatPayloads,
            bitDepth = 10,
            colourPrimaries = colourPrimaries,
            transferCharacteristics = transferCharacteristics,
            matrixCoefficients = matrixCoefficients
        ).build()

        java.io.File(outputPath).writeBytes(container)
        return null
    }
}
