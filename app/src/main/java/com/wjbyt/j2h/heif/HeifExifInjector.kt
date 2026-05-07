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

    fun inject(heicData: ByteArray, exifTiff: ByteArray): ByteArray =
        inject(heicData, exifTiff, thumb = null)

    fun inject(
        heicData: ByteArray,
        exifTiff: ByteArray,
        thumb: ThumbnailGenerator.Thumb?
    ): ByteArray {
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
        val rawPrimaryItemId = parsePitm(heicData, pitmBox)

        val iinfBox = subBoxes.firstOrNull { it.type == "iinf" }
            ?: error("No iinf box")
        val rawParsedIinf = parseIinf(heicData, iinfBox)

        val ilocBox = subBoxes.firstOrNull { it.type == "iloc" }
            ?: error("No iloc box")
        val rawIloc = parseIloc(heicData, ilocBox)

        val irefBox = subBoxes.firstOrNull { it.type == "iref" }
        val rawIref = irefBox?.let { parseIref(heicData, it) }
            ?: IrefData(version = 0, refs = mutableListOf())

        // ----- Samsung-style item ID renumbering -----
        // HeifWriter assigns large IDs (10000+); Samsung uses 1..N. The first
        // infe entry's bytes are the ONLY remaining structural diff between
        // our output and Samsung's after the meta/top-level reorder, and is
        // the most plausible reason vivo's MediaScanner skips our EXIF.
        // Map: tiles (in dimg.to) → 1..N, grid → N+1, other items → N+2..M
        val dimgRef = rawIref.refs.firstOrNull { it.refType == "dimg" }
        val tileIds = dimgRef?.toItemIds ?: emptyList()
        val idMap = mutableMapOf<Int, Int>()
        var nextRemap = 1
        for (id in tileIds) idMap[id] = nextRemap++
        if (rawPrimaryItemId !in idMap) idMap[rawPrimaryItemId] = nextRemap++
        for (e in rawParsedIinf.entries) {
            if (e.itemId !in idMap) idMap[e.itemId] = nextRemap++
        }
        // Helper: remap a single item id (returns identity if not in map).
        fun rm(id: Int): Int = idMap[id] ?: id
        val primaryItemId = rm(rawPrimaryItemId)
        val parsedIinf = rawParsedIinf.copy(
            entries = rawParsedIinf.entries.map { it.copy(itemId = rm(it.itemId)) }
        )
        val iloc = rawIloc.copy(
            entries = rawIloc.entries.map { it.copy(itemId = rm(it.itemId)) }
        )
        val iref = rawIref.copy(refs = rawIref.refs.map {
            it.copy(
                fromItemId = rm(it.fromItemId),
                toItemIds = it.toItemIds.map { id -> rm(id) }
            )
        }.toMutableList())

        val baseNewId = nextRemap.coerceAtLeast(primaryItemId + 1)
        val thumbItemId = if (thumb != null) baseNewId else 0
        val newItemId = if (thumb != null) baseNewId + 1 else baseNewId

        // ----- iprp augmentation -----
        // Add (a) irot box associated with the primary image, and (b) when a
        // thumbnail bitstream is provided, the thumb's hvcC + ispe + irot props
        // and an ipma entry binding them to the new thumbnail item.
        // vivo's gallery requires this Samsung-style structure before it
        // surfaces EXIF.
        val iprpBox = subBoxes.firstOrNull { it.type == "iprp" }
        val newIprpBytes: ByteArray? = iprpBox?.let { ipBox ->
            val parsed = parseIprp(heicData, ipBox)
            val children = parsed.ipcoChildren.toMutableList()
            // Remap ipma item ids alongside iinf/iloc/iref so all references
            // line up after the Samsung-style renumbering above.
            val ipma = parsed.ipmaEntries.map {
                it.copy(itemId = rm(it.itemId))
            }.toMutableList()

            // Grid irot
            val hasIrot = children.any {
                it.size >= 8 && it[4] == 'i'.code.toByte() && it[5] == 'r'.code.toByte() &&
                it[6] == 'o'.code.toByte() && it[7] == 't'.code.toByte()
            }
            var changed = false
            if (!hasIrot) {
                children += wrapBox("irot", byteArrayOf(0x00))
                val irotIdx = children.size
                for ((i, e) in ipma.withIndex()) {
                    if (e.itemId == primaryItemId) {
                        ipma[i] = e.copy(associations = e.associations +
                            IpmaAssoc(essential = true, propertyIndex = irotIdx))
                    }
                }
                changed = true
            }

            // Thumbnail's three properties + ipma entry, if thumbnail provided.
            if (thumb != null) {
                children += wrapBox("hvcC", thumb.hvcC)
                val thumbHvcCIdx = children.size
                val ispeBody = ByteArrayOutputStream().apply {
                    write(byteArrayOf(0, 0, 0, 0))                  // version+flags
                    writeU32(this, thumb.width.toLong())
                    writeU32(this, thumb.height.toLong())
                }
                children += wrapBox("ispe", ispeBody.toByteArray())
                val thumbIspeIdx = children.size
                children += wrapBox("irot", byteArrayOf(0x00))
                val thumbIrotIdx = children.size

                ipma += IpmaEntry(
                    itemId = thumbItemId,
                    associations = listOf(
                        IpmaAssoc(true, thumbHvcCIdx),
                        IpmaAssoc(true, thumbIspeIdx),
                        IpmaAssoc(true, thumbIrotIdx)
                    )
                )
                changed = true
            }

            if (changed)
                buildIprp(children, parsed.ipmaVersion, parsed.ipmaFlags, ipma)
            else null
        }

        // ----- Build new pitm with remapped primary id (size invariant) -----
        val pitmVer = parsePitmVersion(heicData, pitmBox)
        val newPitmBytes = buildPitm(pitmVer, primaryItemId)
        require(newPitmBytes.size == pitmBox.size.toInt()) {
            "pitm size changed unexpectedly after id remap: " +
            "${pitmBox.size} -> ${newPitmBytes.size}"
        }

        // ----- Build new sub-box bytes (sizes are deterministic) -----
        val newIinfEntries = parsedIinf.entries.toMutableList()
        // Samsung's accepted-by-vivo HEIC has the Exif item HIDDEN (flag=1)
        // and the thumbnail listed BEFORE the Exif entry in iinf. Match that
        // exactly — earlier guesses to the contrary did not move the needle.
        if (thumb != null) {
            newIinfEntries += IinfEntry(itemId = thumbItemId, itemType = "hvc1", itemName = "", flags = 1)
        }
        newIinfEntries += IinfEntry(itemId = newItemId, itemType = "Exif", itemName = "", flags = 1)
        val newIinfBytes = buildIinf(parsedIinf.version, newIinfEntries)

        val newRefs = iref.refs.toMutableList()
        if (thumb != null) {
            // Match Samsung iref order: ..dimg, thmb, cdsc..
            newRefs += IrefEntry("thmb", thumbItemId, listOf(primaryItemId))
        }
        newRefs += IrefEntry("cdsc", newItemId, listOf(primaryItemId))
        val newIrefBytes = buildIref(iref.version, newRefs)

        // We build iloc twice: first with placeholder offsets to learn size,
        // then with real offsets once we know meta growth.
        val placeholderEntries = mutableListOf<IlocEntry>()
        placeholderEntries += IlocEntry(
            itemId = newItemId, constructionMethod = 0, dataReferenceIndex = 0,
            baseOffset = 0, extents = listOf(IlocExtent(0, 0, 0))
        )
        if (thumb != null) {
            placeholderEntries += IlocEntry(
                itemId = thumbItemId, constructionMethod = 0, dataReferenceIndex = 0,
                baseOffset = 0, extents = listOf(IlocExtent(0, 0, 0))
            )
        }
        val placeholderIloc = iloc.copy(entries = iloc.entries + placeholderEntries)
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

        // EXIF item data layout that Android's libstagefright / ExifInterface
        // expects (matches Samsung byte-for-byte):
        //   [4 bytes BE: tiff_header_offset = 6]
        //   [6 bytes:    'E' 'x' 'i' 'f' 0x00 0x00]   ← JPEG-style APP1 marker
        //   [TIFF data:  II*\0... or MM\0*...]
        //
        // We previously wrote tiff_header_offset = 0 with TIFF data starting
        // immediately after the prefix. That is also spec-compliant per
        // ISO/IEC 23008-12 Annex A.2, but Android's parser appears to assume
        // the marker is always present and skips 6 bytes blindly — landing
        // inside our TIFF data and failing to parse. Build #59's diagnostic
        // confirmed: ExifInterface returns empty on our HEIC even though
        // sips reads it. Adding the marker + adjusting the prefix to 6 fixes
        // the parser.
        val exifMarker = byteArrayOf(
            0x45.toByte(), 0x78.toByte(), 0x69.toByte(), 0x66.toByte(), 0x00, 0x00
        )
        val exifBlock = ByteBuffer.allocate(4 + exifMarker.size + exifTiff.size)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(exifMarker.size)
            .put(exifMarker)
            .put(exifTiff)
            .array()

        val thumbBlockOffset = exifBlockOffset + exifBlock.size
        val thumbBytes = thumb?.bitstream ?: ByteArray(0)

        // ----- Build final iloc with shifted offsets and the new entries -----
        fun shifted(entries: List<IlocEntry>, extra: Long): List<IlocEntry> = entries.map { entry ->
            if (entry.constructionMethod == 0) {
                if (iloc.baseOffsetSize > 0) {
                    entry.copy(baseOffset = entry.baseOffset + extra)
                } else {
                    entry.copy(extents = entry.extents.map {
                        it.copy(extentOffset = it.extentOffset + extra)
                    })
                }
            } else entry
        }
        fun makeEntry(itemId: Int, off: Long, len: Long, useBaseOffset: Boolean): IlocEntry =
            IlocEntry(
                itemId = itemId,
                constructionMethod = 0,
                dataReferenceIndex = 0,
                baseOffset = if (useBaseOffset) off else 0L,
                extents = listOf(IlocExtent(0,
                    if (useBaseOffset) 0L else off, len))
            )

        val useBO = iloc.baseOffsetSize > 0
        val shiftedEntries = shifted(iloc.entries, delta.toLong())
        val exifEntry = makeEntry(newItemId, exifBlockOffset, exifBlock.size.toLong(), useBO)
        val newEntries = mutableListOf<IlocEntry>().apply {
            add(exifEntry)
            if (thumb != null) add(makeEntry(thumbItemId, thumbBlockOffset,
                                              thumbBytes.size.toLong(), useBO))
        }
        val finalIloc = iloc.copy(entries = shiftedEntries + newEntries)

        val maxOffset = maxOf(exifBlockOffset, thumbBlockOffset)
        val maxLength = maxOf(exifBlock.size.toLong(), thumbBytes.size.toLong())
        val finalIlocAdjusted = upgradeIlocFieldSizes(finalIloc, maxOffset, maxLength)
        val newIlocBytes = buildIloc(finalIlocAdjusted)

        if (newIlocBytes.size != newIlocSizePredicted) {
            val growth = newIlocBytes.size - newIlocSizePredicted
            val newExifOff = exifBlockOffset + growth
            val newThumbOff = thumbBlockOffset + growth
            val reShifted = shifted(iloc.entries, (delta + growth).toLong())
            val useBO2 = finalIlocAdjusted.baseOffsetSize > 0
            val reExif = makeEntry(newItemId, newExifOff, exifBlock.size.toLong(), useBO2)
            val reEntries = mutableListOf<IlocEntry>().apply {
                add(reExif)
                if (thumb != null) add(makeEntry(thumbItemId, newThumbOff,
                                                  thumbBytes.size.toLong(), useBO2))
            }
            val rebuilt = finalIlocAdjusted.copy(entries = reShifted + reEntries)
            val rebuiltBytes = buildIloc(rebuilt)
            require(rebuiltBytes.size == newIlocBytes.size) { "iloc size unstable after upgrade" }
            return reorderMdatBeforeMeta(assemble(
                heicData, topBoxes, metaIdx, metaBox, subBoxes,
                newIinfBytes, newIrefBytes, irefBox, rebuiltBytes,
                newIprpBytes, newPitmBytes,
                newMetaSizeWithGrowth(newMetaSize, growth),
                exifBlock, thumbBytes
            ))
        }

        return combineMdatAndTrailing(normalizeHvcCProfile(assemble(
            heicData, topBoxes, metaIdx, metaBox, subBoxes,
            newIinfBytes, newIrefBytes, irefBox, newIlocBytes,
            newIprpBytes, newPitmBytes,
            newMetaSize, exifBlock, thumbBytes
        )))
    }

    /**
     * Like [reorderMdatBeforeMeta] but ALSO physically merges the trailing
     * Exif/thumbnail blobs into mdat so the file ends cleanly with `free`,
     * matching Samsung's `ftyp/mdat/meta/free` layout exactly.
     *
     * Why: Android's libstagefright HEIF parser (used by ExifInterface and
     * MediaScanner) does NOT follow iloc absolute offsets into a degenerate
     * trailing pseudo-box at the end of the file. Build #58's diagnostic
     * confirmed: ExifInterface returns empty on our HEIC even though sips
     * reads it fine. Putting the Exif item data inside mdat — where every
     * parser looks for media payloads — fixes this.
     *
     * Layout transformation:
     *   IN:  ftyp / meta / free / mdat / [Exif] / [thumb]
     *   OUT: ftyp / mdat'(=tiles+Exif+thumb) / meta / free
     * All iloc entries with construction_method=0 shift by -(meta_size +
     * inter_free_size). File total size is unchanged.
     */
    private fun combineMdatAndTrailing(data: ByteArray): ByteArray {
        // Use the lenient parser — once we add the JPEG-style "Exif\0\0"
        // marker to the trailing blob, its first 4 bytes parse as box-size=6
        // which the strict parser rejects. We need to walk only the WELL-
        // FORMED top-level boxes (ftyp/meta/free/mdat) and treat the rest
        // of the file as opaque trailing data.
        val top = parseTopLevelLenient(data)
        val ftyp = top.firstOrNull { it.type == "ftyp" } ?: return data
        val meta = top.firstOrNull { it.type == "meta" } ?: return data
        val mdat = top.firstOrNull { it.type == "mdat" } ?: return data
        if (mdat.offset < meta.offset) return data  // already in target order

        val betweenMetaAndMdat = top.filter {
            it.offset > meta.offset && it.offset < mdat.offset
        }
        // Trailing bytes = everything from end of mdat to end of file. The
        // lenient parser stops at the malformed Exif marker, so we infer
        // from file-size delta rather than trying to enumerate trailing
        // boxes.
        val mdatEndOffset = (mdat.offset + mdat.size).toInt()
        val trailingSize = data.size - mdatEndOffset
        if (trailingSize <= 0) {
            // No trailing blobs — fall back to plain reorder.
            return reorderMdatBeforeMeta(data)
        }

        val newMdatStart = (ftyp.offset + ftyp.size).toInt()
        val newMdatTotalSize = (mdat.size + trailingSize).toInt()
        val shift = newMdatStart - mdat.offset.toInt()  // negative

        // Patch every iloc entry whose extent currently lives at or after
        // the OLD mdat start (i.e. tile entries AND trailing Exif/thumb
        // entries) — they all need the same shift since both regions move
        // together into the new combined mdat.
        val patchedMeta = patchMetaIlocForMdatShift(
            data, meta, mdat, shift,
            extendShiftToTrailing = true
        ) ?: return data

        val out = ByteArrayOutputStream(data.size)
        // ftyp
        out.write(data, ftyp.offset.toInt(), ftyp.size.toInt())
        // new mdat header (size + "mdat")
        out.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
            .putInt(newMdatTotalSize).array())
        out.write("mdat".toByteArray(Charsets.US_ASCII))
        // old mdat content (skip its 8-byte header)
        out.write(data, mdat.offset.toInt() + 8,
                  mdat.size.toInt() - 8)
        // trailing data (Exif + thumb), contiguous, becomes part of mdat
        out.write(data, mdatEndOffset, trailingSize)
        // patched meta
        out.write(patchedMeta)
        // free (or other boxes that originally lived between meta and mdat)
        for (b in betweenMetaAndMdat) {
            out.write(data, b.offset.toInt(), b.size.toInt())
        }
        check(out.size() == data.size) {
            "combineMdatAndTrailing size mismatch: ${out.size()} vs ${data.size}"
        }
        return out.toByteArray()
    }

    /**
     * Patch hvcC's `profile_space_tier_idc` byte from "Main Still Picture"
     * (profile_idc=3, what HeifWriter writes) to "Main" (profile_idc=1, what
     * Samsung writes). Also OR in bit 30 of profile_compatibility_flags so
     * the config claims Main compatibility, matching Samsung's 0x60000000.
     *
     * This is the last structural difference between our HEIC and Samsung's
     * after the renumbering. The underlying HEVC bitstream is unaffected
     * (Main Still Picture is a strict subset of Main, so any Main decoder
     * accepts it). vivo's MediaScanner may use the hvcC config as a gate to
     * decide whether to extract EXIF.
     */
    private fun normalizeHvcCProfile(data: ByteArray): ByteArray {
        // Lenient: file may have a trailing Exif blob whose marker bytes
        // look like a malformed box header.
        val top = parseTopLevelLenient(data)
        val meta = top.firstOrNull { it.type == "meta" } ?: return data
        val subStart = meta.payloadOffset + 4
        val subEnd = meta.endOffset
        val subs = parseBoxList(data, subStart, subEnd)
        val iprp = subs.firstOrNull { it.type == "iprp" } ?: return data
        val ipsubs = parseBoxList(data, iprp.payloadOffset, iprp.endOffset)
        val ipco = ipsubs.firstOrNull { it.type == "ipco" } ?: return data
        val props = parseBoxList(data, ipco.payloadOffset, ipco.endOffset)
        val out = data.copyOf()
        var patched = 0
        for (p in props) {
            if (p.type != "hvcC") continue
            val ptiOff = (p.payloadOffset + 1).toInt()
            // Force Main profile (idc=1), preserve profile_space + tier bits.
            val orig = out[ptiOff].toInt() and 0xFF
            val keepSpaceTier = orig and 0xE0   // top 3 bits
            val newByte = (keepSpaceTier or 0x01) and 0xFF
            if (orig != newByte) {
                out[ptiOff] = newByte.toByte()
                patched++
            }
            // Also force tier=0 (Main tier) so hvcC matches Samsung's 0x01
            // exactly. HeifWriter emits 0x21 (high tier) for thumbnails.
            out[ptiOff] = (out[ptiOff].toInt() and 0x9F).toByte()
            // Set profile_compatibility_flags bit 30 (Main compat).
            val compatOff = (p.payloadOffset + 2).toInt()
            out[compatOff] = (out[compatOff].toInt() or 0x40).toByte()
        }
        return out
    }

    private fun parsePitmVersion(data: ByteArray, box: RawBox): Int =
        data[box.payloadOffset.toInt()].toInt() and 0xFF

    private fun buildPitm(version: Int, primaryItemId: Int): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(byteArrayOf(version.toByte(), 0, 0, 0)) // version + flags
        if (version == 0) writeU16(body, primaryItemId)
        else writeU32(body, primaryItemId.toLong())
        return wrapBox("pitm", body.toByteArray())
    }

    /**
     * Post-process to put `mdat` before `meta` (Samsung-style top-level
     * order). vivo's MediaScanner is the most plausible reader that demands
     * this — files written by HeifWriter ship with `meta` first, and vivo's
     * gallery does not extract make/model/GPS from such files even though
     * they are spec-compliant and read fine on macOS.
     *
     * The mechanics: any iloc extent with construction_method==0 that
     * currently points into the OLD mdat region needs its absolute
     * extent_offset shifted by `−(meta_size + free_size_between_meta_and_mdat)`
     * so it points to the same bytes at their NEW location near the start of
     * the file. iloc field widths are preserved (we never grow), so the
     * meta box stays the same size and we can swap regions wholesale.
     */
    private fun reorderMdatBeforeMeta(data: ByteArray): ByteArray {
        // Lenient: may run on an already-injected file with trailing blob.
        val top = parseTopLevelLenient(data)
        val ftyp = top.firstOrNull { it.type == "ftyp" } ?: return data
        val meta = top.firstOrNull { it.type == "meta" } ?: return data
        val mdat = top.firstOrNull { it.type == "mdat" } ?: return data
        // Already in target order? Nothing to do.
        if (mdat.offset < meta.offset) return data

        // Boxes between meta and mdat (typically a free pad). They move with
        // meta — i.e., they end up between meta and the trailing region.
        val betweenMetaAndMdat = top.filter {
            it.offset > meta.offset && it.offset < mdat.offset
        }
        // Boxes after mdat (our trailing Exif/thumb appended after mdat).
        // They keep their absolute offsets.
        val afterMdat = top.filter { it.offset >= mdat.offset + mdat.size }

        // How much do iloc tile offsets need to shift?
        val newMdatOffset = (ftyp.offset + ftyp.size).toInt()
        val mdatShift = newMdatOffset - mdat.offset.toInt()  // negative

        val patchedMetaBytes = patchMetaIlocForMdatShift(
            data, meta, mdat, mdatShift
        ) ?: return data

        // ----- Stitch -----
        val out = ByteArrayOutputStream(data.size)
        // ftyp
        out.write(data, ftyp.offset.toInt(), ftyp.size.toInt())
        // mdat (unchanged bytes, new position)
        out.write(data, mdat.offset.toInt(), mdat.size.toInt())
        // patched meta
        out.write(patchedMetaBytes)
        // boxes that originally lived between meta and mdat (e.g., free pad)
        for (b in betweenMetaAndMdat) {
            out.write(data, b.offset.toInt(), b.size.toInt())
        }
        // trailing region (Exif/thumb), unchanged
        for (b in afterMdat) {
            out.write(data, b.offset.toInt(), b.size.toInt())
        }
        // Sanity: total size unchanged.
        check(out.size() == data.size) {
            "reorder produced wrong size: ${out.size()} vs ${data.size}"
        }
        return out.toByteArray()
    }

    /**
     * Returns a fresh meta box (header + payload) where:
     *  (a) every iloc extent_offset that pointed into the old mdat range has
     *      been shifted by [mdatShift]
     *  (b) the meta sub-boxes are emitted in Samsung's canonical order
     *      (hdlr / iinf / pitm / iprp / iloc / idat / iref). HeifWriter
     *      otherwise emits them in (hdlr / iloc / iinf / pitm / iprp / idat
     *      / iref), and vivo's MediaScanner appears to depend on the former.
     *
     * Returns null if the iloc field widths cannot accommodate the shifted
     * values without growing (caller should fall back to the un-reordered
     * output).
     */
    private fun patchMetaIlocForMdatShift(
        data: ByteArray, meta: RawBox, mdat: RawBox, mdatShift: Int,
        extendShiftToTrailing: Boolean = false
    ): ByteArray? {
        val subStart = meta.payloadOffset + 4  // after meta version+flags
        val subEnd = meta.endOffset
        val subs = parseBoxList(data, subStart, subEnd)
        val ilocBox = subs.firstOrNull { it.type == "iloc" } ?: return null
        val iloc = parseIloc(data, ilocBox)

        val mdatStart = mdat.offset
        val mdatEnd = mdat.offset + mdat.size
        // When merging trailing into mdat, ALL extents at or beyond the
        // old mdat start need to shift (tiles AND Exif/thumb).
        // When just reordering, only tile extents inside the old mdat range
        // shift; trailing extents stay put.
        fun shouldShift(abs: Long): Boolean =
            if (extendShiftToTrailing) abs >= mdatStart
            else abs in mdatStart until mdatEnd
        val newEntries = iloc.entries.map { e ->
            if (e.constructionMethod != 0) return@map e
            if (iloc.baseOffsetSize > 0 && shouldShift(e.baseOffset)) {
                e.copy(baseOffset = e.baseOffset + mdatShift)
            } else {
                val newExtents = e.extents.map { x ->
                    val abs = (if (iloc.baseOffsetSize > 0) e.baseOffset else 0L) +
                                x.extentOffset
                    if (shouldShift(abs)) {
                        x.copy(extentOffset = x.extentOffset + mdatShift)
                    } else x
                }
                e.copy(extents = newExtents)
            }
        }
        val newIloc = iloc.copy(entries = newEntries)
        val newIlocBytes = buildIloc(newIloc)
        if (newIlocBytes.size != ilocBox.size.toInt()) {
            // Field widths grew (shouldn't, since we only DECREASE values),
            // but bail safely if it ever happens.
            return null
        }

        // Reorder sub-boxes to Samsung's canonical layout. Boxes whose type
        // is not in the list (rare extensions) keep their relative position
        // at the end.
        val canonical = listOf("hdlr", "iinf", "pitm", "iprp", "iloc", "idat", "iref")
        val sortedSubs = subs.sortedBy {
            val idx = canonical.indexOf(it.type)
            if (idx < 0) canonical.size else idx
        }

        // Splice the new iloc bytes into the meta box, in canonical order.
        val out = ByteArray(meta.size.toInt())
        // 1. meta header + version+flags
        System.arraycopy(data, meta.offset.toInt(), out, 0, (subStart - meta.offset).toInt())
        // 2. sub-boxes in canonical order, replacing iloc bytes
        var writePos = (subStart - meta.offset).toInt()
        for (sub in sortedSubs) {
            if (sub.type == "iloc") {
                System.arraycopy(newIlocBytes, 0, out, writePos, newIlocBytes.size)
                writePos += newIlocBytes.size
            } else {
                System.arraycopy(data, sub.offset.toInt(), out, writePos, sub.size.toInt())
                writePos += sub.size.toInt()
            }
        }
        return out
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
        newPitmBytes: ByteArray,
        newMetaSize: Int,
        exifBlock: ByteArray,
        thumbBytes: ByteArray
    ): ByteArray {
        val newMetaPayload = ByteArrayOutputStream()
        // version+flags of meta FullBox
        newMetaPayload.write(src, oldMeta.payloadOffset.toInt(), 4)
        var irefWritten = false
        for (sub in subBoxes) {
            when (sub.type) {
                "pitm" -> newMetaPayload.write(newPitmBytes)
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
        // Exif blob, then thumb bitstream (in this order so iloc offsets match).
        out.write(exifBlock)
        if (thumbBytes.isNotEmpty()) out.write(thumbBytes)
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

    /**
     * Like [parseTopLevel] but stops gracefully on malformed box headers
     * instead of throwing — for use by post-process stages that walk a
     * file containing our trailing Exif blob (whose first 4 bytes look
     * like a degenerate box of size 6 thanks to the JPEG-style "Exif\0\0"
     * prefix). Anything past the first malformed header is the caller's
     * to handle as opaque trailing data.
     */
    private fun parseTopLevelLenient(data: ByteArray): List<RawBox> {
        val out = mutableListOf<RawBox>()
        var p = 0L
        val end = data.size.toLong()
        while (p + 8 <= end) {
            val size32 = readU32(data, p)
            val type = readType(data, p + 4)
            val (size, headerSize) = when (size32) {
                1L -> {
                    if (p + 16 > end) break
                    val largeSize = readU64(data, p + 8)
                    largeSize to 16
                }
                0L -> (end - p) to 8
                else -> size32 to 8
            }
            if (size < headerSize || p + size > end) break
            out += RawBox(type, p, size, headerSize)
            p += size
        }
        return out
    }

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

    // ---------------------------------------------------------------
    // In-place repair: add the JPEG-style "Exif\0\0" marker to the
    // existing Exif item of an already-converted HEIC, without re-encoding
    // the image. Old builds wrote the Exif item as just [4-byte prefix=0]
    // [TIFF...]; Android ExifInterface needs [4-byte prefix=6][Exif\0\0]
    // [TIFF...]. We read the old item bytes, prepend the marker, append
    // the new bytes inside the existing mdat (extending it by 6 + bytes
    // for prefix change), and patch iloc to point to the new location.
    // The orphaned old bytes are left in mdat — small storage waste, but
    // structurally still valid and avoids having to relocate every other
    // iloc entry.
    // ---------------------------------------------------------------

    sealed interface RepairResult {
        data class Patched(val bytes: ByteArray, val growthBytes: Int) : RepairResult
        data object AlreadyOk : RepairResult
        data class CannotRepair(val reason: String) : RepairResult
    }

    fun repairExifMarker(heicBytes: ByteArray): RepairResult {
        val top = parseTopLevelLenient(heicBytes)
        val mdat = top.firstOrNull { it.type == "mdat" }
            ?: return RepairResult.CannotRepair("no mdat box")
        val meta = top.firstOrNull { it.type == "meta" }
            ?: return RepairResult.CannotRepair("no meta box")

        val subStart = meta.payloadOffset + 4
        val subEnd = meta.endOffset
        val subs = parseBoxList(heicBytes, subStart, subEnd)
        val iinfBox = subs.firstOrNull { it.type == "iinf" }
            ?: return RepairResult.CannotRepair("no iinf")
        val ilocBox = subs.firstOrNull { it.type == "iloc" }
            ?: return RepairResult.CannotRepair("no iloc")

        val parsedIinf = parseIinf(heicBytes, iinfBox)
        val exifEntry = parsedIinf.entries.firstOrNull { it.itemType == "Exif" }
            ?: return RepairResult.CannotRepair("no Exif item")

        val iloc = parseIloc(heicBytes, ilocBox)
        val exifIlocEntry = iloc.entries.firstOrNull { it.itemId == exifEntry.itemId }
            ?: return RepairResult.CannotRepair("no iloc entry for Exif item")
        val exifExtent = exifIlocEntry.extents.firstOrNull()
            ?: return RepairResult.CannotRepair("Exif iloc has no extent")
        if (exifIlocEntry.constructionMethod != 0) {
            return RepairResult.CannotRepair("Exif construction_method != 0")
        }

        val absStart = (if (iloc.baseOffsetSize > 0) exifIlocEntry.baseOffset else 0L) +
                        exifExtent.extentOffset
        val absEnd = absStart + exifExtent.extentLength
        if (absEnd > heicBytes.size) {
            return RepairResult.CannotRepair("Exif extent overflows file")
        }
        val itemBytes = heicBytes.copyOfRange(absStart.toInt(), absEnd.toInt())

        // Already has the marker?
        if (itemBytes.size >= 10 &&
            itemBytes[0] == 0.toByte() && itemBytes[1] == 0.toByte() &&
            itemBytes[2] == 0.toByte() && itemBytes[3] == 6.toByte() &&
            itemBytes[4] == 0x45.toByte() && itemBytes[5] == 0x78.toByte() &&
            itemBytes[6] == 0x69.toByte() && itemBytes[7] == 0x66.toByte() &&
            itemBytes[8] == 0.toByte() && itemBytes[9] == 0.toByte()) {
            return RepairResult.AlreadyOk
        }

        // Extract raw TIFF (skip whatever prefix exists).
        if (itemBytes.size < 8) return RepairResult.CannotRepair("Exif item too short")
        val prefix = readU32(itemBytes, 0L)
        val tiffStart = (4 + prefix).toInt()
        if (tiffStart >= itemBytes.size || tiffStart < 0) {
            return RepairResult.CannotRepair("bad prefix=$prefix")
        }
        val tiff = itemBytes.copyOfRange(tiffStart, itemBytes.size)

        // Build new item bytes with proper marker.
        val newItem = ByteArray(4 + 6 + tiff.size)
        newItem[3] = 6
        newItem[4] = 0x45; newItem[5] = 0x78; newItem[6] = 0x69; newItem[7] = 0x66
        // bytes 8 and 9 are already 0
        System.arraycopy(tiff, 0, newItem, 10, tiff.size)

        // The new bytes go at the END of the existing mdat (extending it).
        // Other iloc entries inside mdat keep their offsets — they're at the
        // same absolute positions. The old Exif item bytes are orphaned (no
        // iloc points to them anymore), but stay in the file.
        val insertPos = (mdat.offset + mdat.size).toInt()
        val newExifAbsOffset = insertPos.toLong()
        val newExifLength = newItem.size.toLong()

        // Patch iloc: Exif extent now points to new location, length = new size.
        val newIlocEntries = iloc.entries.map { e ->
            if (e.itemId == exifEntry.itemId) {
                e.copy(
                    baseOffset = 0L,
                    extents = listOf(IlocExtent(0L, newExifAbsOffset, newExifLength))
                )
            } else e
        }
        // Make sure offset/length field widths can hold the new values.
        val upgraded = upgradeIlocFieldSizes(
            iloc.copy(entries = newIlocEntries),
            extraOffset = newExifAbsOffset,
            extraLength = newExifLength
        )
        val newIlocBytes = buildIloc(upgraded)
        if (newIlocBytes.size != ilocBox.size.toInt()) {
            // Field widths grew; we'd have to re-layout meta. Out of scope for
            // the in-place fast path. Caller can fall back to full re-encode.
            return RepairResult.CannotRepair(
                "iloc field widths need to grow (${ilocBox.size} -> ${newIlocBytes.size})"
            )
        }

        // Build output: original bytes through end of old mdat content, then
        // the new Exif item bytes, then everything that came after old mdat
        // (meta, free, etc.). Update mdat header size.
        val out = ByteArray(heicBytes.size + newItem.size)
        System.arraycopy(heicBytes, 0, out, 0, insertPos)
        System.arraycopy(newItem, 0, out, insertPos, newItem.size)
        System.arraycopy(heicBytes, insertPos, out, insertPos + newItem.size,
                         heicBytes.size - insertPos)

        // Update mdat box size (4 bytes BE at mdat.offset).
        val newMdatSize = (mdat.size + newItem.size).toInt()
        val mdatSizePos = mdat.offset.toInt()
        out[mdatSizePos]     = ((newMdatSize ushr 24) and 0xFF).toByte()
        out[mdatSizePos + 1] = ((newMdatSize ushr 16) and 0xFF).toByte()
        out[mdatSizePos + 2] = ((newMdatSize ushr 8) and 0xFF).toByte()
        out[mdatSizePos + 3] = (newMdatSize and 0xFF).toByte()

        // Splice patched iloc back. The iloc box itself moved forward by
        // newItem.size bytes (it lives in meta, which is after mdat).
        val newIlocPos = ilocBox.offset.toInt() + newItem.size
        System.arraycopy(newIlocBytes, 0, out, newIlocPos, newIlocBytes.size)

        return RepairResult.Patched(out, newItem.size)
    }
}
