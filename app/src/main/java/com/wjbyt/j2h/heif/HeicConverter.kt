package com.wjbyt.j2h.heif

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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

        val existing = parent.findFile(targetName)
        if (existing != null && existing.length() > 0) return Result.Skipped("$targetName 已存在")

        val snapshot = MediaStoreSync.readSourceJpg(context, jpg)

        val jpgBytes = try {
            context.contentResolver.openInputStream(jpg.uri)?.use { it.readBytes() }
                ?: return Result.Failed("无法打开 JPG")
        } catch (e: Exception) { return Result.Failed("读取失败: ${e.message}") }

        val exifTiff = try {
            com.wjbyt.j2h.exif.JpegExifExtractor.extractTiff(java.io.ByteArrayInputStream(jpgBytes))
        } catch (_: Exception) { null }

        val bitmap = try {
            BitmapFactory.decodeByteArray(jpgBytes, 0, jpgBytes.size, BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inSampleSize = 1
            })
        } catch (e: Exception) { return Result.Failed("解码失败: ${e.message}") }
            ?: return Result.Failed("解码失败")

        try {
            // Encoder chain: libheif first (better compression, full EXIF in file), fall
            // back to HeifWriter (auto-tiles via hardware HEVC, decodable for any size).
            existing?.delete()
            if (LibheifEncoder.isAvailable()) {
                val bytes = runCatching { encodeViaLibheif(bitmap, exifTiff) }
                    .onFailure { com.wjbyt.j2h.work.ConversionForegroundService
                        .appendLog("  · libheif 编码异常: ${it.message}") }
                    .getOrNull()
                if (bytes != null) {
                    writeAndVerify("libheif", bytes, parent, targetName, jpg, snapshot)?.let { return it }
                }
            }
            val hwBytes = try { encodeViaHeifWriter(bitmap) }
                catch (e: Exception) { return Result.Failed("HeifWriter 编码失败: ${e.message}") }
            return writeAndVerify("HeifWriter", hwBytes, parent, targetName, jpg, snapshot)
                ?: Result.Failed("所有编码路径都无法在本机解码（图片可能超出 HEVC 能力）",
                                 keepOriginal = true)
        } finally {
            bitmap.recycle()
        }
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

    private fun encodeViaLibheif(bitmap: Bitmap, exifTiff: ByteArray?): ByteArray {
        val tmp = File.createTempFile("j2h_lh_", ".heic", context.cacheDir)
        try {
            val err = LibheifEncoder.encode(bitmap, exifTiff, tmp.absolutePath, quality)
            if (err != null) throw RuntimeException("libheif: $err")
            return tmp.readBytes()
        } finally { tmp.delete() }
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
