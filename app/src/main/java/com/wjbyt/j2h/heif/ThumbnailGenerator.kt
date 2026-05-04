package com.wjbyt.j2h.heif

import android.content.Context
import android.graphics.Bitmap
import androidx.heifwriter.HeifWriter
import java.io.File

/**
 * Generates a thumbnail HEVC bitstream + decoder config from a source bitmap,
 * by piggy-backing on Android's HeifWriter (writing a tiny 1-image .heic to a
 * temp file) and then parsing out just the hvc1 bitstream and hvcC config.
 *
 * Why: vendor galleries (vivo, Samsung) seem to require a separate thumbnail
 * hvc1 item with thmb iref → primary before they treat a HEIC as a "complete"
 * phone photo and surface its EXIF.
 */
object ThumbnailGenerator {

    data class Thumb(val bitstream: ByteArray, val hvcC: ByteArray,
                     val width: Int, val height: Int)

    /** Returns null on failure. Thumbnail is downsampled to fit within [maxDim]. */
    fun generate(context: Context, src: Bitmap, maxDim: Int = 320): Thumb? {
        // Compute target dimensions (preserve aspect ratio, both axes even for 4:2:0).
        val sw = src.width; val sh = src.height
        val scale = if (sw >= sh) maxDim.toFloat() / sw else maxDim.toFloat() / sh
        var tw = (sw * scale).toInt() and 1.inv()
        var th = (sh * scale).toInt() and 1.inv()
        if (tw < 16) tw = 16
        if (th < 16) th = 16

        val small = try {
            Bitmap.createScaledBitmap(src, tw, th, true)
        } catch (_: Throwable) { return null }

        val tmp = File.createTempFile("j2h_thumb_", ".heic", context.cacheDir)
        try {
            val writer = try {
                HeifWriter.Builder(tmp.absolutePath, tw, th, HeifWriter.INPUT_MODE_BITMAP)
                    .setQuality(70)
                    .setMaxImages(1)
                    .setGridEnabled(false)
                    .build()
            } catch (e: Throwable) { return null }
            try {
                writer.start()
                writer.addBitmap(small)
                writer.stop(30_000L)
            } finally {
                writer.close()
                if (small !== src) small.recycle()
            }

            val heicBytes = tmp.readBytes()
            return extractHvc1(heicBytes, tw, th)
        } finally { tmp.delete() }
    }

    /**
     * Parse the HeifWriter-produced single-frame HEIC and extract the hvc1
     * bitstream (length-prefixed NAL units) and hvcC config record body.
     */
    private fun extractHvc1(heic: ByteArray, w: Int, h: Int): Thumb? {
        val top = parseTopLevel(heic)
        val meta = top.firstOrNull { it.type == "meta" } ?: return null
        val mdat = top.firstOrNull { it.type == "mdat" } ?: return null

        val subStart = meta.offset + meta.headerSize + 4
        val subEnd = meta.offset + meta.size
        val subs = parseList(heic, subStart, subEnd)

        // hvcC body
        val iprp = subs.firstOrNull { it.type == "iprp" } ?: return null
        val iprpSubs = parseList(heic, iprp.offset + iprp.headerSize, iprp.offset + iprp.size)
        val ipco = iprpSubs.firstOrNull { it.type == "ipco" } ?: return null
        val ipcoSubs = parseList(heic, ipco.offset + ipco.headerSize, ipco.offset + ipco.size)
        val hvcCBox = ipcoSubs.firstOrNull { it.type == "hvcC" } ?: return null
        val hvcCBody = heic.copyOfRange(
            (hvcCBox.offset + hvcCBox.headerSize).toInt(),
            (hvcCBox.offset + hvcCBox.size).toInt()
        )

        // Image bitstream: iloc tells us where the single hvc1 item lives.
        val iloc = subs.firstOrNull { it.type == "iloc" } ?: return null
        val (off, len) = locateFirstItem(heic, iloc) ?: return null
        if (off < 0 || len <= 0 || off + len > heic.size) return null
        val bitstream = heic.copyOfRange(off.toInt(), (off + len).toInt())

        return Thumb(bitstream, hvcCBody, w, h)
    }

    private fun locateFirstItem(data: ByteArray, iloc: Box): Pair<Long, Long>? {
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
        val cnt = if (ver < 2) {
            val v = readU16(data, p.toLong()); p += 2; v
        } else {
            val v = readU32(data, p.toLong()).toInt(); p += 4; v
        }
        if (cnt == 0) return null
        // First entry
        if (ver < 2) p += 2 else p += 4
        if (ver == 1 || ver == 2) p += 2
        p += 2
        val baseOff = if (bosz == 4) readU32(data, p.toLong()) else 0L
        p += bosz
        val ec = readU16(data, p.toLong()); p += 2
        if (ec == 0) return null
        if ((ver == 1 || ver == 2) && isz > 0) p += isz
        val o = if (osz == 4) readU32(data, p.toLong()) else 0L
        p += osz
        val l = if (lsz == 4) readU32(data, p.toLong()) else 0L
        return (baseOff + o) to l
    }

    private data class Box(val type: String, val offset: Long, val size: Long, val headerSize: Int)

    private fun parseTopLevel(data: ByteArray) = parseList(data, 0L, data.size.toLong())

    private fun parseList(data: ByteArray, start: Long, end: Long): List<Box> {
        val out = mutableListOf<Box>()
        var p = start
        while (p < end - 8) {
            val sz32 = readU32(data, p)
            val type = String(data, (p + 4).toInt(), 4, Charsets.US_ASCII)
            val (size, hdr) = when (sz32) {
                1L -> {
                    val v = readU64(data, p + 8); v to 16
                }
                0L -> (end - p) to 8
                else -> sz32 to 8
            }
            if (size < hdr || p + size > end) break
            out += Box(type, p, size, hdr)
            p += size
        }
        return out
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
}
