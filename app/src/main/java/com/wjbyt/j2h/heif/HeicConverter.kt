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

            // Generate a small thumbnail item — vivo gallery wants this present
            // before it surfaces EXIF for a HEIC.
            val thumbnail = try {
                ThumbnailGenerator.generate(context, bitmap, maxDim = 320)
            } catch (e: Exception) {
                com.wjbyt.j2h.work.ConversionForegroundService
                    .appendLog("  · 缩略图生成失败（继续）: ${e.message}")
                null
            }

            val finalBytes = if (exifTiff != null && exifTiff.isNotEmpty()) {
                try { HeifExifInjector.inject(hwBytes, exifTiff, thumbnail) }
                catch (e: Exception) {
                    com.wjbyt.j2h.work.ConversionForegroundService
                        .appendLog("  · EXIF/缩略图 注入异常: ${e.message}")
                    hwBytes
                }
            } else hwBytes

            // Diagnostic: print top-level box order so we can verify on-device
            // that the mdat-before-meta reorder actually applied. Samsung's
            // HEIC is ftyp/mdat/meta/free; HeifWriter raw output is
            // ftyp/meta/free/mdat. After our injector + reorder it should
            // match Samsung's order.
            describeTopLevel(finalBytes)?.let {
                com.wjbyt.j2h.work.ConversionForegroundService
                    .appendLog("  · 最终顶层布局: $it")
            }
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
            val pixels = w.toLong() * h
            com.wjbyt.j2h.work.ConversionForegroundService.appendLog(
                "  · 解码后尺寸 ${w}x${h} ≈ ${pixels / 1_000_000} MP"
            )
            val even = if (w == bitmap.width && h == bitmap.height) bitmap
                       else Bitmap.createBitmap(bitmap, 0, 0, w, h)
            try {
                existing?.delete()
                val tmp = File.createTempFile("j2h_10b_", ".heic", context.cacheDir)
                try {
                    // Try single-frame Main10 first; if the encoder rejects the size
                    // (>~35MP), fall back to grid-tile encoding.
                    var err = TenBitEncoder.encode(
                        even, tmp.absolutePath, qualityHint = quality,
                        colourPrimaries = 12, transferCharacteristics = 1, matrixCoefficients = 1
                    )
                    if (err != null && err.contains("不支持")) {
                        com.wjbyt.j2h.work.ConversionForegroundService.appendLog(
                            "  · 单帧尺寸超限，切换到瓦片网格编码"
                        )
                        err = TenBitGridEncoder.encode(
                            even, tmp.absolutePath, qualityHint = quality,
                            tileW = 2048, tileH = 2048,
                            colourPrimaries = 12, transferCharacteristics = 1, matrixCoefficients = 1
                        )
                    }
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
        // Verify bytes decode before writing anywhere — saves a round-trip
        // through the filesystem on broken outputs.
        verifyDecodableBytes(bytes)?.let { reason ->
            com.wjbyt.j2h.work.ConversionForegroundService
                .appendLog("  · $label 输出不可解码（$reason）")
            return null
        }

        // Try MediaStore-owned write first — when WE create the row via
        // ContentResolver.insert(), MediaStore records owner_package_name=
        // com.wjbyt.j2h, which lets us set DATE_TAKEN / GPS / make / model
        // at insert time AND have those values stick. The SAF write path
        // (parent.createFile) gives the row to DocumentsUI; vivo silently
        // rejects all our updates against DocumentsUI-owned rows AND its
        // MediaScanner doesn't extract EXIF on the way in.
        val msResult = tryMediaStoreWrite(bytes, targetName, parent, snapshot)
        val outUri: android.net.Uri
        val outDoc: DocumentFile?
        val mediaPath: String?
        if (msResult != null) {
            outUri = msResult.first
            outDoc = null
            mediaPath = msResult.second
            com.wjbyt.j2h.work.ConversionForegroundService.appendLog(
                "  · MediaStore 写入成功（我们拥有该行）"
            )
        } else {
            // Fallback: SAF write (legacy, ownership stays with DocumentsUI).
            val out = parent.createFile("image/heic", targetName)
                ?: return null.also { com.wjbyt.j2h.work.ConversionForegroundService
                    .appendLog("  · 无法创建输出文件") }
            try {
                context.contentResolver.openOutputStream(out.uri, "w")?.use { it.write(bytes) }
                    ?: run { out.delete(); return null }
            } catch (e: Exception) {
                out.delete()
                com.wjbyt.j2h.work.ConversionForegroundService
                    .appendLog("  · 写入失败: ${e.message}")
                return null
            }
            outUri = out.uri
            outDoc = out
            mediaPath = null
        }

        // Bytes were already verified above; no need to re-verify on disk.
        val metaNote = if (mediaPath != null) {
            // MediaStore path: snapshot already merged into the row at insert
            // time; just set mtime so 时间 displays correctly in vivo gallery.
            val mtimeOk = snapshot.dateTakenMillis?.let { ts ->
                if (mediaPath.isBlank()) false
                else try { java.io.File(mediaPath).setLastModified(ts) }
                     catch (_: Exception) { false }
            } ?: false
            com.wjbyt.j2h.work.ConversionForegroundService.appendLog(
                "  · mtime 设置: ${if (mtimeOk) "ok" else "fail"} (path=$mediaPath)"
            )
            " [meta: ms-insert]"
        } else {
            MediaStoreSync.apply(context, outUri, snapshot)
        }
        val outSize = outDoc?.length() ?: (mediaPath?.let { java.io.File(it).length() } ?: 0L)
        val srcSize = srcJpg.length()
        if (!srcJpg.delete()) {
            com.wjbyt.j2h.work.ConversionForegroundService
                .appendLog("  · 已转换但删除原 JPG 失败 — 手动删除")
        }
        return Result.Ok(targetName, srcSize, outSize, "$label$metaNote")
    }

    /**
     * Returns (mediaUri, absolutePath) on success, null when MediaStore
     * insert isn't possible for this destination (e.g. user picked a folder
     * outside the standard public dirs that MediaStore manages).
     */
    private fun tryMediaStoreWrite(
        bytes: ByteArray, targetName: String, parent: DocumentFile,
        snap: MediaStoreSync.Snapshot
    ): Pair<android.net.Uri, String>? {
        val relPath = relativePathForMediaStore(parent.uri) ?: return null
        val resolver = context.contentResolver
        val collection = android.provider.MediaStore.Images.Media
            .getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)

        // If there's already a file with this name at this path (e.g. user
        // re-runs conversion), MediaStore will create "$name (1).heic" by
        // default. With MANAGE_EXTERNAL_STORAGE we can clear the old row
        // first so the new file lands at the expected name.
        try {
            val existSel = "${android.provider.MediaStore.MediaColumns.DISPLAY_NAME}=? AND " +
                           "${android.provider.MediaStore.MediaColumns.RELATIVE_PATH}=?"
            resolver.delete(collection, existSel, arrayOf(targetName, relPath))
        } catch (_: Exception) {}

        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, targetName)
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/heic")
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, relPath)
            put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
            snap.dateTakenMillis?.let {
                put(android.provider.MediaStore.Images.Media.DATE_TAKEN, it)
                put(android.provider.MediaStore.Images.Media.DATE_MODIFIED, it / 1000)
                put(android.provider.MediaStore.Images.Media.DATE_ADDED, it / 1000)
            }
            // Best-effort vendor columns; ignored by AOSP, may stick on vivo.
            snap.gpsLat?.let { put("latitude", it) }
            snap.gpsLon?.let { put("longitude", it) }
        }
        val uri = try { resolver.insert(collection, values) } catch (e: Exception) {
            com.wjbyt.j2h.work.ConversionForegroundService.appendLog(
                "  · MediaStore.insert 失败: ${e.message?.take(80)}"
            )
            null
        } ?: return null

        // If a file with the same DISPLAY_NAME already existed, MediaStore
        // returns a URI for it; ensure we're starting from a clean state.
        try {
            resolver.openOutputStream(uri, "w")?.use { it.write(bytes) }
                ?: throw IllegalStateException("openOutputStream returned null")
        } catch (e: Exception) {
            try { resolver.delete(uri, null, null) } catch (_: Exception) {}
            com.wjbyt.j2h.work.ConversionForegroundService.appendLog(
                "  · MediaStore 写入失败: ${e.message?.take(80)}"
            )
            return null
        }

        // Publish (clear IS_PENDING) so the gallery picks it up.
        val publishValues = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
        }
        try { resolver.update(uri, publishValues, null, null) }
        catch (e: Exception) {
            com.wjbyt.j2h.work.ConversionForegroundService.appendLog(
                "  · MediaStore 发布失败: ${e.message?.take(60)}"
            )
        }

        // Post-publish: try every plausible column name for shoot-time /
        // GPS / camera identity, one at a time. We OWN the row now, so any
        // column the schema actually accepts will land. Log per-column
        // result so the next iteration can lock in the working names.
        val updateAttempts = mutableListOf<Pair<String, Any>>()
        snap.dateTakenMillis?.let {
            updateAttempts += "datetaken" to it
            updateAttempts += "date_taken" to it
            updateAttempts += "inferred_date" to it
            updateAttempts += "datetime_original" to it
            updateAttempts += "exif_datetime_original" to it
            updateAttempts += "exif_datetime" to it
        }
        snap.gpsLat?.let {
            updateAttempts += "latitude" to it
            updateAttempts += "gps_latitude" to it
            updateAttempts += "exif_gps_latitude" to it
        }
        snap.gpsLon?.let {
            updateAttempts += "longitude" to it
            updateAttempts += "gps_longitude" to it
            updateAttempts += "exif_gps_longitude" to it
        }
        snap.make?.takeIf { it.isNotBlank() }?.let {
            updateAttempts += "make" to it
            updateAttempts += "manufacturer" to it
            updateAttempts += "exif_make" to it
            updateAttempts += "camera_make" to it
        }
        snap.model?.takeIf { it.isNotBlank() }?.let {
            updateAttempts += "model" to it
            updateAttempts += "device" to it
            updateAttempts += "exif_model" to it
            updateAttempts += "camera_model" to it
        }
        val accepted = mutableListOf<String>()
        val rejected = mutableListOf<String>()
        for ((col, v) in updateAttempts) {
            val cv = android.content.ContentValues().apply {
                when (v) {
                    is Long -> put(col, v)
                    is Double -> put(col, v)
                    is String -> put(col, v)
                    else -> put(col, v.toString())
                }
            }
            try {
                val n = resolver.update(uri, cv, null, null)
                if (n > 0) accepted += col else rejected += "$col(0)"
            } catch (e: Exception) {
                rejected += "$col(${e.message?.take(20)})"
            }
        }
        com.wjbyt.j2h.work.ConversionForegroundService.appendLog(
            "  · 列写入: 接受=${accepted.joinToString(",")}; 拒绝=${rejected.joinToString(",")}"
        )

        // Resolve to an absolute path so callers can setLastModified.
        // MediaColumns.DATA is restricted on Android 11+ even for owned
        // files; compute the path deterministically from RELATIVE_PATH +
        // DISPLAY_NAME. Falls back to DATA if available.
        val rootPath = android.os.Environment.getExternalStorageDirectory().absolutePath
        val computedPath = "$rootPath/${relPath.trimEnd('/')}/$targetName"
        val absPath = try {
            resolver.query(uri, arrayOf(android.provider.MediaStore.MediaColumns.DATA),
                           null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (_: Exception) { null } ?: computedPath

        // Diagnostic dump: what does our row actually look like after
        // insert+publish? Compare against a Samsung HEIC's row (which
        // shows full make/model/GPS in vivo gallery) — that will reveal
        // the vendor column names we must hit.
        dumpRow(uri, "我们刚 insert 的行")
        probeAnotherPopulatedRow(resolver, uri)
        return uri to absPath
    }

    /** Print every non-empty column of [uri] to the log. */
    private fun dumpRow(uri: android.net.Uri, label: String) {
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (!c.moveToFirst()) {
                    com.wjbyt.j2h.work.ConversionForegroundService.appendLog(
                        "  · $label: 行不存在")
                    return
                }
                val sb = StringBuilder()
                for (i in 0 until c.columnCount) {
                    val name = c.getColumnName(i)
                    val v = try { c.getString(i) } catch (_: Exception) { null }
                    if (!v.isNullOrBlank() && v != "0") {
                        sb.append(name).append('=').append(v.take(40)).append("; ")
                    }
                }
                com.wjbyt.j2h.work.ConversionForegroundService.appendLog(
                    "  · $label: $sb")
            }
        } catch (e: Exception) {
            com.wjbyt.j2h.work.ConversionForegroundService.appendLog(
                "  · $label 查询失败: ${e.message?.take(60)}")
        }
    }

    /**
     * Find a different image row that vivo's MediaScanner DID populate
     * with EXIF (i.e. has a latitude or make-like value). Dumping it
     * exposes the exact vendor column names we need to match.
     */
    private fun probeAnotherPopulatedRow(
        resolver: android.content.ContentResolver, ourUri: android.net.Uri
    ) {
        try {
            val collection = android.provider.MediaStore.Images.Media
                .getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
            // Pick any image NOT owned by us — that's a Samsung-style
            // file that MediaScanner extracted EXIF from natively.
            // LIMIT inside the orderBy isn't supported on Android 11+;
            // pass it via a Bundle instead.
            val sel = "owner_package_name IS NULL OR owner_package_name != ?"
            val args = arrayOf(context.packageName)
            val queryArgs = android.os.Bundle().apply {
                putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, sel)
                putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
                putString(android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                    "${android.provider.MediaStore.MediaColumns.DATE_MODIFIED} DESC")
                putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, 20)
            }
            resolver.query(collection, null, queryArgs, null)
                ?.use { c ->
                    var picked = false
                    while (c.moveToNext()) {
                        // Has any non-empty geo/make-like column?
                        var interesting = false
                        for (i in 0 until c.columnCount) {
                            val name = c.getColumnName(i).lowercase()
                            if (name in setOf("latitude", "longitude", "make",
                                              "manufacturer", "datetaken")) {
                                val v = try { c.getString(i) } catch (_: Exception) { null }
                                if (!v.isNullOrBlank() && v != "0" && v != "0.0") {
                                    interesting = true; break
                                }
                            }
                        }
                        if (!interesting) continue
                        picked = true
                        val sb = StringBuilder()
                        for (i in 0 until c.columnCount) {
                            val name = c.getColumnName(i)
                            val v = try { c.getString(i) } catch (_: Exception) { null }
                            if (!v.isNullOrBlank() && v != "0" && v != "0.0") {
                                sb.append(name).append('=').append(v.take(35)).append("; ")
                            }
                        }
                        com.wjbyt.j2h.work.ConversionForegroundService.appendLog(
                            "  · 别人的行 (供参考): $sb")
                        break
                    }
                    if (!picked) {
                        com.wjbyt.j2h.work.ConversionForegroundService.appendLog(
                            "  · 找不到第三方写入且包含 EXIF 的图像行")
                    }
                }
        } catch (e: Exception) {
            com.wjbyt.j2h.work.ConversionForegroundService.appendLog(
                "  · 探针查询失败: ${e.message?.take(60)}")
        }
    }

    /** "Download/照片/" from a SAF tree URI for primary external storage. */
    private fun relativePathForMediaStore(parentUri: android.net.Uri): String? {
        return try {
            val docId = try {
                android.provider.DocumentsContract.getTreeDocumentId(parentUri)
            } catch (_: Exception) {
                android.provider.DocumentsContract.getDocumentId(parentUri)
            }
            val parts = docId.split(":", limit = 2)
            if (parts[0] != "primary") return null  // only primary external
            val rel = parts.getOrNull(1).orEmpty()
            if (rel.isEmpty()) null else "$rel/"
        } catch (_: Exception) { null }
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
    /** Decode-verify directly from in-memory bytes (no SAF round-trip). */
    private fun verifyDecodableBytes(bytes: ByteArray): String? {
        val tmp = File.createTempFile("j2h_verify_", ".heic", context.cacheDir)
        return try {
            tmp.writeBytes(bytes)
            val test = try { android.graphics.BitmapFactory.decodeFile(tmp.absolutePath) }
                       catch (e: Exception) { return "解码异常: ${e.message}" }
            if (test == null) return "解码返回空，bitstream 可能损坏"
            test.recycle()
            null
        } finally { tmp.delete() }
    }

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

    /** Returns "ftyp+24 / mdat+12345 / meta+456 / ..." or null on parse error. */
    private fun describeTopLevel(data: ByteArray): String? {
        return try {
            val sb = StringBuilder()
            var p = 0
            var first = true
            while (p < data.size - 8) {
                val sz32 = ((data[p].toLong() and 0xFF) shl 24) or
                           ((data[p+1].toLong() and 0xFF) shl 16) or
                           ((data[p+2].toLong() and 0xFF) shl 8) or
                            (data[p+3].toLong() and 0xFF)
                val type = String(data, p+4, 4, Charsets.US_ASCII)
                    .replace(' ', '?')
                val size: Long = when (sz32) {
                    1L -> {
                        if (p + 16 > data.size) return null
                        var v = 0L
                        for (k in 0 until 8) v = (v shl 8) or (data[p+8+k].toLong() and 0xFF)
                        v
                    }
                    0L -> (data.size - p).toLong()
                    else -> sz32
                }
                if (size < 8 || p + size > data.size) return null
                if (!first) sb.append(" / ")
                sb.append(type).append('+').append(size)
                first = false
                p += size.toInt()
            }
            sb.toString()
        } catch (_: Exception) { null }
    }
}
