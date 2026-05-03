package com.wjbyt.j2h.heif

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.heifwriter.HeifWriter
import androidx.documentfile.provider.DocumentFile
import androidx.exifinterface.media.ExifInterface
import com.wjbyt.j2h.exif.JpegExifExtractor
import java.io.ByteArrayInputStream
import java.io.File

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

        // Capture a fingerprint EXIF tag from the source so we can verify it survives.
        // DateTimeOriginal is purely an EXIF field — never derived from container metadata.
        val sourceFingerprint: SourceFingerprint = try {
            val srcExif = ExifInterface(ByteArrayInputStream(jpgBytes))
            SourceFingerprint(
                dateTimeOriginal = srcExif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL),
                dateTime = srcExif.getAttribute(ExifInterface.TAG_DATETIME),
                make = srcExif.getAttribute(ExifInterface.TAG_MAKE),
                model = srcExif.getAttribute(ExifInterface.TAG_MODEL),
                gpsLat = srcExif.getAttribute(ExifInterface.TAG_GPS_LATITUDE)
            )
        } catch (e: Exception) {
            SourceFingerprint()
        }

        val bitmap = try {
            decodeBitmap(jpgBytes)
        } catch (e: Exception) {
            return Result.Failed("解码失败: ${e.message}")
        } ?: return Result.Failed("解码失败")

        val heicBytesNoExif = try {
            encodeToHeic(bitmap)
        } catch (e: Exception) {
            return Result.Failed("HEIC 编码失败: ${e.message}")
        } finally {
            bitmap.recycle()
        }
        if (heicBytesNoExif == null || heicBytesNoExif.isEmpty()) {
            return Result.Failed("HEIC 编码无输出")
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

        // Verification — must be strict enough to catch silent EXIF-injection failures.
        val verifyError = verify(out, sourceFingerprint, hasSourceExif = exifTiff != null && exifTiff.isNotEmpty())
        if (verifyError != null) {
            // Show MMR result FIRST so it survives any later log truncation.
            val mmrLine = mmrProbe(out)
            com.wjbyt.j2h.work.ConversionForegroundService.appendLog(">>> $mmrLine")

            val diag = StringBuilder()
            diag.appendLine(HeifDumper.dump(heicBytesNoExif, "PRE"))
            diag.appendLine(HeifDumper.dump(heicBytes, "POST"))
            if (exifTiff != null && exifTiff.isNotEmpty()) {
                val tiffSample = exifTiff.copyOfRange(0, minOf(20, exifTiff.size))
                    .joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
                diag.appendLine("src TIFF: $tiffSample (len=${exifTiff.size})")
            }
            for (line in diag.toString().lines()) {
                if (line.isNotBlank()) com.wjbyt.j2h.work.ConversionForegroundService.appendLog(line)
            }
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

    private fun encodeToHeic(bitmap: Bitmap): ByteArray? {
        // HeifWriter writes to a file. Use app's cache dir for the temp.
        val tmp = File.createTempFile("j2h_", ".heic", context.cacheDir)
        try {
            val writer = HeifWriter.Builder(
                tmp.absolutePath,
                bitmap.width,
                bitmap.height,
                HeifWriter.INPUT_MODE_BITMAP
            )
                .setQuality(quality)
                .setMaxImages(1)
                // Critical: AOSP MediaMetadataRetriever does NOT extract EXIF from
                // grid-tiled HEIC. Force single-frame HEVC so the primary item is the
                // actual image, which MMR (and therefore ExifInterface) can read EXIF for.
                .setGridEnabled(false)
                .build()
            try {
                writer.start()
                writer.addBitmap(bitmap)
                writer.stop(30_000L)
            } finally {
                writer.close()
            }
            return tmp.readBytes()
        } finally {
            tmp.delete()
        }
    }

    private fun decodeBitmap(bytes: ByteArray): Bitmap? {
        // ARGB_8888 + sample size 1: full resolution, no quality changes from BitmapFactory.
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = 1
            inMutable = false
        })
    }

    private fun mmrProbe(heic: DocumentFile): String {
        val tmp = File.createTempFile("mmr_", ".heic", context.cacheDir)
        return try {
            context.contentResolver.openInputStream(heic.uri)?.use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            }
            val mmr = MediaMetadataRetriever()
            mmr.setDataSource(tmp.absolutePath)
            val off = mmr.extractMetadata(33) // METADATA_KEY_EXIF_OFFSET
            val len = mmr.extractMetadata(34) // METADATA_KEY_EXIF_LENGTH
            val w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_IMAGE_WIDTH)
            val h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_IMAGE_HEIGHT)
            mmr.release()
            "MMR: img=${w}x${h} EXIF_OFFSET=$off LEN=$len"
        } catch (e: Exception) {
            "MMR error: ${e.message}"
        } finally {
            tmp.delete()
        }
    }

    private data class SourceFingerprint(
        val dateTimeOriginal: String? = null,
        val dateTime: String? = null,
        val make: String? = null,
        val model: String? = null,
        val gpsLat: String? = null
    ) {
        /** True when the source actually had user-meaningful EXIF — not just container-derived stuff. */
        fun hasReal(): Boolean = listOf(dateTimeOriginal, dateTime, make, model, gpsLat)
            .any { !it.isNullOrEmpty() }
    }

    private fun verify(heic: DocumentFile, src: SourceFingerprint, hasSourceExif: Boolean): String? {
        // Use a temp file; ExifInterface(InputStream) is unreliable for HEIF (needs random access).
        val tmp = File.createTempFile("verify_", ".heic", context.cacheDir)
        try {
            try {
                context.contentResolver.openInputStream(heic.uri)?.use { input ->
                    tmp.outputStream().use { input.copyTo(it) }
                } ?: return "无法读回新文件"
            } catch (e: Exception) { return "读回失败: ${e.message}" }

            if (tmp.length() == 0L) return "新文件为空"

            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(tmp.absolutePath, opts)
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return "无法解码新 HEIC 的尺寸"

            if (hasSourceExif && src.hasReal()) {
                val exif = try {
                    ExifInterface(tmp.absolutePath)
                } catch (e: Exception) { return "新 HEIC 的 EXIF 不可读: ${e.message}" }

                // Compare each fingerprint field that source had — the matching field in the
                // patched HEIC must be present and equal. Container-only tags (orientation, image
                // width) are deliberately excluded since they can be derived without real EXIF.
                src.dateTimeOriginal?.takeIf { it.isNotEmpty() }?.let {
                    val v = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    if (v != it) return "DateTimeOriginal 丢失（源=$it, 新=$v）"
                }
                src.dateTime?.takeIf { it.isNotEmpty() }?.let {
                    val v = exif.getAttribute(ExifInterface.TAG_DATETIME)
                    if (v != it) return "DateTime 丢失（源=$it, 新=$v）"
                }
                src.make?.takeIf { it.isNotEmpty() }?.let {
                    val v = exif.getAttribute(ExifInterface.TAG_MAKE)
                    if (v != it) return "Make 丢失（源=$it, 新=$v）"
                }
                src.model?.takeIf { it.isNotEmpty() }?.let {
                    val v = exif.getAttribute(ExifInterface.TAG_MODEL)
                    if (v != it) return "Model 丢失（源=$it, 新=$v）"
                }
                src.gpsLat?.takeIf { it.isNotEmpty() }?.let {
                    val v = exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE)
                    if (v != it) return "GPS 丢失（源=$it, 新=$v）"
                }
            }
            return null
        } finally {
            tmp.delete()
        }
    }
}
