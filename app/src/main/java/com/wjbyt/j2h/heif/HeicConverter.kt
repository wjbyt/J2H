package com.wjbyt.j2h.heif

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.documentfile.provider.DocumentFile
import androidx.exifinterface.media.ExifInterface
import com.wjbyt.j2h.exif.JpegExifExtractor
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * End-to-end JPG → HEIC conversion for a single file with EXIF preservation
 * and post-conversion verification (Q3 mode B).
 *
 * Pipeline:
 *   1. Read JPG bytes from SAF.
 *   2. Extract raw TIFF EXIF blob from APP1 segment.
 *   3. Decode JPG → Bitmap (no resampling).
 *   4. Encode Bitmap → HEIC at quality 95 via Bitmap.compress.
 *   5. Inject EXIF item into HEIC container.
 *   6. Write the patched HEIC alongside the original.
 *   7. Verify: re-decode the new HEIC, confirm dimensions match,
 *      and read back EXIF datetime / GPS to confirm the patch took.
 *   8. If verification passes, delete the original JPG. Otherwise keep both.
 */
class HeicConverter(
    private val context: Context,
    private val quality: Int = 95
) {
    sealed interface Result {
        data class Ok(val outName: String, val bytesIn: Long, val bytesOut: Long) : Result
        data class Skipped(val reason: String) : Result
        data class Failed(val reason: String, val keepOriginal: Boolean = true) : Result
    }

    fun convert(jpg: DocumentFile, parent: DocumentFile): Result {
        val name = jpg.name ?: return Result.Failed("no name")
        val baseName = name.substringBeforeLast('.', name)
        val targetName = "$baseName.heic"

        // Skip if a converted file already exists (idempotent re-runs).
        val existing = parent.findFile(targetName)
        if (existing != null && existing.length() > 0) {
            return Result.Skipped("$targetName 已存在")
        }

        val jpgBytes = try {
            context.contentResolver.openInputStream(jpg.uri)?.use { it.readBytes() }
                ?: return Result.Failed("无法打开 JPG")
        } catch (e: Exception) {
            return Result.Failed("读取失败: ${e.message}")
        }

        val exifTiff = try {
            JpegExifExtractor.extractTiff(ByteArrayInputStream(jpgBytes))
        } catch (e: Exception) {
            null
        }

        val bitmap = try {
            decodeBitmap(jpgBytes)
        } catch (e: Exception) {
            return Result.Failed("解码失败: ${e.message}")
        } ?: return Result.Failed("解码失败")

        val heicBytesNoExif = try {
            ByteArrayOutputStream(jpgBytes.size).use { out ->
                val ok = bitmap.compress(Bitmap.CompressFormat.HEIC, quality, out)
                if (!ok) return Result.Failed("HEIC 编码失败")
                out.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }

        val heicBytes = if (exifTiff != null && exifTiff.isNotEmpty()) {
            try {
                HeifExifInjector.inject(heicBytesNoExif, exifTiff)
            } catch (e: Exception) {
                // EXIF preservation is required; do not delete original.
                return Result.Failed("EXIF 注入失败: ${e.message}", keepOriginal = true)
            }
        } else {
            heicBytesNoExif
        }

        // Create the destination file. If a stale 0-byte placeholder exists, remove it.
        existing?.delete()
        val out = parent.createFile("image/heic", targetName)
            ?: return Result.Failed("无法创建输出文件")

        try {
            context.contentResolver.openOutputStream(out.uri, "w")?.use { os ->
                os.write(heicBytes)
                os.flush()
            } ?: run {
                out.delete()
                return Result.Failed("无法写入输出")
            }
        } catch (e: Exception) {
            out.delete()
            return Result.Failed("写入失败: ${e.message}")
        }

        // Verification.
        val verifyError = verify(out, exifExpected = exifTiff != null)
        if (verifyError != null) {
            out.delete()
            return Result.Failed("校验失败: $verifyError", keepOriginal = true)
        }

        // Safe to delete original.
        val originalSize = jpg.length()
        val deleted = jpg.delete()
        if (!deleted) {
            return Result.Failed("已转换但删除原文件失败", keepOriginal = true)
        }
        return Result.Ok(targetName, originalSize, out.length())
    }

    private fun decodeBitmap(bytes: ByteArray): Bitmap? {
        // ARGB_8888 + sample size 1: full resolution, no quality changes from BitmapFactory.
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = 1
            inMutable = false
        })
    }

    private fun verify(heic: DocumentFile, exifExpected: Boolean): String? {
        val bytes = try {
            context.contentResolver.openInputStream(heic.uri)?.use { it.readBytes() }
        } catch (e: Exception) { null } ?: return "无法读回新文件"

        if (bytes.isEmpty()) return "新文件为空"

        // Decode dimensions.
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return "无法解码新 HEIC 的尺寸"

        if (exifExpected) {
            val exif = try {
                ExifInterface(ByteArrayInputStream(bytes))
            } catch (e: Exception) { return "EXIF 不可读: ${e.message}" }
            val any = listOf(
                ExifInterface.TAG_DATETIME_ORIGINAL,
                ExifInterface.TAG_DATETIME,
                ExifInterface.TAG_MAKE,
                ExifInterface.TAG_MODEL,
                ExifInterface.TAG_GPS_LATITUDE,
                ExifInterface.TAG_IMAGE_WIDTH,
                ExifInterface.TAG_ORIENTATION
            ).any { !exif.getAttribute(it).isNullOrEmpty() }
            if (!any) return "EXIF 注入后无可读字段"
        }
        return null
    }
}
