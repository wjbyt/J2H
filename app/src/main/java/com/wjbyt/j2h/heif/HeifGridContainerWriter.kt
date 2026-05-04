package com.wjbyt.j2h.heif

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * HEIF grid container writer. Wraps multiple HEVC-encoded tile bitstreams
 * into a single .heic with a 'grid' derived item as the primary, referencing
 * the tiles via 'dimg' iref. Used for images that exceed the encoder's
 * single-frame capacity (50MP / 200MP DNGs etc.).
 *
 * Layout (ISO/IEC 23008-12 §6.6.2 grid + general HEIF):
 *   ftyp  major='heic' compat=[mif1, heic, heix, msf1]
 *   meta
 *     hdlr 'pict'
 *     pitm primary_item_id = (last item, the grid)
 *     iloc one entry per item (tiles + grid)
 *     iinf  N tile infe v2 type='hvc1' + 1 grid infe v2 type='grid'
 *     iref  dimg from=grid_id, to=[tile_ids...] in raster order
 *     iprp
 *       ipco  shared properties:
 *         #1 hvcC          (used by all tiles)
 *         #2 ispe(tileW, tileH)
 *         #3 colr nclx
 *         #4 pixi 3×bitDepth
 *         #5 ispe(outputW, outputH)  (for grid)
 *       ipma
 *         each tile -> [hvcC, tile_ispe, colr, pixi]
 *         grid       -> [grid_ispe, colr, pixi]
 *   mdat tile_data[0..N-1] then grid_struct (8 bytes ImageGrid)
 */
class HeifGridContainerWriter(
    private val outputW: Int,
    private val outputH: Int,
    private val tileW: Int,
    private val tileH: Int,
    private val rows: Int,
    private val cols: Int,
    private val hvcCBody: ByteArray,
    private val tileData: List<ByteArray>,        // length-prefixed NAL units per tile
    private val bitDepth: Int = 10,
    private val colourPrimaries: Int = 12,
    private val transferCharacteristics: Int = 1,
    private val matrixCoefficients: Int = 1,
    private val fullRange: Boolean = false
) {
    init {
        require(rows in 1..256 && cols in 1..256)
        require(tileData.size == rows * cols) { "tileData count != rows*cols" }
    }

    fun build(): ByteArray {
        val ftyp = buildFtyp()

        // Tile item IDs are 1..N, grid is N+1.
        val numTiles = rows * cols
        val tileIds = (1..numTiles).toList()
        val gridId = numTiles + 1

        // ---- ipco children ----
        val hvcCBox = wrapBox("hvcC", hvcCBody)
        val tileIspeBox = run {
            val b = ByteArrayOutputStream()
            b.write(byteArrayOf(0, 0, 0, 0))
            writeU32(b, tileW.toLong()); writeU32(b, tileH.toLong())
            wrapBox("ispe", b.toByteArray())
        }
        val colrBox = run {
            val b = ByteArrayOutputStream()
            b.write("nclx".toByteArray(Charsets.US_ASCII))
            writeU16(b, colourPrimaries); writeU16(b, transferCharacteristics); writeU16(b, matrixCoefficients)
            b.write(if (fullRange) 0x80 else 0)
            wrapBox("colr", b.toByteArray())
        }
        val pixiBox = run {
            val b = ByteArrayOutputStream()
            b.write(byteArrayOf(0, 0, 0, 0))
            b.write(3); b.write(bitDepth); b.write(bitDepth); b.write(bitDepth)
            wrapBox("pixi", b.toByteArray())
        }
        val gridIspeBox = run {
            val b = ByteArrayOutputStream()
            b.write(byteArrayOf(0, 0, 0, 0))
            writeU32(b, outputW.toLong()); writeU32(b, outputH.toLong())
            wrapBox("ispe", b.toByteArray())
        }

        // ipco property indices (1-based):
        //   1: hvcC, 2: tile ispe, 3: colr, 4: pixi, 5: grid ispe
        val ipcoBox = wrapBox("ipco", hvcCBox + tileIspeBox + colrBox + pixiBox + gridIspeBox)

        val ipmaBox = run {
            val b = ByteArrayOutputStream()
            b.write(byteArrayOf(0, 0, 0, 0)) // version=0, flags=0
            writeU32(b, (numTiles + 1).toLong()) // entry_count
            // Each tile -> [1=hvcC essential, 2=tile_ispe, 3=colr, 4=pixi]
            for (id in tileIds) {
                writeU16(b, id)
                b.write(4) // association_count
                b.write(0x80 or 1) // hvcC essential
                b.write(0x80 or 2) // tile ispe essential
                b.write(0x80 or 3) // colr
                b.write(0x80 or 4) // pixi
            }
            // Grid -> [5=grid_ispe, 3=colr, 4=pixi]
            writeU16(b, gridId)
            b.write(3)
            b.write(0x80 or 5)
            b.write(0x80 or 3)
            b.write(0x80 or 4)
            wrapBox("ipma", b.toByteArray())
        }
        val iprpBox = wrapBox("iprp", ipcoBox + ipmaBox)

        // ---- Other meta sub-boxes (sizes constant w.r.t. iloc offsets) ----
        val hdlrBox = run {
            val b = ByteArrayOutputStream()
            b.write(byteArrayOf(0, 0, 0, 0))
            writeU32(b, 0)
            b.write("pict".toByteArray(Charsets.US_ASCII))
            writeU32(b, 0); writeU32(b, 0); writeU32(b, 0)
            b.write(0)
            wrapBox("hdlr", b.toByteArray())
        }
        val pitmBox = run {
            val b = ByteArrayOutputStream()
            b.write(byteArrayOf(0, 0, 0, 0))
            writeU16(b, gridId)
            wrapBox("pitm", b.toByteArray())
        }
        val iinfBox = run {
            val body = ByteArrayOutputStream()
            body.write(byteArrayOf(0, 0, 0, 0)) // version=0
            writeU16(body, numTiles + 1) // entry_count
            for (id in tileIds) body.write(buildInfe(id, "hvc1"))
            body.write(buildInfe(gridId, "grid"))
            wrapBox("iinf", body.toByteArray())
        }
        val irefBox = run {
            val body = ByteArrayOutputStream()
            body.write(byteArrayOf(0, 0, 0, 0)) // iref version=0
            // dimg from=gridId, to=[tile1, tile2, ...] (raster order)
            val sub = ByteArrayOutputStream()
            writeU16(sub, gridId)
            writeU16(sub, numTiles)
            for (id in tileIds) writeU16(sub, id)
            body.write(wrapBox("dimg", sub.toByteArray()))
            wrapBox("iref", body.toByteArray())
        }

        // Grid struct = 8 bytes (version 0, flags 0, rows-1, cols-1, w16, h16).
        val gridStruct = run {
            val b = ByteArrayOutputStream()
            b.write(0)                  // version
            b.write(0)                  // flags (16-bit dimensions)
            b.write(rows - 1)
            b.write(cols - 1)
            writeU16(b, outputW)
            writeU16(b, outputH)
            b.toByteArray()
        }

        // ---- iloc with computed offsets ----
        // Layout in mdat (after 8-byte mdat header):
        //   tileData[0], tileData[1], ..., tileData[N-1], gridStruct
        // We need each item's iloc.extent_offset (absolute file offset).
        // First we need to compute meta size which depends on iloc size, which
        // depends on number of items. iloc size is constant for fixed widths.
        //
        // iloc payload: 4 (vf) + 1 (b1) + 1 (b2) + 2 (count)
        //              + per item: 2(id) + 2(cm) + 2(dri) + 0(base) + 2(extentCnt)
        //                          + 4(off) + 4(len)  = 16 bytes/item
        // For (numTiles + 1) items: ilocBoxSize = 8 + 8 + 16*(numTiles+1).
        val ilocBoxSize = 8 + 8 + 16 * (numTiles + 1)

        val metaPayloadSize = 4 + hdlrBox.size + pitmBox.size + iinfBox.size +
                              irefBox.size + iprpBox.size + ilocBoxSize
        val metaBoxSize = 8 + metaPayloadSize
        val mdatStart = ftyp.size + metaBoxSize
        val mdatPayloadStart = mdatStart + 8 // after mdat header

        // Compute per-tile offsets in file.
        val tileOffsets = LongArray(numTiles)
        var cursor = mdatPayloadStart.toLong()
        for (i in 0 until numTiles) {
            tileOffsets[i] = cursor
            cursor += tileData[i].size
        }
        val gridOffset = cursor

        val ilocBox = run {
            val b = ByteArrayOutputStream()
            b.write(byteArrayOf(1, 0, 0, 0))   // version=1
            b.write((4 shl 4) or 4)             // offset_size=4, length_size=4
            b.write((0 shl 4) or 0)             // base_offset_size=0, index_size=0
            writeU16(b, numTiles + 1)            // item_count

            for (i in 0 until numTiles) {
                writeU16(b, tileIds[i])
                writeU16(b, 0)                   // 12 reserved + cm=0
                writeU16(b, 0)                   // data_reference_index
                writeU16(b, 1)                   // extent_count
                writeU32(b, tileOffsets[i])
                writeU32(b, tileData[i].size.toLong())
            }
            // Grid item
            writeU16(b, gridId)
            writeU16(b, 0)
            writeU16(b, 0)
            writeU16(b, 1)
            writeU32(b, gridOffset)
            writeU32(b, gridStruct.size.toLong())
            wrapBox("iloc", b.toByteArray())
        }
        check(ilocBox.size == ilocBoxSize) { "iloc size mismatch: ${ilocBox.size} != $ilocBoxSize" }

        // ---- assemble meta ----
        val metaPayload = ByteArrayOutputStream()
        metaPayload.write(byteArrayOf(0, 0, 0, 0))
        metaPayload.write(hdlrBox)
        metaPayload.write(pitmBox)
        metaPayload.write(iinfBox)
        metaPayload.write(irefBox)
        metaPayload.write(iprpBox)
        metaPayload.write(ilocBox)
        check(metaPayload.size() == metaPayloadSize)
        val metaBox = wrapBox("meta", metaPayload.toByteArray())

        // ---- assemble mdat ----
        val mdatPayloadSize = tileData.sumOf { it.size } + gridStruct.size
        val mdatBox = run {
            val total = 8 + mdatPayloadSize
            val out = ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN)
            out.putInt(total)
            out.put("mdat".toByteArray(Charsets.US_ASCII))
            for (td in tileData) out.put(td)
            out.put(gridStruct)
            out.array()
        }

        return ftyp + metaBox + mdatBox
    }

    private fun buildInfe(itemId: Int, type4cc: String): ByteArray {
        val b = ByteArrayOutputStream()
        b.write(byteArrayOf(2, 0, 0, 0)) // version=2, flags=0
        writeU16(b, itemId)
        writeU16(b, 0) // protection_index
        b.write(type4cc.toByteArray(Charsets.US_ASCII))
        b.write(0)     // empty item_name + null terminator
        return wrapBox("infe", b.toByteArray())
    }

    private fun buildFtyp(): ByteArray {
        val brands = listOf("heic", "mif1", "heic", "heix", "msf1")
        val payload = ByteArrayOutputStream()
        payload.write(brands[0].toByteArray(Charsets.US_ASCII))
        writeU32(payload, 0)
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
