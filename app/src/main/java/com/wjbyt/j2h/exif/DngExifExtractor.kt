package com.wjbyt.j2h.exif

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Extracts a metadata-only TIFF blob from a DNG file. DNG is itself TIFF, so
 * we walk its IFD0, keep the photo-metadata tags (Make / Model / DateTime /
 * Orientation / Software / resolutions / Copyright), copy the ExifIFD and
 * GPSIFD verbatim (those have aperture / shutter / ISO / focal length / GPS
 * coordinates), and drop everything else (strip pointers, raw image format
 * tags, DNG-specific tags).
 *
 * The output is a BE TIFF blob (`MM\0*` magic) suitable for feeding into
 * [com.wjbyt.j2h.heif.HeifExifInjector]. Used by the DNG → HEIC path so the
 * resulting HEIC carries the full set of camera params, not just the four
 * fields ExifInterface managed to surface.
 */
object DngExifExtractor {

    /** IFD0 tags worth keeping for gallery display. */
    private val IFD0_KEEP = setOf(
        0x010E,  // ImageDescription
        0x010F,  // Make
        0x0110,  // Model
        0x0112,  // Orientation
        0x011A,  // XResolution
        0x011B,  // YResolution
        0x0128,  // ResolutionUnit
        0x0131,  // Software
        0x0132,  // DateTime
        0x013B,  // Artist
        0x0213,  // YCbCrPositioning
        0x8298   // Copyright
    )

    fun extractTiff(dng: ByteArray): ByteArray? {
        if (dng.size < 16) return null
        val le = when {
            dng[0] == 'M'.code.toByte() && dng[1] == 'M'.code.toByte() -> false
            dng[0] == 'I'.code.toByte() && dng[1] == 'I'.code.toByte() -> true
            else -> return null
        }
        val magic = readU16(dng, 2, le)
        if (magic != 42) return null
        val ifd0Off = readU32(dng, 4, le).toInt()
        if (ifd0Off < 8 || ifd0Off + 2 > dng.size) return null

        val ifd0 = parseIfd(dng, ifd0Off, le) ?: return null
        val exifOff = ifd0.firstOrNull { it.tag == 0x8769 }?.valueOrOffset?.toInt()
        val gpsOff = ifd0.firstOrNull { it.tag == 0x8825 }?.valueOrOffset?.toInt()

        val exifIfd = exifOff?.let { parseIfd(dng, it, le) } ?: emptyList()
        val gpsIfd = gpsOff?.let { parseIfd(dng, it, le) } ?: emptyList()

        // Filter IFD0 to keep tags + pointers (we'll re-emit pointers ourselves).
        val keptIfd0 = ifd0.filter { it.tag in IFD0_KEEP }.toMutableList()
        if (exifIfd.isNotEmpty()) keptIfd0 += Entry(
            0x8769, TYPE_LONG, 1, 0L, null, isPointer = true, pointerKey = "exif"
        )
        if (gpsIfd.isNotEmpty()) keptIfd0 += Entry(
            0x8825, TYPE_LONG, 1, 0L, null, isPointer = true, pointerKey = "gps"
        )

        if (keptIfd0.isEmpty() && exifIfd.isEmpty() && gpsIfd.isEmpty()) return null

        return rebuildTiff(keptIfd0, exifIfd, gpsIfd)
    }

    // ----- IFD model -----

    private const val TYPE_BYTE = 1
    private const val TYPE_ASCII = 2
    private const val TYPE_SHORT = 3
    private const val TYPE_LONG = 4
    private const val TYPE_RATIONAL = 5
    private const val TYPE_UNDEFINED = 7
    private const val TYPE_SLONG = 9
    private const val TYPE_SRATIONAL = 10

    private fun typeSize(t: Int): Int = when (t) {
        TYPE_BYTE, TYPE_ASCII, TYPE_UNDEFINED -> 1
        TYPE_SHORT -> 2
        TYPE_LONG, TYPE_SLONG -> 4
        TYPE_RATIONAL, TYPE_SRATIONAL -> 8
        else -> 0  // unknown / unsupported
    }

    private data class Entry(
        val tag: Int,
        val type: Int,
        val count: Int,
        /** Original raw 4-byte value-or-offset from the source (read in source endianness). */
        val valueOrOffset: Long,
        /**
         * External bytes when count * typeSize > 4 — copied from source. When
         * we re-emit, we'll either inline them again or place them in the new
         * data area.
         */
        val external: ByteArray?,
        val isPointer: Boolean = false,
        /** Marker so we can patch pointer values during emit. */
        val pointerKey: String? = null
    )

    private fun parseIfd(d: ByteArray, off: Int, le: Boolean): List<Entry>? {
        if (off + 2 > d.size) return null
        val n = readU16(d, off, le)
        if (off + 2 + n * 12 > d.size) return null
        val entries = mutableListOf<Entry>()
        for (i in 0 until n) {
            val p = off + 2 + i * 12
            val tag = readU16(d, p, le)
            val type = readU16(d, p + 2, le)
            val count = readU32(d, p + 4, le).toInt()
            val ts = typeSize(type)
            val totalBytes = if (ts == 0) 0 else (ts.toLong() * count).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val external: ByteArray? = if (totalBytes > 4 && ts > 0) {
                val ext = readU32(d, p + 8, le).toInt()
                if (ext < 0 || ext + totalBytes > d.size) null
                else {
                    // Source is little-endian — but we need to re-emit BE.
                    // Convert per-element so the bytes match the new endianness.
                    val raw = d.copyOfRange(ext, ext + totalBytes)
                    if (le) swapEndian(raw, type, count) else raw
                }
            } else null
            // For inline values we also need to convert bytes to BE order.
            val valueOrOffset = if (totalBytes <= 4 && ts > 0) {
                // Read as a single typed value (or array packed in 4 bytes)
                // and re-encode as BE bytes so we can stamp them inline.
                packInlineBE(d, p + 8, le, type, count)
            } else 0L
            entries += Entry(tag, type, count, valueOrOffset, external)
        }
        return entries
    }

    /**
     * Re-encode an inline value-or-offset into a BE 32-bit pattern. The 4 bytes
     * at `p..p+4` in the source hold either a single small value (left-aligned)
     * or up to 4 packed values (e.g. 4 BYTEs, 2 SHORTs, etc.). Reading as the
     * source endianness and re-emitting BE keeps the stored values correct.
     */
    private fun packInlineBE(d: ByteArray, p: Int, le: Boolean, type: Int, count: Int): Long {
        val ts = typeSize(type)
        if (ts == 0 || count <= 0) {
            // Unknown — preserve raw bytes verbatim (assume BE source for safety).
            return ((d[p].toLong() and 0xFF) shl 24) or
                   ((d[p + 1].toLong() and 0xFF) shl 16) or
                   ((d[p + 2].toLong() and 0xFF) shl 8) or
                    (d[p + 3].toLong() and 0xFF)
        }
        val out = ByteArray(4)
        when (ts) {
            1 -> for (i in 0 until count.coerceAtMost(4)) out[i] = d[p + i]
            2 -> for (i in 0 until count.coerceAtMost(2)) {
                val v = readU16(d, p + i * 2, le)
                out[i * 2]     = ((v ushr 8) and 0xFF).toByte()
                out[i * 2 + 1] = (v and 0xFF).toByte()
            }
            4 -> {
                val v = readU32(d, p, le)
                out[0] = ((v ushr 24) and 0xFF).toByte()
                out[1] = ((v ushr 16) and 0xFF).toByte()
                out[2] = ((v ushr 8) and 0xFF).toByte()
                out[3] = (v and 0xFF).toByte()
            }
        }
        return ((out[0].toLong() and 0xFF) shl 24) or
               ((out[1].toLong() and 0xFF) shl 16) or
               ((out[2].toLong() and 0xFF) shl 8) or
                (out[3].toLong() and 0xFF)
    }

    private fun swapEndian(bytes: ByteArray, type: Int, count: Int): ByteArray {
        val ts = typeSize(type)
        if (ts <= 1) return bytes
        val out = ByteArray(bytes.size)
        when (ts) {
            2 -> for (i in 0 until count) {
                if (i * 2 + 1 < bytes.size) {
                    out[i * 2]     = bytes[i * 2 + 1]
                    out[i * 2 + 1] = bytes[i * 2]
                }
            }
            4 -> for (i in 0 until count) {
                val b = i * 4
                if (b + 3 < bytes.size) {
                    out[b]     = bytes[b + 3]
                    out[b + 1] = bytes[b + 2]
                    out[b + 2] = bytes[b + 1]
                    out[b + 3] = bytes[b]
                }
            }
            8 -> for (i in 0 until count) {
                // RATIONAL / SRATIONAL = 2 LONGs
                val b = i * 8
                if (b + 7 < bytes.size) {
                    out[b]     = bytes[b + 3]; out[b + 1] = bytes[b + 2]
                    out[b + 2] = bytes[b + 1]; out[b + 3] = bytes[b]
                    out[b + 4] = bytes[b + 7]; out[b + 5] = bytes[b + 6]
                    out[b + 6] = bytes[b + 5]; out[b + 7] = bytes[b + 4]
                }
            }
        }
        return out
    }

    // ----- TIFF rebuild -----

    private fun rebuildTiff(
        ifd0: List<Entry>, exifIfd: List<Entry>, gpsIfd: List<Entry>
    ): ByteArray {
        // Pre-compute layout positions.
        val ifd0Size = 2L + 12L * ifd0.size + 4L
        val ifd0CoreEnd = 8L + ifd0Size
        val ifd0ExternalSize = ifd0.sumOf { (it.external?.size ?: 0).toLong() }
        // ExifIFD goes right after IFD0 + its external data.
        val exifIfdOff = ifd0CoreEnd + ifd0ExternalSize
        val exifSize = if (exifIfd.isNotEmpty()) 2L + 12L * exifIfd.size + 4L else 0L
        val exifExternalSize = exifIfd.sumOf { (it.external?.size ?: 0).toLong() }
        val gpsIfdOff = exifIfdOff + exifSize + exifExternalSize
        val gpsSize = if (gpsIfd.isNotEmpty()) 2L + 12L * gpsIfd.size + 4L else 0L

        val out = ByteArrayOutputStream()
        // TIFF header: MM\0* + magic 42 + IFD0 offset
        out.write(byteArrayOf(0x4D, 0x4D, 0x00, 0x2A))
        out.write(beU32(8L))

        writeIfd(out, 8L, ifd0, mapOf("exif" to exifIfdOff, "gps" to gpsIfdOff))
        if (exifIfd.isNotEmpty()) writeIfd(out, exifIfdOff, exifIfd, emptyMap())
        if (gpsIfd.isNotEmpty()) writeIfd(out, gpsIfdOff, gpsIfd, emptyMap())
        return out.toByteArray()
    }

    private fun writeIfd(
        out: ByteArrayOutputStream, ifdStart: Long, entries: List<Entry>,
        pointerOffsets: Map<String, Long>
    ) {
        val core = 2L + 12L * entries.size + 4L
        var extCursor = ifdStart + core
        // Pre-compute external offsets per entry.
        val patched = entries.map { e ->
            if (e.external != null) {
                val off = extCursor
                extCursor += e.external.size
                e to off
            } else e to null
        }
        // count
        out.write(beU16(entries.size))
        // entries
        for ((e, extOff) in patched) {
            out.write(beU16(e.tag))
            out.write(beU16(e.type))
            out.write(beU32(e.count.toLong()))
            // value-or-offset (4 bytes)
            when {
                e.isPointer && e.pointerKey != null -> {
                    val target = pointerOffsets[e.pointerKey] ?: 0L
                    out.write(beU32(target))
                }
                extOff != null -> out.write(beU32(extOff))
                else -> {
                    // inline value, already in BE order
                    out.write(beU32(e.valueOrOffset))
                }
            }
        }
        // next IFD offset = 0
        out.write(beU32(0L))
        // External data
        for ((e, _) in patched) {
            e.external?.let { out.write(it) }
        }
    }

    // ----- byte helpers -----

    private fun readU16(d: ByteArray, p: Int, le: Boolean): Int {
        val a = d[p].toInt() and 0xFF
        val b = d[p + 1].toInt() and 0xFF
        return if (le) (b shl 8) or a else (a shl 8) or b
    }

    private fun readU32(d: ByteArray, p: Int, le: Boolean): Long {
        val a = d[p].toLong() and 0xFF
        val b = d[p + 1].toLong() and 0xFF
        val c = d[p + 2].toLong() and 0xFF
        val r = d[p + 3].toLong() and 0xFF
        return if (le) (r shl 24) or (c shl 16) or (b shl 8) or a
        else            (a shl 24) or (b shl 16) or (c shl 8) or r
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
