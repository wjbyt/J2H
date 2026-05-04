package com.wjbyt.j2h.heif

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Builds a still-image HEIF container around a single HEVC-encoded frame.
 *
 * Output layout (ISO/IEC 23008-12):
 *   ftyp  major='heic' compat=[mif1, heic, heix, msf1]
 *   meta  (FullBox v=0)
 *     hdlr  handler='pict'
 *     pitm  primary_item_id=1
 *     iloc  item 1 -> [offset_in_file, length] in mdat
 *     iinf  one infe v2 entry: id=1 type='hvc1'
 *     iprp
 *       ipco
 *         hvcC   (parameter sets + profile/level/bit-depth metadata)
 *         ispe   width × height
 *         colr   nclx — primaries/transfer/matrix
 *         pixi   3 channels × bit-depth
 *       ipma   item 1 → [hvcC, ispe, colr, pixi] all marked essential
 *   mdat  length-prefixed image NAL units
 *
 * Used to package HEVC Main10 bitstreams into HEIC since Android's MediaMuxer's
 * HEIF output silently falls back to MP4 when given >8-bit HEVC.
 */
class HeifContainerWriter(
    private val width: Int,
    private val height: Int,
    private val hvcCBody: ByteArray,        // body of hvcC (HEVCDecoderConfigurationRecord)
    private val imageData: ByteArray,       // length-prefixed NAL units (mdat payload)
    private val bitDepth: Int = 10,
    private val colourPrimaries: Int = 1,   // 1 = BT.709
    private val transferCharacteristics: Int = 1,
    private val matrixCoefficients: Int = 1,
    private val fullRange: Boolean = false
) {
    fun build(): ByteArray {
        val ftyp = buildFtyp()

        // ---- ipco property children ----
        val hvcCBox = wrapBox("hvcC", hvcCBody)
        val ispeBox = run {
            val b = ByteArrayOutputStream()
            b.write(byteArrayOf(0, 0, 0, 0)) // version+flags
            writeU32(b, width.toLong())
            writeU32(b, height.toLong())
            wrapBox("ispe", b.toByteArray())
        }
        val colrBox = run {
            val b = ByteArrayOutputStream()
            b.write("nclx".toByteArray(Charsets.US_ASCII))
            writeU16(b, colourPrimaries)
            writeU16(b, transferCharacteristics)
            writeU16(b, matrixCoefficients)
            b.write((if (fullRange) 0x80 else 0))
            wrapBox("colr", b.toByteArray())
        }
        val pixiBox = run {
            val b = ByteArrayOutputStream()
            b.write(byteArrayOf(0, 0, 0, 0)) // version+flags
            b.write(3)
            b.write(bitDepth); b.write(bitDepth); b.write(bitDepth)
            wrapBox("pixi", b.toByteArray())
        }
        val ipcoChildren = listOf(hvcCBox, ispeBox, colrBox, pixiBox)
        // Property indices in ipco are 1-based.
        val ipcoBox = wrapBox("ipco", ipcoChildren.reduce { a, b -> a + b })

        // ipma: item 1 -> all 4 properties, all essential
        val ipmaBox = run {
            val b = ByteArrayOutputStream()
            b.write(byteArrayOf(0, 0, 0, 0)) // version=0, flags=0 (=> small entry counts/IDs)
            writeU32(b, 1) // entry_count
            writeU16(b, 1) // item_ID
            b.write(ipcoChildren.size) // association_count
            for (idx in ipcoChildren.indices) {
                val essential = 0x80 // mark essential: hvcC, ispe, pixi must be
                b.write(essential or ((idx + 1) and 0x7F)) // 1-based property index
            }
            wrapBox("ipma", b.toByteArray())
        }
        val iprpBox = wrapBox("iprp", ipcoBox + ipmaBox)

        // ---- meta sub-boxes that don't depend on iloc offsets yet ----
        val hdlrBox = run {
            val b = ByteArrayOutputStream()
            b.write(byteArrayOf(0, 0, 0, 0)) // version+flags
            writeU32(b, 0) // pre_defined
            b.write("pict".toByteArray(Charsets.US_ASCII))
            writeU32(b, 0); writeU32(b, 0); writeU32(b, 0) // reserved [3]
            b.write(0) // name = empty + null terminator
            wrapBox("hdlr", b.toByteArray())
        }
        val pitmBox = run {
            val b = ByteArrayOutputStream()
            b.write(byteArrayOf(0, 0, 0, 0)) // version=0, flags=0
            writeU16(b, 1) // primary item id
            wrapBox("pitm", b.toByteArray())
        }
        val iinfBox = run {
            val infe = run {
                val ib = ByteArrayOutputStream()
                ib.write(byteArrayOf(2, 0, 0, 0)) // version=2, flags=0
                writeU16(ib, 1) // item_ID
                writeU16(ib, 0) // item_protection_index
                ib.write("hvc1".toByteArray(Charsets.US_ASCII))
                ib.write(0) // item_name = empty + null terminator
                wrapBox("infe", ib.toByteArray())
            }
            val b = ByteArrayOutputStream()
            b.write(byteArrayOf(0, 0, 0, 0)) // iinf version=0, flags=0
            writeU16(b, 1) // entry_count
            b.write(infe)
            wrapBox("iinf", b.toByteArray())
        }

        // ---- iloc (placeholder — real offset filled in once final layout is known) ----
        // iloc: version=1 (so we can specify construction_method=0 = file_offset),
        // offset_size=4, length_size=4, base_offset_size=0, index_size=0, item_count=1.
        // entry: item_ID=1, cm=0, dri=0, base_offset=0(0bytes), extent_count=1,
        //        extent_offset=<filled>, extent_length=imageData.size.
        // Total iloc payload bytes (constant): 4(vf) + 1(b1) + 1(b2) + 2(cnt)
        //   + 2(itemID) + 2(cm) + 2(dri) + 2(extent_count) + 4(off) + 4(len) = 24
        // iloc box total size = 8 + 24 = 32 bytes.
        // We'll know the final offset only after assembling the front-matter.
        val ilocBoxSize = 32

        // Assemble metaBox payload size by computing all sub-box sizes.
        val metaPayloadFixed = 4 /* version+flags */ +
                               hdlrBox.size + pitmBox.size + iinfBox.size +
                               iprpBox.size + ilocBoxSize
        val metaBoxSize = 8 + metaPayloadFixed

        // Final layout: ftyp + meta + mdat. mdat sits at offset (ftyp.size + metaBoxSize).
        // imageData lives inside mdat after the 8-byte mdat header.
        val mdatStart = ftyp.size + metaBoxSize
        val imageOffsetInFile = mdatStart + 8 // after mdat header

        val ilocBox = run {
            val b = ByteArrayOutputStream()
            b.write(byteArrayOf(1, 0, 0, 0)) // version=1, flags=0
            b.write((4 shl 4) or 4)           // offset_size=4, length_size=4
            b.write((0 shl 4) or 0)           // base_offset_size=0, index_size=0
            writeU16(b, 1)                    // item_count
            writeU16(b, 1)                    // item_ID
            writeU16(b, 0)                    // 12 reserved + cm=0 (file offset)
            writeU16(b, 0)                    // data_reference_index
            // base_offset omitted (size=0)
            writeU16(b, 1)                    // extent_count
            writeU32(b, imageOffsetInFile.toLong())
            writeU32(b, imageData.size.toLong())
            wrapBox("iloc", b.toByteArray())
        }
        check(ilocBox.size == ilocBoxSize) { "iloc size mismatch: ${ilocBox.size} != $ilocBoxSize" }

        // Build meta.
        val metaPayload = ByteArrayOutputStream()
        metaPayload.write(byteArrayOf(0, 0, 0, 0)) // version+flags
        metaPayload.write(hdlrBox)
        metaPayload.write(pitmBox)
        metaPayload.write(iinfBox)
        metaPayload.write(iprpBox)
        metaPayload.write(ilocBox)
        val metaBox = wrapBox("meta", metaPayload.toByteArray())

        // mdat
        val mdatBox = run {
            val total = 8 + imageData.size
            val out = ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN)
            out.putInt(total)
            out.put("mdat".toByteArray(Charsets.US_ASCII))
            out.put(imageData)
            out.array()
        }

        return ftyp + metaBox + mdatBox
    }

    private fun buildFtyp(): ByteArray {
        // major_brand = heic, minor=0, compatible = [mif1, heic, heix, msf1]
        val brands = listOf("heic", "mif1", "heic", "heix", "msf1")
        val payload = ByteArrayOutputStream()
        payload.write(brands[0].toByteArray(Charsets.US_ASCII)) // major
        writeU32(payload, 0)                                    // minor_version
        for (i in 1 until brands.size) payload.write(brands[i].toByteArray(Charsets.US_ASCII))
        return wrapBox("ftyp", payload.toByteArray())
    }

    private fun wrapBox(type: String, body: ByteArray): ByteArray {
        val total = 8 + body.size
        val buf = ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(total)
        buf.put(type.toByteArray(Charsets.US_ASCII))
        buf.put(body)
        return buf.array()
    }

    private fun writeU16(o: ByteArrayOutputStream, v: Int) {
        o.write((v ushr 8) and 0xFF); o.write(v and 0xFF)
    }
    private fun writeU32(o: ByteArrayOutputStream, v: Long) {
        o.write(((v ushr 24) and 0xFF).toInt())
        o.write(((v ushr 16) and 0xFF).toInt())
        o.write(((v ushr 8) and 0xFF).toInt())
        o.write((v and 0xFF).toInt())
    }
}
