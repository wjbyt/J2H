package com.wjbyt.j2h.heif

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Injects EXIF metadata into a HEIF/HEIC file produced by Android's
 * Bitmap.compress(CompressFormat.HEIC, ...).
 *
 * Android's encoder does not write EXIF, so we surgically patch the container per
 * ISO/IEC 23008-12:
 *   1. Add an ItemInfoEntry of type 'Exif' to iinf.
 *   2. Add a 'cdsc' reference (Exif item describes primary image) to iref.
 *   3. Add an ItemLocationEntry to iloc pointing at the appended EXIF blob.
 *   4. Append the EXIF blob (4-byte BE tiff_header_offset + TIFF data) to the file.
 *   5. Adjust existing iloc entries with construction_method=0 by the meta-box growth delta.
 *
 * Verified to round-trip via androidx.exifinterface.media.ExifInterface and to be
 * accepted by libheif-based readers.
 */
object HeifExifInjector {

    fun inject(heicData: ByteArray, exifTiff: ByteArray): ByteArray {
        require(heicData.size >= 16) { "HEIC data too short" }

        val topBoxes = parseTopLevel(heicData)
        val metaIdx = topBoxes.indexOfFirst { it.type == "meta" }
        require(metaIdx >= 0) { "No meta box found" }
        val metaBox = topBoxes[metaIdx]

        // meta is a FullBox: 4 bytes version+flags, then sub-boxes.
        val metaPayloadStart = metaBox.payloadOffset
        val metaPayloadEnd = metaBox.endOffset
        val subStart = metaPayloadStart + 4
        val subBoxes = parseBoxList(heicData, subStart, metaPayloadEnd)

        val pitmBox = subBoxes.firstOrNull { it.type == "pitm" }
            ?: error("No pitm box")
        val primaryItemId = parsePitm(heicData, pitmBox)

        val iinfBox = subBoxes.firstOrNull { it.type == "iinf" }
            ?: error("No iinf box")
        val parsedIinf = parseIinf(heicData, iinfBox)

        val newItemId = ((parsedIinf.entries.maxOfOrNull { it.itemId } ?: 0) + 1)
            .coerceAtLeast(primaryItemId + 1)

        val ilocBox = subBoxes.firstOrNull { it.type == "iloc" }
            ?: error("No iloc box")
        val iloc = parseIloc(heicData, ilocBox)

        val irefBox = subBoxes.firstOrNull { it.type == "iref" }
        val iref = irefBox?.let { parseIref(heicData, it) }
            ?: IrefData(version = 0, refs = mutableListOf())

        // ----- iprp augmentation: add irot=0 box + associate to primary -----
        // Samsung-format HEIC has irot in ipco and associates it with the primary
        // image via ipma. vivo's gallery requires this association before it
        // considers the file 'a phone photo' worth extracting EXIF from.
        val iprpBox = subBoxes.firstOrNull { it.type == "iprp" }
        val newIprpBytes: ByteArray? = iprpBox?.let { ipBox ->
            val parsed = parseIprp(heicData, ipBox)
            // Skip if irot already exists in ipco.
            val hasIrot = parsed.ipcoChildren.any {
                it.size >= 8 &&
                it[4] == 'i'.code.toByte() && it[5] == 'r'.code.toByte() &&
                it[6] == 'o'.code.toByte() && it[7] == 't'.code.toByte()
            }
            if (hasIrot) {
                null
            } else {
                val irotBox = wrapBox("irot", byteArrayOf(0x00))
                val newIpcoChildren = parsed.ipcoChildren + irotBox
                val newIrotIndex = newIpcoChildren.size // 1-based
                val newIpmaEntries = parsed.ipmaEntries.map { e ->
                    if (e.itemId == primaryItemId)
                        e.copy(associations = e.associations + IpmaAssoc(essential = true, propertyIndex = newIrotIndex))
                    else e
                }
                buildIprp(newIpcoChildren, parsed.ipmaVersion, parsed.ipmaFlags, newIpmaEntries)
            }
        }

        // ----- Build new sub-box bytes (sizes are deterministic) -----
        val newIinfBytes = buildIinf(
            parsedIinf.version,
            // flags=1 ⇒ "item is hidden" — Samsung's HEIC sets this on its Exif item;
            // vivo's gallery ignores Exif items that don't have the hidden flag set.
            parsedIinf.entries + IinfEntry(itemId = newItemId, itemType = "Exif", itemName = "", flags = 1)
        )
        val newIrefBytes = buildIref(
            iref.version,
            iref.refs + IrefEntry("cdsc", newItemId, listOf(primaryItemId))
        )

        // We build iloc twice: first with placeholder offsets to learn size,
        // then with real offsets once we know meta growth.
        val placeholderIloc = iloc.copy(
            entries = iloc.entries + IlocEntry(
                itemId = newItemId,
                constructionMethod = 0,
                dataReferenceIndex = 0,
                baseOffset = 0,
                extents = listOf(IlocExtent(extentIndex = 0, extentOffset = 0, extentLength = 0))
            )
        )
        val newIlocSizePredicted = buildIloc(placeholderIloc).size

        val oldIinfSize = iinfBox.size.toInt()
        val oldIrefSize = irefBox?.size?.toInt() ?: 0
        val oldIlocSize = ilocBox.size.toInt()

        val iprpDelta = if (newIprpBytes != null && iprpBox != null) {
            newIprpBytes.size - iprpBox.size.toInt()
        } else 0
        val sizeDelta = (newIinfBytes.size - oldIinfSize) +
                        (newIrefBytes.size - oldIrefSize) +
                        (newIlocSizePredicted - oldIlocSize) +
                        iprpDelta

        val newMetaSize = metaBox.size.toInt() + sizeDelta
        val delta = sizeDelta // top-level shift for everything after meta

        // ----- Compute exif block file offset -----
        val tailStart = metaBox.endOffset + delta
        val tailSize = topBoxes.subList(metaIdx + 1, topBoxes.size)
            .sumOf { it.size }
        val exifBlockOffset = tailStart + tailSize

        // EXIF item data: 4-byte BE tiff_header_offset then TIFF blob.
        val exifBlock = ByteBuffer.allocate(4 + exifTiff.size)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(0)
            .put(exifTiff)
            .array()

        // ----- Build final iloc with shifted offsets and the new entry -----
        val shiftedEntries = iloc.entries.map { entry ->
            if (entry.constructionMethod == 0) {
                // Absolute file offsets; mdat moved by `delta`.
                if (iloc.baseOffsetSize > 0) {
                    entry.copy(baseOffset = entry.baseOffset + delta)
                } else {
                    entry.copy(extents = entry.extents.map {
                        it.copy(extentOffset = it.extentOffset + delta)
                    })
                }
            } else entry
        }
        val newEntry = IlocEntry(
            itemId = newItemId,
            constructionMethod = 0,
            dataReferenceIndex = 0,
            baseOffset = if (iloc.baseOffsetSize > 0) exifBlockOffset else 0L,
            extents = listOf(
                IlocExtent(
                    extentIndex = 0,
                    extentOffset = if (iloc.baseOffsetSize > 0) 0L else exifBlockOffset,
                    extentLength = exifBlock.size.toLong()
                )
            )
        )
        val finalIloc = iloc.copy(entries = shiftedEntries + newEntry)

        // If predicted iloc field widths are too small to hold our new offset, upgrade.
        val finalIlocAdjusted = upgradeIlocFieldSizes(finalIloc, exifBlockOffset, exifBlock.size.toLong())
        val newIlocBytes = buildIloc(finalIlocAdjusted)

        // If field-size upgrade changed iloc size, recompute everything.
        if (newIlocBytes.size != newIlocSizePredicted) {
            val growth = newIlocBytes.size - newIlocSizePredicted
            // Shift new exif offset by growth and rebuild
            val newExifOffset = exifBlockOffset + growth
            val reShifted = iloc.entries.map { entry ->
                if (entry.constructionMethod == 0) {
                    if (iloc.baseOffsetSize > 0) {
                        entry.copy(baseOffset = entry.baseOffset + delta + growth)
                    } else {
                        entry.copy(extents = entry.extents.map {
                            it.copy(extentOffset = it.extentOffset + delta + growth)
                        })
                    }
                } else entry
            }
            val reNewEntry = newEntry.copy(
                baseOffset = if (finalIlocAdjusted.baseOffsetSize > 0) newExifOffset else 0L,
                extents = listOf(
                    IlocExtent(
                        extentIndex = 0,
                        extentOffset = if (finalIlocAdjusted.baseOffsetSize > 0) 0L else newExifOffset,
                        extentLength = exifBlock.size.toLong()
                    )
                )
            )
            val rebuilt = finalIlocAdjusted.copy(entries = reShifted + reNewEntry)
            val rebuiltBytes = buildIloc(rebuilt)
            require(rebuiltBytes.size == newIlocBytes.size) {
                "iloc size unstable after upgrade"
            }
            return assemble(
                heicData, topBoxes, metaIdx, metaBox, subBoxes,
                newIinfBytes, newIrefBytes, irefBox, rebuiltBytes,
                newIprpBytes,
                newMetaSizeWithGrowth(newMetaSize, growth),
                exifBlock
            )
        }

        return assemble(
            heicData, topBoxes, metaIdx, metaBox, subBoxes,
            newIinfBytes, newIrefBytes, irefBox, newIlocBytes,
            newIprpBytes,
            newMetaSize, exifBlock
        )
    }

    private fun newMetaSizeWithGrowth(base: Int, growth: Int) = base + growth

    private fun assemble(
        src: ByteArray,
        topBoxes: List<RawBox>,
        metaIdx: Int,
        oldMeta: RawBox,
        subBoxes: List<RawBox>,
        newIinfBytes: ByteArray,
        newIrefBytes: ByteArray,
        oldIref: RawBox?,
        newIlocBytes: ByteArray,
        newIprpBytes: ByteArray?,
        newMetaSize: Int,
        exifBlock: ByteArray
    ): ByteArray {
        val newMetaPayload = ByteArrayOutputStream()
        // version+flags of meta FullBox
        newMetaPayload.write(src, oldMeta.payloadOffset.toInt(), 4)
        var irefWritten = false
        for (sub in subBoxes) {
            when (sub.type) {
                "iinf" -> newMetaPayload.write(newIinfBytes)
                "iref" -> { newMetaPayload.write(newIrefBytes); irefWritten = true }
                "iloc" -> newMetaPayload.write(newIlocBytes)
                "iprp" -> if (newIprpBytes != null) newMetaPayload.write(newIprpBytes)
                          else newMetaPayload.write(src, sub.offset.toInt(), sub.size.toInt())
                else -> newMetaPayload.write(src, sub.offset.toInt(), sub.size.toInt())
            }
        }
        if (oldIref == null && !irefWritten) {
            newMetaPayload.write(newIrefBytes)
        }
        check(newMetaPayload.size() + 8 == newMetaSize) {
            "meta size mismatch: built=${newMetaPayload.size() + 8}, expected=$newMetaSize"
        }

        val out = ByteArrayOutputStream()
        // Boxes before meta
        for (i in 0 until metaIdx) {
            out.write(src, topBoxes[i].offset.toInt(), topBoxes[i].size.toInt())
        }
        // New meta
        writeBoxHeader(out, "meta", newMetaSize)
        out.write(newMetaPayload.toByteArray())
        // Tail
        for (i in metaIdx + 1 until topBoxes.size) {
            out.write(src, topBoxes[i].offset.toInt(), topBoxes[i].size.toInt())
        }
        // Exif blob
        out.write(exifBlock)
        return out.toByteArray()
    }

    private fun writeBoxHeader(out: ByteArrayOutputStream, type: String, size: Int) {
        out.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(size).array())
        out.write(type.toByteArray(Charsets.US_ASCII))
    }

    // ---------- Box-level parsing ----------

    data class RawBox(
        val type: String,
        val offset: Long,
        val size: Long,        // total size of box (header + payload)
        val headerSize: Int,   // 8 or 16 (large size)
    ) {
        val payloadOffset: Long get() = offset + headerSize
        val endOffset: Long get() = offset + size
    }

    private fun parseTopLevel(data: ByteArray): List<RawBox> =
        parseBoxList(data, 0L, data.size.toLong())

    private fun parseBoxList(data: ByteArray, start: Long, end: Long): List<RawBox> {
        val out = mutableListOf<RawBox>()
        var p = start
        while (p < end) {
            require(p + 8 <= end) { "Truncated box header at $p" }
            val size32 = readU32(data, p)
            val type = readType(data, p + 4)
            val (size, headerSize) = when (size32) {
                1L -> {
                    require(p + 16 <= end) { "Truncated largesize header at $p" }
                    val largeSize = readU64(data, p + 8)
                    largeSize to 16
                }
                0L -> (end - p) to 8 // box extends to end
                else -> size32 to 8
            }
            require(size >= headerSize) { "Bad box size $size at $p" }
            require(p + size <= end) { "Box at $p ($type, size=$size) overflows container ($end)" }
            out += RawBox(type, p, size, headerSize)
            p += size
        }
        return out
    }

    private fun parsePitm(data: ByteArray, box: RawBox): Int {
        val p = box.payloadOffset.toInt()
        val version = data[p].toInt() and 0xFF
        val itemPos = p + 4
        return if (version == 0) readU16(data, itemPos.toLong()).toInt()
        else readU32(data, itemPos.toLong()).toInt()
    }

    // ---------- iinf ----------

    data class IinfEntry(
        val itemId: Int, val itemType: String, val itemName: String,
        /** infe box flags (24-bit). bit 0 = "item is hidden". */
        val flags: Int = 0
    )
    data class IinfData(val version: Int, val entries: List<IinfEntry>)

    private fun parseIinf(data: ByteArray, box: RawBox): IinfData {
        var p = box.payloadOffset.toInt()
        val version = data[p].toInt() and 0xFF
        p += 4 // skip version+flags
        val entryCount: Int
        if (version == 0) {
            entryCount = readU16(data, p.toLong()).toInt(); p += 2
        } else {
            entryCount = readU32(data, p.toLong()).toInt(); p += 4
        }
        val entries = mutableListOf<IinfEntry>()
        repeat(entryCount) {
            // Each entry is an 'infe' box
            val entrySize = readU32(data, p.toLong()).toInt()
            val entryType = readType(data, p + 4L)
            require(entryType == "infe") { "iinf contains non-infe box: $entryType" }
            val infeVer = data[p + 8].toInt() and 0xFF
            // 3-byte flags follow the version byte.
            val flagsValue = ((data[p + 9].toInt() and 0xFF) shl 16) or
                             ((data[p + 10].toInt() and 0xFF) shl 8) or
                             (data[p + 11].toInt() and 0xFF)
            var q = p + 12 // version+flags consumed
            val itemId: Int
            val itemType: String
            val itemName: String
            if (infeVer < 2) {
                // Legacy: item_ID(16), prot_idx(16), item_name(str), content_type(str)
                itemId = readU16(data, q.toLong()).toInt(); q += 2
                q += 2 // protection_index
                val nameEnd = indexOfNull(data, q, p + entrySize)
                itemName = String(data, q, nameEnd - q, Charsets.UTF_8)
                itemType = "" // legacy entries lack a 4cc type
            } else {
                if (infeVer == 2) {
                    itemId = readU16(data, q.toLong()).toInt(); q += 2
                } else {
                    itemId = readU32(data, q.toLong()).toInt(); q += 4
                }
                q += 2 // protection_index
                itemType = readType(data, q.toLong()); q += 4
                val nameEnd = indexOfNull(data, q, p + entrySize)
                itemName = String(data, q, nameEnd - q, Charsets.UTF_8)
            }
            entries += IinfEntry(itemId, itemType, itemName, flags = flagsValue)
            p += entrySize
        }
        return IinfData(version, entries)
    }

    private fun buildIinf(version: Int, entries: List<IinfEntry>): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(byteArrayOf(version.toByte(), 0, 0, 0)) // version + flags
        if (version == 0) writeU16(body, entries.size)
        else writeU32(body, entries.size.toLong())
        for (e in entries) body.write(buildInfe(e))
        return wrapBox("iinf", body.toByteArray())
    }

    private fun buildInfe(e: IinfEntry): ByteArray {
        // Use version 2 (uint16 item_id) for compactness; supports 4cc item_type.
        val infeVer = if (e.itemId > 0xFFFF) 3 else 2
        val body = ByteArrayOutputStream()
        // version + 24-bit flags (bit 0 = hidden). Preserve flags from source so we
        // don't accidentally unset hidden bits the original encoder set on its tiles.
        val f = e.flags
        body.write(infeVer)
        body.write((f ushr 16) and 0xFF)
        body.write((f ushr 8) and 0xFF)
        body.write(f and 0xFF)
        if (infeVer == 2) writeU16(body, e.itemId) else writeU32(body, e.itemId.toLong())
        writeU16(body, 0) // protection_index
        body.write(if (e.itemType.isEmpty()) "    ".toByteArray(Charsets.US_ASCII)
                   else e.itemType.toByteArray(Charsets.US_ASCII))
        body.write(e.itemName.toByteArray(Charsets.UTF_8))
        body.write(0)
        return wrapBox("infe", body.toByteArray())
    }

    // ---------- iref ----------

    data class IrefEntry(val refType: String, val fromItemId: Int, val toItemIds: List<Int>)
    data class IrefData(val version: Int, val refs: MutableList<IrefEntry>)

    private fun parseIref(data: ByteArray, box: RawBox): IrefData {
        var p = box.payloadOffset.toInt()
        val version = data[p].toInt() and 0xFF
        p += 4
        val end = box.endOffset.toInt()
        val refs = mutableListOf<IrefEntry>()
        while (p < end) {
            val sz = readU32(data, p.toLong()).toInt()
            val refType = readType(data, p + 4L)
            var q = p + 8
            val fromId = if (version == 0) {
                val v = readU16(data, q.toLong()).toInt(); q += 2; v
            } else {
                val v = readU32(data, q.toLong()).toInt(); q += 4; v
            }
            val cnt = readU16(data, q.toLong()).toInt(); q += 2
            val to = mutableListOf<Int>()
            repeat(cnt) {
                val v = if (version == 0) {
                    val x = readU16(data, q.toLong()).toInt(); q += 2; x
                } else {
                    val x = readU32(data, q.toLong()).toInt(); q += 4; x
                }
                to += v
            }
            refs += IrefEntry(refType, fromId, to)
            p += sz
        }
        return IrefData(version, refs)
    }

    private fun buildIref(versionIn: Int, refs: List<IrefEntry>): ByteArray {
        // Promote to version 1 (uint32 ids) only if any id > 65535
        val needV1 = refs.any { it.fromItemId > 0xFFFF || it.toItemIds.any { id -> id > 0xFFFF } }
        val version = if (needV1) 1 else versionIn
        val body = ByteArrayOutputStream()
        body.write(byteArrayOf(version.toByte(), 0, 0, 0))
        for (r in refs) {
            val sub = ByteArrayOutputStream()
            if (version == 0) writeU16(sub, r.fromItemId) else writeU32(sub, r.fromItemId.toLong())
            writeU16(sub, r.toItemIds.size)
            for (id in r.toItemIds) {
                if (version == 0) writeU16(sub, id) else writeU32(sub, id.toLong())
            }
            body.write(wrapBox(r.refType, sub.toByteArray()))
        }
        return wrapBox("iref", body.toByteArray())
    }

    // ---------- iloc ----------

    data class IlocExtent(val extentIndex: Long, val extentOffset: Long, val extentLength: Long)
    data class IlocEntry(
        val itemId: Int,
        val constructionMethod: Int,
        val dataReferenceIndex: Int,
        val baseOffset: Long,
        val extents: List<IlocExtent>
    )
    data class IlocData(
        val version: Int,
        val offsetSize: Int,
        val lengthSize: Int,
        val baseOffsetSize: Int,
        val indexSize: Int,
        val entries: List<IlocEntry>
    )

    private fun parseIloc(data: ByteArray, box: RawBox): IlocData {
        var p = box.payloadOffset.toInt()
        val version = data[p].toInt() and 0xFF
        p += 4
        val b1 = data[p].toInt() and 0xFF
        val b2 = data[p + 1].toInt() and 0xFF
        val offsetSize = (b1 shr 4) and 0xF
        val lengthSize = b1 and 0xF
        val baseOffsetSize = (b2 shr 4) and 0xF
        val indexSize = if (version == 1 || version == 2) b2 and 0xF else 0
        p += 2
        val itemCount: Int
        if (version < 2) {
            itemCount = readU16(data, p.toLong()).toInt(); p += 2
        } else {
            itemCount = readU32(data, p.toLong()).toInt(); p += 4
        }
        val entries = mutableListOf<IlocEntry>()
        repeat(itemCount) {
            val itemId = if (version < 2) {
                readU16(data, p.toLong()).toInt().also { p += 2 }
            } else {
                readU32(data, p.toLong()).toInt().also { p += 4 }
            }
            var constructionMethod = 0
            if (version == 1 || version == 2) {
                // 12 bits reserved + 4 bits construction_method
                val cm = ((data[p].toInt() and 0xFF) shl 8) or (data[p + 1].toInt() and 0xFF)
                constructionMethod = cm and 0xF
                p += 2
            }
            val dri = readU16(data, p.toLong()).toInt(); p += 2
            val baseOffset = readUInt(data, p.toLong(), baseOffsetSize); p += baseOffsetSize
            val extentCount = readU16(data, p.toLong()).toInt(); p += 2
            val extents = mutableListOf<IlocExtent>()
            repeat(extentCount) {
                val idx = if ((version == 1 || version == 2) && indexSize > 0) {
                    readUInt(data, p.toLong(), indexSize).also { p += indexSize }
                } else 0L
                val off = readUInt(data, p.toLong(), offsetSize); p += offsetSize
                val len = readUInt(data, p.toLong(), lengthSize); p += lengthSize
                extents += IlocExtent(idx, off, len)
            }
            entries += IlocEntry(itemId, constructionMethod, dri, baseOffset, extents)
        }
        return IlocData(version, offsetSize, lengthSize, baseOffsetSize, indexSize, entries)
    }

    /** Promote field widths if any value would overflow. */
    private fun upgradeIlocFieldSizes(iloc: IlocData, extraOffset: Long, extraLength: Long): IlocData {
        var os = iloc.offsetSize
        var ls = iloc.lengthSize
        var bos = iloc.baseOffsetSize

        fun fits(v: Long, bytes: Int): Boolean = when (bytes) {
            0 -> v == 0L
            4 -> v in 0..0xFFFFFFFFL
            8 -> true
            else -> false
        }
        fun ensure(v: Long, current: Int): Int {
            if (fits(v, current)) return current
            return if (v <= 0xFFFFFFFFL) (if (current < 4) 4 else 8) else 8
        }

        for (e in iloc.entries) {
            bos = ensure(e.baseOffset, bos.coerceAtLeast(if (e.baseOffset != 0L) 4 else bos))
            for (x in e.extents) {
                os = ensure(x.extentOffset, os.coerceAtLeast(if (x.extentOffset != 0L) 4 else os))
                ls = ensure(x.extentLength, ls.coerceAtLeast(if (x.extentLength != 0L) 4 else ls))
            }
        }
        os = ensure(extraOffset, os.coerceAtLeast(4))
        ls = ensure(extraLength, ls.coerceAtLeast(4))

        return iloc.copy(offsetSize = os, lengthSize = ls, baseOffsetSize = bos)
    }

    private fun buildIloc(iloc: IlocData): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(byteArrayOf(iloc.version.toByte(), 0, 0, 0))
        body.write(((iloc.offsetSize shl 4) or iloc.lengthSize).toByte().toInt())
        val b2 = (iloc.baseOffsetSize shl 4) or
                 (if (iloc.version == 1 || iloc.version == 2) iloc.indexSize else 0)
        body.write(b2.toByte().toInt())
        if (iloc.version < 2) writeU16(body, iloc.entries.size)
        else writeU32(body, iloc.entries.size.toLong())
        for (e in iloc.entries) {
            if (iloc.version < 2) writeU16(body, e.itemId)
            else writeU32(body, e.itemId.toLong())
            if (iloc.version == 1 || iloc.version == 2) {
                writeU16(body, e.constructionMethod and 0xF)
            }
            writeU16(body, e.dataReferenceIndex)
            writeUInt(body, e.baseOffset, iloc.baseOffsetSize)
            writeU16(body, e.extents.size)
            for (x in e.extents) {
                if ((iloc.version == 1 || iloc.version == 2) && iloc.indexSize > 0) {
                    writeUInt(body, x.extentIndex, iloc.indexSize)
                }
                writeUInt(body, x.extentOffset, iloc.offsetSize)
                writeUInt(body, x.extentLength, iloc.lengthSize)
            }
        }
        return wrapBox("iloc", body.toByteArray())
    }

    // ---------- helpers ----------

    private fun wrapBox(type: String, body: ByteArray): ByteArray {
        val total = 8 + body.size
        val out = ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN)
        out.putInt(total)
        out.put(type.toByteArray(Charsets.US_ASCII))
        out.put(body)
        return out.array()
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

    private fun readUInt(d: ByteArray, p: Long, bytes: Int): Long = when (bytes) {
        0 -> 0L
        4 -> readU32(d, p)
        8 -> readU64(d, p)
        else -> error("Unsupported field width: $bytes")
    }

    private fun writeU16(out: ByteArrayOutputStream, v: Int) {
        out.write((v ushr 8) and 0xFF)
        out.write(v and 0xFF)
    }

    private fun writeU32(out: ByteArrayOutputStream, v: Long) {
        out.write(((v ushr 24) and 0xFF).toInt())
        out.write(((v ushr 16) and 0xFF).toInt())
        out.write(((v ushr 8) and 0xFF).toInt())
        out.write((v and 0xFF).toInt())
    }

    private fun writeU64(out: ByteArrayOutputStream, v: Long) {
        for (k in 7 downTo 0) out.write(((v ushr (k * 8)) and 0xFF).toInt())
    }

    private fun writeUInt(out: ByteArrayOutputStream, v: Long, bytes: Int) = when (bytes) {
        0 -> Unit
        4 -> writeU32(out, v)
        8 -> writeU64(out, v)
        else -> error("Unsupported field width: $bytes")
    }

    private fun readType(d: ByteArray, p: Long): String {
        val i = p.toInt()
        return String(d, i, 4, Charsets.US_ASCII)
    }

    private fun indexOfNull(d: ByteArray, from: Int, until: Int): Int {
        var i = from
        while (i < until) { if (d[i] == 0.toByte()) return i; i++ }
        return until
    }

    // ---------- iprp (item properties) ----------

    data class IpmaAssoc(val essential: Boolean, val propertyIndex: Int) // 1-based
    data class IpmaEntry(val itemId: Int, val associations: List<IpmaAssoc>)
    data class IprpData(
        val ipcoChildren: List<ByteArray>, // each is a complete box (with 8-byte header)
        val ipmaVersion: Int,
        val ipmaFlags: Int,
        val ipmaEntries: List<IpmaEntry>
    )

    private fun parseIprp(data: ByteArray, iprpBox: RawBox): IprpData {
        val subs = parseBoxList(data, iprpBox.payloadOffset, iprpBox.endOffset)
        val ipco = subs.firstOrNull { it.type == "ipco" }
            ?: error("iprp without ipco")
        val ipma = subs.firstOrNull { it.type == "ipma" }
            ?: error("iprp without ipma")

        // Each child of ipco is a complete property box; preserve as raw bytes.
        val children = parseBoxList(data, ipco.payloadOffset, ipco.endOffset).map {
            data.copyOfRange(it.offset.toInt(), it.endOffset.toInt())
        }

        var p = ipma.payloadOffset.toInt()
        val ipmaVer = data[p].toInt() and 0xFF
        val ipmaFlags = ((data[p + 1].toInt() and 0xFF) shl 16) or
                        ((data[p + 2].toInt() and 0xFF) shl 8) or
                        (data[p + 3].toInt() and 0xFF)
        p += 4
        val ec = readU32(data, p.toLong()).toInt(); p += 4
        val entries = mutableListOf<IpmaEntry>()
        repeat(ec) {
            val itemId = if (ipmaVer < 1) {
                readU16(data, p.toLong()).toInt().also { p += 2 }
            } else {
                readU32(data, p.toLong()).toInt().also { p += 4 }
            }
            val n = data[p].toInt() and 0xFF; p += 1
            val assocs = mutableListOf<IpmaAssoc>()
            repeat(n) {
                if ((ipmaFlags and 1) != 0) {
                    val v = readU16(data, p.toLong()); p += 2
                    assocs += IpmaAssoc((v ushr 15) == 1, v and 0x7FFF)
                } else {
                    val v = data[p].toInt() and 0xFF; p += 1
                    assocs += IpmaAssoc((v ushr 7) == 1, v and 0x7F)
                }
            }
            entries += IpmaEntry(itemId, assocs)
        }
        return IprpData(children, ipmaVer, ipmaFlags, entries)
    }

    private fun buildIprp(
        ipcoChildren: List<ByteArray>,
        ipmaVer: Int, ipmaFlags: Int,
        ipmaEntries: List<IpmaEntry>
    ): ByteArray {
        // Build ipco
        val ipcoBody = ByteArrayOutputStream()
        for (c in ipcoChildren) ipcoBody.write(c)
        val ipcoBox = wrapBox("ipco", ipcoBody.toByteArray())

        // Build ipma
        val ipmaBody = ByteArrayOutputStream()
        ipmaBody.write(ipmaVer)
        ipmaBody.write((ipmaFlags ushr 16) and 0xFF)
        ipmaBody.write((ipmaFlags ushr 8) and 0xFF)
        ipmaBody.write(ipmaFlags and 0xFF)
        writeU32(ipmaBody, ipmaEntries.size.toLong())
        for (e in ipmaEntries) {
            if (ipmaVer < 1) writeU16(ipmaBody, e.itemId)
            else writeU32(ipmaBody, e.itemId.toLong())
            ipmaBody.write(e.associations.size and 0xFF)
            for (a in e.associations) {
                if ((ipmaFlags and 1) != 0) {
                    val v = ((if (a.essential) 0x8000 else 0) or (a.propertyIndex and 0x7FFF))
                    writeU16(ipmaBody, v)
                } else {
                    val v = ((if (a.essential) 0x80 else 0) or (a.propertyIndex and 0x7F))
                    ipmaBody.write(v)
                }
            }
        }
        val ipmaBox = wrapBox("ipma", ipmaBody.toByteArray())

        return wrapBox("iprp", ipcoBox + ipmaBox)
    }
}
