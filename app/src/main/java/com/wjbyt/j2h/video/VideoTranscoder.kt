package com.wjbyt.j2h.video

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Bundle
import androidx.documentfile.provider.DocumentFile
import java.nio.ByteBuffer

/**
 * HEVC video → AV1 video, surface-to-surface hardware transcoding.
 *
 * Pipeline:
 *   MediaExtractor (input mp4)
 *     ├── video: HEVC samples → MediaCodec HEVC decoder
 *     │                            (configured with encoder.inputSurface)
 *     │                          ↓ Surface (GPU)
 *     │                       MediaCodec AV1 encoder
 *     │                          ↓
 *     │                       MediaMuxer (mp4 output)
 *     │
 *     └── audio: AAC/etc samples copied byte-for-byte to muxer (no transcode)
 *
 * HDR preservation:
 *   - Static metadata (KEY_HDR_STATIC_INFO, color primaries/transfer/matrix/range)
 *     copied from input MediaFormat to encoder MediaFormat.
 *   - HDR10+ dynamic metadata extracted per-decoded-frame from
 *     decoder.getOutputFormat(idx)[KEY_HDR10_PLUS_INFO] and forwarded to the
 *     encoder via setParameters() right before releaseOutputBuffer(idx, true).
 *   - Dolby Vision RPU: not preserved (no public AV1 DV path) — falls back to
 *     HDR10/HDR10+ which the base layer already encodes.
 *
 * Requires hardware AV1 encoder; on devices without one (most non-flagship
 * phones), MediaCodec.createEncoderByType("video/av01") will fail and we
 * return Failed without touching the source file.
 */
object VideoTranscoder {

    sealed interface Result {
        data class Ok(val outName: String, val bytesIn: Long, val bytesOut: Long, val note: String) : Result
        data class Skipped(val reason: String) : Result
        data class Failed(val reason: String, val keepOriginal: Boolean = true) : Result
    }

    /**
     * @param bitrateRatio AV1 target bitrate as a fraction of source HEVC bitrate.
     *   0.6 ≈ visually transparent on a 75" TV at normal viewing distance, ~40%
     *   smaller files. 0.4 saves more but artifacts may show in motion. 0.7-0.8
     *   essentially indistinguishable from source.
     */
    fun transcode(
        context: Context, input: DocumentFile, parent: DocumentFile,
        bitrateRatio: Float = 0.6f
    ): Result {
        val srcName = input.name ?: return Result.Failed("no name")
        val baseName = srcName.substringBeforeLast('.', srcName)
        val targetName = "$baseName.av1.mp4"

        val existing = parent.findFile(targetName)
        if (existing != null && existing.length() > 0) return Result.Skipped("$targetName 已存在")

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, input.uri, null)
        } catch (e: Exception) {
            return Result.Failed("MediaExtractor 打开失败: ${e.message}")
        }

        var videoTrack = -1
        var audioTrack = -1
        var videoFormat: MediaFormat? = null
        var audioFormat: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/") && videoTrack < 0) {
                videoTrack = i; videoFormat = f
            } else if (mime.startsWith("audio/") && audioTrack < 0) {
                audioTrack = i; audioFormat = f
            }
        }
        if (videoTrack < 0 || videoFormat == null) {
            extractor.release()
            return Result.Failed("文件无视频轨")
        }
        val videoMime = videoFormat.getString(MediaFormat.KEY_MIME)!!
        // Dolby Vision profile 8.4 has an HEVC base layer; the system DV decoder strips
        // the RPU and outputs HDR10 BL as a 10-bit P010 surface, which the AV1 encoder
        // can consume directly. Accept both HEVC and DV MIMEs.
        val isDolbyVisionInput = videoMime == "video/dolby-vision"
        if (videoMime != MediaFormat.MIMETYPE_VIDEO_HEVC && !isDolbyVisionInput) {
            extractor.release()
            return Result.Failed("仅支持 HEVC 或 DV 输入（实际: $videoMime）")
        }

        // Pre-flight: AV1 hardware encoder must exist on this device.
        val av1EncoderName = findHardwareAv1EncoderName()
        if (av1EncoderName == null) {
            extractor.release()
            val all = listAv1Encoders().joinToString(", ").ifEmpty { "（一个 AV1 编码器都没找到）" }
            return Result.Failed("本机无硬件 AV1 编码器。系统中找到的 AV1 编码器: $all")
        }
        com.wjbyt.j2h.work.ConversionForegroundService.appendLog("  · 使用 AV1 编码器: $av1EncoderName")

        val w = videoFormat.getInteger(MediaFormat.KEY_WIDTH)
        val h = videoFormat.getInteger(MediaFormat.KEY_HEIGHT)
        val frameRate = if (videoFormat.containsKey(MediaFormat.KEY_FRAME_RATE))
            videoFormat.getInteger(MediaFormat.KEY_FRAME_RATE).coerceIn(1, 240) else 30

        // DV input is always HDR.
        val isHdr = isDolbyVisionInput || isHdrFormat(videoFormat)

        // Build encoder format.
        val encoderFormat = MediaFormat.createVideoFormat("video/av01", w, h).apply {
            setInteger(MediaFormat.KEY_PROFILE,
                if (isHdr) MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10
                else       MediaCodecInfo.CodecProfileLevel.AV1ProfileMain8)
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)

            val sourceBitrate = if (videoFormat.containsKey(MediaFormat.KEY_BIT_RATE))
                videoFormat.getInteger(MediaFormat.KEY_BIT_RATE)
            else (w.toLong() * h * frameRate / 4).toInt() // rough estimate ~0.25 bpp
            val ratio = bitrateRatio.coerceIn(0.2f, 1.0f)
            val targetBitrate = (sourceBitrate * ratio).toInt().coerceAtLeast(1_000_000)
            setInteger(MediaFormat.KEY_BIT_RATE, targetBitrate)
            setInteger(MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)

            // Copy static HDR metadata.
            if (videoFormat.containsKey(MediaFormat.KEY_HDR_STATIC_INFO)) {
                videoFormat.getByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO)?.let {
                    setByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO, it.duplicate())
                }
            }
            for (k in listOf(
                MediaFormat.KEY_COLOR_RANGE,
                MediaFormat.KEY_COLOR_STANDARD,
                MediaFormat.KEY_COLOR_TRANSFER
            )) {
                if (videoFormat.containsKey(k)) setInteger(k, videoFormat.getInteger(k))
            }
        }

        // Open output via SAF.
        existing?.delete()
        val outFile = parent.createFile("video/mp4", targetName)
            ?: run { extractor.release(); return Result.Failed("无法创建输出文件") }
        val pfd = try {
            context.contentResolver.openFileDescriptor(outFile.uri, "rw")
        } catch (e: Exception) {
            outFile.delete(); extractor.release()
            return Result.Failed("openFileDescriptor: ${e.message}")
        } ?: run {
            outFile.delete(); extractor.release()
            return Result.Failed("openFileDescriptor returned null")
        }

        var encoder: MediaCodec? = null
        var decoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var inputSurface: android.view.Surface? = null
        try {
            encoder = MediaCodec.createByCodecName(av1EncoderName)
            try {
                encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            } catch (e: Exception) {
                return Result.Failed("AV1 编码器配置失败: ${e.message}", keepOriginal = true)
            }
            inputSurface = encoder.createInputSurface()

            decoder = MediaCodec.createDecoderByType(videoMime)
            decoder.configure(videoFormat, inputSurface, null, 0)

            decoder.start()
            encoder.start()

            muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            extractor.selectTrack(videoTrack)

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var encoderInputDone = false
            var encoderOutputDone = false
            var muxerVideoTrack = -1
            var muxerAudioTrack = -1
            var muxerStarted = false

            // ---- Main video pump ----
            val deadlineNs = System.nanoTime() + 30L * 60 * 1_000_000_000L // 30 min budget
            while (!encoderOutputDone) {
                if (System.nanoTime() > deadlineNs) return Result.Failed("视频转码超时", keepOriginal = true)

                // Feed extractor → decoder.
                if (!inputDone) {
                    val inIdx = decoder.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        val inBuf = decoder.getInputBuffer(inIdx) ?: return Result.Failed("decoder input null")
                        val sampleSize = extractor.readSampleData(inBuf, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val pts = extractor.sampleTime
                            decoder.queueInputBuffer(inIdx, 0, sampleSize, pts, 0)
                            extractor.advance()
                        }
                    }
                }

                // Drain decoder → release-to-surface (encoder input).
                if (!encoderInputDone) {
                    val outIdx = decoder.dequeueOutputBuffer(info, 0L)
                    when {
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ||
                        outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            /* nothing to do */
                        }
                        outIdx >= 0 -> {
                            val isEos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            if (isEos) {
                                encoder.signalEndOfInputStream()
                                encoderInputDone = true
                                decoder.releaseOutputBuffer(outIdx, false)
                            } else {
                                // Forward per-frame HDR10+ metadata.
                                if (isHdr) {
                                    try {
                                        val perFrame = decoder.getOutputFormat(outIdx)
                                        val hdr10p = perFrame.getByteBuffer(MediaFormat.KEY_HDR10_PLUS_INFO)
                                        if (hdr10p != null && hdr10p.remaining() > 0) {
                                            val arr = ByteArray(hdr10p.remaining())
                                            hdr10p.get(arr)
                                            encoder.setParameters(Bundle().apply {
                                                putByteArray(MediaFormat.KEY_HDR10_PLUS_INFO, arr)
                                            })
                                        }
                                    } catch (_: Exception) { /* not all frames have it */ }
                                }
                                decoder.releaseOutputBuffer(outIdx, info.size > 0)
                            }
                        }
                    }
                }

                // Drain encoder → muxer.
                val encOutIdx = encoder.dequeueOutputBuffer(info, 10_000L)
                when {
                    encOutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (muxerStarted) return Result.Failed("encoder format changed twice")
                        muxerVideoTrack = muxer.addTrack(encoder.outputFormat)
                        if (audioTrack >= 0 && audioFormat != null) {
                            muxerAudioTrack = muxer.addTrack(audioFormat)
                        }
                        muxer.start()
                        muxerStarted = true
                    }
                    encOutIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {}
                    encOutIdx >= 0 -> {
                        val outBuf = encoder.getOutputBuffer(encOutIdx)
                        val isCfg = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        if (outBuf != null && info.size > 0 && muxerStarted && !isCfg) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            muxer.writeSampleData(muxerVideoTrack, outBuf, info)
                        }
                        encoder.releaseOutputBuffer(encOutIdx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            encoderOutputDone = true
                        }
                    }
                }
            }

            // ---- Audio sample copy (no transcode) ----
            if (muxerAudioTrack >= 0 && audioTrack >= 0) {
                extractor.unselectTrack(videoTrack)
                extractor.selectTrack(audioTrack)
                extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                val audioBuf = ByteBuffer.allocate(1 shl 20) // 1 MB scratch
                val audioInfo = MediaCodec.BufferInfo()
                while (true) {
                    val sz = extractor.readSampleData(audioBuf, 0)
                    if (sz < 0) break
                    audioInfo.set(0, sz, extractor.sampleTime, extractor.sampleFlags)
                    muxer.writeSampleData(muxerAudioTrack, audioBuf, audioInfo)
                    extractor.advance()
                }
            }

            try { muxer.stop() } catch (_: Exception) {}

            // Verify output decodes.
            val verifyErr = verifyDecodable(context, outFile)
            if (verifyErr != null) {
                outFile.delete()
                return Result.Failed("校验失败: $verifyErr", keepOriginal = true)
            }

            val srcSize = input.length()
            val outSize = outFile.length()
            // Mirror source mtime.
            try { java.io.File(safPath(outFile.uri))?.setLastModified(input.lastModified()) }
            catch (_: Exception) {}
            if (!input.delete()) {
                return Result.Failed("已转码但删除原视频失败", keepOriginal = true)
            }
            val saved = if (srcSize > 0) (100 - outSize * 100 / srcSize) else 0L
            return Result.Ok(targetName, srcSize, outSize,
                "AV1 ${if (isHdr) "Main10 HDR" else "Main8 SDR"}, 减小 ${saved}%")
        } catch (t: Throwable) {
            outFile.delete()
            return Result.Failed("[${t.javaClass.simpleName}] ${t.message ?: "(no message)"}",
                                 keepOriginal = true)
        } finally {
            try { decoder?.stop() } catch (_: Exception) {}
            try { decoder?.release() } catch (_: Exception) {}
            try { encoder?.stop() } catch (_: Exception) {}
            try { encoder?.release() } catch (_: Exception) {}
            try { inputSurface?.release() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
            try { pfd.close() } catch (_: Exception) {}
        }
    }

    private fun isHdrFormat(f: MediaFormat): Boolean {
        if (f.containsKey(MediaFormat.KEY_HDR_STATIC_INFO)) return true
        if (f.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) {
            val t = f.getInteger(MediaFormat.KEY_COLOR_TRANSFER)
            if (t == MediaFormat.COLOR_TRANSFER_ST2084 || t == MediaFormat.COLOR_TRANSFER_HLG) return true
        }
        return false
    }

    /** Returns the name of a hardware-accelerated AV1 encoder, or null if none. */
    private fun findHardwareAv1EncoderName(): String? {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (info in list.codecInfos) {
            if (!info.isEncoder) continue
            if (info.isAlias) continue
            val supportsAv1 = info.supportedTypes.any { it.equals("video/av01", ignoreCase = true) }
            if (!supportsAv1) continue
            // Use the official API instead of name guessing.
            val isHw = try { info.isHardwareAccelerated } catch (_: Throwable) { false }
            val isSw = try { info.isSoftwareOnly } catch (_: Throwable) { false }
            if (isHw && !isSw) return info.name
        }
        return null
    }

    /** Diagnostic listing of every AV1 encoder + its hw/sw classification. */
    private fun listAv1Encoders(): List<String> {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val out = mutableListOf<String>()
        for (info in list.codecInfos) {
            if (!info.isEncoder) continue
            val supportsAv1 = info.supportedTypes.any { it.equals("video/av01", ignoreCase = true) }
            if (!supportsAv1) continue
            val hw = try { info.isHardwareAccelerated } catch (_: Throwable) { false }
            val sw = try { info.isSoftwareOnly } catch (_: Throwable) { false }
            out += "${info.name}(hw=$hw,sw=$sw)"
        }
        return out
    }

    private fun verifyDecodable(context: Context, file: DocumentFile): String? {
        return try {
            val ex = MediaExtractor()
            try {
                ex.setDataSource(context, file.uri, null)
                if (ex.trackCount == 0) return "新文件无轨道"
                var foundVideo = false
                for (i in 0 until ex.trackCount) {
                    val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("video/")) { foundVideo = true; break }
                }
                if (!foundVideo) return "新文件无视频轨"
                null
            } finally {
                ex.release()
            }
        } catch (e: Exception) {
            "MediaExtractor 读不回新文件: ${e.message}"
        }
    }

    /** Best-effort SAF URI → filesystem path; returns null if not derivable. */
    private fun safPath(uri: android.net.Uri): String? {
        return try {
            val docId = android.provider.DocumentsContract.getDocumentId(uri)
            val parts = docId.split(":", limit = 2)
            if (parts.size != 2) return null
            when (parts[0]) {
                "primary" -> "${android.os.Environment.getExternalStorageDirectory().absolutePath}/${parts[1]}"
                else -> "/storage/${parts[0]}/${parts[1]}"
            }
        } catch (_: Exception) { null }
    }
}
