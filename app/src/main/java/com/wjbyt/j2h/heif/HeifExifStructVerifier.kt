package com.wjbyt.j2h.heif

/**
 * Verifies that a HEIF/HEIC byte array actually contains an EXIF item with valid
 * TIFF data — by parsing the box structure ourselves rather than going through
 * AndroidX ExifInterface / AOSP MediaMetadataRetriever, both of which silently
 * return null for many spec-compliant HEICs (libheif's included).
 *
 * Returns null if a valid EXIF item is present, or a short error string otherwise.
 */
object HeifExifStructVerifier {

    fun verify(data: ByteArray): String? {
        return try {
            val top = parseList(data, 0L, data.size.toLong())
            val meta = top.firstOrNull { it.type == "meta" } ?: return "no meta box"
            val subStart = meta.offset + meta.headerSize + 4
            val subEnd = meta.offset + meta.size
            val subs = parseList(data, subStart, subEnd)

            val iinf = subs.firstOrNull { it.type == "iinf" } ?: return "no iinf"
            val exifId = findExifItemId(data, iinf) ?: return "no infe with item_type='Exif'"

            val iloc = subs.firstOrNull { it.type == "iloc" } ?: return "no iloc"
            val (off, len) = locate(data, iloc, exifId) ?: return "Exif item missing iloc entry"

            if (off < 0 || len <= 0 || off + len > data.size) return "Exif item out of bounds"
            if (len < 8) return "Exif item too small ($len B)"

            // Skip the 4-byte tiff_header_offset prefix, then expect TIFF magic
            // (II*\0 little-endian or MM\0* big-endian).
            val p = off.toInt() + 4
            val a = data[p].toInt() and 0xFF
            val b = data[p + 1].toInt() and 0xFF
            val c = data[p + 2].toInt() and 0xFF
            val d = data[p + 3].toInt() and 0xFF
            val isLE = a == 0x49 && b == 0x49 && c == 0x2A && d == 0x00
            val isBE = a == 0x4D && b == 0x4D && c == 0x00 && d == 0x2A
            if (!isLE && !isBE) return "Exif payload doesn't start with TIFF magic"

            null
        } catch (e: Exception) {
            "parse error: ${e.message}"
        }
    }

    private data class Box(val type: String, val offset: Long, val size: Long, val headerSize: Int)

    private fun parseList(data: ByteArray, start: Long, end: Long): List<Box> {
        val out = mutableListOf<Box>()
        var p = start
        while (p < end - 8) {
            val sz32 = readU32(data, p)
            val type = String(data, (p + 4).toInt(), 4, Charsets.US_ASCII)
            val (size, hdr) = when (sz32) {
                1L -> readU64(data, p + 8) to 16
                0L -> (end - p) to 8
                else -> sz32 to 8
            }
            if (size < hdr || p + size > end) break
            out += Box(type, p, size, hdr)
            p += size
        }
        return out
    }

    private fun findExifItemId(data: ByteArray, iinf: Box): Int? {
        var p = iinf.offset.toInt() + iinf.headerSize
        val ver = data[p].toInt() and 0xFF
        p += 4
        val cnt = if (ver == 0) readU16(data, p.toLong()).also { p += 2 }
                  else readU32(data, p.toLong()).toInt().also { p += 4 }
        for (i in 0 until cnt) {
            val sz = readU32(data, p.toLong()).toInt()
            val infeVer = data[p + 8].toInt() and 0xFF
            var q = p + 12
            val itemId = if (infeVer < 2) readU16(data, q.toLong()).also { q += 2 }
                         else if (infeVer == 2) readU16(data, q.toLong()).also { q += 2 }
                         else readU32(data, q.toLong()).toInt().also { q += 4 }
            q += 2 // protection
            val itemType = if (infeVer >= 2) String(data, q, 4, Charsets.US_ASCII) else ""
            if (itemType == "Exif") return itemId
            p += sz
        }
        return null
    }

    private fun locate(data: ByteArray, iloc: Box, wantId: Int): Pair<Long, Long>? {
        var p = iloc.offset.toInt() + iloc.headerSize
        val ver = data[p].toInt() and 0xFF
        p += 4
        val b1 = data[p].toInt() and 0xFF
        val b2 = data[p + 1].toInt() and 0xFF
        val osz = (b1 shr 4) and 0xF
        val lsz = b1 and 0xF
        val bosz = (b2 shr 4) and 0xF
        val isz = if (ver == 1 || ver == 2) b2 and 0xF else 0
        p += 2
        val cnt = if (ver < 2) readU16(data, p.toLong()).also { p += 2 }
                  else readU32(data, p.toLong()).toInt().also { p += 4 }
        for (i in 0 until cnt) {
            val itemId = if (ver < 2) readU16(data, p.toLong()).also { p += 2 }
                         else readU32(data, p.toLong()).toInt().also { p += 4 }
            if (ver == 1 || ver == 2) p += 2 // construction_method/reserved
            p += 2 // dri
            val baseOff = readUInt(data, p.toLong(), bosz); p += bosz
            val ec = readU16(data, p.toLong()); p += 2
            var firstExtent: Pair<Long, Long>? = null
            repeat(ec) {
                if ((ver == 1 || ver == 2) && isz > 0) p += isz
                val o = readUInt(data, p.toLong(), osz); p += osz
                val l = readUInt(data, p.toLong(), lsz); p += lsz
                if (firstExtent == null) firstExtent = (baseOff + o) to l
            }
            if (itemId == wantId) return firstExtent
        }
        return null
    }

    private fun readU16(d: ByteArray, p: Long): Int {
        val i = p.toInt()
        return ((d[i].toInt() and 0xFF) shl 8) or (d[i + 1].toInt() and 0xFF)
    }

    private fun readU32(d: ByteArray, p: Long): Long {
        val i = p.toInt()
        return ((d[i].toLong() and 0xFF) shl 24) or
                ((d[i + 1].toLong() and 0xFF) shl 16) or
                ((d[i + 2].toLong() and 0xFF) shl 8) or
                (d[i + 3].toLong() and 0xFF)
    }

    private fun readU64(d: ByteArray, p: Long): Long {
        val i = p.toInt()
        var v = 0L
        for (k in 0 until 8) v = (v shl 8) or (d[i + k].toLong() and 0xFF)
        return v
    }

    private fun readUInt(d: ByteArray, p: Long, b: Int): Long = when (b) {
        0 -> 0L
        4 -> readU32(d, p)
        8 -> readU64(d, p)
        else -> error("bad width $b")
    }
}
