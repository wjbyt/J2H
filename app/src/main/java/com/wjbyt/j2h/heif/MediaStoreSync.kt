package com.wjbyt.j2h.heif

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
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
     *  - Set the file's mtime to the shot time (gallery time-sort fallback).
     *  - Trigger MediaScanner; in the callback update DATE_TAKEN (and GPS where supported).
     *
     * Returns a short message describing what was done, for the user-visible log.
     */
    fun apply(context: Context, newHeicUri: Uri, snap: Snapshot): String {
        val parts = mutableListOf<String>()
        val path = pathFromSafUri(newHeicUri)

        // 1) File mtime — this alone is enough for many galleries to time-sort correctly,
        // even if the MediaStore step below fails.
        snap.dateTakenMillis?.let { ts ->
            if (path != null) {
                try {
                    if (java.io.File(path).setLastModified(ts)) parts += "mtime=ok"
                    else parts += "mtime=denied"
                } catch (e: Exception) { parts += "mtime=${e.message}" }
            }
        }

        // 2) MediaScanner: gives us the MediaStore URI; populate DATE_TAKEN there so the
        // vivo gallery shows the correct shot time in the photo's info panel.
        if (path != null && (snap.dateTakenMillis != null || snap.gpsLat != null)) {
            val cv = ContentValues().apply {
                snap.dateTakenMillis?.let { put(MediaStore.Images.Media.DATE_TAKEN, it) }
                // LATITUDE/LONGITUDE columns were removed in API 29; ignore failures.
                snap.gpsLat?.let { put("latitude", it) }
                snap.gpsLon?.let { put("longitude", it) }
            }
            try {
                MediaScannerConnection.scanFile(
                    context, arrayOf(path), arrayOf("image/heic")
                ) { _, scannedUri ->
                    if (scannedUri != null) {
                        try { context.contentResolver.update(scannedUri, cv, null, null) }
                        catch (_: Exception) { /* GPS columns may not exist on newer APIs */ }
                    }
                }
                parts += "scan=requested"
            } catch (e: Exception) { parts += "scan=${e.message}" }
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
