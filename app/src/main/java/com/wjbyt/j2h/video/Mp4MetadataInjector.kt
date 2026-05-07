package com.wjbyt.j2h.video

import java.io.File
import java.io.RandomAccessFile

/**
 * Adds iTunes-style `©mak` / `©mod` atoms to an MP4's `moov/udta` box so
 * vivo gallery shows "拍摄设备" on the video info panel.
 *
 * Android's [android.media.MediaMuxer] only exposes `setLocation()` (writes
 * `©xyz`) and `setOrientationHint()` (writes track header rotation). It has
 * no API for make/model, so we patch the closed file in place.
 *
 * Layout transform (only the typical Android MediaMuxer case is handled —
 * `moov` is the last top-level box):
 *
 *   IN:  ftyp / mdat / moov(...whatever...)
 *   OUT: ftyp / mdat / moov(...udta(©mak, ©mod, original-content)...)
 *
 * If the moov already has a `udta` we append our atoms inside it; otherwise
 * we add a new `udta` at the end of moov. mdat doesn't move so no `stco` /
 * `co64` patching is needed.
 */
object Mp4MetadataInjector {

    /** Returns true when something was written, false on no-op or failure. */
    fun inject(
        file: File,
        make: String?,
        model: String?
    ): Boolean {
        if (make.isNullOrBlank() && model.isNullOrBlank()) return false
        if (!file.exists() || file.length() < 16) return false

        return try {
            RandomAccessFile(file, "rw").use { raf -> doInject(raf, make, model) }
        } catch (_: Exception) {
            false
        }
    }

    private fun doInject(
        raf: RandomAccessFile,
        make: String?,
        model: String?
    ): Boolean {
        val fileLen = raf.length()
        val moov = findTopLevelBox(raf, "moov", fileLen) ?: return false

        // Only handle the trailing-moov layout. Patching a leading moov would
        // require shifting all of mdat, which means re-streaming a hundreds-
        // of-megabytes file just to add ~50 bytes of metadata.
        if (moov.offset + moov.size != fileLen) return false

        // Read the entire moov into memory. Typically <1 MB even for long
        // videos because moov holds only metadata + sample tables, not media.
        raf.seek(moov.offset)
        val moovBytes = ByteArray(moov.size.toInt())
        raf.readFully(moovBytes)

        // Build the iTunes-style atoms we want to add.
        val atoms = mutableListOf<ByteArray>()
        if (!make.isNullOrBlank())  atoms += buildAppleAtom("©mak", make)
        if (!model.isNullOrBlank()) atoms += buildAppleAtom("©mod", model)
        if (atoms.isEmpty()) return false
        val addSize = atoms.sumOf { it.size }

        // Find udta inside moov (offset relative to moov start).
        val udta = findChildBox(moovBytes, 8 /* skip moov 8-byte header */,
                                moovBytes.size, "udta")

        val newMoov: ByteArray = if (udta == null) {
            // No existing udta — create one at end of moov containing our atoms.
            val udtaSize = 8 + addSize
            val udtaBytes = ByteArray(udtaSize)
            writeBE32(udtaBytes, 0, udtaSize)
            udtaBytes[4] = 'u'.code.toByte(); udtaBytes[5] = 'd'.code.toByte()
            udtaBytes[6] = 't'.code.toByte(); udtaBytes[7] = 'a'.code.toByte()
            var p = 8
            for (a in atoms) { System.arraycopy(a, 0, udtaBytes, p, a.size); p += a.size }

            val nm = ByteArray(moovBytes.size + udtaSize)
            System.arraycopy(moovBytes, 0, nm, 0, moovBytes.size)
            System.arraycopy(udtaBytes, 0, nm, moovBytes.size, udtaSize)
            writeBE32(nm, 0, nm.size)  // patched moov size
            nm
        } else {
            // Append our atoms inside existing udta. We slice moov in two,
            // append our atoms after the existing udta content, then continue
            // with the rest of moov.
            val udtaEnd = udta.offset + udta.size  // offset within moov
            val nm = ByteArray(moovBytes.size + addSize)
            System.arraycopy(moovBytes, 0, nm, 0, udtaEnd)
            var p = udtaEnd
            for (a in atoms) { System.arraycopy(a, 0, nm, p, a.size); p += a.size }
            System.arraycopy(moovBytes, udtaEnd, nm, p, moovBytes.size - udtaEnd)
            // Patch sizes: udta + moov.
            writeBE32(nm, udta.offset, udta.size + addSize)
            writeBE32(nm, 0, nm.size)
            nm
        }

        // Replace moov in the file. moov was at the end, so we just truncate
        // and rewrite — no offset tables in mdat to fix up.
        raf.seek(moov.offset)
        raf.write(newMoov)
        raf.setLength(moov.offset + newMoov.size)
        return true
    }

    // ----- box parsing -----

    private data class TopBox(val type: String, val offset: Long, val size: Long)
    private data class ChildBox(val type: String, val offset: Int, val size: Int)

    private fun findTopLevelBox(raf: RandomAccessFile, type: String, fileLen: Long): TopBox? {
        var p = 0L
        val header = ByteArray(8)
        while (p + 8 <= fileLen) {
            raf.seek(p)
            raf.readFully(header)
            val size32 = readBE32(header, 0).toLong() and 0xFFFFFFFFL
            val ty = String(header, 4, 4, Charsets.US_ASCII)
            val boxSize: Long = when (size32) {
                0L -> fileLen - p  // extends to end
                1L -> {
                    if (p + 16 > fileLen) return null
                    val ls = ByteArray(8)
                    raf.seek(p + 8)
                    raf.readFully(ls)
                    readBE64(ls, 0)
                }
                else -> size32
            }
            if (boxSize < 8 || p + boxSize > fileLen) return null
            if (ty == type) return TopBox(ty, p, boxSize)
            p += boxSize
        }
        return null
    }

    private fun findChildBox(buf: ByteArray, start: Int, end: Int, type: String): ChildBox? {
        var p = start
        while (p + 8 <= end) {
            val size32 = readBE32(buf, p).toLong() and 0xFFFFFFFFL
            val ty = String(buf, p + 4, 4, Charsets.US_ASCII)
            val boxSize: Int = when (size32) {
                0L -> end - p
                1L -> {
                    if (p + 16 > end) return null
                    readBE64(buf, p + 8).toInt()  // unlikely huge for a child
                }
                else -> size32.toInt()
            }
            if (boxSize < 8 || p + boxSize > end) return null
            if (ty == type) return ChildBox(ty, p, boxSize)
            p += boxSize
        }
        return null
    }

    // ----- atom builder -----

    /**
     * Build an iTunes-style "©XXX" atom containing a single UTF-8 string.
     * Layout:
     *   [outer size 4][outer type 4]
     *     [data size 4]['data' 4][version+flags 4][locale 4][utf8 string]
     *
     * `version+flags` of 0x00000001 means "UTF-8 text".
     */
    private fun buildAppleAtom(typeStr: String, value: String): ByteArray {
        require(typeStr.length == 4)
        val payload = value.toByteArray(Charsets.UTF_8)
        val dataSize = 16 + payload.size      // 8 hdr + 4 ver/flags + 4 locale + payload
        val outerSize = 8 + dataSize          // 8 hdr + data atom
        val out = ByteArray(outerSize)
        // outer
        writeBE32(out, 0, outerSize)
        out[4] = typeStr[0].code.toByte()  // 0xA9 byte for ©
        out[5] = typeStr[1].code.toByte()
        out[6] = typeStr[2].code.toByte()
        out[7] = typeStr[3].code.toByte()
        // data atom
        writeBE32(out, 8, dataSize)
        out[12] = 'd'.code.toByte(); out[13] = 'a'.code.toByte()
        out[14] = 't'.code.toByte(); out[15] = 'a'.code.toByte()
        writeBE32(out, 16, 1)  // version=0, flags=1 (UTF-8 text)
        writeBE32(out, 20, 0)  // locale = 0
        System.arraycopy(payload, 0, out, 24, payload.size)
        return out
    }

    // ----- byte helpers -----

    private fun readBE32(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or
        ((b[off + 1].toInt() and 0xFF) shl 16) or
        ((b[off + 2].toInt() and 0xFF) shl 8) or
        (b[off + 3].toInt() and 0xFF)

    private fun readBE64(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
        return v
    }

    private fun writeBE32(b: ByteArray, off: Int, v: Int) {
        b[off]     = ((v ushr 24) and 0xFF).toByte()
        b[off + 1] = ((v ushr 16) and 0xFF).toByte()
        b[off + 2] = ((v ushr 8) and 0xFF).toByte()
        b[off + 3] = (v and 0xFF).toByte()
    }
}
