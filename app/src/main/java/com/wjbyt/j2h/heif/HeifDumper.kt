package com.wjbyt.j2h.heif

/**
 * Diagnostic-only: produces a human-readable dump of a HEIF file's box layout
 * so we can verify that EXIF injection produced the expected meta/iinf/iref/iloc
 * structure and that the EXIF blob is at the offset the iloc claims.
 */
object HeifDumper {

    fun dump(heicData: ByteArray, label: String): String {
        val sb = StringBuilder()
        sb.appendLine("=== $label (${heicData.size}B) ===")
        try {
            val top = parseTop(heicData)
            for (b in top) {
                sb.appendLine("  TOP ${b.type} @${b.offset} size=${b.size}")
            }

            val meta = top.firstOrNull { it.type == "meta" }
            if (meta == null) { sb.appendLine("  (no meta)"); return sb.toString() }

            val subStart = meta.offset + meta.headerSize + 4 // skip version+flags
            val subEnd = meta.offset + meta.size
            val subs = parseList(heicData, subStart, subEnd)
            for (s in subs) sb.appendLine("    META.${s.type} @${s.offset} size=${s.size}")

            subs.firstOrNull { it.type == "iinf" }?.let { iinfBox ->
                sb.appendLine("    --- iinf entries ---")
                dumpIinf(heicData, iinfBox).forEach { sb.appendLine("      $it") }
            }
            subs.firstOrNull { it.type == "iref" }?.let { irefBox ->
                sb.appendLine("    --- iref entries ---")
                dumpIref(heicData, irefBox).forEach { sb.appendLine("      $it") }
            }
            subs.firstOrNull { it.type == "iloc" }?.let { ilocBox ->
                sb.appendLine("    --- iloc entries ---")
                dumpIloc(heicData, ilocBox).forEach { sb.appendLine("      $it") }

                // For each iloc entry that names a known Exif item, show the first 16 bytes of data.
                val exifIds = subs.firstOrNull { it.type == "iinf" }?.let { findExifItemIds(heicData, it) } ?: emptyList()
                for (id in exifIds) {
                    val (off, len) = locateItem(heicData, ilocBox, id) ?: continue
                    val bytesToShow = minOf(20, len.toInt(), heicData.size - off.toInt())
                    if (off >= 0 && bytesToShow > 0 && off.toInt() + bytesToShow <= heicData.size) {
                        val sample = heicData.sliceArray(off.toInt() until off.toInt() + bytesToShow)
                            .joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
                        sb.appendLine("      EXIF item $id @offset=$off len=$len → first ${bytesToShow}B: $sample")
                    } else {
                        sb.appendLine("      EXIF item $id @offset=$off len=$len → OUT OF FILE BOUNDS!")
                    }
                }
            }
        } catch (e: Exception) {
            sb.appendLine("  PARSE ERROR: ${e.message}")
        }
        return sb.toString()
    }

    private data class Box(val type: String, val offset: Long, val size: Long, val headerSize: Int)

    private fun parseTop(data: ByteArray) = parseList(data, 0L, data.size.toLong())

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

    private fun dumpIinf(data: ByteArray, box: Box): List<String> {
        val res = mutableListOf<String>()
        var p = box.offset.toInt() + box.headerSize
        val ver = data[p].toInt() and 0xFF
        p += 4
        val cnt = if (ver == 0) readU16(data, p.toLong()).also { p += 2 }
                  else readU32(data, p.toLong()).toInt().also { p += 4 }
        for (i in 0 until cnt) {
            val sz = readU32(data, p.toLong()).toInt()
            val ty = String(data, p + 4, 4, Charsets.US_ASCII)
            val infeVer = data[p + 8].toInt() and 0xFF
            var q = p + 12
            val itemId = if (infeVer < 2) readU16(data, q.toLong()).also { q += 2 }
                         else if (infeVer == 2) readU16(data, q.toLong()).also { q += 2 }
                         else readU32(data, q.toLong()).toInt().also { q += 4 }
            q += 2 // protection
            val itemType = if (infeVer >= 2) String(data, q, 4, Charsets.US_ASCII) else "(legacy)"
            res += "infe v=$infeVer id=$itemId type='$itemType' (boxsize=$sz)"
            p += sz
        }
        return res
    }

    private fun dumpIref(data: ByteArray, box: Box): List<String> {
        val res = mutableListOf<String>()
        var p = box.offset.toInt() + box.headerSize
        val ver = data[p].toInt() and 0xFF
        p += 4
        val end = (box.offset + box.size).toInt()
        while (p < end) {
            val sz = readU32(data, p.toLong()).toInt()
            val ty = String(data, p + 4, 4, Charsets.US_ASCII)
            var q = p + 8
            val from = if (ver == 0) readU16(data, q.toLong()).also { q += 2 }
                       else readU32(data, q.toLong()).toInt().also { q += 4 }
            val n = readU16(data, q.toLong()); q += 2
            val tos = mutableListOf<Int>()
            repeat(n) {
                tos += if (ver == 0) readU16(data, q.toLong()).also { q += 2 }
                       else readU32(data, q.toLong()).toInt().also { q += 4 }
            }
            res += "iref '$ty' from=$from to=$tos"
            p += sz
        }
        return res
    }

    private fun dumpIloc(data: ByteArray, box: Box): List<String> {
        val res = mutableListOf<String>()
        var p = box.offset.toInt() + box.headerSize
        val ver = data[p].toInt() and 0xFF
        p += 4
        val b1 = data[p].toInt() and 0xFF
        val b2 = data[p + 1].toInt() and 0xFF
        val osz = (b1 shr 4) and 0xF
        val lsz = b1 and 0xF
        val bosz = (b2 shr 4) and 0xF
        val isz = if (ver == 1 || ver == 2) b2 and 0xF else 0
        p += 2
        res += "iloc v=$ver offset=$osz len=$lsz base=$bosz idx=$isz"
        val cnt = if (ver < 2) readU16(data, p.toLong()).also { p += 2 }
                  else readU32(data, p.toLong()).toInt().also { p += 4 }
        for (i in 0 until cnt) {
            val itemId = if (ver < 2) readU16(data, p.toLong()).also { p += 2 }
                         else readU32(data, p.toLong()).toInt().also { p += 4 }
            var cm = 0
            if (ver == 1 || ver == 2) {
                cm = ((data[p].toInt() and 0xFF) shl 8 or (data[p + 1].toInt() and 0xFF)) and 0xF
                p += 2
            }
            p += 2 // dri
            val baseOff = readUInt(data, p.toLong(), bosz); p += bosz
            val ec = readU16(data, p.toLong()); p += 2
            val extents = mutableListOf<String>()
            repeat(ec) {
                if ((ver == 1 || ver == 2) && isz > 0) p += isz
                val off = readUInt(data, p.toLong(), osz); p += osz
                val len = readUInt(data, p.toLong(), lsz); p += lsz
                extents += "off=$off len=$len"
            }
            res += "  id=$itemId cm=$cm baseOff=$baseOff extents=[${extents.joinToString(", ")}]"
        }
        return res
    }

    private fun findExifItemIds(data: ByteArray, iinfBox: Box): List<Int> {
        val out = mutableListOf<Int>()
        var p = iinfBox.offset.toInt() + iinfBox.headerSize
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
            q += 2
            val itemType = if (infeVer >= 2) String(data, q, 4, Charsets.US_ASCII) else ""
            if (itemType == "Exif") out += itemId
            p += sz
        }
        return out
    }

    private fun locateItem(data: ByteArray, box: Box, wantId: Int): Pair<Long, Long>? {
        var p = box.offset.toInt() + box.headerSize
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
            if (ver == 1 || ver == 2) p += 2
            p += 2 // dri
            val baseOff = readUInt(data, p.toLong(), bosz); p += bosz
            val ec = readU16(data, p.toLong()); p += 2
            var firstExtent: Pair<Long, Long>? = null
            repeat(ec) {
                if ((ver == 1 || ver == 2) && isz > 0) p += isz
                val off = readUInt(data, p.toLong(), osz); p += osz
                val len = readUInt(data, p.toLong(), lsz); p += lsz
                if (firstExtent == null) firstExtent = (baseOff + off) to len
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
