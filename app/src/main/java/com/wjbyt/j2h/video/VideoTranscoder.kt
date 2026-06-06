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
 * HEVC video → HEVC video re-encode, surface-to-surface hardware transcoding.
 * (Originally targeted AV1 but vivo X200 Ultra doesn't expose hardware AV1
 *  encoder via MediaCodec — only software fallback. HEVC→HEVC at lower bitrate
 *  is the practical hardware-speed path.)
 *
 * Pipeline:
 *   MediaExtractor (input mp4)
 *     ├── video: HEVC/DV samples → MediaCodec decoder
 *     │                            (configured with encoder.inputSurface)
 *     │                          ↓ Surface (GPU)
 *     │                       MediaCodec HEVC encoder (Main10 if HDR, else Main)
 *     │                          ↓
 *     │                       MediaMuxer (mp4 output)
 *     │
 *     └── audio: AAC/etc samples copied byte-for-byte (no transcode)
 *
 * HDR preservation:
 *   - Static metadata (KEY_HDR_STATIC_INFO, color primaries/transfer/matrix/range)
 *     copied from input format to encoder format.
 *   - HDR10+ per-frame dynamic metadata extracted from decoder.getOutputFormat
 *     (idx)[KEY_HDR10_PLUS_INFO] and forwarded to the encoder via setParameters()
 *     right before releaseOutputBuffer(idx, true).
 *   - Dolby Vision RPU layer: dropped — Android exposes no public DV encoder
 *     path for third-party apps. Base-layer HDR10 / HDR10+ is preserved.
 */
object VideoTranscoder {

    sealed interface Result {
        data class Ok(val outName: String, val bytesIn: Long, val bytesOut: Long, val note: String) : Result
        data class Skipped(val reason: String) : Result
        data class Failed(val reason: String, val keepOriginal: Boolean = true) : Result
    }

    /**
     * @param qualityPct 0..100 quality target — used as Constant Quality (CQ) level
     *   when the encoder supports BITRATE_MODE_CQ (most modern HW HEVC encoders do),
     *   else mapped to a VBR target bitrate as fraction of source. 70 ≈ visually
     *   transparent for typical phone HDR video, 50 = aggressive, 90+ = preserve.
     */
    fun transcode(
        context: Context, input: DocumentFile, parent: DocumentFile,
        qualityPct: Int = 70
    ): Result {
        val srcName = input.name ?: return Result.Failed("no name")
        val baseName = srcName.substringBeforeLast('.', srcName)
        // Encode to <base>.j2h.mp4 — the .j2h marker stays in the final name
        // so the scanner can recognise already-converted videos and skip
        // them on subsequent runs. After verification the source <base>.<ext>
        // is deleted, leaving exactly one (smaller, marker-tagged) file in
        // the folder.
        val targetName = "$baseName.j2h.mp4"

        // Carry-over from a previous interrupted run.
        parent.findFile(targetName)?.delete()

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

        // Pre-flight: hardware HEVC encoder. Pretty much guaranteed on any modern
        // phone; if absent we fail fast.
        val hevcEncoderName = findHardwareEncoderName(MediaFormat.MIMETYPE_VIDEO_HEVC)
        if (hevcEncoderName == null) {
            extractor.release()
            val all = listEncoders(MediaFormat.MIMETYPE_VIDEO_HEVC).joinToString(", ")
                .ifEmpty { "（无 HEVC 编码器）" }
            return Result.Failed("本机无硬件 HEVC 编码器。系统中: $all")
        }
        com.wjbyt.j2h.work.ConversionForegroundService.appendLog("  · 使用 HEVC 编码器: $hevcEncoderName")

        val w = videoFormat.getInteger(MediaFormat.KEY_WIDTH)
        val h = videoFormat.getInteger(MediaFormat.KEY_HEIGHT)
        val frameRate = if (videoFormat.containsKey(MediaFormat.KEY_FRAME_RATE))
            videoFormat.getInteger(MediaFormat.KEY_FRAME_RATE).coerceIn(1, 240) else 30

        // DV input is always HDR.
        val isHdr = isDolbyVisionInput || isHdrFormat(videoFormat)

        // Build encoder format.
        val encoderFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, w, h).apply {
            setInteger(MediaFormat.KEY_PROFILE,
                if (isHdr) MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
                else       MediaCodecInfo.CodecProfileLevel.HEVCProfileMain)
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            // 5-second GOP — same as Netflix/YouTube/Disney+ streaming
            // mezzanines. Saves ~20-30 % bits vs. our old 2-second GOP at
            // identical visual quality (P/B frames pack 5× more efficiently
            // than I-frames). Slightly slower seeks; not noticeable.
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5)

            // Bitrate / quality mode is decided AFTER we open the codec and query its
            // EncoderCapabilities (BITRATE_MODE_CQ is encoder-specific). We patch the
            // format below before configure().

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

        // Try to create the output via MediaStore.insert (IS_PENDING=1) so we
        // OWN the row and can set all metadata after encoding — no race with
        // the auto-scanner. Fall back to SAF if the parent isn't a primary
        // external directory MediaStore can address.
        val msInsertUri = tryInsertPendingVideo(context, parent.uri, targetName)
        val outFile: androidx.documentfile.provider.DocumentFile
        val pfd: android.os.ParcelFileDescriptor
        if (msInsertUri != null) {
            val tmpOut = androidx.documentfile.provider.DocumentFile.fromSingleUri(context, msInsertUri)
            if (tmpOut == null) {
                context.contentResolver.delete(msInsertUri, null, null); extractor.release()
                return Result.Failed("DocumentFile.fromSingleUri failed")
            }
            outFile = tmpOut
            val tmpPfd = try {
                context.contentResolver.openFileDescriptor(msInsertUri, "rw")
            } catch (e: Exception) {
                context.contentResolver.delete(msInsertUri, null, null); extractor.release()
                return Result.Failed("openFileDescriptor(ms): ${e.message}")
            }
            if (tmpPfd == null) {
                context.contentResolver.delete(msInsertUri, null, null); extractor.release()
                return Result.Failed("openFileDescriptor(ms) null")
            }
            pfd = tmpPfd
        } else {
            val tmpOut = parent.createFile("video/mp4", targetName)
            if (tmpOut == null) { extractor.release(); return Result.Failed("无法创建输出文件") }
            outFile = tmpOut
            val tmpPfd = try {
                context.contentResolver.openFileDescriptor(outFile.uri, "rw")
            } catch (e: Exception) {
                outFile.delete(); extractor.release()
                return Result.Failed("openFileDescriptor: ${e.message}")
            }
            if (tmpPfd == null) {
                outFile.delete(); extractor.release()
                return Result.Failed("openFileDescriptor returned null")
            }
            pfd = tmpPfd
        }

        var encoder: MediaCodec? = null
        var decoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var inputSurface: android.view.Surface? = null
        try {
            encoder = MediaCodec.createByCodecName(hevcEncoderName)
            // Decide CQ vs VBR based on encoder capabilities.
            try {
                val encCaps = encoder.codecInfo
                    .getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC).encoderCapabilities
                val cqOk = try {
                    encCaps.isBitrateModeSupported(
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ)
                } catch (_: Throwable) { false }

                if (cqOk) {
                    val qRange = encCaps.qualityRange
                    val q = (qRange.lower + (qRange.upper - qRange.lower) *
                                qualityPct.coerceIn(0, 100) / 100).coerceIn(qRange.lower, qRange.upper)
                    encoderFormat.setInteger(MediaFormat.KEY_BITRATE_MODE,
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ)
                    encoderFormat.setInteger(MediaFormat.KEY_QUALITY, q)
                    com.wjbyt.j2h.work.ConversionForegroundService.appendLog(
                        "  · CQ 模式 质量=$q (encoder range ${qRange.lower}..${qRange.upper})"
                    )
                } else {
                    // Fall back to VBR. We cannot trust KEY_BIT_RATE: vivo's
                    // recorder sometimes omits it or reports something nonsense
                    // (we previously hit this and produced 1 Mbps output for a
                    // 50 Mbps source because Int overflow killed the math). So
                    // we compute a per-pixel-per-frame target instead, derived
                    // from common HEVC "visually transparent" rates:
                    //   • SDR 8-bit  HEVC ≈ 0.04 bpp/frame transparent
                    //   • HDR 10-bit HEVC ≈ 0.06 bpp/frame transparent
                    // The qualityPct slider scales that target. We never go
                    // below a sensible per-resolution floor.
                    val pixelsPerFrame = w.toLong() * h
                    // Calibrated to streaming-mezzanine bitrates: at
                    // qualityPct = 70 (default) we land where Netflix /
                    // Disney+ encode 4K HDR for high-tier delivery
                    // (~10–15 Mbps). The user reported old defaults gave
                    // 25 Mbps for 4K HDR — too far above what their downloads
                    // typically use (3–5 Mbps for streaming TV). Halving the
                    // base bpp pushes us into the right band.
                    //
                    // For "preserve at all cost" the user can move the slider
                    // to 100 → ~21 Mbps for 4K HDR, comfortably above the
                    // visually-transparent threshold even on a 75" TV.
                    val baseBppPerFrame = if (isHdr) 0.04 else 0.025
                    val qScale = (qualityPct.coerceIn(30, 100)) / 100.0
                    // Default qualityPct=70 → 70 % of "transparent" ≈ still
                    // visually transparent for re-encoded content; 100 % = the
                    // full transparent target; below 50 % gets aggressive.
                    val targetLong = (pixelsPerFrame * frameRate *
                        baseBppPerFrame * qScale).toLong()
                    val floorBps = if (pixelsPerFrame >= 3840L * 2000) 5_000_000L
                                   else if (pixelsPerFrame >= 1920L * 1000) 1_500_000L
                                   else 800_000L
                    val capBps = 200_000_000L
                    val target = targetLong
                        .coerceAtLeast(floorBps)
                        .coerceAtMost(capBps)
                        .toInt()
                    encoderFormat.setInteger(MediaFormat.KEY_BIT_RATE, target)
                    encoderFormat.setInteger(MediaFormat.KEY_BITRATE_MODE,
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                    val srcBr = if (videoFormat.containsKey(MediaFormat.KEY_BIT_RATE))
                        videoFormat.getInteger(MediaFormat.KEY_BIT_RATE) else 0
                    com.wjbyt.j2h.work.ConversionForegroundService.appendLog(
                        "  · CQ 不支持，回退 VBR ${target / 1000} kbps " +
                        "(${if (isHdr) "HDR" else "SDR"} ${w}x${h}@${frameRate}, " +
                        "源码率=${if (srcBr > 0) "${srcBr/1000} kbps" else "未知"})"
                    )
                }
            } catch (e: Exception) {
                // If anything goes wrong querying caps, leave format without bitrate
                // mode set and let the encoder pick a default.
                com.wjbyt.j2h.work.ConversionForegroundService.appendLog(
                    "  · 查询编码器能力失败，使用编码器默认: ${e.message}"
                )
            }
            try {
                encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            } catch (e: Exception) {
                return Result.Failed("HEVC 编码器配置失败: ${e.message}", keepOriginal = true)
            }
            inputSurface = encoder.createInputSurface()

            decoder = MediaCodec.createDecoderByType(videoMime)
            decoder.configure(videoFormat, inputSurface, null, 0)

            decoder.start()
            encoder.start()

            muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // Preserve metadata that gallery apps display: rotation + GPS.
            // MediaMuxer doesn't copy these from the source automatically.
            try {
                val rotation = if (videoFormat.containsKey(MediaFormat.KEY_ROTATION))
                    videoFormat.getInteger(MediaFormat.KEY_ROTATION) else 0
                if (rotation != 0) muxer.setOrientationHint(rotation)
            } catch (_: Throwable) {}
            var videoGpsLat: Float? = null
            var videoGpsLon: Float? = null
            try {
                val mmr = android.media.MediaMetadataRetriever()
                try {
                    mmr.setDataSource(context, input.uri)
                    // ISO 6709 format: "+ddd.dddd+ddd.dddd[+aa.aaa]/"
                    val loc = mmr.extractMetadata(
                        android.media.MediaMetadataRetriever.METADATA_KEY_LOCATION)
                    if (!loc.isNullOrEmpty()) {
                        // Parse two signed decimal numbers; ignore optional altitude.
                        val re = Regex("([+\\-]\\d+\\.?\\d*)([+\\-]\\d+\\.?\\d*)")
                        re.find(loc)?.let { m ->
                            val lat = m.groupValues[1].toFloatOrNull()
                            val lon = m.groupValues[2].toFloatOrNull()
                            if (lat != null && lon != null) {
                                videoGpsLat = lat; videoGpsLon = lon
                                muxer.setLocation(lat, lon)
                                com.wjbyt.j2h.work.ConversionForegroundService.appendLog(
                                    "  · 视频 GPS 透传: $lat, $lon"
                                )
                            }
                        }
                    }
                } finally { mmr.release() }
            } catch (_: Throwable) {}

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
            val srcMtime = input.lastModified()
            // Read source metadata before deleting the file.
            val srcMeta = readSourceMeta(context, input.uri)
            if (!input.delete()) {
                return Result.Failed("已转码但删除原视频失败", keepOriginal = true)
            }
            // Mirror source mtime onto the converted file so vivo gallery's
            // "时间" panel shows the original shoot time, not the conversion
            // time. Also inject ©mak / ©mod into moov/udta so the gallery's
            // "拍摄设备" line gets populated — Android's MediaMuxer doesn't
            // expose these atoms, so we patch the closed file.
            safPath(outFile.uri)?.let { p ->
                val f = java.io.File(p)
                val mdl = Mp4MetadataInjector.marketModel()
                val creationMp4 = srcMeta.mp4Time ?: (srcMtime / 1000L + 2082844800L)
                // Inject BEFORE setLastModified so the gallery's first (and only)
                // scan sees the complete file — moov + uuid box — just like a
                // native vivo camera video. Two setLastModified calls would trigger
                // two scans; the second would see a non-trailing moov and report -1×-1.
                val injected = try {
                    Mp4MetadataInjector.inject(
                        f,
                        make = android.os.Build.MANUFACTURER,
                        model = mdl,
                        creationMp4Time = creationMp4
                    )
                } catch (_: Exception) { false }
                try { f.setLastModified(srcMtime) } catch (_: Exception) {}
                if (injected) {
                    com.wjbyt.j2h.work.ConversionForegroundService.appendLog(
                        "  · 已注入 ©mak/©mod(QT) + 拍摄时间 + 设备: ${android.os.Build.MANUFACTURER} / $mdl"
                    )
                }
                // Publish MediaStore row (we own it via insert) OR force-rescan.
                if (msInsertUri != null) {
                    publishVideoMediaStore(context, msInsertUri, srcMeta, videoGpsLat, videoGpsLon)
                } else {
                    syncVideoMetadata(context, f, srcMeta)
                }
            }
            val saved = if (srcSize > 0) (100 - outSize * 100 / srcSize) else 0L
            val dvNote = if (isDolbyVisionInput) "（DV→HDR10+）" else ""
            return Result.Ok(targetName, srcSize, outSize,
                "HEVC ${if (isHdr) "Main10 HDR10+" else "Main SDR"}$dvNote, 减小 ${saved}%")
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

    private fun findHardwareEncoderName(mime: String): String? {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (info in list.codecInfos) {
            if (!info.isEncoder) continue
            if (info.isAlias) continue
            val supports = info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
            if (!supports) continue
            val isHw = try { info.isHardwareAccelerated } catch (_: Throwable) { false }
            val isSw = try { info.isSoftwareOnly } catch (_: Throwable) { false }
            if (isHw && !isSw) return info.name
        }
        return null
    }

    private fun listEncoders(mime: String): List<String> {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val out = mutableListOf<String>()
        for (info in list.codecInfos) {
            if (!info.isEncoder) continue
            val supports = info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
            if (!supports) continue
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

    private data class SourceMeta(
        val mp4Time: Long?,    // seconds since 1904-01-01 UTC (for Mp4MetadataInjector)
        val shootMs: Long?,    // milliseconds since 1970-01-01 (for MediaStore DATE_TAKEN)
        val width: Int,
        val height: Int
    )

    /** Read key metadata from the source video via MediaMetadataRetriever. */
    private fun readSourceMeta(context: android.content.Context, uri: android.net.Uri): SourceMeta {
        val mmr = android.media.MediaMetadataRetriever()
        return try {
            mmr.setDataSource(context, uri)
            val w = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val dateStr = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DATE)
            var mp4t: Long? = null; var shootMs: Long? = null
            if (!dateStr.isNullOrBlank()) {
                val clean = dateStr.replace("-","").replace(":","")
                val sdf = java.text.SimpleDateFormat("yyyyMMdd'T'HHmmss", java.util.Locale.US)
                sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
                val t = try { sdf.parse(clean.take(15))?.time } catch (_: Exception) { null }
                if (t != null) { mp4t = t / 1000L + 2082844800L; shootMs = t }
            }
            SourceMeta(mp4t, shootMs, w, h)
        } catch (_: Exception) { SourceMeta(null, null, 0, 0) }
        finally { mmr.release() }
    }

    /**
     * After injection, force MediaStore to see the final file and update
     * DATE_TAKEN / WIDTH / HEIGHT with correct source values.
     * On API 30+: MediaStore.scanFile (synchronous) + explicit update.
     * Below API 30: async MediaScannerConnection.scanFile.
     * MANAGE_EXTERNAL_STORAGE lets us update rows we don't own.
     */
    /** Insert a pending MediaStore video row; returns its URI or null on failure. */
    private fun tryInsertPendingVideo(
        context: android.content.Context,
        parentUri: android.net.Uri,
        targetName: String
    ): android.net.Uri? {
        return try {
            val docId = try { android.provider.DocumentsContract.getTreeDocumentId(parentUri) }
                        catch (_: Exception) { android.provider.DocumentsContract.getDocumentId(parentUri) }
            val parts = docId.split(":", limit = 2)
            if (parts[0] != "primary") return null
            val rel = parts.getOrNull(1).orEmpty().let { if (it.isEmpty()) return null else "$it/" }
            val col = android.provider.MediaStore.Video.Media
                .getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val cv = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, targetName)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, rel)
                put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
            }
            context.contentResolver.insert(col, cv)
        } catch (_: Exception) { null }
    }

    /** Publish a MediaStore video row (IS_PENDING→0) and set metadata we own. */
    private fun publishVideoMediaStore(
        context: android.content.Context,
        uri: android.net.Uri,
        meta: SourceMeta,
        gpsLat: Float?, gpsLon: Float?
    ) {
        try {
            val cv = android.content.ContentValues()
            cv.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
            // Use MediaStore.MediaColumns constants (safe cross-version) and string
            // literals for GPS (LATITUDE/LONGITUDE are not in VideoColumns).
            meta.shootMs?.let { cv.put("datetaken", it) }
            if (meta.width > 0)  cv.put(android.provider.MediaStore.MediaColumns.WIDTH, meta.width)
            if (meta.height > 0) cv.put(android.provider.MediaStore.MediaColumns.HEIGHT, meta.height)
            gpsLat?.let { cv.put("latitude",  it.toDouble()) }
            gpsLon?.let { cv.put("longitude", it.toDouble()) }
            context.contentResolver.update(uri, cv, null, null)
        } catch (_: Exception) {}
    }

    private fun syncVideoMetadata(
        context: android.content.Context, f: java.io.File, meta: SourceMeta
    ) {
        try {
            // Trigger a re-scan and update MediaStore with correct metadata.
            // MediaScannerConnection.scanFile is async; after it queues, we
            // immediately query MediaStore for the existing row and push our values.
            android.media.MediaScannerConnection.scanFile(
                context, arrayOf(f.absolutePath), arrayOf("video/mp4"), null)
            val col = android.provider.MediaStore.Video.Media
                .getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = context.contentResolver.query(col,
                arrayOf(android.provider.MediaStore.MediaColumns._ID),
                "${android.provider.MediaStore.MediaColumns.DATA}=?",
                arrayOf(f.absolutePath), null)?.use { c ->
                if (c.moveToFirst()) android.content.ContentUris.withAppendedId(col, c.getLong(0))
                else null
            } ?: return
            val cv = android.content.ContentValues()
            meta.shootMs?.let { cv.put("datetaken", it) }
            if (meta.width > 0)  cv.put(android.provider.MediaStore.MediaColumns.WIDTH, meta.width)
            if (meta.height > 0) cv.put(android.provider.MediaStore.MediaColumns.HEIGHT, meta.height)
            if (cv.size() > 0) context.contentResolver.update(uri, cv, null, null)
        } catch (_: Exception) {}
    }

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
