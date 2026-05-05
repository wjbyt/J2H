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
        val model: String? = null
    )

    fun readSourceJpg(context: Context, jpg: DocumentFile): Snapshot {
        return try {
            context.contentResolver.openInputStream(jpg.uri)?.use { input ->
                val exif = ExifInterface(input)
                val dt = parseExifDate(
                    exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                        ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
                )
                val ll = exif.latLong // returns DoubleArray? of [lat, lon] or null
                Snapshot(
                    dateTakenMillis = dt,
                    gpsLat = ll?.getOrNull(0),
                    gpsLon = ll?.getOrNull(1),
                    make = exif.getAttribute(ExifInterface.TAG_MAKE),
                    model = exif.getAttribute(ExifInterface.TAG_MODEL)
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

        // 2) MediaScanner: gives us the MediaStore URI; populate the rich
        // columns there so vendor galleries see make/model/GPS.
        if (snap.dateTakenMillis != null || snap.gpsLat != null ||
            snap.make != null || snap.model != null) {
            val cv = ContentValues().apply {
                snap.dateTakenMillis?.let {
                    put(MediaStore.Images.Media.DATE_TAKEN, it)
                    // Some galleries time-sort on DATE_MODIFIED instead.
                    put(MediaStore.Images.Media.DATE_MODIFIED, it / 1000)
                }
                // LATITUDE/LONGITUDE were removed from public API in 29 but
                // most vendor MediaStores keep them — try anyway, swallow
                // SQLiteException if the column doesn't exist.
                snap.gpsLat?.let { put("latitude", it) }
                snap.gpsLon?.let { put("longitude", it) }
            }
            try {
                MediaScannerConnection.scanFile(
                    context, arrayOf(path), arrayOf("image/heic")
                ) { _, scannedUri ->
                    if (scannedUri != null) {
                        try {
                            val n = context.contentResolver.update(scannedUri, cv, null, null)
                            com.wjbyt.j2h.work.ConversionForegroundService.appendLog(
                                "  · MediaStore 更新 $scannedUri → $n 行"
                            )
                        } catch (e: Exception) {
                            com.wjbyt.j2h.work.ConversionForegroundService.appendLog(
                                "  · MediaStore 更新失败: ${e.message?.take(80)}"
                            )
                        }
                    } else {
                        com.wjbyt.j2h.work.ConversionForegroundService.appendLog(
                            "  · MediaScanner 扫描完成但未返回 URI（vendor MediaStore 拒绝索引？）"
                        )
                    }
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
