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

    /**
     * Returns true when something was written, false on no-op or failure.
     *
     * @param creationMp4Time when non-null, the source's shoot time as an MP4
     *   timestamp (seconds since 1904-01-01 UTC = unixSeconds + 2082844800).
     *   Patched into mvhd + every tkhd so the gallery's "时间" shows the shoot
     *   time, not the encode time MediaMuxer stamps.
     */
    fun inject(
        file: File,
        make: String?,
        model: String?,
        creationMp4Time: Long? = null
    ): Boolean {
        if (make.isNullOrBlank() && model.isNullOrBlank() && creationMp4Time == null) return false
        if (!file.exists() || file.length() < 16) return false

        return try {
            RandomAccessFile(file, "rw").use { raf -> doInject(raf, make, model, creationMp4Time) }
        } catch (_: Exception) {
            false
        }
    }

    private fun doInject(
        raf: RandomAccessFile,
        make: String?,
        model: String?,
        creationMp4Time: Long?
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

        // Patch mvhd/tkhd shoot time in place (size-preserving) before any
        // udta resize, so the patched bytes survive the splice below.
        creationMp4Time?.let { patchCreationTimes(moovBytes, it) }

        // Build the atoms to insert into udta.
        // ©mak/©mod: QuickTime text format [len][lang][utf8] — same as ©xyz.
        // vivoMediaExtInfo: vivo's proprietary atom whose JSON takenmodel field
        //   populates 拍摄设备. Must stay INSIDE udta (not after moov) so moov
        //   remains the last top-level box; vivo shows -1×-1 otherwise.
        val atoms = mutableListOf<ByteArray>()
        if (!make.isNullOrBlank())  atoms += buildQtAtom("©mak", make)
        if (!model.isNullOrBlank()) {
            atoms += buildQtAtom("©mod", model)
            atoms += buildVivoUuidBox(model)
        }

        // Nothing to add to udta — but we may have patched times; write back.
        if (atoms.isEmpty()) {
            raf.seek(moov.offset)
            raf.write(moovBytes)
            return creationMp4Time != null
        }
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
        // IMPORTANT: moov must remain the LAST top-level box. vivo fails to
        // parse the file (shows -1×-1 and no metadata) when any box follows
        // moov. So the vivo uuid box goes INSIDE udta, not after moov.
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
     * Build a QuickTime-style "©XXX" udta text atom:
     *   [outer size 4][©type 4][text length 2][language 2][utf8 text]
     * This matches the ©xyz atom MediaMuxer.setLocation writes, so vivo parses
     * the whole udta consistently. Language 0x15C7 mirrors that ©xyz.
     */
    private fun buildQtAtom(typeStr: String, value: String): ByteArray {
        require(typeStr.length == 4)
        val payload = value.toByteArray(Charsets.UTF_8)
        val outerSize = 8 + 4 + payload.size   // hdr(8) + [len:2][lang:2] + text
        val out = ByteArray(outerSize)
        writeBE32(out, 0, outerSize)
        out[4] = typeStr[0].code.toByte()  // 0xA9 byte for ©
        out[5] = typeStr[1].code.toByte()
        out[6] = typeStr[2].code.toByte()
        out[7] = typeStr[3].code.toByte()
        out[8]  = ((payload.size ushr 8) and 0xFF).toByte()  // text length
        out[9]  = (payload.size and 0xFF).toByte()
        out[10] = 0x15.toByte()             // language (same as ©xyz)
        out[11] = 0xC7.toByte()
        System.arraycopy(payload, 0, out, 12, payload.size)
        return out
    }

    /**
     * Build vivo's proprietary `uuid` box that its gallery reads "拍摄设备" from.
     * Layout (mirrors a native vivo camera video):
     *   [size 4]["uuid" 4]["vivoMediaExtInfo" 16]["vivo" + JSON]
     * The displayed device comes from the JSON's `com.android.camera.takenmodel`.
     */
    private fun buildVivoUuidBox(model: String): ByteArray {
        val magic = "vivoMediaExtInfo".toByteArray(Charsets.US_ASCII)  // exactly 16 bytes
        val safe = model.replace("\\", "").replace("\"", "")
        val json = "vivo{" +
            "\"com.android.camera.takenmodel\":\"$safe\"," +
            "\"com.android.camera.moduleid\":\"video\"," +
            "\"com.android.camera.camerafacing\":\"0\"}"
        val payload = json.toByteArray(Charsets.UTF_8)
        val size = 8 + magic.size + payload.size
        val out = ByteArray(size)
        writeBE32(out, 0, size)
        out[4] = 'u'.code.toByte(); out[5] = 'u'.code.toByte()
        out[6] = 'i'.code.toByte(); out[7] = 'd'.code.toByte()
        System.arraycopy(magic, 0, out, 8, 16)
        System.arraycopy(payload, 0, out, 24, payload.size)
        return out
    }

    // ----- creation-time patching -----

    /** Walk moov → mvhd + every trak/tkhd, overwrite creation+modification time. */
    private fun patchCreationTimes(moov: ByteArray, mp4Time: Long) {
        var p = 8
        while (p + 8 <= moov.size) {
            val sz = childSize(moov, p, moov.size) ?: break
            when (String(moov, p + 4, 4, Charsets.US_ASCII)) {
                "mvhd" -> patchBoxTime(moov, p, mp4Time)
                "trak" -> {
                    var q = p + 8
                    val tend = p + sz
                    while (q + 8 <= tend) {
                        val z = childSize(moov, q, tend) ?: break
                        if (String(moov, q + 4, 4, Charsets.US_ASCII) == "tkhd")
                            patchBoxTime(moov, q, mp4Time)
                        q += z
                    }
                }
            }
            p += sz
        }
    }

    /** mvhd/tkhd layout: [size 4][type 4][version 1][flags 3][creation][modification]… */
    private fun patchBoxTime(moov: ByteArray, boxOff: Int, mp4Time: Long) {
        if (boxOff + 12 > moov.size) return
        val ver = moov[boxOff + 8].toInt() and 0xFF
        if (ver == 1) {
            if (boxOff + 28 > moov.size) return
            writeBE64(moov, boxOff + 12, mp4Time)
            writeBE64(moov, boxOff + 20, mp4Time)
        } else {
            if (boxOff + 20 > moov.size) return
            writeBE32(moov, boxOff + 12, mp4Time.toInt())
            writeBE32(moov, boxOff + 16, mp4Time.toInt())
        }
    }

    private fun childSize(buf: ByteArray, p: Int, end: Int): Int? {
        if (p + 8 > end) return null
        val s = readBE32(buf, p).toLong() and 0xFFFFFFFFL
        val sz = when (s) {
            0L -> (end - p).toLong()
            1L -> if (p + 16 <= end) readBE64(buf, p + 8) else return null
            else -> s
        }
        if (sz < 8 || p + sz > end) return null
        return sz.toInt()
    }

    /**
     * vivo's marketing model name (e.g. "vivo X200 Ultra") via system property,
     * falling back to [android.os.Build.MODEL]. Mirrors HeicConverter's logic
     * so video device strings match the JPG/DNG path.
     */
    fun marketModel(): String {
        for (key in listOf(
            "ro.vivo.market.name", "ro.vivo.product.marketname",
            "ro.product.marketname", "ro.config.marketing_name",
            "ro.product.model.display"
        )) {
            try {
                val c = Class.forName("android.os.SystemProperties")
                val v = c.getMethod("get", String::class.java).invoke(null, key) as? String
                if (!v.isNullOrBlank() && !v.equals(android.os.Build.MODEL, true)) return v
            } catch (_: Throwable) {}
        }
        return android.os.Build.MODEL
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

    private fun writeBE64(b: ByteArray, off: Int, v: Long) {
        for (i in 0 until 8) b[off + i] = ((v ushr ((7 - i) * 8)) and 0xFF).toByte()
    }
}
