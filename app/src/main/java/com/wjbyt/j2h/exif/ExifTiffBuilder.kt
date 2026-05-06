package com.wjbyt.j2h.exif

import com.wjbyt.j2h.heif.MediaStoreSync
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Build a minimal big-endian TIFF EXIF blob from a [MediaStoreSync.Snapshot].
 *
 * The output is the raw TIFF stream (starts with `MM\0*` magic) ready to feed
 * into [com.wjbyt.j2h.heif.HeifExifInjector]. Used when the source format
 * isn't JPG (DNG decode, HEIC repair) — we have the field values already
 * pulled by [MediaStoreSync.readSourceJpg] but no ready-made TIFF segment to
 * copy verbatim.
 *
 * Layout produced:
 *   TIFF header (8B)
 *   IFD0 — Make / Model / DateTime / Orientation / ExifIFDPointer / GPSIFDPointer
 *   ExifIFD — DateTimeOriginal / DateTimeDigitized / ExposureTime / FNumber /
 *             ISO / FocalLength / ExifVersion
 *   GPSIFD — Lat/LatRef/Lon/LonRef
 *   string + rational data area at the end
 *
 * Returns null when there's nothing meaningful to write.
 */
object ExifTiffBuilder {

    fun build(snap: MediaStoreSync.Snapshot): ByteArray? {
        val datetimeStr = snap.datetimeStr ?: snap.dateTakenMillis?.let { ms ->
            val sdf = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
            sdf.timeZone = TimeZone.getDefault()
            sdf.format(Date(ms))
        }
        val hasIfd0 = !snap.make.isNullOrBlank() || !snap.model.isNullOrBlank() ||
                      !datetimeStr.isNullOrBlank() || snap.orientation != null
        val hasGps = snap.gpsLat != null && snap.gpsLon != null
        val hasExif = !datetimeStr.isNullOrBlank() || snap.exposureTime != null ||
                      snap.fNumber != null || snap.iso != null || snap.focalLength != null
        if (!hasIfd0 && !hasGps && !hasExif) return null

        // ----- Build sub-IFDs first; we need their sizes to know IFD0
        // pointer offsets. -----
        val exifIfd = buildExifIfd(snap, datetimeStr)
        val gpsIfd = if (hasGps) buildGpsIfd(snap.gpsLat!!, snap.gpsLon!!) else null

        // IFD0 entries (fixed list, populate dynamically).
        val ifd0Entries = mutableListOf<EntryDef>()
        snap.make?.takeIf { it.isNotBlank() }?.let {
            ifd0Entries += EntryDef.ascii(0x010f, it)
        }
        snap.model?.takeIf { it.isNotBlank() }?.let {
            ifd0Entries += EntryDef.ascii(0x0110, it)
        }
        snap.orientation?.let { ifd0Entries += EntryDef.short(0x0112, it) }
        datetimeStr?.let { ifd0Entries += EntryDef.ascii(0x0132, it) }
        if (exifIfd != null) ifd0Entries += EntryDef.long(0x8769, 0L)  // patched
        if (gpsIfd != null)  ifd0Entries += EntryDef.long(0x8825, 0L)  // patched

        // ----- Layout the file -----
        // Layout: header(8) + IFD0 + ExifIFD + GPSIFD + everyone's external data
        val ifd0Size = ifdSize(ifd0Entries)
        val ifd0Off = 8L
        val exifIfdOff = ifd0Off + ifd0Size
        val gpsIfdOff = exifIfdOff + (exifIfd?.totalSize ?: 0L)

        // Patch the pointer entries with their actual offsets.
        for (i in ifd0Entries.indices) {
            val e = ifd0Entries[i]
            if (e.tag == 0x8769) ifd0Entries[i] = e.copy(immediate = exifIfdOff)
            if (e.tag == 0x8825) ifd0Entries[i] = e.copy(immediate = gpsIfdOff)
        }

        val out = ByteArrayOutputStream()
        // TIFF header: MM\0* + magic 42 + offset to IFD0 (=8)
        out.write(byteArrayOf(0x4d, 0x4d, 0x00, 0x2a))
        out.write(beU32(8L))

        writeIfd(out, ifd0Off, ifd0Entries, nextIfdOffset = 0L)
        if (exifIfd != null) writeIfd(out, exifIfdOff, exifIfd.entries, nextIfdOffset = 0L)
        if (gpsIfd != null) writeIfd(out, gpsIfdOff, gpsIfd.entries, nextIfdOffset = 0L)

        return out.toByteArray()
    }

    // ---- per-IFD construction ----

    private data class IfdResult(val entries: List<EntryDef>, val totalSize: Long)

    private fun buildExifIfd(snap: MediaStoreSync.Snapshot, datetimeStr: String?): IfdResult? {
        val entries = mutableListOf<EntryDef>()
        // ExifVersion 0230 (4 bytes UNDEFINED). Inline.
        entries += EntryDef(
            tag = 0x9000, type = 7 /*UNDEFINED*/, count = 4,
            inlineBytes = byteArrayOf(0x30, 0x32, 0x33, 0x30) // "0230"
        )
        datetimeStr?.let {
            entries += EntryDef.ascii(0x9003, it)  // DateTimeOriginal
            entries += EntryDef.ascii(0x9004, it)  // DateTimeDigitized
        }
        snap.exposureTime?.let { entries += EntryDef.rational(0x829a, it) }
        snap.fNumber?.let { entries += EntryDef.rational(0x829d, it) }
        snap.iso?.let { entries += EntryDef.short(0x8827, it) }
        snap.focalLength?.let { entries += EntryDef.rational(0x920a, it) }
        if (entries.isEmpty()) return null
        return IfdResult(entries, ifdSize(entries))
    }

    private fun buildGpsIfd(lat: Double, lon: Double): IfdResult {
        val entries = mutableListOf<EntryDef>()
        // GPSVersionID (4 BYTE): 2,3,0,0
        entries += EntryDef(tag = 0x0000, type = 1 /*BYTE*/, count = 4,
            inlineBytes = byteArrayOf(2, 3, 0, 0))
        entries += EntryDef.ascii(0x0001, if (lat >= 0) "N" else "S")
        entries += EntryDef.gpsRational3(0x0002, kotlin.math.abs(lat))
        entries += EntryDef.ascii(0x0003, if (lon >= 0) "E" else "W")
        entries += EntryDef.gpsRational3(0x0004, kotlin.math.abs(lon))
        return IfdResult(entries, ifdSize(entries))
    }

    // ---- entry abstraction ----

    /**
     * `inlineBytes` (≤4 B) lets us hard-code small fixed-width values.
     * `external` is the bytes that go in the data area when the value
     * doesn't fit in 4 bytes; offsets are computed during write.
     */
    private data class EntryDef(
        val tag: Int,
        val type: Int,
        val count: Long,
        val inlineBytes: ByteArray? = null,   // ≤4 B values
        val external: ByteArray? = null,      // larger values
        val immediate: Long = 0L              // for LONG-pointer entries patched later
    ) {
        companion object {
            fun ascii(tag: Int, s: String): EntryDef {
                val raw = s.toByteArray(Charsets.US_ASCII) + byteArrayOf(0)
                val count = raw.size.toLong()
                return if (raw.size <= 4) {
                    EntryDef(tag, 2, count, inlineBytes = raw.copyOf(4))
                } else {
                    EntryDef(tag, 2, count, external = raw)
                }
            }
            fun short(tag: Int, v: Int): EntryDef {
                // SHORT (16-bit). Inline (4-byte slot, top 2 bytes used).
                val bb = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
                bb.putShort(v.toShort()); bb.putShort(0)
                return EntryDef(tag, 3, 1, inlineBytes = bb.array())
            }
            fun long(tag: Int, v: Long): EntryDef {
                val bb = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
                bb.putInt(v.toInt())
                return EntryDef(tag, 4, 1, inlineBytes = bb.array(), immediate = v)
            }
            /** Single 8-byte rational (1 entry). Goes in external area. */
            fun rational(tag: Int, value: Double): EntryDef {
                val (n, d) = approximateRational(value)
                val bb = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
                bb.putInt(n).putInt(d)
                return EntryDef(tag, 5, 1, external = bb.array())
            }
            /** GPS deg/min/sec triple — 24 bytes external. */
            fun gpsRational3(tag: Int, value: Double): EntryDef {
                val deg = value.toInt()
                val minTotal = (value - deg) * 60.0
                val min = minTotal.toInt()
                val sec = (minTotal - min) * 60.0
                val bb = ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN)
                bb.putInt(deg).putInt(1)
                bb.putInt(min).putInt(1)
                bb.putInt((sec * 10000).toInt()).putInt(10000)
                return EntryDef(tag, 5, 3, external = bb.array())
            }
        }
    }

    private fun approximateRational(value: Double): Pair<Int, Int> {
        // For typical EXIF values (exposure, aperture, focal length) the
        // numerator/denominator are small integers — pick a denominator that
        // keeps precision without overflowing.
        if (value < 1e-6) return 0 to 1
        val denom = when {
            value >= 100 -> 10
            value >= 1   -> 1000
            else         -> 1_000_000
        }
        val numer = (value * denom).toLong().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return numer to denom
    }

    // ---- IFD layout ----

    private fun ifdSize(entries: List<EntryDef>): Long {
        // count(2) + entries(12 each) + nextIfdOffset(4) + external data
        val core = 2L + 12L * entries.size + 4L
        val external = entries.sumOf { (it.external?.size ?: 0).toLong() }
        return core + external
    }

    private fun writeIfd(
        out: ByteArrayOutputStream, ifdStart: Long,
        entries: List<EntryDef>, nextIfdOffset: Long
    ) {
        val core = 2L + 12L * entries.size + 4L
        // Pre-compute external offsets — sequential after the IFD core.
        var extCursor = ifdStart + core
        val patched = entries.map { e ->
            if (e.external != null) {
                val withOff = e.copy(inlineBytes = beU32(extCursor))
                extCursor += e.external.size
                withOff to e.external
            } else {
                e to null
            }
        }
        // count
        out.write(beU16(entries.size))
        // entries
        for ((e, _) in patched) {
            out.write(beU16(e.tag))
            out.write(beU16(e.type))
            out.write(beU32(e.count))
            // value-or-offset (4 bytes). For LONG-pointer entries that were
            // patched with `immediate`, write the patched value.
            if (e.tag == 0x8769 || e.tag == 0x8825) {
                out.write(beU32(e.immediate))
            } else {
                val inline = e.inlineBytes ?: ByteArray(4)
                out.write(inline.copyOf(4))
            }
        }
        // next IFD offset
        out.write(beU32(nextIfdOffset))
        // external data (in the same order as entries)
        for ((_, ext) in patched) ext?.let { out.write(it) }
    }

    private fun beU16(v: Int): ByteArray =
        byteArrayOf(((v ushr 8) and 0xFF).toByte(), (v and 0xFF).toByte())

    private fun beU32(v: Long): ByteArray = byteArrayOf(
        ((v ushr 24) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        (v and 0xFF).toByte()
    )
}
