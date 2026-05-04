package com.wjbyt.j2h.heif

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import androidx.documentfile.provider.DocumentFile
import androidx.heifwriter.HeifWriter
import java.io.File

/**
 * JPG → HEIC conversion. Greatly simplified per the user's directive:
 *
 *  - We no longer try to make the HEIC's embedded EXIF readable in-gallery
 *    (vivo's gallery doesn't read HEIC EXIF; we proved this exhaustively).
 *  - Instead, after a successful encode, we push DateTime / GPS / camera fields
 *    onto Android MediaStore via MediaStoreSync — vendor galleries source those
 *    columns for the photo info panel and time sorting.
 *  - libheif still embeds full EXIF in the file (for desktop / Google Photos),
 *    but we no longer verify it; verify is just "decodable".
 *
 * Encoding path:
 *  1. Try libheif single-frame (best compression, embeds EXIF in file).
 *  2. If its output won't decode on this device (HEVC > Level 6.2, ~35MP cap),
 *     retry with Android's HeifWriter (auto-tiles via hardware HEVC).
 */
class HeicConverter(
    private val context: Context,
    private val quality: Int = 95
) {
    sealed interface Result {
        data class Ok(val outName: String, val bytesIn: Long, val bytesOut: Long, val encoder: String) : Result
        data class Skipped(val reason: String) : Result
        data class Failed(val reason: String, val keepOriginal: Boolean = true) : Result
    }

    fun convert(jpg: DocumentFile, parent: DocumentFile): Result {
        val name = jpg.name ?: return Result.Failed("no name")
        val baseName = name.substringBeforeLast('.', name)
        val targetName = "$baseName.heic"
        val isDng = name.lowercase().endsWith(".dng")

        val existing = parent.findFile(targetName)
        if (existing != null && existing.length() > 0) return Result.Skipped("$targetName 已存在")

        val snapshot = MediaStoreSync.readSourceJpg(context, jpg)
        // Log what we found in the source so we can tell whether missing fields in the
        // output are due to source content vs. our pipeline.
        com.wjbyt.j2h.work.ConversionForegroundService.appendLog(
            "  源 EXIF: DateTime=${snapshot.dateTakenMillis}, " +
            "GPS=${if (snapshot.gpsLat != null) "%.6f,%.6f".format(snapshot.gpsLat, snapshot.gpsLon ?: 0.0) else "null"}, " +
            "Make=${snapshot.make ?: "null"}, Model=${snapshot.model ?: "null"}"
        )

        val srcBytes = try {
            context.contentResolver.openInputStream(jpg.uri)?.use { it.readBytes() }
                ?: return Result.Failed("无法打开源文件")
        } catch (e: Exception) { return Result.Failed("读取失败: ${e.message}") }

        // For JPG we extract the full TIFF EXIF blob to embed in HEIC. DNG already
        // is TIFF-based but its primary IFD points at the raw image strips, not what
        // we want to embed; for now we rely on MediaStoreSync (DateTime + mtime) for
        // DNG → HEIC metadata visibility.
        val exifTiff: ByteArray? = if (!isDng) {
            try {
                com.wjbyt.j2h.exif.JpegExifExtractor.extractTiff(java.io.ByteArrayInputStream(srcBytes))
            } catch (_: Exception) { null }
        } else null

        return try {
            if (isDng) convertDng(jpg, parent, targetName, srcBytes, snapshot, existing)
            else convertJpg(jpg, parent, targetName, srcBytes, exifTiff, snapshot, existing)
        } catch (t: Throwable) {
            // Last-resort safety net so any uncaught throw becomes a Result with a real
            // diagnostic message instead of '异常: null' bubbling to the service layer.
            Result.Failed(
                "未捕获异常 [${t.javaClass.simpleName}]: ${t.message ?: "(no message)"}",
                keepOriginal = true
            )
        }
    }

    /** JPG → 8-bit HEIC via HeifWriter (hardware HEVC Main, fast). */
    private fun convertJpg(
        jpg: DocumentFile, parent: DocumentFile, targetName: String,
        srcBytes: ByteArray, exifTiff: ByteArray?, snapshot: MediaStoreSync.Snapshot,
        existing: DocumentFile?
    ): Result {
        val bitmap = try {
            BitmapFactory.decodeByteArray(srcBytes, 0, srcBytes.size, BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inSampleSize = 1
            })
        } catch (e: Exception) { return Result.Failed("解码失败: ${e.message}") }
            ?: return Result.Failed("解码失败")

        try {
            existing?.delete()
            val hwBytes = try { encodeViaHeifWriter(bitmap) }
                catch (e: Exception) { return Result.Failed("HeifWriter 编码失败: ${e.message}") }
            val finalBytes = if (exifTiff != null && exifTiff.isNotEmpty()) {
                try { HeifExifInjector.inject(hwBytes, exifTiff) }
                catch (e: Exception) {
                    com.wjbyt.j2h.work.ConversionForegroundService
                        .appendLog("  · EXIF 注入异常（保留无 EXIF 输出）: ${e.message}")
                    hwBytes
                }
            } else hwBytes
            return writeAndVerify("HeifWriter-8bit", finalBytes, parent, targetName, jpg, snapshot)
                ?: Result.Failed("HEIC 解码失败（图片可能超出本机 HEVC 解码能力）",
                                 keepOriginal = true)
        } finally { bitmap.recycle() }
    }

    /** DNG → 10-bit HEIC via MediaCodec HEVC Main10 + MediaMuxer HEIF. */
    private fun convertDng(
        jpg: DocumentFile, parent: DocumentFile, targetName: String,
        srcBytes: ByteArray, snapshot: MediaStoreSync.Snapshot, existing: DocumentFile?
    ): Result {
        if (!TenBitEncoder.isAvailable()) {
            return Result.Failed("10-bit 编码器不可用（native lib 未加载）", keepOriginal = true)
        }
        val source = ImageDecoder.createSource(java.nio.ByteBuffer.wrap(srcBytes))
        val bitmap = try {
            ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false
                // Display P3 — wider than sRGB (covers ~96% of camera gamut), narrower
                // than BT.2020 (so non-color-managed viewers don't see washed-out
                // results from over-wide primaries). This is what iPhone/Apple HEIC
                // uses for SDR wide-gamut photos.
                decoder.setTargetColorSpace(android.graphics.ColorSpace.get(
                    android.graphics.ColorSpace.Named.DISPLAY_P3))
            }
        } catch (e: Exception) { return Result.Failed("DNG 解码失败: ${e.message}") }

        if (bitmap.config != Bitmap.Config.RGBA_F16) {
            // Some devices may downgrade. Convert to RGBA_F16 explicitly.
            val converted = try {
                bitmap.copy(Bitmap.Config.RGBA_F16, false)
            } catch (e: Exception) { null }
            bitmap.recycle()
            if (converted == null) return Result.Failed("解码后无法获得 RGBA_F16 位图")
            return finishDng(converted, jpg, parent, targetName, snapshot, existing)
        }
        return finishDng(bitmap, jpg, parent, targetName, snapshot, existing)
    }

    private fun finishDng(
        bitmap: Bitmap, jpg: DocumentFile, parent: DocumentFile, targetName: String,
        snapshot: MediaStoreSync.Snapshot, existing: DocumentFile?
    ): Result {
        try {
            val w = bitmap.width and 1.inv()
            val h = bitmap.height and 1.inv()
            val even = if (w == bitmap.width && h == bitmap.height) bitmap
                       else Bitmap.createBitmap(bitmap, 0, 0, w, h)
            try {
                existing?.delete()
                val tmp = File.createTempFile("j2h_10b_", ".heic", context.cacheDir)
                try {
                    val err = TenBitEncoder.encode(
                        even, tmp.absolutePath, qualityHint = quality,
                        // Display P3 D65 SDR — primaries=12, transfer=1 (BT.709 SDR
                        // gamma ~ sRGB, safe for non-HDR viewers), matrix=1 (BT.709
                        // YUV math). Native YUV conversion uses BT.709 luma weights.
                        colourPrimaries = 12, transferCharacteristics = 1, matrixCoefficients = 1
                    )
                    if (err != null) return Result.Failed("10-bit 编码失败: $err",
                                                          keepOriginal = true)

                    // Diagnostics: file size + first 32 bytes (so we can see if it's a
                    // valid HEIF starting with 'ftyp' or something else entirely).
                    val bytes = tmp.readBytes()
                    val head = bytes.take(32).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
                    val ascii = bytes.take(32).joinToString("") {
                        val c = it.toInt() and 0xFF
                        if (c in 0x20..0x7E) c.toChar().toString() else "."
                    }
                    com.wjbyt.j2h.work.ConversionForegroundService
                        .appendLog("  · 10bit 输出 ${bytes.size}B, 头部: $head ($ascii)")

                    val ok = writeAndVerify(
                        "MediaCodec-Main10", bytes, parent, targetName, jpg, snapshot
                    )
                    if (ok != null) return ok

                    // Verify failed: keep the suspect output as .untrusted.heic so the user
                    // can pull it off the device and run mediainfo / a HEIF parser on it.
                    val baseName = targetName.removeSuffix(".heic")
                    val untrusted = parent.findFile("$baseName.10bit.untrusted.heic")
                    untrusted?.delete()
                    val keep = parent.createFile("image/heic", "$baseName.10bit.untrusted.heic")
                    if (keep != null) {
                        try {
                            context.contentResolver.openOutputStream(keep.uri, "w")
                                ?.use { it.write(bytes) }
                            com.wjbyt.j2h.work.ConversionForegroundService
                                .appendLog("    → 失败的 10-bit HEIC 已保留为 $baseName.10bit.untrusted.heic")
                        } catch (_: Exception) {}
                    }
                    return Result.Failed("10-bit HEIC 解码失败", keepOriginal = true)
                } finally { tmp.delete() }
            } finally {
                if (even !== bitmap) even.recycle()
            }
        } finally { bitmap.recycle() }
    }

    /**
     * Persist [bytes] as the target HEIC, decode-verify, push metadata, delete source.
     * Returns Ok on success, null if the bytes don't decode (caller should try the next
     * encoder), or a non-null Failed if writing itself was the problem.
     */
    private fun writeAndVerify(
        label: String, bytes: ByteArray,
        parent: DocumentFile, targetName: String,
        srcJpg: DocumentFile, snapshot: MediaStoreSync.Snapshot
    ): Result.Ok? {
        val out = parent.createFile("image/heic", targetName)
            ?: return null.also { com.wjbyt.j2h.work.ConversionForegroundService
                .appendLog("  · 无法创建输出文件") }
        try {
            context.contentResolver.openOutputStream(out.uri, "w")?.use { it.write(bytes) }
                ?: run { out.delete(); return null }
        } catch (e: Exception) {
            out.delete()
            com.wjbyt.j2h.work.ConversionForegroundService.appendLog("  · 写入失败: ${e.message}")
            return null
        }

        val decodeErr = verifyDecodable(out)
        if (decodeErr != null) {
            out.delete()
            com.wjbyt.j2h.work.ConversionForegroundService
                .appendLog("  · $label 输出不可解码（$decodeErr）")
            return null
        }

        val metaNote = MediaStoreSync.apply(context, out.uri, snapshot)
        val outSize = out.length()
        val srcSize = srcJpg.length()
        if (!srcJpg.delete()) {
            com.wjbyt.j2h.work.ConversionForegroundService
                .appendLog("  · 已转换但删除原 JPG 失败 — 手动删除")
        }
        return Result.Ok(targetName, srcSize, outSize, "$label$metaNote")
    }

    private fun encodeViaHeifWriter(bitmap: Bitmap): ByteArray {
        val tmp = File.createTempFile("j2h_hw_", ".heic", context.cacheDir)
        try {
            val w = HeifWriter.Builder(
                tmp.absolutePath, bitmap.width, bitmap.height, HeifWriter.INPUT_MODE_BITMAP
            ).setQuality(quality).setMaxImages(1).build()
            try { w.start(); w.addBitmap(bitmap); w.stop(30_000L) } finally { w.close() }
            return tmp.readBytes()
        } finally { tmp.delete() }
    }

    /** Returns null if the file decodes; an error string otherwise. */
    private fun verifyDecodable(heic: DocumentFile): String? {
        val tmp = File.createTempFile("verify_", ".heic", context.cacheDir)
        try {
            try {
                context.contentResolver.openInputStream(heic.uri)?.use { input ->
                    tmp.outputStream().use { input.copyTo(it) }
                }
                if (tmp.length() == 0L) return "新文件为空"
            } catch (e: Exception) { return "读回失败: ${e.message}" }

            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(tmp.absolutePath, opts)
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return "无法解码新 HEIC 的尺寸"

            // Down-sample so we don't allocate ~150 MB just to test. End-to-end decode
            // is required: a truncated file can still report dimensions but fail the
            // actual bitstream decode.
            val sample = generateSequence(1) { it * 2 }
                .first { (opts.outWidth.toLong() * opts.outHeight) / (it.toLong() * it) <= 1_000_000 }
            val test = try {
                BitmapFactory.decodeFile(tmp.absolutePath, BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.RGB_565
                })
            } catch (e: Exception) { return "解码异常: ${e.message}" }
            if (test == null) return "解码返回空，bitstream 可能损坏"
            test.recycle()
            return null
        } finally { tmp.delete() }
    }
}
