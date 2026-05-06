package com.wjbyt.j2h.heif

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import androidx.exifinterface.media.ExifInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * After we write a HEIC via SAF, populate Android MediaStore so vendor galleries
 * (which mostly read DATE_TAKEN from MediaStore rather than parsing the file's
 * EXIF for HEIC) display the correct shot time. GPS and device fields are also
 * tried but Android 13+ removed those columns, so they end up only in the file's
 * own EXIF (still useful for desktop viewers).
 */
object MediaStoreSync {

    /** Source-side EXIF fields we care about. */
    data class Snapshot(
        val dateTakenMillis: Long? = null,    // parsed from EXIF datetime
        val gpsLat: Double? = null,
        val gpsLon: Double? = null,
        val make: String? = null,
        val model: String? = null,
        val datetimeStr: String? = null,      // raw "yyyy:MM:dd HH:mm:ss" — kept verbatim
        val exposureTime: Double? = null,     // seconds
        val fNumber: Double? = null,
        val iso: Int? = null,
        val focalLength: Double? = null,      // mm
        val orientation: Int? = null
    )

    fun readSourceJpg(context: Context, jpg: DocumentFile): Snapshot {
        return try {
            context.contentResolver.openInputStream(jpg.uri)?.use { input ->
                val exif = ExifInterface(input)
                val dtStr = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
                val dt = parseExifDate(dtStr)
                val ll = exif.latLong // returns DoubleArray? of [lat, lon] or null
                Snapshot(
                    dateTakenMillis = dt,
                    gpsLat = ll?.getOrNull(0),
                    gpsLon = ll?.getOrNull(1),
                    make = exif.getAttribute(ExifInterface.TAG_MAKE),
                    model = exif.getAttribute(ExifInterface.TAG_MODEL),
                    datetimeStr = dtStr,
                    exposureTime = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0)
                        .takeIf { it > 0 },
                    fNumber = exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0)
                        .takeIf { it > 0 },
                    iso = exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0)
                        .takeIf { it > 0 },
                    focalLength = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0)
                        .takeIf { it > 0 },
                    orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0)
                        .takeIf { it > 0 }
                )
            }
        } catch (_: Exception) { null } ?: Snapshot()
    }

    /**
     * Push [snap] onto the new HEIC at [newHeicUri]. Best-effort:
     *  - Set the file's mtime to the shot time. vivo gallery's "时间" panel
     *    reads mtime, not EXIF DateTime, so this is what makes the shoot time
     *    appear instead of the conversion time.
     *  - Trigger MediaScanner; in the callback update DATE_TAKEN, GPS, MAKE,
     *    MODEL columns so vendor galleries that consult MediaStore see them.
     *
     * Both ops require MANAGE_EXTERNAL_STORAGE on Android 11+ because our
     * SAF-written files are owned by DocumentsUI, not us. The result string
     * tells us in the user-visible log exactly which steps succeeded.
     */
    fun apply(context: Context, newHeicUri: Uri, snap: Snapshot): String {
        val parts = mutableListOf<String>()
        val path = pathFromSafUri(newHeicUri)
        if (path == null) {
            return " [meta: path-resolve-failed]"
        }

        val hasAllFilesAccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            Environment.isExternalStorageManager() else true
        if (!hasAllFilesAccess) parts += "no-all-files-access"

        // 1) File mtime → shot time. This is the single most-impactful change
        // for vivo's gallery: their info panel displays this value as 时间.
        // Requires MANAGE_EXTERNAL_STORAGE on Android 11+ for SAF-written
        // outputs (because we don't own the inode otherwise).
        snap.dateTakenMillis?.let { ts ->
            try {
                val ok = java.io.File(path).setLastModified(ts)
                parts += if (ok) "mtime=ok" else "mtime=denied"
            } catch (e: Exception) { parts += "mtime=${e.message?.take(30)}" }
        }

        // 2) MediaScanner → MediaStore. Try a known-good base set first, then
        // attempt vendor-extension columns one at a time so an unknown column
        // on one row doesn't kill the whole update. The vivo gallery reads
        // make/model/GPS from MediaStore (verified via the Samsung HEIC: same
        // file content, same MediaScanner — and it shows make/model/地点).
        if (snap.dateTakenMillis != null || snap.gpsLat != null ||
            snap.make != null || snap.model != null) {
            // Always-safe columns (standard MediaStore schema).
            val baseCv = ContentValues().apply {
                snap.dateTakenMillis?.let {
                    put(MediaStore.Images.Media.DATE_TAKEN, it)
                    // Some galleries time-sort on DATE_MODIFIED instead.
                    put(MediaStore.Images.Media.DATE_MODIFIED, it / 1000)
                }
            }
            // Best-guess vendor columns. Each gets attempted independently;
            // we log which ones succeeded so the next iteration knows what
            // vivo's MediaStore actually accepts.
            val attempts = mutableListOf<Pair<String, Any>>()
            snap.gpsLat?.let {
                attempts += "latitude" to it
                attempts += "gps_latitude" to it
            }
            snap.gpsLon?.let {
                attempts += "longitude" to it
                attempts += "gps_longitude" to it
            }
            snap.make?.takeIf { it.isNotBlank() }?.let {
                attempts += "make" to it
                attempts += "manufacturer" to it
            }
            snap.model?.takeIf { it.isNotBlank() }?.let {
                attempts += "model" to it
                attempts += "device" to it
            }
            try {
                MediaScannerConnection.scanFile(
                    context, arrayOf(path), arrayOf("image/heic")
                ) { _, scannedUri ->
                    if (scannedUri == null) {
                        com.wjbyt.j2h.work.ConversionForegroundService.appendLog(
                            "  · MediaScanner 扫描完成但未返回 URI（vendor MediaStore 拒绝索引？）"
                        )
                        return@scanFile
                    }
                    val cr = context.contentResolver
                    // Diagnostic: read back the row vivo wrote during the
                    // scan to see whether MediaScanner extracted EXIF on its
                    // own. If the row already has make/model/GPS populated,
                    // the gallery's empty display is a vendor reader bug
                    // (we can't fix). If it's empty, MediaScanner failed to
                    // extract — and we need a different write path.
                    try {
                        cr.query(scannedUri, null, null, null, null)?.use { c ->
                            if (c.moveToFirst()) {
                                val interesting = listOf(
                                    "make", "model", "manufacturer", "device",
                                    "latitude", "longitude",
                                    "gps_latitude", "gps_longitude",
                                    "datetaken", "date_taken",
                                    "datetime", "exif_datetime",
                                    "owner_package_name", "is_pending",
                                    "_data", "mime_type", "width", "height"
                                )
                                val sb = StringBuilder()
                                for (i in 0 until c.columnCount) {
                                    val name = c.getColumnName(i).lowercase()
                                    if (name !in interesting) continue
                                    val v = try { c.getString(i) } catch (_: Exception) { null }
                                    if (!v.isNullOrBlank() && v != "0") {
                                        sb.append(name).append('=')
                                          .append(v.take(40)).append("; ")
                                    }
                                }
                                com.wjbyt.j2h.work.ConversionForegroundService.appendLog(
                                    "  · MediaStore 行内容: $sb"
                                )
                            }
                        }
                    } catch (e: Exception) {
                        com.wjbyt.j2h.work.ConversionForegroundService.appendLog(
                            "  · MediaStore 查询失败: ${e.message?.take(60)}"
                        )
                    }
                    // Step A: standard columns in one shot.
                    val baseRows = try {
                        cr.update(scannedUri, baseCv, null, null)
                    } catch (e: Exception) {
                        com.wjbyt.j2h.work.ConversionForegroundService.appendLog(
                            "  · MediaStore 基础列更新失败: ${e.message?.take(80)}"
                        )
                        0
                    }
                    // Step B: vendor columns, one at a time so a single bad
                    // column doesn't rollback the whole update.
                    val accepted = mutableListOf<String>()
                    val rejected = mutableListOf<String>()
                    for ((col, value) in attempts) {
                        val cv = ContentValues().apply {
                            when (value) {
                                is Double -> put(col, value)
                                is String -> put(col, value)
                                is Long   -> put(col, value)
                                is Int    -> put(col, value)
                                else -> put(col, value.toString())
                            }
                        }
                        try {
                            val n = cr.update(scannedUri, cv, null, null)
                            if (n > 0) accepted += col
                        } catch (_: Exception) {
                            rejected += col
                        }
                    }
                    com.wjbyt.j2h.work.ConversionForegroundService.appendLog(
                        "  · MediaStore: 基础列 $baseRows 行；vendor 接受=${accepted.joinToString(",")}；拒绝=${rejected.joinToString(",")}"
                    )
                }
                parts += "scan=requested"
            } catch (e: Exception) { parts += "scan=${e.message?.take(30)}" }
        }
        return if (parts.isEmpty()) "" else " [meta: ${parts.joinToString(", ")}]"
    }

    private fun parseExifDate(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        val fmts = listOf(
            "yyyy:MM:dd HH:mm:ss",
            "yyyy-MM-dd HH:mm:ss"
        )
        for (p in fmts) {
            try {
                val sdf = SimpleDateFormat(p, Locale.US)
                sdf.timeZone = TimeZone.getDefault() // EXIF datetime is local time
                return sdf.parse(s)?.time
            } catch (_: Exception) {}
        }
        return null
    }

    /**
     * Best-effort SAF URI → filesystem path. Works for primary external storage
     * (where DCIM/Pictures/etc. live) which covers the typical user case.
     */
    private fun pathFromSafUri(uri: Uri): String? {
        return try {
            val docId = DocumentsContract.getDocumentId(uri)
            val (volume, rel) = docId.split(":", limit = 2).let { it[0] to it.getOrNull(1) }
            if (rel == null) return null
            when (volume) {
                "primary" -> "${Environment.getExternalStorageDirectory().absolutePath}/$rel"
                else -> "/storage/$volume/$rel"
            }
        } catch (_: Exception) { null }
    }
}
