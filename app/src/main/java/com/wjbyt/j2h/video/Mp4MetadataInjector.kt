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

        // Patch mvhd/tkhd shoot time in place (size-preserving).
        creationMp4Time?.let { patchCreationTimes(moovBytes, it) }

        // We deliberately do NOT touch udta. Earlier we injected ©mak/©mod text
        // atoms there for the device name, but that BROKE vivo's location read
        // for SDR videos (the gallery failed to parse ©xyz once extra atoms sat
        // beside it). The device name comes from the vivo `uuid` box below, so
        // ©mak/©mod were redundant anyway. Leaving udta exactly as MediaMuxer
        // wrote it (just ©xyz from setLocation) matches a native vivo video and
        // lets the gallery show 地点 for SDR.

        // Write the (time-patched) moov unchanged in size, then append the vivo
        // `uuid` box (vivoMediaExtInfo) as a top-level box after moov — this is
        // what the gallery reads 拍摄设备 from, and how native vivo videos are laid
        // out. The caller triggers a single re-scan afterwards.
        raf.seek(moov.offset)
        raf.write(moovBytes)
        var end = moov.offset + moovBytes.size
        if (!model.isNullOrBlank()) {
            val uuidBox = buildVivoUuidBox(model)
            raf.seek(end)
            raf.write(uuidBox)
            end += uuidBox.size
        }
        raf.setLength(end)
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
        // Byte-exact replication of a native vivo camera video's uuid box. The
        // payload format is:  "vivo" + JSON + BE32(json byte length) + "cameralbum!"
        // Confirmed by experiment: omitting the BE32 length or the trailing
        // "cameralbum!" marker makes vivo's gallery ignore the box entirely
        // (拍摄设备 stays blank). The JSON also carries the full native field set.
        val json = "{" +
            "\"com.android.camera.temperature\":36," +
            "\"com.android.camera.takenmodel\":\"$safe\"," +
            "\"com.android.camera.moduleid\":\"video\"," +
            "\"com.android.camera.camerafacing\":\"0\"," +
            "\"VideoModuleId\":\"video\"," +
            "\"Lens\":\"Wide\"," +
            "\"MotionTrack\":\"off\"," +
            "\"Facing\":\"back\"," +
            "\"FilmFormat\":\"false\"," +
            "\"MicDevice\":\"MicDevice\"," +
            "\"VideoBeauty\":\"origin\"," +
            "\"VideoSuperNight\":\"off\"," +
            "\"VideoAeLux \":\"300.0\"," +
            "\"videoAngleExpand\":\"0\"," +
            "\"VideoAvailableMemory\":\"4.00G\"," +
            "\"CpuUsage\":\"0.50\"," +
            "\"StartShellTempure\":\"35\"," +
            "\"StartBoardTempure\":\"35\"," +
            "\"VideoIcState\":\"ic_off\"," +
            "\"EndShellTempure\":\"36\"," +
            "\"EndBoardTempure\":\"36\"," +
            "\"version\":2104}"
        val jsonBytes = json.toByteArray(Charsets.UTF_8)
        val tail = "cameralbum!".toByteArray(Charsets.US_ASCII)
        // payload = "vivo" + JSON + BE32(jsonLen) + "cameralbum!"
        val payload = ByteArray(4 + jsonBytes.size + 4 + tail.size)
        var p = 0
        "vivo".toByteArray(Charsets.US_ASCII).let { System.arraycopy(it, 0, payload, p, 4); p += 4 }
        System.arraycopy(jsonBytes, 0, payload, p, jsonBytes.size); p += jsonBytes.size
        writeBE32(payload, p, jsonBytes.size); p += 4
        System.arraycopy(tail, 0, payload, p, tail.size)

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
