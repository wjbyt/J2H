package com.wjbyt.j2h.exif

import java.io.InputStream

/**
 * Extracts the raw TIFF-format EXIF payload from a JPEG file's APP1 segment.
 *
 * JPEG structure: SOI (FFD8) + segments. APP1 marker = FFE1.
 * APP1 EXIF segment payload: "Exif\0\0" (6 bytes) followed by TIFF data.
 *
 * Returns the TIFF block (starting with II*\0 or MM\0*) without the "Exif\0\0" prefix,
 * since HEIF Exif item data uses tiff_header_offset and bare TIFF.
 */
object JpegExifExtractor {

    /** Returns the bare TIFF block, or null if no EXIF APP1 segment is present. */
    fun extractTiff(input: InputStream): ByteArray? {
        val bin = input.buffered()

        // SOI
        if (bin.read() != 0xFF || bin.read() != 0xD8) return null

        while (true) {
            // Skip filler 0xFF bytes
            var b = bin.read()
            if (b == -1) return null
            while (b == 0xFF) {
                val n = bin.read()
                if (n == -1) return null
                if (n != 0xFF) { b = n; break }
            }

            val marker = b
            // Markers without length: SOI(D8), EOI(D9), RSTn(D0..D7), TEM(01)
            if (marker == 0xD9 || marker == 0xDA) return null // EOI or SOS — done with metadata
            if (marker == 0x01 || (marker in 0xD0..0xD7)) continue

            val lenHi = bin.read(); val lenLo = bin.read()
            if (lenHi == -1 || lenLo == -1) return null
            val segLen = (lenHi shl 8) or lenLo // includes the 2 length bytes
            val payloadLen = segLen - 2
            if (payloadLen < 0) return null

            if (marker == 0xE1) {
                // APP1 — possibly EXIF
                val payload = readFully(bin, payloadLen) ?: return null
                if (payload.size >= 6 &&
                    payload[0] == 'E'.code.toByte() &&
                    payload[1] == 'x'.code.toByte() &&
                    payload[2] == 'i'.code.toByte() &&
                    payload[3] == 'f'.code.toByte() &&
                    payload[4] == 0.toByte() &&
                    payload[5] == 0.toByte()
                ) {
                    return payload.copyOfRange(6, payload.size)
                }
                // Some files put XMP in APP1 first; keep scanning
            } else {
                // Skip segment payload
                var remaining = payloadLen
                while (remaining > 0) {
                    val skipped = bin.skip(remaining.toLong())
                    if (skipped <= 0) {
                        val one = bin.read()
                        if (one == -1) return null
                        remaining -= 1
                    } else {
                        remaining -= skipped.toInt()
                    }
                }
            }
        }
    }

    private fun readFully(input: java.io.InputStream, n: Int): ByteArray? {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r == -1) return null
            off += r
        }
        return buf
    }
}
